import XCTest
@testable import PareCore

final class TimeBlockTests: XCTestCase {
    private func calendar() -> Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return value
    }

    private func date(_ hour: Int, _ minute: Int = 0, day: Int = 7) -> Date {
        calendar().date(from: DateComponents(year: 2026, month: 9, day: day,
                                             hour: hour, minute: minute))!
    }

    private func makeStore() -> (Store, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-time-block-\(UUID()).json")
        return (Store(fileURL: url, calendar: calendar()), url)
    }

    func testSchedulingAdoptsEstimateAndPersistsSchemaFour() throws {
        let (store, url) = makeStore()
        let task = store.addTask("写报告", to: .importantNotUrgent, estimatedMinutes: 45)!
        store.scheduleTask(task.id, at: date(14))

        let scheduled = try XCTUnwrap(store.tasks.first)
        XCTAssertTrue(scheduled.isToday)
        XCTAssertEqual(scheduled.scheduledAt, date(14))
        XCTAssertEqual(scheduled.scheduledMinutes, 45)
        XCTAssertEqual(scheduled.scheduleReminderLeadMinutes, 5)

        let loaded = Store(fileURL: url, calendar: calendar())
        XCTAssertEqual(loaded.tasks.first?.scheduledAt, date(14))
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        XCTAssertEqual(json["schemaVersion"] as? Int, 5)
    }

    func testDefaultDurationResizeAndEstimateStayInSync() {
        let (store, _) = makeStore()
        let task = store.addTask("整理材料", to: .importantUrgent)!
        store.scheduleTask(task.id, at: date(9))
        XCTAssertEqual(store.tasks.first?.scheduledMinutes, 30)
        XCTAssertEqual(store.tasks.first?.estimatedMinutes, 30)

        store.setScheduleDuration(task.id, minutes: 65)
        XCTAssertEqual(store.tasks.first?.scheduledMinutes, 65)
        XCTAssertEqual(store.tasks.first?.estimatedMinutes, 65)

        store.setEstimate(task.id, minutes: 20)
        XCTAssertEqual(store.tasks.first?.scheduledMinutes, 20)

        store.setEstimate(task.id, minutes: nil)
        XCTAssertEqual(store.tasks.first?.scheduledMinutes, 30)
        XCTAssertEqual(store.tasks.first?.estimatedMinutes, 30)
    }

    func testScheduleReminderIsIndependentAndFallsBackToStart() {
        let (store, _) = makeStore()
        let task = store.addTask("准备会议", to: .importantUrgent)!
        store.scheduleTask(task.id, at: date(10), reminderLeadMinutes: 5)

        var reminder = store.plannedReminders(now: date(9, 0)).first {
            if case .schedule(let id) = $0.kind { return id == task.id }; return false
        }
        XCTAssertEqual(reminder?.fireDate, date(9, 55))
        XCTAssertEqual(reminder?.identifier, "schedule.\(task.id.uuidString)")

        reminder = store.plannedReminders(now: date(9, 58)).first {
            if case .schedule = $0.kind { return true }; return false
        }
        XCTAssertEqual(reminder?.fireDate, date(10), "lead time passed → remind at start")

        store.setScheduleReminder(task.id, leadMinutes: nil)
        XCTAssertFalse(store.plannedReminders(now: date(9)).contains {
            if case .schedule = $0.kind { return true }; return false
        })
    }

    func testStartingScheduledTaskConsumesOnlyItsStartReminder() {
        let (store, _) = makeStore()
        let task = store.addTask("开始写作", to: .importantUrgent)!
        store.scheduleTask(task.id, at: date(10), reminderLeadMinutes: 5)
        XCTAssertTrue(store.startTimer(task.id, now: date(9, 50)))
        XCTAssertEqual(store.tasks.first?.scheduledAt, date(10))
        XCTAssertNil(store.tasks.first?.scheduleReminderLeadMinutes)
        XCTAssertFalse(store.plannedReminders(now: date(9, 50)).contains {
            if case .schedule = $0.kind { return true }; return false
        })
    }

    func testConflictsAreDetectedButNotBlocked() {
        let (store, _) = makeStore()
        let first = store.addTask("A", to: .importantUrgent)!
        let second = store.addTask("B", to: .importantNotUrgent)!
        let third = store.addTask("C", to: .neither)!
        store.scheduleTask(first.id, at: date(10), durationMinutes: 60)
        store.scheduleTask(second.id, at: date(10, 30), durationMinutes: 30)
        store.scheduleTask(third.id, at: date(11), durationMinutes: 15)

        XCTAssertEqual(store.scheduleConflicts(for: first.id).map(\.id), [second.id])
        XCTAssertEqual(store.scheduleConflicts(for: second.id).map(\.id), [first.id])
        XCTAssertTrue(store.scheduleConflicts(for: third.id).isEmpty)
    }

    func testParallelWorkUsesCheckPointInsteadOfPersonalBlock() {
        let (store, _) = makeStore()
        let task = store.addTask("远端训练", to: .importantNotUrgent)!
        store.scheduleTask(task.id, at: date(9))
        XCTAssertTrue(store.startParallelRun(task.id, now: date(8)))
        XCTAssertNil(store.tasks.first?.scheduledAt, "delegating replaces the personal block")
        store.scheduleTask(task.id, at: date(10))
        XCTAssertNil(store.tasks.first?.scheduledAt)

        store.setParallelCheck(task.id, at: date(10), now: date(8))
        XCTAssertEqual(store.parallelRuns.first?.nextCheckAt, date(10))
        XCTAssertTrue(store.plannedReminders(now: date(8)).contains {
            if case .parallelCheck(let id) = $0.kind { return id == task.id }; return false
        })
    }

    func testRemovingFromTodayAndRolloverClearOldBlocks() {
        let (store, _) = makeStore()
        store.rolloverIfNeeded(now: date(8))
        let first = store.addTask("A", to: .importantUrgent)!
        store.scheduleTask(first.id, at: date(10))
        store.setToday(first.id, false)
        XCTAssertNil(store.tasks.first?.scheduledAt)

        let second = store.addTask("B", to: .importantUrgent)!
        store.scheduleTask(second.id, at: date(15))
        XCTAssertTrue(store.rolloverIfNeeded(now: date(8, day: 8)))
        XCTAssertNil(store.tasks.first(where: { $0.id == second.id })?.scheduledAt)
    }
}
