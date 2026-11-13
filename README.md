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

Depuis Termux : `pkg install gh openjdk-21 openssl-tool`, puis `gh auth login`
et `bash scripts/setup-signing-termux.sh`. Le script réutilise `~/.android/debug.keystore`
s'il existe, sinon crée une clé dédiée. **Pour mettre à jour une installation existante,
importer d'abord son keystore d'origine** : une nouvelle clé ne peut pas remplacer sa signature.
Le debug keystore standard utilise l'alias `androiddebugkey` et le mot de passe `android`.

Le script conserve la clé et `signing.env` dans `~/.config/heos-fader/`, hors dépôt,
et envoie quatre secrets avec `gh secret set` : `HEOS_KEYSTORE_BASE64`,
`HEOS_KEYSTORE_PASSWORD`, `HEOS_KEY_ALIAS`, `HEOS_CERT_SHA256`.
Sauvegarder ce dossier et le copier de manière sécurisée sur chaque machine de build ;
GitHub ne permet pas de relire les valeurs des secrets après envoi.

Avant tout build local, charger le même matériel :

```bash
source "$HOME/.config/heos-fader/signing.env"
cd android
./gradlew :app:assembleDebug
```

CI restaure le keystore temporairement, signe l'APK, vérifie le SHA-256 du certificat,
puis supprime le fichier. Les PR de forks sans accès aux secrets restent compilables :
leur artefact `-temporary` ne permet pas une mise à jour de l'installation signée avec
la clé partagée. Le même identifiant d'application et un versionCode non inférieur
restent nécessaires pour une mise à jour. Le script ne régénère pas une clé existante.
