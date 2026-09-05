import Foundation
import Carbon.HIToolbox

/// A thin wrapper around Carbon's `RegisterEventHotKey`, which gives a truly
/// global hotkey WITHOUT requiring Accessibility / Input-Monitoring permission
/// (unlike `NSEvent.addGlobalMonitorForEvents`).
final class HotKey {
    private var hotKeyRef: EventHotKeyRef?
    private let id: UInt32

    // The C event handler can't capture context, so route callbacks through a
    // static registry keyed by hotkey id.
    private static var handlers: [UInt32: () -> Void] = [:]
    private static var nextID: UInt32 = 1
    private static var handlerInstalled = false

    /// - Parameters:
    ///   - keyCode: a Carbon virtual key code, e.g. `kVK_Space`.
    ///   - modifiers: Carbon modifier mask, e.g. `controlKey | optionKey`.
    init?(keyCode: UInt32, modifiers: UInt32, handler: @escaping () -> Void) {
        self.id = HotKey.nextID
        HotKey.nextID += 1
        guard !Self.isSystemReserved(keyCode: keyCode, modifiers: modifiers) else { return nil }
        guard HotKey.installHandlerIfNeeded() else { return nil }
        HotKey.handlers[id] = handler

        let hotKeyID = EventHotKeyID(signature: OSType(0x50_41_52_45), id: id) // 'PARE'
        let status = RegisterEventHotKey(keyCode,
                                         modifiers,
                                         hotKeyID,
                                         GetEventDispatcherTarget(),
                                         0,
                                         &hotKeyRef)
        if status != noErr {
            HotKey.handlers[id] = nil
            return nil
        }
    }

    deinit {
        if let ref = hotKeyRef { UnregisterEventHotKey(ref) }
        HotKey.handlers[id] = nil
    }

    private static func isSystemReserved(keyCode: UInt32, modifiers: UInt32) -> Bool {
        var hotKeys: Unmanaged<CFArray>?
        guard CopySymbolicHotKeys(&hotKeys) == noErr,
              let entries = hotKeys?.takeRetainedValue() as? [[String: Any]] else { return false }
        let mask = UInt32(cmdKey | shiftKey | optionKey | controlKey)
        return entries.contains { entry in
            (entry[kHISymbolicHotKeyEnabled as String] as? NSNumber)?.boolValue == true
                && (entry[kHISymbolicHotKeyCode as String] as? NSNumber)?.uint32Value == keyCode
                && ((entry[kHISymbolicHotKeyModifiers as String] as? NSNumber)?.uint32Value ?? 0) & mask == modifiers
        }
    }

    private static func installHandlerIfNeeded() -> Bool {
        guard !handlerInstalled else { return true }

        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard),
                                 eventKind: UInt32(kEventHotKeyPressed))
        let status = InstallEventHandler(GetEventDispatcherTarget(),
                            { (_, event, _) -> OSStatus in
                                guard let event = event else { return noErr }
                                var hkID = EventHotKeyID()
                                let err = GetEventParameter(event,
                                                            EventParamName(kEventParamDirectObject),
                                                            EventParamType(typeEventHotKeyID),
                                                            nil,
                                                            MemoryLayout<EventHotKeyID>.size,
                                                            nil,
                                                            &hkID)
                                if err == noErr, let handler = HotKey.handlers[hkID.id] {
                                    DispatchQueue.main.async { handler() }
                                }
                                return noErr
                            },
                            1,
                            &spec,
                            nil,
                            nil)
        handlerInstalled = status == noErr
        return handlerInstalled
    }
}
