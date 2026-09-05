import XCTest
@testable import PareCore

final class CaptureShortcutTests: XCTestCase {
    private final class Registration {
        let release: () -> Void
        init(_ release: @escaping () -> Void) { self.release = release }
        deinit { release() }
    }

    private final class Registry {
        var live = Set<String>()
        var blocked = Set<String>()
        var attempts = 0

        func key(_ shortcut: CaptureShortcut) -> String {
            "\(shortcut.keyCode):\(shortcut.modifiers)"
        }

        func register(_ shortcut: CaptureShortcut) -> AnyObject? {
            attempts += 1
            let key = key(shortcut)
            guard !blocked.contains(key), live.insert(key).inserted else { return nil }
            return Registration { [weak self] in self?.live.remove(key) }
        }
    }

    private func preferences() -> UserDefaults {
        let name = "pare-shortcut-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        return defaults
    }

    private let custom = CaptureShortcut(keyCode: 35,
                                         modifiers: CaptureShortcut.command | CaptureShortcut.shift,
                                         keyLabel: "P")

    func testLegacyAndMalformedPreferencesUseDefault() {
        let defaults = preferences()
        XCTAssertEqual(CaptureShortcutController(defaults: defaults, register: { _ in nil }).shortcut, .default)
        defaults.set(Data("invalid".utf8), forKey: CaptureShortcutController.preferenceKey)
        XCTAssertEqual(CaptureShortcutController(defaults: defaults, register: { _ in nil }).shortcut, .default)
    }

    func testApplyPersistsAndReleasesOldRegistration() {
        let defaults = preferences()
        let registry = Registry()
        let controller = CaptureShortcutController(defaults: defaults, register: registry.register)
        controller.start()
        XCTAssertTrue(controller.apply(custom))
        XCTAssertEqual(registry.live, [registry.key(custom)])
        XCTAssertEqual(controller.shortcut.displayName, "⇧⌘P")
        let reloaded = CaptureShortcutController(defaults: defaults, register: { _ in nil })
        XCTAssertEqual(reloaded.shortcut, custom)
    }

    func testConflictDuringRecordingRestoresOriginalAndDoesNotSaveCandidate() {
        let defaults = preferences()
        let registry = Registry()
        registry.blocked.insert(registry.key(custom))
        let controller = CaptureShortcutController(defaults: defaults, register: registry.register)
        controller.start()
        controller.beginRecording()
        XCTAssertTrue(registry.live.isEmpty)
        XCTAssertFalse(controller.apply(custom))
        XCTAssertFalse(controller.isRecording)
        XCTAssertTrue(controller.isRegistered)
        XCTAssertEqual(registry.live, [registry.key(.default)])
        XCTAssertEqual(controller.shortcut, .default)
        XCTAssertNil(defaults.data(forKey: CaptureShortcutController.preferenceKey))
    }

    func testCancelAndRepeatedCancelKeepOriginalRegistered() {
        let registry = Registry()
        let controller = CaptureShortcutController(defaults: preferences(), register: registry.register)
        controller.start()
        controller.beginRecording()
        controller.cancelRecording()
        let attempts = registry.attempts
        controller.cancelRecording()
        XCTAssertEqual(registry.attempts, attempts)
        XCTAssertTrue(controller.isRegistered)
        XCTAssertEqual(registry.live, [registry.key(.default)])
    }

    func testTypingWithoutModifierDoesNotBecomeGlobalShortcut() {
        let registry = Registry()
        let controller = CaptureShortcutController(defaults: preferences(), register: registry.register)
        controller.start()
        controller.beginRecording()
        XCTAssertFalse(controller.apply(CaptureShortcut(keyCode: 0, modifiers: 0, keyLabel: "A")))
        XCTAssertTrue(controller.isRecording)
        XCTAssertNotNil(controller.message)
        controller.cancelRecording()
        XCTAssertTrue(controller.isRegistered)
        XCTAssertNotNil(CaptureShortcut(keyCode: 0, modifiers: CaptureShortcut.shift, keyLabel: "A").validationError)
    }

    func testRerecordCurrentCombinationAndRestoreDefault() {
        let registry = Registry()
        let controller = CaptureShortcutController(defaults: preferences(), register: registry.register)
        controller.start()
        controller.beginRecording()
        XCTAssertTrue(controller.apply(.default))
        XCTAssertTrue(controller.apply(custom))
        XCTAssertTrue(controller.apply(.default))
        XCTAssertEqual(registry.live, [registry.key(.default)])
        let attempts = registry.attempts
        XCTAssertTrue(controller.apply(.default))
        XCTAssertEqual(registry.attempts, attempts, "Same keys must not be registered twice")
    }

    func testResetConflictKeepsCustomShortcutAlive() {
        let registry = Registry()
        let controller = CaptureShortcutController(defaults: preferences(), register: registry.register)
        controller.start()
        XCTAssertTrue(controller.apply(custom))
        registry.blocked.insert(registry.key(.default))
        XCTAssertFalse(controller.apply(.default))
        XCTAssertTrue(controller.isRegistered)
        XCTAssertEqual(controller.shortcut, custom)
        XCTAssertEqual(registry.live, [registry.key(custom)])
    }

    func testStartupConflictAndFailedRestoreAreVisible() {
        let registry = Registry()
        registry.blocked.insert(registry.key(.default))
        let controller = CaptureShortcutController(defaults: preferences(), register: registry.register)
        controller.start()
        XCTAssertFalse(controller.isRegistered)
        XCTAssertNotNil(controller.message)
        XCTAssertTrue(controller.apply(custom))
        controller.beginRecording()
        registry.blocked.insert(registry.key(custom))
        controller.cancelRecording()
        XCTAssertFalse(controller.isRegistered)
        XCTAssertNotNil(controller.message)
    }
}
