import AppKit

/// Standard editing commands belong to the responder chain, including the
/// field editor used by SwiftUI TextField in the nonactivating capture panel.
/// A programmatic menu-bar app does not receive this menu from a storyboard.
enum AppMenus {
    static func makeMainMenu() -> NSMenu {
        let main = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu(title: "Step")
        let quit = appMenu.addItem(withTitle: "退出 Step",
                                   action: #selector(NSApplication.terminate(_:)),
                                   keyEquivalent: "q")
        quit.target = NSApp
        appItem.submenu = appMenu
        main.addItem(appItem)

        let editItem = NSMenuItem()
        editItem.submenu = makeEditMenu()
        main.addItem(editItem)
        return main
    }

    static func makeEditMenu() -> NSMenu {
        let edit = NSMenu(title: "编辑")
        let commands: [(String, Selector, String, NSEvent.ModifierFlags)] = [
            ("撤销", Selector(("undo:")), "z", .command),
            ("重做", Selector(("redo:")), "Z", [.command, .shift]),
            ("剪切", #selector(NSText.cut(_:)), "x", .command),
            ("复制", #selector(NSText.copy(_:)), "c", .command),
            ("粘贴", #selector(NSText.paste(_:)), "v", .command),
            ("全选", #selector(NSText.selectAll(_:)), "a", .command),
        ]
        for (index, command) in commands.enumerated() {
            if index == 2 { edit.addItem(.separator()) }
            let item = edit.addItem(withTitle: command.0, action: command.1,
                                    keyEquivalent: command.2)
            item.keyEquivalentModifierMask = command.3
            // Leave target nil: whichever text control is being edited handles it.
        }
        return edit
    }
}
