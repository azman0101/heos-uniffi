//! Discovery includes bonded speakers omitted by player/get_players.
use crate::{connection::Connection, error::HeosError, upnp};
use std::{collections::BTreeSet, net::Ipv4Addr, time::Duration};
use tokio::{
    net::UdpSocket,
    task::JoinSet,
    time::{timeout, Instant},
};

const DEVICE_TYPE: &str = "urn:schemas-denon-com:device:ACT-Denon:1";

#[derive(Clone, Debug, uniffi::Record)]
pub struct RebootDevice {
    pub host: String,
    pub name: String,
    pub model: String,
}

#[derive(Debug, uniffi::Record)]
pub struct RebootResult {
    pub host: String,
    pub name: String,
    pub acknowledged: bool,
    pub detail: String,
}

fn validate_host(host: &str) -> Result<(), HeosError> {
    let ip = host
        .parse::<Ipv4Addr>()
        .map_err(|_| HeosError::InvalidArgument {
            reason: "adresse IPv4 requise".into(),
        })?;
    if ip.is_unspecified() || ip.is_multicast() || ip.is_broadcast() {
        return Err(HeosError::InvalidArgument {
            reason: "adresse d'appareil invalide".into(),
        });
    }
    Ok(())
}

fn is_heos_reply(text: &str) -> bool {
    text.lines().any(|line| {
        line.split_once(':')
            .is_some_and(|(k, v)| k.eq_ignore_ascii_case("ST") && v.trim() == DEVICE_TYPE)
    })
}

async fn describe(host: String) -> Result<RebootDevice, HeosError> {
    let xml = upnp::http(
        &format!("{host}:60006"),
        format!(
            "GET /upnp/desc/aios_device/aios_device.xml HTTP/1.0\r\nHost: {host}:60006\r\n\r\n"
        ),
    )
    .await?;
    if !xml.contains(DEVICE_TYPE) {
        return Err(HeosError::Protocol {
            reason: "appareil non HEOS".into(),
        });
    }
    Ok(RebootDevice {
        name: upnp::unescape(upnp::tag_text(&xml, "friendlyName").unwrap_or(&host)),
        model: upnp::unescape(upnp::tag_text(&xml, "modelName").unwrap_or("HEOS")),
        host,
    })
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn discover_reboot_devices(bar_host: String) -> Result<Vec<RebootDevice>, HeosError> {
    validate_host(&bar_host)?;
    let socket = UdpSocket::bind("0.0.0.0:0").await?;
    let request = format!("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {DEVICE_TYPE}\r\n\r\n");
    socket
        .send_to(request.as_bytes(), "239.255.255.250:1900")
        .await?;
    let mut hosts = BTreeSet::from([bar_host]);
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut buf = [0; 8192];
    while let Ok(Ok((len, source))) = timeout(
        deadline.saturating_duration_since(Instant::now()),
        socket.recv_from(&mut buf),
    )
    .await
    {
        if source.is_ipv4() && is_heos_reply(&String::from_utf8_lossy(&buf[..len])) {
            hosts.insert(source.ip().to_string());
        }
        if hosts.len() >= 32 {
            break;
        }
    }
    let mut tasks = JoinSet::new();
    for host in hosts {
        tasks.spawn(describe(host));
    }
    let mut devices = Vec::new();
    while let Some(result) = tasks.join_next().await {
        if let Ok(Ok(device)) = result {
            devices.push(device);
        }
    }
    devices.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(devices)
}

// Never use CommandChannel: its transport retry could reboot a device twice.
async fn reboot_at(addr: &str) -> Result<(), HeosError> {
    let mut connection = Connection::open(addr).await?;
    connection.request("system/reboot").await?;
    Ok(())
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn reboot_devices(devices: Vec<RebootDevice>) -> Result<Vec<RebootResult>, HeosError> {
    if devices.is_empty() || devices.len() > 32 {
        return Err(HeosError::InvalidArgument {
            reason: "sélectionner de 1 à 32 appareils".into(),
        });
    }
    // Validate the whole batch before issuing any command.
    for device in &devices {
        validate_host(&device.host)?;
    }
    let mut seen = BTreeSet::new();
    let mut tasks = JoinSet::new();
    for device in devices {
        if !seen.insert(device.host.clone()) {
            continue;
        }
        tasks.spawn(async move {
            let result = reboot_at(&format!("{}:1255", device.host)).await;
            RebootResult {
                host: device.host,
                name: device.name,
                acknowledged: result.is_ok(),
                detail: match result {
                    Ok(()) => "Commande acceptée — redémarrage en cours".into(),
                    Err(e) => format!(
                        "Redémarrage non confirmé : {e}. Vérifiez l'appareil avant de réessayer."
                    ),
                },
            }
        });
    }
    let mut results = Vec::new();
    while let Some(result) = tasks.join_next().await {
        results.push(result.map_err(|e| HeosError::Connection {
            reason: e.to_string(),
        })?);
    }
    results.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(results)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
        net::TcpListener,
    };

    #[test]
    fn ignores_unrelated_ssdp_devices() {
        assert!(!is_heos_reply("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n"));
        assert!(is_heos_reply(&format!(
            "HTTP/1.1 200 OK\r\nst: {DEVICE_TYPE}\r\n"
        )));
        assert!(validate_host("host/command").is_err());
    }

    #[tokio::test]
    async fn sends_one_reboot_and_requires_acknowledgement() {
        for acknowledge in [true, false] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let addr = listener.local_addr().unwrap();
            let server = tokio::spawn(async move {
                let (stream, _) = listener.accept().await.unwrap();
                let mut stream = BufReader::new(stream);
                let mut line = String::new();
                stream.read_line(&mut line).await.unwrap();
                assert_eq!(line, "heos://system/reboot\r\n");
                if acknowledge {
                    stream.get_mut().write_all(b"{\"heos\":{\"command\":\"system/reboot\",\"result\":\"success\",\"message\":\"\"}}\r\n").await.unwrap();
                }
                drop(stream);
                assert!(timeout(Duration::from_millis(100), listener.accept())
                    .await
                    .is_err());
            });
            assert_eq!(reboot_at(&addr.to_string()).await.is_ok(), acknowledge);
            server.await.unwrap();
        }
    }
}
