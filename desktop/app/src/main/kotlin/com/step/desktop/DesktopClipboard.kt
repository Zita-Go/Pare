package com.step.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.FlavorListener
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

internal enum class DesktopClipboardOperation { READ, WRITE }

/**
 * Compose 1.9.3's AWT get/setClipEntry implementations do not suspend. Keep write retries on
 * that same call stack: M3 cut deletes the selection only AFTER setClipEntry returns. Delaying
 * in a coroutine would let the selection change between copying and deleting it.
 */
internal class DesktopClipboard(
    private val delegate: Clipboard,
    private val unavailable: (DesktopClipboardOperation?) -> Unit = {},
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) : Clipboard {
    var failure by mutableStateOf<DesktopClipboardOperation?>(null)
        private set

    override val nativeClipboard: Any by lazy {
        val native = delegate.nativeClipboard
        if (native is java.awt.datatransfer.Clipboard) AvailabilityClipboard(native) { flavor ->
            // Desktop M3's context-menu availability getter is synchronous, outside an action
            // coroutine. An unavailable probe means "no paste", never a successful write.
            try { access(DesktopClipboardOperation.READ) { native.isDataFlavorAvailable(flavor) } }
            catch (_: ClipboardBusyCancellation) { false }
        } else native
    }

    override suspend fun getClipEntry(): ClipEntry? = access(DesktopClipboardOperation.READ) {
        // Material text fields consume plain text. Snapshot it while still inside the retry
        // boundary; returning the OS's lazy Transferable would allow a later busy exception.
        val transferable = delegate.getClipEntry()?.nativeClipEntry as? Transferable
        snapshotText(transferable)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = access(DesktopClipboardOperation.WRITE) {
        delegate.setClipEntry(clipEntry)
    }

    fun dismissFailure() = report(null)

    private fun report(operation: DesktopClipboardOperation?) {
        failure = operation
        unavailable(operation)
    }

    private inline fun <T> access(operation: DesktopClipboardOperation, action: () -> T): T {
        for (attempt in 0..2) {
            val result = try { action() } catch (error: IllegalStateException) {
                // WClipboard's observed lock failure and AWT's documented unavailable form.
                // Do not conceal unrelated adapter/programming errors.
                if (!error.message.equals("cannot open system clipboard", ignoreCase = true) &&
                    !error.message.equals("clipboard currently unavailable", ignoreCase = true) &&
                    !error.message.equals("Failed to acquire clipboard lock", ignoreCase = true)) throw error
                if (attempt == 2) {
                    report(operation)
                    // Cancellation only ends the current clipboard action's child coroutine.
                    // Returning Unit here would incorrectly allow Cut to delete the original.
                    throw ClipboardBusyCancellation()
                }
                // Two 20 ms waits at most; no background retry can act on a stale selection.
                try { pause(20L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Clipboard action interrupted")
                }
                continue
            }
            report(null)
            return result
        }
        error("Unreachable clipboard retry state")
    }

    private fun snapshotText(transferable: Transferable?): ClipEntry? {
        if (transferable == null || !transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        // Keep the existing plain-text read behavior for an unsupported/disappearing format or
        // a failed OS data transfer. Only the known busy IllegalStateException is retried.
        val text = try { transferable.getTransferData(DataFlavor.stringFlavor) as? String }
        catch (_: IOException) { null }
        catch (_: UnsupportedFlavorException) { null }
        return text?.let { ClipEntry(StringSelection(it)) }
    }

    private class ClipboardBusyCancellation : CancellationException("System clipboard is temporarily unavailable")

    /** Preserve the native API except for the specific synchronous availability probe M3 uses. */
    private class AvailabilityClipboard(
        private val delegate: java.awt.datatransfer.Clipboard,
        private val available: (DataFlavor) -> Boolean
    ) : java.awt.datatransfer.Clipboard("Step clipboard availability") {
        override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean = available(flavor)
        override fun getContents(requestor: Any?): Transferable? = delegate.getContents(requestor)
        override fun setContents(contents: Transferable?, owner: ClipboardOwner?) = delegate.setContents(contents, owner)
        override fun getAvailableDataFlavors(): Array<DataFlavor> = delegate.availableDataFlavors
        override fun getData(flavor: DataFlavor): Any? = delegate.getData(flavor)
        override fun addFlavorListener(listener: FlavorListener?) = delegate.addFlavorListener(listener)
        override fun removeFlavorListener(listener: FlavorListener?) = delegate.removeFlavorListener(listener)
        override fun getFlavorListeners(): Array<FlavorListener> = delegate.flavorListeners
    }
}

private val LocalClipboardFeedbackHosted = staticCompositionLocalOf { false }

/** One wrapper per native surface; nested themes share it without multiplying retry attempts. */
@Composable
internal fun DesktopClipboardHost(content: @Composable () -> Unit) {
    val upstream = LocalClipboard.current
    val clipboard = remember(upstream) { upstream as? DesktopClipboard ?: DesktopClipboard(upstream) }
    val alreadyHosted = LocalClipboardFeedbackHosted.current
    CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardFeedbackHosted provides true) {
        if (alreadyHosted) content() else Box(propagateMinConstraints = true) {
            content()
            // matchParentSize excludes feedback from measurement: no window/layout resize,
            // popup, or focus request is caused by a failed clipboard operation.
            Box(Modifier.matchParentSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                DesktopClipboardFeedback()
            }
        }
    }
}

@Composable
internal fun DesktopClipboardFeedback() {
    val clipboard = LocalClipboard.current as? DesktopClipboard ?: return
    val operation = clipboard.failure ?: return
    Snackbar(
        modifier = Modifier.widthIn(max = 560.dp).testTag("clipboard_unavailable").semantics { liveRegion = LiveRegionMode.Polite },
        action = { TextButton(onClick = clipboard::dismissFailure) { Text("知道了") } }
    ) {
        Text(if (operation == DesktopClipboardOperation.WRITE)
            "剪贴板被占用，复制或剪切未完成。原文已保留，请稍后重试。"
        else "剪贴板被占用，暂时无法粘贴。请稍后重试。")
    }
}
