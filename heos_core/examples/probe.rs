//! Sonde en ligne de commande contre une vraie barre (lecture seule par défaut).
//!
//!   cargo run -p heos_core --example probe -- 192.168.1.12 [secondes_d_écoute]

use std::time::Duration;

use heos_core::{HeosController, VolumeEventListener};

struct Print;

impl VolumeEventListener for Print {
    fn on_volume_changed(&self, pid: i64, new_level: u8, is_muted: bool) {
        println!("événement : pid={pid} niveau={new_level} mute={is_muted}");
    }
}

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let ip = args.next().unwrap_or_else(|| "192.168.1.12".into());
    let listen: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(0);

    let ctl = HeosController::new();
    match ctl.connect(ip, Box::new(Print)).await {
        Ok(speakers) => speakers.iter().for_each(|s| println!("{s:?}")),
        Err(e) => return eprintln!("échec : {e}"),
    }
    match ctl.get_speaker_levels().await {
        Ok(channels) => channels.iter().for_each(|c| println!("{c:?}")),
        Err(e) => eprintln!("niveaux indisponibles : {e}"),
    }
    if listen > 0 {
        println!("écoute des événements pendant {listen}s (bouge le volume à la télécommande)…");
        tokio::time::sleep(Duration::from_secs(listen)).await;
    }
}
