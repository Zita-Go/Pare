import AppKit
import SwiftUI
import PareCore

/// The larger "整理" window (每天一次) where the four quadrants get room to
/// breathe. While it is open the app temporarily becomes a regular app so the
/// window takes proper keyboard focus for text entry; on close it slips back to
/// being a menu-bar-only accessory.
final class OrganizeWindowController: NSWindowController, NSWindowDelegate {
    private let onClose: () -> Void
    /// RFC-0001 T2: shared with `OrganizeView` so an evening-`review` tap (or the
    /// 右键「今日小结」入口) can force the 今日小结 card open on present.
    private let summaryState = TodaySummaryState()

    init(store: Store, onClose: @escaping () -> Void,
         onOpenWeeklyReview: @escaping () -> Void = {},
         onOpenTaskDetails: @escaping (UUID) -> Void = { _ in }) {
        self.onClose = onClose

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1060, height: 700),
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
        window.minSize = NSSize(width: 880, height: 580)

        let root = OrganizeView(store: store, summaryState: summaryState,
                                onOpenWeeklyReview: onOpenWeeklyReview)
            .environment(\.openTaskDetails, onOpenTaskDetails)
        let hosting = NSHostingController(rootView: root)
        window.contentViewController = hosting
        window.center()

        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    /// Present the window. `showSummary` expands the 今日小结 card (used by the
    /// evening-`review` notification and the manual「今日小结」entry); a normal
    /// open leaves the card in whatever state the user last left it.
    func present(showSummary: Bool = false) {
        if showSummary { summaryState.expanded = true }
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
