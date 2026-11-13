# AGENTS.md — heos-uniffi

Démo Android pilotant un système Denon HEOS : cœur réseau **Rust** (TCP 1255) + UI **Kotlin / Jetpack Compose**, reliés par **UniFFI** (proc-macros).
Cahier des charges d'origine : [`docs/specification.md`](docs/specification.md). Barre de son de test : `192.168.1.12`.

## Organisation du dépôt

```
Cargo.toml              workspace Rust
heos_core/              crate cdylib+lib : protocole HEOS + API UniFFI
  src/protocol.rs         JSON/`message` (query-string), événements
  src/connection.rs       socket TCP, requête/réponse, timeouts
  src/controller.rs       HeosController, HeosSpeaker, VolumeEventListener
  src/upnp.rs             SOAP/UPnP minimal (port 60006) : service Denon ACT
  src/levels.rs           SpeakerLevel / SpeakerRole : « Niveaux » −12…+12 dB par enceinte
  src/error.rs            HeosError  → HeosException côté Kotlin
  tests/fake_server.rs    test bout-en-bout contre une fausse barre
  examples/probe.rs       sonde CLI contre une vraie barre
uniffi-bindgen/         binaire uniffi-bindgen (workspace, évite la feature `cli` dans la lib Android)
scripts/                gen-kotlin.sh (bindings) · build-android.sh (cargo-ndk + bindings)
android/                projet Gradle : :app (Compose) · :presence (prototype TV, voir plus bas)
docs/                   spécification
```

Générés, **non versionnés** : `android/app/src/main/jniLibs/`, `android/app/src/main/java/uniffi/`, `target/`.

## Commandes

- Tests Rust : `cargo test`
- Sonde réelle (lecture seule) : `cargo run -p heos_core --example probe -- 192.168.1.12 10`
- Bindings Kotlin (sans NDK) : `scripts/gen-kotlin.sh`
- Libs Android + bindings : `scripts/build-android.sh` (prérequis : `cargo install cargo-ndk`, NDK, `rustup target add aarch64-linux-android x86_64-linux-android`)
- Piège toolchain : si `cargo` vient de Homebrew, il n'a pas les cibles Android de rustup. Préfixer le `PATH` avec `~/.rustup/toolchains/stable-*/bin` avant `scripts/build-android.sh` (et définir `ANDROID_NDK_HOME`).
- APK : `cd android && ./gradlew :app:assembleDebug` (JDK 21, `local.properties` avec `sdk.dir`)

## Règles UniFFI (Rust ↔ Kotlin)

### Proc-macros uniquement
- **Jamais de fichier `.udl`** sauf demande explicite.
- `uniffi::setup_scaffolding!();` à la racine de `heos_core/src/lib.rs`.
- `crate-type = ["cdylib", "lib"]` dans `heos_core/Cargo.toml`.

### Export des types
- Structures (data classes Kotlin) : `#[derive(uniffi::Record)]` (ex. `HeosSpeaker`).
- Énumérations : `#[derive(uniffi::Enum)]` ; erreurs : `#[derive(uniffi::Error, thiserror::Error)]` → exceptions Kotlin. Pas de champ nommé `message` dans les variantes d'erreur (collision avec `Exception.message`).
- Contrôleur : `#[derive(uniffi::Object)]` (`HeosController`) avec `connect`, `set_volume`, `toggle_mute`, `disconnect`.

### Asynchrone & callbacks
- `async fn` exportées : `#[uniffi::export(async_runtime = "tokio")]` (→ `suspend fun` Kotlin).
- Événements temps réel (`event/player_volume_changed`, socket dédiée) via une interface de rappel :
  ```rust
  #[uniffi::export(callback_interface)]
  pub trait VolumeEventListener: Send + Sync {
      fn on_volume_changed(&self, pid: i64, new_level: u8, is_muted: bool);
  }
  ```
  Le callback est appelé depuis un thread Rust : côté Kotlin, ne faire que mettre à jour un état, ne pas bloquer.

### Build Android
- NDK : `cargo ndk -t arm64-v8a -t x86_64 -o ./android/app/src/main/jniLibs build -p heos_core --release` (ou plugin Gradle [Gobley](https://gobley.dev) `dev.gobley.uniffi`).
- Kotlin : `cargo run --bin uniffi-bindgen generate --library <lib> --language kotlin --out-dir ./android/app/src/main/java`.
- `build.gradle.kts` : JNA en AAR (`net.java.dev.jna:jna:<version>@aar`) et `kotlinx-coroutines-android` (requis par les bindings async).

## Niveaux par enceinte (écran « Niveaux » de l'app HEOS)
Le HEOS CLI n'expose que la **barre** (leader du groupe surround ; caisson et arrières n'ont pas de `pid`).
Les niveaux −12…+12 dB sont dans la config surround de la barre, via UPnP `ACT` (port 60006) :
- Lecture : `GetSurroundSpeakerConfig` ; `level` brut = 12 + dB (0..=24).
- Écriture : `SetSurroundSpeakerConfig` **à la barre uniquement**, avec un **fragment** XML (échappé dans l'élément) :
  `<SurroundSpeakerConfig><Front><Left><level>13</level></Left></Front></SurroundSpeakerConfig>`.
  Renvoyer la config complète donne `500 Internal Error`. Format relevé par capture réseau de l'app officielle (PCAPdroid).
- Rôles → chemin : barre = `Front/Left` (**vérifié**) ; arrière G/D = `Rear/Left|Right` (**vérifiés à l'oreille**, malgré `Rear/enabled=0`) ;
  caisson = `Subwoofer/Subwoofer` (**déduit, à confirmer**).
- La config contient aussi `Front/Right` et une `distance` par enceinte : effet non testé, ne pas les écrire sans accord explicite.
- API : `get_speaker_levels()`, `set_speaker_level(role, db)` (−12..=12).
- Le volume général reste `set_volume(pid)` (CLI). Les volumes UPnP `RenderingControl` de chaque appareil
  existent mais n'affectent pas ce que l'app HEOS affiche : ne pas les utiliser pour « le niveau d'une enceinte ».
- Ne pas écrire la config d'enceintes sur le vrai matériel sans accord explicite de l'utilisateur.

## Connexion au lancement
L'app essaie la dernière IP validée (défaut `192.168.1.12`, `SharedPreferences`) en moins de 3 s, sans afficher la saisie d'IP.
`connect` n'aboutit que si l'hôte contacté figure dans `get_players` avec un modèle contenant « bar » (sinon `NotASoundbar`) :
`get_players` liste tout le système depuis n'importe quel appareil, le modèle seul ne suffit donc pas.
Sur échec ou délai dépassé, le formulaire s'affiche avec l'erreur. « Déconnecter » y mène aussi, pour changer d'IP.

## Prototype `:presence` (TV Sony)
App Android TV de test, séparée de HEOS Fader : BRAVIA CAM (USB, Camera2) + ML Kit embarqué. TV 32 bits (`armeabi-v7a`).
Elle estime la place (gauche, centre, droite) et décale `Surround G` et `Surround D` (−5 dB côté proche, +7 dB côté éloigné, 0 au centre), seulement tant qu'elle est à l'écran. Les niveaux d'origine sont remis à la sortie, ou à la prochaine ouverture si l'app a été tuée.
La télémétrie de ML Kit est coupée dans le manifeste (`TransportBackendDiscovery` retiré) : à revérifier après toute mise à jour de ML Kit.
Le calibrage de `SeatTracker` vaut pour une seule pièce et une seule position de caméra.

## Protocole HEOS : points d'attention
- `pid` est un **entier signé** (la barre de test a `pid = -196792939`) → `i64`.
- Deux sockets : commandes (`register_for_change_events?enable=off`) et événements (`enable=on`).
- Les réponses « command under process » et les événements intercalés sont ignorés par `Connection::request`.
