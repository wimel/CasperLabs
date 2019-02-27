package io.casperlabs.casper.helper

import java.nio.file.{Files, Path, Paths}

import cats.{Applicative, ApplicativeError, Id, Monad}
import cats.data.EitherT
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.catscontrib.ski._
import io.casperlabs.blockstorage._
import io.casperlabs.casper.LastApprovedBlock.LastApprovedBlock
import io.casperlabs.casper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.comm.CasperPacketHandler.{
  ApprovedBlockReceivedHandler,
  CasperPacketHandlerImpl,
  CasperPacketHandlerInternal
}
import io.casperlabs.casper.util.comm.TransportLayerTestImpl
import io.casperlabs.casper.util.rholang.{InterpreterUtil, RuntimeManager}
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm._
import io.casperlabs.comm.CommError.{CommErrT, ErrorHandler}
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.Connect
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp.HandleMessages.handle
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.p2p.effects.PacketHandler
import io.casperlabs.shared.{Cell, Log}
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.casper.helper.BlockDagStorageTestFixture.mapSize
import io.casperlabs.ipc
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Random

class HashSetCasperTestNode[F[_]](
    name: String,
    val local: PeerNode,
    tle: TransportLayerTestImpl[F],
    val genesis: BlockMessage,
    sk: Array[Byte],
    logicalTime: LogicalTime[F],
    implicit val errorHandlerEff: ErrorHandler[F],
    storageSize: Long,
    val blockDagDir: Path,
    val blockStoreDir: Path,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f,
    shardId: String = "casperlabs"
)(
    implicit
    concurrentF: Concurrent[F],
    val blockStore: BlockStore[F],
    val blockDagStorage: BlockDagStorage[F],
    val metricEff: Metrics[F],
    val abF: ToAbstractContext[F],
    val casperState: Cell[F, CasperState]
) {

  private val storageDirectory = Files.createTempDirectory(s"hash-set-casper-test-$name")

  implicit val logEff             = new LogStub[F]
  implicit val timeEff            = logicalTime
  implicit val connectionsCell    = Cell.unsafe[F, Connections](Connect.Connections.empty)
  implicit val transportLayerEff  = tle
  implicit val cliqueOracleEffect = SafetyOracle.cliqueOracle[F]
  implicit val rpConfAsk          = createRPConfAsk[F](local)

  val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[F]()

  val bonds = genesis.body
    .flatMap(_.state.map(_.bonds.map(b => b.validator.toByteArray -> b.stake).toMap))
    .getOrElse(Map.empty)

  val runtimeManager = RuntimeManager(casperSmartContractsApi, bonds)
  val defaultTimeout = FiniteDuration(1000, MILLISECONDS)

  val validatorId = ValidatorIdentity(Ed25519.toPublic(sk), sk, "ed25519")

  val approvedBlock = ApprovedBlock(candidate = Some(ApprovedBlockCandidate(block = Some(genesis))))

  implicit val labF        = LastApprovedBlock.unsafe[F](Some(approvedBlock))
  val postGenesisStateHash = ProtoUtil.postStateHash(genesis)

  implicit val ee = runtimeManager.executionEngineService
  implicit val casperEff = new MultiParentCasperImpl[F](
    Some(validatorId),
    genesis,
    postGenesisStateHash,
    shardId,
    blockProcessingLock,
    faultToleranceThreshold = faultToleranceThreshold
  )

  implicit val multiparentCasperRef = MultiParentCasperRef.unsafe[F](Some(casperEff))

  val handlerInternal = new ApprovedBlockReceivedHandler(casperEff, approvedBlock)
  val casperPacketHandler =
    new CasperPacketHandlerImpl[F](Ref.unsafe[F, CasperPacketHandlerInternal[F]](handlerInternal))
  implicit val packetHandlerEff = PacketHandler.pf[F](
    casperPacketHandler.handle
  )

  def initialize(): F[Unit] =
    // pre-population removed from internals of Casper
    blockStore.put(genesis.blockHash, genesis) *>
      blockDagStorage.getRepresentation.flatMap { dag =>
        InterpreterUtil
          .validateBlockCheckpoint[F](
            genesis,
            dag,
            runtimeManager
          )
          .void
      }

  def receive(): F[Unit] = tle.receive(p => handle[F](p, defaultTimeout), kp(().pure[F]))

  def tearDown(): F[Unit] =
    tearDownNode().map { _ =>
      blockStoreDir.recursivelyDelete()
      blockDagDir.recursivelyDelete()
    }

  def tearDownNode(): F[Unit] =
    for {
      _ <- blockStore.close()
      _ <- blockDagStorage.close()
    } yield ()
}

object HashSetCasperTestNode {
  type Effect[A] = EitherT[Task, CommError, A]

  implicit val absF = new ToAbstractContext[Effect] {
    def fromTask[A](fa: Task[A]): Effect[A] = new MonadOps(fa).liftM[CommErrT]
  }

  def standaloneF[F[_]](
      genesis: BlockMessage,
      sk: Array[Byte],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit
      errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F],
      absF: ToAbstractContext[F]
  ): F[HashSetCasperTestNode[F]] = {
    val name     = "standalone"
    val identity = peerNode(name, 40400)
    val tle =
      new TransportLayerTestImpl[F](identity, Map.empty[PeerNode, Ref[F, mutable.Queue[Protocol]]])
    val logicalTime: LogicalTime[F] = new LogicalTime[F]
    implicit val log                = new Log.NOPLog[F]()
    implicit val metricEff          = new Metrics.MetricsNOP[F]

    val blockDagDir   = BlockDagStorageTestFixture.blockDagStorageDir
    val blockStoreDir = BlockDagStorageTestFixture.blockStorageDir
    val env           = Context.env(blockStoreDir, mapSize)
    for {
      blockStore <- FileLMDBIndexBlockStore.create[F](env, blockStoreDir).map(_.right.get)
      blockDagStorage <- BlockDagFileStorage.createEmptyFromGenesis[F](
                          BlockDagFileStorage.Config(
                            blockDagDir.resolve("latest-messages-data"),
                            blockDagDir.resolve("latest-messages-crc"),
                            blockDagDir.resolve("block-metadata-data"),
                            blockDagDir.resolve("block-metadata-crc"),
                            blockDagDir.resolve("checkpoints")
                          ),
                          genesis
                        )(Concurrent[F], Log[F], blockStore)
      blockProcessingLock <- Semaphore[F](1)
      casperState         <- Cell.mvarCell[F, CasperState](CasperState())
      node = new HashSetCasperTestNode[F](
        name,
        identity,
        tle,
        genesis,
        sk,
        logicalTime,
        errorHandler,
        storageSize,
        blockDagDir,
        blockStoreDir,
        blockProcessingLock,
        faultToleranceThreshold
      )(
        concurrentF,
        blockStore,
        blockDagStorage,
        metricEff,
        absF,
        casperState
      )
      result <- node.initialize.map(_ => node)
    } yield result
  }

  def standaloneEff(
      genesis: BlockMessage,
      sk: Array[Byte],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit scheduler: Scheduler
  ): HashSetCasperTestNode[Effect] =
    standaloneF[Effect](genesis, sk, storageSize, faultToleranceThreshold)(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect],
      ToAbstractContext[Effect]
    ).value.unsafeRunSync.right.get

  def networkF[F[_]](
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F],
      absF: ToAbstractContext[F]
  ): F[IndexedSeq[HashSetCasperTestNode[F]]] = {
    val n     = sks.length
    val names = (1 to n).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))
    val msgQueues = peers
      .map(_ -> new mutable.Queue[Protocol]())
      .toMap
      .mapValues(Ref.unsafe[F, mutable.Queue[Protocol]])
    val logicalTime: LogicalTime[F] = new LogicalTime[F]

    val nodesF =
      names
        .zip(peers)
        .zip(sks)
        .toList
        .traverse {
          case ((n, p), sk) =>
            val tle                = new TransportLayerTestImpl[F](p, msgQueues)
            implicit val log       = new Log.NOPLog[F]()
            implicit val metricEff = new Metrics.MetricsNOP[F]

            val blockDagDir   = BlockDagStorageTestFixture.blockDagStorageDir
            val blockStoreDir = BlockDagStorageTestFixture.blockStorageDir
            val env           = Context.env(blockStoreDir, mapSize)
            for {
              blockStore <- FileLMDBIndexBlockStore.create[F](env, blockStoreDir).map(_.right.get)
              blockDagStorage <- BlockDagFileStorage.createEmptyFromGenesis[F](
                                  BlockDagFileStorage.Config(
                                    blockDagDir.resolve("latest-messages-data"),
                                    blockDagDir.resolve("latest-messages-crc"),
                                    blockDagDir.resolve("block-metadata-data"),
                                    blockDagDir.resolve("block-metadata-crc"),
                                    blockDagDir.resolve("checkpoints")
                                  ),
                                  genesis
                                )(Concurrent[F], Log[F], blockStore)
              semaphore <- Semaphore[F](1)
              casperState <- Cell.mvarCell[F, CasperState](
                              CasperState()
                            )
              node = new HashSetCasperTestNode[F](
                n,
                p,
                tle,
                genesis,
                sk,
                logicalTime,
                errorHandler,
                storageSize,
                blockDagDir,
                blockStoreDir,
                semaphore,
                faultToleranceThreshold
              )(
                concurrentF,
                blockStore,
                blockDagStorage,
                metricEff,
                absF,
                casperState
              )
            } yield node
        }
        .map(_.toVector)

    import Connections._
    //make sure all nodes know about each other
    for {
      nodes <- nodesF
      pairs = for {
        n <- nodes
        m <- nodes
        if n.local != m.local
      } yield (n, m)
      _ <- nodes.traverse(_.initialize).void
      _ <- pairs.foldLeft(().pure[F]) {
            case (f, (n, m)) =>
              f.flatMap(
                _ =>
                  n.connectionsCell.flatModify(
                    _.addConn[F](m.local)(Monad[F], n.logEff, n.metricEff)
                  )
              )
          }
    } yield nodes
  }

  def networkEff(
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  ): Effect[IndexedSeq[HashSetCasperTestNode[Effect]]] =
    networkF[Effect](sks, genesis, storageSize, faultToleranceThreshold)(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect],
      ToAbstractContext[Effect]
    )

  val appErrId = new ApplicativeError[Id, CommError] {
    def ap[A, B](ff: Id[A => B])(fa: Id[A]): Id[B] = Applicative[Id].ap[A, B](ff)(fa)
    def pure[A](x: A): Id[A]                       = Applicative[Id].pure[A](x)
    def raiseError[A](e: CommError): Id[A] = {
      val errString = e match {
        case UnknownCommError(msg)                => s"UnknownCommError($msg)"
        case DatagramSizeError(size)              => s"DatagramSizeError($size)"
        case DatagramFramingError(ex)             => s"DatagramFramingError($ex)"
        case DatagramException(ex)                => s"DatagramException($ex)"
        case HeaderNotAvailable                   => "HeaderNotAvailable"
        case ProtocolException(th)                => s"ProtocolException($th)"
        case UnknownProtocolError(msg)            => s"UnknownProtocolError($msg)"
        case PublicKeyNotAvailable(node)          => s"PublicKeyNotAvailable($node)"
        case ParseError(msg)                      => s"ParseError($msg)"
        case EncryptionHandshakeIncorrectlySigned => "EncryptionHandshakeIncorrectlySigned"
        case BootstrapNotProvided                 => "BootstrapNotProvided"
        case PeerNodeNotFound(peer)               => s"PeerNodeNotFound($peer)"
        case PeerUnavailable(peer)                => s"PeerUnavailable($peer)"
        case MalformedMessage(pm)                 => s"MalformedMessage($pm)"
        case CouldNotConnectToBootstrap           => "CouldNotConnectToBootstrap"
        case InternalCommunicationError(msg)      => s"InternalCommunicationError($msg)"
        case TimeOut                              => "TimeOut"
        case _                                    => e.toString
      }

      throw new Exception(errString)
    }

    def handleErrorWith[A](fa: Id[A])(f: (CommError) => Id[A]): Id[A] = fa
  }

  implicit val syncEffectInstance = cats.effect.Sync.catsEitherTSync[Task, CommError]

  val errorHandler = ApplicativeError_.applicativeError[Id, CommError](appErrId)

  def randomBytes(length: Int): Array[Byte] = Array.fill(length)(Random.nextInt(256).toByte)

  def endpoint(port: Int): Endpoint = Endpoint("host", port, port)

  def peerNode(name: String, port: Int): PeerNode =
    PeerNode(NodeIdentifier(name.getBytes), endpoint(port))

  //TODO: Give a better implementation for use in testing; this one is too simplistic.
  def simpleEEApi[F[_]: Applicative](): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      import ipc._
      private val zero          = Array.fill(32)(0.toByte)
      private val key           = Key(Key.KeyInstance.Hash(KeyHash(ByteString.copyFrom(zero))))
      private val transform     = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
      private val op            = Op(Op.OpInstance.Read(ReadOp()))
      private val transforEntry = TransformEntry(Some(key), Some(transform))
      private val opEntry       = OpEntry(Some(key), Some(op))
      private val ee            = ExecutionEffect(Seq(opEntry), Seq(transforEntry), 0)

      override def emptyStateHash: ByteString = ByteString.copyFrom(zero)

      override def exec(
          prestate: ByteString,
          deploys: Seq[Deploy]
      ): F[Either[Throwable, Seq[DeployResult]]] =
        //This function returns the same `DeployResult` for all deploys,
        //regardless of their wasm code. It pretends to have run all the deploys,
        //but it doesn't really; it just returns the same result no matter what.
        deploys
          .map(_ => DeployResult(Some(DeployResult.Result(DeployResult.Result.Result.Effects(ee)))))
          .asRight[Throwable]
          .pure[F]

      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = {
        //This function increments the prestate by interpreting as an integer and adding 1.
        //The purpose of this is simply to have the output post-state be different
        //than the input pre-state. `effects` is not used.
        val arr    = if (prestate.isEmpty) zero.clone() else prestate.toByteArray
        val n      = BigInt(arr)
        val newArr = pad((n + 1).toByteArray, 32)

        ByteString.copyFrom(newArr).asRight[Throwable].pure[F]
      }

      override def close(): F[Unit] = ().pure[F]
    }

  private def pad(x: Array[Byte], length: Int): Array[Byte] =
    if (x.length < length) Array.fill(length - x.length)(0.toByte) ++ x
    else x
}
