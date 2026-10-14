//! Les « enceintes » d'un système surround HEOS : barre, caisson, arrières…

use crate::error::HeosError;
use crate::upnp;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SpeakerRole {
    Main,
    Subwoofer,
    RearLeft,
    RearRight,
    Other,
}

impl SpeakerRole {
    fn from_channel(audio_channel: &str) -> Self {
        match audio_channel {
            "LOW_FREQUENCY" => Self::Subwoofer,
            "REAR_LEFT" => Self::RearLeft,
            "REAR_RIGHT" => Self::RearRight,
            _ => Self::Other,
        }
    }

    fn default_name(self) -> &'static str {
        match self {
            Self::Main => "Barre de son",
            Self::Subwoofer => "Caisson de basses",
            Self::RearLeft => "Arrière gauche",
            Self::RearRight => "Arrière droite",
            Self::Other => "Enceinte",
        }
    }

    fn order(self) -> u8 {
        self as u8
    }
}

/// Une enceinte réglable séparément (volume 0..=100 propre à l'appareil).
#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeakerChannel {
    /// Adresse de l'appareil ; identifiant à repasser à `set_channel_volume`.
    pub host: String,
    pub name: String,
    pub role: SpeakerRole,
    pub volume: u8,
}

/// Retient parmi `candidates` les appareils du même groupe que `lead` (la barre) et lit
/// leur nom et leur volume. Échoue seulement si le leader lui-même est injoignable.
pub async fn collect_channels(
    lead: &str,
    candidates: Vec<String>,
) -> Result<Vec<SpeakerChannel>, HeosError> {
    let lead_info = upnp::member_info(lead).await?;

    let mut hosts: Vec<String> = Vec::new();
    for h in candidates {
        if h != lead && !hosts.contains(&h) {
            hosts.push(h);
        }
    }
    let probes: Vec<_> = hosts
        .into_iter()
        .map(|h| tokio::spawn(async move { (upnp::member_info(&h).await, h) }))
        .collect();

    let mut members = vec![(lead.to_string(), SpeakerRole::Main)];
    for p in probes {
        if let Ok((Ok(info), host)) = p.await {
            if info.group_uuid == lead_info.group_uuid && !info.group_uuid.is_empty() {
                members.push((host, SpeakerRole::from_channel(&info.audio_channel)));
            }
        }
    }

    let reads: Vec<_> = members
        .into_iter()
        .map(|(host, role)| {
            tokio::spawn(async move {
                let volume = upnp::get_volume(&host).await?;
                let name = upnp::friendly_name(&host)
                    .await
                    .unwrap_or_else(|| role.default_name().to_string());
                Ok::<_, HeosError>(SpeakerChannel { host, name, role, volume })
            })
        })
        .collect();

    let mut channels = Vec::new();
    for r in reads {
        match r.await {
            Ok(Ok(c)) => channels.push(c),
            Ok(Err(e)) if channels.is_empty() && e.is_transport() => return Err(e),
            _ => {}
        }
    }
    channels.sort_by(|a, b| (a.role.order(), &a.name).cmp(&(b.role.order(), &b.name)));
    Ok(channels)
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::*;

    /// Faux appareil Denon : GroupControl + RenderingControl + description.
    async fn fake_device(uuid: &'static str, channel: &'static str, name: &'static str, volume: u8) -> (String, Arc<Mutex<u8>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let vol = Arc::new(Mutex::new(volume));
        let state = vol.clone();
        tokio::spawn(async move {
            loop {
                let (mut s, _) = listener.accept().await.unwrap();
                let state = state.clone();
                tokio::spawn(async move {
                    let mut buf = vec![0u8; 8192];
                    let n = s.read(&mut buf).await.unwrap();
                    let req = String::from_utf8_lossy(&buf[..n]).into_owned();
                    let body = if req.starts_with("GET ") {
                        format!("<root><friendlyName>{name}</friendlyName></root>")
                    } else if req.contains("#GetCurrentState") {
                        format!("<r><CurrentState>&lt;Event&gt;&lt;AudioChannel val=&quot;{channel}&quot;/&gt;&lt;GroupUUID val=&quot;{uuid}&quot;/&gt;&lt;/Event&gt;</CurrentState></r>")
                    } else if req.contains("#GetVolume") {
                        format!("<r><CurrentVolume>{}</CurrentVolume></r>", state.lock().unwrap())
                    } else if req.contains("#SetVolume") {
                        let v = upnp::tag_text(&req, "DesiredVolume").unwrap().parse().unwrap();
                        *state.lock().unwrap() = v;
                        "<r/>".to_string()
                    } else {
                        panic!("requête inattendue : {req}")
                    };
                    let resp = format!("HTTP/1.0 200 OK\r\nContent-Length: {}\r\n\r\n{body}", body.len());
                    s.write_all(resp.as_bytes()).await.unwrap();
                });
            }
        });
        (addr, vol)
    }

    #[tokio::test]
    async fn collects_group_members_and_sets_volumes_independently() {
        let (bar, bar_vol) = fake_device("g1", "NORMAL", "HEOS Bar", 2).await;
        let (sub, sub_vol) = fake_device("g1", "LOW_FREQUENCY", "HEOS Subwoofer", 2).await;
        let (rear, rear_vol) = fake_device("g1", "REAR_LEFT", "BackLeft", 2).await;
        let (stranger, _) = fake_device("other-group", "NORMAL", "Cuisine", 40).await;
        let dead = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap().to_string();

        let channels = collect_channels(&bar, vec![rear.clone(), stranger, bar.clone(), dead, sub.clone()])
            .await
            .unwrap();

        let got: Vec<_> = channels.iter().map(|c| (c.role, c.name.as_str(), c.volume)).collect();
        assert_eq!(
            got,
            [
                (SpeakerRole::Main, "HEOS Bar", 2),
                (SpeakerRole::Subwoofer, "HEOS Subwoofer", 2),
                (SpeakerRole::RearLeft, "BackLeft", 2),
            ]
        );

        upnp::set_volume(&rear, 3).await.unwrap();
        assert_eq!((*bar_vol.lock().unwrap(), *sub_vol.lock().unwrap(), *rear_vol.lock().unwrap()), (2, 2, 3));
    }

    #[tokio::test]
    async fn fails_when_lead_unreachable() {
        let dead = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap().to_string();
        assert!(collect_channels(&dead, vec![]).await.is_err());
    }
}
