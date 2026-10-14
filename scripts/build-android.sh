#!/usr/bin/env bash
# Compile heos_core pour Android (jniLibs) puis régénère les bindings Kotlin.
# Prérequis : rustup target add aarch64-linux-android x86_64-linux-android
#             cargo install cargo-ndk ; ANDROID_NDK_HOME défini.
set -euo pipefail
cd "$(dirname "$0")/.."

cargo ndk -t arm64-v8a -t x86_64 \
  -o android/app/src/main/jniLibs build -p heos_core --release

scripts/gen-kotlin.sh
