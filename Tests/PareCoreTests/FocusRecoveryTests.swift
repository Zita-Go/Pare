import XCTest
@testable import PareCore

final class FocusRecoveryTests: XCTestCase {
    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pare-focus-recovery-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    func testExplicitLaunchRecoveryPausesFocusWithoutCountingDowntime() throws {
        let url = try directory().appendingPathComponent("store.json")
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let store = Store(fileURL: url)
        let focus = try XCTUnwrap(store.addTask("personal focus", to: .importantUrgent))
        let parallel = try XCTUnwrap(store.addTask("remote run", to: .importantNotUrgent))
        XCTAssertTrue(store.startTimer(focus.id, now: start, targetMinutes: 5))
        XCTAssertTrue(store.startParallelRun(parallel.id, now: start))

        let loaded = Store(fileURL: url)
        XCTAssertEqual(loaded.activeTimer?.taskID, focus.id)
        XCTAssertNotNil(loaded.recoverInterruptedFocus())
        XCTAssertNil(loaded.activeTimer)
        XCTAssertNil(loaded.tasks.first { $0.id == focus.id }?.actualMinutes)
        XCTAssertTrue(loaded.tasks.first { $0.id == focus.id }?.progressSessions?.isEmpty ?? true)
        XCTAssertEqual(loaded.parallelRuns.map(\.taskID), [parallel.id])
        XCTAssertNil(loaded.recoverInterruptedFocus())
        let reloaded = Store(fileURL: url)
        XCTAssertNil(reloaded.activeTimer)
        XCTAssertEqual(reloaded.parallelRuns.map(\.taskID), [parallel.id])
        XCTAssertNil(reloaded.stopTimer(now: start.addingTimeInterval(18 * 3600)))
        XCTAssertNil(reloaded.tasks.first { $0.id == focus.id }?.actualMinutes)
    }

    func testTerminationCountsOnlyCurrentSessionAndPreservesParallelRuns() throws {
        let url = try directory().appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let task = try XCTUnwrap(store.addTask("focus", to: .importantUrgent))
        let parallel = try XCTUnwrap(store.addTask("parallel", to: .importantNotUrgent))
        XCTAssertTrue(store.startTimer(task.id, now: start))
        XCTAssertTrue(store.startParallelRun(parallel.id, now: start))

        XCTAssertTrue(store.prepareForTermination(now: start.addingTimeInterval(125)))
        XCTAssertTrue(store.prepareForTermination(now: start.addingTimeInterval(18 * 3600)))
        let loaded = Store(fileURL: url)
        XCTAssertNil(loaded.activeTimer)
        XCTAssertEqual(loaded.tasks.first { $0.id == task.id }?.actualMinutes, 3)
        XCTAssertEqual(loaded.tasks.first { $0.id == task.id }?.progressSessions?.count, 1)
        XCTAssertEqual(loaded.parallelRuns.map(\.taskID), [parallel.id])
    }

    func testFailedTerminationCanRetryWithoutCountingSessionTwice() throws {
        let url = try directory().appendingPathComponent("store.json")
        let store = Store(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let task = try XCTUnwrap(store.addTask("focus", to: .importantUrgent))
        XCTAssertTrue(store.startTimer(task.id, now: start))
        let backup = url.appendingPathExtension("bak")
        try FileManager.default.removeItem(at: backup)
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)

        XCTAssertFalse(store.prepareForTermination(now: start.addingTimeInterval(125)))
        XCTAssertNotNil(store.persistenceIssue)
        XCTAssertNil(store.activeTimer)
        XCTAssertEqual(store.tasks.first?.actualMinutes, 3)
        try FileManager.default.removeItem(at: backup)
        XCTAssertTrue(store.prepareForTermination(now: start.addingTimeInterval(18 * 3600)))
        XCTAssertNil(store.persistenceIssue)
        let loaded = Store(fileURL: url)
        XCTAssertNil(loaded.activeTimer)
        XCTAssertEqual(loaded.tasks.first?.actualMinutes, 3)
        XCTAssertEqual(loaded.tasks.first?.progressSessions?.count, 1)
    }

    func testImportDoesNotResumeHistoricalFocusTimer() throws {
        let dir = try directory()
        let source = dir.appendingPathComponent("source.json")
        let store = Store(fileURL: source)
        let task = try XCTUnwrap(store.addTask("imported focus", to: .importantUrgent))
        XCTAssertTrue(store.startTimer(task.id, now: Date(timeIntervalSince1970: 1_800_000_000)))
        let destination = dir.appendingPathComponent("destination.json")
        let imported = Store(fileURL: destination)
        try imported.importData(from: source)

        XCTAssertNil(imported.activeTimer)
        XCTAssertNil(imported.tasks.first?.actualMinutes)
        XCTAssertNil(Store(fileURL: destination).activeTimer)
    }
}
