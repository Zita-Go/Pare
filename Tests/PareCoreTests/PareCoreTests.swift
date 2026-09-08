import XCTest
@testable import PareCore

final class PareCoreTests: XCTestCase {

    private func makeStore() -> (Store, URL) {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-test-\(UUID().uuidString).json")
        return (Store(fileURL: tmp), tmp)
    }

    func testCaptureRejectsBlankAndTrims() {
        let (store, _) = makeStore()
        XCTAssertFalse(store.capture("   \n  "))
        XCTAssertTrue(store.capture("  买牛奶  "))
        XCTAssertEqual(store.inbox.count, 1)
        XCTAssertEqual(store.inbox.first?.text, "买牛奶")
    }

    func testOrganizeMovesInboxItemIntoQuadrant() {
        let (store, _) = makeStore()
        store.capture("写周报")
        let id = store.inbox[0].id
        let task = store.organize(inboxID: id, to: .importantUrgent, addToToday: true)
        XCTAssertNotNil(task)
        XCTAssertTrue(store.inbox.isEmpty)
        XCTAssertEqual(store.tasks(in: .importantUrgent).count, 1)
        XCTAssertTrue(store.todayTasks.contains { $0.id == task?.id })
    }

    func testTodayProgressCounts() {
        let (store, _) = makeStore()
        store.addTask("A", to: .importantUrgent, addToToday: true)
        store.addTask("B", to: .importantNotUrgent, addToToday: true)
        store.addTask("C", to: .neither, addToToday: false)
        XCTAssertEqual(store.todayTotal, 2)
        XCTAssertEqual(store.todayCompleted, 0)
        let a = store.todayTasks.first { $0.text == "A" }!
        store.toggleComplete(a.id)
        XCTAssertEqual(store.todayCompleted, 1)
        XCTAssertEqual(store.todayRemaining, 1)
    }

    func testPersistenceRoundTrip() {
        let (store, url) = makeStore()
        store.capture("持久化测试")
        store.addTask("任务1", to: .importantUrgent, addToToday: true)
        // Reload from the same file into a fresh store.
        let reloaded = Store(fileURL: url)
        XCTAssertEqual(reloaded.inbox.count, 1)
        XCTAssertEqual(reloaded.inbox.first?.text, "持久化测试")
        XCTAssertEqual(reloaded.tasks.count, 1)
        XCTAssertTrue(reloaded.todayTasks.contains { $0.text == "任务1" })
        try? FileManager.default.removeItem(at: url)
    }

    func testDailyRolloverArchivesDoneAndClearsToday() {
        let (store, _) = makeStore()
        let today = Date()

        // Mirror the real app: first launch establishes today's baseline and
        // must NOT archive anything.
        XCTAssertFalse(store.rolloverIfNeeded(now: today))

        store.addTask("done", to: .importantUrgent, addToToday: true)
        store.addTask("todo", to: .importantNotUrgent, addToToday: true)
        let done = store.tasks.first { $0.text == "done" }!
        store.setCompleted(done.id, true, now: today)

        // Next day: rollover archives completed work and clears today's focus.
        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: today)!
        let rolled = store.rolloverIfNeeded(now: tomorrow)
        XCTAssertTrue(rolled)
        XCTAssertEqual(store.archive.count, 1, "completed task should be archived")
        XCTAssertEqual(store.tasks.count, 1, "only the incomplete task remains")
        XCTAssertFalse(store.tasks.first?.isToday ?? true, "today focus cleared on new day")
    }

    func testEstimatesFeedTodayTotals() {
        let (store, _) = makeStore()
        store.addTask("A", to: .importantUrgent, addToToday: true, estimatedMinutes: 30)
        store.addTask("B", to: .importantNotUrgent, addToToday: true, estimatedMinutes: 90)
        store.addTask("C", to: .neither, addToToday: false, estimatedMinutes: 60)
        XCTAssertEqual(store.todayEstimatedMinutes, 120, "only today's tasks count")

        let a = store.todayTasks.first { $0.text == "A" }!
        store.toggleComplete(a.id)
        XCTAssertEqual(store.todayRemainingEstimatedMinutes, 90, "completed time drops out of remaining")

        store.setEstimate(a.id, minutes: nil)
        XCTAssertEqual(store.todayEstimatedMinutes, 90, "clearing an estimate removes it from the total")

        store.setEstimate(a.id, minutes: 0)
        XCTAssertNil(store.tasks.first { $0.text == "A" }?.estimatedMinutes, "0 clears the estimate")
    }

    func testDurationLabelFormatting() {
        XCTAssertEqual(DurationLabel.short(25), "25m")
        XCTAssertEqual(DurationLabel.short(60), "1h")
        XCTAssertEqual(DurationLabel.short(90), "1h30m")
        XCTAssertEqual(DurationLabel.short(0), "")
    }

    func testDailyRemindersUseConfiguredTimes() {
        let (store, _) = makeStore()
        let cal = Calendar.current
        var comps = DateComponents()
        comps.year = 2026; comps.month = 1; comps.day = 15; comps.hour = 8; comps.minute = 0
        let now = cal.date(from: comps)!

        let planned = store.plannedReminders(now: now)
        let plan = planned.first { if case .plan = $0.kind { return true }; return false }
        let review = planned.first { if case .review = $0.kind { return true }; return false }
        XCTAssertNotNil(plan)
        XCTAssertNotNil(review)
        // Defaults: 09:00 and 18:00, both later today (now is 08:00).
        XCTAssertEqual(cal.component(.hour, from: plan!.fireDate), 9)
        XCTAssertEqual(cal.component(.hour, from: review!.fireDate), 18)
        XCTAssertTrue(cal.isDate(plan!.fireDate, inSameDayAs: now))
        XCTAssertGreaterThan(review!.fireDate, plan!.fireDate)

        // After 09:00 the plan reminder rolls over to tomorrow.
        let later = cal.date(bySettingHour: 10, minute: 0, second: 0, of: now)!
        let plan2 = store.plannedReminders(now: later)
            .first { if case .plan = $0.kind { return true }; return false }!
        XCTAssertGreaterThan(plan2.fireDate, later)
        XCTAssertEqual(cal.component(.hour, from: plan2.fireDate), 9)
        XCTAssertFalse(cal.isDate(plan2.fireDate, inSameDayAs: later))
    }

    func testPerTaskReminderScheduledOnlyWhenFutureAndIncomplete() {
        let (store, _) = makeStore()
        let now = Date()
        store.addTask("给客户回电", to: .importantUrgent)
        let t = store.tasks.first!

        // A future reminder is planned.
        store.setRemind(t.id, at: now.addingTimeInterval(3600))
        XCTAssertTrue(store.plannedReminders(now: now).contains { $0.taskID == t.id })

        // Completing the task drops its reminder.
        store.setCompleted(t.id, true, now: now)
        XCTAssertFalse(store.plannedReminders(now: now).contains { $0.taskID == t.id })

        // A reminder already in the past is not (re)scheduled.
        store.setCompleted(t.id, false, now: now)
        store.setRemind(t.id, at: now.addingTimeInterval(-60))
        XCTAssertFalse(store.plannedReminders(now: now).contains { $0.taskID == t.id })

        // Clearing the reminder removes it entirely.
        store.setRemind(t.id, at: nil)
        XCTAssertNil(store.tasks.first?.remindAt)
    }

    func testInboxPileupMentionedOnceThresholdCrossed() {
        let (store, _) = makeStore()
        var s = store.settings
        s.inboxPileupThreshold = 3
        store.updateSettings(s)

        store.capture("a"); store.capture("b")
        let planBelow = store.plannedReminders()
            .first { if case .plan = $0.kind { return true }; return false }!
        XCTAssertFalse(planBelow.body.contains("待整理"), "below threshold: no pileup mention")

        store.capture("c") // now 3 >= threshold
        let planAt = store.plannedReminders()
            .first { if case .plan = $0.kind { return true }; return false }!
        XCTAssertTrue(planAt.body.contains("待整理"), "at threshold: pileup mentioned")
    }

    func testSettingsPersistAcrossReload() {
        let (store, url) = makeStore()
        var s = store.settings
        s.planHour = 7
        s.reviewReminderEnabled = false
        s.inboxPileupThreshold = 12
        store.updateSettings(s)

        let reloaded = Store(fileURL: url)
        XCTAssertEqual(reloaded.settings.planHour, 7)
        XCTAssertFalse(reloaded.settings.reviewReminderEnabled)
        XCTAssertEqual(reloaded.settings.inboxPileupThreshold, 12)
        try? FileManager.default.removeItem(at: url)
    }

    func testMovingBetweenQuadrantsPreservesTaskState() {
        let (store, _) = makeStore()
        let now = Date()
        let task = store.addTask("移动我", to: .importantUrgent, addToToday: true,
                                 estimatedMinutes: 30, now: now)!
        store.updateTaskDetails(task.id, text: task.text, note: "背景", nextAction: "先打开文件",
                                dueAt: now.addingTimeInterval(7200),
                                remindAt: now.addingTimeInterval(3600), recurrence: nil)
        store.scheduleTask(task.id, at: now.addingTimeInterval(1800))

        store.changeQuadrant(task.id, to: .urgentNotImportant)
        var moved = store.tasks.first { $0.id == task.id }!
        XCTAssertEqual(moved.quadrant, .urgentNotImportant)
        XCTAssertTrue(moved.isToday)
        XCTAssertEqual(moved.note, "背景")
        XCTAssertEqual(moved.nextAction, "先打开文件")
        XCTAssertEqual(moved.estimatedMinutes, 30)
        XCTAssertNotNil(moved.remindAt)
        XCTAssertNotNil(moved.scheduledAt)

        store.setCompleted(task.id, true)
        store.changeQuadrant(task.id, to: .neither)
        moved = store.tasks.first { $0.id == task.id }!
        XCTAssertEqual(moved.quadrant, .neither)
        XCTAssertTrue(moved.completed)
        XCTAssertNotNil(moved.completedAt)
    }
}
