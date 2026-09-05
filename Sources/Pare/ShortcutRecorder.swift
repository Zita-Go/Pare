import AppKit
import SwiftUI
import PareCore

struct ShortcutRecorder: NSViewRepresentable {
    @ObservedObject var controller: CaptureShortcutController

    func makeNSView(context: Context) -> ShortcutRecorderView {
        ShortcutRecorderView(controller: controller)
    }

    func updateNSView(_ view: ShortcutRecorderView, context: Context) {
        view.refresh()
    }
}

/// Only listens inside this field's own key window during explicit recording.
/// No global event tap, Accessibility or Input Monitoring permission is used.
final class ShortcutRecorderView: NSView {
    private let controller: CaptureShortcutController
    private var monitor: Any?

    init(controller: CaptureShortcutController) {
        self.controller = controller
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityRole(.button)
        setAccessibilityLabel("修改随手记快捷键")
        setAccessibilityHelp("点按后输入组合键，Esc 取消")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var acceptsFirstResponder: Bool { true }
    override var intrinsicContentSize: NSSize { NSSize(width: 186, height: 36) }

    deinit {
        if let monitor { NSEvent.removeMonitor(monitor) }
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        beginRecording()
    }

    override func accessibilityPerformPress() -> Bool {
        window?.makeFirstResponder(self)
        beginRecording()
        return true
    }

    override func becomeFirstResponder() -> Bool {
        // Tab focus does not itself reserve a key; Space/Return begins capture.
        needsDisplay = true
        return true
    }

    override func resignFirstResponder() -> Bool {
        controller.cancelRecording()
        refresh()
        return true
    }

    override func keyDown(with event: NSEvent) {
        if !controller.isRecording, [36, 49].contains(event.keyCode) {
            beginRecording()
        } else {
            super.keyDown(with: event)
        }
    }

    private func beginRecording() {
        controller.beginRecording()
        if monitor == nil {
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .leftMouseDown, .rightMouseDown]) { [weak self] event in
                if let self, self.controller.isRecording, event.type != .keyDown {
                    if event.window !== self.window
                        || !self.bounds.contains(self.convert(event.locationInWindow, from: nil)) {
                        self.controller.cancelRecording()
                        self.refresh()
                    }
                    return event
                }
                guard let self, self.controller.isRecording,
                      event.window === self.window, self.window?.isKeyWindow == true,
                      self.window?.firstResponder === self else { return event }
                self.record(event)
                return nil
            }
        }
        refresh()
    }

    private func record(_ event: NSEvent) {
        guard !event.isARepeat else { return }
        let flags = event.modifierFlags.intersection([.command, .option, .control, .shift])
        if event.keyCode == 53, flags.isEmpty {
            controller.cancelRecording()
            window?.makeFirstResponder(nil)
            refresh()
            return
        }
        if event.keyCode == 48, flags.isEmpty || flags == .shift {
            controller.cancelRecording()
            if flags == .shift { window?.selectPreviousKeyView(self) }
            else { window?.selectNextKeyView(self) }
            refresh()
            return
        }
        var modifiers: UInt32 = 0
        if flags.contains(.command) { modifiers |= CaptureShortcut.command }
        if flags.contains(.option) { modifiers |= CaptureShortcut.option }
        if flags.contains(.control) { modifiers |= CaptureShortcut.control }
        if flags.contains(.shift) { modifiers |= CaptureShortcut.shift }
        let namedKeys: [UInt16: String] = [
            36: "↩", 48: "Tab", 49: "Space", 51: "⌫", 53: "Esc", 65: "Num .",
            67: "Num *", 69: "Num +", 75: "Num /", 76: "Num ↩", 78: "Num −",
            81: "Num =", 82: "Num 0", 83: "Num 1", 84: "Num 2", 85: "Num 3",
            86: "Num 4", 87: "Num 5", 88: "Num 6", 89: "Num 7", 91: "Num 8", 92: "Num 9",
            96: "F5", 97: "F6", 98: "F7", 99: "F3", 100: "F8", 101: "F9",
            103: "F11", 105: "F13", 106: "F16", 107: "F14", 109: "F10", 111: "F12",
            113: "F15", 114: "Help", 115: "Home", 116: "Page Up", 117: "⌦",
            118: "F4", 119: "End", 120: "F2", 121: "Page Down", 122: "F1",
            123: "←", 124: "→", 125: "↓", 126: "↑"
        ]
        let label = namedKeys[event.keyCode]
            ?? event.characters(byApplyingModifiers: [])?.uppercased() ?? ""
        _ = controller.apply(CaptureShortcut(keyCode: UInt32(event.keyCode),
                                             modifiers: modifiers, keyLabel: label))
        if !controller.isRecording { window?.makeFirstResponder(nil) }
        refresh()
    }

    func refresh() {
        if !controller.isRecording, let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
        setAccessibilityValue(controller.isRecording ? "请按新的组合键" : controller.shortcut.displayName)
        needsDisplay = true
    }

    override func draw(_ dirtyRect: NSRect) {
        let path = NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), xRadius: 8, yRadius: 8)
        NSColor(hex: 0x2D3B35).setFill()
        path.fill()
        let focused = window?.firstResponder === self
        NSColor(hex: focused ? 0xE8C87A : 0x3D5046).setStroke()
        path.lineWidth = focused ? 2 : 1
        path.stroke()
        let title = controller.isRecording ? "请按新的组合键…" : controller.shortcut.displayName
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 13, weight: .medium),
            .foregroundColor: NSColor(hex: 0xE8C87A)
        ]
        let size = (title as NSString).size(withAttributes: attributes)
        (title as NSString).draw(at: NSPoint(x: (bounds.width - size.width) / 2,
                                            y: (bounds.height - size.height) / 2),
                                withAttributes: attributes)
    }
}
