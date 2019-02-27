use common::key::Key;
use execution::{Error as ExecutionError, Executor};
use parking_lot::Mutex;
use std::collections::HashMap;
use std::marker::PhantomData;
use storage::error::{Error as StorageError, RootNotFound};
use storage::gs::{DbReader, ExecutionEffect};
use storage::history::*;
use storage::transform::Transform;
use vm::wasm_costs::WasmCosts;
use wasm_prep::Preprocessor;

pub struct EngineState<R, H>
where
    R: DbReader,
    H: History<R>,
{
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Mutex<H>,
    wasm_costs: WasmCosts,
    _phantom: PhantomData<R>,
}

pub enum ExecutionResult {
    Success(ExecutionEffect, u64),
    Failure(Error, u64),
}

#[derive(Debug)]
pub enum Error {
    PreprocessingError(String),
    ExecError(ExecutionError),
    StorageError(StorageError),
}

impl From<wasm_prep::PreprocessingError> for Error {
    fn from(error: wasm_prep::PreprocessingError) -> Self {
        match error {
            wasm_prep::PreprocessingError::InvalidImportsError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::NoExportSection => {
                Error::PreprocessingError(String::from("No export section found."))
            }
            wasm_prep::PreprocessingError::NoImportSection => {
                Error::PreprocessingError(String::from("No import section found,"))
            }
            wasm_prep::PreprocessingError::DeserializeError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::OperationForbiddenByGasRules => {
                Error::PreprocessingError(String::from("Encountered operation forbidden by gas rules. Consult instruction -> metering config map."))
            }
            wasm_prep::PreprocessingError::StackLimiterError => {
                Error::PreprocessingError(String::from("Wasm contract error: Stack limiter error."))

            }
        }
    }
}

impl From<StorageError> for Error {
    fn from(error: StorageError) -> Self {
        Error::StorageError(error)
    }
}

impl From<ExecutionError> for Error {
    fn from(error: ExecutionError) -> Self {
        Error::ExecError(error)
    }
}

impl<H, R> EngineState<R, H>
where
    H: History<R>,
    R: DbReader,
{
    pub fn new(state: H) -> EngineState<R, H> {
        EngineState {
            state: Mutex::new(state),
            wasm_costs: WasmCosts::new(),
            _phantom: PhantomData,
        }
    }

    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy<P: Preprocessor, E: Executor>(
        &self,
        module_bytes: &[u8],
        address: [u8; 20],
        timestamp: u64,
        nonce: u64,
        prestate_hash: [u8; 32],
        gas_limit: u64,
        executor: &E,
        preprocessor: &P,
    ) -> Result<ExecutionResult, RootNotFound> {
        match preprocessor.preprocess(module_bytes, &self.wasm_costs) {
            Err(error) => Ok(ExecutionResult::Failure(error.into(), 0)),
            Ok(module) => {
                let mut tc: storage::gs::TrackingCopy<R> =
                    self.state.lock().checkout(prestate_hash)?;
                match executor.exec(module, address, timestamp, nonce, gas_limit, &mut tc) {
                    (Ok(ee), cost) => Ok(ExecutionResult::Success(ee, cost)),
                    (Err(error), cost) => Ok(ExecutionResult::Failure(error.into(), cost)),
                }
            }
        }
    }

    pub fn apply_effect(
        &self,
        prestate_hash: [u8; 32],
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, RootNotFound> {
        self.state.lock().commit(prestate_hash, effects)
    }
}
