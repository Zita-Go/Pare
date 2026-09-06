import AppKit
import PareCore

/// Wires everything together and owns the single shared `Store`.
final class AppDelegate: NSObject, NSApplicationDelegate {
    let store = Store(fileURL: Store.defaultFileURL())

    private var statusItemController: StatusItemController!
    private var popoverController: PopoverController!
    private var organizeWindowController: OrganizeWindowController?
    private var settingsWindowController: SettingsWindowController?
    private var weeklyReviewWindowController: WeeklyReviewWindowController?
    private var onboardingWindowController: OnboardingWindowController?
    private var notificationManager: NotificationManager!
    private var attentionDetector: AttentionDriftDetector!
    private var momentumManager: MomentumManager!
    private var shortcutController: CaptureShortcutController!
    private var sleepObserver: NSObjectProtocol?
    private var wakeObserver: NSObjectProtocol?
    private var sleepStoppedTimer: (task: String, minutes: Int)?

    func applicationDidFinishLaunching(_ notification: Notification) {
        store.rolloverIfNeeded()

        popoverController = PopoverController(store: store, onOpenOrganize: { [weak self] in
            self?.openOrganize()
        })

        // Set up notifications early so the delegate is registered before launch
        // fully settles (this also handles a tap that launched the app).
        notificationManager = NotificationManager(store: store, onOpenOrganize: { [weak self] showSummary in
            self?.openOrganize(showSummary: showSummary)
        }, onOpenWeeklyReview: { [weak self] in
            self?.openWeeklyReview()
        }, onOpenCapture: { [weak self] draft in
            self?.openCapture(prefill: draft)
        }, onSnoozeAttention: { [weak self] in
            self?.attentionDetector?.snooze()
        })
        notificationManager.configure()
        installSleepObservers()

        momentumManager = MomentumManager(store: store) { [weak self] task, plan in
            self?.notificationManager.sendImplementationCue(task: task, plan: plan)
        }
        momentumManager.start()

        attentionDetector = AttentionDriftDetector(store: store) { [weak self] task, decision in
            self?.notificationManager.sendAttentionDriftNotice(task: task, decision: decision)
        }
        attentionDetector.start()

        shortcutController = CaptureShortcutController { [weak self] shortcut in
            HotKey(keyCode: shortcut.keyCode, modifiers: shortcut.modifiers) { [weak self] in
                self?.togglePopover()
            }
        }
        shortcutController.start()

        statusItemController = StatusItemController(
            store: store,
            shortcutController: shortcutController,
            onLeftClick: { [weak self] in self?.togglePopover() },
            onOpenOrganize: { [weak self] in self?.openOrganize() },
            onOpenSummary: { [weak self] in self?.openOrganize(showSummary: true) },
            onOpenWeeklyReview: { [weak self] in self?.openWeeklyReview() },
            onOpenSettings: { [weak self] in self?.openSettings() },
            onQuit: { NSApp.terminate(nil) })

        if !UserDefaults.standard.bool(forKey: "pareDidCompleteOnboarding") {
            onboardingWindowController = OnboardingWindowController(
                manager: notificationManager,
                onOpenOrganize: { [weak self] in self?.openOrganize() },
                onFinish: { [weak self] in self?.onboardingWindowController = nil })
            onboardingWindowController?.present()
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        // Catches the case where the app stayed running across midnight, and
        // re-arms the daily reminders for the next day with fresh counts.
        store.rolloverIfNeeded()
        statusItemController?.refreshBadge()
        notificationManager?.reschedule()
    }

    func applicationWillTerminate(_ notification: Notification) {
        let center = NSWorkspace.shared.notificationCenter
        if let sleepObserver { center.removeObserver(sleepObserver) }
        if let wakeObserver { center.removeObserver(wakeObserver) }
        attentionDetector?.stop()
        momentumManager?.stop()
    }

    private func installSleepObservers() {
        let center = NSWorkspace.shared.notificationCenter
        sleepObserver = center.addObserver(forName: NSWorkspace.willSleepNotification,
                                           object: nil, queue: .main) { [weak self] _ in
            guard let self, let task = self.store.activeTimerTask,
                  let minutes = self.store.stopTimer(now: Date()) else { return }
            self.sleepStoppedTimer = (task.text, minutes)
        }
        wakeObserver = center.addObserver(forName: NSWorkspace.didWakeNotification,
                                          object: nil, queue: .main) { [weak self] _ in
            guard let self, let stopped = self.sleepStoppedTimer else { return }
            self.sleepStoppedTimer = nil
            self.notificationManager.sendSleepStoppedNotice(task: stopped.task, minutes: stopped.minutes)
        }
    }

    private func togglePopover() {
        store.rolloverIfNeeded()
        popoverController.toggle(relativeTo: statusItemController.button)
    }

    private func openCapture(prefill: String? = nil) {
        store.rolloverIfNeeded()
        popoverController.show(relativeTo: statusItemController?.button, prefill: prefill)
    }

    /// RFC-0001 T2: `showSummary` expands the 今日小结 card on present — used by the
    /// evening review notification tap and the 右键「今日小结」manual entry.
    private func openOrganize(showSummary: Bool = false) {
        store.rolloverIfNeeded()
        if organizeWindowController == nil {
            organizeWindowController = OrganizeWindowController(
                store: store,
                onClose: {},
                onOpenSettings: { [weak self] in self?.openSettings() },
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
            settingsWindowController = SettingsWindowController(
                store: store, manager: notificationManager, attentionDetector: attentionDetector,
                shortcutController: shortcutController)
        }
        settingsWindowController?.present()
    }
}
