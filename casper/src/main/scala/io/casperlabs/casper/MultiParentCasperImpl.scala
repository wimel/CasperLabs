package io.casperlabs.casper

import cats.Applicative
import cats.effect.Sync
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.Validate.ValidateErrorWrapper
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.comm.CommUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.rholang._
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import io.casperlabs.models.BlockMetadata
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService

/**
  Encapsulates mutable state of the MultiParentCasperImpl

  @param seenBlockHashes - tracks hashes of all blocks seen so far
  @param blockBuffer
  @param deployHistory
  @param invalidBlockTracker
  @param equivocationsTracker: Used to keep track of when other validators detect the equivocation consisting of the base block at the sequence number identified by the (validator, base equivocation sequence number) pair of each EquivocationRecord.
  */
final case class CasperState(
    seenBlockHashes: Set[BlockHash] = Set.empty[BlockHash],
    blockBuffer: Set[BlockMessage] = Set.empty[BlockMessage],
    deployHistory: Set[Deploy] = Set.empty[Deploy],
    invalidBlockTracker: Set[BlockHash] = Set.empty[BlockHash],
    dependencyDag: DoublyLinkedDag[BlockHash] = BlockDependencyDag.empty,
    equivocationsTracker: Set[EquivocationRecord] = Set.empty[EquivocationRecord],
    //TODO: store this info in the BlockDagRepresentation instead
    transforms: Map[BlockHash, Seq[ipc.TransformEntry]] =
      Map.empty[BlockHash, Seq[ipc.TransformEntry]]
)

class MultiParentCasperImpl[F[_]: Sync: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: BlockStore: RPConfAsk: BlockDagStorage: ExecutionEngineService](
    validatorId: Option[ValidatorIdentity],
    genesis: BlockMessage,
    postGenesisStateHash: StateHash,
    shardId: String,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f
)(implicit state: Cell[F, CasperState])
    extends MultiParentCasper[F] {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  type Validator = ByteString

  //TODO: Extract hardcoded version
  private val version = 1L

  private val lastFinalizedBlockHashContainer = Ref.unsafe[F, BlockHash](genesis.blockHash)

  def addBlock(
      b: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  ): F[BlockStatus] =
    Sync[F].bracket(blockProcessingLock.acquire)(
      _ =>
        for {
          dag            <- blockDag
          blockHash      = b.blockHash
          containsResult <- dag.contains(blockHash)
          s              <- Cell[F, CasperState].read
          result <- if (containsResult || s.seenBlockHashes.contains(blockHash)) {
                     Log[F]
                       .info(
                         s"Block ${PrettyPrinter.buildString(b.blockHash)} has already been processed by another thread."
                       )
                       .map(_ => BlockStatus.processing)
                   } else {
                     (validatorId match {
                       case Some(ValidatorIdentity(publicKey, _, _)) =>
                         val sender = ByteString.copyFrom(publicKey)
                         handleDoppelganger(b, sender)
                       case None => ().pure[F]
                     }) *> Cell[F, CasperState].modify { s =>
                       s.copy(seenBlockHashes = s.seenBlockHashes + b.blockHash)
                     } *> internalAddBlock(b, dag)
                   }
        } yield result
    )(_ => blockProcessingLock.release)

  private def internalAddBlock(
      b: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[BlockStatus] =
    for {
      validFormat            <- Validate.formatOfFields[F](b)
      validSig               <- Validate.blockSignature[F](b)
      validSender            <- Validate.blockSender[F](b, genesis, dag)
      validVersion           <- Validate.version[F](b, version)
      lastFinalizedBlockHash <- lastFinalizedBlockHashContainer.get
      attemptResult <- if (!validFormat) (InvalidUnslashableBlock, dag).pure[F]
                      else if (!validSig) (InvalidUnslashableBlock, dag).pure[F]
                      else if (!validSender) (InvalidUnslashableBlock, dag).pure[F]
                      else if (!validVersion) (InvalidUnslashableBlock, dag).pure[F]
                      else attemptAdd(b, dag, lastFinalizedBlockHash)
      (attempt, updatedDag) = attemptResult
      _ <- attempt match {
            case MissingBlocks => ().pure[F]
            case _ =>
              Cell[F, CasperState].modify { s =>
                s.copy(
                  blockBuffer = s.blockBuffer - b,
                  dependencyDag = DoublyLinkedDagOperations.remove(s.dependencyDag, b.blockHash)
                )
              }
          }
      _ <- attempt match {
            case MissingBlocks           => ().pure[F]
            case IgnorableEquivocation   => ().pure[F]
            case InvalidUnslashableBlock => ().pure[F]
            case _ =>
              reAttemptBuffer(updatedDag, lastFinalizedBlockHash) // reAttempt for any status that resulted in the adding of the block into the view
          }
      estimates <- estimator(updatedDag)
      _ <- Log[F].debug(
            s"Tip estimates: ${estimates.map(x => PrettyPrinter.buildString(x.blockHash)).mkString(", ")}"
          )
      tip                           = estimates.head
      _                             <- Log[F].info(s"New fork-choice tip is block ${PrettyPrinter.buildString(tip.blockHash)}.")
      lastFinalizedBlockHash        <- lastFinalizedBlockHashContainer.get
      updatedLastFinalizedBlockHash <- updateLastFinalizedBlock(updatedDag, lastFinalizedBlockHash)
      _                             <- lastFinalizedBlockHashContainer.set(updatedLastFinalizedBlockHash)
      _ <- Log[F].info(
            s"New last finalized block hash is ${PrettyPrinter.buildString(updatedLastFinalizedBlockHash)}."
          )
    } yield attempt

  private def updateLastFinalizedBlock(
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[BlockHash] =
    for {
      childrenHashes <- dag
                         .children(lastFinalizedBlockHash)
                         .map(_.getOrElse(Set.empty[BlockHash]).toList)
      // Find all finalized children so that we can get rid of their deploys.
      finalizedChildren <- ListContrib.filterM(
                            childrenHashes,
                            (blockHash: BlockHash) =>
                              isGreaterThanFaultToleranceThreshold(dag, blockHash)
                          )
      newFinalizedBlock <- if (finalizedChildren.isEmpty) {
                            lastFinalizedBlockHash.pure[F]
                          } else {
                            finalizedChildren.traverse { childHash =>
                              for {
                                removed <- removeDeploysInBlock(childHash)
                                _ <- Log[F].info(
                                      s"Removed $removed deploys from deploy history as we finalized block ${PrettyPrinter
                                        .buildString(childHash)}."
                                    )
                                finalizedHash <- updateLastFinalizedBlock(dag, childHash)
                              } yield finalizedHash
                            } map (_.head)
                          }
    } yield newFinalizedBlock

  /** Remove deploys from the history which are included in a just finalised block. */
  private def removeDeploysInBlock(blockHash: BlockHash): F[Int] =
    for {
      b                  <- ProtoUtil.unsafeGetBlock[F](blockHash)
      deploysToRemove    = b.body.get.deploys.map(_.deploy.get).toSet
      stateBefore        <- Cell[F, CasperState].read
      initialHistorySize = stateBefore.deployHistory.size
      _ <- Cell[F, CasperState].modify { s =>
            s.copy(deployHistory = s.deployHistory.filterNot(deploysToRemove))
          }
      stateAfter     <- Cell[F, CasperState].read
      deploysRemoved = initialHistorySize - stateAfter.deployHistory.size
    } yield deploysRemoved

  /*
   * On the first pass, block B is finalized if B's main parent block is finalized
   * and the safety oracle says B's normalized fault tolerance is above the threshold.
   * On the second pass, block B is finalized if any of B's children blocks are finalized.
   *
   * TODO: Implement the second pass in BlockAPI
   */
  private def isGreaterThanFaultToleranceThreshold(
      dag: BlockDagRepresentation[F],
      blockHash: BlockHash
  ): F[Boolean] =
    for {
      faultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, blockHash)
      _ <- Log[F].info(
            s"Fault tolerance for block ${PrettyPrinter.buildString(blockHash)} is $faultTolerance; threshold is $faultToleranceThreshold"
          )
    } yield faultTolerance > faultToleranceThreshold

  def contains(
      b: BlockMessage
  ): F[Boolean] =
    for {
      blockStoreContains <- BlockStore[F].contains(b.blockHash)
      state              <- Cell[F, CasperState].read
      bufferContains     = state.blockBuffer.exists(_.blockHash == b.blockHash)
    } yield (blockStoreContains || bufferContains)

  //TODO: `Deploy` is now redundant with `DeployData`, so we should remove it.
  //The reason we needed both in RChain was because rholang was submitted as source
  //code and parsed by the node. Now we just accept wasm code directly. We still need
  //to verify the wasm code for correctness though.
  //TODO: verify wasm code correctness (done in rust but should be immediate so that we fail fast)
  //TODO: verify sig immediately (again, so we fail fast)
  def deploy(d: DeployData): F[Either[Throwable, Unit]] =
    addDeploy(Deploy(d.sessionCode, Some(d))).map(_ => Right(()))

  def addDeploy(deploy: Deploy): F[Unit] =
    for {
      _ <- Cell[F, CasperState].modify { s =>
            s.copy(deployHistory = s.deployHistory + deploy)
          }
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(deploy)}")
    } yield ()

  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockMessage]] =
    for {
      lastFinalizedBlockHash <- lastFinalizedBlockHashContainer.get
      rankedEstimates        <- Estimator.tips[F](dag, lastFinalizedBlockHash)
    } yield rankedEstimates

  /*
   * Logic:
   *  -Score each of the blockDAG heads extracted from the block messages via GHOST
   *  -Let P = subset of heads such that P contains no conflicts and the total score is maximized
   *  -Let R = subset of deploy messages which are not included in DAG obtained by following blocks in P
   *  -If R is non-empty then create a new block with parents equal to P and (non-conflicting) txns obtained from R
   *  -Else if R is empty and |P| > 1 then create a block with parents equal to P and no transactions
   *  -Else None
   *
   *  TODO: Make this return Either so that we get more information about why not block was
   *  produced (no deploys, already processing, no validator id)
   */
  def createBlock: F[CreateBlockStatus] = validatorId match {
    case Some(ValidatorIdentity(publicKey, privateKey, sigAlgorithm)) =>
      for {
        dag          <- blockDag
        orderedHeads <- estimator(dag)
        p            <- chooseNonConflicting[F](orderedHeads, genesis, dag)
        _ <- Log[F].info(
              s"${p.size} parents out of ${orderedHeads.size} latest blocks will be used."
            )
        r                <- remainingDeploys(dag, p)
        bondedValidators = bonds(p.head).map(_.validator).toSet
        //We ensure that only the justifications given in the block are those
        //which are bonded validators in the chosen parent. This is safe because
        //any latest message not from a bonded validator will not change the
        //final fork-choice.
        latestMessages <- dag.latestMessages
        justifications = toJustification(latestMessages)
          .filter(j => bondedValidators.contains(j.validator))
        proposal <- if (r.nonEmpty || p.length > 1) {
                     createProposal(dag, p, r, justifications)
                   } else {
                     CreateBlockStatus.noNewDeploys.pure[F]
                   }
        signedBlock <- proposal match {
                        case Created(blockMessage) =>
                          signBlock(blockMessage, dag, publicKey, privateKey, sigAlgorithm, shardId)
                            .map(Created.apply)
                        case _ => proposal.pure[F]
                      }
      } yield signedBlock
    case None => CreateBlockStatus.readOnlyMode.pure[F]
  }

  def lastFinalizedBlock: F[BlockMessage] =
    for {
      lastFinalizedBlockHash <- lastFinalizedBlockHashContainer.get
      blockMessage           <- ProtoUtil.unsafeGetBlock[F](lastFinalizedBlockHash)
    } yield blockMessage

  // TODO: Optimize for large number of deploys accumulated over history
  private def remainingDeploys(
      dag: BlockDagRepresentation[F],
      p: Seq[BlockMessage]
  ): F[Seq[Deploy]] =
    for {
      state <- Cell[F, CasperState].read
      hist  = state.deployHistory
      d <- DagOperations
            .bfTraverseF[F, BlockMessage](p.toList)(ProtoUtil.unsafeGetParents[F])
            .map { b =>
              b.body
                .map(_.deploys.flatMap(_.deploy))
                .toSeq
                .flatten
            }
            .toList
      deploy = d.flatten.toSet
      result = (hist.diff(deploy)).toSeq
    } yield result

  //TODO: Need to specify SEQ vs PAR type block?
  private def createProposal(
      dag: BlockDagRepresentation[F],
      p: Seq[BlockMessage],
      r: Seq[Deploy],
      justifications: Seq[Justification]
  ): F[CreateBlockStatus] =
    (for {
      now <- Time[F].currentMillis
      s   <- Cell[F, CasperState].read
      //temporary function for getting transforms for blocks
      f = (b: BlockMetadata) =>
        s.transforms.getOrElse(b.blockHash, Seq.empty[ipc.TransformEntry]).pure[F]
      processedHash <- ExecEngineUtil.processDeploys(
                        p,
                        dag,
                        r,
                        f
                      )
      (preStateHash, processedDeploys) = processedHash
      deployLookup                     = processedDeploys.zip(r).toMap
      commutingEffects                 = ExecEngineUtil.findCommutingEffects(processedDeploys)
      deploysForBlock = commutingEffects.map {
        case (eff, cost) => {
          val deploy = deployLookup(
            ipc.DeployResult(
              cost,
              ipc.DeployResult.Result.Effects(eff)
            )
          )
          protocol.ProcessedDeploy(
            Some(deploy),
            cost,
            false
          )
        }
      }
      maxBlockNumber = p.foldLeft(-1L) {
        case (acc, b) => math.max(acc, blockNumber(b))
      }
      //TODO: compute bonds properly
      newBonds              = ProtoUtil.bonds(p.head)
      transforms            = commutingEffects.unzip._1.flatMap(_.transformMap)
      possiblePostStateHash <- ExecutionEngineService[F].commit(preStateHash, transforms)
      postStateHash <- possiblePostStateHash match {
                        case Left(ex)    => Sync[F].raiseError(ex)
                        case Right(hash) => hash.pure[F]
                      }
      number = maxBlockNumber + 1
      postState = RChainState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(postStateHash)
        .withBonds(newBonds)
        .withBlockNumber(number)

      body = Body()
        .withState(postState)
        .withDeploys(deploysForBlock)
      header = blockHeader(body, p.map(_.blockHash), version, now)
      block  = unsignedBlockProto(body, header, justifications, shardId)

      msgBody = transforms
        .map(t => {
          val k    = PrettyPrinter.buildString(t.key.get)
          val tStr = PrettyPrinter.buildString(t.transform.get)
          s"$k :: $tStr"
        })
        .mkString("\n")
      _ <- Log[F]
            .info(s"Block #$number created with effects:\n$msgBody")
    } yield CreateBlockStatus.created(block)).handleErrorWith(
      ex =>
        Log[F]
          .error(
            s"Critical error encountered while processing deploys: ${ex.getMessage}"
          )
          .map(_ => CreateBlockStatus.internalDeployError(ex))
    )

  def blockDag: F[BlockDagRepresentation[F]] =
    BlockDagStorage[F].getRepresentation

  def storageContents(hash: StateHash): F[String] =
    """""".pure[F]

  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    for {
      state   <- Cell[F, CasperState].read
      tracker = state.equivocationsTracker
    } yield
      (tracker
        .map(_.equivocator)
        .flatMap(weights.get)
        .sum
        .toFloat / weightMapTotal(weights))

  implicit val functorRaiseInvalidBlock = Validate.raiseValidateErrorThroughSync[F]

  /*
   * TODO: Pass in blockDag. We should only call _blockDag.get at one location.
   * This would require returning the updated block DAG with the block status.
   *
   * We want to catch equivocations only after we confirm that the block completing
   * the equivocation is otherwise valid.
   */
  private def attemptAdd(
      b: BlockMessage,
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[(BlockStatus, BlockDagRepresentation[F])] = {
    val validationStatus = (for {
      _ <- Log[F].info(s"Attempting to add Block ${PrettyPrinter.buildString(b.blockHash)} to DAG.")
      postValidationStatus <- Validate
                               .blockSummary[F](b, genesis, dag, shardId, lastFinalizedBlockHash)
      s <- Cell[F, CasperState].read
      //temporary function for getting transforms for blocks
      f = (b: BlockMetadata) =>
        Sync[F].delay(
          s.transforms
            .getOrElse(b.blockHash, Seq.empty[ipc.TransformEntry])
        )
      processedHash <- ExecEngineUtil
                        .effectsForBlock(b, dag, f)
                        .recoverWith {
                          case _ => FunctorRaise[F, InvalidBlock].raise(InvalidTransaction)
                        }
      (preStateHash, blockEffects) = processedHash
      _ <- Validate.transactions[F](
            b,
            dag,
            preStateHash,
            blockEffects
          )
      _ <- Validate.bondsCache[F](b, ProtoUtil.bonds(genesis))
      _ <- Validate
            .neglectedInvalidBlock[F](
              b,
              s.invalidBlockTracker
            )
      _ <- EquivocationDetector
            .checkNeglectedEquivocationsWithUpdate[F](
              b,
              dag,
              genesis
            )
      _ <- EquivocationDetector.checkEquivocations[F](s.dependencyDag, b, dag)
    } yield blockEffects).attempt

    validationStatus.flatMap {
      case Right(effects) =>
        addEffects(Valid, b, effects, dag).tupleLeft(Valid)
      case Left(ValidateErrorWrapper(invalid)) =>
        addEffects(invalid, b, Seq.empty, dag)
          .tupleLeft(invalid)
      case Left(unexpected) =>
        for {
          _ <- Log[F].error(
                s"Unexpected exception during validation of the block ${Base16.encode(b.blockHash.toByteArray)}",
                unexpected
              )
          _ <- Sync[F].raiseError[BlockStatus](unexpected)
        } yield (BlockException(unexpected), dag)
    }
  }

  // TODO: Handle slashing
  private def addEffects(
      status: BlockStatus,
      block: BlockMessage,
      transforms: Seq[ipc.TransformEntry],
      dag: BlockDagRepresentation[F]
  ): F[BlockDagRepresentation[F]] =
    status match {
      //Add successful! Send block to peers, log success, try to add other blocks
      case Valid =>
        for {
          updatedDag <- addToState(block, transforms)
          _          <- CommUtil.sendBlock[F](block)
          _ <- Log[F].info(
                s"Added ${PrettyPrinter.buildString(block.blockHash)}"
              )
        } yield updatedDag
      case MissingBlocks =>
        Cell[F, CasperState].modify { s =>
          s.copy(blockBuffer = s.blockBuffer + block)
        } *> fetchMissingDependencies(block) *> dag.pure[F]

      case AdmissibleEquivocation =>
        val baseEquivocationBlockSeqNum = block.seqNum - 1
        for {
          _ <- Cell[F, CasperState].modify { s =>
                if (s.equivocationsTracker.exists {
                      case EquivocationRecord(validator, seqNum, _) =>
                        block.sender == validator && baseEquivocationBlockSeqNum == seqNum
                    }) {
                  // More than 2 equivocating children from base equivocation block and base block has already been recorded
                  s
                } else {
                  val newEquivocationRecord =
                    EquivocationRecord(
                      block.sender,
                      baseEquivocationBlockSeqNum,
                      Set.empty[BlockHash]
                    )
                  s.copy(equivocationsTracker = s.equivocationsTracker + newEquivocationRecord)
                }
              }
          updatedDag <- addToState(block, transforms)
          _          <- CommUtil.sendBlock[F](block)
          _ <- Log[F].info(
                s"Added admissible equivocation child block ${PrettyPrinter.buildString(block.blockHash)}"
              )
        } yield updatedDag
      case IgnorableEquivocation =>
        /*
         * We don't have to include these blocks to the equivocation tracker because if any validator
         * will build off this side of the equivocation, we will get another attempt to add this block
         * through the admissible equivocations.
         */
        Log[F]
          .info(
            s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the BlockDAG"
          ) *> dag.pure[F]
      case InvalidUnslashableBlock =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidFollows =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidBlockNumber =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidParents =>
        handleInvalidBlockEffect(status, block, transforms)
      case JustificationRegression =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidSequenceNumber =>
        handleInvalidBlockEffect(status, block, transforms)
      case NeglectedInvalidBlock =>
        handleInvalidBlockEffect(status, block, transforms)
      case NeglectedEquivocation =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidTransaction =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidBondsCache =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidRepeatDeploy =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidShardId =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidBlockHash =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidDeployCount =>
        handleInvalidBlockEffect(status, block, transforms)
      case Processing =>
        throw new RuntimeException(s"A block should not be processing at this stage.")
      case BlockException(ex) =>
        Log[F].error(s"Encountered exception in while processing block ${PrettyPrinter
          .buildString(block.blockHash)}: ${ex.getMessage}") *> dag.pure[F]
    }

  private def fetchMissingDependencies(
      b: BlockMessage
  ): F[Unit] =
    for {
      dag <- blockDag
      missingDependencies <- dependenciesHashesOf(b)
                              .filterA(
                                blockHash =>
                                  dag
                                    .lookup(blockHash)
                                    .map(_.isEmpty)
                              )
      state <- Cell[F, CasperState].read
      missingUnseenDependencies = missingDependencies.filter(
        blockHash => !state.seenBlockHashes.contains(blockHash)
      )
      _ <- missingDependencies.traverse(hash => handleMissingDependency(hash, b))
      _ <- missingUnseenDependencies.traverse(hash => requestMissingDependency(hash))
    } yield ()

  private def handleMissingDependency(hash: BlockHash, childBlock: BlockMessage): F[Unit] =
    Cell[F, CasperState].modify(
      s =>
        s.copy(
          dependencyDag = DoublyLinkedDagOperations
            .add[BlockHash](s.dependencyDag, hash, childBlock.blockHash)
        )
    )

  private def requestMissingDependency(hash: BlockHash) =
    CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))

  private def handleInvalidBlockEffect(
      status: BlockStatus,
      block: BlockMessage,
      effects: Seq[ipc.TransformEntry]
  ): F[BlockDagRepresentation[F]] =
    for {
      _ <- Log[F].warn(
            s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
          )
      // TODO: Slash block for status except InvalidUnslashableBlock
      _ <- Cell[F, CasperState].modify { s =>
            s.copy(invalidBlockTracker = s.invalidBlockTracker + block.blockHash)
          }
      updateDag <- addToState(block, effects)
    } yield updateDag

  private def addToState(
      block: BlockMessage,
      effects: Seq[ipc.TransformEntry]
  ): F[BlockDagRepresentation[F]] =
    for {
      _          <- BlockStore[F].put(block.blockHash, block)
      updatedDag <- BlockDagStorage[F].insert(block)
      hash       = block.blockHash
      _ <- Cell[F, CasperState].modify { s =>
            s.copy(transforms = s.transforms + (hash -> effects))
          }
    } yield updatedDag

  private def reAttemptBuffer(
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[Unit] =
    for {
      state          <- Cell[F, CasperState].read
      dependencyFree = state.dependencyDag.dependencyFree
      dependencyFreeBlocks = state.blockBuffer
        .filter(block => dependencyFree.contains(block.blockHash))
        .toList
      attemptsWithDag <- dependencyFreeBlocks.foldM(
                          (
                            List.empty[(BlockMessage, (BlockStatus, BlockDagRepresentation[F]))],
                            dag
                          )
                        ) {
                          case ((attempts, updatedDag), b) =>
                            for {
                              status <- attemptAdd(b, updatedDag, lastFinalizedBlockHash)
                            } yield ((b, status) :: attempts, status._2)
                        }
      (attempts, updatedDag) = attemptsWithDag
      _ <- if (attempts.isEmpty) {
            ().pure[F]
          } else {
            for {
              _ <- removeAdded(state.dependencyDag, attempts)
              _ <- reAttemptBuffer(updatedDag, lastFinalizedBlockHash)
            } yield ()
          }
    } yield ()

  private def removeAdded(
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      attempts: List[(BlockMessage, (BlockStatus, BlockDagRepresentation[F]))]
  ): F[Unit] =
    for {
      successfulAdds <- attempts
                         .filter {
                           case (_, (status, _)) => status.inDag
                         }
                         .pure[F]
      _ <- unsafeRemoveFromBlockBuffer(successfulAdds)
      _ <- removeFromBlockBufferDependencyDag(blockBufferDependencyDag, successfulAdds)
    } yield ()

  private def unsafeRemoveFromBlockBuffer(
      successfulAdds: List[(BlockMessage, (BlockStatus, BlockDagRepresentation[F]))]
  ): F[Unit] = {
    val addedBlocks = successfulAdds.map(_._1)
    Cell[F, CasperState].modify { s =>
      s.copy(blockBuffer = s.blockBuffer -- addedBlocks)
    }
  }

  private def removeFromBlockBufferDependencyDag(
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      successfulAdds: List[(BlockMessage, (BlockStatus, BlockDagRepresentation[F]))]
  ): F[Unit] =
    Cell[F, CasperState].modify { s =>
      s.copy(dependencyDag = successfulAdds.foldLeft(blockBufferDependencyDag) {
        case (acc, successfulAdd) =>
          DoublyLinkedDagOperations.remove(acc, successfulAdd._1.blockHash)
      })
    }

  //TODO: Delete this method
  def getRuntimeManager: F[Option[RuntimeManager[F]]] =
    Applicative[F].pure(None)

  def fetchDependencies: F[Unit] =
    for {
      s <- Cell[F, CasperState].read
      _ <- s.dependencyDag.dependencyFree.toList.traverse { hash =>
            CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))
          }
    } yield ()
}
