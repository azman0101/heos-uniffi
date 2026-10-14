//! Accès UPnP/SOAP à la barre (port 60006) : service `ACT` de Denon, utilisé par l'app HEOS
//! officielle pour l'écran « Niveaux » (config des enceintes du groupe surround).

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

use crate::error::HeosError;

pub const UPNP_PORT: u16 = 60006;
const HTTP_TIMEOUT: Duration = Duration::from_secs(5);

const ACT: (&str, &str) = ("/ACT/control", "urn:schemas-denon-com:service:ACT:1");

/// `192.168.1.12` → `192.168.1.12:60006` ; une adresse avec port est conservée.
pub fn upnp_addr(host: &str) -> String {
    if host.contains(':') {
        host.to_string()
    } else {
        format!("{host}:{UPNP_PORT}")
    }
}

async fn http(addr: &str, request: String) -> Result<String, HeosError> {
    timeout(HTTP_TIMEOUT, async {
        let mut stream = TcpStream::connect(addr).await?;
        stream.write_all(request.as_bytes()).await?;
        let mut raw = Vec::new();
        stream.read_to_end(&mut raw).await?;
        let raw = String::from_utf8_lossy(&raw).into_owned();
        let (head, body) = raw.split_once("\r\n\r\n").ok_or_else(|| HeosError::Protocol {
            reason: "réponse HTTP invalide".into(),
        })?;
        if !head.lines().next().unwrap_or("").contains(" 200") {
            return Err(HeosError::Protocol {
                reason: format!("{} {}", head.lines().next().unwrap_or(""), tag_text(body, "faultstring").unwrap_or_default()),
            });
        }
        Ok(body.to_string())
    })
    .await
    .map_err(|_| HeosError::Timeout)?
}

/// HTTP/1.0 : pas de chunked, la connexion se ferme à la fin (accepté par les Denon).
async fn soap(host: &str, svc: (&str, &str), action: &str, args: &str) -> Result<String, HeosError> {
    let (path, urn) = svc;
    let body = format!(
        r#"<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:{action} xmlns:u="{urn}">{args}</u:{action}></s:Body></s:Envelope>"#
    );
    let addr = upnp_addr(host);
    let req = format!(
        "POST {path} HTTP/1.0\r\nHost: {addr}\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nSOAPACTION: \"{urn}#{action}\"\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    );
    http(&addr, req).await
}

/// XML de configuration des enceintes (`<SurroundSpeakerConfig>…</SurroundSpeakerConfig>`), déséchappé.
pub async fn get_surround_config(host: &str) -> Result<String, HeosError> {
    let xml = soap(host, ACT, "GetSurroundSpeakerConfig", "").await?;
    let inner = tag_text(&xml, "SurroundSpeakerConfig").ok_or_else(|| HeosError::Protocol {
        reason: "GetSurroundSpeakerConfig sans contenu".into(),
    })?;
    Ok(unescape(inner))
}

/// Envoie un fragment de config (ex. `<Front><Left><level>13</level></Left></Front>`).
/// L'app officielle n'envoie que l'élément modifié : la config complète est refusée (500).
pub async fn set_surround_fragment(host: &str, fragment: &str) -> Result<(), HeosError> {
    let config = escape(&format!("<SurroundSpeakerConfig>{fragment}</SurroundSpeakerConfig>"));
    soap(host, ACT, "SetSurroundSpeakerConfig", &format!("<SurroundSpeakerConfig>{config}</SurroundSpeakerConfig>"))
        .await
        .map(|_| ())
}

/// Texte du premier `<tag>…</tag>` (suffisant pour ces réponses SOAP plates).
pub fn tag_text<'a>(xml: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}>");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find(&format!("</{tag}>"))? + start;
    Some(&xml[start..end])
}

pub fn unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

pub fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_tag_text() {
        let xml = "<u:R><CurrentVolume>42</CurrentVolume></u:R>";
        assert_eq!(tag_text(xml, "CurrentVolume"), Some("42"));
        assert_eq!(tag_text(xml, "Missing"), None);
    }

    #[test]
    fn escape_roundtrip() {
        let xml = "<A><b>1</b></A>";
        assert_eq!(escape(xml), "&lt;A&gt;&lt;b&gt;1&lt;/b&gt;&lt;/A&gt;");
        assert_eq!(unescape(&escape(xml)), xml);
    }

    #[test]
    fn builds_upnp_addr() {
        assert_eq!(upnp_addr("192.168.1.12"), "192.168.1.12:60006");
        assert_eq!(upnp_addr("127.0.0.1:9"), "127.0.0.1:9");
    }
}
