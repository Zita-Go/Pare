import XCTest
@testable import PareCore

/// RFC-0001 T5: 预估 ↔ 实际校准的偏差计算单测。
///
/// 聚焦「偏差计算在 PareCore 有单测」这条验收：直接喂固定数据给 `Store.estimateBias`
/// 与 `todaySummary` 的 bias，断言低估 / 高估 / 估得准 / 无数据 / 部分可比 / 取整等分支；
/// 并覆盖 `setActual` 的记录 / 修改 / 清除（对应「完成后能记录/跳过/修改实际用时」），
/// 以及「未记录时不显示误导数字」在数据层的保证（`bias.hasData == false`）。
final class CalibrationTests: XCTestCase {

    // MARK: - Fixtures

    private func fixedCalendar() -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return cal
    }

    private func makeStore() -> (Store, URL) {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-calib-test-\(UUID().uuidString).json")
        return (Store(fileURL: tmp, calendar: fixedCalendar()), tmp)
    }

    private func date(_ cal: Calendar, _ y: Int, _ mo: Int, _ d: Int,
                      _ h: Int = 12, _ mi: Int = 0) -> Date {
        var c = DateComponents()
        c.year = y; c.month = mo; c.day = d; c.hour = h; c.minute = mi
        return cal.date(from: c)!
    }

    /// A bare task carrying just the estimate/actual pair the bias math reads.
    private func task(est: Int?, act: Int?) -> TaskItem {
        TaskItem(text: "t", quadrant: .importantUrgent, estimatedMinutes: est, actualMinutes: act)
    }

    // MARK: - estimateBias: direction & magnitude

    func testEstimateBiasUnderestimate() {
        // actual > estimate → underestimated (positive delta).
        let bias = Store.estimateBias(for: [task(est: 30, act: 50), task(est: 60, act: 70)])
        XCTAssertTrue(bias.hasData)
        XCTAssertEqual(bias.comparableCount, 2)
        XCTAssertEqual(bias.totalEstimated, 90)
        XCTAssertEqual(bias.totalActual, 120)
        XCTAssertEqual(bias.deltaMinutes, 30, "actual - estimate")
        XCTAssertEqual(bias.averageDeltaMinutes, 15, "30 / 2")
    }

    func testEstimateBiasOverestimate() {
        // actual < estimate → overestimated (negative delta).
        let bias = Store.estimateBias(for: [task(est: 60, act: 20), task(est: 40, act: 30)])
        XCTAssertEqual(bias.comparableCount, 2)
        XCTAssertEqual(bias.deltaMinutes, -50)
        XCTAssertEqual(bias.averageDeltaMinutes, -25)
    }

    func testEstimateBiasExact() {
        let bias = Store.estimateBias(for: [task(est: 40, act: 40)])
        XCTAssertTrue(bias.hasData)
        XCTAssertEqual(bias.deltaMinutes, 0)
        XCTAssertEqual(bias.averageDeltaMinutes, 0)
    }

    // MARK: - estimateBias: comparability gating ("未记录时不显示误导数字")

    func testEstimateBiasNoComparableDataHasNone() {
        // Only-estimate, only-actual, and neither must all be excluded → no data.
        let bias = Store.estimateBias(for: [
            task(est: 30, act: nil),   // estimate only
            task(est: nil, act: 45),   // actual only
            task(est: nil, act: nil),  // neither
        ])
        XCTAssertFalse(bias.hasData, "nothing has BOTH estimate and actual")
        XCTAssertEqual(bias.comparableCount, 0)
        XCTAssertEqual(bias.totalEstimated, 0)
        XCTAssertEqual(bias.totalActual, 0)
        XCTAssertEqual(bias.deltaMinutes, 0)
        XCTAssertEqual(bias.averageDeltaMinutes, 0, "no divide-by-zero, no misleading number")
    }

    func testEstimateBiasCountsOnlyComparablePairs() {
        // A mix: only the two est+act pairs count; the rest are ignored.
        let bias = Store.estimateBias(for: [
            task(est: 30, act: 50),    // counts
            task(est: 20, act: nil),   // ignored (no actual)
            task(est: nil, act: 90),   // ignored (no estimate)
            task(est: 15, act: 15),    // counts
        ])
        XCTAssertEqual(bias.comparableCount, 2)
        XCTAssertEqual(bias.totalEstimated, 45, "30 + 15, ignoring the incomparable ones")
        XCTAssertEqual(bias.totalActual, 65, "50 + 15")
        XCTAssertEqual(bias.deltaMinutes, 20)
    }

    // MARK: - estimateBias: rounding of the per-task average

    func testAverageDeltaRoundsToNearest() {
        // Total delta +2 over 3 tasks → 0.67 → rounds to 1.
        let bias = Store.estimateBias(for: [task(est: 30, act: 30), task(est: 30, act: 31), task(est: 30, act: 31)])
        XCTAssertEqual(bias.deltaMinutes, 2)
        XCTAssertEqual(bias.averageDeltaMinutes, 1)
    }

    func testAverageDeltaCanRoundToZeroWhileDeltaNonZero() {
        // Total delta +1 over 3 tasks → 0.33 → rounds to 0, though there IS a delta.
        let bias = Store.estimateBias(for: [task(est: 30, act: 30), task(est: 30, act: 30), task(est: 30, act: 31)])
        XCTAssertTrue(bias.hasData)
        XCTAssertEqual(bias.deltaMinutes, 1)
        XCTAssertEqual(bias.averageDeltaMinutes, 0, "sub-minute average rounds to 0 but delta stays 1")
    }

    // MARK: - setActual: record / modify / clear ("完成后能记录/跳过/修改实际用时")

    func testSetActualRecordModifyAndClear() {
        let (store, url) = makeStore()
        defer { try? FileManager.default.removeItem(at: url) }
        let cal = store.calendar
        store.addTask("写周报", to: .importantUrgent, estimatedMinutes: 25, now: date(cal, 2026, 1, 15, 9))
        let id = store.tasks[0].id

        // Record.
        let t1 = date(cal, 2026, 1, 15, 10)
        store.setActual(id, minutes: 30, now: t1)
        XCTAssertEqual(store.tasks[0].actualMinutes, 30)
        XCTAssertEqual(store.tasks[0].lastProgressAt, t1, "recording is a progress action")

        // Modify.
        store.setActual(id, minutes: 45, now: date(cal, 2026, 1, 15, 11))
        XCTAssertEqual(store.tasks[0].actualMinutes, 45)

        // Clear via nil and via 0.
        store.setActual(id, minutes: nil, now: t1)
        XCTAssertNil(store.tasks[0].actualMinutes, "nil clears")
        store.setActual(id, minutes: 20, now: t1)
        store.setActual(id, minutes: 0, now: t1)
        XCTAssertNil(store.tasks[0].actualMinutes, "0 clears")
    }

    // MARK: - todaySummary.bias feeds the "偏差一瞥" without misleading empties

    func testTodaySummaryBiasReflectsRecordedActuals() {
        let (store, url) = makeStore()
        defer { try? FileManager.default.removeItem(at: url) }
        let cal = store.calendar
        let now = date(cal, 2026, 1, 15, 18)

        // Two completed today with est+actual; one completed today WITHOUT an
        // actual (must not pollute the comparison).
        store.addTask("A", to: .importantUrgent, estimatedMinutes: 30, now: date(cal, 2026, 1, 15, 9))
        let a = store.tasks.first { $0.text == "A" }!.id
        store.setCompleted(a, true, now: date(cal, 2026, 1, 15, 10))
        store.setActual(a, minutes: 45, now: date(cal, 2026, 1, 15, 10)) // +15

        store.addTask("B", to: .importantNotUrgent, estimatedMinutes: 60, now: date(cal, 2026, 1, 15, 9))
        let b = store.tasks.first { $0.text == "B" }!.id
        store.setCompleted(b, true, now: date(cal, 2026, 1, 15, 11))
        store.setActual(b, minutes: 75, now: date(cal, 2026, 1, 15, 11)) // +15

        store.addTask("C", to: .importantUrgent, estimatedMinutes: 20, now: date(cal, 2026, 1, 15, 9))
        let c = store.tasks.first { $0.text == "C" }!.id
        store.setCompleted(c, true, now: date(cal, 2026, 1, 15, 12)) // no actual

        let bias = store.todaySummary(now: now).bias
        XCTAssertEqual(bias.comparableCount, 2, "only A and B recorded both")
        XCTAssertEqual(bias.totalEstimated, 90)
        XCTAssertEqual(bias.totalActual, 120)
        XCTAssertEqual(bias.deltaMinutes, 30)
        XCTAssertEqual(bias.averageDeltaMinutes, 15)
    }

    func testTodaySummaryBiasHasNoDataWhenNothingRecorded() {
        let (store, url) = makeStore()
        defer { try? FileManager.default.removeItem(at: url) }
        let cal = store.calendar
        // Completed today but with only an estimate — no actual recorded.
        store.addTask("只估不记", to: .importantUrgent, estimatedMinutes: 30, now: date(cal, 2026, 1, 15, 9))
        let id = store.tasks[0].id
        store.setCompleted(id, true, now: date(cal, 2026, 1, 15, 10))

        let bias = store.todaySummary(now: date(cal, 2026, 1, 15, 18)).bias
        XCTAssertFalse(bias.hasData, "no actual → nothing comparable → summary shows no bias number")
    }
}
