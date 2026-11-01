# Android priority, organize drag and navigation

Goal: make priority visible in task capture, allow organize-by-drag, and reclaim bottom navigation space.
Architecture: retain editor model and task callbacks; add guarded task quadrant/order actions and a long-press layer around the existing organize list. Preserve normal scrolling/taps and user data. No cloud/Mac changes.
User scope: latest requests explicitly ask these usability corrections. Existing native Compose architecture retained.

- [x] Move the existing priority control below task title, outside disclosure; validate new/existing capture and keyboard/large font.
- [x] Add long-press task movement with highlighted quadrant targets (including empty), insertion feedback and edge scrolling; only save on release. Cancellation does not mutate. Preserve accessible priority editing.
- [x] Store additive quadrantOrder without touching day-plan order; guard stale source/destination, deleted/completed/archive/conflict and batch limits. Mac preserves this extra field but still uses its own display ordering.
- [x] Compact bottom bar to 64dp content with natural large-font growth; retain labels,48dp taps and system navigation insets.
- [x] Run units/build/lint and relevant native interaction regressions with fictional QA data; inspect screenshots, review, package and install development update.

Completed: 339 unit tests; 22 distinct relevant UI cases passed across initial and targeted verification. Final integration and screenshot capture rerun passed. Installed 0.12.0/versionCode14 over normal emulator app; data retained. Independent review fixes include non-row gesture filtering, stationary hold suppression and separate drop geometry.
