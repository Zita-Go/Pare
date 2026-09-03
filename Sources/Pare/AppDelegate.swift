import AppKit
import Carbon.HIToolbox
import PareCore

/// Wires everything together and owns the single shared `Store`.
final class AppDelegate: NSObject, NSApplicationDelegate {
    let store = Store(fileURL: Store.defaultFileURL())

    private var statusItemController: StatusItemController!
    private var popoverController: PopoverController!
    private var organizeWindowController: OrganizeWindowController?
    private var settingsWindowController: SettingsWindowController?
    private var weeklyReviewWindowController: WeeklyReviewWindowController?
    private var notificationManager: NotificationManager!
    private var hotKey: HotKey?

    func applicationDidFinishLaunching(_ notification: Notification) {
        store.rolloverIfNeeded()

        // Set up notifications early so the delegate is registered before launch
        // fully settles (this also handles a tap that launched the app).
        notificationManager = NotificationManager(store: store, onOpenOrganize: { [weak self] showSummary in
            self?.openOrganize(showSummary: showSummary)
        }, onOpenWeeklyReview: { [weak self] in
            self?.openWeeklyReview()
        })
        notificationManager.configure()

        popoverController = PopoverController(store: store, onOpenOrganize: { [weak self] in
            self?.openOrganize()
        })

        statusItemController = StatusItemController(
            store: store,
            onLeftClick: { [weak self] in self?.togglePopover() },
            onOpenOrganize: { [weak self] in self?.openOrganize() },
            onOpenSummary: { [weak self] in self?.openOrganize(showSummary: true) },
            onOpenWeeklyReview: { [weak self] in self?.openWeeklyReview() },
            onOpenSettings: { [weak self] in self?.openSettings() },
            onQuit: { NSApp.terminate(nil) })

        // Global hotkey: ⌥Space — summon capture from anywhere, no permission
        // prompt required (Carbon RegisterEventHotKey). Also just click the menu
        // bar icon; the hotkey is only a convenience.
        hotKey = HotKey(keyCode: UInt32(kVK_Space),
                        modifiers: UInt32(optionKey)) { [weak self] in
            self?.togglePopover()
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        // Catches the case where the app stayed running across midnight, and
        // re-arms the daily reminders for the next day with fresh counts.
        store.rolloverIfNeeded()
        statusItemController?.refreshBadge()
        notificationManager?.reschedule()
    }

    private func togglePopover() {
        store.rolloverIfNeeded()
        popoverController.toggle(relativeTo: statusItemController.button)
    }

    /// RFC-0001 T2: `showSummary` expands the 今日小结 card on present — used by the
    /// evening review notification tap and the 右键「今日小结」manual entry.
    private func openOrganize(showSummary: Bool = false) {
        store.rolloverIfNeeded()
        if organizeWindowController == nil {
            organizeWindowController = OrganizeWindowController(
                store: store,
                onClose: {},
                onOpenWeeklyReview: { [weak self] in self?.openWeeklyReview() })
        }
        organizeWindowController?.present(showSummary: showSummary)
    }

    private func openWeeklyReview() {
        store.rolloverIfNeeded()
        if weeklyReviewWindowController == nil {
            weeklyReviewWindowController = WeeklyReviewWindowController(store: store)
        }
        weeklyReviewWindowController?.present()
    }

    private func openSettings() {
        if settingsWindowController == nil {
            settingsWindowController = SettingsWindowController(store: store, manager: notificationManager)
        }
        settingsWindowController?.present()
    }
}
