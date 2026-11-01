# Material Symbols Rounded subset

Pare Android bundles 30 static Android vector drawables from Google's
[Material Symbols](https://developers.google.com/fonts/docs/material_symbols).
The original files were fetched from the official
[google/material-design-icons repository](https://github.com/google/material-design-icons)
at revision `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`.
The first 28 files used the GitHub connector. `refresh` and `check_circle` were
retrieved directly from the same pinned raw GitHub source when the connector
was unavailable and the public metadata API was rate-limited.

All assets use **Material Symbols Rounded, weight 400, grade 0, optical size 24**.
The default files have no weight/grade suffix in the upstream naming scheme.
Ordinary icons use fill 0; the three selected navigation icons use the matching
fill 1 resource. No font, runtime download, or complete icon library is bundled.

## Attribution and license

The symbols are provided by Google under the Apache License, Version 2.0.
`LICENSE` contains the complete upstream license. `NOTICE` records attribution
and Pare's modifications. The same notice and full license are included in the
application at `app/src/main/assets/licenses/material-symbols.txt`.
The upstream repository has no root NOTICE file at the pinned revision.

`manifest.json` records each exact source path and immutable URL, upstream Git
blob SHA-1, upstream SHA-256, modified drawable SHA-256, fill setting, and
mirroring/viewport metadata. The first 28 source files were checked against
upstream Git blob metadata. For `refresh` and `check_circle`, source hashes
were calculated from the pinned raw files; this distinction is recorded in
their manifest entries.

## Android adaptations

- Rename resources to `pare_symbol_*` and add per-file modification notices.
- Replace the upstream theme-based tint with an explicit opaque monochrome fill.
  `PareIcon` applies the Compose content color or the caller's tint.
- Preserve upstream path data and RTL auto-mirroring. Back, previous, and next
  are mirrored; the upstream flag is mirrored as well.
- Keep a 24dp intrinsic size and a shared 960 by 960 viewport. `auto_awesome`
  arrives with a 24-unit viewport, so its unchanged path is wrapped in a scale-40
  group. The rendered geometry and proportions stay equivalent.

## Semantic mapping

`PareIcons` and `PareIcon` are in `app/src/main/java/com/pare/app/PareIcons.kt`.
Today uses `event_available`; Quadrants uses `grid_view`; Idea uses `lightbulb`.
Their selected versions are filled variants of those same symbols. Play and
Pause are the matching outlined circle pair. Finding aliases Idea, Note uses
`description`, Decision uses `flag`, and Seed uses `eco`. Archive uses `archive`
and Unlink uses `link_off`; both retain their adjacent explanatory text in the
UI. Refresh uses `refresh`, and CheckCircle uses `check_circle`. The manifest
contains the complete mapping.

Callers provide accessible descriptions on standalone controls and use null
descriptions when adjacent text or the parent control already supplies a label.
Use a 20dp visual size for compact rows and 24dp for navigation while retaining
the parent control's minimum 48dp touch region.

## Updating the subset

Choose a new pinned upstream commit, fetch only the entries listed in the
manifest, verify each fetched file against its upstream Git blob hash, and
apply the documented adaptations. Keep the license and notice current and
regenerate both source and drawable checksums. Validate XML parsing, path data,
viewport scaling, mirrored directions, selected/unselected pairs, and the
packaged license before inspecting the icons in the app.
