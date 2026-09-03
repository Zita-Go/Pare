import AppKit
import SwiftUI
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

    init(store: Store, manager: NotificationManager) {
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

        let hosting = NSHostingController(rootView: SettingsView(store: store, manager: manager))
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
    }
}

// MARK: - SettingsView

private struct SettingsView: View {
    @ObservedObject var store: Store
    let manager: NotificationManager

    @State private var authText = "检查中…"
    @State private var testSent = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("提醒设置")
                    .font(Theme.font(20, weight: .semibold))
                    .foregroundColor(Theme.text)

                permissionCard
                planCard
                reviewCard
                pileupCard

                Text("单个任务的定时提醒：在「整理」窗口点任务右侧的 🔔 设置。到点会单独提醒你，可选「完成 / 稍后」。")
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
        }
    }

    // MARK: Cards

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
        card {
            sectionToggle("早晨规划提醒", isOn: settingsBinding(\.planReminderEnabled))
            Text("每天提醒你花几分钟整理、挑出今天要做的几件事。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if store.settings.planReminderEnabled {
                timeRow(planTime)
            }
        }
    }

    private var reviewCard: some View {
        card {
            sectionToggle("傍晚收尾提醒", isOn: settingsBinding(\.reviewReminderEnabled))
            Text("下班前汇总一句：还有几件没做、或今天完成了几件。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if store.settings.reviewReminderEnabled {
                timeRow(reviewTime)
            }
        }
    }

    private var pileupCard: some View {
        card {
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

    // MARK: Small pieces

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) { content() }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .panel(corner: 12)
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
