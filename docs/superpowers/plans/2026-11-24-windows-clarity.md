# Windows clarity and convenience implementation plan

> For agentic workers: use the existing verification/TDD workflow, with root coordinating shared Gradle runs and independent reviews.

**Goal:** Implement the six usability improvements approved by the user: easy creation/start, explicit planning dates, contextual task routing, truthful sync, connected idea actions, and compact responsive layout/settings.

**Architecture:** Keep the existing shared domain and transactional stores. Extend editor initialization and use local nested task navigation to preserve idea drafts. Keep one visible settings entry and reuse the current desktop controls. Only presentation/entry points change; sync consent, task semantics and saved data remain unchanged.

**Tech Stack:** Kotlin, Compose Desktop, JUnit/Compose UI tests, Windows native packaging CI.

**Spec:** User approved all six items in the preceding review on 2026-11-24.

## Constraints
- Work directly on main, as previously requested. No new branch.
- Preserve four quadrants, idea incubation, today planning, smallest next steps and personal/waiting distinction.
- Reuse approved icon and existing green/gold visual system.
- Use only fictional isolated stores for tests/screenshots. No user records or secrets in commits.
- Do not mark sync successful merely because it is enabled. No new background AI or automatic adoption.

## Interface contracts / ownership
- Editor worker: UiEditors.kt. TaskEditor gains trailing default params initialTab="step", addToPlan=false, initialQuadrant=1. Creation and inclusion use one atomic store edit. IdeaEditor keeps its own state while opening a linked TaskEditor/AI child; deleted links remain unavailable.
- Settings worker: UiSettings.kt, Main.kt and new focused status/options components. Expose SyncStatusButton(runtime,onClick) for sidebar. Settings reads optional LocalDesktopOptionsContent; Main supplies current desktop controls, shared with the tray's existing dialog. Root removes toolbar desktop-options entry. Defaults keep test callers compatible.
- Root: DesktopUi.kt, UiQuickActions.kt and planning/date helpers. Explicit date selector for quadrants, visible add/remove day control; star only for included unfinished tasks. Today can create into selected day. Time-related entries open schedule tab. Featured task exposes direct 5-minute start + alternate duration. One-column list at compact widths with time overview opt-in; wide right overview capped in width. Remove duplicate day-AI button and simplify view names.

## Tasks
- [x] Add red editor tests for new task+date inclusion and preserved idea draft/return through linked action.
- [x] Implement editor contracts and linked-action navigation; green focused tests.
- [x] Add red sync presentation/settings navigation tests; implement accurate labels and embedded desktop controls.
- [x] Add red root workflow tests: selected-date visibility/inclusion, direct create, schedule entry routing, featured start, compact overview.
- [x] Implement root presentation changes; preserve drag, undo and dirty-close safeguards.
- [x] Run full core/desktop tests, inspect compact/wide light/dark output, and independent review.
- [x] Build Windows 0.3.0, test upgrade from 0.2.2, rerun actual native input acceptance against the produced artifact.
- [x] Publish verified Windows package, update download links, push clean main and report user-visible results.

## Review focus
Cross-date targeting; task creation rollback if plan is conflicted; idea drafts surviving child navigation; task vs idea AI context; all sync error/consent states; existing timer blocking a second focus; compact text/button overflow; continued support for drag/time resize and Escape/dirty-close.

Verification: core304/desktop142 on Windows, Android398; all zero failures. Native run45419230623 verified input/hotkey/reminder history, retaining explicit physical-device unsupported checks. Release source91b8d63 published as windows-v0.3.0; ordinary release, not Latest.
