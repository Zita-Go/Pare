import XCTest
@testable import PareCore

final class PlanningAgentTests: XCTestCase {
    private func store() -> Store {
        Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("pare-agent-test-\(UUID()).json"))
    }
    private func draft(_ task: TaskItem) -> AIDraft {
        AIDraft(summary: "先写一个小步骤", suggestions: [AISuggestion(sourceID: task.id,
            title: task.text, nextAction: "写三个标题", definitionOfDone: "有一份提纲", isKeyTask: true)])
    }
    private func goal(in store: Store, task: TaskItem, title: String = "长期目标") -> LongTermGoal {
        let id = store.addGoal(title)!
        var goal = store.goals.first { $0.id == id }!
        goal.taskIDs = [task.id]
        store.updateGoal(goal)
        return store.goals.first { $0.id == id }!
    }

    func testGoalsPreferencesAndLinkedProgressPersistWithoutAutoCompletingGoal() throws {
        let store = store()
        let task = store.addTask("任务", to: .importantUrgent)!
        let goal = goal(in: store, task: task)
        var preferences = AgentPreferences(); preferences.planningMinutes = 45; preferences.notes = "少给选项"
        store.updateAgentPreferences(preferences)
        store.setCompleted(task.id, true)
        let reloaded = Store(fileURL: store.dataFileURL)
        let context = reloaded.planningAgentContext(goalIDs: [goal.id], taskIDs: [task.id])
        XCTAssertEqual(context.goals[0].completedLinkedTaskCount, 1)
        XCTAssertEqual(context.goals[0].goal.status, .active)
        XCTAssertEqual(context.preferences.notes, "少给选项")
        XCTAssertEqual(context.preferences.planningMinutes, 45)
    }

    func testDeletedLinkedTaskDoesNotFalselyCountAsComplete() {
        let store = store(); let task = store.addTask("任务", to: .importantUrgent)!
        let goal = goal(in: store, task: task)
        store.deleteTask(task.id)
        let context = store.planningAgentContext(goalIDs: [goal.id], taskIDs: [])
        XCTAssertEqual(context.goals[0].missingLinkedTaskCount, 1)
        XCTAssertEqual(context.goals[0].completedLinkedTaskCount, 0)
    }

    func testUnselectedGoalsAreExcludedAndOptOutOmitsAllAgentContext() throws {
        let store = store(); let task = store.addTask("任务", to: .importantUrgent)!
        let chosen = goal(in: store, task: task, title: "选中的目标")
        _ = goal(in: store, task: task, title: "未选中的私密目标")
        let context = store.planningAgentContext(goalIDs: [chosen.id], taskIDs: [task.id])
        XCTAssertEqual(context.goals.count, 1)
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(context), as: UTF8.self).contains("未选中的私密目标"))
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: task)])
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: request.data()) as? [String: Any])
        XCTAssertNil(json["agentContext"])
    }

    func testFeedbackIsExplicitRelatedAndRemovable() throws {
        let store = store()
        let task = store.addTask("A", to: .importantUrgent)!
        let unrelated = store.addTask("B", to: .importantNotUrgent)!
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: task)])
        let id = try XCTUnwrap(store.recordAISummary(draft(task), request: request))
        XCTAssertTrue(store.planningAgentContext(goalIDs: [], taskIDs: [task.id]).recentRelatedFeedback.isEmpty)
        store.setAISummaryFeedback(id, feedback: AIUserFeedback(helpful: false, comment: "步骤太大"))
        XCTAssertFalse(store.planningAgentContext(goalIDs: [], taskIDs: [task.id]).recentRelatedFeedback[0].helpful)
        XCTAssertTrue(store.planningAgentContext(goalIDs: [], taskIDs: [unrelated.id]).recentRelatedFeedback.isEmpty)
        XCTAssertEqual(Store(fileURL: store.dataFileURL).aiSummaries[0].feedback?.comment, "步骤太大")
        store.setAISummaryFeedback(id, feedback: nil)
        XCTAssertTrue(store.planningAgentContext(goalIDs: [], taskIDs: [task.id]).recentRelatedFeedback.isEmpty)
    }

    func testPreferenceChangesInvalidateDraftBeforeTaskMutation() throws {
        let store = store(); let task = store.addTask("任务", to: .importantUrgent)!
        let context = store.planningAgentContext(goalIDs: [], taskIDs: [task.id])
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: task)], agentContext: context)
        var preferences = AgentPreferences(); preferences.notes = "新偏好"
        store.updateAgentPreferences(preferences)
        XCTAssertThrowsError(try store.applyAIDraft(draft(task), request: request, selectedIDs: [task.id],
                                                   originalTasks: [task], originalInbox: []))
        XCTAssertFalse(store.tasks[0].isToday)
    }

    func testGoalChangesInvalidatePendingContext() throws {
        let store = store(); let task = store.addTask("任务", to: .importantUrgent)!
        var goal = goal(in: store, task: task)
        let context = store.planningAgentContext(goalIDs: [goal.id], taskIDs: [task.id])
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: task)], agentContext: context)
        goal.status = .paused; store.updateGoal(goal)
        XCTAssertThrowsError(try store.validatePlanningContext(request))
    }

    func testInterruptedRunIsNotAutomaticallyResumedAndContainsNoRequestSecrets() throws {
        let store = store(); let task = store.addTask("任务", to: .importantUrgent)!
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: task)],
                                agentContext: store.planningAgentContext(goalIDs: [], taskIDs: [task.id]))
        let id = store.beginPlanningRun(request)
        store.updatePlanningRun(id, phase: .thinking)
        let reloaded = Store(fileURL: store.dataFileURL)
        XCTAssertEqual(reloaded.planningRuns.first?.phase, .interrupted)
        XCTAssertTrue(reloaded.planningRuns.first?.toolsUsed.contains("读取用户偏好") == true)
        XCTAssertFalse(reloaded.tasks[0].isToday)
        XCTAssertEqual(reloaded.planningRuns.count, 1)
    }

    func testRunRetentionIsBoundedAndAppliedStateIsStableAcrossReload() {
        let store = store(); let task = store.addTask("任务", to: .importantUrgent)!
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: task)])
        for _ in 0..<25 { store.beginPlanningRun(request) }
        let id = store.planningRuns[0].id
        store.updatePlanningRun(id, phase: .applied)
        let reloaded = Store(fileURL: store.dataFileURL)
        XCTAssertEqual(reloaded.planningRuns.count, 20)
        XCTAssertEqual(reloaded.planningRuns[0].phase, .applied)
    }
}
