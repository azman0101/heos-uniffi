//! heos_core — pilotage Denon HEOS (CLI TCP 1255) depuis Kotlin via UniFFI.

mod connection;
mod controller;
mod error;
mod levels;
mod protocol;
mod upnp;
mod reboot;
pub use reboot::{discover_reboot_devices, reboot_devices, RebootDevice, RebootResult};

pub use levels::{SpeakerLevel, SpeakerRole};
pub use controller::{HeosController, HeosSpeaker, VolumeEventListener, HEOS_PORT};
pub use error::HeosError;

uniffi::setup_scaffolding!();
