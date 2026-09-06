import XCTest
@testable import PareCore

/// Deterministic lifecycle coverage; does not change the system clock or sleep the Mac.
final class ReleaseLifecycleTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("pare-lifecycle-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }
    private func calendar(_ zone: String = "Asia/Shanghai") -> Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(identifier: zone)!
        return result
    }
    private func date(_ cal: Calendar, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        cal.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute))!
    }
    private func store(_ cal: Calendar) -> Store {
        Store(fileURL: directory.appendingPathComponent("store.json"), calendar: cal)
    }

    func testSkippedDaysArchiveOnceAndPersistUnfinishedWork() throws {
        let cal = calendar(), s = store(calendar())
        let before = date(cal, 9, 6, 23, 59), after = date(cal, 9, 9, 8)
        s.rolloverIfNeeded(now: before)
        let done = s.addTask("完成项", to: .importantUrgent, addToToday: true)!
        let pending = s.addTask("待推进项", to: .importantNotUrgent, addToToday: true)!
        s.setDailyWin(pending.id)
        s.setCompleted(done.id, true, now: before)
        XCTAssertTrue(s.rolloverIfNeeded(now: after))
        XCTAssertFalse(s.rolloverIfNeeded(now: after.addingTimeInterval(60)))
        let loaded = store(cal)
        XCTAssertEqual(loaded.archive.map(\.id), [done.id])
        XCTAssertEqual(loaded.tasks.map(\.id), [pending.id])
        XCTAssertFalse(loaded.tasks[0].isToday)
        XCTAssertNotEqual(loaded.tasks[0].isDailyWin, true)
        XCTAssertFalse(loaded.rolloverIfNeeded(now: after))
    }

    func testCalendarRolloverAcrossShortDayIsNotBasedOn24Hours() {
        let cal = calendar("America/Los_Angeles"), s = store(cal)
        let before = date(cal, 3, 8, 0, 30), after = date(cal, 3, 9, 0, 15)
        XCTAssertLessThan(after.timeIntervalSince(before), 24 * 3600)
        s.rolloverIfNeeded(now: before)
        let task = s.addTask("夏令时测试", to: .importantUrgent, addToToday: true)!
        s.setCompleted(task.id, true, now: before)
        XCTAssertTrue(s.rolloverIfNeeded(now: after))
        XCTAssertEqual(s.archive.count, 1)
    }

    func testActiveTimerAcrossMidnightKeepsSessionAndDoesNotLoseTask() {
        let cal = calendar(), s = store(cal)
        let before = date(cal, 9, 6, 23, 59), after = date(cal, 9, 7, 0, 1)
        s.rolloverIfNeeded(now: before)
        let task = s.addTask("跨日专注", to: .importantUrgent, addToToday: true)!
        XCTAssertTrue(s.startTimer(task.id, now: before))
        XCTAssertTrue(s.rolloverIfNeeded(now: after))
        XCTAssertEqual(s.activeTimerTask?.id, task.id)
        XCTAssertEqual(s.stopTimer(now: after), 2)
        XCTAssertEqual(s.tasks[0].progressSessions?.count, 1)
        XCTAssertEqual(s.tasks[0].actualMinutes, 2)
    }

    func testStoppedTimerReloadDoesNotAccumulateSleepOrDuplicateSession() {
        let cal = calendar(), s = store(cal)
        let start = date(cal, 9, 6, 22), sleep = start.addingTimeInterval(10 * 60)
        let task = s.addTask("休眠计时数据", to: .importantUrgent)!
        XCTAssertTrue(s.startTimer(task.id, now: start, targetMinutes: 5))
        XCTAssertEqual(s.stopTimer(now: sleep), 10)
        let loaded = store(cal)
        XCTAssertNil(loaded.activeTimer)
        XCTAssertNil(loaded.stopTimer(now: sleep.addingTimeInterval(8 * 3600)))
        XCTAssertEqual(loaded.tasks[0].actualMinutes, 10)
        XCTAssertEqual(loaded.tasks[0].progressSessions?.count, 1)
        XCTAssertEqual(loaded.tasks[0].progressSessions?.first?.endedAt, sleep)
    }

    func testBrowserPolicyIgnoresStaleFutureAndRepeatedContexts() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let stale = (0..<12).map { AttentionContextEvent(timestamp: now.addingTimeInterval(-600 + Double($0)), context: "hash-\($0)", kind: .browserPage) }
        let future = (0..<12).map { AttentionContextEvent(timestamp: now.addingTimeInterval(1 + Double($0)), context: "hash-\($0)", kind: .browserPage) }
        let repeated = (0..<12).map { AttentionContextEvent(timestamp: now.addingTimeInterval(-Double($0)), context: "same-hash", kind: .browserPage) }
        for events in [stale, future, repeated] {
            XCTAssertNil(AttentionDriftPolicy.evaluate(events: events, now: now, windowMinutes: 4, switchThreshold: 8))
        }
    }

    func testBrowserRapidDwellBoundary() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func events(_ gap: Double) -> [AttentionContextEvent] {
            (0..<10).map { AttentionContextEvent(timestamp: now.addingTimeInterval(Double($0 - 9) * gap), context: "hash-\($0)", kind: .browserPage) }
        }
        XCTAssertNotNil(AttentionDriftPolicy.evaluate(events: events(25), now: now, windowMinutes: 10, switchThreshold: 8))
        XCTAssertNil(AttentionDriftPolicy.evaluate(events: events(26), now: now, windowMinutes: 10, switchThreshold: 8))
    }
}
