import AppKit
import Combine
import PareCore

/// Owns the menu bar `NSStatusItem`. Shows a task glyph plus a badge with the
/// number of tasks still to do today ("今天还有 N 件事"). Left-click toggles the
/// capture popover; right-click opens a small menu.
final class StatusItemController {
    let statusItem: NSStatusItem
    var button: NSStatusBarButton? { statusItem.button }

    private let store: Store
    private let onLeftClick: () -> Void
    private let onOpenOrganize: () -> Void
    /// RFC-0001 T2: 右键菜单「今日小结…」手动入口 —— 打开整理窗并展开今日小结卡片。
    private let onOpenSummary: () -> Void
    private let onOpenWeeklyReview: () -> Void
    private let onOpenSettings: () -> Void
    private let onQuit: () -> Void
    private var cancellable: AnyCancellable?
    private var timerCancellable: AnyCancellable?

    init(store: Store,
         onLeftClick: @escaping () -> Void,
         onOpenOrganize: @escaping () -> Void,
         onOpenSummary: @escaping () -> Void,
         onOpenWeeklyReview: @escaping () -> Void,
         onOpenSettings: @escaping () -> Void,
         onQuit: @escaping () -> Void) {
        self.store = store
        self.onLeftClick = onLeftClick
        self.onOpenOrganize = onOpenOrganize
        self.onOpenSummary = onOpenSummary
        self.onOpenWeeklyReview = onOpenWeeklyReview
        self.onOpenSettings = onOpenSettings
        self.onQuit = onQuit
        self.statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        configureButton()
        refreshBadge()

        // Keep the badge in sync with any data change.
        cancellable = store.objectWillChange.sink { [weak self] in
            DispatchQueue.main.async { self?.refreshBadge() }
        }
        timerCancellable = Timer.publish(every: 30, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                guard self?.store.activeTimer != nil else { return }
                self?.refreshBadge()
            }
    }

    private func configureButton() {
        guard let button = statusItem.button else { return }
        button.target = self
        button.action = #selector(handleClick)
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        button.imagePosition = .imageLeading
    }

    func refreshBadge() {
        guard let button = statusItem.button else { return }
        let attention = needsAttention
        button.image = Self.glyph(attention: attention)
        let remaining = store.todayRemaining
        if let task = store.activeTimerTask {
            let minutes = max(1, store.timerElapsedMinutes())
            button.title = " ⏱ \(DurationLabel.short(minutes))"
            button.setAccessibilityLabel("正在专注：\(task.text)，已 \(DurationLabel.short(minutes))")
            button.imagePosition = .imageLeading
            return
        }
        button.title = remaining > 0 ? " \(remaining)" : ""
        button.imagePosition = (button.image == nil) ? .noImage
                             : (remaining > 0 ? .imageLeading : .imageOnly)
        button.setAccessibilityLabel(Self.accessibilityLabel(remaining: remaining, attention: attention))
    }

    /// Whether the menu-bar icon should show its restrained status dot.
    /// RFC-0001: 触发条件 = 待整理堆积 或 象限二有停滞。两个信号都来自 PareCore：
    /// 堆积复用提醒用的同一开关与阈值；停滞直接消费 `store.stalledFocusTasks`（与晨间
    /// 引导同一口径，并自动尊重 focusStalenessEnabled / focusStalenessDays 设置）。
    /// 只用出现/消失表达“有东西该看一下”，不喧宾夺主（不叠加计数、不变色警告）。
    private var needsAttention: Bool {
        let s = store.settings
        let pileup = s.inboxPileupEnabled && store.inboxCount >= s.inboxPileupThreshold
        return pileup || !store.stalledFocusTasks().isEmpty
    }

    /// The menu-bar glyph, optionally carrying a small corner status dot.
    /// RFC-0001: 状态点作为模板图的一部分绘制，随菜单栏明暗自动着色；点周围挖掉一圈
    /// 让它从清单线条里"跳出来"、读作独立标记而非清单的一部分。
    private static func glyph(attention: Bool) -> NSImage? {
        guard #available(macOS 11.0, *),
              let base = NSImage(systemSymbolName: "checklist", accessibilityDescription: "Pare") else {
            return nil
        }
        base.isTemplate = true
        guard attention else { return base }

        let size = base.size
        let composite = NSImage(size: size)
        composite.lockFocus()
        base.draw(in: NSRect(origin: .zero, size: size))
        if let ctx = NSGraphicsContext.current {
            let dot = max(3.0, size.height * 0.30)
            let dotRect = NSRect(x: size.width - dot, y: size.height - dot, width: dot, height: dot)
            // 1. 先在点四周清出一圈透明（模板图里 alpha=0 即"无墨"），与清单笔画分离。
            ctx.compositingOperation = .clear
            NSBezierPath(ovalIn: dotRect.insetBy(dx: -dot * 0.45, dy: -dot * 0.45)).fill()
            // 2. 再画实心点（模板图只看 alpha，用任意不透明色即可）。
            ctx.compositingOperation = .sourceOver
            NSColor.black.setFill()
            NSBezierPath(ovalIn: dotRect).fill()
        }
        composite.unlockFocus()
        composite.isTemplate = true
        return composite
    }

    /// RFC-0001: 让 VoiceOver 读出菜单栏图标的状态（今日剩余 + 是否有待看信号）。
    private static func accessibilityLabel(remaining: Int, attention: Bool) -> String {
        var parts = ["Pare 随手记"]
        if remaining > 0 { parts.append("今天还有 \(remaining) 件未完成") }
        if attention { parts.append("有待整理或停滞的事，抽空看一下") }
        return parts.joined(separator: "，")
    }

    @objc private func handleClick() {
        if let event = NSApp.currentEvent, event.type == .rightMouseUp {
            showMenu()
        } else {
            onLeftClick()
        }
    }

    private func showMenu() {
        let menu = NSMenu()

        let organize = NSMenuItem(title: "整理…", action: #selector(menuOrganize), keyEquivalent: "o")
        organize.target = self
        menu.addItem(organize)

        // RFC-0001 T2: 手动打开今日小结（同时展开整理窗顶部的收束卡片）。
        let summary = NSMenuItem(title: "今日小结…", action: #selector(menuSummary), keyEquivalent: "")
        summary.target = self
        menu.addItem(summary)

        let weekly = NSMenuItem(title: "本周回顾…", action: #selector(menuWeeklyReview), keyEquivalent: "")
        weekly.target = self
        menu.addItem(weekly)

        let settings = NSMenuItem(title: "提醒设置…", action: #selector(menuSettings), keyEquivalent: ",")
        settings.target = self
        menu.addItem(settings)

        menu.addItem(.separator())

        let clickHint = NSMenuItem(title: "左键点图标：随手记", action: nil, keyEquivalent: "")
        clickHint.isEnabled = false
        menu.addItem(clickHint)

        let info = NSMenuItem(title: "全局快捷键：⌥Space", action: nil, keyEquivalent: "")
        info.isEnabled = false
        menu.addItem(info)

        menu.addItem(.separator())

        let quit = NSMenuItem(title: "退出 Pare", action: #selector(menuQuit), keyEquivalent: "q")
        quit.target = self
        menu.addItem(quit)

        // Attach transiently so the normal click action keeps working afterward.
        statusItem.menu = menu
        button?.performClick(nil)
        statusItem.menu = nil
    }

    @objc private func menuOrganize() { onOpenOrganize() }
    @objc private func menuSummary() { onOpenSummary() }
    @objc private func menuWeeklyReview() { onOpenWeeklyReview() }
    @objc private func menuSettings() { onOpenSettings() }
    @objc private func menuQuit() { onQuit() }
}
