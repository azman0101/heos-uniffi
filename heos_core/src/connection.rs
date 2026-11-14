//! Socket TCP HEOS : envoi de commandes ASCII, lecture ligne par ligne (`\r\n`).

use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::TcpStream;
use tokio::time::timeout;

use crate::error::HeosError;
use crate::protocol::Response;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);

pub struct Connection {
    reader: BufReader<OwnedReadHalf>,
    writer: OwnedWriteHalf,
}

impl Connection {
    pub async fn open(addr: &str) -> Result<Self, HeosError> {
        let stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(addr))
            .await
            .map_err(|_| HeosError::Timeout)??;
        stream.set_nodelay(true)?;
        let (r, writer) = stream.into_split();
        Ok(Self {
            reader: BufReader::new(r),
            writer,
        })
    }

    /// Lit la prochaine ligne JSON (réponse ou événement). Bloque sans limite de temps.
    pub async fn read_response(&mut self) -> Result<Response, HeosError> {
        loop {
            let mut line = String::new();
            if self.reader.read_line(&mut line).await? == 0 {
                return Err(HeosError::Connection {
                    reason: "connexion fermée par l’appareil HEOS".into(),
                });
            }
            let line = line.trim();
            if !line.is_empty() {
                return Response::parse(line);
            }
        }
    }

    /// Envoie `heos://<path_and_query>` et attend la réponse correspondante.
    ///
    /// Ignore les événements intercalés et les « command under process ».
    /// Après une erreur de transport/timeout la connexion doit être jetée
    /// (la lecture annulée peut laisser un flux désynchronisé).
    pub async fn request(&mut self, path_and_query: &str) -> Result<Response, HeosError> {
        let command = path_and_query.split('?').next().unwrap_or(path_and_query);
        timeout(REQUEST_TIMEOUT, async {
            self.writer
                .write_all(format!("heos://{path_and_query}\r\n").as_bytes())
                .await?;
            loop {
                let resp = self.read_response().await?;
                if resp.is_event() || resp.heos.command != command || resp.is_under_process() {
                    continue;
                }
                return resp.into_success();
            }
        })
        .await
        .map_err(|_| HeosError::Timeout)?
    }
}
