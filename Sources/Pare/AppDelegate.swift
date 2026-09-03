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
    private var notificationManager: NotificationManager!
    private var hotKey: HotKey?

    func applicationDidFinishLaunching(_ notification: Notification) {
        store.rolloverIfNeeded()

        // Set up notifications early so the delegate is registered before launch
        // fully settles (this also handles a tap that launched the app).
        notificationManager = NotificationManager(store: store, onOpenOrganize: { [weak self] in
            self?.openOrganize()
        })
        notificationManager.configure()

        popoverController = PopoverController(store: store, onOpenOrganize: { [weak self] in
            self?.openOrganize()
        })

        statusItemController = StatusItemController(
            store: store,
            onLeftClick: { [weak self] in self?.togglePopover() },
            onOpenOrganize: { [weak self] in self?.openOrganize() },
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

    private func openOrganize() {
        store.rolloverIfNeeded()
        if organizeWindowController == nil {
            organizeWindowController = OrganizeWindowController(store: store, onClose: {})
        }
        organizeWindowController?.present()
    }

    private func openSettings() {
        if settingsWindowController == nil {
            settingsWindowController = SettingsWindowController(store: store, manager: notificationManager)
        }
        settingsWindowController?.present()
    }
}
