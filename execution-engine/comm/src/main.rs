extern crate clap;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate protobuf;
extern crate storage;
extern crate wasm_prep;

pub mod engine_server;

use clap::{App, Arg};
use engine_server::*;
use execution_engine::engine::EngineState;
use storage::gs::inmem::InMemHist;
use storage::history::EMPTY_ROOT_HASH;

fn main() {
    let matches = App::new("Execution engine server")
        .arg(Arg::with_name("socket").required(true).help("Socket file"))
        .get_matches();
    let socket = matches
        .value_of("socket")
        .expect("missing required argument");
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

    let init_state = storage::gs::mocked_account([48u8; 20]);
    let engine_state = EngineState::new(InMemHist::new_initialized(&EMPTY_ROOT_HASH, init_state));
    let server_builder = engine_server::new(socket, engine_state);
    let _server = server_builder.build().expect("Start server");

    println!("Server is listening on socket: {}", socket);

    // loop idefinitely
    loop {
        std::thread::park();
    }
}
