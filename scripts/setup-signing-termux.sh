#!/usr/bin/env bash
# Run in Termux after: pkg install gh openjdk-21 openssl-tool
# Import ~/.android/debug.keystore first to keep the identity of an existing APK.
# Keep $HOME literal in the portable environment file and instructions.
# shellcheck disable=SC2016
set -euo pipefail
umask 077
repo=azman0101/heos-uniffi
signing_dir="$HOME/.config/heos-fader"
mkdir -p "$signing_dir"

if [[ ! -f "$signing_dir/signing.env" ]]; then
  if [[ -f "$signing_dir/heos.keystore" ]]; then
    echo "Keystore exists without signing.env; restore its credentials instead of overwriting it." >&2
    exit 1
  fi
  if [[ -f "$HOME/.android/debug.keystore" ]]; then
    cp "$HOME/.android/debug.keystore" "$signing_dir/heos.keystore"
    signing_password=android
    signing_alias=androiddebugkey
  else
    signing_password=$(openssl rand -hex 32)
    signing_alias=heos
    export HEOS_KEYSTORE_PASSWORD="$signing_password"
    keytool -genkeypair -noprompt -storetype PKCS12 \
      -keystore "$signing_dir/heos.keystore" -alias "$signing_alias" \
      -storepass:env HEOS_KEYSTORE_PASSWORD -keypass:env HEOS_KEYSTORE_PASSWORD \
      -keyalg RSA -keysize 3072 -validity 10000 \
      -dname 'CN=HEOS Fader'
  fi
  {
    printf 'export HEOS_KEYSTORE_FILE="$HOME/.config/heos-fader/heos.keystore"\n'
    printf 'export HEOS_KEYSTORE_PASSWORD=%q\n' "$signing_password"
    printf 'export HEOS_KEY_ALIAS=%q\n' "$signing_alias"
  } > "$signing_dir/signing.env"
fi
# shellcheck disable=SC1091
source "$signing_dir/signing.env"
chmod 600 "$HEOS_KEYSTORE_FILE" "$signing_dir/signing.env"
# DER certificate digest: portable and independent of keytool's output language.
certificate=$(mktemp)
trap 'rm -f "$certificate"' EXIT
keytool -exportcert -keystore "$HEOS_KEYSTORE_FILE" -alias "$HEOS_KEY_ALIAS" \
  -storepass:env HEOS_KEYSTORE_PASSWORD > "$certificate"
cert_sha256=$(sha256sum "$certificate" | cut -d ' ' -f 1)
base64 < "$HEOS_KEYSTORE_FILE" | gh secret set HEOS_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$HEOS_KEYSTORE_PASSWORD" | gh secret set HEOS_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$HEOS_KEY_ALIAS" | gh secret set HEOS_KEY_ALIAS --repo "$repo"
printf '%s' "$cert_sha256" | gh secret set HEOS_CERT_SHA256 --repo "$repo"
echo "Signing secrets uploaded. Certificate SHA-256: $cert_sha256"
echo "Back up $signing_dir securely. Copy that folder to each build machine."
echo 'Before a local build: source "$HOME/.config/heos-fader/signing.env"'
