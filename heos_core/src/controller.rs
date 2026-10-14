//! Contrôleur exposé à Kotlin : une session = 2 sockets TCP (commandes + événements).

use std::sync::Arc;
use std::time::Duration;

use tokio::sync::Mutex;
use tokio::task::JoinHandle;
use tokio::time::sleep;

use crate::connection::Connection;
use crate::error::HeosError;
use crate::protocol::Response;
use crate::levels::{self, SpeakerLevel, SpeakerRole};

pub const HEOS_PORT: u16 = 1255;
const RECONNECT_DELAY: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, uniffi::Record)]
pub struct HeosSpeaker {
    pub pid: i64,
    pub name: String,
    pub model: String,
    pub volume: u8,
    pub is_muted: bool,
}

/// Implémenté côté Kotlin. Appelé depuis un thread du runtime Rust : ne pas bloquer.
#[uniffi::export(callback_interface)]
pub trait VolumeEventListener: Send + Sync {
    fn on_volume_changed(&self, pid: i64, new_level: u8, is_muted: bool);
}

/// Socket de commandes ; se reconnecte (une fois) si elle est morte.
struct CommandChannel {
    addr: String,
    conn: Option<Connection>,
}

impl CommandChannel {
    async fn open(addr: &str) -> Result<Connection, HeosError> {
        let mut conn = Connection::open(addr).await?;
        // Pas de flot d'événements sur la socket de commandes.
        conn.request("system/register_for_change_events?enable=off")
            .await?;
        Ok(conn)
    }

    /// À n'utiliser qu'avec des commandes idempotentes (rejouées après reconnexion).
    async fn request(&mut self, path_and_query: &str) -> Result<Response, HeosError> {
        for attempt in 0..2 {
            if self.conn.is_none() {
                self.conn = Some(Self::open(&self.addr).await?);
            }
            match self.conn.as_mut().unwrap().request(path_and_query).await {
                Err(e) if e.is_transport() => {
                    self.conn = None;
                    if attempt == 1 {
                        return Err(e);
                    }
                }
                other => return other,
            }
        }
        unreachable!()
    }
}

struct Session {
    commands: CommandChannel,
    event_task: JoinHandle<()>,
    /// Hôte de la barre (sans port HEOS) ; leader du groupe surround.
    lead_host: String,
}

impl Drop for Session {
    fn drop(&mut self) {
        self.event_task.abort();
    }
}

#[derive(uniffi::Object)]
pub struct HeosController {
    session: Mutex<Option<Session>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl HeosController {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            session: Mutex::new(None),
        })
    }

    /// Se connecte à la barre de son (`192.168.1.12` ou `hôte:port`), renvoie toutes
    /// les enceintes avec leur état courant, puis notifie `listener` en temps réel.
    pub async fn connect(
        &self,
        ip: String,
        listener: Box<dyn VolumeEventListener>,
    ) -> Result<Vec<HeosSpeaker>, HeosError> {
        let ip = ip.trim();
        if ip.is_empty() {
            return Err(HeosError::InvalidArgument {
                reason: "adresse IP vide".into(),
            });
        }
        let addr = if ip.contains(':') {
            ip.to_string()
        } else {
            format!("{ip}:{HEOS_PORT}")
        };
        let listener: Arc<dyn VolumeEventListener> = Arc::from(listener);

        let mut guard = self.session.lock().await;
        *guard = None; // ferme une éventuelle session précédente

        let mut commands = CommandChannel {
            conn: Some(CommandChannel::open(&addr).await?),
            addr: addr.clone(),
        };
        let speakers = fetch_speakers(&mut commands).await?;

        let events = open_event_connection(&addr).await?;
        let lead_host = host_of(&addr);
        let event_task = tokio::spawn(run_event_loop(addr, listener, events));

        *guard = Some(Session {
            commands,
            event_task,
            lead_host,
        });
        Ok(speakers)
    }

    pub async fn set_volume(&self, pid: i64, level: u8) -> Result<(), HeosError> {
        if level > 100 {
            return Err(HeosError::InvalidArgument {
                reason: format!("volume {level} hors de 0..=100"),
            });
        }
        let mut guard = self.session.lock().await;
        let c = &mut guard.as_mut().ok_or(HeosError::NotConnected)?.commands;
        c.request(&format!("player/set_volume?pid={pid}&level={level}"))
            .await
            .map(|_| ())
    }

    /// Inverse le mute (get_mute + set_mute plutôt que toggle_mute : rejouable sans risque).
    pub async fn toggle_mute(&self, pid: i64) -> Result<(), HeosError> {
        let mut guard = self.session.lock().await;
        let c = &mut guard.as_mut().ok_or(HeosError::NotConnected)?.commands;
        let state = if fetch_mute(c, pid).await? { "off" } else { "on" };
        c.request(&format!("player/set_mute?pid={pid}&state={state}"))
            .await
            .map(|_| ())
    }

    /// Niveaux (−12..+12 dB) de la barre, des arrières et du caisson, comme l'écran « Niveaux » de l'app HEOS.
    pub async fn get_speaker_levels(&self) -> Result<Vec<SpeakerLevel>, HeosError> {
        levels::read_levels(&self.lead_host().await?).await
    }

    pub async fn set_speaker_level(&self, role: SpeakerRole, db: i8) -> Result<(), HeosError> {
        levels::write_level(&self.lead_host().await?, role, db).await
    }

    pub async fn disconnect(&self) {
        *self.session.lock().await = None;
    }
}

impl HeosController {
    async fn lead_host(&self) -> Result<String, HeosError> {
        let guard = self.session.lock().await;
        Ok(guard.as_ref().ok_or(HeosError::NotConnected)?.lead_host.clone())
    }
}

/// `192.168.1.12:1255` → `192.168.1.12`.
fn host_of(addr: &str) -> String {
    addr.rsplit_once(':').map_or(addr, |(h, _)| h).to_string()
}

async fn fetch_speakers(c: &mut CommandChannel) -> Result<Vec<HeosSpeaker>, HeosError> {
    let players = c.request("player/get_players").await?.players()?;
    let mut speakers = Vec::with_capacity(players.len());
    for p in players {
        speakers.push(HeosSpeaker {
            volume: fetch_volume(c, p.pid).await?,
            is_muted: fetch_mute(c, p.pid).await?,
            pid: p.pid,
            name: p.name,
            model: p.model,
        });
    }
    Ok(speakers)
}

async fn fetch_volume(c: &mut CommandChannel, pid: i64) -> Result<u8, HeosError> {
    let resp = c.request(&format!("player/get_volume?pid={pid}")).await?;
    resp.params()
        .get("level")
        .and_then(|l| l.parse::<u8>().ok())
        .map(|l| l.min(100))
        .ok_or_else(|| HeosError::Protocol {
            reason: format!("get_volume sans level : {}", resp.heos.message),
        })
}

async fn fetch_mute(c: &mut CommandChannel, pid: i64) -> Result<bool, HeosError> {
    let resp = c.request(&format!("player/get_mute?pid={pid}")).await?;
    Ok(resp.params().get("state").is_some_and(|s| s == "on"))
}

async fn open_event_connection(addr: &str) -> Result<Connection, HeosError> {
    let mut conn = Connection::open(addr).await?;
    conn.request("system/register_for_change_events?enable=on")
        .await?;
    Ok(conn)
}

/// Lit les événements en boucle ; se reconnecte si la socket tombe
/// (les changements survenus pendant la coupure ne sont pas rejoués).
async fn run_event_loop(
    addr: String,
    listener: Arc<dyn VolumeEventListener>,
    mut conn: Connection,
) {
    loop {
        while let Ok(resp) = conn.read_response().await {
            if let Some(e) = resp.volume_event() {
                listener.on_volume_changed(e.pid, e.level, e.muted);
            }
        }
        conn = loop {
            sleep(RECONNECT_DELAY).await;
            if let Ok(c) = open_event_connection(&addr).await {
                break c;
            }
        };
    }
}
