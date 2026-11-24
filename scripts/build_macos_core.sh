#!/usr/bin/env bash
set -euo pipefail

# Build Rust `core-ffi` as a macOS XCFramework + UniFFI Swift bindings into `macos/`.
# Run on macOS with Xcode command line tools & Rust installed:
#   ./scripts/build_macos_core.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

TARGETS=("aarch64-apple-darwin")
if [[ "${UNIVERSAL:-0}" == "1" ]]; then
  TARGETS+=("x86_64-apple-darwin")
fi

for target in "${TARGETS[@]}"; do
  rustup target add "$target" >/dev/null 2>&1 || true
  echo "==> Building core-ffi for $target..."
  cargo build --release -p core-ffi --target "$target"
done

GEN_DIR="$ROOT_DIR/macos/Sources/LocalInterpreterCoreBindings"
HDR_DIR="$ROOT_DIR/target/uniffi-headers"
rm -rf "$GEN_DIR" "$HDR_DIR" "$ROOT_DIR/macos/Frameworks/LocalInterpreterCore.xcframework"
mkdir -p "$GEN_DIR" "$HDR_DIR" "$ROOT_DIR/macos/Frameworks"

FIRST_LIB="$ROOT_DIR/target/${TARGETS[0]}/release/liblocal_interpreter_core.dylib"
echo "==> Generating UniFFI Swift bindings..."
cargo run --release -p core-ffi --bin uniffi-bindgen -- \
  generate --library "$FIRST_LIB" --language swift --out-dir "$HDR_DIR"

cp "$HDR_DIR"/*.swift "$GEN_DIR/"
# Package C headers + modulemap for XCFramework
mkdir -p "$HDR_DIR/include"
cp "$HDR_DIR"/*.h "$HDR_DIR/include/"
cp "$HDR_DIR"/*.modulemap "$HDR_DIR/include/module.modulemap"

if [[ "${#TARGETS[@]}" -eq 2 ]]; then
  echo "==> Creating universal macOS static library..."
  mkdir -p "$ROOT_DIR/target/universal-macos"
  lipo -create \
    "$ROOT_DIR/target/aarch64-apple-darwin/release/liblocal_interpreter_core.a" \
    "$ROOT_DIR/target/x86_64-apple-darwin/release/liblocal_interpreter_core.a" \
    -output "$ROOT_DIR/target/universal-macos/liblocal_interpreter_core.a"
  STATIC_LIB="$ROOT_DIR/target/universal-macos/liblocal_interpreter_core.a"
else
  STATIC_LIB="$ROOT_DIR/target/${TARGETS[0]}/release/liblocal_interpreter_core.a"
fi

echo "==> Packaging LocalInterpreterCore.xcframework..."
xcodebuild -create-xcframework \
  -library "$STATIC_LIB" \
  -headers "$HDR_DIR/include" \
  -output "$ROOT_DIR/macos/Frameworks/LocalInterpreterCore.xcframework"

echo "✅ Done! Build & launch macOS app via: cd macos && swift run"
