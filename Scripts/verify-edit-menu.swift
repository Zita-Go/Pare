import AppKit

/// Runs AppKit menu dispatch without showing windows or posting system events.
/// Compiled with the production menu builder by verify-edit-menu.sh. Removing
/// the edit menu or changing its selector/key equivalent must fail these checks.
@main
@MainActor
enum VerifyEditMenu {
    private static var checks = 0
    private static var failures = 0

    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)

        #if HAS_APP_MENUS
        print("Checking production AppMenus.makeEditMenu()")
        #else
        print("Checking baseline: no application edit menu")
        #endif

        check(makeMenu().items.filter { !$0.isSeparatorItem }.allSatisfy { $0.target == nil },
              "Production actions resolve their target through the responder chain")
        checkSelectAll(isFieldEditor: false)
        checkSelectAll(isFieldEditor: true)
        checkOtherEditCommands()
        checkIgnoredModifiers()
        check(!app.isActive && app.windows.allSatisfy { !$0.isVisible },
              "Verification never activated the app or displayed a window")
        print("Edit menu verification: \(failures == 0 ? "PASS" : "FAIL") (\(checks) checks, \(failures) failures)")
        exit(failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE)
    }

    private static func makeMenu() -> NSMenu {
        #if HAS_APP_MENUS
        return AppMenus.makeEditMenu()
        #else
        return NSMenu(title: "Baseline without edit commands")
        #endif
    }

    private static func checkSelectAll(isFieldEditor: Bool) {
        let menu = makeMenu()
        let editor = NSTextView(frame: .zero)
        editor.isFieldEditor = isFieldEditor
        editor.isEditable = true
        editor.isSelectable = true
        editor.string = "回归测试 café e\u{301} 👩🏽‍💻\n第二行"
        let fullRange = NSRange(location: 0, length: (editor.string as NSString).length)
        editor.setSelectedRange(NSRange(location: fullRange.length, length: 0))
        // Target the native editor explicitly to keep this check independent of
        // visible windows, keyboard focus, the desktop, and the system clipboard.
        for item in menu.items where !item.isSeparatorItem { item.target = editor }
        let handled = menu.performKeyEquivalent(with: event("a", keyCode: 0, modifiers: .command))
        let kind = isFieldEditor ? "field editor" : "text editor"
        check(handled && editor.selectedRange() == fullRange,
              "⌘A selects the complete Unicode text in the native \(kind) " +
              "(handled=\(handled), selected=\(editor.selectedRange()), expected=\(fullRange))")
    }

    private static func checkOtherEditCommands() {
        // Only selection uses a real editor. Recording the remaining selectors
        // checks menu routing without reading or changing the general pasteboard.
        let cases: [(String, UInt16, NSEvent.ModifierFlags, String)] = [
            ("c", 8, .command, "copy:"),
            ("x", 7, .command, "cut:"),
            ("v", 9, .command, "paste:"),
            ("z", 6, .command, "undo:"),
            ("z", 6, [.command, .shift], "redo:"),
        ]
        for (key, keyCode, modifiers, expected) in cases {
            let menu = makeMenu()
            let receiver = EditCommandReceiver()
            for item in menu.items where !item.isSeparatorItem { item.target = receiver }
            let handled = menu.performKeyEquivalent(with: event(key, keyCode: keyCode, modifiers: modifiers))
            check(handled && receiver.received == [expected],
                  "\(modifiers.contains(.shift) ? "⇧" : "")⌘\(key.uppercased()) dispatches \(expected) exactly once " +
                  "(handled=\(handled), received=\(receiver.received))")
        }
    }

    private static func checkIgnoredModifiers() {
        let cases: [(String, NSEvent.ModifierFlags)] = [
            ("a", []), ("a", .control), ("a", [.command, .option]),
            ("a", [.command, .control]), ("a", [.command, .shift]),
            ("v", [.command, .option]), ("z", [.command, .control]),
        ]
        for (key, modifiers) in cases {
            let menu = makeMenu()
            let receiver = EditCommandReceiver()
            for item in menu.items where !item.isSeparatorItem { item.target = receiver }
            let keyCode: UInt16 = key == "a" ? 0 : key == "v" ? 9 : 6
            let handled = menu.performKeyEquivalent(with: event(key, keyCode: keyCode, modifiers: modifiers))
            check(!handled && receiver.received.isEmpty,
                  "Unassigned modifier combination \(modifiers.rawValue)+\(key) does not edit text")
        }
    }

    private static func event(_ key: String, keyCode: UInt16,
                              modifiers: NSEvent.ModifierFlags) -> NSEvent {
        // This event stays inside this process: no CGEvent post or application
        // sendEvent call, and no connection to any user's focused control.
        // AppKit's charactersIgnoringModifiers still preserves Shift. Supplying
        // lowercase here for ⇧⌘Z would create an impossible keyboard event and
        // incorrectly dispatch undo instead of redo.
        let characters = modifiers.contains(.shift) ? key.uppercased() : key
        return NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: modifiers,
                               timestamp: 0, windowNumber: 0, context: nil,
                               characters: characters, charactersIgnoringModifiers: characters,
                               isARepeat: false, keyCode: keyCode)!
    }

    private static func check(_ passed: Bool, _ message: String) {
        checks += 1
        print("\(passed ? "PASS" : "FAIL"): \(message)")
        if !passed { failures += 1 }
    }
}

@MainActor
private final class EditCommandReceiver: NSObject {
    var received: [String] = []
    @objc func selectAll(_ sender: Any?) { received.append("selectAll:") }
    @objc func copy(_ sender: Any?) { received.append("copy:") }
    @objc func cut(_ sender: Any?) { received.append("cut:") }
    @objc func paste(_ sender: Any?) { received.append("paste:") }
    @objc func undo(_ sender: Any?) { received.append("undo:") }
    @objc func redo(_ sender: Any?) { received.append("redo:") }
}
