# Research-led Android 0.4 implementation

Spec: `docs/superpowers/specs/2026-10-30-android-product-design.md`.

User delegated research and design decisions. Three approaches were considered: further styling 0.3 cards (rejected because density/interaction stay wrong), copying one competitor (rejected because Pare has a different task/idea model and Android requirements), and applying observed list-first/progressive-disclosure principles with measured Pare-specific tokens (selected).

## Ownership

1. Main UI agent: list-first main pages, task menus, destination-only navigation, explicit inbox classification and minimal VM organize transaction extension.
2. Editor agent: title-first capture, expanded task detail, focus/keyboard handling, compact account presentation; preserve draft and stale-write protection.
3. Root: system light/dark palette and dimensions, version/build, density/flow/large-font acceptance tests, reference report and actual screenshots.

No sync protocol, remote configuration, keys or real data changes. Work is on existing `codex/android-sync`; QA remains separate `com.pare.app.qa`. Preserve all prior safety/regression tests. Competitor screenshots stay in /tmp as research only.

## Acceptance

- [x] Research findings and exact chosen dimensions/color roles documented with official sources; competitor assets stayed outside repository.
- [x] Six regular tasks fit the360×800dp standard-font fixture; first row119.24dp, row height73.14dp including divider. Large fonts preserve visible actions.
- [x] Title-only capture, focus, expansion, task menus and explicit inbox quadrant choice pass emulator flows.
- [x] 79 unit tests and3 device flows pass; existing remote-edit/rotation/persistence/sync safeguards retained.
- [x] Light/dark, editor/account screenshots visually reviewed. Lint0errors/13advisories; local0.4APK prepared.

Independent review identified draft expansion loss after keeping/closing/reopening. A formal test reproduced it; parent SaveableStateHolder now retains editor state and clears it when discarding/saving. Existing detail fields remain visible even if a former capture draft is reloaded as an existing task. Full tests passed after repair.

User steering confirmed that research must not turn Pare into a competitor clone. Added docs/product-principles.md and a short AGENTS.md pointer to keep future work focused on Pare's own workflow.
