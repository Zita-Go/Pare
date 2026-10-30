import AppKit
import SwiftUI
import PareCore

/// Documentation-only launcher. Uses the shipping views and a temporary store;
/// never constructs AppDelegate, schedules notifications, or installs hotkeys.
@main
enum ReadmeDemo {
    static func main() {
        let app = NSApplication.shared
        let delegate = DemoDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.regular)
        app.run()
        withExtendedLifetime(delegate) {}
    }
}

final class DemoDelegate: NSObject, NSApplicationDelegate {
    private var store: Store!
    private var organizer: OrganizeWindowController!
    private var capture: PopoverController!
    private var backdrop: NSWindow!
    private var settings: SettingsWindowController?
    private var weeklyReview: WeeklyReviewWindowController?
    private var shortcuts: CaptureShortcutController?
    private var notifications: NotificationManager?
    private var attention: AttentionDriftDetector?
    private let verifyShortcuts = CommandLine.arguments.contains("--verify-shortcuts")
        || Bundle.main.bundleIdentifier == "com.zitago.Pare.ShortcutCheck"
    private let verifyIntegrations = CommandLine.arguments.contains("--verify-integrations")
    private let parallelDemo = CommandLine.arguments.contains("--parallel-demo")
    private var aiDemoEndpoint: String? {
        CommandLine.arguments.first { $0.hasPrefix("--ai-endpoint=") }
            .map { String($0.dropFirst("--ai-endpoint=".count)) }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-readme-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        store = Store(fileURL: directory.appendingPathComponent("store.json"))
        seed()
        configureDemoAI()
        makeMenu()

        // A plain neutral backdrop gives the real frosted windows a consistent
        // appearance without including the user's desktop or other apps.
        backdrop = NSWindow(contentRect: NSScreen.main!.frame,
                            styleMask: [.borderless], backing: .buffered, defer: false)
        backdrop.backgroundColor = NSColor(red: 0.10, green: 0.12, blue: 0.11, alpha: 1)
        backdrop.isReleasedWhenClosed = false
        backdrop.orderFront(nil)
        organizer = OrganizeWindowController(store: store, onClose: {},
            onOpenSettings: { [weak self] in self?.showShortcutSettings() },
            onOpenWeeklyReview: { [weak self] in self?.showWeeklyReview() })
        capture = PopoverController(store: store) { [weak self] in self?.showOrganizer() }
        shortcuts = CaptureShortcutController { [weak self] shortcut in
            HotKey(keyCode: shortcut.keyCode, modifiers: shortcut.modifiers) { [weak self] in
                self?.capture.toggle(relativeTo: nil)
            }
        }
        if verifyShortcuts {
            shortcuts?.start()
        }
        showOrganizer()
        print("Demo store: \(store.dataFileURL.path)")
        print("Organizer window: \(organizer.window!.windowNumber)")
        fflush(stdout)
    }

    private func seed() {
        UserDefaults.standard.set(false, forKey: "pareCapturePureMode")
        var settings = store.settings
        settings.planReminderEnabled = false
        settings.reviewReminderEnabled = false
        settings.weeklyReviewEnabled = false
        settings.attentionDriftEnabled = false
        settings.todayCapacityMinutes = 120
        store.updateSettings(settings)
        store.rolloverIfNeeded()
        if CommandLine.arguments.contains("--usability-demo") {
            var preferences = store.agentPreferences
            preferences.planningMinutes = 60
            store.updateAgentPreferences(preferences)
        }

        let report = store.addTask("完成报告提纲", to: .importantUrgent,
                                   addToToday: true, estimatedMinutes: 25)!
        store.setMomentumDetails(report.id, nextAction: "打开文档，先写三个小标题",
                                 definitionOfDone: "列出结构和要点，暂时不用润色")
        let confirm = store.addTask("确认明天的预约", to: .importantUrgent, estimatedMinutes: 5)!
        let trip = store.addTask("整理周末旅行计划", to: .importantNotUrgent,
                                 addToToday: true, estimatedMinutes: 30)!
        store.addTask("读完一章书并记下想法", to: .importantNotUrgent)
        let reply = store.addTask("回复活动时间确认", to: .urgentNotImportant, estimatedMinutes: 5)!
        store.addTask("整理收藏夹", to: .neither, estimatedMinutes: 15)
        let done = store.addTask("预约年度体检", to: .importantNotUrgent,
                                 addToToday: true, estimatedMinutes: 10)!
        store.setCompleted(done.id, true)
        store.setActual(done.id, minutes: 8)
        store.setDailyWin(report.id)
        let calendar = Calendar.current
        let now = Date()
        let minute = calendar.component(.minute, from: now)
        let shifted = calendar.date(byAdding: .minute, value: 30 - minute % 15, to: now)!
        var roundedComponents = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: shifted)
        roundedComponents.second = 0
        let rounded = calendar.date(from: roundedComponents)!
        let blockStart = calendar.isDate(rounded, inSameDayAs: now)
            && calendar.component(.hour, from: rounded) < 23
            ? rounded
            : calendar.date(bySettingHour: 22, minute: 0, second: 0, of: now)!
        store.scheduleTask(report.id, at: blockStart, durationMinutes: 30)
        store.setToday(confirm.id, true)
        store.scheduleTask(confirm.id, at: blockStart.addingTimeInterval(30 * 60), durationMinutes: 15)
        if let goalID = store.addGoal("本月完成一份结构清晰的报告"),
           var goal = store.goals.first(where: { $0.id == goalID }) {
            goal.motivation = "先理清结构，再逐步补全内容。"
            goal.taskIDs = [report.id]
            store.updateGoal(goal)
        }
        let demoRequest = AIRequest(workflow: .nextStep, inputs: [AIInput(task: report)])
        let demoDraft = AIDraft(summary: "演示总结：先写提纲，不要求一次完成整份报告。", suggestions: [
            AISuggestion(sourceID: report.id, title: report.text, nextAction: "写三个小标题",
                         definitionOfDone: "留下一份粗提纲", estimatedMinutes: 5)
        ])
        if let summaryID = try? store.recordAISummary(demoDraft, request: demoRequest) {
            store.updateAISummaryOutcome(summaryID, outcome: .filledDraft, adoptedCount: 1)
            store.setAISummaryFeedback(summaryID, feedback: AIUserFeedback(helpful: false, comment: "演示反馈：希望再缩小一点，只先写一个标题。"))
        }
        store.capture("找个时间整理书桌")
        store.capture("给旅行相册加一页手绘地图")
        _ = store.addThought("每周留一个不排计划的下午", notes: "散步、阅读，或者什么都不做。")
        if let idea = store.addThought("探索适合自己的研究流程", notes: "从真实问题出发，逐步整理成可以复用的方法。") {
            _ = store.updateIdea(idea.id, text: idea.text, notes: idea.notes,
                                 nextQuestion: "什么样的记录能帮助下一次开始？", stage: .exploring,
                                 reviewAt: now.addingTimeInterval(7 * 86_400))
            _ = store.addIdeaEntry(ideaID: idea.id, text: "先把一次探索缩小到一个问题，不要求一次完成整个方向。", kind: .finding)
            _ = store.linkTaskToIdea(ideaID: idea.id, taskID: report.id)
            if CommandLine.arguments.contains("--usability-demo") {
                for number in 1...5 {
                    _ = store.addIdeaEntry(ideaID: idea.id, text: "演示记录 \(number)：先验证一个具体问题，再根据结果决定下一步。", kind: .note,
                                          now: now.addingTimeInterval(Double(number)))
                }
                for title in ["访谈一位使用者", "收集三个实际案例", "整理本次观察"] {
                    _ = store.createTaskFromIdea(ideaID: idea.id, title: title)
                }
            }
        }
        if parallelDemo {
            store.startParallelRun(trip.id, now: now.addingTimeInterval(-38 * 60),
                                   nextCheckAt: now.addingTimeInterval(7 * 60))
            store.startParallelRun(reply.id, now: now.addingTimeInterval(-12 * 60))
        }
    }

    /// Screenshot-only configuration. It never copies the user's provider or
    /// key; `--ai-endpoint` must be an explicit loopback mock supplied by the
    /// screenshot command. Ordinary demo runs always start with AI disabled.
    private func configureDemoAI() {
        var config = AIConfiguration()
        if let endpoint = aiDemoEndpoint {
            config.enabled = true
            config.baseURL = endpoint
            config.model = "pare-readme-mock"
        }
        try? AIService.shared.save(config, newKey: "")
    }

    private func makeMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "退出截图演示", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)
        let demoItem = NSMenuItem()
        let demoMenu = NSMenu(title: "演示")
        let organize = NSMenuItem(title: "打开整理", action: #selector(showOrganizer), keyEquivalent: "1")
        organize.target = self
        demoMenu.addItem(organize)
        let input = NSMenuItem(title: "打开随手记", action: #selector(showCapture), keyEquivalent: "2")
        input.target = self
        demoMenu.addItem(input)
        if verifyShortcuts {
            let shortcut = NSMenuItem(title: "验证快捷键设置", action: #selector(showShortcutSettings), keyEquivalent: "3")
            shortcut.target = self
            demoMenu.addItem(shortcut)
        }
        demoItem.submenu = demoMenu
        main.addItem(demoItem)
        // Native edit menu enables ordinary copy/paste within the real views.
        let editItem = NSMenuItem()
        let edit = NSMenu(title: "编辑")
        edit.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.addItem(withTitle: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editItem.submenu = edit
        main.addItem(editItem)
        NSApp.mainMenu = main
    }

    @objc private func showOrganizer() {
        capture?.close()
        organizer.present()
        let compact = CommandLine.arguments.contains("--compact")
        organizer.window?.setContentSize(NSSize(width: compact ? 880 : 1060, height: compact ? 620 : 660))
        organizer.window?.center()
    }

    @objc private func showCapture() {
        organizer.window?.orderOut(nil)
        capture.show(relativeTo: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            if let panel = NSApp.windows.first(where: {
                $0 is NSPanel && !$0.styleMask.contains(.titled)
            }) {
                panel.center()
                print("Capture window: \(panel.windowNumber)")
                fflush(stdout)
            }
        }
    }

    private func showWeeklyReview() {
        if weeklyReview == nil { weeklyReview = WeeklyReviewWindowController(store: store) }
        weeklyReview?.present()
    }

    @objc private func showShortcutSettings() {
        guard let shortcuts else { return }
        capture.close()
        if settings == nil {
            let notifications = NotificationManager(store: store, onOpenOrganize: { _ in },
                                                     onOpenWeeklyReview: {}, onOpenCapture: { _ in },
                                                     onSnoozeAttention: {})
            let attention = AttentionDriftDetector(store: store) { _, _ in }
            self.notifications = notifications
            self.attention = attention
            // Explicit integration QA only: keep ordinary screenshots free of
            // scheduling/monitoring. The seeded detector settings remain off;
            // enabling them and granting OS permissions is a separate UI action.
            if verifyIntegrations {
                notifications.configure()
                attention.start()
            }
            settings = SettingsWindowController(store: store, manager: notifications,
                                                 attentionDetector: attention, shortcutController: shortcuts, syncCoordinator: SyncCoordinator(store: store))
        }
        settings?.present()
    }
}
