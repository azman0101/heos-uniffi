//! Test bout-en-bout contre une fausse barre HEOS (aucun matériel requis).

use std::sync::{Arc, Mutex};
use std::time::Duration;

use heos_core::{HeosController, HeosError, SpeakerRole, VolumeEventListener};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::broadcast;

const PID: i64 = -42;

fn reply(command: &str, message: &str, payload: &str) -> String {
    format!(
        r#"{{"heos": {{"command": "{command}", "result": "success", "message": "{message}"}}{payload}}}"#
    ) + "\r\n"
}

async fn serve(stream: TcpStream, state: Arc<Mutex<(u8, bool)>>, events: broadcast::Sender<String>) {
    let (r, mut w) = stream.into_split();
    let mut lines = BufReader::new(r).lines();
    let mut rx: Option<broadcast::Receiver<String>> = None;
    loop {
        tokio::select! {
            line = lines.next_line() => {
                let Ok(Some(line)) = line else { return };
                let cmd = line.trim().strip_prefix("heos://").unwrap().to_string();
                let (path, query) = cmd.split_once('?').unwrap_or((&cmd, ""));
                let out = match path {
                    "system/register_for_change_events" => {
                        if query == "enable=on" { rx = Some(events.subscribe()); }
                        reply(path, query, "")
                    }
                    "player/get_players" => reply(path, "", &format!(
                        r#", "payload": [{{"name": "Salon", "pid": {PID}, "model": "HEOS Bar", "ip": "127.0.0.1"}}]"#)),
                    "player/get_volume" => reply(path, &format!("pid={PID}&level={}", state.lock().unwrap().0), ""),
                    "player/get_mute" => reply(path, &format!("pid={PID}&state={}",
                        if state.lock().unwrap().1 { "on" } else { "off" }), ""),
                    "player/set_volume" => {
                        let level: u8 = query.split("level=").nth(1).unwrap().parse().unwrap();
                        state.lock().unwrap().0 = level;
                        let _ = events.send(format!(
                            r#"{{"heos": {{"command": "event/player_volume_changed", "message": "pid={PID}&level={level}&mute=off"}}}}"#) + "\r\n");
                        reply(path, query, "")
                    }
                    "player/set_mute" => {
                        let on = query.ends_with("state=on");
                        state.lock().unwrap().1 = on;
                        let level = state.lock().unwrap().0;
                        let _ = events.send(format!(
                            r#"{{"heos": {{"command": "event/player_volume_changed", "message": "pid={PID}&level={level}&mute={}"}}}}"#,
                            if on { "on" } else { "off" }) + "\r\n");
                        reply(path, query, "")
                    }
                    other => panic!("commande inattendue : {other}"),
                };
                w.write_all(out.as_bytes()).await.unwrap();
            }
            ev = async { match rx.as_mut() { Some(r) => r.recv().await, None => std::future::pending().await } } => {
                if let Ok(ev) = ev { w.write_all(ev.as_bytes()).await.unwrap(); }
            }
        }
    }
}

async fn start_fake() -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    let state = Arc::new(Mutex::new((30u8, false)));
    let (events, _) = broadcast::channel(16);
    tokio::spawn(async move {
        loop {
            let (s, _) = listener.accept().await.unwrap();
            tokio::spawn(serve(s, state.clone(), events.clone()));
        }
    });
    addr
}

#[derive(Default, Clone)]
struct Recorder(Arc<Mutex<Vec<(i64, u8, bool)>>>);

impl VolumeEventListener for Recorder {
    fn on_volume_changed(&self, pid: i64, new_level: u8, is_muted: bool) {
        self.0.lock().unwrap().push((pid, new_level, is_muted));
    }
}

async fn wait_for(rec: &Recorder, n: usize) -> Vec<(i64, u8, bool)> {
    for _ in 0..100 {
        let got = rec.0.lock().unwrap().clone();
        if got.len() >= n {
            return got;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    panic!("événements attendus : {n}, reçus : {:?}", rec.0.lock().unwrap());
}

#[tokio::test]
async fn connect_control_and_receive_events() {
    let addr = start_fake().await;
    let rec = Recorder::default();
    let ctl = HeosController::new();

    let speakers = ctl.connect(addr, Box::new(rec.clone())).await.unwrap();
    assert_eq!(speakers.len(), 1);
    let s = &speakers[0];
    assert_eq!((s.pid, s.name.as_str(), s.model.as_str(), s.volume, s.is_muted),
               (PID, "Salon", "HEOS Bar", 30, false));

    ctl.set_volume(PID, 55).await.unwrap();
    assert_eq!(wait_for(&rec, 1).await[0], (PID, 55, false));

    ctl.toggle_mute(PID).await.unwrap();
    assert_eq!(wait_for(&rec, 2).await[1], (PID, 55, true));
    ctl.toggle_mute(PID).await.unwrap();
    assert_eq!(wait_for(&rec, 3).await[2], (PID, 55, false));
}

#[tokio::test]
async fn rejects_bad_input_and_requires_connection() {
    let ctl = HeosController::new();
    assert!(matches!(ctl.set_volume(1, 50).await, Err(HeosError::NotConnected)));
    assert!(matches!(ctl.toggle_mute(1).await, Err(HeosError::NotConnected)));
    assert!(matches!(ctl.get_speaker_levels().await, Err(HeosError::NotConnected)));
    assert!(matches!(ctl.set_speaker_level(SpeakerRole::Main, 1).await, Err(HeosError::NotConnected)));
    assert!(matches!(ctl.set_volume(1, 101).await, Err(HeosError::InvalidArgument { .. })));
    assert!(matches!(
        ctl.connect("  ".into(), Box::new(Recorder::default())).await,
        Err(HeosError::InvalidArgument { .. })
    ));
}

#[tokio::test]
async fn connect_failure_is_reported() {
    // Port fermé : on réserve un port puis on libère le listener.
    let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap().to_string();
    drop(l);
    let r = HeosController::new().connect(addr, Box::new(Recorder::default())).await;
    assert!(matches!(r, Err(HeosError::Connection { .. })), "{r:?}");
}
