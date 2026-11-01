# Android icon and key-screen polish

**Goal:** Implement the user's approved next iteration: cohesive rounded vector icons, a useful daily-priority card, more intentional idea/AI layouts, and restrained empty/control states.

**Architecture:** Presentation-only changes on the existing feature branch; use bundled licensed vector assets and existing callbacks/models. No runtime icon network, new domain rules, AI requests, consent changes or cloud migrations.

**Spec:** The preceding approved recommendation and `docs/research/2026-10-31-cross-category-ui-review.md`. Baseline `a60fce1` (Android0.10.0).

## Constraints

- Keep48dp interactive regions; visually20dp row icons and24dp navigation icons. Line/filled variants share one family; state/labels remain understandable without color alone.
- Preserve green actions/ordinary selections and gold priority. Do not change the app launcher mark in this iteration.
- Today/organize/ideas icons express dated tasks/four quadrants/ideas. Replace text chevrons and hand-assembled pause shapes with real vectors.
- Only the priority task reveals its existing smallest next step; never invent a next action or promise a5-minute start when the callback only opens a picker.
- Idea stages/note/finding/decision acquire clear, lightweight symbols and hierarchy; all history/linked actions/forms remain accessible.
- AI proposal/adoption changes remain visible; auxiliary scope/settings/history are less card-heavy. Keep raw input/output, security flags, preview and manual generation.
- Tests use separateQA package and fictional data. Preserve current dark/large-font behavior, six-task baseline, date/scroll and draft recovery.

## Work and ownership

- [x] Icon worker: new `PareIcons.kt`, 30 bundled drawable assets and license/provenance; no other UI files.
- [x] Idea worker: `IdeaWorkspace.kt` and targeted idea tests; consume icon API. Existing presentation models remain unchanged.
- [x] AI worker: `MobileAIViews.kt`, `PlanningAIViews.kt` and targeted existing AI tests; consume icon API, no model edits. Keep compact reviewed task/subject/date context across proposal and adoption.
- [x] Root: `VisualComponents.kt`, `TodayOverview.kt`, `TodayViewControls.kt`, `PareInterface.kt`, relevant action/chrome icon call sites; priority next-step and compact empty states; maintain semantics and callbacks.
- [x] Review/test: independent code review, unit/build/lint, native navigation/icon-state/density/large-font and AI/idea flows. 327 unit tests, 47 distinct UI cases with combined passing evidence; final 3 AI cases pass after target-label refinement. Lint: 0 errors, 29 warnings, 1 hint.
- [x] Final delivery: inspect native screenshots, package and verify 0.11.0, install over the normal emulator app while preserving its data. Installed versionCode 13 / versionName 0.11.0; visible MainActivity is foreground with no startup ANR. Source and documentation are ready for the local commit.

## Shared icon interface

`PareIcon(id:Int, description:String?, modifier:Modifier=Modifier, tint:Color=LocalContentColor.current)` consumes drawable IDs from `PareIcons` object. Required properties: Today, TodaySelected, Quadrants, QuadrantsSelected, Idea, IdeaSelected, Calendar, Play, Pause, Back, Next, Previous, Down, Up, Close, More, Edit, Add, Check, Star, Sparkle, Note, Finding, Decision, Link, Clock, Seed.

## Acceptance focus

- Glyph changes preserve real click targets and selected state; navigation labels remain visible.
- Priority next-step is actual task content, not truncated beyond access; long text does not push controls offscreen.
- Old role/tag semantics and direct time/focus controls work; paused/due/waiting states remain distinct.
- AI disclosure does not select hidden actions, discard inputs or send extra requests.
- Native screenshots contain no real account, model credentials or task records; no fake mockup is reported as app output.
