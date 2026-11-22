# Desktop Chinese typography

`NotoSansSC-variable.ttf` is the unmodified Noto Sans SC variable font from
[Google Fonts](https://github.com/google/fonts/tree/a85815a42757630ce188fdad368c2dfc444d4773/ofl/notosanssc).
It provides consistent Simplified Chinese and Latin typography without depending
on the fonts installed on Windows. It does not contain Apple's PingFang font.

## Source and redistribution

- Source repository: `https://github.com/google/fonts`
- Pinned commit: `a85815a42757630ce188fdad368c2dfc444d4773`
- Source file: `ofl/notosanssc/NotoSansSC[wght].ttf`
- Local filename: `NotoSansSC-variable.ttf` (filename changed; contents unchanged)
- Font version: `2.004-H2`
- Size: 17,772,300 bytes (approximately 16.95 MiB)
- License: SIL Open Font License 1.1; the original notice and full license are in
  [OFL.txt](OFL.txt). Keep this file with the font in application distributions.
- Copyright holder: Adobe, 2014–2021. The Reserved Font Name in this license is
  `Source`. No font modification or static instancing was performed.

| File | SHA-256 |
| --- | --- |
| `NotoSansSC-variable.ttf` | `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da` |
| `OFL.txt` | `1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9` |

The font's Git blob SHA-1 is `fb0637bafbcd804fe32152370a1225990745b4bc`;
the license's is `1c9f43281b8f216c5461fe9ac729afbade7724e4`. Both downloaded
files were checked against the source repository's published blob identifiers.

## Compose Desktop 1.9.3

The font contains a `wght` axis ranging from 100 to 900. Its default axis value
and OS/2 weight are **100**, and its default name record is `Noto Sans SC Thin`.
Specifying only the Compose matching weight is insufficient with the legacy
three-argument font overload, which supplies empty variation settings. Set the
actual axis explicitly when registering each semantic weight:

```kotlin
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

private val StepFontFamily = FontFamily(
    listOf(400, 500, 600).map { weight ->
        Font(
            resource = "fonts/NotoSansSC-variable.ttf",
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight))
        )
    }
)
```

Use this family for every Material typography role so dialogs, labels, and
headings share the same typeface. All three instances use the same bundled file;
there is no download or system-font lookup needed for its supported glyphs.

## Verification

The bundled font contains 31,036 glyphs and 30,890 Unicode cmap entries. A
headless probe loaded the resource using Compose Desktop 1.9.3's resource font
factory and `loadTypeface` with Skiko 0.9.22.2 on macOS. Skia reported actual
weights and `wght` variations of 400, 500, and 600 respectively; the glyph bounds
changed across weights. A synthetic Chinese/Latin sample including 今天、四象限、
想法、目标、最小下一步 and ABC123 had no missing glyphs. This verifies resource
loading and axis selection; Windows screenshot review separately verifies the
platform's final rasterization and layout.

When replacing this font, retain the license, update the pinned source and
hashes, and recheck actual Skia weights rather than relying on the font filename.
