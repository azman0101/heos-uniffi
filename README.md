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

## APK dans GitHub Actions

Le workflow [HEOS Fader APK](.github/workflows/heos-apk.yml) teste le cœur Rust,
reconstruit les bibliothèques ARM64/x86_64 et les bindings Kotlin, puis produit
l'APK de `:app` avec JDK 21, Android API 36 et NDK `30.0.16248370`.
Il vérifie la présence de `libheos_core.so` et `libjnidispatch.so` pour chaque ABI.
Il ne contacte et ne redémarre aucun appareil réel.

Il se lance pour les PR et les push sur `main` qui touchent le code ou le build,
et manuellement depuis **Actions → HEOS Fader APK → Run workflow** une fois le
workflow présent sur la branche par défaut. Sur une exécution réussie, télécharger
**Artifacts → heos-fader-debug-apk-shared** (ou `-temporary` sans secrets), puis extraire `app-debug.apk` (conservé 14 jours).
Le prototype `:presence` n'est pas inclus dans cet artefact.

### Signature identique en local et dans CI

Sur le MacBook, après authentification avec `gh`, exécuter :

```bash
bash scripts/setup-signing.sh
```

Le script réutilise `~/.android/debug.keystore` (alias `androiddebugkey`, mot de passe
standard `android`) et envoie quatre secrets : `HEOS_KEYSTORE_BASE64`,
`HEOS_KEYSTORE_PASSWORD`, `HEOS_KEY_ALIAS`, `HEOS_CERT_SHA256`.
Il ne remplace ni ne génère la clé : les APK signés localement conservent leur identité.
Sauvegarder ce keystore de manière sécurisée ; GitHub ne permet pas de relire les secrets.

Les builds locaux utilisent déjà ce fichier par défaut. Pour le sélectionner explicitement :

```bash
export HEOS_KEYSTORE_FILE="$HOME/.android/debug.keystore"
export HEOS_KEYSTORE_PASSWORD=android
export HEOS_KEY_ALIAS=androiddebugkey
cd android
./gradlew :app:assembleDebug
```

CI restaure le même keystore temporairement, signe l'APK, vérifie le SHA-256 du
certificat puis supprime le fichier. Les PR de forks sans accès aux secrets restent
compilables : leur artefact `-temporary` ne permet pas une mise à jour de l'installation
signée avec la clé partagée. Le même identifiant d'application et un versionCode non
inférieur restent nécessaires pour une mise à jour.
