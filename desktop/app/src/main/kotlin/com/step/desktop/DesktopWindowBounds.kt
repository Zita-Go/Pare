package com.step.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

// Compose's own LocalWindow is internal in 1.9.3; each native app surface supplies its owner here.
internal val LocalDesktopWindow = staticCompositionLocalOf<Window?> { null }

/**
 * All rectangles and dimensions are AWT user-space units, including screen insets.
 * Compose 1.9.3 Windows.desktop.kt passes DpSize.value.roundToInt() to Window.setSize
 * and uses GraphicsConfiguration.bounds minus Toolkit.screenInsets for alignment.
 * Do not divide these values by defaultTransform.scale again on a high-DPI monitor.
 */
internal object DesktopWindowBounds {
    fun workArea(screen: Rectangle, insets: Insets) = Rectangle(
        screen.x + insets.left, screen.y + insets.top,
        (screen.width - insets.left - insets.right).coerceAtLeast(1),
        (screen.height - insets.top - insets.bottom).coerceAtLeast(1)
    )

    fun initialSize(preferred: Dimension, workArea: Rectangle): Dimension = Dimension(
        preferred.width.coerceIn(1, workArea.width.coerceAtLeast(1)),
        preferred.height.coerceIn(1, workArea.height.coerceAtLeast(1))
    )

    fun minimumSize(preferred: Dimension, workArea: Rectangle, current: Dimension? = null): Dimension {
        val result = initialSize(preferred, workArea)
        // AWT setMinimumSize also resizes the window. Moving an already smaller window to a
        // larger monitor must not enlarge it merely because the original minimum fits again.
        if (current != null && current.width > 0 && current.height > 0) {
            result.width = minOf(result.width, current.width)
            result.height = minOf(result.height, current.height)
        }
        return result
    }

    fun fit(bounds: Rectangle, workArea: Rectangle): Rectangle {
        val size = initialSize(bounds.size, workArea)
        return Rectangle(
            bounds.x.coerceIn(workArea.x, workArea.x + workArea.width.coerceAtLeast(1) - size.width),
            bounds.y.coerceIn(workArea.y, workArea.y + workArea.height.coerceAtLeast(1) - size.height),
            size.width, size.height
        )
    }
}

private fun desktopWorkArea(configuration: GraphicsConfiguration): Rectangle {
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }
        .getOrDefault(Insets(0, 0, 0, 0))
    return DesktopWindowBounds.workArea(configuration.bounds, insets)
}

/** Native dialogs may be larger than their capture/widget owner, but must fit its monitor. */
@Composable
internal fun rememberNativeWindowWorkArea(window: Window?): Rectangle? {
    var configurationRevision by remember(window) { mutableIntStateOf(0) }
    DisposableEffect(window) {
        val listener = PropertyChangeListener { configurationRevision++ }
        window?.addPropertyChangeListener("graphicsConfiguration", listener)
        onDispose { window?.removePropertyChangeListener("graphicsConfiguration", listener) }
    }
    // Read the revision so a same-density monitor change also recomposes an open dialog.
    val configuration = remember(window, configurationRevision) { window?.graphicsConfiguration }
    return configuration?.let(::desktopWorkArea)
}

@Composable
private fun rememberInitialWindowSize(width: Int, height: Int): Dimension = remember(width, height) {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    DesktopWindowBounds.initialSize(Dimension(width, height), desktopWorkArea(configuration))
}

@Composable
internal fun rememberFittedWindowState(width: Int, height: Int): WindowState {
    val size = rememberInitialWindowSize(width, height)
    return rememberWindowState(width = size.width.dp, height = size.height.dp, position = WindowPosition(Alignment.Center))
}

@Composable
internal fun rememberFittedDialogState(width: Int, height: Int): DialogState {
    val size = rememberInitialWindowSize(width, height)
    return rememberDialogState(width = size.width.dp, height = size.height.dp, position = WindowPosition(Alignment.Center))
}

/** Fit at opening/reopening or a monitor configuration change, never on every move/resize. */
@Composable
internal fun KeepWindowInWorkArea(window: Window, minimumWidth: Int, minimumHeight: Int, visible: Boolean) {
    val guard = remember(window, minimumWidth, minimumHeight) {
        WindowWorkAreaGuard(window, Dimension(minimumWidth, minimumHeight))
    }
    DisposableEffect(guard) {
        guard.install()
        onDispose { guard.close() }
    }
    LaunchedEffect(guard, visible) { if (visible) guard.requestFit() }
}

private class WindowWorkAreaGuard(private val window: Window, private val preferredMinimum: Dimension) : AutoCloseable {
    private var closed = false
    private var pending = false
    private val configurationListener = PropertyChangeListener { requestFit() }
    private val windowListener = object : WindowAdapter() {
        override fun windowOpened(event: WindowEvent) = requestFit()
        override fun windowStateChanged(event: WindowEvent) {
            if (event.newState == Frame.NORMAL) requestFit()
        }
    }
    private val componentListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) = requestFit()
    }

    fun install() {
        window.addPropertyChangeListener("graphicsConfiguration", configurationListener)
        window.addWindowListener(windowListener)
        window.addWindowStateListener(windowListener)
        window.addComponentListener(componentListener)
        requestFit()
    }

    fun requestFit() {
        if (closed || pending) return
        pending = true
        // Let AWT finish its DPI/configuration and Compose WindowState updates first.
        SwingUtilities.invokeLater {
            pending = false
            if (!closed && window.isDisplayable) fitIfNeeded()
        }
    }

    private fun fitIfNeeded() {
        // Maximized/fullscreen frames may include invisible borders outside the work area.
        // Leave system placement and its restore bounds under the window manager's control.
        if (window is Frame && window.extendedState != Frame.NORMAL) return
        val configuration = window.graphicsConfiguration ?: return
        if (configuration.device.fullScreenWindow === window) return
        val workArea = desktopWorkArea(configuration)
        val minimum = DesktopWindowBounds.minimumSize(preferredMinimum, workArea, window.size)
        if (window.minimumSize != minimum) window.minimumSize = minimum
        val fitted = DesktopWindowBounds.fit(window.bounds, workArea)
        if (window.bounds != fitted) window.bounds = fitted
    }

    override fun close() {
        closed = true
        window.removePropertyChangeListener("graphicsConfiguration", configurationListener)
        window.removeWindowListener(windowListener)
        window.removeWindowStateListener(windowListener)
        window.removeComponentListener(componentListener)
    }
}
