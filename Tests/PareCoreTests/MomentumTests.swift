import XCTest
@testable import PareCore

final class MomentumTests: XCTestCase {
    private func calendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return calendar
    }

    private func date(_ hour: Int, _ minute: Int = 0) -> Date {
        calendar().date(from: DateComponents(year: 2026, month: 9, day: 4,
                                             hour: hour, minute: minute))!
    }

    private func makeStore() -> (Store, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-momentum-\(UUID().uuidString).json")
        return (Store(fileURL: url, calendar: calendar()), url)
    }

    func testDailyWinIsExclusiveJoinsTodayAndSortsFirst() {
        let (store, _) = makeStore()
        let first = store.addTask("先写提纲", to: .importantNotUrgent, addToToday: true)!
        let second = store.addTask("验证实验", to: .importantUrgent)!

        store.setDailyWin(first.id, now: date(9))
        store.setDailyWin(second.id, now: date(9, 1))

        XCTAssertEqual(store.dailyWinTask?.id, second.id)
        XCTAssertEqual(store.todayTasks.first?.id, second.id)
        XCTAssertTrue(store.tasks.first(where: { $0.id == second.id })?.isToday == true)
        XCTAssertFalse(store.tasks.first(where: { $0.id == first.id })?.isDailyWin == true)
    }

    func testFiveMinuteStartPlansCheckpointAndRecordsProgress() {
        let (store, _) = makeStore()
        let task = store.addTask("推进论文", to: .importantNotUrgent)!
        let start = date(10)

        XCTAssertTrue(store.startTimer(task.id, now: start, targetMinutes: 5))
        let checkpoint = store.plannedReminders(now: start).first {
            if case .timerCheckpoint(let id) = $0.kind { return id == task.id }
            return false
        }
        XCTAssertEqual(checkpoint?.identifier, "timer.checkpoint")
        XCTAssertEqual(checkpoint?.fireDate, start.addingTimeInterval(5 * 60))

        XCTAssertEqual(store.stopTimer(now: start.addingTimeInterval(125)), 3)
        let summary = store.todaySummary(now: date(20))
        XCTAssertEqual(summary.progressSessionCount, 1)
        XCTAssertEqual(summary.progressedTaskCount, 1)
        XCTAssertEqual(summary.focusMinutes, 3)
        XCTAssertEqual(store.tasks.first?.progressSessions?.first?.targetMinutes, 5)
    }

    func testContinueClearsCheckpointWithoutLosingSession() {
        let (store, _) = makeStore()
        let task = store.addTask("继续推进", to: .importantUrgent)!
        let start = date(11)
        XCTAssertTrue(store.startTimer(task.id, now: start, targetMinutes: 5))

        store.continueTimerWithoutTarget()

        XCTAssertNil(store.activeTimer?.targetMinutes)
        XCTAssertFalse(store.plannedReminders(now: start).contains {
            if case .timerCheckpoint = $0.kind { return true }
            return false
        })
        XCTAssertEqual(store.stopTimer(now: start.addingTimeInterval(6 * 60)), 6)
    }

    func testBlockerAndImplementationPlanPersistAndFeedSummary() {
        let (store, url) = makeStore()
        let task = store.addTask("整理结果", to: .importantUrgent)!
        let plan = ImplementationPlan(kind: .time, action: "先打开结果表",
                                      hour: 14, minute: 30)
        store.updateTaskDetails(task.id, text: task.text, note: nil,
                                nextAction: "列出三个异常样本", dueAt: nil,
                                remindAt: nil, recurrence: nil,
                                definitionOfDone: "写下初步结论",
                                implementationPlan: plan, isDailyWin: true,
                                now: date(9))
        store.recordBlocker(task.id, reason: .tooLarge, now: date(9, 5))
        store.recordBlocker(task.id, reason: .tooLarge, now: date(9, 6))

        let reminder = store.plannedReminders(now: date(10)).first {
            if case .implementation(let id) = $0.kind { return id == task.id }
            return false
        }
        XCTAssertEqual(reminder?.fireDate, date(14, 30))
        XCTAssertEqual(plan.summary, "如果到了 14:30，那么先打开结果表")
        XCTAssertEqual(store.todaySummary(now: date(20)).blockerCounts[.tooLarge], 2)

        let reloaded = Store(fileURL: url, calendar: calendar())
        let saved = reloaded.tasks.first { $0.id == task.id }
        XCTAssertEqual(saved?.definitionOfDone, "写下初步结论")
        XCTAssertEqual(saved?.implementationPlan, plan)
        XCTAssertTrue(saved?.isDailyWin == true)
        XCTAssertEqual(saved?.blockerEntries?.count, 2)
    }
}
