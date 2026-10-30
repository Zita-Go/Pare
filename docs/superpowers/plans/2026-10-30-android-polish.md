# Android automatic sync and mobile usability implementation plan

Goal: extend the approved Android/Mac sync design into an Android client that normally synchronizes without button presses, and make frequent mobile actions clear and accessible.
Spec: `docs/superpowers/specs/2026-10-29-android-sync-design.md`.

Execution: existing `codex/android-sync` branch, with separate sync and UI file ownership. Existing README edits belong to the prior documentation correction and remain intact. No real account login, credentials copying, backend configuration changes, or Mac installation changes.

## Contract and constraints

- Existing RPC, revision checks, immutable retries and atomic groups stay compatible with Mac.
- Only a signed-in account with confirmed synchronization consent may upload automatically. Pausing/signing out cancels scheduled work; queued content is retained.
- Foreground: sync on resume and after edits, periodically pull while visible, retry after connectivity returns. No overlapping token refresh or requests between UI and worker.
- Background: WorkManager with network constraints and bounded retry; no foreground-service notification, exact alarm or battery-exemption request. Periodic jobs use Android's supported minimum interval; no promise of real-time locked-screen sync.
- Session stays encrypted in Android Keystore; task data and outbox commit together in SQLite. Failed local saving keeps the editing draft open.
- Keep Today/Organize/Ideas navigation. Warm restrained surfaces, consistent icons and rounded corners, 48dp touch targets, readable large-font layout. Account and conflict details remain secondary, not a permanent control dashboard.
- Formal Android UI tests may verify the app in its isolated emulator; do not replace unavailable desktop CUA with ad hoc desktop input automation. No real tasks or passwords in screenshots or fixtures.

## Tasks

1. Sync agent owns `PareViewModel.kt`, `PareRepository.kt`, `SessionVault.kt`, new sync engine/scheduler/worker, and related unit tests. Preserve existing UI-facing methods and flows; add `onBackground()` and expose preview readiness if needed. Test serial UI/worker execution, lost response, pause/logout while queued, retry after offline, consent/account gates and snapshot observer updates.
2. UI agent owns `MainActivity.kt`, new UI/theme/components and presentation tests. Add safe draft-preserving asynchronous save callback contract with sync agent; stable order and translated idea stages; task next action/estimate editing with payload preservation if agent can isolate the helper. Avoid adding complex feature surfaces.
3. Root owns Gradle/manifest/dependencies/version/docs, integration, and emulator instrumentation scaffolding. Tests verify actual capture/edit/complete/navigation on temporary local fixtures, no network account. Build APK and run full unit/lint/instrumented checks.
4. Independent review of sync concurrency/privacy and mobile behavior; fix important findings and record remaining physical-device limits.

## Acceptance

- [x] Automatic sync scheduling/retry and existing sync/persistence tests: 69 unit tests passed.
- [x] Mobile editor retains draft on failure and rotation; accessible labels and 48dp actions implemented.
- [x] Android APK/lint and isolated emulator UI tests: 2 device tests passed, lint 0 errors/13 dependency or KTX suggestions.
- [x] Independent review fixed stale editor overwrite/resurrection and slow-failure foreground retry loops. Scoped re-review found no remaining P1/P2.
- [x] Actual UI screenshots reviewed at 1080×2400. Additional failing UI tests reproduced an invisible error message and a 43px list shift during sync; pinned feedback and stable header progress fixed both, full suite green afterward.
- [x] APK and documentation prepared; physical devices and extended idle/battery behavior remain unverified.

Ruling: Formal Compose instrumentation uses isolated `com.pare.app.qa` with no real session; it provides interaction and screenshot evidence without ad hoc desktop automation. A shared process engine and per-database locks serialize UI/worker refresh and committed snapshot publication.
Ruling: Invalid login recovery currently requires sync details → sign out → same-account login; this is discoverability debt, not a data-loss or authentication blocker. The email landing-page configuration remains separate work.

Ruling: Extend existing automatic-sync requirement directly; first consent and conflict choices remain explicit. Android notification delivery, focus timers and AI workflows remain separate work from this focused optimization.
