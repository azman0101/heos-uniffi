#!/usr/bin/env bash
# Upload the Mac's existing debug signing identity without replacing it.
set -euo pipefail
umask 077
repo=azman0101/heos-uniffi
keystore="$HOME/.android/debug.keystore"
keytool="/opt/homebrew/opt/openjdk@21/bin/keytool"
[[ -f "$keystore" ]] || { echo "Existing signing key not found: $keystore" >&2; exit 1; }
certificate=$(mktemp)
trap 'rm -f "$certificate"' EXIT
"$keytool" -exportcert -keystore "$keystore" -alias androiddebugkey \
  -storepass android > "$certificate"
cert_sha256=$(shasum -a 256 "$certificate" | awk '{print $1}')
base64 < "$keystore" | gh secret set HEOS_KEYSTORE_BASE64 --repo "$repo"
printf '%s' android | gh secret set HEOS_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' androiddebugkey | gh secret set HEOS_KEY_ALIAS --repo "$repo"
printf '%s' "$cert_sha256" | gh secret set HEOS_CERT_SHA256 --repo "$repo"
echo "Signing secrets uploaded. Certificate SHA-256: $cert_sha256"
