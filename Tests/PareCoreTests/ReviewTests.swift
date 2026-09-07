import XCTest
@testable import PareCore

/// Tests for the T1 "回看层" additions: review queries, the weekly-review
/// reminder, staleness/overload watches, `setActual`, and migration safety.
final class ReviewTests: XCTestCase {

    // MARK: - Fixtures

    /// A fixed Gregorian calendar in a DST-free zone (UTC+8) so day/week
    /// boundaries are deterministic on any machine.
    private func fixedCalendar() -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return cal
    }

    private func makeStore(calendar: Calendar? = nil) -> (Store, URL) {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-review-test-\(UUID().uuidString).json")
        return (Store(fileURL: tmp, calendar: calendar ?? fixedCalendar()), tmp)
    }

    private func date(_ cal: Calendar, _ y: Int, _ mo: Int, _ d: Int,
                      _ h: Int = 12, _ mi: Int = 0) -> Date {
        var c = DateComponents()
        c.year = y; c.month = mo; c.day = d; c.hour = h; c.minute = mi
        return cal.date(from: c)!
    }

    // MARK: - setActual

    func testSetActualWritesClearsAndTouchesProgress() {
        let (store, _) = makeStore()
        let cal = store.calendar
        store.addTask("写周报", to: .importantUrgent, now: date(cal, 2026, 1, 10))
        let id = store.tasks[0].id
        XCTAssertNil(store.tasks[0].lastProgressAt, "addTask leaves lastProgressAt nil")

        let touch = date(cal, 2026, 1, 15, 9, 0)
        store.setActual(id, minutes: 30, now: touch)
        XCTAssertEqual(store.tasks[0].actualMinutes, 30)
        XCTAssertEqual(store.tasks[0].lastProgressAt, touch, "setActual refreshes lastProgressAt")

        store.setActual(id, minutes: 0, now: touch)
        XCTAssertNil(store.tasks[0].actualMinutes, "0 clears the actual time")

        store.setActual(id, minutes: 45, now: touch)
        store.setActual(id, minutes: nil, now: touch)
        XCTAssertNil(store.tasks[0].actualMinutes, "nil clears the actual time")

        store.setActual(id, minutes: -5, now: touch)
        XCTAssertNil(store.tasks[0].actualMinutes, "negative clamps to 0 → cleared")
    }

    func testProgressActionsMaintainLastProgressAt() {
        let (store, _) = makeStore()
        let cal = store.calendar
        // organize records progress at `now`.
        store.capture("整理这条")
        let inboxID = store.inbox[0].id
        let organizedAt = date(cal, 2026, 1, 11, 8, 0)
        let task = store.organize(inboxID: inboxID, to: .importantNotUrgent, now: organizedAt)!
        XCTAssertEqual(store.tasks.first { $0.id == task.id }?.lastProgressAt, organizedAt)

        let t2 = date(cal, 2026, 1, 12, 8, 0)
        store.setToday(task.id, true, now: t2)
        XCTAssertEqual(store.tasks.first { $0.id == task.id }?.lastProgressAt, t2)

        let t3 = date(cal, 2026, 1, 13, 8, 0)
        store.changeQuadrant(task.id, to: .urgentNotImportant, now: t3)
        XCTAssertEqual(store.tasks.first { $0.id == task.id }?.lastProgressAt, t3)

        let t4 = date(cal, 2026, 1, 14, 8, 0)
        store.toggleToday(task.id, now: t4)
        XCTAssertEqual(store.tasks.first { $0.id == task.id }?.lastProgressAt, t4)

        let t5 = date(cal, 2026, 1, 15, 8, 0)
        store.setCompleted(task.id, true, now: t5)
        XCTAssertEqual(store.tasks.first { $0.id == task.id }?.lastProgressAt, t5)
    }

    // MARK: - todaySummary

    func testTodaySummaryCountsFocusAndCarryOver() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 18, 0)

        // A: completed today, actual (30) preferred over estimate (20).
        store.addTask("A", to: .importantUrgent, estimatedMinutes: 20, now: date(cal, 2026, 1, 15, 9))
        let a = store.tasks.first { $0.text == "A" }!.id
        store.setCompleted(a, true, now: date(cal, 2026, 1, 15, 10))
        store.setActual(a, minutes: 30, now: date(cal, 2026, 1, 15, 10))

        // B: completed today, estimate (45) used since no actual.
        store.addTask("B", to: .importantNotUrgent, estimatedMinutes: 45, now: date(cal, 2026, 1, 15, 9))
        let b = store.tasks.first { $0.text == "B" }!.id
        store.setCompleted(b, true, now: date(cal, 2026, 1, 15, 11))

        // C: today, still incomplete → carry over.
        store.addTask("C", to: .importantUrgent, addToToday: true, now: date(cal, 2026, 1, 15, 9))

        // D: completed yesterday → not part of today.
        store.addTask("D", to: .importantUrgent, now: date(cal, 2026, 1, 14, 9))
        let d = store.tasks.first { $0.text == "D" }!.id
        store.setCompleted(d, true, now: date(cal, 2026, 1, 14, 20))

        let summary = store.todaySummary(now: now)
        XCTAssertEqual(summary.completedCount, 2)
        XCTAssertEqual(Set(summary.completedTasks.map(\.text)), ["A", "B"])
        XCTAssertEqual(summary.focusMinutes, 75, "30 (actual) + 45 (estimate fallback)")
        XCTAssertEqual(summary.carryOver.map(\.text), ["C"])
        XCTAssertFalse(summary.isZeroCompletion)
    }

    func testTodaySummaryZeroCompletion() {
        let (store, _) = makeStore()
        let cal = store.calendar
        store.addTask("只排不做", to: .importantUrgent, addToToday: true, now: date(cal, 2026, 1, 15, 9))
        let summary = store.todaySummary(now: date(cal, 2026, 1, 15, 18))
        XCTAssertTrue(summary.isZeroCompletion)
        XCTAssertEqual(summary.completedCount, 0)
        XCTAssertEqual(summary.focusMinutes, 0)
        XCTAssertEqual(summary.carryOver.map(\.text), ["只排不做"])
        XCTAssertFalse(summary.bias.hasData, "no completions → nothing comparable")
        XCTAssertNil(summary.biasHint, "no comparable data → bias area hides")
    }

    // MARK: - todaySummary bias / biasHint (T2 偏差对比区)

    /// Helper: add a task completed today with an estimate and (optional) actual.
    private func completeToday(_ store: Store, _ text: String,
                               estimate: Int?, actual: Int?) {
        let cal = store.calendar
        store.addTask(text, to: .importantUrgent, estimatedMinutes: estimate,
                      now: date(cal, 2026, 1, 15, 9))
        let id = store.tasks.first { $0.text == text }!.id
        store.setCompleted(id, true, now: date(cal, 2026, 1, 15, 10))
        if let actual { store.setActual(id, minutes: actual, now: date(cal, 2026, 1, 15, 10)) }
    }

    func testTodaySummaryBiasHiddenWhenNoActualRecorded() {
        // Completed with an estimate but no actual (T5 skipped) → not comparable.
        let (store, _) = makeStore()
        let cal = store.calendar
        completeToday(store, "写周报", estimate: 30, actual: nil)
        let summary = store.todaySummary(now: date(cal, 2026, 1, 15, 18))
        XCTAssertEqual(summary.completedCount, 1)
        XCTAssertFalse(summary.bias.hasData)
        XCTAssertNil(summary.biasHint, "no actuals → bias area auto-hides")
    }

    func testTodaySummaryBiasUnderestimateHint() {
        let (store, _) = makeStore()
        let cal = store.calendar
        completeToday(store, "A", estimate: 30, actual: 50) // +20
        completeToday(store, "B", estimate: 60, actual: 70) // +10
        let summary = store.todaySummary(now: date(cal, 2026, 1, 15, 18))
        XCTAssertEqual(summary.bias.comparableCount, 2)
        XCTAssertEqual(summary.bias.deltaMinutes, 30, "actual > estimate → underestimated")
        let hint = summary.biasHint
        XCTAssertNotNil(hint)
        XCTAssertTrue(hint!.contains("低估"))
        XCTAssertTrue(hint!.contains("平均每件"), "average delta 15m ≥ 1 → per-task wording")
    }

    func testTodaySummaryBiasOverestimateHint() {
        let (store, _) = makeStore()
        let cal = store.calendar
        completeToday(store, "高估", estimate: 60, actual: 20) // -40
        let hint = store.todaySummary(now: date(cal, 2026, 1, 15, 18)).biasHint
        XCTAssertNotNil(hint)
        XCTAssertTrue(hint!.contains("高估"))
    }

    func testTodaySummaryBiasExactHint() {
        let (store, _) = makeStore()
        let cal = store.calendar
        completeToday(store, "准", estimate: 40, actual: 40) // 0
        let hint = store.todaySummary(now: date(cal, 2026, 1, 15, 18)).biasHint
        XCTAssertNotNil(hint)
        XCTAssertTrue(hint!.contains("估得很准"))
    }

    func testTodaySummaryBiasFallsBackToTotalWhenAverageRoundsToZero() {
        // total delta +1m over 3 tasks → per-task average rounds to 0.
        let (store, _) = makeStore()
        let cal = store.calendar
        completeToday(store, "t0", estimate: 30, actual: 30)
        completeToday(store, "t1", estimate: 30, actual: 30)
        completeToday(store, "t2", estimate: 30, actual: 31)
        let summary = store.todaySummary(now: date(cal, 2026, 1, 15, 18))
        XCTAssertEqual(summary.bias.averageDeltaMinutes, 0)
        let hint = summary.biasHint
        XCTAssertNotNil(hint)
        XCTAssertTrue(hint!.contains("合计低估"), "small average falls back to total delta")
    }

    func testTodaySummaryCarryOverSortedByQuadrantThenCreatedAt() {
        let (store, _) = makeStore()
        let cal = store.calendar
        // Different quadrants + two in the same quadrant to drive the comparator.
        store.addTask("杂事", to: .neither, addToToday: true, now: date(cal, 2026, 1, 15, 9))
        store.addTask("紧急晚", to: .importantUrgent, addToToday: true, now: date(cal, 2026, 1, 15, 10))
        store.addTask("紧急早", to: .importantUrgent, addToToday: true, now: date(cal, 2026, 1, 15, 8))
        let summary = store.todaySummary(now: date(cal, 2026, 1, 15, 18))
        XCTAssertEqual(summary.carryOver.map(\.text), ["紧急早", "紧急晚", "杂事"],
                       "sorted by quadrant.rawValue then createdAt")
    }

    func testWeeklyReviewBiasFallsBackToTotalWhenAverageRoundsToZero() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 20)
        // 3 comparable tasks, total delta = +1m → per-task average rounds to 0.
        for (i, actual) in [30, 30, 31].enumerated() {
            store.addTask("t\(i)", to: .importantUrgent, estimatedMinutes: 30, now: date(cal, 2026, 1, 13, 9))
            let id = store.tasks.last!.id
            store.setCompleted(id, true, now: date(cal, 2026, 1, 13, 10))
            store.setActual(id, minutes: actual, now: date(cal, 2026, 1, 13, 10))
        }
        let review = store.weeklyReview(now: now)
        XCTAssertEqual(review.bias.deltaMinutes, 1)
        XCTAssertEqual(review.bias.averageDeltaMinutes, 0)
        XCTAssertTrue(review.markdown.contains("合计低估"), "small average falls back to total delta")
    }

    func testTodaySummaryCountsArchivedCompletionAfterRollover() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let day = date(cal, 2026, 1, 15, 9)
        XCTAssertFalse(store.rolloverIfNeeded(now: day), "first run establishes baseline")
        store.addTask("归档前完成", to: .importantUrgent, now: day)
        let id = store.tasks[0].id
        store.setCompleted(id, true, now: date(cal, 2026, 1, 15, 10))
        // Rolling to the next day archives the completed task.
        XCTAssertTrue(store.rolloverIfNeeded(now: date(cal, 2026, 1, 16, 9)))
        XCTAssertEqual(store.archive.count, 1)
        // Summary of the original day still finds it via the tasks+archive union.
        let summary = store.todaySummary(now: date(cal, 2026, 1, 15, 20))
        XCTAssertEqual(summary.completedCount, 1)
    }

    // MARK: - stalledFocusTasks

    func testStalledFocusTasksThresholdQuadrantAndProgress() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 9)

        // Quadrant II, created 3 days ago, never progressed → stalled (>=3).
        store.addTask("停滞3天", to: .importantNotUrgent, now: date(cal, 2026, 1, 12, 9))
        // Quadrant II, created 2 days ago → not yet stalled.
        store.addTask("才2天", to: .importantNotUrgent, now: date(cal, 2026, 1, 13, 9))
        // Quadrant I, created 5 days ago → wrong quadrant.
        store.addTask("象限一", to: .importantUrgent, now: date(cal, 2026, 1, 10, 9))
        // Quadrant II, created 5 days ago but completed → excluded.
        store.addTask("已完成", to: .importantNotUrgent, now: date(cal, 2026, 1, 10, 9))
        let doneID = store.tasks.first { $0.text == "已完成" }!.id
        store.setCompleted(doneID, true, now: date(cal, 2026, 1, 11, 9))

        var stalled = store.stalledFocusTasks(now: now)
        XCTAssertEqual(stalled.map(\.text), ["停滞3天"], "only the incomplete, >=3d quadrant-II task")

        // Progressing it (touch today) clears the staleness.
        let stalledID = store.tasks.first { $0.text == "停滞3天" }!.id
        store.setToday(stalledID, true, now: now)
        stalled = store.stalledFocusTasks(now: now)
        XCTAssertTrue(stalled.isEmpty, "progress resets the staleness clock")
    }

    func testStalledFocusTasksFallBackToCreatedAtAndRespectDisable() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 9)
        store.addTask("从没推进", to: .importantNotUrgent, now: date(cal, 2026, 1, 11, 9))
        XCTAssertNil(store.tasks[0].lastProgressAt, "fallback path: lastProgressAt is nil")
        XCTAssertEqual(store.stalledFocusTasks(now: now).map(\.text), ["从没推进"],
                       "uses createdAt when lastProgressAt is nil")

        var s = store.settings
        s.focusStalenessEnabled = false
        store.updateSettings(s)
        XCTAssertTrue(store.stalledFocusTasks(now: now).isEmpty, "disabled → no stalled items")
    }

    // MARK: - todayOverload

    func testTodayOverloadByCount() {
        let (store, _) = makeStore()
        let cal = store.calendar
        for i in 0..<5 {
            store.addTask("t\(i)", to: .importantUrgent, addToToday: true, now: date(cal, 2026, 1, 15, 9))
        }
        XCTAssertNil(store.todayOverload(), "5 items is at, not over, the default max")
        store.addTask("t5", to: .importantUrgent, addToToday: true, now: date(cal, 2026, 1, 15, 9))
        let overload = store.todayOverload()
        XCTAssertNotNil(overload)
        XCTAssertTrue(overload!.exceedsCount)
        XCTAssertFalse(overload!.exceedsCapacity)
        XCTAssertTrue(overload!.message.contains("6 件"))
    }

    func testTodayOverloadByCapacityAndDisable() {
        let (store, _) = makeStore()
        let cal = store.calendar
        // 3 items, but 300 minutes > 240 default capacity.
        store.addTask("长1", to: .importantUrgent, addToToday: true, estimatedMinutes: 120, now: date(cal, 2026, 1, 15, 9))
        store.addTask("长2", to: .importantUrgent, addToToday: true, estimatedMinutes: 120, now: date(cal, 2026, 1, 15, 9))
        store.addTask("长3", to: .importantUrgent, addToToday: true, estimatedMinutes: 60, now: date(cal, 2026, 1, 15, 9))
        let overload = store.todayOverload()
        XCTAssertNotNil(overload)
        XCTAssertFalse(overload!.exceedsCount)
        XCTAssertTrue(overload!.exceedsCapacity)
        XCTAssertTrue(overload!.message.contains("约"))

        var s = store.settings
        s.todayOverloadEnabled = false
        store.updateSettings(s)
        XCTAssertNil(store.todayOverload(), "disabled → nil")
    }

    func testTodayOverloadBothThresholds() {
        let (store, _) = makeStore()
        let cal = store.calendar
        for i in 0..<6 {
            store.addTask("t\(i)", to: .importantUrgent, addToToday: true, estimatedMinutes: 60, now: date(cal, 2026, 1, 15, 9))
        }
        let overload = store.todayOverload()
        XCTAssertNotNil(overload)
        XCTAssertTrue(overload!.exceedsCount)
        XCTAssertTrue(overload!.exceedsCapacity)
        XCTAssertTrue(overload!.message.contains("6 件"))
        XCTAssertTrue(overload!.message.contains("约"))
    }

    // MARK: - weeklyReview

    func testWeeklyReviewNaturalWeekBoundaries() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 12) // Thursday
        let review = store.weeklyReview(now: now)
        XCTAssertEqual(cal.component(.weekday, from: review.weekStart), 2, "week starts Monday")
        XCTAssertEqual(review.weekEnd, cal.date(byAdding: .day, value: 7, to: review.weekStart))
        XCTAssertLessThanOrEqual(review.weekStart, now)
        XCTAssertGreaterThan(review.weekEnd, now)
    }

    func testWeeklyReviewCountsQuadrantsBiasAndMarkdown() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 20) // Thu, week is 1/12–1/18

        // Two completed this week in different quadrants, both with est+actual.
        store.addTask("周报", to: .importantUrgent, estimatedMinutes: 30, now: date(cal, 2026, 1, 13, 9))
        let w = store.tasks.first { $0.text == "周报" }!.id
        store.setCompleted(w, true, now: date(cal, 2026, 1, 13, 10))
        store.setActual(w, minutes: 50, now: date(cal, 2026, 1, 13, 10)) // +20

        store.addTask("读书", to: .importantNotUrgent, estimatedMinutes: 60, now: date(cal, 2026, 1, 14, 9))
        let r = store.tasks.first { $0.text == "读书" }!.id
        store.setCompleted(r, true, now: date(cal, 2026, 1, 14, 10))
        store.setActual(r, minutes: 70, now: date(cal, 2026, 1, 14, 10)) // +10

        // One completed 8 days earlier → outside this week.
        store.addTask("上周的事", to: .importantUrgent, now: date(cal, 2026, 1, 6, 9))
        let old = store.tasks.first { $0.text == "上周的事" }!.id
        store.setCompleted(old, true, now: date(cal, 2026, 1, 7, 10))

        let review = store.weeklyReview(now: now)
        XCTAssertEqual(review.completedCount, 2, "only this week's completions")
        XCTAssertEqual(review.byQuadrant[.importantUrgent], 1)
        XCTAssertEqual(review.byQuadrant[.importantNotUrgent], 1)
        XCTAssertFalse(review.isEmpty)

        XCTAssertEqual(review.bias.comparableCount, 2)
        XCTAssertEqual(review.bias.totalEstimated, 90)
        XCTAssertEqual(review.bias.totalActual, 120)
        XCTAssertEqual(review.bias.deltaMinutes, 30, "actual > estimate → underestimated")

        let md = review.markdown
        XCTAssertTrue(md.contains("# 本周回顾（1月12日 – 1月18日）"))
        XCTAssertTrue(md.contains("本周完成 **2** 件。"))
        XCTAssertTrue(md.contains("## 按象限分布"))
        XCTAssertTrue(md.contains("- \(Quadrant.importantUrgent.title)：1"))
        XCTAssertTrue(md.contains("- \(Quadrant.importantNotUrgent.title)：1"))
        XCTAssertTrue(md.contains("## 估时校准"))
        XCTAssertTrue(md.contains("低估"))
    }

    func testWeeklyReviewEmptyWeek() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let review = store.weeklyReview(now: date(cal, 2026, 1, 15, 12))
        XCTAssertTrue(review.isEmpty)
        XCTAssertEqual(review.completedCount, 0)
        XCTAssertTrue(review.markdown.contains("这周才刚开始"))
        XCTAssertFalse(review.markdown.contains("## 按象限分布"))
    }

    func testWeeklyReviewListsStalledFocusInMarkdown() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 20)
        store.addTask("学英语", to: .importantNotUrgent, now: date(cal, 2026, 1, 11, 9)) // 4 days stale
        let review = store.weeklyReview(now: now)
        XCTAssertEqual(review.stalledFocus.map(\.text), ["学英语"])
        XCTAssertTrue(review.markdown.contains("## 象限二 · 一直被拖的事"))
        XCTAssertTrue(review.markdown.contains("学英语（已 4 天没动）"))
    }

    func testWeeklyReviewBiasBranches() {
        // No comparable data (completed but no actuals recorded).
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 20)
        store.addTask("无实际", to: .importantUrgent, estimatedMinutes: 30, now: date(cal, 2026, 1, 13, 9))
        let id = store.tasks[0].id
        store.setCompleted(id, true, now: date(cal, 2026, 1, 13, 10))
        XCTAssertFalse(store.weeklyReview(now: now).bias.hasData)
        XCTAssertTrue(store.weeklyReview(now: now).markdown.contains("还没有可对比"))

        // Overestimate branch: actual < estimate.
        let (store2, _) = makeStore()
        store2.addTask("高估", to: .importantUrgent, estimatedMinutes: 60, now: date(cal, 2026, 1, 13, 9))
        let id2 = store2.tasks[0].id
        store2.setCompleted(id2, true, now: date(cal, 2026, 1, 13, 10))
        store2.setActual(id2, minutes: 20, now: date(cal, 2026, 1, 13, 10))
        let md2 = store2.weeklyReview(now: now).markdown
        XCTAssertTrue(md2.contains("高估"))

        // Exact branch: actual == estimate.
        let (store3, _) = makeStore()
        store3.addTask("准", to: .importantUrgent, estimatedMinutes: 40, now: date(cal, 2026, 1, 13, 9))
        let id3 = store3.tasks[0].id
        store3.setCompleted(id3, true, now: date(cal, 2026, 1, 13, 10))
        store3.setActual(id3, minutes: 40, now: date(cal, 2026, 1, 13, 10))
        let md3 = store3.weeklyReview(now: now).markdown
        XCTAssertTrue(md3.contains("估得很准"))
    }

    // MARK: - Reminders

    func testWeeklyReviewReminderScheduledAtConfiguredSlot() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 12) // Thursday
        let weekly = store.plannedReminders(now: now)
            .first { if case .weeklyReview = $0.kind { return true }; return false }
        XCTAssertNotNil(weekly)
        XCTAssertEqual(weekly!.identifier, "weekly.review")
        XCTAssertEqual(cal.component(.weekday, from: weekly!.fireDate), 1, "default is Sunday")
        XCTAssertEqual(cal.component(.hour, from: weekly!.fireDate), 20)
        XCTAssertGreaterThan(weekly!.fireDate, now)

        var s = store.settings
        s.weeklyReviewEnabled = false
        store.updateSettings(s)
        XCTAssertFalse(store.plannedReminders(now: now)
            .contains { if case .weeklyReview = $0.kind { return true }; return false })
    }

    func testMorningPlanAppendsStalledSentenceOnlyWhenStalled() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 7)

        func planBody() -> String {
            store.plannedReminders(now: now)
                .first { if case .plan = $0.kind { return true }; return false }!.body
        }
        XCTAssertFalse(planBody().contains("象限二"), "no stalled task → no stalled sentence")

        store.addTask("拖了很久", to: .importantNotUrgent, now: date(cal, 2026, 1, 11, 9)) // 4 days
        let body = planBody()
        XCTAssertTrue(body.contains("象限二有 1 件"))
        XCTAssertTrue(body.contains("≥3 天"))
    }

    // MARK: - Migration

    func testOldSnapshotWithoutNewFieldsDecodesCleanly() {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-migration-\(UUID().uuidString).json")
        let oldJSON = """
        {
          "inbox": [
            {"id": "11111111-1111-1111-1111-111111111111", "text": "旧收件", "createdAt": "2026-01-10T09:00:00Z"}
          ],
          "tasks": [
            {"id": "22222222-2222-2222-2222-222222222222", "text": "旧任务", "quadrant": 1, "createdAt": "2026-01-09T09:00:00Z", "isToday": true, "completed": false}
          ],
          "archive": [
            {"id": "33333333-3333-3333-3333-333333333333", "text": "旧归档", "quadrant": 0, "createdAt": "2026-01-01T09:00:00Z", "isToday": false, "completed": true, "completedAt": "2026-01-02T10:00:00Z"}
          ],
          "lastActiveDay": "2026-01-10T00:00:00Z",
          "settings": {
            "planReminderEnabled": true,
            "planHour": 7,
            "planMinute": 30,
            "reviewReminderEnabled": false,
            "reviewHour": 18,
            "reviewMinute": 0,
            "inboxPileupEnabled": true,
            "inboxPileupThreshold": 12
          }
        }
        """
        try! oldJSON.data(using: .utf8)!.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let store = Store(fileURL: tmp)
        // Data survives — nothing is lost by the new fields.
        XCTAssertEqual(store.inbox.count, 1)
        XCTAssertEqual(store.inbox.first?.text, "旧收件")
        XCTAssertEqual(store.tasks.count, 1)
        XCTAssertEqual(store.archive.count, 1)
        // New task fields default to nil.
        XCTAssertNil(store.tasks[0].actualMinutes)
        XCTAssertNil(store.tasks[0].parallelRunSessions)
        XCTAssertTrue(store.parallelRuns.isEmpty)
        XCTAssertNil(store.tasks[0].lastProgressAt)
        // Old settings preserved…
        XCTAssertEqual(store.settings.planHour, 7)
        XCTAssertEqual(store.settings.planMinute, 30)
        XCTAssertFalse(store.settings.reviewReminderEnabled)
        XCTAssertEqual(store.settings.inboxPileupThreshold, 12)
        // …and the new preferences take their defaults.
        XCTAssertTrue(store.settings.weeklyReviewEnabled)
        XCTAssertEqual(store.settings.weeklyReviewWeekday, 1)
        XCTAssertEqual(store.settings.weeklyReviewHour, 20)
        XCTAssertTrue(store.settings.focusStalenessEnabled)
        XCTAssertEqual(store.settings.focusStalenessDays, 3)
        XCTAssertEqual(store.settings.todayMaxCount, 5)
        XCTAssertEqual(store.settings.todayCapacityMinutes, 240)
    }

    func testSnapshotWithoutSettingsUsesDefaults() {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-migration-nosettings-\(UUID().uuidString).json")
        let json = """
        {
          "inbox": [],
          "tasks": [
            {"id": "44444444-4444-4444-4444-444444444444", "text": "无设置任务", "quadrant": 2, "createdAt": "2026-01-09T09:00:00Z", "isToday": false, "completed": false}
          ]
        }
        """
        try! json.data(using: .utf8)!.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let store = Store(fileURL: tmp)
        XCTAssertEqual(store.tasks.count, 1)
        XCTAssertEqual(store.settings, .default, "absent settings → default settings")
    }

    func testNewFieldsPersistAcrossReload() {
        let (store, url) = makeStore()
        defer { try? FileManager.default.removeItem(at: url) }
        let cal = store.calendar
        store.addTask("持久化", to: .importantNotUrgent, now: date(cal, 2026, 1, 12, 9))
        let id = store.tasks[0].id
        store.setActual(id, minutes: 25, now: date(cal, 2026, 1, 15, 9))
        var s = store.settings
        s.weeklyReviewHour = 21
        s.focusStalenessDays = 5
        s.todayMaxCount = 7
        store.updateSettings(s)

        let reloaded = Store(fileURL: url, calendar: cal)
        XCTAssertEqual(reloaded.tasks.first?.actualMinutes, 25)
        XCTAssertEqual(reloaded.tasks.first?.lastProgressAt, date(cal, 2026, 1, 15, 9))
        XCTAssertEqual(reloaded.settings.weeklyReviewHour, 21)
        XCTAssertEqual(reloaded.settings.focusStalenessDays, 5)
        XCTAssertEqual(reloaded.settings.todayMaxCount, 7)
    }
}
