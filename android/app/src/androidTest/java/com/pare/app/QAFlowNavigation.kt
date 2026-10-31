package com.pare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.espresso.Espresso

/** Navigate the actual public UI; this helper never reads or mutates application state. */
internal fun ComposeTestRule.showTaskInLibrary(taskId: String) {
    Espresso.closeSoftKeyboard()
    onNodeWithTag("tab_organize").performClick()
    waitUntil(10_000) { onAllNodesWithTag("main_list").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("main_list").performScrollToNode(hasTestTag("task_$taskId"))
    onNodeWithTag("task_$taskId").assertIsDisplayed()
}

internal fun ComposeTestRule.openTaskAction(taskId: String, action: String) {
    showTaskInLibrary(taskId)
    if (action in setOf("schedule", "run", "details")) {
        onNodeWithTag("more_$taskId").performClick()
        waitUntil(10_000) { onAllNodesWithTag("${action}_$taskId").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("${action}_$taskId").performClick()
    } else {
        onNodeWithTag("edit_$taskId").performClick()
        waitUntil(10_000) { onAllNodesWithTag("task_tools_toggle").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("task_tools_toggle").performScrollTo().performClick()
        waitUntil(10_000) { onAllNodesWithTag("${action}_$taskId").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("${action}_$taskId").performScrollTo().performClick()
    }
}
