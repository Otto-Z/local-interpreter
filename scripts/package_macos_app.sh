#!/usr/bin/env bash
set -euo pipefail

# Build Rust XCFramework + SwiftUI release binary and package LocalInterpreterMac.app + .dmg on macOS.
# Usage: ./scripts/package_macos_app.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT_DIR/scripts/build_macos_core.sh"

cd "$ROOT_DIR/macos"
echo "==> Building LocalInterpreterMac (release)..."
swift build -c release

BIN_DIR="$(swift build -c release --show-bin-path)"
BIN_PATH="$BIN_DIR/LocalInterpreterMac"
if [[ ! -f "$BIN_PATH" ]]; then
  echo "❌ Expected release binary at $BIN_PATH" >&2
  exit 1
fi

VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$ROOT_DIR/macos/Resources/Info.plist" 2>/dev/null || echo '3.0.0')"
ARCH_TAG="arm64"
if [[ "${UNIVERSAL:-0}" == "1" ]]; then
  ARCH_TAG="universal"
fi

DIST_DIR="$ROOT_DIR/dist"
APP_DIR="$DIST_DIR/LocalInterpreterMac.app"
DMG_STAGING="$DIST_DIR/dmg-staging"
DMG_PATH="$DIST_DIR/LocalInterpreterMac-v${VERSION}-${ARCH_TAG}.dmg"

rm -rf "$APP_DIR" "$DMG_STAGING" "$DMG_PATH"
mkdir -p "$APP_DIR/Contents/MacOS" "$APP_DIR/Contents/Resources"

cp "$BIN_PATH" "$APP_DIR/Contents/MacOS/LocalInterpreterMac"
chmod +x "$APP_DIR/Contents/MacOS/LocalInterpreterMac"
cp "$ROOT_DIR/macos/Resources/Info.plist" "$APP_DIR/Contents/Info.plist"

if command -v codesign >/dev/null 2>&1; then
  echo "==> Ad-hoc signing LocalInterpreterMac.app..."
  codesign --force --deep --sign - "$APP_DIR"
fi

echo "==> Creating DMG installer at $DMG_PATH..."
mkdir -p "$DMG_STAGING"
cp -R "$APP_DIR" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"
hdiutil create \
  -volname "离线同传 LocalInterpreter" \
  -srcfolder "$DMG_STAGING" \
  -ov \
  -format UDZO \
  "$DMG_PATH"
rm -rf "$DMG_STAGING"

echo "✅ Packaged macOS app bundle: $APP_DIR"
echo "✅ Packaged macOS DMG installer: $DMG_PATH"
