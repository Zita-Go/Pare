import XCTest
@testable import PareCore

final class ParallelRunTests: XCTestCase {
    private func makeStore() -> (Store, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-parallel-\(UUID()).json")
        return (Store(fileURL: url), url)
    }

    func testSeveralTasksCanRunWhileHumanFocusRemainsSeparate() {
        let (store, _) = makeStore()
        let focus = store.addTask("我来写", to: .importantUrgent)!
        let first = store.addTask("远端实验", to: .importantNotUrgent)!
        let second = store.addTask("生成插图", to: .importantNotUrgent)!
        let start = Date(timeIntervalSince1970: 1_800_000_000)

        XCTAssertTrue(store.startTimer(focus.id, now: start))
        XCTAssertTrue(store.startParallelRun(first.id, now: start))
        XCTAssertTrue(store.startParallelRun(second.id, now: start.addingTimeInterval(60)))
        XCTAssertEqual(store.activeTimerTask?.id, focus.id)
        XCTAssertEqual(Set(store.parallelRuns.map(\.taskID)), [first.id, second.id])
        XCTAssertTrue(store.tasks.first(where: { $0.id == first.id })?.isToday == true)
        XCTAssertFalse(store.startParallelRun(focus.id, now: start))
        XCTAssertFalse(store.startTimer(first.id, now: start))
    }

    func testDailyWinCanKeepProgressingElsewhereWithoutChangingItsMeaning() {
        let (store, _) = makeStore()
        let task = store.addTask("今日最重要结果", to: .importantUrgent, addToToday: true)!
        store.setDailyWin(task.id)
        XCTAssertTrue(store.startParallelRun(task.id))
        XCTAssertEqual(store.dailyWinTask?.id, task.id)
        XCTAssertEqual(store.parallelRunningTasks.map(\.id), [task.id])
    }

    func testPauseRecordsWallClockSessionWithoutInflatingFocusMinutes() {
        let (store, _) = makeStore()
        let task = store.addTask("运行测试", to: .importantNotUrgent)!
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        store.startParallelRun(task.id, now: start)

        XCTAssertEqual(store.pauseParallelRun(task.id, now: start.addingTimeInterval(125)), 3)
        XCTAssertTrue(store.parallelRuns.isEmpty)
        XCTAssertNil(store.tasks[0].actualMinutes)
        XCTAssertEqual(store.tasks[0].parallelRunSessions?.first?.elapsedMinutes, 3)
        XCTAssertEqual(store.tasks[0].parallelRunSessions?.first?.reason, .paused)
        XCTAssertEqual(store.parallelRecordedMinutes(task.id), 3)
    }

    func testCompletingRunningTaskStopsRunAndPersists() {
        let (store, url) = makeStore()
        let task = store.addTask("等待生成", to: .importantNotUrgent)!
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        store.startParallelRun(task.id, now: start)
        XCTAssertEqual(store.completeParallelRun(task.id, now: start.addingTimeInterval(600)), 10)

        let loaded = Store(fileURL: url)
        XCTAssertTrue(loaded.parallelRuns.isEmpty)
        XCTAssertTrue(loaded.tasks[0].completed)
        XCTAssertNil(loaded.tasks[0].actualMinutes)
        XCTAssertEqual(loaded.tasks[0].parallelRunSessions?.first?.reason, .completed)
    }

    func testParallelRunsPersistAndInvalidReferencesAreDiscarded() {
        let (store, url) = makeStore()
        let first = store.addTask("A", to: .importantNotUrgent)!
        let second = store.addTask("B", to: .importantNotUrgent)!
        store.startParallelRun(first.id)
        store.startParallelRun(second.id)
        XCTAssertEqual(Store(fileURL: url).parallelRuns.count, 2)
        let json = try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        XCTAssertEqual(json["schemaVersion"] as? Int, 5)
        XCTAssertEqual((json["parallelRuns"] as? [[String: Any]])?.count, 2)

        store.deleteTask(first.id)
        store.setCompleted(second.id, true)
        let loaded = Store(fileURL: url)
        XCTAssertTrue(loaded.parallelRuns.isEmpty)
        XCTAssertEqual(loaded.tasks[0].parallelRunSessions?.last?.reason, .completed)
    }

    func testOptionalCheckReminderUsesParallelCategoryData() {
        let (store, _) = makeStore()
        let task = store.addTask("检查远端结果", to: .importantNotUrgent)!
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let check = now.addingTimeInterval(1800)
        store.startParallelRun(task.id, now: now, nextCheckAt: check)
        let reminder = store.plannedReminders(now: now).first {
            if case .parallelCheck(let id) = $0.kind { return id == task.id }
            return false
        }
        XCTAssertEqual(reminder?.fireDate, check)
        XCTAssertEqual(reminder?.taskID, task.id)
        XCTAssertTrue(reminder?.identifier.hasPrefix("parallel.check.") == true)

        store.setParallelCheck(task.id, at: nil, now: now)
        XCTAssertFalse(store.plannedReminders(now: now).contains {
            if case .parallelCheck = $0.kind { return true }; return false
        })
    }

    func testRolloverKeepsRunningTaskDiscoverableWithoutPuttingItInFocusStats() {
        let (store, _) = makeStore()
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date(timeIntervalSince1970: 1_800_000_000))
        let tomorrow = cal.date(byAdding: .day, value: 1, to: today)!
        store.rolloverIfNeeded(now: today)
        let task = store.addTask("跨日运行", to: .importantNotUrgent, addToToday: true)!
        store.startParallelRun(task.id, now: today.addingTimeInterval(3600))

        XCTAssertTrue(store.rolloverIfNeeded(now: tomorrow))
        XCTAssertFalse(store.tasks[0].isToday)
        XCTAssertEqual(store.parallelRunningTasks.map(\.id), [task.id])
        XCTAssertNil(store.tasks[0].actualMinutes)
    }
}
