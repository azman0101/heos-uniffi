/// Erreurs exposées à Kotlin (`HeosException.*`).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum HeosError {
    #[error("non connecté à la barre de son")]
    NotConnected,
    #[error("erreur de connexion : {reason}")]
    Connection { reason: String },
    #[error("délai d'attente dépassé")]
    Timeout,
    #[error("réponse HEOS invalide : {reason}")]
    Protocol { reason: String },
    #[error("commande refusée par HEOS (eid {code}) : {reason}")]
    CommandFailed { code: i32, reason: String },
    #[error("argument invalide : {reason}")]
    InvalidArgument { reason: String },
}

impl HeosError {
    /// Vrai si la socket est à considérer comme morte (⇒ reconnexion possible).
    pub(crate) fn is_transport(&self) -> bool {
        matches!(self, Self::Connection { .. } | Self::Timeout)
    }
}

impl From<std::io::Error> for HeosError {
    fn from(e: std::io::Error) -> Self {
        Self::Connection {
            reason: e.to_string(),
        }
    }
}
