//! « Niveaux » par enceinte (−12…+12 dB), comme l'écran du même nom de l'app HEOS.
//!
//! Stockés dans la config surround de la barre (`level` = 12 + dB, 0..=24) et écrits avec
//! `ACT/SetSurroundSpeakerConfig` à la barre uniquement, en fragment XML (cf. capture réseau).

use crate::error::HeosError;
use crate::upnp;

pub const MIN_DB: i8 = -12;
pub const MAX_DB: i8 = 12;
const ZERO: i8 = 12; // level 12 = 0 dB

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SpeakerRole {
    Main,
    RearLeft,
    RearRight,
    Subwoofer,
}

const ROLES: [SpeakerRole; 4] = [
    SpeakerRole::Main,
    SpeakerRole::RearLeft,
    SpeakerRole::RearRight,
    SpeakerRole::Subwoofer,
];

impl SpeakerRole {
    /// (section, élément) dans `SurroundSpeakerConfig`.
    /// La barre = `Front/Left` (vérifié par capture) ; les autres suivent le même schéma (à confirmer).
    fn path(self) -> (&'static str, &'static str) {
        match self {
            Self::Main => ("Front", "Left"),
            Self::RearLeft => ("Rear", "Left"),
            Self::RearRight => ("Rear", "Right"),
            Self::Subwoofer => ("Subwoofer", "Subwoofer"),
        }
    }
}

/// Niveau d'une enceinte, en dB (−12..=12).
#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeakerLevel {
    pub role: SpeakerRole,
    pub db: i8,
}

/// `level` brut (0..=24) de `<section><inner>…<level>N</level>` dans la config.
fn read_level(xml: &str, section: &str, inner: &str) -> Option<u8> {
    let after_section = &xml[xml.find(&format!("<{section}>"))?..];
    let after_inner = &after_section[after_section.find(&format!("<{inner}>"))?..];
    upnp::tag_text(after_inner, "level")?.trim().parse().ok()
}

fn fragment(role: SpeakerRole, level: u8) -> String {
    let (section, inner) = role.path();
    format!("<{section}><{inner}><level>{level}</level></{inner}></{section}>")
}

pub async fn read_levels(lead: &str) -> Result<Vec<SpeakerLevel>, HeosError> {
    let xml = upnp::get_surround_config(lead).await?;
    Ok(ROLES
        .into_iter()
        .filter_map(|role| {
            let (section, inner) = role.path();
            let raw = read_level(&xml, section, inner)? as i8;
            Some(SpeakerLevel { role, db: (raw - ZERO).clamp(MIN_DB, MAX_DB) })
        })
        .collect())
}

pub async fn write_level(lead: &str, role: SpeakerRole, db: i8) -> Result<(), HeosError> {
    if !(MIN_DB..=MAX_DB).contains(&db) {
        return Err(HeosError::InvalidArgument {
            reason: format!("niveau {db} dB hors de {MIN_DB}..={MAX_DB}"),
        });
    }
    upnp::set_surround_fragment(lead, &fragment(role, (db + ZERO) as u8)).await
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::*;

    // Config relevée sur la vraie barre (barre +5 dB, le reste à 0 dB).
    const CONFIG: &str = "<SurroundSpeakerConfig><Front><enabled>1</enabled><crossover>0</crossover><Right><distance>8</distance><level>12</level><test_tone>0</test_tone></Right><Left><distance>8</distance><level>17</level><test_tone>0</test_tone></Left></Front><Center><enabled>0</enabled><Center><distance>8</distance><level>12</level></Center></Center><Subwoofer><enabled>0</enabled><lowpass>250</lowpass><phase>0</phase><Subwoofer><distance>8</distance><level>9</level><test_tone>0</test_tone></Subwoofer></Subwoofer><Rear><enabled>0</enabled><crossover>0</crossover><Right><distance>4</distance><level>12</level></Right><Left><distance>4</distance><level>14</level></Left></Rear><RearBack><enabled>0</enabled><Right><level>3</level></Right><Left><level>3</level></Left></RearBack></SurroundSpeakerConfig>";

    #[test]
    fn reads_each_role_without_mixing_sections() {
        let get = |s, i| read_level(CONFIG, s, i);
        assert_eq!(get("Front", "Left"), Some(17));
        assert_eq!(get("Rear", "Left"), Some(14)); // pas RearBack
        assert_eq!(get("Rear", "Right"), Some(12));
        assert_eq!(get("Subwoofer", "Subwoofer"), Some(9));
        assert_eq!(get("Nope", "Left"), None);
    }

    #[test]
    fn fragment_matches_official_app_capture() {
        assert_eq!(
            fragment(SpeakerRole::Main, 13),
            "<Front><Left><level>13</level></Left></Front>"
        );
    }

    /// Fausse barre : renvoie CONFIG (échappée) et mémorise le dernier SetSurroundSpeakerConfig.
    async fn fake_bar() -> (String, Arc<Mutex<Option<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let last = Arc::new(Mutex::new(None));
        let seen = last.clone();
        tokio::spawn(async move {
            loop {
                let (mut s, _) = listener.accept().await.unwrap();
                let seen = seen.clone();
                tokio::spawn(async move {
                    let mut buf = vec![0u8; 8192];
                    let n = s.read(&mut buf).await.unwrap();
                    let req = String::from_utf8_lossy(&buf[..n]).into_owned();
                    let body = if req.contains("#GetSurroundSpeakerConfig") {
                        format!("<r><SurroundSpeakerConfig>{}</SurroundSpeakerConfig></r>", upnp::escape(CONFIG))
                    } else if req.contains("#SetSurroundSpeakerConfig") {
                        let inner = upnp::tag_text(&req, "SurroundSpeakerConfig").unwrap();
                        *seen.lock().unwrap() = Some(upnp::unescape(inner));
                        "<r/>".to_string()
                    } else {
                        panic!("requête inattendue : {req}")
                    };
                    s.write_all(format!("HTTP/1.0 200 OK\r\nContent-Length: {}\r\n\r\n{body}", body.len()).as_bytes())
                        .await
                        .unwrap();
                });
            }
        });
        (addr, last)
    }

    #[tokio::test]
    async fn reads_levels_in_db_and_writes_official_fragment() {
        let (bar, last) = fake_bar().await;

        let db: Vec<_> = read_levels(&bar).await.unwrap().into_iter().map(|l| (l.role, l.db)).collect();
        assert_eq!(
            db,
            [
                (SpeakerRole::Main, 5),
                (SpeakerRole::RearLeft, 2),
                (SpeakerRole::RearRight, 0),
                (SpeakerRole::Subwoofer, -3),
            ]
        );

        write_level(&bar, SpeakerRole::Main, 1).await.unwrap();
        assert_eq!(
            last.lock().unwrap().as_deref(),
            Some("<SurroundSpeakerConfig><Front><Left><level>13</level></Left></Front></SurroundSpeakerConfig>")
        );
    }

    #[tokio::test]
    async fn rejects_out_of_range_db() {
        let (bar, last) = fake_bar().await;
        assert!(matches!(write_level(&bar, SpeakerRole::Main, 13).await, Err(HeosError::InvalidArgument { .. })));
        assert!(matches!(write_level(&bar, SpeakerRole::Main, -13).await, Err(HeosError::InvalidArgument { .. })));
        assert!(last.lock().unwrap().is_none());
    }
}
