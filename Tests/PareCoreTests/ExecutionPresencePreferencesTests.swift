import XCTest
@testable import PareCore

final class ExecutionPresencePreferencesTests: XCTestCase {
    private let account = UUID(uuidString: "00000000-0000-4000-8000-000000000201")!
    private let device = UUID(uuidString: "00000000-0000-4000-8000-000000000202")!
    private var domain: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        domain = "com.pare.fictional.presence-consent.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: domain)!
        defaults.removePersistentDomain(forName: domain)
    }
    override func tearDown() {
        defaults.removePersistentDomain(forName: domain)
        defaults = nil; domain = nil
        super.tearDown()
    }

    func testConsentDefaultsOffAndIsScopedToAccountAndInstallation() throws {
        let prefs = ExecutionPresencePreferences(defaults: defaults)
        XCTAssertFalse(prefs.isEnabled(accountID: account, deviceID: device))
        try prefs.setEnabled(true, accountID: account, deviceID: device)
        XCTAssertTrue(prefs.isEnabled(accountID: account, deviceID: device))
        XCTAssertFalse(prefs.isEnabled(accountID: UUID(), deviceID: device))
        XCTAssertFalse(prefs.isEnabled(accountID: account, deviceID: UUID()))
        let relaunched = ExecutionPresencePreferences(defaults: UserDefaults(suiteName: domain)!)
        XCTAssertTrue(relaunched.isEnabled(accountID: account, deviceID: device))
        try relaunched.setEnabled(false, accountID: account, deviceID: device)
        XCTAssertFalse(prefs.isEnabled(accountID: account, deviceID: device))
    }

    func testMalformedOrNumericDefaultsDoNotCountAsConsent() {
        let key = "pare.presence.sharing.v1.\(account.uuidString.lowercased()).\(device.uuidString.lowercased())"
        let prefs = ExecutionPresencePreferences(defaults: defaults)
        let malformedValues: [Any] = ["true", "YES", 1, 2, ["enabled": true]]
        for malformed in malformedValues {
            defaults.set(malformed, forKey: key)
            XCTAssertFalse(prefs.isEnabled(accountID: account, deviceID: device))
        }
    }

    func testDisableReservationPrecedesRapidReenableAndCannotReplaceNewRunningReport() throws {
        let prefs = ExecutionPresencePreferences(defaults: defaults)
        let sequences = ExecutionPresenceSequenceStore(defaults: defaults)
        try prefs.setEnabled(true, accountID: account, deviceID: device)
        let originalRun = try sequences.next(accountID: account, deviceID: device)
        try prefs.setEnabled(false, accountID: account, deviceID: device)
        // The coordinator reserves this synchronously before dispatching its best-effort clear.
        let clear = try sequences.next(accountID: account, deviceID: device)
        try prefs.setEnabled(true, accountID: account, deviceID: device)
        let newRun = try sequences.next(accountID: account, deviceID: device)
        XCTAssertLessThan(originalRun, clear)
        XCTAssertLessThan(clear, newRun)
        try sequences.advance(accountID: account, deviceID: device, to: clear)
        XCTAssertEqual(try sequences.next(accountID: account, deviceID: device), newRun + 1)
    }

    func testConsentWritesNeverChangeSequenceOrContentDefaults() throws {
        defaults.set("fictional-content-setting", forKey: "unrelated-content-sync")
        let sequences = ExecutionPresenceSequenceStore(defaults: defaults)
        let first = try sequences.next(accountID: account, deviceID: device)
        let prefs = ExecutionPresencePreferences(defaults: defaults)
        try prefs.setEnabled(true, accountID: account, deviceID: device)
        try prefs.setEnabled(false, accountID: account, deviceID: device)
        XCTAssertEqual(defaults.string(forKey: "unrelated-content-sync"), "fictional-content-setting")
        XCTAssertEqual(try sequences.next(accountID: account, deviceID: device), first + 1)
    }

    func testFailedConsentPersistenceFailsClosed() {
        final class FailingDefaults: UserDefaults {
            override func synchronize() -> Bool { false }
        }
        let failed = FailingDefaults(suiteName: domain)!
        let prefs = ExecutionPresencePreferences(defaults: failed)
        XCTAssertThrowsError(try prefs.setEnabled(true, accountID: account, deviceID: device))
        XCTAssertFalse(prefs.isEnabled(accountID: account, deviceID: device))
        XCTAssertThrowsError(try prefs.setEnabled(false, accountID: account, deviceID: device))
        XCTAssertFalse(prefs.isEnabled(accountID: account, deviceID: device))
    }
}
