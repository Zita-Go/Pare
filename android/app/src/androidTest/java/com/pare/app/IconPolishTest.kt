package com.pare.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real bundled vectors and navigation only: no account, repository, runtime or network setup. */
class IconPolishTest {
    @get:Rule val compose = createComposeRule()

    @Before fun requireIsolatedPackage() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.pare.app.qa")
    }

    @Test fun bundledGlyphsRemainVisibleAndNavigationFillChangesShapeInBothThemes() {
        val dark = mutableStateOf(false)
        val filled = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                PareTheme(darkTheme = dark.value) {
                    Surface(Modifier.width(360.dp).testTag("icon_gallery")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Step 图标验收", style = MaterialTheme.typography.titleMedium)
                            Text(if (filled.value) "导航选中 · 同色形状对照" else "导航未选中 · 同色形状对照",
                                style = MaterialTheme.typography.bodySmall)
                            glyphs.chunked(5).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { glyph ->
                                        Column(Modifier.weight(1f).height(64.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            PareIcon(if (filled.value) glyph.selected ?: glyph.normal else glyph.normal,
                                                glyph.label, Modifier.size(24.dp).testTag("glyph_${glyph.key}"),
                                                tint = MaterialTheme.colorScheme.onSurface)
                                            Text(glyph.label, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark.value = isDark; filled.value = false }
            val theme = if (isDark) "dark" else "light"
            val normal = glyphs.associate { glyph ->
                val pixels = glyphPixels(glyph.key)
                // Empty paths, transparent tint and incorrect resource conversion all lose visible ink.
                assertTrue("${glyph.label} must render visible geometry in $theme",
                    pixels.count { colorDistance(it, pixels.first()) > 24 } > pixels.size / 50)
                glyph.key to pixels
            }
            saveScreenshot("icon_gallery", "icon-gallery-$theme-outline.png")
            compose.runOnIdle { filled.value = true }
            glyphs.filter { it.selected != null }.forEach { glyph ->
                val selected = glyphPixels(glyph.key)
                val previous = normal.getValue(glyph.key)
                assertEquals(previous.size, selected.size)
                assertTrue("${glyph.label} must remain visible when selected in $theme",
                    selected.count { colorDistance(it, selected.first()) > 24 } > selected.size / 50)
                // Same location, size and tint: color/indicator changes cannot satisfy this check.
                // No golden hash: require a visible shape change while tolerating renderer variation.
                assertTrue("${glyph.label} selection must change its shape, not only its color",
                    selected.indices.count { colorDistance(selected[it], previous[it]) > 24 } > selected.size / 100)
            }
            saveScreenshot("icon_gallery", "icon-gallery-$theme-filled.png")
        }
    }

    @Test fun navigationKeepsLabelsRolesAndSeparateTouchTargetsAcrossSelectionAndThemes() {
        val dark = mutableStateOf(false)
        val selected = mutableStateOf(0)
        val callbacks = mutableListOf<Int>()
        val tabs = listOf("tab_today" to "今天", "tab_organize" to "整理", "tab_ideas" to "想法")
        compose.setContent {
            PareTheme(darkTheme = dark.value) {
                Surface(Modifier.width(360.dp).testTag("icon_navigation")) {
                    PareNavigation(selected.value) { index -> callbacks += index; selected.value = index }
                }
            }
        }

        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark.value = isDark; callbacks.clear() }
            val viewport = compose.onNodeWithTag("icon_navigation").fetchSemanticsNode().boundsInRoot
            val targets = tabs.map { (tag, label) ->
                val control = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
                    .assertHasClickAction().assertTextContains(label)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val bounds = control.fetchSemanticsNode().boundsInRoot
                assertTrue("$label must fit inside its navigation surface",
                    bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1 &&
                        bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                // A paragraph may retain unused constraint width while Text reports tight bounds.
                // Check the rendered line, not that unused area (see FocusPresetLayoutTest).
                val layout=layouts.single()
                assertEquals(1,layout.lineCount)
                assertFalse(layout.multiParagraph.didExceedMaxLines)
                assertTrue(layout.getLineLeft(0)>=-1f)
                assertTrue("$label must fit horizontally",layout.getLineRight(0)<=layout.size.width+1f)
                assertTrue(layout.getLineTop(0)>=-1f)
                assertTrue("$label must fit vertically",layout.getLineBottom(0)<=layout.size.height+1f)
                bounds
            }
            targets.zipWithNext().forEach { (left, right) ->
                assertTrue("Adjacent navigation touch targets must not overlap", left.right <= right.left)
            }
            compose.runOnIdle { assertTrue("Theme changes must not navigate", callbacks.isEmpty()) }
            compose.onNodeWithTag("tab_today").assertIsSelected()
            val visited = mutableListOf<Int>()
            listOf(1, 2, 0).forEach { destination ->
                compose.onNodeWithTag(tabs[destination].first).performTouchInput { click(center) }
                visited += destination
                compose.runOnIdle { assertEquals(visited, callbacks) }
                tabs.forEachIndexed { index, (tag, label) ->
                    val control = compose.onNodeWithTag(tag).assertIsDisplayed().assertTextContains(label)
                    if (index == destination) control.assertIsSelected() else control.assertIsNotSelected()
                    compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                }
                saveScreenshot("icon_navigation", "icon-navigation-${if (isDark) "dark" else "light"}-$destination.png")
            }
        }
    }

    private fun glyphPixels(key: String): IntArray {
        val bitmap = compose.onNodeWithTag("glyph_$key").assertIsDisplayed().captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs((a shr 16 and 255) - (b shr 16 and 255)) +
            abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))

    private fun saveScreenshot(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(context.getExternalFilesDir(null), "ui-acceptance")).apply { mkdirs() }
        File(directory, name).outputStream().use {
            assertTrue(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private data class Glyph(val key: String, val label: String, val normal: Int, val selected: Int? = null)

    private val glyphs = listOf(
        Glyph("today", "今天", PareIcons.Today, PareIcons.TodaySelected),
        Glyph("quadrants", "四象限", PareIcons.Quadrants, PareIcons.QuadrantsSelected),
        Glyph("idea", "想法", PareIcons.Idea, PareIcons.IdeaSelected),
        Glyph("calendar", "日期", PareIcons.Calendar),
        Glyph("play", "开始", PareIcons.Play),
        Glyph("pause", "暂停", PareIcons.Pause),
        Glyph("note", "记录", PareIcons.Note),
        Glyph("finding", "发现", PareIcons.Finding),
        Glyph("decision", "决定", PareIcons.Decision),
        Glyph("seed", "萌芽", PareIcons.Seed),
        Glyph("back", "返回", PareIcons.Back),
        Glyph("previous", "前一天", PareIcons.Previous),
        Glyph("next", "后一天", PareIcons.Next),
        Glyph("down", "展开", PareIcons.Down),
        Glyph("up", "收起", PareIcons.Up),
        Glyph("close", "关闭", PareIcons.Close),
        Glyph("more", "更多", PareIcons.More),
        Glyph("edit", "编辑", PareIcons.Edit),
        Glyph("add", "添加", PareIcons.Add),
        Glyph("check", "完成", PareIcons.Check),
        Glyph("star", "重点", PareIcons.Star),
        Glyph("sparkle", "AI", PareIcons.Sparkle),
        Glyph("link", "关联", PareIcons.Link),
        Glyph("unlink", "取消关联", PareIcons.Unlink),
        Glyph("archive", "归档", PareIcons.Archive),
        Glyph("refresh", "刷新", PareIcons.Refresh),
        Glyph("complete", "已完成", PareIcons.CheckCircle),
        Glyph("clock", "时间", PareIcons.Clock),
    )
}
