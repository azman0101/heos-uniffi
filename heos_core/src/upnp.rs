//! Réglage individuel des enceintes via UPnP (RenderingControl / GroupControl, port 60006).
//!
//! Le HEOS CLI n'expose que le « leader » d'un groupe surround (la barre). Chaque membre
//! (caisson, arrières…) a pourtant son propre registre de volume, joignable en UPnP.

use std::collections::HashSet;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::time::{timeout, Instant};

use crate::error::HeosError;

pub const UPNP_PORT: u16 = 60006;
const HTTP_TIMEOUT: Duration = Duration::from_secs(5);
const SSDP_ADDR: &str = "239.255.255.250:1900";
const SSDP_WINDOW: Duration = Duration::from_millis(2500);

const RENDERING: (&str, &str) = (
    "/upnp/control/renderer_dvc/RenderingControl",
    "urn:schemas-upnp-org:service:RenderingControl:1",
);
const GROUP: (&str, &str) = (
    "/upnp/control/AiosServicesDvc/GroupControl",
    "urn:schemas-denon-com:service:GroupControl:1",
);
const DESCRIPTION: &str = "/upnp/desc/aios_device/aios_device.xml";

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

async fn get(host: &str, path: &str) -> Result<String, HeosError> {
    let addr = upnp_addr(host);
    http(&addr, format!("GET {path} HTTP/1.0\r\nHost: {addr}\r\n\r\n")).await
}

pub async fn get_volume(host: &str) -> Result<u8, HeosError> {
    let xml = soap(host, RENDERING, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>").await?;
    tag_text(&xml, "CurrentVolume")
        .and_then(|v| v.trim().parse::<u8>().ok())
        .map(|v| v.min(100))
        .ok_or_else(|| HeosError::Protocol {
            reason: "GetVolume sans CurrentVolume".into(),
        })
}

pub async fn set_volume(host: &str, level: u8) -> Result<(), HeosError> {
    soap(
        host,
        RENDERING,
        "SetVolume",
        &format!("<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>{level}</DesiredVolume>"),
    )
    .await
    .map(|_| ())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemberInfo {
    pub group_uuid: String,
    pub audio_channel: String,
}

pub async fn member_info(host: &str) -> Result<MemberInfo, HeosError> {
    let xml = unescape(&soap(host, GROUP, "GetCurrentState", "").await?);
    Ok(MemberInfo {
        group_uuid: attr_val(&xml, "GroupUUID").unwrap_or_default(),
        audio_channel: attr_val(&xml, "AudioChannel").unwrap_or_default(),
    })
}

pub async fn friendly_name(host: &str) -> Option<String> {
    let xml = get(host, DESCRIPTION).await.ok()?;
    tag_text(&xml, "friendlyName").map(|n| n.trim().to_string()).filter(|n| !n.is_empty())
}

/// Adresses IP des appareils Denon/HEOS qui répondent à un M-SEARCH SSDP.
pub async fn ssdp_discover() -> Vec<String> {
    let Ok(sock) = UdpSocket::bind("0.0.0.0:0").await else {
        return vec![];
    };
    let msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-denon-com:device:ACT:1\r\n\r\n";
    let (mut seen, mut order) = (HashSet::new(), Vec::new());
    let deadline = Instant::now() + SSDP_WINDOW;
    let mut next_probe = Instant::now();
    let mut buf = [0u8; 2048];
    while Instant::now() < deadline {
        if Instant::now() >= next_probe {
            let _ = sock.send_to(msg.as_bytes(), SSDP_ADDR).await; // 2 envois : UDP perd des paquets
            next_probe = Instant::now() + Duration::from_millis(800);
        }
        if let Ok(Ok((_, from))) = timeout(Duration::from_millis(200), sock.recv_from(&mut buf)).await {
            let ip = from.ip().to_string();
            if seen.insert(ip.clone()) {
                order.push(ip);
            }
        }
    }
    order
}

/// Texte du premier `<tag>…</tag>` (suffisant pour ces réponses SOAP plates).
pub fn tag_text<'a>(xml: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}>");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find(&format!("</{tag}>"))? + start;
    Some(&xml[start..end])
}

/// Valeur de `<tag val="…"/>`.
pub fn attr_val(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{tag} val=\"");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find('"')? + start;
    Some(xml[start..end].to_string())
}

pub fn unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
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
    fn reads_attributes_in_escaped_state() {
        let raw = "&lt;Event&gt;&lt;AudioChannel val=&quot;REAR_LEFT&quot;/&gt;&lt;GroupUUID val=&quot;abc-1&quot;/&gt;&lt;/Event&gt;";
        let xml = unescape(raw);
        assert_eq!(attr_val(&xml, "AudioChannel").as_deref(), Some("REAR_LEFT"));
        assert_eq!(attr_val(&xml, "GroupUUID").as_deref(), Some("abc-1"));
        assert_eq!(attr_val(&xml, "Nope"), None);
    }

    #[test]
    fn builds_upnp_addr() {
        assert_eq!(upnp_addr("192.168.1.12"), "192.168.1.12:60006");
        assert_eq!(upnp_addr("127.0.0.1:9"), "127.0.0.1:9");
    }
}
