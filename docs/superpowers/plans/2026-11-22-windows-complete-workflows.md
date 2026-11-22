# Windows Complete Workflows Implementation Plan

**Goal:** Finish the explicitly listed Windows daily-use gaps while preserving Step's core product workflows and safe local-first state.
**Architecture:** Existing Compose Desktop UI, shared Kotlin domain actions, Windows adapters behind testable interfaces. Continue isolated preview branch `codex/windows-preview`, clean baseline8812ac7; core294/app40 and native Windows installer/self-test already passed.
**Spec:** ../specs/2026-11-22-windows-complete-workflows-design.md

## Ownership and integration

- Root: UiEditors.kt, new UiLibrary.kt/UiReview.kt/metadata helpers, tests, Gradle/build/CI/docs and integration audit.
- Desktop UI worker: DesktopUi.kt, new drag/timeline/quick-actions/widget UI and tests. Root provides `LibraryPane(runtime,onOpen)` and `ReviewPane(runtime,onTask)`.
- Runtime worker: DesktopRuntime/Store/Vault/Platform/Reminders/Main and new backup, presence, native-system adapters/tests. Notify other workers when public contracts are ready.
- Intelligence worker: UiAI.kt, UiSettings.kt, new intelligence/preferences/history UI/local policies and tests. Runtime worker owns encrypted history mutations and backing services; collaborate by explicit APIs.
- Only root runs shared Gradle tasks; workers can supply tests for root red/green runs or use isolated compiler/test outputs. No user data, no live AI/accounts in fixtures.

## Work packages

- [x] Library lifecycle/search: reuse LibrarySearch/LibraryActions/localTrash; confirmed deletion, archive/restore and safe active-run finalization; tests for restore, conflict and history retention.
- [x] Metadata: deadline, total estimate, actual correction with narrow stale baselines; preserve notes, day plans and recorded effort.
- [x] Drag and schedule: quadrant/today reorder, interactive timeline create/move/resize/cross-day with previews and cancellation; meaningful drag workflow tests.
- [x] Quick progression: row/capture controls, complete next step, blockers/smaller action/start5; independent focus vs waiting and success-only navigation.
- [x] Review: recent record list,8-week bars, estimation calibration and aggregate export; empty/missing data handled honestly.
- [x] Intelligence: local eligible hints/cooldown, preferences/background, feedback, local trace viewing/deletion, approved-context use; no automatic network.
- [x] Backup import/recovery: bounded validated preview, stale preview protection, rollback snapshot, credentials excluded; malformed/conflict/duplicate/mutation tests.
- [x] Presence: configure/publish/read existing protocol with opt-in, staleness and lifecycle shutdown; no remote execution commands.
- [x] Windows integration: self-start, actionable/system scheduled notifications, update checking/download verification/controlled installer, local attention signals, mini desktop window; adapter tests and native CI checks.
- [x] Integration: full core/app suites, Android regression if shared sources changed, Windows CI package/install/smoke and fictional dark/light/density screenshots; independent code audit and fix findings.
- [x] Delivery: feature/verification matrix, remaining physical-machine checks, clean commit/push; do not imply public release or automatic enablement.

## Integration checkpoint

- Implemented all UI/runtime work packages; source changes remain on preview branch. New lifecycle, metadata, drag/timeline, quick actions, review, preferences/history/consent, recovery, presence, Windows adapters and widget are wired to real actions.
- Actual RED evidence captured for metadata, history search, drag/quick actions, intelligence policy/consent, Windows decoding/HTTP path, and quick-draft lifecycle. Target UI workflows subsequently passed.
- Shared-client regressions passed: Mac360 tests, Android389 tests. Full Desktop run had92 tests with1 old ambiguous label locator failure (fixed by scoping to modal) and1 Windows-only skip onMac; final run in progress after further recovery/browser/UI tests.
- Independent audit findings addressed: external task open draft loss, per-process PowerShell RemoteSigned policy, quadratic backup tail copying, download temporary-file failure busy recovery. Follow-up audit in progress.
- Backend v2 preserves old client device label filtering; upgraded clients requestv2, same payload/lease version. Migration/tests/deployment owned by intelligence worker for now; see final report before marking deployed.
- Windows0.2.0 CI now builds/installs prior0.1.0 fixture then upgrades and checks unchanged store/history; native system registration/cleanup smoke also added. No public release yet.

- Pre-CI frozen tree: core295/app98, zero failures, one Windows-native test skipped onMac. Mac360/Android389 regressions passed. Follow-up audit cleared both restoration issues: conservative trash merge and prepared-only rollback publishing. Actual pointer tests, narrow/wide dark/light/density layouts and short-block titles passed. Windows0.2.0 packaging/upgrade/native-registration run next.

- Backendv2 deployed as20261122123824; originalv1 rollback regression re-run successfully byroot after a hung tool call was interrupted. v2 rollback assertions passed; zero remaining fixture accounts, Advisor reviewed. Acceptance record in backend/supabase/docs/presence-v2-acceptance.md.
- FirstWindows0.2 run45028021531 found WindowsPowerShell module autoload failure inherited through Java frompwsh7. Fixed child-only PSModulePath to trustedWindows5.1 builtin modules; added poisoned-parent-process regression.
- Run45030161274 passed all unit tests, build,0.1→0.2 upgrade with unchangedstore/history and new-package smoke. Native-system cleanup failed because hidden TaskScheduler items were excluded byGetTasks(0); uncaught main error made jpackage show a blocking dialog. Corrected all enumerations toGetTasks(1), diagnostic CLI catches errors and exits with code1; workflow split with phase-specific deadlines/logs. Final native rerun pending.
- Final Windows run45032978273 atd5d4143 succeeded in every step: tests, native installers, prior0.1 build/install/fixture, upgrade/store+step-history preservation, new executable smoke, native registration/duplicate-safe/cleanup and package/checksum uploads. PublicRelease remains uncreated. Verification/artifact links and physical-PC limits are in docs/releases/windows-v0.2.0.md; no personal store or credentials used.

- Final verification archive downloaded with matching SHA256 d6fcb8e6c2de2d0b114846b34a791102f0ef52ba97aad7f0f178422daf115b03. XML/HTML counters confirmed core295/app99, zero failures and skips. All three nativecheck JSON receipts confirmed; selected final Windows PNGs copied unchanged. Installers remain in GitHub artifact14758742929; slow local336MB transfer was not completed and no local installer is claimed.
