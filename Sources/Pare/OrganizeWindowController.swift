import AppKit
import SwiftUI
import PareCore

/// The larger "整理" window (每天一次) where the four quadrants get room to
/// breathe. While it is open the app temporarily becomes a regular app so the
/// window takes proper keyboard focus for text entry; on close it slips back to
/// being a menu-bar-only accessory.
final class OrganizeWindowController: NSWindowController, NSWindowDelegate {
    private let onClose: () -> Void

    init(store: Store, onClose: @escaping () -> Void) {
        self.onClose = onClose

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 940, height: 640),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false)
        window.title = "整理"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isOpaque = false
        window.backgroundColor = .clear
        window.appearance = NSAppearance(named: .darkAqua)
        window.isReleasedWhenClosed = false
        window.minSize = NSSize(width: 780, height: 540)

        let hosting = NSHostingController(rootView: OrganizeView(store: store))
        window.contentViewController = hosting
        window.center()

        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func present() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        if !AppWindows.anyTitledVisible(excluding: window) {
            NSApp.setActivationPolicy(.accessory)
        }
        onClose()
    }
}
