import XCTest
@testable import PareCore

final class ProductivityTests: XCTestCase {
    private func calendar() -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return cal
    }

    private func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 9, _ minute: Int = 0) -> Date {
        calendar().date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: minute))!
    }

    private func makeStore() -> (Store, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-productivity-\(UUID().uuidString).json")
        return (Store(fileURL: url, calendar: calendar()), url)
    }

    func testNaturalLanguageReminderParser() {
        let now = date(2026, 9, 4, 10)
        let relative = CaptureParser.parse("30分钟后提醒我喝水", now: now, calendar: calendar())
        XCTAssertEqual(relative.text, "喝水")
        XCTAssertEqual(relative.remindAt, now.addingTimeInterval(1_800))

        let clock = CaptureParser.parse("明天下午3点提醒我写报告", now: now, calendar: calendar())
        XCTAssertEqual(clock.text, "写报告")
        XCTAssertEqual(clock.remindAt, date(2026, 9, 5, 15))
    }

    func testThoughtTaskDetailsAndTodayOrderPersist() {
        let (store, url) = makeStore()
        store.capture("研究一个新方向")
        let thought = store.keepAsThought(inboxID: store.inbox[0].id)!
        store.updateThought(thought.id, text: thought.text, notes: "目标 / 证据 / 下一步")

        let a = store.addTask("A", to: .importantUrgent, addToToday: true)!
        let b = store.addTask("B", to: .importantNotUrgent, addToToday: true)!
        store.moveToday(b.id, to: 0)
        store.updateTaskDetails(a.id, text: "A edited", note: "背景", nextAction: "先列提纲",
                                dueAt: date(2026, 9, 5, 18), remindAt: date(2026, 9, 5, 17),
                                recurrence: .weekly)

        let reloaded = Store(fileURL: url, calendar: calendar())
        XCTAssertEqual(reloaded.thoughts.first?.notes, "目标 / 证据 / 下一步")
        XCTAssertEqual(reloaded.todayTasks.first?.id, b.id)
        let edited = reloaded.tasks.first { $0.id == a.id }
        XCTAssertEqual(edited?.text, "A edited")
        XCTAssertEqual(edited?.nextAction, "先列提纲")
        XCTAssertEqual(edited?.recurrence, .weekly)

        let promoted = reloaded.promoteThought(reloaded.thoughts[0].id)!
        XCTAssertEqual(promoted.quadrant, .importantNotUrgent)
        XCTAssertEqual(promoted.note, "目标 / 证据 / 下一步")
        XCTAssertTrue(reloaded.thoughts.isEmpty)
    }

    func testUndoDeleteAndClear() {
        let (store, _) = makeStore()
        let a = store.addTask("A", to: .neither)!
        store.addTask("B", to: .neither)
        store.deleteTask(a.id)
        XCTAssertEqual(store.tasks.map(\.text), ["B"])
        store.undoLastDeletion()
        XCTAssertEqual(Set(store.tasks.map(\.text)), ["A", "B"])

        XCTAssertEqual(store.clearQuadrant(.neither), 2)
        XCTAssertTrue(store.tasks.isEmpty)
        store.undoLastDeletion()
        XCTAssertEqual(Set(store.tasks.map(\.text)), ["A", "B"])
    }

    func testFocusTimerAccumulatesAndPersists() {
        let (store, url) = makeStore()
        let task = store.addTask("深度工作", to: .importantNotUrgent)!
        let start = date(2026, 9, 4, 10)
        XCTAssertTrue(store.startTimer(task.id, now: start))

        let running = Store(fileURL: url, calendar: calendar())
        XCTAssertEqual(running.activeTimer?.taskID, task.id)
        XCTAssertEqual(running.stopTimer(now: start.addingTimeInterval(125)), 3)
        XCTAssertEqual(running.tasks.first?.actualMinutes, 3)

        XCTAssertTrue(running.startTimer(task.id, now: start.addingTimeInterval(200)))
        XCTAssertEqual(running.stopTimer(now: start.addingTimeInterval(240)), 1)
        XCTAssertEqual(running.tasks.first?.actualMinutes, 4)
    }

    func testRecurringReminderPlansFourFutureOccurrences() {
        let (store, _) = makeStore()
        let now = date(2026, 9, 4, 10)
        let task = store.addTask("每日复盘", to: .importantNotUrgent)!
        store.updateTaskDetails(task.id, text: task.text, note: nil, nextAction: nil,
                                dueAt: nil, remindAt: date(2026, 9, 3, 20), recurrence: .daily)
        let reminders = store.plannedReminders(now: now).filter { $0.taskID == task.id }
        XCTAssertEqual(reminders.count, 4)
        XCTAssertTrue(reminders.allSatisfy { $0.fireDate > now && $0.repeatRule == .daily })
        XCTAssertEqual(calendar().dateComponents([.day], from: reminders[0].fireDate,
                                                 to: reminders[1].fireDate).day, 1)
    }

    func testLongTermTrendsAggregateWeeksAndBacklog() {
        let (store, _) = makeStore()
        let now = date(2026, 9, 4, 20) // Friday
        store.addTask("上周深度", to: .importantNotUrgent, estimatedMinutes: 30,
                      now: date(2026, 8, 25))
        let first = store.tasks.last!.id
        store.setCompleted(first, true, now: date(2026, 8, 26, 18))
        store.setActual(first, minutes: 45, now: date(2026, 8, 26, 18))

        store.addTask("本周救火", to: .importantUrgent, now: date(2026, 9, 2))
        let second = store.tasks.last!.id
        store.setCompleted(second, true, now: date(2026, 9, 3, 18))
        store.addTask("待删", to: .neither)

        let trends = store.longTermTrends(now: now, weekCount: 2)
        XCTAssertEqual(trends.weeks.map(\.completedCount), [1, 1])
        XCTAssertEqual(trends.byQuadrant[.importantNotUrgent], 1)
        XCTAssertEqual(trends.byQuadrant[.importantUrgent], 1)
        XCTAssertEqual(trends.quadrantFourBacklog, 1)
        XCTAssertTrue(trends.bias.hasData)
    }

    func testCompletedWorkClearsOverloadSignal() {
        let (store, _) = makeStore()
        for i in 0..<6 { store.addTask("T\(i)", to: .importantUrgent, addToToday: true) }
        XCTAssertNotNil(store.todayOverload())
        for task in store.todayTasks { store.setCompleted(task.id, true) }
        XCTAssertNil(store.todayOverload())
    }

    func testAutomaticBackupRecoveryAndTransfer() throws {
        let (store, url) = makeStore()
        store.capture("第一版")
        store.capture("第二版") // saves a known-good previous generation
        let backup = url.appendingPathExtension("bak")
        XCTAssertTrue(FileManager.default.fileExists(atPath: backup.path))

        try Data("broken".utf8).write(to: url, options: .atomic)
        let recovered = Store(fileURL: url, calendar: calendar())
        XCTAssertEqual(recovered.inbox.map(\.text), ["第一版"])
        XCTAssertTrue(recovered.persistenceIssue?.contains("自动备份") == true)

        let exported = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-export-\(UUID().uuidString).json")
        try recovered.exportData(to: exported)
        let (destination, _) = makeStore()
        try destination.importData(from: exported)
        XCTAssertEqual(destination.inbox.map(\.text), ["第一版"])
    }

    func testUnrecoverableStoreIsNotOverwritten() throws {
        let (_, url) = makeStore()
        try Data("bad-primary".utf8).write(to: url, options: .atomic)
        try Data("bad-backup".utf8).write(to: url.appendingPathExtension("bak"), options: .atomic)
        let store = Store(fileURL: url, calendar: calendar())
        XCTAssertNotNil(store.persistenceIssue)
        XCTAssertFalse(store.capture("不能覆盖旧文件"))
        XCTAssertTrue(store.inbox.isEmpty)
        XCTAssertEqual(String(data: try Data(contentsOf: url), encoding: .utf8), "bad-primary")
    }

    func testThoughtReviewJoinsMorningNudgeAndUnestimatedCount() {
        let (store, _) = makeStore()
        let now = date(2026, 9, 4, 8)
        let thought = store.addThought("重新想产品方向", now: now)!
        store.updateThought(thought.id, text: thought.text, notes: nil,
                            reviewAt: now.addingTimeInterval(-60), now: now)
        store.addTask("未估时", to: .importantUrgent, addToToday: true, now: now)
        store.addTask("已估时", to: .importantNotUrgent, addToToday: true,
                      estimatedMinutes: 30, now: now)

        XCTAssertEqual(store.thoughtsDueForReview(now: now).map(\.text), ["重新想产品方向"])
        XCTAssertEqual(store.todayRemainingUnestimatedCount, 1)
        let plan = store.plannedReminders(now: now)
            .first { if case .plan = $0.kind { return true }; return false }
        XCTAssertTrue(plan?.body.contains("1 个想法") == true)
    }
}
