import XCTest
@testable import PareCore

final class FocusStartGuardTests: XCTestCase {
    private func store() -> Store {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pare-start-guard-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return Store(fileURL: directory.appendingPathComponent("store.json"))
    }

    func testDecliningPeerConfirmationPreservesCurrentTimerAndTime() throws {
        let store = store()
        let first = try XCTUnwrap(store.addTask("fictional current task", to: .importantUrgent))
        let second = try XCTUnwrap(store.addTask("fictional next task", to: .importantNotUrgent))
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(store.startTimer(first.id, now: now))
        var asked: [UUID] = []
        store.confirmFocusStart = { asked.append($0); return false }
        XCTAssertFalse(store.startTimer(second.id, now: now.addingTimeInterval(90)))
        XCTAssertEqual(asked, [second.id])
        XCTAssertEqual(store.activeTimer?.taskID, first.id)
        XCTAssertEqual(store.activeTimer?.startedAt, now)
        XCTAssertNil(store.tasks.first { $0.id == first.id }?.actualMinutes)
        XCTAssertTrue(store.startTimer(first.id))
        XCTAssertEqual(asked.count, 1)
    }

    func testTaskRemovedDuringModalConfirmationCannotStartOrStopCurrentTimer() throws {
        let store = store()
        let first = try XCTUnwrap(store.addTask("fictional current task", to: .importantUrgent))
        let second = try XCTUnwrap(store.addTask("fictional removed task", to: .importantNotUrgent))
        XCTAssertTrue(store.startTimer(first.id))
        store.confirmFocusStart = { id in store.deleteTask(id); return true }
        XCTAssertFalse(store.startTimer(second.id))
        XCTAssertEqual(store.activeTimer?.taskID, first.id)
        XCTAssertFalse(store.tasks.contains { $0.id == second.id })
    }

    func testExplicitContinueStartsOnlyLocalFocus() throws {
        let store = store()
        let task = try XCTUnwrap(store.addTask("fictional local task", to: .importantNotUrgent))
        store.confirmFocusStart = { _ in true }
        XCTAssertTrue(store.startTimer(task.id, targetMinutes: 5))
        XCTAssertEqual(store.activeTimer?.taskID, task.id)
        XCTAssertEqual(store.activeTimer?.targetMinutes, 5)
        XCTAssertTrue(store.parallelRuns.isEmpty)
    }
}
