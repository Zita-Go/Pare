# Android 2×2 widget

User requested two launcher columns by two rows and challenged the stretched preview. Retain existing capture/focus guards, data privacy and offline behavior.

- [x] Request2×2 cells with110dp legacy/minimum bounds; responsive compact, tall and expanded RemoteViews layouts.
- [x] Align displayed task/next step with the active run controlled by pause/resume.
- [x] Use concise action labels while retaining48dp touch bounds and full accessible descriptions.
- [x] Add a fixed-size preview layout and verify the actual launcher confirmation.
- [x] Actually add a fictional QA widget, capture host options/geometry, and cross-check the default launcher grid instead of relying only on2×2 text.
- [x] Finish final layout/action regression, package0.13.2, install preserving normal data, document exact layout and grid evidence.

Measured default launcher:5 columns/5 rows, getCellSize207×375px atdensity2.625, no grid border space. QA host382×718px. Launcher3 uses minimum widget inset6dp (16px each edge), so both axes match2 cells. Two-by-two cells are not necessarily physically square. Do not change the user's launcher grid settings to make the picture square.

Final layout/placement verification passed; 373 units, build/lint/signature pass. Native 2×2 target confirmed; actual placement plus public launcher geometry cross-check recorded. No grid preference changed.
