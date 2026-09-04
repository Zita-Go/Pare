import AppKit
import ApplicationServices
import Combine
import CoreGraphics
import PareCore

private func pareAttentionAXCallback(_ observer: AXObserver, _ element: AXUIElement,
                                     _ notification: CFString,
                                     _ refcon: UnsafeMutableRawPointer?) {
    guard let refcon else { return }
    let detector = Unmanaged<AttentionDriftDetector>.fromOpaque(refcon).takeUnretainedValue()
    detector.handleAccessibilityNotification(notification as String)
}

/// Detects active-but-fragmented attention without storing browsing history.
/// Base mode observes app activation. Enhanced mode hashes the current browser
/// window title with a process-local salt and retains only that opaque hash for
/// the rolling analysis window.
final class AttentionDriftDetector: ObservableObject {
    @Published private(set) var accessibilityText = "未开启浏览器增强检测"
    @Published private(set) var lastDetectionText: String?
    @Published private(set) var isMonitoring = false

    private let store: Store
    private let onDetected: (TaskItem, AttentionDriftDecision) -> Void
    private var workspaceObservers: [NSObjectProtocol] = []
    private var storeCancellable: AnyCancellable?
    private var events: [AttentionContextEvent] = []
    private var lastMeaningfulActionAt = Date()
    private var sessionActive = true
    private var screenAwake = true
    private var started = false

    private var axObserver: AXObserver?
    private var axApplication: AXUIElement?
    private var axWindow: AXUIElement?
    private var observedPID: pid_t?
    private var observedBrowserBundle: String?
    private var lastBrowserContext: String?
    private var lastBrowserEventAt = Date.distantPast
    private let sessionSalt = UUID().uuidString

    private static let lastPromptKey = "pareAttentionLastPrompt"
    private static let snoozeUntilKey = "pareAttentionSnoozeUntil"
    private static let promptDayKey = "pareAttentionPromptDay"
    private static let promptCountKey = "pareAttentionPromptCount"

    init(store: Store, onDetected: @escaping (TaskItem, AttentionDriftDecision) -> Void) {
        self.store = store
        self.onDetected = onDetected
        storeCancellable = store.objectWillChange.sink { [weak self] in
            guard let self else { return }
            self.lastMeaningfulActionAt = Date()
            self.events.removeAll()
            DispatchQueue.main.async { self.refreshConfiguration() }
        }
    }

    deinit { stop() }

    func start() {
        guard !started else { refreshConfiguration(); return }
        started = true
        let center = NSWorkspace.shared.notificationCenter
        workspaceObservers.append(center.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil, queue: .main) { [weak self] in self?.applicationActivated($0) })
        workspaceObservers.append(center.addObserver(
            forName: NSWorkspace.sessionDidResignActiveNotification,
            object: nil, queue: .main) { [weak self] _ in self?.sessionActive = false })
        workspaceObservers.append(center.addObserver(
            forName: NSWorkspace.sessionDidBecomeActiveNotification,
            object: nil, queue: .main) { [weak self] _ in self?.sessionActive = true })
        workspaceObservers.append(center.addObserver(
            forName: NSWorkspace.screensDidSleepNotification,
            object: nil, queue: .main) { [weak self] _ in self?.screenAwake = false })
        workspaceObservers.append(center.addObserver(
            forName: NSWorkspace.screensDidWakeNotification,
            object: nil, queue: .main) { [weak self] _ in self?.screenAwake = true })
        refreshConfiguration()
    }

    func stop() {
        guard started else { return }
        started = false
        let center = NSWorkspace.shared.notificationCenter
        workspaceObservers.forEach(center.removeObserver)
        workspaceObservers.removeAll()
        detachBrowserObserver()
    }

    func refreshConfiguration() {
        let settings = store.settings
        isMonitoring = settings.attentionDriftEnabled
        refreshAccessibilityStatus()
        if !settings.attentionDriftEnabled || !settings.attentionDriftBrowserEnabled {
            detachBrowserObserver()
        } else if AXIsProcessTrusted(),
                  let app = NSWorkspace.shared.frontmostApplication,
                  isBrowser(app.bundleIdentifier) {
            attachBrowserObserver(to: app)
        }
    }

    func requestAccessibilityPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.refreshConfiguration()
        }
    }

    func openAccessibilitySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") else { return }
        NSWorkspace.shared.open(url)
    }

    func snooze(hours: Double = 1) {
        UserDefaults.standard.set(Date().addingTimeInterval(hours * 3600),
                                  forKey: Self.snoozeUntilKey)
        events.removeAll()
        lastDetectionText = "已暂停 \(Int(hours)) 小时"
    }

    fileprivate func handleAccessibilityNotification(_ notification: String) {
        guard store.settings.attentionDriftEnabled,
              store.settings.attentionDriftBrowserEnabled,
              AXIsProcessTrusted() else { return }
        if notification == (kAXFocusedWindowChangedNotification as String)
            || notification == (kAXMainWindowChangedNotification as String) {
            attachFocusedWindow()
        }
        recordCurrentBrowserPage()
    }

    // MARK: App and browser observations

    private func applicationActivated(_ notification: Notification) {
        guard store.settings.attentionDriftEnabled,
              let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
              let bundle = app.bundleIdentifier,
              bundle != Bundle.main.bundleIdentifier else { return }

        record(context: "app:\(bundle)", kind: .application)
        if store.settings.attentionDriftBrowserEnabled, AXIsProcessTrusted(), isBrowser(bundle) {
            attachBrowserObserver(to: app)
        } else {
            detachBrowserObserver()
        }
    }

    private func isBrowser(_ bundle: String?) -> Bool {
        guard let id = bundle?.lowercased() else { return false }
        let known = ["com.apple.safari", "com.google.chrome", "com.microsoft.edgemac",
                     "company.thebrowser.browser", "com.brave.browser", "org.mozilla.firefox",
                     "com.vivaldi.vivaldi", "com.operasoftware.opera"]
        return known.contains(where: { id == $0 || id.hasPrefix($0 + ".") })
    }

    private func attachBrowserObserver(to app: NSRunningApplication) {
        guard observedPID != app.processIdentifier else { return }
        detachBrowserObserver()
        var observer: AXObserver?
        guard AXObserverCreate(app.processIdentifier, pareAttentionAXCallback, &observer) == .success,
              let observer else { return }

        observedPID = app.processIdentifier
        observedBrowserBundle = app.bundleIdentifier
        axObserver = observer
        let appElement = AXUIElementCreateApplication(app.processIdentifier)
        axApplication = appElement
        let refcon = Unmanaged.passUnretained(self).toOpaque()
        for name in [kAXFocusedWindowChangedNotification, kAXMainWindowChangedNotification,
                     kAXFocusedUIElementChangedNotification] {
            _ = AXObserverAddNotification(observer, appElement, name as CFString, refcon)
        }
        CFRunLoopAddSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(observer), .commonModes)
        attachFocusedWindow()
        lastBrowserContext = currentBrowserContext()
    }

    private func attachFocusedWindow() {
        guard let observer = axObserver, let app = axApplication else { return }
        let refcon = Unmanaged.passUnretained(self).toOpaque()
        if let previous = axWindow {
            _ = AXObserverRemoveNotification(observer, previous, kAXTitleChangedNotification as CFString)
        }
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(app, kAXFocusedWindowAttribute as CFString, &value) == .success,
              let value else { axWindow = nil; return }
        let window = unsafeBitCast(value, to: AXUIElement.self)
        axWindow = window
        _ = AXObserverAddNotification(observer, window, kAXTitleChangedNotification as CFString, refcon)
    }

    private func detachBrowserObserver() {
        if let observer = axObserver {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(observer), .commonModes)
        }
        axObserver = nil
        axApplication = nil
        axWindow = nil
        observedPID = nil
        observedBrowserBundle = nil
        lastBrowserContext = nil
    }

    private func currentBrowserContext() -> String? {
        guard let window = axWindow, let bundle = observedBrowserBundle else { return nil }
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(window, kAXTitleAttribute as CFString, &value) == .success,
              let title = value as? String, !title.isEmpty else { return nil }
        var hasher = Hasher()
        hasher.combine(sessionSalt)
        hasher.combine(title)
        return "page:\(bundle):\(hasher.finalize())"
    }

    private func recordCurrentBrowserPage(now: Date = Date()) {
        guard now.timeIntervalSince(lastBrowserEventAt) >= 0.8,
              recentlyInteracted,
              let context = currentBrowserContext(), context != lastBrowserContext else { return }
        lastBrowserContext = context
        lastBrowserEventAt = now
        record(context: context, kind: .browserPage, now: now)
    }

    private var recentlyInteracted: Bool {
        guard let anyInput = CGEventType(rawValue: UInt32.max) else { return true }
        return CGEventSource.secondsSinceLastEventType(.combinedSessionState,
                                                       eventType: anyInput) < 5
    }

    // MARK: Policy and prompting

    private func record(context: String, kind: AttentionContextKind, now: Date = Date()) {
        let settings = store.settings
        guard settings.attentionDriftEnabled, sessionActive, screenAwake,
              store.activeTimer == nil,
              now.timeIntervalSince(lastMeaningfulActionAt) >= Double(settings.attentionDriftWindowMinutes * 60),
              canPrompt(now: now) else { return }
        if events.last?.context == context { return }
        events.append(AttentionContextEvent(timestamp: now, context: context, kind: kind))
        let cutoff = now.addingTimeInterval(-Double(max(1, settings.attentionDriftWindowMinutes)) * 60)
        events.removeAll { $0.timestamp < cutoff }

        guard let decision = AttentionDriftPolicy.evaluate(
            events: events, now: now,
            windowMinutes: settings.attentionDriftWindowMinutes,
            switchThreshold: settings.attentionDriftSwitchThreshold),
              let task = suggestedTask() else { return }

        registerPrompt(now: now)
        events.removeAll()
        lastDetectionText = "检测到 \(decision.switchCount) 次快速切换"
        onDetected(task, decision)
    }

    private func suggestedTask() -> TaskItem? {
        if let dailyWin = store.dailyWinTask, !dailyWin.completed { return dailyWin }
        if let today = store.todayTasks.first(where: { !$0.completed }) { return today }
        if let due = store.tasks.filter({ !$0.completed && $0.dueAt != nil })
            .sorted(by: { ($0.dueAt ?? .distantFuture) < ($1.dueAt ?? .distantFuture) }).first {
            return due
        }
        return store.stalledFocusTasks().first
    }

    private func canPrompt(now: Date) -> Bool {
        let defaults = UserDefaults.standard
        if let snooze = defaults.object(forKey: Self.snoozeUntilKey) as? Date, snooze > now { return false }
        if let last = defaults.object(forKey: Self.lastPromptKey) as? Date,
           now.timeIntervalSince(last) < Double(store.settings.attentionDriftCooldownMinutes * 60) {
            return false
        }
        resetDailyCountIfNeeded(now: now)
        return defaults.integer(forKey: Self.promptCountKey) < store.settings.attentionDriftDailyLimit
    }

    private func registerPrompt(now: Date) {
        let defaults = UserDefaults.standard
        resetDailyCountIfNeeded(now: now)
        defaults.set(now, forKey: Self.lastPromptKey)
        defaults.set(defaults.integer(forKey: Self.promptCountKey) + 1, forKey: Self.promptCountKey)
    }

    private func resetDailyCountIfNeeded(now: Date) {
        let defaults = UserDefaults.standard
        let day = Calendar.current.startOfDay(for: now).timeIntervalSince1970
        if defaults.double(forKey: Self.promptDayKey) != day {
            defaults.set(day, forKey: Self.promptDayKey)
            defaults.set(0, forKey: Self.promptCountKey)
        }
    }

    private func refreshAccessibilityStatus() {
        if !store.settings.attentionDriftBrowserEnabled {
            accessibilityText = "浏览器增强检测未开启"
        } else if AXIsProcessTrusted() {
            accessibilityText = "辅助功能权限已开启 ✓"
        } else {
            accessibilityText = "需要辅助功能权限；仅在内存中保留标题散列"
        }
    }
}
