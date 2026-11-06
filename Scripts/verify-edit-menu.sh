#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# A separate, non-activating process; no app launch, user store, or clipboard.
# --baseline deliberately omits production commands to demonstrate the failure.
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--baseline" ) ]]; then
    printf 'Usage: %s [--baseline]\n' "$0" >&2
    exit 2
fi
PARE_EDIT_CHECK_DIR="$(mktemp -d /private/tmp/pare-edit-menu.XXXXXX)"
PARE_EDIT_CHECK_FLAGS=(-swift-version 5 -parse-as-library)
PARE_EDIT_CHECK_SOURCES=(Scripts/verify-edit-menu.swift)
if [[ "${1:-}" != "--baseline" && -f Sources/Pare/AppMenus.swift ]]; then
    PARE_EDIT_CHECK_FLAGS+=(-D HAS_APP_MENUS)
    PARE_EDIT_CHECK_SOURCES+=(Sources/Pare/AppMenus.swift)
fi
printf 'Verification log: %s/check.log\n' "$PARE_EDIT_CHECK_DIR"
swiftc \
    -module-cache-path "$PARE_EDIT_CHECK_DIR/module-cache" \
    "${PARE_EDIT_CHECK_FLAGS[@]}" "${PARE_EDIT_CHECK_SOURCES[@]}" \
    -framework AppKit -o "$PARE_EDIT_CHECK_DIR/verify-edit-menu"
"$PARE_EDIT_CHECK_DIR/verify-edit-menu" 2>&1 | tee "$PARE_EDIT_CHECK_DIR/check.log"
