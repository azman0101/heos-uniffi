//! heos_core — pilotage Denon HEOS (CLI TCP 1255) depuis Kotlin via UniFFI.

mod channels;
mod connection;
mod controller;
mod error;
mod protocol;
mod upnp;

pub use channels::{SpeakerChannel, SpeakerRole};
pub use controller::{HeosController, HeosSpeaker, VolumeEventListener, HEOS_PORT};
pub use error::HeosError;

uniffi::setup_scaffolding!();
