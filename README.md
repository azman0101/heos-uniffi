# heos-uniffi

Démo Android qui pilote les enceintes d'un système **Denon HEOS** (volume, mute, retour temps réel de la télécommande).

- **Rust** (`heos_core`) : protocole HEOS CLI sur TCP 1255, tokio + serde.
- **UniFFI** : bindings Kotlin générés (proc-macros, pas d'UDL).
- **Android** : Jetpack Compose — écran de connexion (IP) puis liste d'enceintes (slider + mute).

## Démarrage rapide

```bash
# 1. Tests du moteur (fausse barre intégrée)
cargo test

# 2. Vérifier contre la vraie barre (lecture seule)
cargo run -p heos_core --example probe -- 192.168.1.12 10

# 3. Libs Android + bindings Kotlin
scripts/build-android.sh        # nécessite cargo-ndk + NDK

# 4. App
cd android && ./gradlew :app:installDebug
```

Voir [`AGENTS.md`](AGENTS.md) pour l'organisation du dépôt et les conventions, et [`docs/specification.md`](docs/specification.md) pour le cahier des charges.
