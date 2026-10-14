//! Format des messages HEOS CLI : une ligne JSON terminée par `\r\n`.

use std::collections::HashMap;

use serde::Deserialize;
use serde_json::Value;

use crate::error::HeosError;

#[derive(Debug, Deserialize)]
pub struct Response {
    pub heos: Header,
    #[serde(default)]
    pub payload: Value,
}

#[derive(Debug, Deserialize)]
pub struct Header {
    pub command: String,
    /// Absent pour les événements (`event/...`).
    #[serde(default)]
    pub result: Option<String>,
    #[serde(default)]
    pub message: String,
}

#[derive(Debug, Deserialize)]
pub struct Player {
    pub pid: i64,
    pub name: String,
    #[serde(default)]
    pub model: String,
}

/// Contenu de `event/player_volume_changed`.
#[derive(Debug, PartialEq, Eq)]
pub struct VolumeEvent {
    pub pid: i64,
    pub level: u8,
    pub muted: bool,
}

impl Response {
    pub fn parse(line: &str) -> Result<Self, HeosError> {
        serde_json::from_str(line).map_err(|e| HeosError::Protocol {
            reason: format!("{e} (ligne : {line})"),
        })
    }

    pub fn is_event(&self) -> bool {
        self.heos.command.starts_with("event/")
    }

    /// HEOS envoie `"message": "command under process"` avant la vraie réponse
    /// des commandes longues.
    pub fn is_under_process(&self) -> bool {
        self.heos.message.contains("command under process")
    }

    /// Les paramètres du champ `message` (`pid=1&level=30`).
    pub fn params(&self) -> HashMap<String, String> {
        parse_query(&self.heos.message)
    }

    /// Convertit un `"result": "fail"` en `HeosError::CommandFailed`.
    pub fn into_success(self) -> Result<Self, HeosError> {
        if self.heos.result.as_deref() == Some("fail") {
            let params = self.params();
            return Err(HeosError::CommandFailed {
                code: params
                    .get("eid")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(-1),
                reason: params
                    .get("text")
                    .cloned()
                    .unwrap_or_else(|| self.heos.message.clone()),
            });
        }
        Ok(self)
    }

    pub fn players(&self) -> Result<Vec<Player>, HeosError> {
        serde_json::from_value(self.payload.clone()).map_err(|e| HeosError::Protocol {
            reason: format!("payload get_players invalide : {e}"),
        })
    }

    pub fn volume_event(&self) -> Option<VolumeEvent> {
        if self.heos.command != "event/player_volume_changed" {
            return None;
        }
        let p = self.params();
        Some(VolumeEvent {
            pid: p.get("pid")?.parse().ok()?,
            level: p.get("level")?.parse::<u8>().ok()?.min(100),
            muted: p.get("mute").is_some_and(|m| m == "on"),
        })
    }
}

/// Parse `clé1=valeur1&clé2=valeur2` (avec décodage `%XX`).
pub fn parse_query(message: &str) -> HashMap<String, String> {
    message
        .split('&')
        .filter(|pair| !pair.is_empty())
        .map(|pair| match pair.split_once('=') {
            Some((k, v)) => (percent_decode(k), percent_decode(v)),
            None => (percent_decode(pair), String::new()),
        })
        .collect()
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) {
                out.push(hi << 4 | lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex(b: u8) -> Option<u8> {
    (b as char).to_digit(16).map(|d| d as u8)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_query() {
        let q = parse_query("pid=-196792939&level=30");
        assert_eq!(q["pid"], "-196792939");
        assert_eq!(q["level"], "30");
        assert!(parse_query("").is_empty());
        assert_eq!(parse_query("enable")["enable"], "");
    }

    #[test]
    fn decodes_percent_escapes() {
        let q = parse_query("eid=2&text=ID%20Not%20Valid%26co");
        assert_eq!(q["text"], "ID Not Valid&co");
        // séquence tronquée : laissée telle quelle
        assert_eq!(percent_decode("100%"), "100%");
        assert_eq!(percent_decode("%zz"), "%zz");
    }

    #[test]
    fn parses_get_players() {
        let line = r#"{"heos": {"command": "player/get_players", "result": "success", "message": ""}, "payload": [{"name": "HEOS Bar", "pid": -196792939, "model": "HEOS Bar", "version": "3.139.170", "ip": "192.168.1.12", "network": "wired", "lineout": 0, "serial": "X"}]}"#;
        let players = Response::parse(line).unwrap().players().unwrap();
        assert_eq!(players.len(), 1);
        assert_eq!(players[0].pid, -196792939);
        assert_eq!(players[0].name, "HEOS Bar");
    }

    #[test]
    fn parses_volume_event() {
        let line = r#"{"heos": {"command": "event/player_volume_changed", "message": "pid=1&level=35&mute=off"}}"#;
        let r = Response::parse(line).unwrap();
        assert!(r.is_event());
        assert_eq!(
            r.volume_event(),
            Some(VolumeEvent { pid: 1, level: 35, muted: false })
        );
    }

    #[test]
    fn ignores_other_events() {
        let line = r#"{"heos": {"command": "event/player_state_changed", "message": "pid=1&state=play"}}"#;
        assert_eq!(Response::parse(line).unwrap().volume_event(), None);
    }

    #[test]
    fn maps_failure_to_error() {
        let line = r#"{"heos": {"command": "player/get_volume", "result": "fail", "message": "eid=2&text=ID Not Valid"}}"#;
        match Response::parse(line).unwrap().into_success() {
            Err(HeosError::CommandFailed { code, reason }) => {
                assert_eq!(code, 2);
                assert_eq!(reason, "ID Not Valid");
            }
            other => panic!("attendu CommandFailed, obtenu {other:?}"),
        }
    }

    #[test]
    fn detects_under_process() {
        let line = r#"{"heos": {"command": "player/set_volume", "result": "success", "message": "command under process"}}"#;
        assert!(Response::parse(line).unwrap().is_under_process());
    }

    #[test]
    fn rejects_garbage() {
        assert!(matches!(Response::parse("pas du json"), Err(HeosError::Protocol { .. })));
    }
}
