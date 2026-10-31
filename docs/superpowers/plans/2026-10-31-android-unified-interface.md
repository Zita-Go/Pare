# Android unified interface implementation plan

> **For agentic workers:** Use subagent-driven-development with disjoint presentation files; root owns integration and serial builds.

**Goal:** Implement the user-approved cross-category design review across Pare Android, retaining familiar shortcuts and trustworthy state.

**Architecture:** Keep domain, persistence, network, and consent logic unchanged. Share role-based visual helpers, update existing Compose surfaces, then verify complete workflows using the separate QA package and fictional fixtures.

**Tech Stack:** Kotlin, Jetpack Compose Material3, existing Android 8+ client. No new runtime dependencies.

**Spec:** `docs/research/2026-10-31-cross-category-ui-review.md` (approved by user's “好的，都做一下吧”).

## Global constraints

- Continue the existing `codex/android-sync` feature workspace from `6c2e498`; preserve the approved research document and prior edits.
- Retain forest-green/warm-gold identity; green selections, gold daily priority. Normal task text16sp; supporting time13–14sp; system text scaling respected.
- Minimum48dp tap regions. Standard primary/outlined action shape12dp; small selections8dp, content groups12dp, focus16dp, short sheets24dp.
- Keep all 5/15/30/60 focus presets and manual duration; calendar/estimate entry remains discoverable.
- Never silently save a draft or change AI consent/context/adoption selection. Preserve raw request history and failure feedback.
- No real credentials/tasks in source, logs or screenshots; no backend changes or remote publication.

## Review focus

- Long Chinese text and 1.6×+ font: actions remain reachable above keyboard; headings do not overlap controls.
- Rotation/back during draft or request: same draft and explicit confirmation; no duplicate submission.
- Date/timezone and view switching: selected day/scroll preserved; timezone differences remain clear.
- Success versus failure: transient success does not push content; errors stay visible; animation does not imply a failed write succeeded.
- Active/paused/due/waiting states and AI proposals: real state remains apparent; no hidden changed fields or duplicate timers.

## Tasks and ownership

- [x] **Root – shared visual roles and Today.** `PareTheme.kt`, new `PareInterface.kt`, `VisualComponents.kt`, `MainActivity.kt`, `TodayOverview.kt`, `TodayTimeline.kt`. Compact combined day header/date navigator and view toggle; target first task≤180dp in normal360×800 fixture without alerts; keep literal date/timezone access. Reduce icon emphasis and per-row card chrome, add restrained placement feedback honoring motion scale.
- [x] **Management worker – consistent pages and feedback.** Goals/Preferences, Library/Trash, Account/Devices, TaskMetadata/IdeaManagement/ImplementationPlan and Review surfaces. Unified top return, consistent edit/save footer and transient success, persistent errors, explicit discard. Do not change underlying actions.
- [x] **Reading/AI worker – content before controls.** `IdeaWorkspace.kt`, `PlanningAIViews.kt`, `MobileAIViews.kt`. Reading hierarchy for question/findings/actions; AI recommendation and exact adoption changes first, reasons/settings/history disclosure; preserve consent/security flags and manual requests.
- [x] **Execution/editor worker – fast actions.** `ExecutionDialog.kt`, `EditorDialog.kt`, `TaskSupportDialog.kt`, `TodayAllocationDialog.kt`, `TaskReminderDialog.kt`, `TimelineScheduleDialog.kt`, `MobileNotificationSettings.kt`. Short focus form, concise permission status, detailed notification controls folded, standard controls/footer, no timing/permission behavior changes.
- [x] **Root – integration and acceptance.** Update affected UI test navigation, add meaningful layout/feedback checks; full unit/build/lint plus relevant/full QA flows. Inspect native light/dark and large-font screenshots. Independent final source review; package0.10.0, install normal emulator preserving data, local commit and docs.

## Shared interface

Root provides `ParePageHeader(title,onBack,modifier,backLabel,enabled,actions)` for management/read headers; `pareChoiceColors()` for green selected chips; `pareActionShape()` for12dp actions. Workers may use explicit `MaterialTheme.shapes.medium` until helper lands; no edits to root-owned files.

## Execution notes

Ruling: reuse the existing feature workspace and user-approved deployment/testing conventions; separate worktrees would duplicate the local SDK and complicate emulator/artifact integration. Workers own disjoint files and never run Gradle concurrently.

Ruling: all six approved improvement areas are included. Decorative app images, new business features, generalized dashboards and backend changes are outside this presentation update.

Acceptance completed2026-11-01:327unit tests,44 distinct device flows after scopedreverification,0Lint errors; native360×800 firsttask178.3dp,6tasksvisible. IndependentreviewfoundtwoP2issues (scheduleBackdraft protection, narrowtoolbar48dp); bothfixedandscopedreviewpassed. Finalstyle checks4/4. See docs/releases/android-v0.10.0.md forlimits andscreenshots.
