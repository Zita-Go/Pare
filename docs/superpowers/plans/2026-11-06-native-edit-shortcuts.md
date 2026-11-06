# Native Edit Shortcuts Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. A separate agent owns the isolated native regression scripts.

**Goal:** Restore standard macOS text editing shortcuts, including Command-A, in production Step.

**Architecture:** Install a native application/Edit menu at launch; route text editing selectors through the responder chain with nil targets. Share the same Edit menu with the screenshot fixture so its keyboard behavior cannot silently differ from production.

**Tech Stack:** AppKit NSMenu, NSTextView/field editor, existing Swift application.

**Spec:** User reports input now works when desktop automation stops, but Command-A cannot select all. Production has no mainMenu; ReadmeDemo has an Edit menu. Restore expected macOS behavior without changing focus logic or global hotkeys.

## Global Constraints

- No desktop focus grabbing, confirmation dialogs or system event injection during native regression checks.
- Use fictional Unicode text; do not touch general pasteboard, user tasks, credentials or focus timers.
- Keep bundle/storage/sync identities and approved icon unchanged. Android is unaffected.
- Preserve any live capture draft when delivering the update; do not force a restart.
- Existing task branch codex/android-sync; no remote push or release.

## Review Focus

- Command-A selects all UTF-16 text in both NSTextView and field-editor mode.
- C/X/V/Z/Shift-Z resolve to standard selectors; bare A and extra-modifier combinations are not captured.
- Production menu items retain nil targets for the current responder; no fixed task or text field receives commands.
- Native fixture does not activate, display windows or use the user's clipboard.
- Production and demo use the same edit-menu helper.

## Tasks

- [x] Reproduce missing-menu failure in isolated native regression: Unicode full-selection checks and edit-route checks fail; no-window/nonactive guards pass.
- [x] Add AppMenus, install at production launch, and share Edit menu with ReadmeDemo.
- [x] Run native regression GREEN, Swift suite and release build; independently review scope/response routing.
- [x] Deliver Mac 2.15.2/build44 safely, retaining any live draft until user-controlled restart; document verification limits.

## Results

- RED: `bash Scripts/verify-edit-menu.sh --baseline` compiles and exits1; actual NSTextView and field editor remain at {24,0}, expected {0,24}; total7 failures. No modified/bare-key interception and no activation/window display.

- GREEN: 16 native checks pass. The native redo probe caught the need for uppercase `Z` with the Shift modifier; the fixture now preserves Shift in charactersIgnoringModifiers as real events do.
- Final `swift test`:343 passed,0 failures; release build/plist/signature pass. Independent review: no blockers.
- Native regression explicitly targets an isolated NSTextView/field editor or selector recorder; it validates menu behavior without stealing focus, not a full real-window responder-chain walkthrough.
- Installed2.15.2/build44 in place with private old-bundle backup; existing2.15.1 process retained without quitting or activating it. The user must save any live draft and normally quit/reopen to load the new binary. No task/store/clipboard access in installation.
- No remote publication. Android remains0.14.1.
