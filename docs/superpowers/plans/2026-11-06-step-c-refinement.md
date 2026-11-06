# Step C Refinement Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the canonical artwork; a separate agent validates Android after resources are ready. Steps use checkbox syntax for tracking.

**Goal:** Install the user-approved fuller C icon, preserving Step's data and platform identities.

**Architecture:** Promote review-09 geometry and native rendering into the canonical generator. Derive SVG, Android adaptive/notification assets and Mac menu art from that geometry, with explicit platform optical adaptation.

**Tech Stack:** Swift/AppKit, SVG, Android vector XML, existing native QA.

**Spec:** `docs/brand/step/review-09/README.md` and approved `step-fuller-material-1024.png`; user replied “合适” on 2026-11-06.

## Global Constraints

- Preserve white tile, chamfered S, coordinated oblique terminals, green facets and separate gold sphere.
- Preserve com.zitago.Pare, com.pare.app, storage, credentials, sync and notification identities.
- Never interrupt active focus or overwrite user data; no real data in committed artifacts.
- Scope is local integration/update; no remote publication.
- Continue the existing codex/android-sync task branch; the only initial untracked files are this task's review-09 previews.

## Review Focus

- Approved desktop PNG must reproduce exactly: compare decoded pixels, not file metadata.
- Goal/end separation: native menu and Android notification connected components.
- Taller Android silhouette: test all ink within the central 66dp adaptive safe circle.
- Menu attention dot must not erase the S or goal: compare ordinary/attention pixels using actual renderer.
- Update must retain one normal install/process and leave active focus unchanged.

## Task 1 — Resources and local version

- [x] Promote preview rendering to `Scripts/render-step-brand.swift`; regenerate Resources, Android artwork and docs/images.
- [x] Keep preview history immutable. Record approval in approved-c, product principles and review-09 notes.
- [x] Bump local metadata to Mac 2.15.1/build43 and Android 0.14.1/code20.

## Task 2 — Verification

- [x] Compare master against approved preview and inspect output sizes/masks.
- [x] Build/sign Mac; render real menu ordinary/attention PDF and confirm separate components/no lost ink.
- [x] Build Android debug/qa resources and run affected existing notification instrumentation on isolated QA package.
- [x] Independent review of asset consistency, identities and scope.

## Task 3 — Update and deliver

- [x] Check current Mac timer/draft state, back up install, normal quit only if safe, replace/signature-check/relaunch once.
- [x] Update emulator normal package in place after validation, retain data; no clear/uninstall of normal package.
- [ ] Verify installed versions/resources and record actual completion/limitations.

## Execution notes

This is an approved visual-resource refinement, with no behavior changes. No new mirror tests or business-suite reruns are needed; existing native asset checks and builds are the relevant verification. User approval already authorizes local replacement after validation.

## Results

- Master PNG byte-identical to approved review-09 image. Generator safe-circle sampling passes.
- Mac release build/plist/signature pass; actual shipping glyph + final PDF native check: normal 2 components, attention 3, erased brand pixels 0. Initial sandbox drawing attempt could not reach WindowServer; scoped native rerun passed.
- Android debug/QA/test APK builds pass; affected native notification test 1/1 passes. Initial run lacked required capture output argument; rerun uses isolated QA private files. Final full-art safe-circle radius <=32.848437dp; 33dp allowed. QA packages removed after test.
- Independent read-only review: no blocking issue; no storage/identity/business changes.
- Normal-quit Mac after fresh no-active-timer check, privately backed up old app, replaced Contents and verified signature/resources; installed 2.15.1/build43, one normal main process. Private collection counts/ID digests unchanged.
- Android emulator normal package updated in place to0.14.1/code20; only com.pare.app remains.
- Final desktop UI check pending: Mac locked after launch; user unlock question sent. Installed metadata and process checks are complete, but are not a substitute for final window verification.
- Local archives/checksums generated. No push or public release.
