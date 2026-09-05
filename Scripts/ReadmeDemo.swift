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
    private var shortcuts: CaptureShortcutController?
    private var notifications: NotificationManager?
    private var attention: AttentionDriftDetector?
    private let verifyShortcuts = CommandLine.arguments.contains("--verify-shortcuts")
        || Bundle.main.bundleIdentifier == "com.zitago.Pare.ShortcutCheck"

    func applicationDidFinishLaunching(_ notification: Notification) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-readme-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        store = Store(fileURL: directory.appendingPathComponent("store.json"))
        seed()
        makeMenu()

        // A plain neutral backdrop gives the real frosted windows a consistent
        // appearance without including the user's desktop or other apps.
        backdrop = NSWindow(contentRect: NSScreen.main!.frame,
                            styleMask: [.borderless], backing: .buffered, defer: false)
        backdrop.backgroundColor = NSColor(red: 0.10, green: 0.12, blue: 0.11, alpha: 1)
        backdrop.isReleasedWhenClosed = false
        backdrop.orderFront(nil)
        organizer = OrganizeWindowController(store: store, onClose: {})
        capture = PopoverController(store: store) { [weak self] in self?.showOrganizer() }
        if verifyShortcuts {
            shortcuts = CaptureShortcutController { [weak self] shortcut in
                HotKey(keyCode: shortcut.keyCode, modifiers: shortcut.modifiers) { [weak self] in
                    self?.capture.toggle(relativeTo: nil)
                }
            }
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

        let report = store.addTask("完成报告提纲", to: .importantUrgent,
                                   addToToday: true, estimatedMinutes: 25)!
        store.setMomentumDetails(report.id, nextAction: "打开文档，先写三个小标题",
                                 definitionOfDone: "列出结构和要点，暂时不用润色")
        store.addTask("确认明天的预约", to: .importantUrgent, estimatedMinutes: 5)
        store.addTask("整理周末旅行计划", to: .importantNotUrgent,
                      addToToday: true, estimatedMinutes: 30)
        store.addTask("读完一章书并记下想法", to: .importantNotUrgent, estimatedMinutes: 20)
        store.addTask("回复活动时间确认", to: .urgentNotImportant, estimatedMinutes: 5)
        store.addTask("整理收藏夹", to: .neither, estimatedMinutes: 15)
        let done = store.addTask("预约年度体检", to: .importantNotUrgent,
                                 addToToday: true, estimatedMinutes: 10)!
        store.setCompleted(done.id, true)
        store.setActual(done.id, minutes: 8)
        store.setDailyWin(report.id)
        store.capture("找个时间整理书桌")
        store.capture("给旅行相册加一页手绘地图")
        _ = store.addThought("每周留一个不排计划的下午", notes: "散步、阅读，或者什么都不做。")
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
        organizer.window?.setContentSize(NSSize(width: 1060, height: 660))
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
            settings = SettingsWindowController(store: store, manager: notifications,
                                                 attentionDetector: attention, shortcutController: shortcuts)
        }
        settings?.present()
    }
}
