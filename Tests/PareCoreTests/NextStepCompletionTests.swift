import XCTest
@testable import PareCore

final class NextStepCompletionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func file() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("pare-next-step-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("store.json")
    }

    func testCompletedStepsSurviveTaskAndSyncPayloadRoundTrips() throws {
        let json = Data(#"{"id":"22222222-2222-4222-8222-222222222222","text":"整理虚构研究","quadrant":1,"createdAt":"2026-11-19T08:00:00Z","isToday":false,"completed":false,"completedSteps":[{"id":"33333333-3333-4333-8333-333333333333","text":"列出三个问题","completedAt":"2026-11-19T08:05:00Z","definitionOfDone":"三个问题可供讨论"}]}"#.utf8)
        let task = try SyncWire.decoder().decode(TaskItem.self, from: json)
        let payload = try SyncPayload.task(task, archived: false)
        let (decoded, archived) = try SyncPayload.decodeTask(payload)
        let result = try XCTUnwrap(JSONSerialization.jsonObject(with: SyncWire.encoder().encode(decoded)) as? [String: Any])
        let steps = try XCTUnwrap(result["completedSteps"] as? [[String: Any]])
        XCTAssertFalse(archived)
        XCTAssertEqual(steps.count, 1)
        XCTAssertEqual((steps[0]["id"] as? String)?.lowercased(), "33333333-3333-4333-8333-333333333333")
        XCTAssertEqual(steps[0]["text"] as? String, "列出三个问题")
        XCTAssertEqual(steps[0]["definitionOfDone"] as? String, "三个问题可供讨论")
        XCTAssertEqual(decoded, task)
    }

    func testLegacyTaskWithoutHistoryAndStepWithoutCriterionDecode() throws {
        let json = Data(#"{"id":"22222222-2222-4222-8222-222222222222","text":"旧任务","quadrant":1,"createdAt":"2026-11-19T08:00:00Z","isToday":false,"completed":false}"#.utf8)
        let task = try SyncWire.decoder().decode(TaskItem.self, from: json)
        XCTAssertNil(task.completedSteps)
        XCTAssertNil(try SyncPayload.decodeTask(SyncPayload.task(task, archived: false)).0.completedSteps)
        let step = try SyncWire.decoder().decode(CompletedStep.self, from: Data(#"{"id":"33333333-3333-4333-8333-333333333333","text":"旧步骤","completedAt":"2026-11-19T08:05:00Z"}"#.utf8))
        XCTAssertNil(step.definitionOfDone)
    }

    private func addTask(to store: Store) throws -> UUID {
        let task = try XCTUnwrap(store.addTask("虚构研究", to: .importantNotUrgent, now: now))
        store.setMomentumDetails(task.id, nextAction: "列出三个问题", definitionOfDone: "三个问题可供讨论", now: now)
        return task.id
    }

    private func complete(_ store: Store, _ id: UUID, at date: Date? = nil) -> CompletedStep? {
        store.completeNextAction(id, expectedNextAction: "列出三个问题",
                                 expectedDefinitionOfDone: "三个问题可供讨论", now: date ?? now)
    }

    func testCompletionClearsCurrentStepAndTwoStepsPersistAcrossRestart() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let first = try XCTUnwrap(store.completeNextAction(id, expectedNextAction: " \n列出三个问题 ",
            expectedDefinitionOfDone: " 三个问题可供讨论\n", now: now.addingTimeInterval(60)))
        XCTAssertEqual(first.text, "列出三个问题")
        XCTAssertEqual(first.definitionOfDone, "三个问题可供讨论")
        XCTAssertEqual(first.completedAt, now.addingTimeInterval(60))
        XCTAssertNil(store.tasks[0].nextAction)
        XCTAssertNil(store.tasks[0].definitionOfDone)
        XCTAssertFalse(store.tasks[0].completed)
        XCTAssertNil(store.tasks[0].completedAt)
        let reloaded = Store(fileURL: url)
        XCTAssertEqual(reloaded.tasks[0].completedSteps, [first])
        XCTAssertNil(reloaded.tasks[0].nextAction)
        XCTAssertEqual(reloaded.tasks[0].lastProgressAt, now.addingTimeInterval(60))

        XCTAssertTrue(reloaded.updateNextAction(id, nextAction: " 比较两个选项 ", definitionOfDone: "\n",
            expectedNextAction: nil, expectedDefinitionOfDone: nil, expectedCompletedStepID: first.id,
            now: now.addingTimeInterval(120)))
        let second = try XCTUnwrap(reloaded.completeNextAction(id, expectedNextAction: "比较两个选项",
            expectedDefinitionOfDone: " ", expectedCompletedStepID: first.id, now: now.addingTimeInterval(180)))
        XCTAssertNil(second.definitionOfDone)
        XCTAssertNotEqual(first.id, second.id)
        let saved = Store(fileURL: url).tasks[0]
        XCTAssertEqual(saved.completedSteps, [first, second])
        XCTAssertNil(saved.nextAction)
        XCTAssertEqual(saved.lastProgressAt, now.addingTimeInterval(180))
        XCTAssertFalse(saved.completed)
    }

    func testCompletionRejectsMissingEmptyCompletedStaleAndRepeatedRequestsWithoutSaving() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let original = store.tasks, originalData = try Data(contentsOf: url)
        XCTAssertNil(complete(store, UUID()))
        XCTAssertNil(store.completeNextAction(id, expectedNextAction: " ", expectedDefinitionOfDone: nil, now: now))
        XCTAssertNil(store.completeNextAction(id, expectedNextAction: "旧步骤", expectedDefinitionOfDone: "三个问题可供讨论", now: now))
        XCTAssertNil(store.completeNextAction(id, expectedNextAction: "列出三个问题", expectedDefinitionOfDone: nil, now: now))
        XCTAssertEqual(store.tasks, original)
        XCTAssertEqual(try Data(contentsOf: url), originalData)

        store.setCompleted(id, true, now: now)
        let completed = store.tasks
        XCTAssertNil(complete(store, id))
        XCTAssertEqual(store.tasks, completed)
        store.setCompleted(id, false, now: now)
        XCTAssertNotNil(complete(store, id))
        let after = store.tasks, afterData = try Data(contentsOf: url)
        XCTAssertNil(complete(store, id, at: now.addingTimeInterval(1)))
        XCTAssertEqual(store.tasks, after)
        XCTAssertEqual(try Data(contentsOf: url), afterData)
    }

    func testUndoOnlyRestoresLatestStepAndPersists() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let first = try XCTUnwrap(complete(store, id))
        store.setMomentumDetails(id, nextAction: "比较两个选项", definitionOfDone: nil, now: now)
        let second = try XCTUnwrap(store.completeNextAction(id, expectedNextAction: "比较两个选项",
            expectedDefinitionOfDone: nil, expectedCompletedStepID: first.id, now: now))
        let before = store.tasks
        XCTAssertFalse(store.undoCompletedNextAction(id, stepID: first.id, now: now))
        XCTAssertFalse(store.undoCompletedNextAction(UUID(), stepID: second.id, now: now))
        XCTAssertEqual(store.tasks, before)
        XCTAssertTrue(store.undoCompletedNextAction(id, stepID: second.id, now: now.addingTimeInterval(60)))
        let saved = Store(fileURL: url).tasks[0]
        XCTAssertEqual(saved.nextAction, "比较两个选项")
        XCTAssertNil(saved.definitionOfDone)
        XCTAssertEqual(saved.completedSteps, [first])
        XCTAssertEqual(saved.lastProgressAt, now.addingTimeInterval(60))
        XCTAssertFalse(store.undoCompletedNextAction(id, stepID: second.id, now: now))
    }

    func testUndoDoesNotOverwriteNewActionOrCriterionOrCompletedTask() throws {
        let store = Store(fileURL: try file()), id = try addTask(to: store)
        let step = try XCTUnwrap(complete(store, id))
        for fields: (String?, String?) in [("后续行动", nil), (nil, "后来补的标准")] {
            store.setMomentumDetails(id, nextAction: fields.0, definitionOfDone: fields.1, now: now)
            let before = store.tasks
            XCTAssertFalse(store.undoCompletedNextAction(id, stepID: step.id, now: now))
            XCTAssertEqual(store.tasks, before)
        }
        store.setMomentumDetails(id, nextAction: nil, definitionOfDone: nil, now: now)
        store.setCompleted(id, true, now: now)
        XCTAssertFalse(store.undoCompletedNextAction(id, stepID: step.id, now: now))
        store.setCompleted(id, false, now: now)
        XCTAssertTrue(store.undoCompletedNextAction(id, stepID: step.id, now: now))
        XCTAssertEqual(store.tasks[0].nextAction, "列出三个问题")
        XCTAssertEqual(store.tasks[0].definitionOfDone, "三个问题可供讨论")
        XCTAssertTrue(store.tasks[0].completedSteps?.isEmpty == true)
    }

    func testCompletionAndUndoPreserveParentPlanReminderEstimateAndActiveTimer() throws {
        let store = Store(fileURL: try file()), id = try addTask(to: store)
        store.setDailyWin(id, now: now)
        store.scheduleTask(id, at: now.addingTimeInterval(600), durationMinutes: 45, now: now)
        store.setRemind(id, at: now.addingTimeInterval(1200))
        store.setActual(id, minutes: 12, now: now)
        XCTAssertTrue(store.startTimer(id, now: now, targetMinutes: 5))
        let original = store.tasks[0], timer = store.activeTimer, plans = store.syncDayPlans
        let step = try XCTUnwrap(complete(store, id, at: now.addingTimeInterval(90)))
        var expected = original
        expected.nextAction = nil; expected.definitionOfDone = nil
        expected.completedSteps = [step]; expected.lastProgressAt = now.addingTimeInterval(90)
        XCTAssertEqual(store.tasks[0], expected)
        XCTAssertEqual(store.activeTimer, timer)
        XCTAssertEqual(store.syncDayPlans, plans)
        XCTAssertTrue(store.undoCompletedNextAction(id, stepID: step.id, now: now.addingTimeInterval(120)))
        expected = original; expected.completedSteps = []; expected.lastProgressAt = now.addingTimeInterval(120)
        XCTAssertEqual(store.tasks[0], expected)
        XCTAssertEqual(store.activeTimer, timer)
        XCTAssertEqual(store.syncDayPlans, plans)
    }

    func testCompletionAndUndoKeepParallelRunAndCheckTime() throws {
        let store = Store(fileURL: try file()), id = try addTask(to: store)
        XCTAssertTrue(store.startParallelRun(id, now: now, nextCheckAt: now.addingTimeInterval(300)))
        let runs = store.parallelRuns
        let step = try XCTUnwrap(complete(store, id))
        XCTAssertEqual(store.parallelRuns, runs)
        XCTAssertNil(store.tasks[0].parallelRunSessions)
        XCTAssertTrue(store.undoCompletedNextAction(id, stepID: step.id, now: now))
        XCTAssertEqual(store.parallelRuns, runs)
    }

    func testCompletionFailureRollsBackTaskSyncQueueAndPlans() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        try store.bindSyncAccount(accountID: "fictional-next-step-owner")
        let original = store.tasks, sync = store.syncState, plans = store.syncDayPlans
        let originalData = try Data(contentsOf: url)
        try FileManager.default.removeItem(at: url.appendingPathExtension("bak"))
        try FileManager.default.createDirectory(at: url.appendingPathExtension("bak"), withIntermediateDirectories: false)
        XCTAssertNil(complete(store, id))
        XCTAssertEqual(store.tasks, original)
        XCTAssertEqual(store.syncState, sync)
        XCTAssertEqual(store.syncDayPlans, plans)
        XCTAssertEqual(try Data(contentsOf: url), originalData)
        XCTAssertNotNil(store.persistenceIssue)
        XCTAssertTrue(store.prepareForTermination(now: now))
        try FileManager.default.removeItem(at: url.appendingPathExtension("bak"))
        XCTAssertNotNil(complete(store, id))
        XCTAssertNil(store.persistenceIssue)
    }

    func testUndoFailureDoesNotReportSuccessOrRestoreUnpersistedStep() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store), step = try XCTUnwrap(complete(store, id))
        let before = store.tasks
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        XCTAssertFalse(store.undoCompletedNextAction(id, stepID: step.id, now: now))
        XCTAssertEqual(store.tasks, before)
        XCTAssertNotNil(store.persistenceIssue)
        try FileManager.default.removeItem(at: url)
        XCTAssertTrue(store.undoCompletedNextAction(id, stepID: step.id, now: now))
        XCTAssertEqual(Store(fileURL: url).tasks[0].nextAction, "列出三个问题")
    }

    func testUpdateNextActionRejectsStaleDraftAndPersistsOnlyStepFields() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store), step = try XCTUnwrap(complete(store, id))
        let before = store.tasks[0]
        XCTAssertFalse(store.updateNextAction(id, nextAction: "过时草稿", definitionOfDone: nil,
            expectedNextAction: "列出三个问题", expectedDefinitionOfDone: "三个问题可供讨论",
            expectedCompletedStepID: step.id, now: now))
        XCTAssertTrue(store.updateNextAction(id, nextAction: " 下一步行动 ", definitionOfDone: " 一段结论 ",
            expectedNextAction: nil, expectedDefinitionOfDone: nil, expectedCompletedStepID: step.id,
            now: now.addingTimeInterval(60)))
        var expected = before
        expected.nextAction = "下一步行动"; expected.definitionOfDone = "一段结论"
        expected.lastProgressAt = now.addingTimeInterval(60)
        XCTAssertEqual(store.tasks[0], expected)
        XCTAssertEqual(Store(fileURL: url).tasks[0], expected)
        XCTAssertEqual(store.tasks[0].completedSteps, [step])
        XCTAssertFalse(store.updateNextAction(id, nextAction: "另一草稿", definitionOfDone: nil,
            expectedNextAction: "下一步行动", expectedDefinitionOfDone: "过时标准", expectedCompletedStepID: step.id, now: now))
        XCTAssertTrue(store.updateNextAction(id, nextAction: " \n", definitionOfDone: "不可孤立的标准",
            expectedNextAction: " 下一步行动 ", expectedDefinitionOfDone: " 一段结论 ", expectedCompletedStepID: step.id, now: now))
        XCTAssertNil(store.tasks[0].nextAction)
        XCTAssertNil(store.tasks[0].definitionOfDone)
        store.setCompleted(id, true, now: now)
        XCTAssertFalse(store.updateNextAction(id, nextAction: "已完成", definitionOfDone: nil,
            expectedNextAction: nil, expectedDefinitionOfDone: nil, expectedCompletedStepID: step.id, now: now))
        XCTAssertFalse(store.updateNextAction(UUID(), nextAction: "不存在", definitionOfDone: nil,
            expectedNextAction: nil, expectedDefinitionOfDone: nil, now: now))
    }

    func testUpdateFailurePreservesCurrentDraftAndHistory() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let before = store.tasks
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        XCTAssertFalse(store.updateNextAction(id, nextAction: "新草稿", definitionOfDone: "新标准",
            expectedNextAction: "列出三个问题", expectedDefinitionOfDone: "三个问题可供讨论", now: now))
        XCTAssertEqual(store.tasks, before)
        XCTAssertNotNil(store.persistenceIssue)
    }

    func testTaskDetailSaveFailurePreservesSuccessorAndDailyWinNeighbors() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        _ = try XCTUnwrap(complete(store, id))
        store.setMomentumDetails(id, nextAction: "后来确定的下一步", definitionOfDone: "后续标准", now: now)
        let neighbor = try XCTUnwrap(store.addTask("已有今日重点", to: .importantUrgent, now: now))
        store.setDailyWin(neighbor.id, now: now)
        try store.bindSyncAccount(accountID: "fictional-detail-editor")
        let before = store.tasks, sync = store.syncState, plans = store.syncDayPlans
        let previousData = try Data(contentsOf: url)
        XCTAssertFalse(store.updateTaskDetails(UUID(), text: "不存在", note: nil, nextAction: nil,
            dueAt: nil, remindAt: nil, recurrence: nil, now: now))
        XCTAssertFalse(store.updateTaskDetails(id, text: " \n", note: nil, nextAction: nil,
            dueAt: nil, remindAt: nil, recurrence: nil, now: now))
        let backup = url.appendingPathExtension("bak")
        try FileManager.default.removeItem(at: backup)
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)

        XCTAssertFalse(store.updateTaskDetails(id, text: "编辑后的任务", note: "新备注", nextAction: "未保存的新草稿",
            dueAt: now.addingTimeInterval(3600), remindAt: now.addingTimeInterval(600), recurrence: .daily,
            definitionOfDone: "未保存的新标准", isDailyWin: true, now: now.addingTimeInterval(60)))

        XCTAssertEqual(store.tasks, before)
        XCTAssertEqual(store.syncState, sync)
        XCTAssertEqual(store.syncDayPlans, plans)
        XCTAssertEqual(try Data(contentsOf: url), previousData)
        XCTAssertNotNil(store.persistenceIssue)
        XCTAssertTrue(store.prepareForTermination(now: now))
        try FileManager.default.removeItem(at: backup)
        XCTAssertTrue(store.updateTaskDetails(id, text: "编辑后的任务", note: "新备注", nextAction: "保存的新草稿",
            dueAt: nil, remindAt: nil, recurrence: nil, definitionOfDone: "保存的新标准",
            isDailyWin: true, now: now.addingTimeInterval(60)))
        XCTAssertEqual(Store(fileURL: url).tasks, store.tasks)
        XCTAssertEqual(store.dailyWinTask?.id, id)
        XCTAssertEqual(store.tasks.first { $0.id == neighbor.id }?.isDailyWin, false)
    }

    func testOldCompletionCannotConsumeRecreatedSameTextStep() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let first = try XCTUnwrap(complete(store, id))
        store.setMomentumDetails(id, nextAction: "列出三个问题", definitionOfDone: "三个问题可供讨论", now: now)
        let before = store.tasks, data = try Data(contentsOf: url)

        XCTAssertNil(complete(store, id))

        XCTAssertEqual(store.tasks, before)
        XCTAssertEqual(try Data(contentsOf: url), data)
        XCTAssertNil(store.completeNextAction(id, expectedNextAction: "列出三个问题",
            expectedDefinitionOfDone: "三个问题可供讨论", expectedCompletedStepID: UUID(), now: now))
        XCTAssertNotNil(store.completeNextAction(id, expectedNextAction: "列出三个问题",
            expectedDefinitionOfDone: "三个问题可供讨论", expectedCompletedStepID: first.id, now: now))
    }

    func testOldEditorCannotOverwriteRecreatedSameTextStep() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let first = try XCTUnwrap(complete(store, id))
        store.setMomentumDetails(id, nextAction: "列出三个问题", definitionOfDone: "三个问题可供讨论", now: now)
        let before = store.tasks, data = try Data(contentsOf: url)

        XCTAssertFalse(store.updateNextAction(id, nextAction: "旧编辑器的新草稿", definitionOfDone: nil,
            expectedNextAction: "列出三个问题", expectedDefinitionOfDone: "三个问题可供讨论", now: now))

        XCTAssertEqual(store.tasks, before)
        XCTAssertEqual(try Data(contentsOf: url), data)
        XCTAssertFalse(store.updateNextAction(id, nextAction: "新草稿", definitionOfDone: nil,
            expectedNextAction: "列出三个问题", expectedDefinitionOfDone: "三个问题可供讨论",
            expectedCompletedStepID: UUID(), now: now))
        XCTAssertTrue(store.updateNextAction(id, nextAction: "新草稿", definitionOfDone: nil,
            expectedNextAction: "列出三个问题", expectedDefinitionOfDone: "三个问题可供讨论",
            expectedCompletedStepID: first.id, now: now))
    }

    func testTaskDetailFailurePreservesPreviouslyUnsavedChanges() throws {
        let url = try file(), store = Store(fileURL: url)
        let id = try addTask(to: store)
        let backup = url.appendingPathExtension("bak")
        try FileManager.default.removeItem(at: backup)
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)
        store.setEstimate(id, minutes: 17)
        let pending = store.tasks

        XCTAssertFalse(store.updateTaskDetails(id, text: "未保存的标题", note: nil, nextAction: "未保存的下一步",
            dueAt: nil, remindAt: nil, recurrence: nil, now: now))

        XCTAssertEqual(store.tasks, pending)
        XCTAssertFalse(store.prepareForTermination(now: now))
        try FileManager.default.removeItem(at: backup)
        XCTAssertTrue(store.retrySaving())
        XCTAssertEqual(Store(fileURL: url).tasks, pending)
    }
}
