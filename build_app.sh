#!/usr/bin/env bash
set -euo pipefail

# Build Step.app — a menu-bar-only macOS app — using only the Swift toolchain.
# No full Xcode required (Command Line Tools are enough).

cd "$(dirname "$0")"

APP_NAME="Step"
# Keep executable and bundle identity stable for existing data and permissions.
EXECUTABLE_NAME="Pare"
BUNDLE_ID="com.zitago.Pare"
VERSION="2.16.0"
BUILD="45"
ICON_MASTER="Resources/AppIcon.png"
STATUS_ICON="Resources/StepStatusIcon.pdf"

for resource in "$ICON_MASTER" "$STATUS_ICON"; do
  if [[ ! -f "$resource" ]]; then
    echo "error: required app artwork not found at $resource" >&2
    exit 1
  fi
done

echo "==> swift build -c release"
swift build -c release

BIN_PATH="$(swift build -c release --show-bin-path 2>/dev/null)"
EXE="$BIN_PATH/$EXECUTABLE_NAME"
if [[ ! -x "$EXE" ]]; then
  echo "error: built executable not found at $EXE" >&2
  exit 1
fi

APP="dist/$APP_NAME.app"
echo "==> assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$EXE" "$APP/Contents/MacOS/$EXECUTABLE_NAME"
cp "$STATUS_ICON" "$APP/Contents/Resources/"

# Only a public client key belongs in the bundle. Local project configuration
# stays ignored by Git; this filter never copies credentials or admin fields.
SYNC_CONFIG="${PARE_SYNC_CONFIGURATION_FILE:-backend/supabase/project.local.json}"
if [[ -f "$SYNC_CONFIG" ]]; then
  python3 - "$SYNC_CONFIG" "$APP/Contents/Resources/SyncConfiguration.json" <<'PY'
import json, pathlib, sys
from urllib.parse import urlparse
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
url, key = value.get('api_url', ''), value.get('publishable_key', '')
parts = urlparse(url)
if parts.scheme != 'https' or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
    raise SystemExit('Invalid sync project URL')
if not key.startswith('sb_publishable_'):
    raise SystemExit('Bundle configuration requires a publishable client key')
pathlib.Path(sys.argv[2]).write_text(json.dumps({'api_url': url, 'publishable_key': key}))
PY
fi

echo "==> generating AppIcon.icns"
ICON_TMP="$(mktemp -d)"
trap 'rm -rf "$ICON_TMP"' EXIT
ICONSET="$ICON_TMP/AppIcon.iconset"
mkdir -p "$ICONSET"
make_icon() {
  local pixels="$1"
  local filename="$2"
  sips -z "$pixels" "$pixels" "$ICON_MASTER" --out "$ICONSET/$filename" >/dev/null
}
make_icon 16   icon_16x16.png
make_icon 32   icon_16x16@2x.png
make_icon 32   icon_32x32.png
make_icon 64   icon_32x32@2x.png
make_icon 128  icon_128x128.png
make_icon 256  icon_128x128@2x.png
make_icon 256  icon_256x256.png
make_icon 512  icon_256x256@2x.png
make_icon 512  icon_512x512.png
make_icon 1024 icon_512x512@2x.png
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>${APP_NAME}</string>
    <key>CFBundleDisplayName</key><string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key><string>${BUNDLE_ID}</string>
    <key>CFBundleExecutable</key><string>${EXECUTABLE_NAME}</string>
    <key>CFBundleIconFile</key><string>AppIcon.icns</string>
    <key>CFBundleIconFiles</key>
    <array><string>AppIcon.icns</string></array>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>${VERSION}</string>
    <key>CFBundleVersion</key><string>${BUILD}</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSHumanReadableCopyright</key><string>Step</string>
</dict>
</plist>
PLIST

echo "==> validating Info.plist"
plutil -lint "$APP/Contents/Info.plist"

echo "==> ad-hoc code signing"
codesign --force --sign - "$APP"

echo ""
echo "✅ 完成：$APP"
echo "启动： open \"$APP\"      （或在 Finder 里双击 Step.app）"
echo "安装： 把 dist/Step.app 拖进「应用程序」文件夹即可长期使用。"
