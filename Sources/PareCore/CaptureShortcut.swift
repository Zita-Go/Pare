import Foundation
import Combine

/// Physical key plus Carbon modifier bits. Kept separate from task backups:
/// keyboard layouts and shortcut conflicts are specific to this Mac.
public struct CaptureShortcut: Codable, Equatable {
    public static let command: UInt32 = 1 << 8
    public static let shift: UInt32 = 1 << 9
    public static let option: UInt32 = 1 << 11
    public static let control: UInt32 = 1 << 12
    public static let supportedModifiers = command | shift | option | control
    public static let `default` = CaptureShortcut(keyCode: 49, modifiers: option, keyLabel: "Space")

    public let keyCode: UInt32
    public let modifiers: UInt32
    public let keyLabel: String

    public init(keyCode: UInt32, modifiers: UInt32, keyLabel: String) {
        self.keyCode = keyCode
        self.modifiers = modifiers
        self.keyLabel = keyLabel
    }

    public var validationError: String? {
        guard modifiers & (Self.command | Self.control | Self.option) != 0 else {
            return "请同时按住 ⌘、⌥ 或 ⌃ 中至少一个修饰键。"
        }
        guard modifiers & ~Self.supportedModifiers == 0, keyCode <= 126,
              ![54, 55, 56, 57, 58, 59, 60, 61, 62, 63].contains(keyCode),
              !keyLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return "请选择一个普通按键与修饰键的组合。"
        }
        return nil
    }

    public func usesSameKeys(as other: CaptureShortcut) -> Bool {
        keyCode == other.keyCode && modifiers == other.modifiers
    }

    public var displayName: String {
        var value = ""
        for (mask, symbol) in [(Self.control, "⌃"), (Self.option, "⌥"),
                               (Self.shift, "⇧"), (Self.command, "⌘")] {
            if modifiers & mask != 0 { value += symbol }
        }
        return value + keyLabel
    }
}

/// Owns registration lifetimes. Injection keeps replacement/rollback testable
/// without reserving real global keys in the unit test process.
public final class CaptureShortcutController: ObservableObject {
    public static let preferenceKey = "pareCaptureShortcut"
    @Published public private(set) var shortcut: CaptureShortcut
    @Published public private(set) var isRecording = false
    @Published public private(set) var isRegistered = false
    @Published public private(set) var message: String?

    private var registration: AnyObject?
    private let defaults: UserDefaults
    private let register: (CaptureShortcut) -> AnyObject?

    public init(defaults: UserDefaults = .standard,
                register: @escaping (CaptureShortcut) -> AnyObject?) {
        self.defaults = defaults
        self.register = register
        if let data = defaults.data(forKey: Self.preferenceKey),
           let saved = try? JSONDecoder().decode(CaptureShortcut.self, from: data),
           saved.validationError == nil {
            shortcut = saved
        } else {
            shortcut = .default
        }
    }

    public func start() {
        guard registration == nil, !isRecording else { return }
        registration = register(shortcut)
        isRegistered = registration != nil
        message = isRegistered ? nil : "快捷键未能注册，可能已被占用。请在这里换一个组合；仍可点击菜单栏图标。"
    }

    public func beginRecording() {
        guard !isRecording else { return }
        registration = nil // Let the recorder receive the current global combo too.
        isRegistered = false
        message = nil
        isRecording = true
    }

    public func cancelRecording() {
        guard isRecording else { return }
        isRecording = false
        start()
    }

    @discardableResult
    public func apply(_ candidate: CaptureShortcut) -> Bool {
        if let error = candidate.validationError {
            message = error
            return false
        }
        if registration != nil, candidate.usesSameKeys(as: shortcut) {
            message = "当前快捷键已生效。"
            return true
        }
        // For reset/change outside recording, keep the old registration alive
        // until the replacement has actually succeeded.
        guard let replacement = register(candidate) else {
            isRecording = false
            if registration == nil { registration = register(shortcut) }
            isRegistered = registration != nil
            message = isRegistered
                ? "这个组合未能注册，可能已被占用。已保留原快捷键 \(shortcut.displayName)。"
                : "新旧快捷键都未能注册，请换一个组合；仍可点击菜单栏图标。"
            return false
        }
        registration = replacement
        shortcut = candidate
        isRecording = false
        isRegistered = true
        if let data = try? JSONEncoder().encode(candidate) {
            defaults.set(data, forKey: Self.preferenceKey)
        }
        message = "已生效：\(candidate.displayName)"
        return true
    }
}
