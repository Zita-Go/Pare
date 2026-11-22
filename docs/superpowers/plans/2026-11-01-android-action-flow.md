# Android action flow and home widget

Goal: implement the approved next iteration: choose existing quadrant tasks for today, put smallest next step before action tools, show actual daily effort, polish drag with safe undo, and a home-screen widget.
Architecture: existing Compose screens/state machine, local native Android RemoteViews widget, explicit activity routes and existing execution guards. Preserve manual AI consent and all accounts/data. Continue current branch.

- [x] Today: optional existing-task chooser, explicit add-to-day action, valid selected-day personal/check minutes and unknown count. Never infer effort from elapsed waiting or duration blocks.
- [x] Detail: title/priority, actual next step, start + make smaller; secondary details/tools folded, unsaved edits saved before action.
- [x] Organize: direct add-to-today control; compact empty quadrant line; drag preview follows finger; source/destination feedback; conditional field-scoped undo preserving unrelated new notes/history and rejecting stale order.
- [x] Widget: real today focus + next step, capture, guarded start/pause/resume; native resources, no extra auth/model calls; refresh on relevant data/execution/time changes. Main routes validate pending intent contents and current run identity.
- [x] Verification: unit, relevant UI and full regression where justified, widget rendering/routes, notification and offline/recovery regression, no real-account mutations. Physical device requested because only emulator detected; record unavailable checks accurately.
- [x] Review screenshots with fictional QA state; build/install developer update preserving existing data, documentation and local commit.

Completed 2026-11-01: 369 unit tests and 73 distinct native device cases have passing evidence; final targeted10 and7 cases passed after fixes. Live QA PID5761 was force-stopped and disappeared; separate verification restored the original run and exact persisted task/plan/outbox, then recorded one history entry. Actual alarm delivery/private metadata/click passed; no actual locked/screen-off state was observed. Physical test explicitly deferred. Installed0.13.0/code15 over normal app, data retained.
