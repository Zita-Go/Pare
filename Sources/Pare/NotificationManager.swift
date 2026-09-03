import AppKit
import Combine
import UserNotifications
import os
import PareCore

/// Bridges the pure reminder plan (`Store.plannedReminders`) to macOS system
/// notifications. Kept deliberately thin: all "what / when / text" decisions
/// live in `PareCore` (and are unit-tested); this type only translates them
/// into `UNNotificationRequest`s and handles the user's taps.
///
/// Because Pare is a persistent menu-bar app, we simply reschedule whenever the
/// data changes (debounced) and when the app becomes active — that keeps the
/// daily reminders' counts fresh and re-arms them for the next day.
final class NotificationManager: NSObject, UNUserNotificationCenterDelegate {

    private let store: Store
    private let onOpenOrganize: () -> Void
    private let center = UNUserNotificationCenter.current()
    private var cancellable: AnyCancellable?
    private var pendingReschedule: DispatchWorkItem?

    // Category / action identifiers.
    private static let taskCategory  = "PARE_TASK"
    private static let dailyCategory = "PARE_DAILY"
    private static let actionComplete = "PARE_COMPLETE"
    private static let actionSnooze15 = "PARE_SNOOZE_15"
    private static let actionSnooze60 = "PARE_SNOOZE_60"
    private static let actionOpen     = "PARE_OPEN"
    private static let testIdentifier = "pare.test"

    private static let logger = Logger(subsystem: "com.zitago.Pare", category: "notify")
    private static func diag(_ message: String) { logger.notice("\(message, privacy: .public)") }

    init(store: Store, onOpenOrganize: @escaping () -> Void) {
        self.store = store
        self.onOpenOrganize = onOpenOrganize
        super.init()
    }

    // MARK: - Lifecycle

    /// Call once, early in app launch (sets the delegate before launch finishes).
    func configure() {
        center.delegate = self
        registerCategories()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            Self.diag("authorization granted=\(granted) error=\(error?.localizedDescription ?? "nil")")
            self?.logSettings()
            DispatchQueue.main.async { self?.reschedule() }
        }
        // Keep reminders in sync with any data / settings change.
        cancellable = store.objectWillChange.sink { [weak self] in
            self?.scheduleRescheduleSoon()
        }
        reschedule()
        // Diagnostics: confirm the requests actually landed in the center.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.dumpPending() }
    }

    private func registerCategories() {
        let complete = UNNotificationAction(identifier: Self.actionComplete, title: "完成", options: [])
        let snooze15 = UNNotificationAction(identifier: Self.actionSnooze15, title: "15 分钟后再提醒", options: [])
        let snooze60 = UNNotificationAction(identifier: Self.actionSnooze60, title: "1 小时后再提醒", options: [])
        let task = UNNotificationCategory(identifier: Self.taskCategory,
                                          actions: [complete, snooze15, snooze60],
                                          intentIdentifiers: [], options: [])

        let open = UNNotificationAction(identifier: Self.actionOpen, title: "打开整理", options: [.foreground])
        let daily = UNNotificationCategory(identifier: Self.dailyCategory,
                                           actions: [open],
                                           intentIdentifiers: [], options: [])
        center.setNotificationCategories([task, daily])
    }

    // MARK: - Scheduling

    private func scheduleRescheduleSoon() {
        pendingReschedule?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.reschedule() }
        pendingReschedule = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }

    /// Recompute the plan and mirror it into the notification center. Safe to
    /// call often; it only touches requests it owns.
    func reschedule() {
        let planned = store.plannedReminders(now: Date())
        center.getPendingNotificationRequests { [weak self] existing in
            guard let self else { return }
            let stale = existing.map(\.identifier).filter(self.isOurs)
            if !stale.isEmpty { self.center.removePendingNotificationRequests(withIdentifiers: stale) }

            let cal = Calendar.current
            for r in planned {
                let comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: r.fireDate)
                let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                let content = UNMutableNotificationContent()
                content.title = r.title
                content.body = r.body
                content.sound = .default
                if let taskID = r.taskID {
                    content.categoryIdentifier = Self.taskCategory
                    content.userInfo = ["taskID": taskID.uuidString]
                } else {
                    content.categoryIdentifier = Self.dailyCategory
                }
                let request = UNNotificationRequest(identifier: r.identifier, content: content, trigger: trigger)
                self.center.add(request) { err in
                    if let err { Self.diag("add \(r.identifier) failed: \(err.localizedDescription)") }
                }
            }
            let next = planned.map(\.fireDate).min().map { String(describing: $0) } ?? "none"
            Self.diag("scheduled \(planned.count) reminder(s); next=\(next)")
        }
    }

    private func isOurs(_ id: String) -> Bool {
        id == "daily.plan" || id == "daily.review" || id.hasPrefix("task.") || id == Self.testIdentifier
    }

    // MARK: - Settings-panel helpers

    /// Fire a notification a few seconds out so the user can confirm delivery.
    func sendTestReminder() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] _, _ in
            guard let self else { return }
            let content = UNMutableNotificationContent()
            content.title = "测试提醒"
            content.body = "如果你看到这条，说明 Pare 的提醒已经开启 ✓"
            content.sound = .default
            content.categoryIdentifier = Self.dailyCategory
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 3, repeats: false)
            let request = UNNotificationRequest(identifier: Self.testIdentifier, content: content, trigger: trigger)
            self.center.add(request) { err in
                Self.diag("test add err=\(err?.localizedDescription ?? "nil")")
            }
        }
    }

    func requestAuthorizationAgain() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.reschedule() }
        }
    }

    func openSystemNotificationSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Human-readable authorization status, delivered on the main thread.
    func refreshAuthText(_ completion: @escaping (String) -> Void) {
        center.getNotificationSettings { s in
            let text: String
            switch s.authorizationStatus {
            case .authorized:    text = "已开启 ✓"
            case .denied:        text = "已被系统关闭 · 去「系统设置」开启"
            case .notDetermined: text = "尚未授权 · 点下方按钮开启"
            case .provisional:   text = "临时授权"
            case .ephemeral:     text = "临时"
            @unknown default:    text = "未知"
            }
            DispatchQueue.main.async { completion(text) }
        }
    }

    private func logSettings() {
        center.getNotificationSettings { s in
            Self.diag("authStatus=\(s.authorizationStatus.rawValue) alert=\(s.alertSetting.rawValue)")
        }
    }

    private func dumpPending() {
        center.getPendingNotificationRequests { reqs in
            let ids = reqs.map(\.identifier).sorted().joined(separator: ",")
            Self.diag("pending count=\(reqs.count) ids=[\(ids)]")
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Show even when Pare happens to be the active app; then re-arm daily.
        completionHandler([.banner, .list, .sound])
        DispatchQueue.main.async { [weak self] in self?.reschedule() }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let taskID = (info["taskID"] as? String).flatMap { UUID(uuidString: $0) }
        let action = response.actionIdentifier

        DispatchQueue.main.async { [weak self] in
            guard let self else { completionHandler(); return }
            switch action {
            case Self.actionComplete:
                if let id = taskID { self.store.setCompleted(id, true) }
            case Self.actionSnooze15:
                if let id = taskID {
                    self.store.setRemind(id, at: Date().addingTimeInterval(15 * 60))
                    self.reschedule()
                }
            case Self.actionSnooze60:
                if let id = taskID {
                    self.store.setRemind(id, at: Date().addingTimeInterval(60 * 60))
                    self.reschedule()
                }
            case Self.actionOpen, UNNotificationDefaultActionIdentifier:
                self.onOpenOrganize()
            default:
                break
            }
            completionHandler()
        }
    }
}
