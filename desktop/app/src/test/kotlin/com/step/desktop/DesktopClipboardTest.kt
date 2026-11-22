package com.step.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/** All clipboard data is fictional and every backend is in memory, including the M3 action tests. */
@OptIn(ExperimentalTestApi::class)
class DesktopClipboardTest {
    @Test fun busyWritesRetryBrieflyThenSucceedWithoutReportingFailure() = runBlocking {
        for (message in listOf("cannot open system clipboard", "clipboard currently unavailable", "Failed to acquire clipboard lock")) {
            val backend = FakeClipboard().apply { writeFailures = 2; writeError = IllegalStateException(message) }
            val pauses = mutableListOf<Long>()
            val failures = mutableListOf<DesktopClipboardOperation?>()
            DesktopClipboard(backend, failures::add, pauses::add).setClipEntry(textEntry(ORIGINAL))
            assertEquals(ORIGINAL, text(backend.entry))
            assertEquals(message, 3, backend.writes)
            assertEquals(listOf(20L, 20L), pauses)
            assertFalse(failures.any { it != null })
        }
    }

    @Test fun exhaustedWriteCancelsTheActionInsteadOfPretendingTheCutWasCopied() {
        val backend = FakeClipboard().apply { writeFailures = 100 }
        val pauses = mutableListOf<Long>()
        var failure: DesktopClipboardOperation? = null
        val clipboard = DesktopClipboard(backend, { failure = it }, pauses::add)
        assertThrows(CancellationException::class.java) { runBlocking { clipboard.setClipEntry(textEntry(ORIGINAL)) } }
        assertEquals(DesktopClipboardOperation.WRITE, failure)
        assertEquals(3, backend.writes)
        assertEquals(listOf(20L, 20L), pauses)
        assertEquals("虚构：原剪贴板", text(backend.entry))
    }

    @Test fun lazyTransferableReadsAreRetriedAndReturnedAsAStableTextSnapshot() = runBlocking {
        var value = "虚构：稍后重试读到的中文"
        var dataReads = 0
        val backend = FakeClipboard().apply {
            entry = ClipEntry(object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
                override fun getTransferData(flavor: DataFlavor): Any {
                    dataReads++
                    if (dataReads <= 2) throw busy()
                    return value
                }
            })
        }
        val pauses = mutableListOf<Long>()
        val snapshot = DesktopClipboard(backend, {}, pauses::add).getClipEntry()
        assertEquals(3, dataReads)
        value = "虚构：剪贴板内容已变化"
        assertEquals("虚构：稍后重试读到的中文", text(snapshot))
        assertEquals(3, dataReads)
        assertEquals(listOf(20L, 20L), pauses)
    }

    @Test fun unrelatedIllegalStateExceptionsAreNotSwallowedOrRetried() {
        val problem = IllegalStateException("Broken clipboard adapter invariant")
        val backend = FakeClipboard().apply { writeFailures = 100; writeError = problem }
        var paused = false
        var reported = false
        val clipboard = DesktopClipboard(backend, { reported = true }, { paused = true })
        assertSame(problem, assertThrows(IllegalStateException::class.java) { runBlocking { clipboard.setClipEntry(textEntry(ORIGINAL)) } })
        assertEquals(1, backend.writes)
        assertFalse(paused)
        assertFalse(reported)
    }

    @Test fun nativeContextMenuAvailabilityProbeTreatsABusyClipboardAsUnavailable() {
        var blocked = true
        var probes = 0
        val native = object : java.awt.datatransfer.Clipboard("fictional-memory-only") {
            override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean {
                probes++
                if (blocked) throw busy()
                return flavor == DataFlavor.stringFlavor
            }
        }
        val pauses = mutableListOf<Long>()
        var failure: DesktopClipboardOperation? = null
        val guarded = DesktopClipboard(FakeClipboard(native), { failure = it }, pauses::add)
            .nativeClipboard as java.awt.datatransfer.Clipboard
        assertFalse(guarded.isDataFlavorAvailable(DataFlavor.stringFlavor))
        assertEquals(3, probes)
        assertEquals(listOf(20L, 20L), pauses)
        assertEquals(DesktopClipboardOperation.READ, failure)
        blocked = false
        assertTrue(guarded.isDataFlavorAvailable(DataFlavor.stringFlavor))
    }

    @Test fun materialCutFailurePreservesTheSourceAndTheCompositionRemainsUsable() = runDesktopComposeUiTest(width = 640, height = 400) {
        val backend = FakeClipboard().apply { writeFailures = 100 }
        var failure: DesktopClipboardOperation? = null
        val clipboard = DesktopClipboard(backend, { failure = it }, {})
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                DesktopTheme {
                    var value by remember { mutableStateOf(ORIGINAL) }
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(value, { value = it }, Modifier.testTag("clipboard_field"))
                        Button({ clicks++ }, Modifier.testTag("still_alive")) { Text("继续编辑虚构任务") }
                    }
                }
            }
        }
        val field = onNodeWithTag("clipboard_field")
        field.performTextInputSelection(TextRange(0, ORIGINAL.length))
        field.performSemanticsAction(SemanticsActions.CutText) { it() }
        waitForIdle()
        assertEquals(DesktopClipboardOperation.WRITE, failure)
        field.assertEditable(ORIGINAL)
        onNodeWithTag("clipboard_unavailable").assertIsDisplayed()
        onNodeWithTag("still_alive").performClick()
        assertEquals(1, clicks)

        // A later copy and cut use the same surviving field and coroutine scope.
        backend.writeFailures = 0
        field.performTextInputSelection(TextRange(0, ORIGINAL.length))
        field.performSemanticsAction(SemanticsActions.CopyText) { it() }
        waitForIdle()
        field.assertEditable(ORIGINAL)
        assertEquals(ORIGINAL, text(backend.entry))
        onNodeWithTag("clipboard_unavailable").assertDoesNotExist()
        field.performTextInputSelection(TextRange(0, ORIGINAL.length))
        field.performSemanticsAction(SemanticsActions.CutText) { it() }
        waitForIdle()
        field.assertEditable("")
    }

    @Test fun materialCopyAndPasteFailuresKeepTheFieldAliveAndLaterPasteRecovers() = runDesktopComposeUiTest(width = 640, height = 400) {
        val backend = FakeClipboard().apply { writeFailures = 100 }
        val clipboard = DesktopClipboard(backend, {}, {})
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                DesktopTheme {
                    var value by remember { mutableStateOf(ORIGINAL) }
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(value, { value = it }, Modifier.testTag("clipboard_field"))
                    }
                }
            }
        }
        val field = onNodeWithTag("clipboard_field")
        field.performTextInputSelection(TextRange(0, ORIGINAL.length))
        field.performSemanticsAction(SemanticsActions.CopyText) { it() }
        waitForIdle()
        field.assertEditable(ORIGINAL)
        backend.readFailures = 100
        field.performTextInputSelection(TextRange(0, ORIGINAL.length))
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        waitForIdle()
        field.assertEditable(ORIGINAL)
        backend.readFailures = 0
        backend.entry = textEntry("虚构：恢复后的 paste 文本")
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        waitUntil(timeoutMillis = 5_000) {
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "虚构：恢复后的 paste 文本"
        }
        field.assertEditable("虚构：恢复后的 paste 文本")
    }

    private fun SemanticsNodeInteraction.assertEditable(expected: String) =
        assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

    private class FakeClipboard(override val nativeClipboard: Any = Any()) : Clipboard {
        var entry: ClipEntry? = textEntry("虚构：原剪贴板")
        var writeFailures = 0
        var readFailures = 0
        var writes = 0
        var reads = 0
        var writeError: IllegalStateException = busy()
        override suspend fun getClipEntry(): ClipEntry? {
            reads++
            if (readFailures-- > 0) throw busy()
            return entry
        }
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes++
            if (writeFailures-- > 0) throw writeError
            entry = clipEntry
        }
    }

    private companion object {
        const val ORIGINAL = "虚构：保留这段中文与 English 123"
        fun busy() = IllegalStateException("cannot open system clipboard")
        fun textEntry(value: String) = ClipEntry(StringSelection(value))
        fun text(entry: ClipEntry?): String? = (entry?.nativeClipEntry as? Transferable)
            ?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
