import XCTest
@testable import PareCore

final class AIHistoryTests: XCTestCase {
    private var calendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        return cal
    }
    private func date(_ day: Int, hour: Int = 12) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour))!
    }
    private func makeStore() -> Store {
        Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("pare-memory-test-\(UUID()).json"), calendar: calendar)
    }
    private func draft(_ task: TaskItem, summary: String = "先完成五分钟的小步骤") -> AIDraft {
        AIDraft(summary: summary, suggestions: [AISuggestion(sourceID: task.id, title: task.text,
            nextAction: "写三个标题", definitionOfDone: "留下提纲", estimatedMinutes: 5)])
    }
    private func request(_ task: TaskItem) -> AIRequest {
        AIRequest(workflow: .nextStep, inputs: [AIInput(task: task)])
    }

    func testExistingFilesWithoutMemoryLoadWithEmptyDefaults() throws {
        let store = makeStore()
        store.addTask("原任务", to: .importantUrgent)
        var snapshot = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: store.dataFileURL)) as? [String: Any])
        snapshot.removeValue(forKey: "aiSummaries")
        snapshot.removeValue(forKey: "aiBackground")
        try JSONSerialization.data(withJSONObject: snapshot).write(to: store.dataFileURL)
        let reloaded = Store(fileURL: store.dataFileURL, calendar: calendar)
        XCTAssertEqual(reloaded.tasks.count, 1)
        XCTAssertTrue(reloaded.aiSummaries.isEmpty)
        XCTAssertEqual(reloaded.aiBackground, "")
    }

    func testHistoryIsOmittedUntilExplicitlyAttached() throws {
        let store = makeStore()
        let task = store.addTask("当前任务", to: .importantUrgent)!
        store.setAIBackground("背景内容")
        let data = try request(task).data()
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNil(object["history"])
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("背景内容"))
    }

    func testSummaryIncludesCompletedArchiveAndOnlyRecordedSessionsInPeriod() {
        let store = makeStore()
        store.rolloverIfNeeded(now: date(1))
        let task = store.addTask("已归档", to: .importantNotUrgent, estimatedMinutes: 20, now: date(1))!
        store.startTimer(task.id, now: date(2))
        store.stopTimer(now: date(2).addingTimeInterval(10 * 60))
        store.recordBlocker(task.id, reason: .tooLarge, now: date(2))
        store.setCompleted(task.id, true, now: date(2).addingTimeInterval(20 * 60))
        store.rolloverIfNeeded(now: date(3))
        let onlyEstimated = store.addTask("只估时未计时", to: .importantUrgent, estimatedMinutes: 60)!
        store.setCompleted(onlyEstimated.id, true, now: date(3))
        let context = store.aiHistoryContext(selectedTaskIDs: [task.id], days: 7, now: date(5))
        XCTAssertEqual(context.days.reduce(0) { $0 + $1.completedCount }, 2)
        XCTAssertEqual(context.days.reduce(0) { $0 + $1.recordedMinutes }, 10)
        XCTAssertEqual(context.comparableCompletedTaskCount, 1)
        XCTAssertEqual(context.estimatedMinutesForComparableTasks, 20)
        XCTAssertEqual(context.actualMinutesForComparableTasks, 10)
        XCTAssertEqual(context.selectedTaskHistory.first?.blockersInPeriod["任务太大"], 1)
    }

    func testOldAndFutureEventsDoNotLeakIntoTheWindow() {
        let store = makeStore()
        let task = store.addTask("任务", to: .importantUrgent)!
        for day in [1, 5, 20] {
            store.startTimer(task.id, now: date(day))
            store.stopTimer(now: date(day).addingTimeInterval(60))
            store.recordBlocker(task.id, reason: .unclear, now: date(day))
        }
        let context = store.aiHistoryContext(selectedTaskIDs: [task.id], days: 7, now: date(10))
        XCTAssertEqual(context.days.reduce(0) { $0 + $1.recordedSessionCount }, 1)
        XCTAssertEqual(context.selectedTaskHistory.first?.sessionCountInPeriod, 1)
        XCTAssertNil(context.selectedTaskHistory.first?.lastRecordedChangeAt)
        XCTAssertEqual(context.days.count, 7)
        XCTAssertEqual(store.aiHistoryContext(selectedTaskIDs: [], days: 999, now: date(10)).days.count, 30)
    }

    func testUnselectedTitlesNotesBackgroundAndAnalysesAreExcluded() throws {
        let store = makeStore()
        let a = store.addTask("选中任务", to: .importantUrgent)!
        let b = store.addTask("另一任务私密标题", to: .importantNotUrgent)!
        store.setAIBackground("长期私密背景")
        try store.recordAISummary(draft(b, summary: "另一任务的分析"), request: request(b), now: date(3))
        let context = store.aiHistoryContext(selectedTaskIDs: [a.id], now: date(5))
        let text = String(decoding: try JSONEncoder().encode(context), as: UTF8.self)
        XCTAssertFalse(text.contains("另一任务私密标题"))
        XCTAssertFalse(text.contains("另一任务的分析"))
        XCTAssertFalse(text.contains("长期私密背景"))
        XCTAssertEqual(context.selectedTaskHistory.map(\.id), [a.id])
        XCTAssertTrue(context.previousAnalyses.isEmpty)
    }

    func testUserChoosesSummariesAndOutcomeIsNotTreatedAsCompletion() throws {
        let store = makeStore()
        let task = store.addTask("任务", to: .importantUrgent)!
        let id = try XCTUnwrap(store.recordAISummary(draft(task), request: request(task), now: date(3)))
        store.updateAISummaryOutcome(id, outcome: .filledDraft, adoptedCount: 1)
        store.setAIBackground("本月先完成报告")
        let context = store.aiHistoryContext(selectedTaskIDs: [task.id], includeBackground: true,
                                             summaryIDs: [id], now: date(5))
        XCTAssertEqual(context.userBackground, "本月先完成报告")
        XCTAssertEqual(context.previousAnalyses.count, 1)
        XCTAssertTrue(context.previousAnalyses[0].outcome.contains("尚不代表保存或执行"))
        XCTAssertTrue(context.previousAnalyses[0].source.contains("不是用户已确认的事实"))
        XCTAssertEqual(context.days.reduce(0) { $0 + $1.completedCount }, 0)
    }

    func testBackgroundAndSummaryOutcomePersistAndCanBeDeleted() throws {
        let store = makeStore()
        let task = store.addTask("任务", to: .importantUrgent)!
        store.setAIBackground("背景")
        let id = try XCTUnwrap(store.recordAISummary(draft(task), request: request(task)))
        store.updateAISummaryOutcome(id, outcome: .discarded)
        let reloaded = Store(fileURL: store.dataFileURL, calendar: calendar)
        XCTAssertEqual(reloaded.aiBackground, "背景")
        XCTAssertEqual(reloaded.aiSummaries.first?.outcome, .discarded)
        reloaded.deleteAISummary(id)
        XCTAssertTrue(Store(fileURL: store.dataFileURL, calendar: calendar).aiSummaries.isEmpty)
        XCTAssertEqual(reloaded.tasks.count, 1)
    }

    func testSummaryRetentionAndRequestContextAreBounded() throws {
        let store = makeStore()
        let task = store.addTask("任务", to: .importantUrgent)!
        store.setAIBackground(String(repeating: "字", count: 1700))
        for index in 0..<55 {
            try store.recordAISummary(draft(task, summary: "总结\(index)"), request: request(task),
                                       now: date(3).addingTimeInterval(Double(index)))
        }
        XCTAssertEqual(store.aiSummaries.count, 50)
        XCTAssertEqual(store.aiBackground.count, 1500)
        let context = store.aiHistoryContext(selectedTaskIDs: [task.id],
            summaryIDs: Set(store.aiSummaries.map(\.id)), now: date(5))
        XCTAssertEqual(context.previousAnalyses.count, 3)
        XCTAssertEqual(context.previousAnalyses.first?.summary, "总结54")
    }

    func testSavingAnAISummaryDoesNotInvalidateTaskAdoption() throws {
        let store = makeStore()
        let task = store.addTask("任务", to: .importantUrgent)!
        let draft = draft(task)
        try store.recordAISummary(draft, request: request(task))
        try store.applyAIDraft(draft, request: request(task), selectedIDs: [task.id],
                               originalTasks: [task], originalInbox: [])
        XCTAssertEqual(store.tasks.first?.nextAction, "写三个标题")
    }
}
