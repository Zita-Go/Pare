# Android 0.3 visual redesign

User rejected the 0.2 appearance and requested a substantial visual improvement. Use the previously accepted warm deep-green/gold Pare direction. This pass changes presentation, not synchronization or task semantics.

## Design

- Default application palette: forest background #14211A, tonal surfaces #203127 / #283B30, subtle borders #35483C, gold #E8C87A, warm white #F2F1E7 and muted readable green-gray text.
- Compact page identity: small PARE mark, concise 今天 / 整理 / 想法 heading, meaningful date and task information. Do not devote the first screen to a large slogan, machine-formatted timezone, and duplicated status text.
- Task content takes priority. Cards have deliberate spacing, compact metadata chips, circular completion control and quiet secondary actions; actual daily focus receives gold emphasis.
- Use a compact rounded bottom dock for three destinations plus one add action. Keep navigation/CTA fully outside scrolling content and system gesture insets.
- Organize uses color and typographic grouping, not repeated empty paragraphs. Ideas receive clear stage badges and real content metadata; empty states may use lightweight native vector motifs.
- Forms share the same typography, shapes and surfaces. Preserve fixed visible save/cancel controls, pinned error feedback, drafts and latest-version comparisons. No new action may silently replace data.

## Ownership and acceptance

Main-screen agent: MainActivity and new native visual components/date presentation helpers. Forms agent: PareTheme, EditorDialog and AccountDialog. Root: version/system theme XML, integration, screenshots and acceptance.

Existing test tags and 48dp hit targets remain; large fonts and IME must remain usable. Keep all existing sync, privacy and editor concurrency protections. QA uses only fictional data in com.pare.app.qa. No real account actions, cloud settings, Mac installation changes or public publishing.

- [x] Main screens and forms redesigned with shared typography, palette and 48dp targets.
- [x] 74 unit tests, APK build and lint verified; 0 lint errors and 13 nonblocking suggestions.
- [x] 2 formal emulator flows passed, including 1.6× font, pinned error feedback, unchanged list position while syncing, and draft/conflict safeguards.
- [x] Actual 1080×2400 screenshots of all three pages, empty state, editor, account and large-font error visually inspected.
- [x] Updated local APK and preview documentation prepared. No public publishing or real-data QA.

Integration repairs: capture BoxWithConstraints height explicitly across nested form scopes; keep navigation styling compatible with API26 via AndroidX; add test-only Espresso dependency to dismiss the keyboard before full-form screenshots.
