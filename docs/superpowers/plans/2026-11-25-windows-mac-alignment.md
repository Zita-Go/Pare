# Windows Mac Alignment Implementation Plan

> **For agentic workers:** Use subagent-driven development with bounded file ownership; the coordinator runs Gradle once per integration stage.

**Goal:** Make the Windows workspace and quick capture follow Step's existing Mac workflow, with direct quadrant creation and parallel controls.

**Architecture:** Keep Compose Desktop and the tested local-first domain. Align the visible shell and nearby actions with Mac instead of adding another navigation system. Audit capability and native-window parity separately from rendered UI tests.

**Tech Stack:** Kotlin, Compose Desktop, existing shared core, Windows native acceptance harness.

**Spec:** User requests in this conversation; `docs/product-principles.md`; current `Sources/Pare/OrganizeView.swift`, `CaptureView.swift`, and `PopoverController.swift` are the interaction reference.

## Global Constraints

- Work directly on `main`, preserve existing edits and production sync/consent safeguards.
- Use fictional profiles only. No real tasks, credentials, API history or private screenshots in Git.
- Keep four quadrants, incubating ideas, today planning and the smallest next step.
- One personal focus, multiple parallel runs; Step does not control other machines or their AI.
- Never describe unsupported native environments or absent UI as verified features.

## Design Decisions

Use Mac's compact left inbox rail and four horizontal workspaces: 四象限、今日计划、搜索与记录、想法与项目. Keep goals reachable from ideas, review from the toolbar, settings/sync visible. Reduce stacked headings and card padding while keeping readable typography and reachable controls. Add pinned inline task input in every quadrant. Preserve detail editing, dragging, source planning dates and undo.

Quick capture follows Mac's focus-first input, today list, feedback and Organize entry. Keep draft text across hides; IME confirmation must not save prematurely. Native outside-click dismissal must not dismiss owned dialogs or candidate windows.

## Review Focus

- Typing digits/spaces in fields must not trigger global matrix shortcuts.
- Save failure or repeated Return must not clear or duplicate quadrant drafts.
- Active parallel runs outside today's selection must remain reachable.
- Small windows, larger scale, long titles and controls must remain usable.
- Hiding capture, opening a child dialog and returning must preserve focus/drafts and timers.

## Tasks

- [ ] Root: behavior tests for inline quadrant addition, navigation reachability, keyboard classification and text-input isolation; run RED, implement compact Mac shell and inputs, run GREEN.
- [ ] Parallel agent: direct start/control/check time actions with concurrency semantics; RED then GREEN coordinated by root.
- [ ] Capture agent: extract quick capture, align focused input and task controls; repeat open/hide, IME, Esc, child dialogs, draft/save-error tests.
- [ ] Auditor: evidence-based capability matrix, distinguishing available, differently exposed and missing Mac features.
- [ ] Root: full core/app tests, inspect real Compose renders at compact/wide sizes, independent review, fix regressions and document honest native limitations.
- [ ] Root: build and Windows CI acceptance before producing or publishing an updated installer; keep exact source and evidence provenance.
