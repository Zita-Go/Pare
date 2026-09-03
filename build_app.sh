#!/usr/bin/env bash
set -euo pipefail

# Build Pare.app — a menu-bar-only macOS app — using only the Swift toolchain.
# No full Xcode required (Command Line Tools are enough).

cd "$(dirname "$0")"

APP_NAME="Pare"
BUNDLE_ID="com.zitago.Pare"
VERSION="1.0.0"
BUILD="1"

echo "==> swift build -c release"
swift build -c release

BIN_PATH="$(swift build -c release --show-bin-path 2>/dev/null)"
EXE="$BIN_PATH/$APP_NAME"
if [[ ! -x "$EXE" ]]; then
  echo "error: built executable not found at $EXE" >&2
  exit 1
fi

APP="dist/$APP_NAME.app"
echo "==> assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$EXE" "$APP/Contents/MacOS/$APP_NAME"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>${APP_NAME}</string>
    <key>CFBundleDisplayName</key><string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key><string>${BUNDLE_ID}</string>
    <key>CFBundleExecutable</key><string>${APP_NAME}</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>${VERSION}</string>
    <key>CFBundleVersion</key><string>${BUILD}</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSHumanReadableCopyright</key><string>Pare</string>
</dict>
</plist>
PLIST

echo "==> validating Info.plist"
plutil -lint "$APP/Contents/Info.plist"

echo "==> ad-hoc code signing"
codesign --force --sign - "$APP"

echo ""
echo "✅ 完成：$APP"
echo "启动： open \"$APP\"      （或在 Finder 里双击 Pare.app）"
echo "安装： 把 dist/Pare.app 拖进「应用程序」文件夹即可长期使用。"
