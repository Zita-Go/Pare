package com.pare.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real long presses and pointer moves, with production cards and entirely fictional local state. */
class OrganizeDragTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2099-05-20T09:00:00Z")
    private lateinit var state: MutableState<SyncState>
    private val moves = mutableListOf<OrganizeDrop>()
    private var rejectSave = false
    private var cardActions = 0
    private fun id(number: Int) = "98000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun row(number: Int) = node("organize_drag_${id(number)}")
    private fun ids(quadrant: Int) = OrganizeActions.tasks(state.value, quadrant).map { it.id }

    @Test fun stationaryLongPressAndReleaseOutsideBoardDoNotSave() {
        val initial = show()
        val firstRow = row(1).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val origin = Offset(firstRow.left + firstRow.width / 3f, firstRow.center.y)
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            compose.onRoot().performTouchInput { down(origin) }
            pointerDown = true
            compose.mainClock.advanceTimeBy(700)
            compose.onRoot().performTouchInput { advanceEventTime(700) }
            compose.mainClock.advanceTimeByFrame()
            node("organize_drop_targets").assertIsDisplayed()
            compose.onRoot().performTouchInput { up() }
            pointerDown = false
            compose.mainClock.advanceTimeByFrame()
        } finally {
            if (pointerDown) compose.onRoot().performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals(initial, state.value)
        assertTrue(moves.isEmpty())
        assertEquals(0, cardActions)
        node("organize_drop_targets").assertDoesNotExist()

        drag(1, destination = {
            val board = node("drag_board").fetchSemanticsNode().boundsInRoot
            Offset(board.center.x, board.bottom + 16f)
        }) {
            assertEquals(initial, state.value)
            assertTrue(moves.isEmpty())
        }
        assertEquals(initial, state.value)
        assertTrue(moves.isEmpty())
        assertEquals(0, cardActions)
        node("organize_drop_targets").assertDoesNotExist()
    }

    @Test fun cancelledLongPressDoesNotSaveOrActivateTaskAndEmptyQuadrantAcceptsRepeatedMoves() {
        val initial = show()
        node("organize_drop_targets").assertDoesNotExist()
        drag(1, destination = { node("organize_drop_3").fetchSemanticsNode().boundsInRoot.center }, cancelGesture = true) {
            assertEquals(initial, state.value)
            assertTrue(moves.isEmpty())
            optionalScreenshot("organize-drag-empty-quadrant-preview.png")
        }
        assertEquals(initial, state.value)
        assertTrue(moves.isEmpty())
        assertEquals(0, cardActions)
        node("organize_drop_targets").assertDoesNotExist()

        drag(1, destination = { node("organize_drop_3").fetchSemanticsNode().boundsInRoot.center }) {
            assertEquals(initial, state.value)
            assertTrue(moves.isEmpty())
        }
        assertEquals(listOf(id(1)), ids(3))
        assertEquals(listOf(id(2), id(3)), ids(0))
        assertEquals(1, moves.size)
        assertEquals(3, state.value.items.getValue("task:${id(1)}").content()!!.getValue("quadrant").jsonPrimitive.int)

        node("drag_list").performScrollToNode(hasTestTag("organize_drag_${id(1)}"))
        val saved = state.value
        drag(1, destination = { node("organize_drop_1").fetchSemanticsNode().boundsInRoot.center }) {
            assertEquals(saved, state.value)
            assertEquals(1, moves.size)
        }
        assertEquals(listOf(id(1)), ids(1))
        assertTrue(ids(3).isEmpty())
        assertEquals(2, moves.size)
        assertEquals(0, cardActions)
    }

    @Test fun hoveringBeforeAndAfterRowsPreviewsStableIdsAndOnlyReleaseChangesOrder() {
        val initial = show()
        drag(3, destination = { row(2).fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.top + it.height / 4f) } }) {
            assertTrue("Quadrant targets must leave the first task available for in-list reordering",
                node("organize_drop_targets").fetchSemanticsNode().boundsInRoot.bottom <= row(1).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top)
            node("organize_insert_${id(2)}").assertIsDisplayed()
            assertEquals(initial, state.value)
            assertTrue(moves.isEmpty())
            optionalScreenshot("organize-drag-insertion-preview.png")
        }
        assertEquals(listOf(id(1), id(3), id(2)), ids(0))
        assertEquals(id(2), moves.single().beforeId)
        assertTrue(row(3).fetchSemanticsNode().boundsInRoot.top < row(2).fetchSemanticsNode().boundsInRoot.top)

        val saved = state.value
        drag(2, destination = { row(1).fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.bottom - it.height / 4f) } }) {
            node("organize_insert_${id(3)}").assertIsDisplayed()
            assertEquals(saved, state.value)
            assertEquals(1, moves.size)
        }
        assertEquals(listOf(id(1), id(2), id(3)), ids(0))
        assertEquals(id(3), moves.last().beforeId)
        assertEquals(2, moves.size)
        assertEquals(initial.items.getValue("task:${id(2)}").content()!!["lastProgressAt"],
            state.value.items.getValue("task:${id(2)}").content()!!["lastProgressAt"])
        assertEquals(0, cardActions)
    }

    @Test fun rejectedSaveKeepsOriginalPlacementAndAllowsRetry() {
        val initial = show()
        rejectSave = true
        drag(1, destination = { node("organize_drop_3").fetchSemanticsNode().boundsInRoot.center }) {
            assertEquals(initial, state.value)
        }
        assertEquals(1, moves.size)
        assertEquals(initial, state.value)
        assertEquals(listOf(id(1), id(2), id(3)), ids(0))
        assertTrue(ids(3).isEmpty())
        node("organize_drop_targets").assertDoesNotExist()
        row(1).assertIsDisplayed()

        rejectSave = false
        drag(1, destination = { node("organize_drop_3").fetchSemanticsNode().boundsInRoot.center }) {
            assertEquals(initial, state.value)
        }
        assertEquals(2, moves.size)
        assertEquals(listOf(id(1)), ids(3))
    }

    @Test fun edgeAutoScrollKeepsDragAliveWhenOriginalRowLeavesComposition() {
        val initial = show(taskCount = 24)
        drag(1, destination = {
            node("drag_board").fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.bottom - 1f) }
        }) {
            compose.mainClock.advanceTimeBy(3_000)
            row(1).assertDoesNotExist()
            node("organize_drop_3").assertIsDisplayed()
            assertEquals(initial, state.value)
            assertTrue(moves.isEmpty())
            val emptyQuadrant = node("organize_drop_3").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { moveTo(emptyQuadrant) }
            compose.mainClock.advanceTimeByFrame()
        }
        assertEquals(1, moves.size)
        assertEquals(id(1), moves.single().taskId)
        assertEquals(listOf(id(1)), ids(3))
        assertEquals(23, ids(0).size)
    }

    private fun show(taskCount: Int = 3): SyncState {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.pare.app.qa")
        val items = (1..taskCount).map { number ->
            val payload = ContentActions.save(null, "task", "虚构：第${number}页观察笔记", "", 0, id(number), now)
            Entity("task", id(number), JsonObject(payload + ("content" to JsonObject(
                payload.getValue("content").jsonObject + ("quadrantOrder" to JsonPrimitive(number * 1024L))))))
        }
        val initial = SyncState(device = "fictional-organize-drag", items = items.associateBy { it.key })
        state = mutableStateOf(initial)
        compose.setContent {
            PareTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                  Surface(Modifier.fillMaxWidth().height(600.dp)) {
                    OrganizeDragLayout(state.value, enabled = true,
                        modifier = Modifier.fillMaxSize().testTag("drag_board"),
                        onMove = { drop ->
                            moves += drop
                            // The persistence boundary can reject a drop without publishing new state.
                            if (!rejectSave) state.value = OrganizeActions.move(state.value, drop.taskId,
                                drop.quadrant, drop.beforeId, drop.baseline, now.plusSeconds(60))
                        }) { controller ->
                        LazyColumn(Modifier.fillMaxSize().testTag("drag_list"), state = controller.listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
                            (0..3).forEach { quadrant ->
                                val tasks = OrganizeActions.tasks(state.value, quadrant)
                                item(key = "quadrant:$quadrant") { controller.Header(quadrant, tasks.size) }
                                itemsIndexed(tasks, key = { _, task -> task.key }) { index, task ->
                                    controller.Task(task) {
                                        EntityCard(task, false, { cardActions++ }, { cardActions++ }, { cardActions++ },
                                            inToday = false, firstInGroup = index == 0, lastInGroup = index == tasks.lastIndex)
                                    }
                                }
                                item(key = "end:$quadrant") { controller.End(quadrant, tasks.isEmpty()) }
                            }
                        }
                    }
                  }
                }
            }
        }
        compose.waitForIdle()
        return initial
    }

    /** Keep a real pointer down while inspecting the visual preview and immutable saved state. */
    private fun drag(source: Int, destination: () -> Offset, cancelGesture: Boolean = false, hovering: () -> Unit) {
        val origin = row(source).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            compose.onRoot().performTouchInput { down(origin) }
            pointerDown = true
            compose.mainClock.advanceTimeBy(700)
            compose.onRoot().performTouchInput { advanceEventTime(700); moveBy(Offset(0f, -1f)) }
            compose.mainClock.advanceTimeByFrame()
            node("organize_drop_targets").assertIsDisplayed()
            val target = destination()
            compose.onRoot().performTouchInput { moveTo(target) }
            compose.mainClock.advanceTimeByFrame()
            hovering()
            compose.onRoot().performTouchInput { if (cancelGesture) cancel() else up() }
            pointerDown = false
            compose.mainClock.advanceTimeByFrame()
        } finally {
            if (pointerDown) compose.onRoot().performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    private fun optionalScreenshot(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val folder = File(directory).apply { mkdirs() }
        File(folder, name).outputStream().use {
            assertTrue(node("drag_board").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
