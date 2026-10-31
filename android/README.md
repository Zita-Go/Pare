# Pare Android

Kotlin + Compose offline client, Android 8.0 (API 26) or newer. The 0.9.1 development build has completed focused UI acceptance; it is not a production-signed store release. The previously accepted 0.8.0 baseline covers capture, task details, completion, Today planning, idea exploration with linked actions, blocker support, device-local focus/external execution, Android reminders, contextual AI, local reflection, email/password login and automatic synchronization.

The 0.4 research-led layout uses compact task rows, a 56dp app bar, destination-only navigation, title-first capture and full task details, with a neutral system light/dark palette. Green identifies main actions; gold identifies Pare's daily focus. Explicit inbox classification preserves the four-quadrant workflow. See [product principles](../docs/product-principles.md), [design rationale](../docs/superpowers/specs/2026-10-30-android-product-design.md) and [0.4 layout evidence](../docs/releases/android-v0.4.0.md).

0.5 adds Pare’s own idea-to-action workflow: record a question, finding or decision in an idea workspace, derive a linked action and optionally schedule it for today. “Today” separates personal effort from time spent checking external results, independently of the task’s total estimate. Blocker support saves a smaller next step, a stop criterion and a short planned investment; it does not start a timer or call a model. Existing check plans keep their type when shrinking an action. See the [detailed workflow design](../docs/superpowers/specs/2026-10-30-pare-distinctive-workflows.md) and [0.5 screenshots and walkthrough](../docs/releases/android-v0.5.0.md).

0.6 adds one device-local personal timer and multiple external runs, with durable pause/resume and idempotent finished history. Android notifications cover task/recurrence, current-day calendar, focus and check reminders, with permission-aware timing and private lock-screen content. AI uses explicitly reviewed context, an HTTPS-compatible endpoint, encrypted local credentials/traces and guarded adoption/undo. Local review is read-only and gentle hints never call a model. See [0.6 usage and acceptance](../docs/releases/android-v0.6.0.md).

0.7 unifies warm neutral surfaces, green actions, gold emphasis, typography and control shapes. Compact grouped task rows preserve six visible tasks at 360×800dp. Timer values, idea questions, AI proposals and form sections have distinct hierarchy. Real Dialog text-layout results confirm fontScale 1.6 in editor/planning/support QA; primary actions remain visible and at least 48dp. See [before/after and full-resolution screenshots](../docs/releases/android-v0.7.0.md).

0.8 makes Today a proportional calendar: tap a time to create, reorder or drag unscheduled tasks onto it, move and resize blocks, preview before release, and conditionally undo. Quick capture follows the selected date; the task quick menu has three actions, with secondary tools in details. "Waiting for results" describes external execution without implying control over other AIs. See [0.8 usage/screenshots](../docs/releases/android-v0.8.0.md) and the [honest feature coverage audit](../docs/android-feature-coverage.md).

0.9 adds reviewed day/idea/inbox/goal AI workflows, goals and editable preferences, searchable content management with a device-local recovery bin, existing-task idea links and review dates, separate due/actual-time fields, explicit clock-cued action reminders, eight-week recorded-activity review and aggregate copying. The timeline supports adjacent-date drag targets and an arbitrary date picker. “Save and start five minutes” requests a real timer only after saving succeeds. Optional cross-device running-state summaries are advisory; each device keeps its own timer controls. Sharing defaults off and requires local account/device consent. See the [0.9 coverage increment and remaining boundaries](../docs/android-feature-coverage.md#09-实现增量).

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

The first login pulls before uploading and requires an explicit merge/enable action. The cloud scope includes tasks, inbox, ideas, goals, date/time-zone day plans and the whitelisted profile; tasks, inbox, ideas, day-plan selection/allocation, goals and supported profile preferences have mobile editing UI. Unsupported fields remain preserved rather than being represented as working controls. Credentials are kept outside the database in AES/GCM ciphertext backed by Android Keystore. Android backup and cleartext HTTP are disabled.

Signing out pauses uploads and retains pending work bound to that account. This initial client refuses binding the same database to another user, preventing accidental cross-account uploads; account-local database switching is not yet implemented. Reinstalling or clearing app storage removes local data and tokens, including unuploaded edits.

The status distinguishes offline/local, pending upload, syncing, merged and conflict states. Foreground sync runs on resume, after committed edits, when connectivity returns, and every 15 seconds while visible. Background WorkManager jobs use network constraints and unique periodic work (15-minute minimum, subject to OS scheduling) plus pending one-time work. Transient failures back off from 30 seconds up to 5 minutes. A shared engine serializes UI/worker authentication and network operations. Pausing/signing out immediately revokes further automatic calls and cancels scheduled work; an already-sent request may still finish on the server and is safely replayed after renewed consent. Conflicting local operations and remote commits are held as complete groups. Choices are collected durably and the group is applied only after every member has a choice; newer remote changes reset stale choices. Typed payload validation runs before cursor advancement. Timestamps are never used as a last-write-wins policy.

The 0.8 acceptance below did not include live account login or real-task upload. The [0.9 server acceptance](../backend/supabase/docs/presence-v1-acceptance.md) used isolated QA accounts and two Swift clients, not the Android process or real user data. Instrumented UI tests install a separate QA package, com.pare.app.qa, and use fictional data only. Debug APKs are development builds, not production signed releases.

## Acceptance evidence

0.9: **324 Android unit tests, 29 distinct emulator UI flows, and 343 Mac tests passed**. The final cross-date MainActivity test needed the Material date accessibility selector corrected; all five MainActivity workspace flows then passed on rerun (the other 24 had passed in the full run). Debug build and Lint pass: 0 errors, 26 nonblocking warnings, 1 hint. The 0.9 goal/profile/idea/task/inbox/day-plan payloads also pass the Mac decoder. Presence SQL regression and 18 real HTTPS assertions passed with disposable accounts, whose data and sessions were removed. Mac 2.14.0 is built and ad-hoc signed; the Android debug APK is installed in the existing local emulator. Neither has been published as a new GitHub release. See [0.9 usage/screenshots](../docs/releases/android-v0.9.0.md). The figures below are historical 0.8 results.

### Accepted 0.8 baseline

`testDebugUnitTest`, `assembleDebug`, `lintDebug`, and `connectedQaAndroidTest` pass: 208 unit tests and 17 on-device UI flows, no failures. Lint reports 0 errors and 24 nonblocking warnings and 1 hint. Timeline tests additionally use real pointer gestures for move/resize/reorder/drop, partially clipped tray rows, future-day quick capture, permissions and conditional undo. Unit cases cover DST, cross-midnight, source authority, guarded legacy migration, operation-group bounds and earlier-day reminders. Tests cover the actual capture → organize → today → complete → reopen flow, ideas, rotation, stale editor rejection, actual 1.6× Dialog font editing, visible failure feedback, and stable layout during synchronization. 0.6 tests cover focus/external run coexistence, pause/rotation/relaunch, crash-safe history append, clock/boot changes, notification permissions/recurrence/deduplication/timezones/private clicks, actual Android Keystore, fake-service AI context/preview/adoption/undo including two rounds, and transparent notification icon rendering. No live provider call was made.

0.5 tests cover idea exploration → linked action → today → check allocation → blocker support → completion → retained idea history, exact-ID retries, archived/deleted records, conflict previews, current-day versus future calendar blocks, and explicit draft recovery. Synthetic Android payloads also pass the existing Mac decoder through `Scripts/WorkflowCompatibilityCheck.swift`; this checks wire compatibility, not a new real-account end-to-end sync session. Screenshot artifacts are generated by Compose tests and visually reviewed (1080×2400 full views, 945×2100 for the fixed 360×800dp density fixture); they contain only fictional fixtures.

```sh
./android/build.sh testDebugUnitTest assembleDebug lintDebug connectedQaAndroidTest
```

The `qa` build type has a separate package ID and data directory; it never accesses the user's normal Pare session or database. Formal Compose tests run inside the isolated Android emulator. These are app tests, not a workaround for unavailable desktop CUA control. Physical devices, vendor battery restrictions and long idle sessions remain to be checked.

The editor keeps drafts during failed writes and rotation. If the editable fields changed remotely or the item was deleted while the editor was open, save is rejected without losing the draft; the user may compare and explicitly load the latest version. Revision-only acknowledgements and unedited session/history fields do not cause false conflicts. Draft switching asks before discarding another unsaved draft.

0.6 screenshots: [focus](../docs/images/android/v0.6/focus.png), [external execution](../docs/images/android/v0.6/external.png), [reminders](../docs/images/android/v0.6/reminder.png), [AI suggestion](../docs/images/android/v0.6/ai-suggestion.png).

0.5 workflow screenshots: [idea workspace](../docs/images/android/v0.5/idea-workspace.png), [check planning](../docs/images/android/v0.5/check-plan.png), [small next step](../docs/images/android/v0.5/small-step.png). Dialog captures contain only the window; the small-step capture contains the list region.

Baseline screenshots: [Today](../docs/images/android/v0.4/today.png), [Organize](../docs/images/android/v0.4/organize.png), [Ideas](../docs/images/android/v0.4/ideas.png), [large-font error feedback](../docs/images/android/v0.4/editor-large-font.png).

Development follows Pare’s own idea-to-action, planning and low-motivation support workflow. Running execution remains device-local; finished history synchronizes. The 0.9 code adds optional, expiring cross-device status summaries and start warnings, not remote timer control or external-AI orchestration. Account-local database switching and production release signing remain outside the implemented scope. Real provider calls and physical-device background delivery still require separate verification; AI tests use fake transport. An invalid login currently recovers via sync details → sign out → sign in to the same account. The existing server email confirmation landing-page issue is separate from this Android optimization.

0.9.1 keeps focus duration presets on one equal-width row (5, 15, 30, 60 minutes), retaining custom input. The debug build and Lint pass. Two targeted real-dialog cases (320dp dialog at normal text; 280dp dialog at 1.6× text, with measured 272dp/232dp row widths) and the two existing execution-capability flows pass. Labels, selection, 60-minute submission and custom 7-minute submission are checked. This patch does not claim a fresh full-suite run. [Screenshots and details](../docs/releases/android-v0.9.1.md).
