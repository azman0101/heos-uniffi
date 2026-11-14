//! Read-only discovery, including bonded surrounds and subwoofers.
#[tokio::main]
async fn main() {
    let host = std::env::args().nth(1).unwrap_or_else(|| "192.168.1.12".into());
    match heos_core::discover_reboot_devices(host).await {
        Ok(devices) => devices.iter().for_each(|device| println!("{device:?}")),
        Err(error) => eprintln!("Discovery failed: {error}"),
    }
}
