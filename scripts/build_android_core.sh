#!/usr/bin/env bash
set -euo pipefail

# Cross-compile Rust `core-ffi` for Android arm64-v8a + generate UniFFI Kotlin bindings into `android/`.
# Prerequisites: rustup target add aarch64-linux-android && cargo install cargo-ndk

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

rustup target add aarch64-linux-android >/dev/null 2>&1 || true

JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs"
KOTLIN_OUT="$ROOT_DIR/android/app/src/main/java"
mkdir -p "$JNI_DIR"

echo "==> Building liblocal_interpreter_core.so for arm64-v8a..."
cargo ndk -t arm64-v8a -o "$JNI_DIR" build --release -p core-ffi

echo "==> Generating UniFFI Kotlin bindings..."
cargo run --release -p core-ffi --bin uniffi-bindgen -- \
  generate --library "$JNI_DIR/arm64-v8a/liblocal_interpreter_core.so" \
  --language kotlin \
  --out-dir "$KOTLIN_OUT"

echo "✅ Android arm64-v8a .so and Kotlin UniFFI bindings updated!"
