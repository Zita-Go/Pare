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
    private let onOpenSettings: () -> Void
    private let onQuit: () -> Void
    private var cancellable: AnyCancellable?

    init(store: Store,
         onLeftClick: @escaping () -> Void,
         onOpenOrganize: @escaping () -> Void,
         onOpenSettings: @escaping () -> Void,
         onQuit: @escaping () -> Void) {
        self.store = store
        self.onLeftClick = onLeftClick
        self.onOpenOrganize = onOpenOrganize
        self.onOpenSettings = onOpenSettings
        self.onQuit = onQuit
        self.statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        configureButton()
        refreshBadge()

        // Keep the badge in sync with any data change.
        cancellable = store.objectWillChange.sink { [weak self] in
            DispatchQueue.main.async { self?.refreshBadge() }
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
        if #available(macOS 11.0, *) {
            let image = NSImage(systemSymbolName: "checklist", accessibilityDescription: "Pare")
            image?.isTemplate = true
            button.image = image
        } else {
            button.image = nil
        }
        let remaining = store.todayRemaining
        button.title = remaining > 0 ? " \(remaining)" : ""
        button.imagePosition = (button.image == nil) ? .noImage
                             : (remaining > 0 ? .imageLeading : .imageOnly)
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
    @objc private func menuSettings() { onOpenSettings() }
    @objc private func menuQuit() { onQuit() }
}
