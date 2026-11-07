#!/bin/bash
# 把「本地同传」打包成可双击运行的 macOS 应用
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="本地同传"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
BUNDLE="$DIST/$APP_NAME.app"
LITE="${LITE:-0}"
VERSION="2.2.3"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

echo "==> 准备独立 Python 运行时"
if [ ! -x "$BUILD/python/bin/python3.12" ] || [ ! -d "$BUILD/site-packages" ]; then
  rm -rf "$BUILD/python" "$BUILD/site-packages"
  mkdir -p "$BUILD"
  uv python install 3.12 >/dev/null
  SYS_PY="$(uv python find 3.12)"
  cp -R "$(dirname "$(dirname "$SYS_PY")")" "$BUILD/python"
  uv pip install \
    --python "$BUILD/python/bin/python3.12" \
    --target "$BUILD/site-packages" \
    -r "$ROOT/requirements.txt" >/dev/null
  echo "    运行时就绪 ($(du -sh "$BUILD/site-packages" | cut -f1))"
else
  echo "    复用已有运行时"
fi

echo "==> 准备内置翻译模型"
BUILTIN_MODEL="$BUILD/builtin-model"
if [ ! -f "$BUILTIN_MODEL/config.json" ]; then
  mkdir -p "$BUILTIN_MODEL"
  PYTHONPATH="$BUILD/site-packages" "$BUILD/python/bin/python3.12" -c "
from huggingface_hub import snapshot_download
snapshot_download('sahilchachra/hy-mt2-1.8b-4bit-mlx', local_dir='$BUILTIN_MODEL')
"
fi
du -sh "$BUILTIN_MODEL" | awk '{print "    内置模型: " $1}'

echo "==> 准备内置助教模型"
ASSISTANT_MODEL="$BUILD/assistant-model"
if [ "$LITE" = "1" ]; then
  echo "    轻量模式：跳过助教模型（首次需要时在应用内下载）"
  mkdir -p "$ASSISTANT_MODEL"
else
  if [ ! -f "$ASSISTANT_MODEL/config.json" ]; then
    mkdir -p "$ASSISTANT_MODEL"
    PYTHONPATH="$BUILD/site-packages" "$BUILD/python/bin/python3.12" -c "
from huggingface_hub import snapshot_download
snapshot_download('mlx-community/Qwen3-4B-Instruct-2507-4bit', local_dir='$ASSISTANT_MODEL')
"
  fi
  du -sh "$ASSISTANT_MODEL" | awk '{print "    助教模型: " $1}'
fi

echo "==> 生成图标"
if [ -x "$ROOT/.venv/bin/python" ]; then
  "$ROOT/.venv/bin/python" "$ROOT/tools/make_icon.py" "$BUILD/icon_1024.png"
else
  PYTHONPATH="$BUILD/site-packages" "$BUILD/python/bin/python3.12" "$ROOT/tools/make_icon.py" "$BUILD/icon_1024.png"
fi
ICONSET="$BUILD/icon.iconset"
rm -rf "$ICONSET" && mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z "$s" "$s" "$BUILD/icon_1024.png" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z "$((s * 2))" "$((s * 2))" "$BUILD/icon_1024.png" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
cp "$BUILD/icon_1024.png" "$ICONSET/icon_512x512@2x.png"
iconutil -c icns "$ICONSET" -o "$BUILD/icon.icns"

echo "==> 编译原生外壳"
swiftc -O -swift-version 5 -target arm64-apple-macos13.0 \
  -framework AppKit -framework WebKit -framework AVFoundation \
  -framework ScreenCaptureKit -framework CoreMedia -framework CoreGraphics \
  "$ROOT/native/"*.swift -o "$BUILD/本地同传"

echo "==> 编译 PDF OCR 助手"
swiftc -O -swift-version 5 -target arm64-apple-macos13.0 \
  -framework AppKit -framework PDFKit -framework Vision \
  "$ROOT/tools/pdfocr.swift" -o "$BUILD/pdfocr"

echo "==> 组装 $APP_NAME.app"
rm -rf "$BUNDLE"
mkdir -p "$BUNDLE/Contents/MacOS" "$BUNDLE/Contents/Resources"

mkdir -p "$BUNDLE/Contents/Resources/source"
cp -R "$ROOT/app" "$BUNDLE/Contents/Resources/source/app"
mkdir -p "$BUNDLE/Contents/Resources/source/models"
cp -R "$BUILTIN_MODEL" "$BUNDLE/Contents/Resources/source/models/hy-mt2-1.8b-4bit"
if [ "$LITE" = "1" ]; then
  mkdir -p "$BUNDLE/Contents/Resources/source/models"
  touch "$BUNDLE/Contents/Resources/source/models/lite"
else
  cp -R "$ASSISTANT_MODEL" "$BUNDLE/Contents/Resources/source/models/assistant"
fi
find "$BUNDLE/Contents/Resources/source" -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true

cp -R "$BUILD/python" "$BUNDLE/Contents/Resources/python"
cp -R "$BUILD/site-packages" "$BUNDLE/Contents/Resources/site-packages"
mkdir -p "$BUNDLE/Contents/Resources/source/bin"
cp "$BUILD/pdfocr" "$BUNDLE/Contents/Resources/source/bin/pdfocr"
chmod +x "$BUNDLE/Contents/Resources/source/bin/pdfocr"
cp "$BUILD/icon.icns" "$BUNDLE/Contents/Resources/icon.icns"

cp "$BUILD/本地同传" "$BUNDLE/Contents/MacOS/本地同传"
chmod +x "$BUNDLE/Contents/MacOS/本地同传"

cat > "$BUNDLE/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>本地同传</string>
  <key>CFBundleDisplayName</key><string>本地同传</string>
  <key>CFBundleExecutable</key><string>本地同传</string>
  <key>CFBundleIdentifier</key><string>com.local.interpreter</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleVersion</key><string>2.2.3</string>
  <key>CFBundleShortVersionString</key><string>2.2.3</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleIconFile</key><string>icon</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.productivity</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSMicrophoneUsageDescription</key><string>用于采集麦克风声音并在本机实时翻译。</string>
  <key>NSAudioCaptureUsageDescription</key><string>用于采集系统声音并在本机实时翻译。</string>
  <key>NSAppTransportSecurity</key>
  <dict>
    <key>NSAllowsLocalNetworking</key><true/>
  </dict>
</dict>
</plist>
PLIST

printf 'APPL????' > "$BUNDLE/Contents/PkgInfo"

echo "==> 签名（本地 ad-hoc）"
codesign --force --deep --sign - "$BUNDLE" >/dev/null 2>&1 || echo "    签名跳过（不影响本机运行）"

echo
echo "完成：$BUNDLE"
echo "大小：$(du -sh "$BUNDLE" | cut -f1)"

echo "==> 生成 DMG"
if [ "$LITE" = "1" ]; then
  DMG="$DIST/本地同传-${VERSION}-lite.dmg"
else
  DMG="$DIST/本地同传-${VERSION}.dmg"
fi
rm -f "$DMG"
hdiutil create -volname "本地同传" -srcfolder "$BUNDLE" -ov -format UDZO "$DMG" >/dev/null 2>&1 || echo "    DMG 生成失败（应用本身不受影响）"
[ -f "$DMG" ] && echo "DMG：$DMG ($(du -sh "$DMG" | cut -f1))"
