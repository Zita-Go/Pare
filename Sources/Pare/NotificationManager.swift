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
    /// RFC-0001 T2: `Bool` = 是否展开今日小结（晚间 `daily.review` 通知点击时为 true）。
    private let onOpenOrganize: (Bool) -> Void
    /// RFC-0001 T3: opens the 本周回顾 window (tapping the weekly.review reminder).
    private let onOpenWeeklyReview: () -> Void
    private let onOpenCapture: (String?) -> Void
    private let onSnoozeAttention: () -> Void
    private let center = UNUserNotificationCenter.current()
    private var cancellable: AnyCancellable?
    private var refreshCancellable: AnyCancellable?
    private var pendingReschedule: DispatchWorkItem?

    // Category / action identifiers.
    private static let taskCategory   = "PARE_TASK"
    private static let dailyCategory  = "PARE_DAILY"
    private static let weeklyCategory = "PARE_WEEKLY"
    private static let attentionCategory = "PARE_ATTENTION"
    private static let momentumCategory = "PARE_MOMENTUM"
    private static let timerCategory = "PARE_TIMER_CHECKPOINT"
    private static let parallelCategory = "PARE_PARALLEL_CHECK"
    private static let scheduleCategory = "PARE_SCHEDULE"
    private static let actionComplete = "PARE_COMPLETE"
    private static let actionSnooze15 = "PARE_SNOOZE_15"
    private static let actionSnooze60 = "PARE_SNOOZE_60"
    private static let actionOpen     = "PARE_OPEN"
    private static let actionOpenWeekly = "PARE_OPEN_WEEKLY"
    private static let actionAttentionFocus = "PARE_ATTENTION_FOCUS"
    private static let actionAttentionBlocker = "PARE_ATTENTION_BLOCKER"
    private static let actionAttentionSnooze = "PARE_ATTENTION_SNOOZE"
    private static let actionQuickStart = "PARE_QUICK_START"
    private static let actionTimerContinue = "PARE_TIMER_CONTINUE"
    private static let actionTimerPause = "PARE_TIMER_PAUSE"
    private static let actionParallelPause = "PARE_PARALLEL_PAUSE"
    private static let actionParallelSnooze15 = "PARE_PARALLEL_SNOOZE_15"
    private static let actionParallelSnooze60 = "PARE_PARALLEL_SNOOZE_60"
    private static let actionScheduleStart = "PARE_SCHEDULE_START"
    private static let actionScheduleDelay15 = "PARE_SCHEDULE_DELAY_15"
    private static let testIdentifier = "pare.test"
    /// The stable identifier of the weekly-review reminder (see `Reminders`).
    private static let weeklyIdentifier = "weekly.review"
    /// RFC-0001 T2: the evening review reminder's identifier — tapping it opens
    /// 整理 with the 今日小结 card expanded.
    private static let reviewIdentifier = "daily.review"

    private static let logger = Logger(subsystem: "com.zitago.Pare", category: "notify")
    private static func diag(_ message: String) { logger.notice("\(message, privacy: .public)") }

    init(store: Store,
         onOpenOrganize: @escaping (Bool) -> Void,
         onOpenWeeklyReview: @escaping () -> Void,
         onOpenCapture: @escaping (String?) -> Void,
         onSnoozeAttention: @escaping () -> Void) {
        self.store = store
        self.onOpenOrganize = onOpenOrganize
        self.onOpenWeeklyReview = onOpenWeeklyReview
        self.onOpenCapture = onOpenCapture
        self.onSnoozeAttention = onSnoozeAttention
        super.init()
    }

    // MARK: - Lifecycle

    /// Call once, early in app launch (sets the delegate before launch finishes).
    func configure() {
        center.delegate = self
        registerCategories()
        // Do not throw the system permission sheet at first launch without
        // context. Onboarding and Settings explain the value, then request it.
        logSettings()
        // Keep reminders in sync with any data / settings change.
        cancellable = store.objectWillChange.sink { [weak self] in
            self?.scheduleRescheduleSoon()
        }
        refreshCancellable = Timer.publish(every: 6 * 60 * 60, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in self?.reschedule() }
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

        // RFC-0001 T3: the weekly review opens its own window, so it gets its own
        // action label ("打开回顾") rather than reusing the daily "打开整理".
        let openWeekly = UNNotificationAction(identifier: Self.actionOpenWeekly, title: "打开回顾", options: [.foreground])
        let weekly = UNNotificationCategory(identifier: Self.weeklyCategory,
                                            actions: [openWeekly],
                                            intentIdentifiers: [], options: [])
        let focus = UNNotificationAction(identifier: Self.actionAttentionFocus,
                                         title: "开始 5 分钟", options: [])
        let blocker = UNNotificationAction(identifier: Self.actionAttentionBlocker,
                                           title: "记录卡点", options: [.foreground])
        let attentionSnooze = UNNotificationAction(identifier: Self.actionAttentionSnooze,
                                                   title: "忽略 1 小时", options: [])
        let attention = UNNotificationCategory(identifier: Self.attentionCategory,
                                               actions: [focus, blocker, attentionSnooze],
                                               intentIdentifiers: [], options: [])
        let quickStart = UNNotificationAction(identifier: Self.actionQuickStart,
                                              title: "开始 5 分钟", options: [])
        let momentum = UNNotificationCategory(identifier: Self.momentumCategory,
                                              actions: [quickStart, open],
                                              intentIdentifiers: [], options: [])
        let timerContinue = UNNotificationAction(identifier: Self.actionTimerContinue,
                                                 title: "继续", options: [])
        let timerPause = UNNotificationAction(identifier: Self.actionTimerPause,
                                              title: "暂停", options: [])
        let timer = UNNotificationCategory(identifier: Self.timerCategory,
                                           actions: [timerContinue, timerPause, complete],
                                           intentIdentifiers: [], options: [])
        let parallelPause = UNNotificationAction(identifier: Self.actionParallelPause,
                                                 title: "暂停", options: [])
        let parallel15 = UNNotificationAction(identifier: Self.actionParallelSnooze15,
                                              title: "15 分钟后再检查", options: [])
        let parallel60 = UNNotificationAction(identifier: Self.actionParallelSnooze60,
                                              title: "1 小时后再检查", options: [])
        let parallel = UNNotificationCategory(identifier: Self.parallelCategory,
                                              actions: [complete, parallelPause, parallel15, parallel60],
                                              intentIdentifiers: [], options: [])
        let scheduleStart = UNNotificationAction(identifier: Self.actionScheduleStart,
                                                 title: "开始专注", options: [])
        let scheduleDelay = UNNotificationAction(identifier: Self.actionScheduleDelay15,
                                                 title: "延后 15 分钟", options: [])
        let schedule = UNNotificationCategory(identifier: Self.scheduleCategory,
                                              actions: [scheduleStart, scheduleDelay, complete],
                                              intentIdentifiers: [], options: [])
        center.setNotificationCategories([task, daily, weekly, attention, momentum, timer, parallel, schedule])
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
                    content.userInfo = ["taskID": taskID.uuidString]
                }
                switch r.kind {
                case .task:
                    content.categoryIdentifier = Self.taskCategory
                case .implementation:
                    content.categoryIdentifier = Self.momentumCategory
                case .timerCheckpoint:
                    content.categoryIdentifier = Self.timerCategory
                case .parallelCheck:
                    content.categoryIdentifier = Self.parallelCategory
                case .schedule:
                    content.categoryIdentifier = Self.scheduleCategory
                case .weeklyReview:
                    content.categoryIdentifier = Self.weeklyCategory
                case .plan, .review:
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
        id == "daily.plan" || id == Self.reviewIdentifier || id == Self.weeklyIdentifier
            || id.hasPrefix("task.") || id == Self.testIdentifier || id == "timer.sleep"
            || id.hasPrefix("attention.drift") || id.hasPrefix("implementation.")
            || id.hasPrefix("parallel.check.")
            || id.hasPrefix("schedule.")
            || id == "timer.checkpoint"
    }

    // MARK: - Settings-panel helpers

    /// Fire a notification a few seconds out so the user can confirm delivery.
    private enum SubmissionError: LocalizedError {
        case permissionDenied
        var errorDescription: String? { "系统未允许通知，测试提醒没有发送。请在系统通知设置中允许 Pare。" }
    }

    func sendTestReminder(completion: @escaping (Result<Void, Error>) -> Void) {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            if let error {
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }
            guard granted else {
                DispatchQueue.main.async { completion(.failure(SubmissionError.permissionDenied)) }
                return
            }
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
                DispatchQueue.main.async {
                    if let err { completion(.failure(err)) }
                    else { completion(.success(())) }
                }
            }
        }
    }

    func sendSleepStoppedNotice(task: String, minutes: Int) {
        let content = UNMutableNotificationContent()
        content.title = "专注计时已自动停止"
        content.body = "电脑休眠前已为“\(task)”记录 \(DurationLabel.short(minutes))，避免把离开时间算进去。"
        content.sound = .default
        content.categoryIdentifier = Self.dailyCategory
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)
        center.add(UNNotificationRequest(identifier: "timer.sleep", content: content, trigger: trigger))
    }

    func sendAttentionDriftNotice(task: TaskItem, decision: AttentionDriftDecision,
                                  completion: ((Result<Void, Error>) -> Void)? = nil) {
        let content = UNMutableNotificationContent()
        content.title = "要不要先停一下？"
        if decision.browserPageChanges > 0 {
            content.body = "好像在几个页面间快速切换。先推进“\(task.text)”一小段？"
        } else {
            content.body = "刚才在几个应用间切换了 \(decision.switchCount) 次。先推进“\(task.text)”一小段？"
        }
        content.categoryIdentifier = Self.attentionCategory
        content.interruptionLevel = .active
        content.userInfo = ["taskID": task.id.uuidString]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let id = "attention.drift.\(Int(Date().timeIntervalSince1970))"
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger)) { error in
            DispatchQueue.main.async {
                if let error { completion?(.failure(error)) }
                else { completion?(.success(())) }
            }
        }
    }

    func sendImplementationCue(task: TaskItem, plan: ImplementationPlan) {
        let content = UNMutableNotificationContent()
        content.title = "现在是个好起点"
        content.body = plan.summary
        content.categoryIdentifier = Self.momentumCategory
        content.interruptionLevel = .active
        content.userInfo = ["taskID": task.id.uuidString]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        // Keep live cues outside `isOurs`: marking the plan as triggered also
        // schedules a reminder refresh, which must not remove this 1s request.
        let id = "momentum.live.\(task.id.uuidString).\(Int(Date().timeIntervalSince1970))"
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
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
        if notification.request.identifier.hasPrefix("attention.drift") {
            completionHandler([.banner, .list])
        } else {
            completionHandler([.banner, .list, .sound])
        }
        DispatchQueue.main.async { [weak self] in self?.reschedule() }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let requestID = response.notification.request.identifier
        let taskID = (info["taskID"] as? String).flatMap { UUID(uuidString: $0) }
        let action = response.actionIdentifier

        DispatchQueue.main.async { [weak self] in
            guard let self else { completionHandler(); return }
            switch action {
            case Self.actionAttentionFocus:
                if let id = taskID {
                    self.store.setDailyWin(id)
                    _ = self.store.startTimer(id, targetMinutes: 5)
                }
            case Self.actionAttentionBlocker:
                self.onOpenCapture("我现在卡在：")
            case Self.actionAttentionSnooze:
                self.onSnoozeAttention()
            case Self.actionQuickStart:
                if let id = taskID {
                    self.store.setDailyWin(id)
                    _ = self.store.startTimer(id, targetMinutes: 5)
                }
            case Self.actionTimerContinue:
                self.store.continueTimerWithoutTarget()
            case Self.actionTimerPause:
                _ = self.store.stopTimer()
            case Self.actionParallelPause:
                if let id = taskID { _ = self.store.pauseParallelRun(id) }
            case Self.actionParallelSnooze15:
                if let id = taskID {
                    self.store.setParallelCheck(id, at: Date().addingTimeInterval(15 * 60))
                    self.reschedule()
                }
            case Self.actionParallelSnooze60:
                if let id = taskID {
                    self.store.setParallelCheck(id, at: Date().addingTimeInterval(60 * 60))
                    self.reschedule()
                }
            case Self.actionScheduleStart:
                if let id = taskID,
                   let task = self.store.tasks.first(where: { $0.id == id }) {
                    _ = self.store.startTimer(id, targetMinutes: task.scheduledMinutes ?? task.estimatedMinutes)
                }
            case Self.actionScheduleDelay15:
                if let id = taskID { self.store.shiftSchedule(id, by: 15) }
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
            case Self.actionOpen, Self.actionOpenWeekly, UNNotificationDefaultActionIdentifier:
                // The weekly review has its own window; everything else opens 整理.
                if requestID == Self.weeklyIdentifier {
                    self.onOpenWeeklyReview()
                } else if requestID.hasPrefix("attention.drift") {
                    self.onOpenOrganize(false)
                } else {
                    // RFC-0001 T2: the evening review tap opens 整理 *and* expands
                    // the 今日小结 card; other opens leave the card as the user left it.
                    self.onOpenOrganize(requestID == Self.reviewIdentifier)
                }
            default:
                break
            }
            completionHandler()
        }
    }
}
