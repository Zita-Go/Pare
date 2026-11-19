# Next-step completion implementation plan

**Goal:** Finish an individual next action, keep its history, and write the next action without completing the parent task.
**Architecture:** Optional task completedSteps records {id,text,completedAt,definitionOfDone?}; existing current nextAction/definitionOfDone stay the active step. Add guarded complete/save/undo operations in both clients. Existing sync payload preserves optional/unknown fields; backend content has no field whitelist, so no schema/deployment change.
**Scope:** Mac and Android, local updates only. Preserve task IDs, plans, reminders, timers, credentials, original task completion and user data. Continue current codex/android-sync branch. No real data screenshots/tests.

- [x] Swift core: TDD complete/undo/update, storage and sync compatibility, unchanged execution/parent state, stale and failure guards.
- [x] Mac UI: direct completion in details/support, compact next-step entry in Today/capture, folded history, optional next step, undo without overwriting drafts.
- [x] Android: matching JSON operations, payload validation, execution/support UI, completion history and successor, unit/UI verification.
- [x] Integration: full Swift and Android tests/builds, independent review, local Mac2.16.0/build45 and Android0.15.0/code21 packages.
- [x] Deliver safely without interrupting live drafts/focus. Record actual verification, no public release.

## Decisions

Completing a step appends its text, completion time and optional stop criterion, then clears the current step/criterion. Main task stays open. A subsequent step is optional and saved separately. Repeated stale clicks are rejected/idempotent; undo only restores the latest completed step while no new action/criterion/draft exists. Edited unsaved steps must be saved before being completed. Step history appears folded by default. No automatic AI call or timer change.

## Verification targets

Sequential steps and relaunch; old payload decode; roundtrip sync/unknown preservation; parent/timer/plan untouched; stale/idempotent submission; undo successor protection; failed persistence does not announce success; controls have meaningful labels and do not crowd out primary task actions.

## Final results

- Swift full suite359/359; Android384/384; final AndroidNextStepFlowTest2/2 plus five previously run startup flow cases. Lint0errors/101existingwarnings/1hint.
- Mac release/plist/signature pass. One release attempt observed Store.swift being edited during compilation; after all agents stopped editing, a fresh release build passed in44.24s.
- Independent review issues (failed detail-save closing, same-text successor ABA) fixed with persistent-result guard/rollback and completion-history ID baselines. Regression tests passed after failing on old behavior.
- Mac2.16.0/build45 installed in place with private backup; one existing2.15.2 process retained, restart required after saving drafts/finishing focus. No desktop controls used in this task.
- Android emulator5554 upgraded in place to0.15.0/code21. Final packages and SHA256SUMS in dist. No backend migration, real-account testing, publication or push.
- Native Mac workflow visual acceptance remains unperformed; compile+domain/state tests completed. Android new workflow exercised on isolated QA emulator, no physical phone.
