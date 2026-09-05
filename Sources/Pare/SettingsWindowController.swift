import AppKit
import SwiftUI
import UniformTypeIdentifiers
import PareCore

/// Shared helper for the accessory ↔ regular activation-policy dance: a
/// menu-bar-only app should drop back to `.accessory` only when *no* real
/// window is left on screen.
enum AppWindows {
    static func anyTitledVisible(excluding: NSWindow?) -> Bool {
        NSApp.windows.contains { w in
            w !== excluding && w.isVisible && w.styleMask.contains(.titled)
        }
    }
}

/// The small "提醒设置" window: notification permission, the daily plan/review
/// reminders (on/off + time), the inbox-pileup nudge, and a one-tap test.
/// Per-task reminders are set on the tasks themselves in the 整理 window.
final class SettingsWindowController: NSWindowController, NSWindowDelegate {
    private let shortcutController: CaptureShortcutController

    init(store: Store, manager: NotificationManager, attentionDetector: AttentionDriftDetector,
         shortcutController: CaptureShortcutController) {
        self.shortcutController = shortcutController
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 560),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false)
        window.title = "Pare 设置"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isOpaque = false
        window.backgroundColor = .clear
        window.appearance = NSAppearance(named: .darkAqua)
        window.isReleasedWhenClosed = false

        let hosting = NSHostingController(rootView: SettingsView(
            store: store, manager: manager, attentionDetector: attentionDetector,
            shortcutController: shortcutController))
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
        shortcutController.cancelRecording()
        if !AppWindows.anyTitledVisible(excluding: window) {
            NSApp.setActivationPolicy(.accessory)
        }
    }

    func windowDidResignKey(_ notification: Notification) {
        shortcutController.cancelRecording()
    }
}

// MARK: - SettingsView

private struct SettingsView: View {
    @ObservedObject var store: Store
    let manager: NotificationManager
    @ObservedObject var attentionDetector: AttentionDriftDetector
    @ObservedObject var shortcutController: CaptureShortcutController
    @StateObject private var loginItem = LoginItemManager()

    @State private var authText = "检查中…"
    @State private var testSent = false
    @State private var dataMessage: String?
    @State private var attentionMessage: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Pare 设置")
                    .font(Theme.font(20, weight: .semibold))
                    .foregroundColor(Theme.text)

                settingsGroup("快捷键") {
                    shortcutCard
                }
                permissionCard
                settingsGroup("启动与数据") {
                    startupAndDataCard
                }
                settingsGroup("提醒与节奏") {
                    planCard
                    groupDivider
                    reviewCard
                    groupDivider
                    weeklyCard
                    groupDivider
                    pileupCard
                }
                settingsGroup("注意力漂移") {
                    attentionDriftCard
                }
                settingsGroup("规划边界") {
                    overloadCard
                }
                settingsGroup("整理纪律") {
                    focusStalenessCard
                    groupDivider
                    quadrantCleanupCard
                }

                Text("单个任务的定时提醒：在「整理」窗口点任务右侧的「…」设置任意时间与重复规则。到点可选「完成 / 稍后」。")
                    .font(Theme.font(11))
                    .foregroundColor(Theme.textDim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 20)
            .padding(.top, 30)
            .padding(.bottom, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: 460, height: 560)
        .background(FrostBackground(tint: 0.6))
        .onAppear(perform: refreshAuth)
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            // Returning from System Settings (or reopening the panel) should
            // reflect a freshly granted permission without extra taps.
            refreshAuth()
            loginItem.refresh()
            attentionDetector.refreshConfiguration()
        }
    }

    // MARK: Cards

    private var shortcutCard: some View {
        sectionContent {
            HStack {
                Text("唤出 / 收起随手记")
                    .font(Theme.font(13, weight: .medium)).foregroundColor(Theme.text)
                Spacer()
                ShortcutRecorder(controller: shortcutController)
                    .frame(width: 186, height: 36)
            }
            Text(shortcutController.isRecording
                 ? "请按组合键；Esc 或点别处取消。录入时原快捷键暂时停用。"
                 : "点击右侧录入新组合，立即生效；至少包含 ⌘、⌥ 或 ⌃。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Text("默认 ⌥Space · 仅保存在这台 Mac")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
                Spacer()
                Button("恢复默认") { _ = shortcutController.apply(.default) }
                    .buttonStyle(.plain).font(Theme.font(11, weight: .medium))
                    .foregroundColor(Theme.amber)
            }
            if let message = shortcutController.message {
                Text(message).font(Theme.font(11)).foregroundColor(Theme.amber)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var startupAndDataCard: some View {
        sectionContent {
            Toggle(isOn: Binding(get: { loginItem.enabled }, set: { loginItem.setEnabled($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("登录时自动启动 Pare")
                        .font(Theme.font(15, weight: .medium)).foregroundColor(Theme.text)
                    Text(loginItem.statusText)
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                }
            }
            .toggleStyle(.switch).tint(Theme.amber)

            groupDivider

            if let issue = store.persistenceIssue {
                HStack(alignment: .top, spacing: 7) {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundColor(Theme.qUrgent)
                    Text(issue).font(Theme.font(11)).foregroundColor(Theme.text)
                }
            } else {
                Text("每次保存都会保留一份上一版本自动备份。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
            }

            HStack(spacing: 8) {
                actionButton("导出备份") { exportBackup() }
                actionButton("导入备份") { importBackup() }
                actionButton("恢复自动备份") {
                    dataMessage = store.restoreAutomaticBackup() ? "已恢复自动备份 ✓" : "没有可恢复的自动备份"
                }
            }
            if let dataMessage {
                Text(dataMessage).font(Theme.font(11)).foregroundColor(Theme.amber)
            }
        }
    }

    private var permissionCard: some View {
        card {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("通知权限")
                        .font(Theme.font(14, weight: .medium))
                        .foregroundColor(Theme.text)
                    Text(authText)
                        .font(Theme.font(12))
                        .foregroundColor(Theme.textDim)
                }
                Spacer()
            }
            HStack(spacing: 8) {
                actionButton("请求权限") { manager.requestAuthorizationAgain(); scheduleAuthRefresh() }
                actionButton("系统设置") { manager.openSystemNotificationSettings() }
                actionButton(testSent ? "已发送，稍候…" : "发送测试提醒") {
                    manager.sendTestReminder()
                    testSent = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                        testSent = false
                        refreshAuth()
                    }
                }
            }
        }
    }

    private var planCard: some View {
        sectionContent {
            sectionToggle("早晨规划提醒", isOn: settingsBinding(\.planReminderEnabled))
            Text("每天提醒你花几分钟整理、挑出今天要做的几件事。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if store.settings.planReminderEnabled {
                timeRow(planTime)
            }
        }
    }

    private var attentionDriftCard: some View {
        sectionContent {
            sectionToggle("检测应用来回切换", isOn: settingsBinding(\.attentionDriftEnabled))
            Text("只在内存中统计短时间内的前台应用切换，不记录窗口标题、页面内容或历史。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)

            if store.settings.attentionDriftEnabled {
                Stepper(value: settingsBinding(\.attentionDriftWindowMinutes), in: 2...10) {
                    Text("在 \(store.settings.attentionDriftWindowMinutes) 分钟窗口内分析")
                        .font(Theme.font(13)).foregroundColor(Theme.text)
                }
                Stepper(value: settingsBinding(\.attentionDriftSwitchThreshold), in: 5...20) {
                    Text("快速切换达到 \(store.settings.attentionDriftSwitchThreshold) 次时判断")
                        .font(Theme.font(13)).foregroundColor(Theme.text)
                }
                Stepper(value: settingsBinding(\.attentionDriftCooldownMinutes), in: 30...240, step: 30) {
                    Text("提醒后冷却 \(store.settings.attentionDriftCooldownMinutes) 分钟")
                        .font(Theme.font(13)).foregroundColor(Theme.text)
                }
                Stepper(value: settingsBinding(\.attentionDriftDailyLimit), in: 1...6) {
                    Text("每天最多提醒 \(store.settings.attentionDriftDailyLimit) 次")
                        .font(Theme.font(13)).foregroundColor(Theme.text)
                }

                groupDivider

                Toggle(isOn: browserDriftBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("浏览器页面增强检测")
                            .font(Theme.font(15, weight: .medium)).foregroundColor(Theme.text)
                        Text("窗口标题会立即变成进程内临时散列；不保存标题、网址或浏览记录。")
                            .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    }
                }
                .toggleStyle(.switch).tint(Theme.amber)

                if store.settings.attentionDriftBrowserEnabled {
                    Text(attentionDetector.accessibilityText)
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    HStack(spacing: 8) {
                        actionButton("请求辅助功能权限") { attentionDetector.requestAccessibilityPermission() }
                        actionButton("系统设置") { attentionDetector.openAccessibilitySettings() }
                    }
                }
                if let last = attentionDetector.lastDetectionText {
                    Text(last).font(Theme.font(11)).foregroundColor(Theme.amber)
                }
                HStack(spacing: 8) {
                    actionButton("发送测试漂移提醒") { sendAttentionTest() }
                    actionButton("忽略 1 小时") { attentionDetector.snooze() }
                }
                if let attentionMessage {
                    Text(attentionMessage).font(Theme.font(11)).foregroundColor(Theme.amber)
                }
            }
        }
    }

    private func sendAttentionTest() {
        let task = store.todayTasks.first(where: { !$0.completed })
            ?? store.tasks.first(where: { !$0.completed })
        guard let task else {
            attentionMessage = "先添加一件未完成任务，提醒才有推荐目标。"
            return
        }
        manager.sendAttentionDriftNotice(
            task: task,
            decision: AttentionDriftDecision(switchCount: 9, uniqueContextCount: 2,
                                              medianDwellSeconds: 12, returnRatio: 0.8,
                                              browserPageChanges: 4))
        attentionMessage = "测试提醒已发送 ✓"
    }

    private var reviewCard: some View {
        sectionContent {
            sectionToggle("傍晚收尾提醒", isOn: settingsBinding(\.reviewReminderEnabled))
            Text("下班前汇总一句：还有几件没做、或今天完成了几件。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if store.settings.reviewReminderEnabled {
                timeRow(reviewTime)
            }
        }
    }

    // RFC-0001 T3: 每周回顾提醒 —— 周日晚提醒点开「本周回顾」，可开关、改星期与时间。
    private var weeklyCard: some View {
        sectionContent {
            sectionToggle("每周回顾提醒", isOn: settingsBinding(\.weeklyReviewEnabled))
            Text("每周挑一个晚上回看整周：完成了多少、集中在哪个象限、什么总被拖，可一键复制成 Markdown。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
            if store.settings.weeklyReviewEnabled {
                weekdayRow
                timeRow(weeklyTime)
            }
        }
    }

    private var weekdayRow: some View {
        HStack {
            Text("每周").font(Theme.font(13)).foregroundColor(Theme.textDim)
            Spacer()
            Picker("", selection: settingsBinding(\.weeklyReviewWeekday)) {
                ForEach(Self.weekdayOptions, id: \.value) { opt in
                    Text(opt.label).tag(opt.value)
                }
            }
            .labelsHidden()
            .pickerStyle(.menu)
            .tint(Theme.amber)
            .fixedSize()
        }
    }

    private var pileupCard: some View {
        sectionContent {
            sectionToggle("待整理堆积提醒", isOn: settingsBinding(\.inboxPileupEnabled))
            Text("随手记攒多了，就在每日提醒里带一句，提醒你抽空整理。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if store.settings.inboxPileupEnabled {
                Stepper(value: settingsBinding(\.inboxPileupThreshold), in: 3...50) {
                    Text("超过 \(store.settings.inboxPileupThreshold) 条时提醒")
                        .font(Theme.font(13))
                        .foregroundColor(Theme.text)
                }
            }
        }
    }

    // RFC-0001 T4: 三组「主动但克制」的整理纪律信号，均可在此关闭 / 调阈值。

    private var focusStalenessCard: some View {
        sectionContent {
            sectionToggle("象限二停滞提醒", isOn: settingsBinding(\.focusStalenessEnabled))
            Text("「重要 · 不紧急」里的事搁太久，就在早晨规划提醒里带一句，别让深度的事被一直拖着。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
            if store.settings.focusStalenessEnabled {
                Stepper(value: settingsBinding(\.focusStalenessDays), in: 1...30) {
                    Text("超过 \(store.settings.focusStalenessDays) 天没推进时提醒")
                        .font(Theme.font(13))
                        .foregroundColor(Theme.text)
                }
            }
        }
    }

    private var overloadCard: some View {
        sectionContent {
            sectionToggle("今日超载提示", isOn: settingsBinding(\.todayOverloadEnabled))
            Text("今天挑的事太多或排得太满时，在整理窗顶部轻声提示一句 —— 只提示，不阻止你继续添加。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
            if store.settings.todayOverloadEnabled {
                Stepper(value: settingsBinding(\.todayMaxCount), in: 1...20) {
                    Text("超过 \(store.settings.todayMaxCount) 件时提示")
                        .font(Theme.font(13))
                        .foregroundColor(Theme.text)
                }
                Stepper(value: settingsBinding(\.todayCapacityMinutes), in: 30...600, step: 30) {
                    Text("或预估超过 \(DurationLabel.short(store.settings.todayCapacityMinutes)) 时提示")
                        .font(Theme.font(13))
                        .foregroundColor(Theme.text)
                }
            }
        }
    }

    private var quadrantCleanupCard: some View {
        sectionContent {
            sectionToggle("象限四清理引导", isOn: settingsBinding(\.quadrantCleanupEnabled))
            Text("「不重要 · 不紧急」积压时，在那一格顶部给一句清理引导，并提供便捷清空。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
            if store.settings.quadrantCleanupEnabled {
                Stepper(value: settingsBinding(\.quadrantCleanupThreshold), in: 1...30) {
                    Text("积到 \(store.settings.quadrantCleanupThreshold) 件时引导")
                        .font(Theme.font(13))
                        .foregroundColor(Theme.text)
                }
            }
        }
    }

    // MARK: Small pieces

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) { content() }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panel(corner: 12)
    }

    @ViewBuilder
    private func sectionContent<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) { content() }
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func settingsGroup<Content: View>(_ title: String,
                                              @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            Text(title.uppercased())
                .font(Theme.font(11, weight: .semibold))
                .tracking(0.7)
                .foregroundColor(Theme.amber)
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(corner: 12)
    }

    private var groupDivider: some View {
        Rectangle().fill(Theme.hairline).frame(height: 1)
    }

    private func sectionToggle(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(title).font(Theme.font(15, weight: .medium)).foregroundColor(Theme.text)
        }
        .toggleStyle(.switch)
        .tint(Theme.amber)
    }

    private func timeRow(_ time: Binding<Date>) -> some View {
        HStack {
            Text("提醒时间").font(Theme.font(13)).foregroundColor(Theme.textDim)
            Spacer()
            DatePicker("", selection: time, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .datePickerStyle(.stepperField)
                .tint(Theme.amber)
        }
    }

    private func actionButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Theme.font(12, weight: .medium))
                .foregroundColor(Theme.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func exportBackup() {
        let panel = NSSavePanel()
        panel.title = "导出 Pare 备份"
        panel.nameFieldStringValue = "Pare-backup.json"
        panel.allowedContentTypes = [.json]
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try store.exportData(to: url)
            dataMessage = "备份已导出 ✓"
        } catch {
            dataMessage = error.localizedDescription
        }
    }

    private func importBackup() {
        let panel = NSOpenPanel()
        panel.title = "选择 Pare 备份"
        panel.allowedContentTypes = [.json]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }

        let alert = NSAlert()
        alert.messageText = "导入这个备份？"
        alert.informativeText = "当前数据会先保存为自动备份，导入失败时不会改变现有内容。"
        alert.alertStyle = .warning
        alert.addButton(withTitle: "导入")
        alert.addButton(withTitle: "取消")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        do {
            try store.importData(from: url)
            dataMessage = "备份已导入 ✓"
        } catch {
            dataMessage = error.localizedDescription
        }
    }

    // MARK: Bindings & helpers

    private func settingsBinding<T>(_ keyPath: WritableKeyPath<PareCore.Settings, T>) -> Binding<T> {
        Binding(
            get: { store.settings[keyPath: keyPath] },
            set: { newValue in
                var s = store.settings
                s[keyPath: keyPath] = newValue
                store.updateSettings(s)
            })
    }

    private var browserDriftBinding: Binding<Bool> {
        Binding(
            get: { store.settings.attentionDriftBrowserEnabled },
            set: { enabled in
                var settings = store.settings
                settings.attentionDriftBrowserEnabled = enabled
                store.updateSettings(settings)
                if enabled { attentionDetector.requestAccessibilityPermission() }
                else { attentionDetector.refreshConfiguration() }
            })
    }

    private var planTime: Binding<Date> {
        Binding(
            get: { Self.time(hour: store.settings.planHour, minute: store.settings.planMinute) },
            set: { d in
                let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                var s = store.settings
                s.planHour = c.hour ?? 9
                s.planMinute = c.minute ?? 0
                store.updateSettings(s)
            })
    }

    private var reviewTime: Binding<Date> {
        Binding(
            get: { Self.time(hour: store.settings.reviewHour, minute: store.settings.reviewMinute) },
            set: { d in
                let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                var s = store.settings
                s.reviewHour = c.hour ?? 18
                s.reviewMinute = c.minute ?? 0
                store.updateSettings(s)
            })
    }

    private var weeklyTime: Binding<Date> {
        Binding(
            get: { Self.time(hour: store.settings.weeklyReviewHour, minute: store.settings.weeklyReviewMinute) },
            set: { d in
                let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                var s = store.settings
                s.weeklyReviewHour = c.hour ?? 20
                s.weeklyReviewMinute = c.minute ?? 0
                store.updateSettings(s)
            })
    }

    /// Weekday choices for the weekly review, Monday-first for a natural-week
    /// feel, but tagged with `Calendar`'s convention (1 = Sunday … 7 = Saturday)
    /// so the selection maps straight onto `Settings.weeklyReviewWeekday`.
    private static let weekdayOptions: [(label: String, value: Int)] = [
        ("周一", 2), ("周二", 3), ("周三", 4), ("周四", 5), ("周五", 6), ("周六", 7), ("周日", 1)
    ]

    private static func time(hour: Int, minute: Int) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }

    private func refreshAuth() {
        manager.refreshAuthText { authText = $0 }
    }

    private func scheduleAuthRefresh() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0, execute: refreshAuth)
    }
}
