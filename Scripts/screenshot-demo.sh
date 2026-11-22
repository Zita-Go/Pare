#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Build a separate app from the shipping views, never the production launcher.
swift build --target PareCore
PARE_BIN_DIR="$(swift build --show-bin-path)"
PARE_DEMO_DIR="$(mktemp -d /private/tmp/pare-screenshots.XXXXXX)"
PARE_DEMO_NAME="Step Screenshots"
PARE_DEMO_PLIST="Scripts/ReadmeDemo-Info.plist"
if [[ "${1:-}" == "--verify-shortcuts" ]]; then
    PARE_DEMO_NAME="Step Shortcut Check"
    PARE_DEMO_PLIST="Scripts/ShortcutCheck-Info.plist"
fi
PARE_DEMO_APP="$PARE_DEMO_DIR/$PARE_DEMO_NAME.app"
mkdir -p "$PARE_DEMO_APP/Contents/MacOS" "$PARE_DEMO_APP/Contents/Resources"
cp Resources/StepStatusIcon.pdf "$PARE_DEMO_APP/Contents/Resources/"
cp "$PARE_DEMO_PLIST" "$PARE_DEMO_APP/Contents/Info.plist"
PARE_UI_SOURCES=()
for source in Sources/Pare/*.swift; do
    case "$source" in
        */main.swift|*/AppDelegate.swift) continue ;;
    esac
    PARE_UI_SOURCES+=("$source")
done
swiftc -swift-version 5 -parse-as-library -module-name PareScreenshots \
    -I "$PARE_BIN_DIR/Modules" \
    "$PARE_BIN_DIR"/PareCore.build/*.swift.o \
    "${PARE_UI_SOURCES[@]}" Scripts/ReadmeDemo.swift \
    -framework AppKit -framework Carbon -framework ApplicationServices \
    -framework CoreGraphics -framework UserNotifications \
    -o "$PARE_DEMO_APP/Contents/MacOS/PareScreenshots"
codesign --force --sign - "$PARE_DEMO_APP"
printf 'Screenshot demo: %s\n' "$PARE_DEMO_APP"
open -n "$PARE_DEMO_APP" --args "$@"
