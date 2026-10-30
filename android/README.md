# Pare Android

Kotlin + Compose offline client, Android 8.0 (API 26) or newer. Version 0.5.0 covers capture, task details, completion, Today selection / daily focus, idea exploration with linked actions, personal/check time planning, blocker support, email/password login and automatic foreground/background synchronization. Focus timers, AI and notifications remain future work.

The 0.4 research-led layout uses compact task rows, a 56dp app bar, destination-only navigation, title-first capture and full task details, with a neutral system light/dark palette. Green identifies main actions; gold identifies Pare's daily focus. Explicit inbox classification preserves the four-quadrant workflow. See [product principles](../docs/product-principles.md), [design rationale](../docs/superpowers/specs/2026-10-30-android-product-design.md) and [0.4 layout evidence](../docs/releases/android-v0.4.0.md).

0.5 adds Pare’s own idea-to-action workflow: record a question, finding or decision in an idea workspace, derive a linked action and optionally schedule it for today. “Today” separates personal effort from time spent checking external results, independently of the task’s total estimate. Blocker support saves a smaller next step, a stop criterion and a short planned investment; it does not start a timer or call a model. Existing check plans keep their type when shrinking an action. See the [detailed workflow design](../docs/superpowers/specs/2026-10-30-pare-distinctive-workflows.md) and [0.5 screenshots and walkthrough](../docs/releases/android-v0.5.0.md).

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

The first login pulls before uploading and requires an explicit merge/enable action. The cloud scope includes tasks, inbox, ideas, goals, date/time-zone day plans and the whitelisted profile; tasks, inbox, ideas and day-plan selection/allocation have mobile editing UI. Goals and profile remain synchronized without a mobile editing surface. Credentials are kept outside the database in AES/GCM ciphertext backed by Android Keystore. Android backup and cleartext HTTP are disabled.

Signing out pauses uploads and retains pending work bound to that account. This initial client refuses binding the same database to another user, preventing accidental cross-account uploads; account-local database switching is not yet implemented. Reinstalling or clearing app storage removes local data and tokens, including unuploaded edits.

The status distinguishes offline/local, pending upload, syncing, merged and conflict states. Foreground sync runs on resume, after committed edits, when connectivity returns, and every 15 seconds while visible. Background WorkManager jobs use network constraints and unique periodic work (15-minute minimum, subject to OS scheduling) plus pending one-time work. Transient failures back off from 30 seconds up to 5 minutes. A shared engine serializes UI/worker authentication and network operations. Pausing/signing out immediately revokes further automatic calls and cancels scheduled work; an already-sent request may still finish on the server and is safely replayed after renewed consent. Conflicting local operations and remote commits are held as complete groups. Choices are collected durably and the group is applied only after every member has a choice; newer remote changes reset stale choices. Typed payload validation runs before cursor advancement. Timestamps are never used as a last-write-wins policy.

No live account login, real-task upload or physical-device/network validation is part of this round of automated tests. Instrumented UI tests install a separate QA package, com.pare.app.qa, and use fictional data only. Debug APKs are development builds, not production signed releases.

## Current acceptance evidence

`testDebugUnitTest`, `assembleDebug`, `lintDebug`, and `connectedQaAndroidTest` pass: 114 unit tests and 4 on-device UI flows, no failures. Lint reports 0 errors and 13 nonblocking dependency-version/KTX suggestions. Tests cover the actual capture → organize → today → complete → reopen flow, ideas, rotation, stale editor rejection, 1.6× font editing, visible failure feedback, and stable layout during synchronization. New tests cover idea exploration → linked action → today → check allocation → blocker support → completion → retained idea history, exact-ID retries, archived/deleted records, conflict previews, current-day versus future calendar blocks, and explicit draft recovery. Synthetic Android payloads also pass the existing Mac decoder through `Scripts/WorkflowCompatibilityCheck.swift`; this checks wire compatibility, not a new real-account end-to-end sync session. Screenshot artifacts are generated by Compose tests and visually reviewed (1080×2400 full views, 945×2100 for the fixed 360×800dp density fixture); they contain only fictional fixtures.

```sh
./android/build.sh testDebugUnitTest assembleDebug lintDebug connectedQaAndroidTest
```

The `qa` build type has a separate package ID and data directory; it never accesses the user's normal Pare session or database. Formal Compose tests run inside the isolated Android emulator. These are app tests, not a workaround for unavailable desktop CUA control. Physical devices, vendor battery restrictions and long idle sessions remain to be checked.

The editor keeps drafts during failed writes and rotation. If the editable fields changed remotely or the item was deleted while the editor was open, save is rejected without losing the draft; the user may compare and explicitly load the latest version. Revision-only acknowledgements and unedited session/history fields do not cause false conflicts. Draft switching asks before discarding another unsaved draft.

New workflow screenshots: [idea workspace](../docs/images/android/v0.5/idea-workspace.png), [check planning](../docs/images/android/v0.5/check-plan.png), [small next step](../docs/images/android/v0.5/small-step.png). Dialog captures contain only the window; the small-step capture contains the list region.

Baseline screenshots: [Today](../docs/images/android/v0.4/today.png), [Organize](../docs/images/android/v0.4/organize.png), [Ideas](../docs/images/android/v0.4/ideas.png), [large-font error feedback](../docs/images/android/v0.4/editor-large-font.png).

Future development follows Pare’s own idea-to-action, planning and low-motivation support workflow, not competitor feature lists. Mobile focus timers, AI, notifications, account-local database switching and production release signing remain future work. An invalid login currently recovers via sync details → sign out → sign in to the same account. The existing server email confirmation landing-page issue is separate from this Android optimization.
