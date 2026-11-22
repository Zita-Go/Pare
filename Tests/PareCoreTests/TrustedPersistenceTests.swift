import XCTest
@testable import PareCore

final class TrustedPersistenceTests: XCTestCase {
    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pare-trust-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private func backup(in directory: URL) throws -> URL {
        let url = directory.appendingPathComponent("import.json")
        try Data(#"{"schemaVersion":4,"inbox":[],"tasks":[],"aiBackground":"incoming"}"#.utf8).write(to: url)
        return url
    }

    // A newer schema may have changed required fields, so the version guard
    // must run before decoding those fields or considering an older backup.
    func testFutureSchemaDoesNotRestoreOlderBackupOrOverwritePrimary() throws {
        for payload in [#"{"schemaVersion":99,"inbox":[],"tasks":[]}"#,
                        #"{"schemaVersion":99,"newStorage":{"entries":[]}}"#] {
            let dir = try directory()
            let url = dir.appendingPathComponent("store.json")
            let old = Store(fileURL: url)
            XCTAssertTrue(old.capture("older data"))
            let previous = try Data(contentsOf: url)
            try previous.write(to: url.appendingPathExtension("bak"))
            let future = Data(payload.utf8)
            try future.write(to: url)

            let loaded = Store(fileURL: url)
            XCTAssertTrue(loaded.inbox.isEmpty)
            XCTAssertNotNil(loaded.persistenceIssue)
            XCTAssertFalse(loaded.capture("must not overwrite"))
            XCTAssertThrowsError(try loaded.importData(from: backup(in: dir)))
            XCTAssertFalse(loaded.restoreAutomaticBackup())
            XCTAssertThrowsError(try loaded.exportData(to: url))
            XCTAssertEqual(try Data(contentsOf: url), future)
            XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("bak")), previous)
        }
    }

    func testFutureSchemaRolloverAndSaveRejectionDoNotPreventCleanTermination() throws {
        let url = try directory().appendingPathComponent("store.json")
        let future = Data(#"{"schemaVersion":99,"newStorage":{"entries":[]}}"#.utf8)
        try future.write(to: url)
        let store = Store(fileURL: url)
        let now = Date(timeIntervalSince1970: 1_800_000_000)

        XCTAssertTrue(store.isPersistenceReadOnly)
        XCTAssertFalse(store.rolloverIfNeeded(now: now))
        XCTAssertFalse(store.rolloverIfNeeded(now: now.addingTimeInterval(86_400)))
        XCTAssertNil(store.lastActiveDay)
        XCTAssertTrue(store.prepareForTermination(now: now))
        XCTAssertFalse(store.retrySaving())
        XCTAssertTrue(store.prepareForTermination(now: now))
        XCTAssertEqual(try Data(contentsOf: url), future)
    }

    func testImportBackupWriteFailureLeavesMemoryAndPrimaryUnchanged() throws {
        let dir = try directory()
        let url = dir.appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        XCTAssertTrue(store.capture("existing"))
        let previous = try Data(contentsOf: url)
        // A directory at the backup path makes atomic backup writes fail on
        // all users, unlike chmod, which privileged test runners can bypass.
        try FileManager.default.createDirectory(at: url.appendingPathExtension("bak"), withIntermediateDirectories: false)

        XCTAssertThrowsError(try store.importData(from: backup(in: dir)))
        XCTAssertEqual(store.inbox.map(\.text), ["existing"])
        XCTAssertEqual(store.aiBackground, "")
        XCTAssertEqual(try Data(contentsOf: url), previous)
        XCTAssertNotNil(store.persistenceIssue)
    }

    func testImportPrimaryWriteFailureDoesNotApplyNewState() throws {
        let dir = try directory()
        let url = dir.appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        XCTAssertTrue(store.capture("existing"))
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)

        XCTAssertThrowsError(try store.importData(from: backup(in: dir)))
        XCTAssertEqual(store.inbox.map(\.text), ["existing"])
        XCTAssertEqual(store.aiBackground, "")
        XCTAssertNotNil(store.persistenceIssue)
        let saved = Store(fileURL: url.appendingPathExtension("bak"))
        XCTAssertEqual(saved.inbox.map(\.text), ["existing"])
    }

    func testRestoreReportsWriteFailureWithoutChangingMemory() throws {
        let dir = try directory()
        let url = dir.appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        XCTAssertTrue(store.capture("existing"))
        let incoming = try Data(contentsOf: backup(in: dir))
        try incoming.write(to: url.appendingPathExtension("bak"))
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)

        XCTAssertFalse(store.restoreAutomaticBackup())
        XCTAssertEqual(store.inbox.map(\.text), ["existing"])
        XCTAssertEqual(store.aiBackground, "")
        XCTAssertNotNil(store.persistenceIssue)
        XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("bak")), incoming)
        try FileManager.default.removeItem(at: url)
        XCTAssertTrue(store.restoreAutomaticBackup())
        XCTAssertEqual(store.aiBackground, "incoming")
        XCTAssertTrue(store.inbox.isEmpty)
    }

    func testReminderCaptureIsImmediatelyPlannedAndDurable() throws {
        let url = try directory().appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        let before = Date()
        XCTAssertTrue(store.capture("30分钟后提醒我喝水"))
        let after = Date()
        let task = try XCTUnwrap(store.tasks.first)
        XCTAssertEqual(task.text, "喝水")
        XCTAssertEqual(task.quadrant, .importantNotUrgent)
        XCTAssertFalse(task.isToday)
        XCTAssertTrue(store.inbox.isEmpty)
        let reminder = try XCTUnwrap(store.plannedReminders(now: after).first { $0.taskID == task.id })
        XCTAssertGreaterThanOrEqual(reminder.fireDate, before.addingTimeInterval(1800))
        XCTAssertLessThanOrEqual(reminder.fireDate, after.addingTimeInterval(1800))
        XCTAssertTrue(reminder.isTaskActionable)

        let reloaded = Store(fileURL: url)
        XCTAssertEqual(reloaded.tasks.first?.id, task.id)
        XCTAssertEqual(reloaded.plannedReminders(now: after).filter { $0.taskID == task.id }.count, 1)
    }

    func testOrdinaryCaptureStaysInInbox() throws {
        let store = Store(fileURL: try directory().appendingPathComponent("store.json"))
        XCTAssertTrue(store.capture("  整理书架  "))
        XCTAssertEqual(store.inbox.map(\.text), ["整理书架"])
        XCTAssertTrue(store.tasks.isEmpty)
        XCTAssertTrue(store.plannedReminders().allSatisfy { $0.taskID == nil })
    }

    func testFailedCaptureKeepsExistingStateAndDoesNotPlanReminder() throws {
        let dir = try directory()
        let url = dir.appendingPathComponent("missing/store.json")
        let store = Store(fileURL: url)
        XCTAssertFalse(store.capture("30分钟后提醒我喝水"))
        XCTAssertTrue(store.tasks.isEmpty)
        XCTAssertTrue(store.inbox.isEmpty)
        XCTAssertTrue(store.plannedReminders().allSatisfy { $0.taskID == nil })
        XCTAssertNotNil(store.persistenceIssue)
    }

    func testOrdinaryEditReportsWriteFailureAndCanRetryDurably() throws {
        let dir = try directory()
        let url = dir.appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        XCTAssertTrue(store.capture("existing"))
        let backup = url.appendingPathExtension("bak")
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)
        let task = try XCTUnwrap(store.addTask("pending edit", to: .importantUrgent))
        XCTAssertNotNil(store.persistenceIssue)
        XCTAssertFalse(store.retrySaving())
        XCTAssertTrue(Store(fileURL: url).tasks.isEmpty)

        try FileManager.default.removeItem(at: backup)
        XCTAssertTrue(store.retrySaving())
        XCTAssertNil(store.persistenceIssue)
        XCTAssertEqual(Store(fileURL: url).tasks.first?.id, task.id)
    }
}
