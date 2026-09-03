import XCTest
@testable import PareCore

/// Tests for the T4 "整理纪律引导" additions: the Quadrant-IV cleanup nudge and
/// its `clearQuadrant` action, plus the T4 acceptance angles on the existing
/// staleness sentence (recedes after progress / can be turned off) and the
/// today-overload hint (appears and recedes across the threshold).
final class DisciplineTests: XCTestCase {

    // MARK: - Fixtures (mirror ReviewTests for deterministic day/week math)

    private func fixedCalendar() -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return cal
    }

    private func makeStore(calendar: Calendar? = nil) -> (Store, URL) {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-discipline-test-\(UUID().uuidString).json")
        return (Store(fileURL: tmp, calendar: calendar ?? fixedCalendar()), tmp)
    }

    private func date(_ cal: Calendar, _ y: Int, _ mo: Int, _ d: Int,
                      _ h: Int = 12, _ mi: Int = 0) -> Date {
        var c = DateComponents()
        c.year = y; c.month = mo; c.day = d; c.hour = h; c.minute = mi
        return cal.date(from: c)!
    }

    // MARK: - quadrantFourCleanup

    func testQuadrantFourCleanupAppearsAtThresholdAndCountsOnlyOpenNeither() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 9)

        // Four incomplete Quadrant-IV items: below the default threshold (5).
        for i in 0..<4 {
            store.addTask("杂\(i)", to: .neither, now: now)
        }
        XCTAssertNil(store.quadrantFourCleanup(now: now), "4 items is below the default threshold")

        // Other quadrants must not push Quadrant-IV over the line.
        store.addTask("要紧", to: .importantUrgent, now: now)
        store.addTask("重要不急", to: .importantNotUrgent, now: now)
        XCTAssertNil(store.quadrantFourCleanup(now: now), "other quadrants don't count toward IV")

        // Fifth incomplete IV item reaches the threshold → nudge appears.
        store.addTask("杂4", to: .neither, now: now)
        let nudge = store.quadrantFourCleanup(now: now)
        XCTAssertNotNil(nudge)
        XCTAssertEqual(nudge?.count, 5)
        XCTAssertEqual(nudge?.threshold, 5)
        XCTAssertTrue(nudge?.message.contains("5 件") ?? false)

        // Completing one drops it below the threshold again → recedes.
        let doneID = store.tasks.first { $0.text == "杂0" }!.id
        store.setCompleted(doneID, true, now: now)
        XCTAssertNil(store.quadrantFourCleanup(now: now), "completed items are not part of the backlog")
    }

    func testQuadrantFourCleanupRespectsThresholdSettingAndDisable() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 9)

        var s = store.settings
        s.quadrantCleanupThreshold = 2
        store.updateSettings(s)

        store.addTask("a", to: .neither, now: now)
        XCTAssertNil(store.quadrantFourCleanup(now: now), "1 < threshold 2")
        store.addTask("b", to: .neither, now: now)
        XCTAssertNotNil(store.quadrantFourCleanup(now: now), "reaches the tuned threshold")

        s = store.settings
        s.quadrantCleanupEnabled = false
        store.updateSettings(s)
        XCTAssertNil(store.quadrantFourCleanup(now: now), "disabled → no nudge")
    }

    // MARK: - clearQuadrant

    func testClearQuadrantRemovesOnlyThatQuadrant() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 9)

        store.addTask("紧急1", to: .importantUrgent, now: now)
        store.addTask("紧急2", to: .importantUrgent, now: now)
        store.addTask("焦点", to: .importantNotUrgent, now: now)
        store.addTask("委托", to: .urgentNotImportant, now: now)
        store.addTask("杂1", to: .neither, now: now)
        store.addTask("杂2", to: .neither, now: now)
        // A completed Quadrant-IV task — the default clear should keep it.
        store.addTask("杂已完成", to: .neither, now: now)
        let doneID = store.tasks.first { $0.text == "杂已完成" }!.id
        store.setCompleted(doneID, true, now: now)

        let removed = store.clearQuadrant(.neither)
        XCTAssertEqual(removed, 2, "only the two incomplete IV tasks are cleared")
        XCTAssertEqual(store.tasks(in: .importantUrgent).count, 2, "quadrant I untouched")
        XCTAssertEqual(store.tasks(in: .importantNotUrgent).count, 1, "quadrant II untouched")
        XCTAssertEqual(store.tasks(in: .urgentNotImportant).count, 1, "quadrant III untouched")
        XCTAssertEqual(store.tasks(in: .neither).map(\.text), ["杂已完成"],
                       "completed IV task survives the default clear")

        // Clearing again with completed included empties the drawer; others stay.
        let removed2 = store.clearQuadrant(.neither, includingCompleted: true)
        XCTAssertEqual(removed2, 1)
        XCTAssertTrue(store.tasks(in: .neither).isEmpty)
        XCTAssertEqual(store.tasks(in: .importantUrgent).count, 2)
        XCTAssertEqual(store.tasks(in: .importantNotUrgent).count, 1)
        XCTAssertEqual(store.tasks(in: .urgentNotImportant).count, 1)

        // Nothing left to clear → no-op returns 0.
        XCTAssertEqual(store.clearQuadrant(.neither), 0)
    }

    func testClearQuadrantPersists() {
        let (store, url) = makeStore()
        defer { try? FileManager.default.removeItem(at: url) }
        let cal = store.calendar
        store.addTask("留下", to: .importantUrgent, now: date(cal, 2026, 1, 15, 9))
        store.addTask("清掉", to: .neither, now: date(cal, 2026, 1, 15, 9))
        store.clearQuadrant(.neither)

        let reloaded = Store(fileURL: url, calendar: cal)
        XCTAssertEqual(reloaded.tasks.map(\.text), ["留下"], "clear survives reload")
    }

    // MARK: - Staleness sentence (T4 acceptance: recedes after progress / off)

    func testMorningStalledSentenceRecedesAfterProgressAndWhenDisabled() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 7)

        func planBody() -> String {
            store.plannedReminders(now: now)
                .first { if case .plan = $0.kind { return true }; return false }!.body
        }

        // A Quadrant-II task idle for 4 days surfaces the stalled sentence.
        store.addTask("学英语", to: .importantNotUrgent, now: date(cal, 2026, 1, 11, 9))
        XCTAssertTrue(planBody().contains("象限二有 1 件"))

        // Progressing it (moved into today) resets the clock → sentence recedes.
        let id = store.tasks.first { $0.text == "学英语" }!.id
        store.setToday(id, true, now: now)
        XCTAssertFalse(planBody().contains("象限二"), "progress clears the stalled sentence")

        // Let it go stale again, then turn the watch off in settings.
        store.setToday(id, false, now: date(cal, 2026, 1, 11, 9))
        XCTAssertTrue(planBody().contains("象限二"), "stale again after backdated progress")
        var s = store.settings
        s.focusStalenessEnabled = false
        store.updateSettings(s)
        XCTAssertFalse(planBody().contains("象限二"), "disabled in settings → no stalled sentence")
    }

    // MARK: - Today overload (T4 acceptance: appears and recedes)

    func testTodayOverloadAppearsThenRecedesAsItemsDrop() {
        let (store, _) = makeStore()
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 9)

        var ids: [UUID] = []
        for i in 0..<6 {
            store.addTask("t\(i)", to: .importantUrgent, addToToday: true, now: now)
            ids.append(store.tasks.last!.id)
        }
        XCTAssertNotNil(store.todayOverload(now: now), "6 > default max 5 → hint appears")

        // Pull one back out of today → 5 items, at (not over) the limit → recedes.
        store.setToday(ids[0], false, now: now)
        XCTAssertNil(store.todayOverload(now: now), "back at the limit → hint recedes")
    }
}
