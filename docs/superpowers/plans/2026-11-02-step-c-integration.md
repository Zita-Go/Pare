# Step C Brand Integration Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Platform tasks have disjoint ownership and may proceed concurrently.

**Goal:** Integrate the approved C icon and Step display identity on Mac and Android without changing existing user data or installation identity.

**Architecture:** A canonical vector rendition of C supplies color icons and a legible monochrome mark. Platform adapters keep legacy identifiers and replace presentation resources/copy only. Prior unapproved implementation is available as a reviewed starting point, not a source of approved artwork.

**Tech Stack:** Swift/AppKit, Android Kotlin/XML, native resource tooling.

**Spec:** `docs/brand/step/approved-c.md`

## Global Constraints

- Preserve `com.zitago.Pare`, executable/module names Pare/PareCore, `com.pare.app`, QA suffix, DB/storage paths, Keychain/Keystore identifiers, preferences, WorkManager classes, channel/intent/URI identities and synchronization formats.
- Do not touch real tasks, API credentials or raw AI history in tests or screenshots. Use QA fixtures only.
- Do not restore the rejected first icon. Use approved C as the visual source.
- Do not stop an active personal focus session or start a duplicate normal app.
- No GitHub publish, push or store release is part of this turn.

## Review Focus

- Brand changes accidentally renaming persisted storage/work/credential identities: compare diff and existing migration tests.
- Mono notification/menu icon retaining a visible separate goal dot at 16–24px: inspect render and native notification QA.
- Launcher masks clipping the goal or diagonal ends: check circle/squircle and adaptive safe zone.
- Existing install appearing as a second app or losing data: preserve package/bundle IDs and check one-instance logic.
- Captures containing real data: use isolated QA fixture and synthetic examples only.

## Workspace ruling

Continue the existing `codex/android-sync` task checkout. HEAD is `8a54853`; all current uncommitted changes are this task's design documents. This resumes an existing task and its backed-up work, with disjoint file ownership, rather than moving the established toolchains and private runtime configuration into a second checkout. No main/master work or user changes are overwritten.

## Task 1 — Canonical C resources (root)

Files: `Resources/Brand/*`, `Resources/AppIcon.png`, `Resources/StepStatusIcon.pdf`, `Scripts/render-step-brand.swift`, `docs/images/app-icon.png`; Android drawable/mipmap icon resources and brand color values.

- [x] Recreate C's approved silhouette/facets/dot as deterministic vector geometry, including monochrome optical variant.
- [x] Render macOS master, menu PDF, Android legacy/adaptive/monochrome resources and review sheet.
- [x] Visually compare to approved C, inspect 16/18/24/32/64px and masks. Verify all image files parse and have clean bounds.

## Task 2 — Mac visible identity (Mac implementer)

Files: backed-up `Sources/Pare/*`, `Sources/PareCore/*` visible labels only, `Tests/PareCoreTests/UIContractTests.swift`, `build_app.sh`, demo scripts/plists. Do not edit icon assets or generator.

- [x] Review and restore only Mac presentation changes from `/private/tmp/step-unapproved-proposal-2026-11-02/files`.
- [x] Keep executable Pare and bundle ID; output `dist/Step.app`; use new menu template with fallback.
- [x] Verify stored-data/keychain/preferences/single-instance identities unchanged.
- [x] Run Swift tests and report. Root performs final packaging after art assets exist.

## Task 3 — Android visible identity (Android implementer)

Files: Android Gradle version, Manifest, visible Kotlin strings/widget layout labels and affected tests, `values/strings.xml`. Root owns drawable/mipmap/brand color resources.

- [x] Review and restore presentation changes from backup, retaining app package, components, work and storage identities.
- [x] Update icon assertions to check valid ink/transparent bounds rather than an old single pixel.
- [x] Run unit tests/lint and selected native widget/notification cases after root signals resources ready; avoid duplicate Gradle jobs.

## Task 4 — Integration, review and delivery (root + independent reviewer)

- [x] Update README/current branding while preserving real repository/download URLs and historical release names.
- [x] Run `swift test`, `./build_app.sh`, Android `testDebugUnitTest assembleDebug lintDebug` and selected QA instrumentation as appropriate, once per final change.
- [x] Independent review of compatibility and asset consistency; resolve material findings.
- [x] Inspect app metadata and produced artifacts. Confirm no real data in tracked files.
- [x] Check running Mac focus state before any replacement/restart; keep session intact if active. Deliver tested local package and perform safe authorized update when possible.

## Progress

- Approval recorded: C, 2026-11-02.
- Baseline: existing HEAD `8a54853`, code unchanged before implementation. Prior 343 Swift and 374 Android unit tests were passing; rerun after presentation changes.
- Task1 complete: canonical vector, adaptive safe-circle check and native menu proof. Mono terminal has a4.5-unit optical inset to keep goal separate; color C unchanged. Actual menu2/3 components and0 erased brand pixels; Android24px two-component check passed.
- Task2 complete: /private/tmp/step-c-mac-report.md, 343 Swift tests passed; final Mac release build/signature passed.
- Task3 complete: /private/tmp/step-c-android-report.md, 374 units; build/lint0errors;9 native pass+1expected skip, then final affected notification test passed after mono adjustment.
- Task4 review: /private/tmp/step-c-final-review.md; no important defect. Scoped mono re-review requested. README/current guide updated, historical URLs/data paths preserved.
- Runtime update boundary: desktop tool reports Mac locked. Unlock question pending; no real Mac exit/restart or installation replacement has been performed. Packaging/Android emulator update can continue independently.
- Final delivery: final mono re-review clean. Both packages and SHA256SUMS generated in dist. Android emulator normal package updated in place successfully to0.14.0/code19; package inventory contains only com.pare.app, no residual QA duplicates. Mac runtime update remains pending unlock.
- Mac update completed after user unlocked and authorized checking/updating. Confirmed no active personal timer and no editor draft before normal quit. Installed signed Step2.15.0 in /Applications/Step.app, old /Applications/Pare.app entry absent; private rollback bundle retained outside repository. Refreshed LaunchServices and removed a remaining old running process by normal Quit; exactly one current main process runs from Step.app.
- Post-update task/inbox/archive/thought/goal/parallel-run count and ID digests all match the pre-update snapshot; no task text, IDs or account data recorded in repository.
- Finder visibly shows Step and C icon. New process sample confirms2.15.0 and ordinary AppKit event loop. Final main-window interaction verification is pending because the Mac locked again; user notified. This is not a failed build or data check.
