#!/usr/bin/env bash
set -euo pipefail

# Build Rust XCFramework + SwiftUI release binary and package LocalInterpreterMac.app on macOS.
# Usage: ./scripts/package_macos_app.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT_DIR/scripts/build_macos_core.sh"

cd "$ROOT_DIR/macos"
echo "==> Building LocalInterpreterMac (release)..."
swift build -c release

BIN_PATH="$ROOT_DIR/macos/.build/release/LocalInterpreterMac"
APP_DIR="$ROOT_DIR/dist/LocalInterpreterMac.app"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/Contents/MacOS" "$APP_DIR/Contents/Resources"

cp "$BIN_PATH" "$APP_DIR/Contents/MacOS/LocalInterpreterMac"
cp "$ROOT_DIR/macos/Resources/Info.plist" "$APP_DIR/Contents/Info.plist"

if command -v codesign >/dev/null 2>&1; then
  echo "==> Ad-hoc signing LocalInterpreterMac.app..."
  codesign --force --deep --sign - "$APP_DIR"
fi

echo "✅ Packaged macOS app bundle at: $APP_DIR"
echo "   Launch via: open \"$APP_DIR\""
