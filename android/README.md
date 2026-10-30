# Pare Android

Kotlin + Compose offline client, Android 8.0 (API 26) or newer. The first build covers capture, tasks, completion, Today selection / daily focus, ideas, email/password login and manual / foreground sync. Focus timers, AI, notifications and background work are not implemented in this increment.

## Build

Install JDK 17, Android SDK command-line tools, `platforms;android-36`, `build-tools;35.0.0`, and `platform-tools`. SDK licenses must be accepted by the developer. Use `ANDROID_HOME` for an existing SDK, or install it at `android/.toolchain/sdk`. `JAVA_HOME` can point to any JDK 17. `build.sh` also finds Homebrew openjdk@17 or the local `.toolchain/jdk` fallback used on this machine.

From repository root:

```sh
./android/build.sh testDebugUnitTest assembleDebug lintDebug
```

The script generates ignored `local.properties` from the ignored `backend/supabase/project.local.json` (`api_url`, `publishable_key`). Without this local configuration, the app builds and works offline; login reports that sync is not configured. No administrator key is needed. Only the public URL/key are compiled into the APK; never put a service-role key in client configuration.

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. The checked-in Gradle wrapper pins Gradle 8.13 and its official SHA256. Build dependencies are pinned in the Gradle files. Build and tooling caches are ignored.

## Data and synchronization

A single SQLite transaction writes the complete durable sync snapshot in bounded 64 KiB rows (schema v2 migrates v1 snapshots inside SQLite, avoiding the per-row CursorWindow limit): content, base revisions, conflict copies, cursor, device ID and immutable pending RPC request. The immutable operation ID survives a lost response; editing while a request is in flight does not erase subsequent edits. Remote apply does not produce an upload echo. Unknown JSON fields are preserved when mobile edits supported fields.

The first login pulls before uploading and requires an explicit merge/enable action. The cloud scope includes tasks, inbox, ideas, goals, date/time-zone day plans and the whitelisted profile; only the first three and day-plan selection have mobile editing UI. Credentials are kept outside the database in AES/GCM ciphertext backed by Android Keystore. Android backup and cleartext HTTP are disabled.

Signing out pauses uploads and retains pending work bound to that account. This initial client refuses binding the same database to another user, preventing accidental cross-account uploads; account-local database switching is not yet implemented. Reinstalling or clearing app storage removes local data and tokens, including unuploaded edits.

The status distinguishes offline/local, pending upload, syncing, merged and conflict states. Foreground sync runs on resume and after local edits while enabled. Sync is not guaranteed while the app is suspended. Conflicting local operations and remote commits are held as complete groups. Choices are collected durably and the group is applied only after every member has a choice; newer remote changes reset stale choices. Typed payload validation runs before cursor advancement. Timestamps are never used as a last-write-wins policy.

No live account login, real-task upload, device installation or real-device/network validation is part of the automated tests. Debug APKs are development builds, not production signed releases.

## Current acceptance evidence

The local `testDebugUnitTest`, `assembleDebug`, and `lintDebug` checks pass with 29 tests and zero test failures; lint reports zero errors and ten advisory dependency/KTX warnings. The APK signature verifies. An isolated official AOSP API 36 ARM64 emulator (`PareSyncQA`, serial `emulator-5580`) booted and installed the patched APK successfully, then was shut down. Native UI control could not bind the unbundled emulator process, so Today/Organize/Ideas visual smoke and screenshots remain unverified. No physical device was connected or modified.

This is the first task/idea sync increment. Mobile focus timers, AI, notifications, background sync and production release signing are still future work.
