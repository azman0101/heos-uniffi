#!/usr/bin/env bash
# Génère les bindings Kotlin (uniffi/heos_core/heos_core.kt) depuis la lib hôte.
# Ne nécessite ni NDK ni Android SDK.
set -euo pipefail
cd "$(dirname "$0")/.."

cargo build -p heos_core
case "$(uname -s)" in
  Darwin) LIB=target/debug/libheos_core.dylib ;;
  *)      LIB=target/debug/libheos_core.so ;;
esac

cargo run -q --bin uniffi-bindgen generate \
  --library "$LIB" --language kotlin \
  --out-dir android/app/src/main/java
echo "Bindings Kotlin → android/app/src/main/java/uniffi/heos_core/heos_core.kt"
