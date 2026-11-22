package com.pare.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The real navigation component, with no repository, account, or network setup. */
class CompactNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Before fun requireIsolatedPackage() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.pare.app.qa")
    }

    @Test fun navigationUsesLessHeightAndGrowsForLargeLabelsWithoutLosingTouchTargets() {
        val fontScale = mutableStateOf(1f)
        val selected = mutableStateOf(0)
        val callbacks = mutableListOf<Int>()
        var pixelsPerDp = 1f
        var leftInset = 0
        var rightInset = 0
        var bottomInset = 0
        compose.setContent {
            val density = Density(LocalDensity.current.density, fontScale.value)
            CompositionLocalProvider(LocalDensity provides density) {
                pixelsPerDp = density.density
                val insets = NavigationBarDefaults.windowInsets
                leftInset = insets.getLeft(density, LocalLayoutDirection.current)
                rightInset = insets.getRight(density, LocalLayoutDirection.current)
                bottomInset = insets.getBottom(density)
                PareTheme {
                    Surface(Modifier.width(320.dp)) {
                        Column {
                            // Mirrors the host's status strip above its bottom navigation.
                            Box(Modifier.fillMaxWidth().height(40.dp).testTag("compact_status")) {
                                Text("离线：更改已保存在本机")
                            }
                            Box(Modifier.fillMaxWidth().testTag("compact_navigation")) {
                                PareNavigation(selected.value) { index ->
                                    callbacks += index
                                    selected.value = index
                                }
                            }
                        }
                    }
                }
            }
        }

        var regularHeight = 0f
        listOf(1f, 1.6f).forEach { scale ->
            compose.runOnIdle { fontScale.value = scale; callbacks.clear() }
            val navigation = compose.onNodeWithTag("compact_navigation").fetchSemanticsNode().boundsInRoot
            val status = compose.onNodeWithTag("compact_status").fetchSemanticsNode().boundsInRoot
            val heightDp = (navigation.height - bottomInset) / pixelsPerDp
            assertTrue("Navigation must not overlap its status banner", status.bottom <= navigation.top)
            if (scale == 1f) {
                // 64dp targets plus a divider; an 80dp stock NavigationBar fails this budget.
                assertTrue("Normal-font navigation must release content space: $heightDp dp", heightDp <= 65.5f)
                regularHeight = heightDp
            } else {
                assertTrue("Large labels must be allowed to grow the navigation", heightDp > regularHeight)
                assertTrue("Large-font navigation should remain compact: $heightDp dp", heightDp <= 80f)
            }

            val targets = tabs.map { (tag, label) ->
                val control = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
                    .assertHasClickAction().assertTextContains(label)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val target = control.fetchSemanticsNode().boundsInRoot
                assertContained(label, target, navigation)
                assertTrue("$label must avoid the left system inset", target.left >= navigation.left + leftInset - 1f)
                assertTrue("$label must avoid the right system inset", target.right <= navigation.right - rightInset + 1f)
                assertTrue("$label must avoid the bottom system inset", target.bottom <= navigation.bottom - bottomInset + 1f)

                val icon = compose.onNodeWithTag("${tag}_icon", useUnmergedTree = true)
                    .assertIsDisplayed().assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
                    .fetchSemanticsNode().boundsInRoot
                val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                val textBounds = text.fetchSemanticsNode().boundsInRoot
                assertContained("$label icon", icon, target)
                assertContained("$label text", textBounds, target)
                assertTrue("$label icon and label must not overlap", icon.bottom <= textBounds.top)

                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertEquals(1, layout.lineCount)
                assertFalse(layout.multiParagraph.didExceedMaxLines)
                assertTrue(layout.getLineLeft(0) >= -1f)
                assertTrue("$label must fit horizontally", layout.getLineRight(0) <= layout.size.width + 1f)
                assertTrue(layout.getLineTop(0) >= -1f)
                assertTrue("$label must fit vertically", layout.getLineBottom(0) <= layout.size.height + 1f)
                target
            }
            targets.zipWithNext().forEach { (left, right) ->
                assertTrue("Navigation touch targets must not overlap", left.right <= right.left)
            }
            compose.runOnIdle { assertTrue("A font-size change must not navigate", callbacks.isEmpty()) }
            val visited = mutableListOf<Int>()
            listOf(1, 2, 0).forEach { destination ->
                compose.onNodeWithTag(tabs[destination].first).performTouchInput { click(center) }
                visited += destination
                compose.runOnIdle { assertEquals(visited, callbacks) }
                tabs.forEachIndexed { index, (tag, _) ->
                    if (index == destination) compose.onNodeWithTag(tag).assertIsSelected()
                    else compose.onNodeWithTag(tag).assertIsNotSelected()
                }
            }
        }
    }

    private fun assertContained(name: String, inner: Rect, outer: Rect) {
        assertTrue("$name must be fully inside its parent",
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
                inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
    }

    private val tabs = listOf("tab_today" to "今天", "tab_organize" to "整理", "tab_ideas" to "想法")
}
