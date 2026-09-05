import XCTest
@testable import PareCore

final class AIAssistanceTests: XCTestCase {
    private func makeStore() -> Store {
        Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("pare-ai-test-\(UUID()).json"))
    }
    private func input(_ task: TaskItem, workflow: AIWorkflow = .nextStep, minutes: Int = 30) -> AIRequest {
        AIRequest(workflow: workflow, inputs: [AIInput(task: task)], availableMinutes: minutes)
    }
    private func suggestion(_ task: TaskItem) -> AISuggestion {
        AISuggestion(sourceID: task.id, title: task.text, nextAction: "写三个小标题",
                     definitionOfDone: "有一份提纲", estimatedMinutes: 5)
    }
    private func envelope(_ draft: AIDraft, finish: String = "stop") throws -> Data {
        let content = String(decoding: try JSONEncoder().encode(draft), as: UTF8.self)
        return try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": finish,
                                                                       "message": ["content": content]]]])
    }

    func testEndpointRequiresTLSExceptLoopbackAndRejectsEmbeddedCredentials() throws {
        var config = AIConfiguration()
        for value in ["http://remote.example/v1", "https://user:password@example.com/v1",
                      "https://example.com/v1?key=secret", "https://example.com/v1#fragment"] {
            config.baseURL = value
            XCTAssertThrowsError(try config.endpoint())
        }
        config.baseURL = "http://127.0.0.1:11434/v1/"
        XCTAssertEqual(try config.endpoint().absoluteString, "http://127.0.0.1:11434/v1/chat/completions")
        config.baseURL = "https://example.com/v1/chat/completions"
        XCTAssertEqual(try config.endpoint().path, "/v1/chat/completions")
    }

    func testRequestOmitsPrivateNotesByDefaultAndKeepsKeyOutOfBody() throws {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent, note: "私密备注")
        let request = input(task)
        XCTAssertFalse(String(decoding: try request.data(), as: UTF8.self).contains("私密备注"))
        XCTAssertEqual(AIInput(task: task, includeNotes: true).note, "私密备注")
        var config = AIConfiguration(); config.model = "test-model"
        let network = try AIClient.makeRequest(config: config, apiKey: "unit-test-placeholder", input: request)
        XCTAssertEqual(network.value(forHTTPHeaderField: "Authorization"), "Bearer unit-test-placeholder")
        XCTAssertFalse(String(decoding: network.httpBody!, as: UTF8.self).contains("unit-test-placeholder"))
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: network.httpBody!) as? [String: Any])
        XCTAssertEqual(body["store"] as? Bool, false)
        XCTAssertNotNil(body["response_format"])
        XCTAssertEqual(body["max_completion_tokens"] as? Int, 2000)
    }

    func testCompatibilityModeKeepsJSONValidationAndUsesCompatibleTokenLimit() throws {
        var config = AIConfiguration(); config.model = "test-model"
        config.baseURL = "http://localhost:11434/v1"; config.strictSchema = false
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        let request = try AIClient.makeRequest(config: config, apiKey: "", input: input(task))
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: request.httpBody!) as? [String: Any])
        XCTAssertNil(body["response_format"])
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(body["max_tokens"] as? Int, 2000)
        XCTAssertThrowsError(try AIClient.decode(Data("bad response".utf8), for: input(task)))
    }

    func testInputCapsAndDuplicateIDs() {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        XCTAssertThrowsError(try AIRequest(workflow: .plan, inputs: []).data())
        XCTAssertThrowsError(try AIRequest(workflow: .plan, inputs: Array(repeating: AIInput(task: task), count: 21)).data())
        XCTAssertThrowsError(try AIRequest(workflow: .plan, inputs: [AIInput(task: task), AIInput(task: task)]).data())
        XCTAssertThrowsError(try AIRequest(workflow: .plan, inputs: [AIInput(task: task)], clarification: String(repeating: "x", count: 2001)).data())
    }

    func testUnknownSourceDuplicateAndInvalidDurationsRejected() throws {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        var item = suggestion(task)
        try AIDraft(suggestions: [item]).validate(for: input(task))
        item.sourceID = UUID()
        XCTAssertThrowsError(try AIDraft(suggestions: [item]).validate(for: input(task)))
        item = suggestion(task); item.estimatedMinutes = 60
        XCTAssertThrowsError(try AIDraft(suggestions: [item]).validate(for: input(task)))
        item = suggestion(task); item.quadrant = 9
        XCTAssertThrowsError(try AIDraft(suggestions: [item]).validate(for: input(task)))
        XCTAssertThrowsError(try AIDraft(suggestions: [suggestion(task), suggestion(task)]).validate(for: input(task)))
    }

    func testPlanCannotExceedBudgetOrInventMultipleKeyTasks() {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        var item = suggestion(task); item.estimatedMinutes = 30; item.isKeyTask = true
        XCTAssertThrowsError(try AIDraft(suggestions: [item]).validate(for: input(task, workflow: .plan, minutes: 10)))
        item.estimatedMinutes = 5; item.isKeyTask = false
        XCTAssertThrowsError(try AIDraft(suggestions: [item]).validate(for: input(task, workflow: .plan)))
    }

    func testClarificationHasNoActionsAndTruncationDoesNotApply() throws {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        let draft = AIDraft(question: "报告交给谁？", suggestions: [])
        XCTAssertEqual(try AIClient.decode(envelope(draft), for: input(task)), draft)
        XCTAssertThrowsError(try AIDraft(question: "报告交给谁？", suggestions: [suggestion(task)]).validate(for: input(task)))
        XCTAssertThrowsError(try AIClient.decode(envelope(AIDraft(suggestions: [suggestion(task)]), finish: "length"), for: input(task)))
        let refusal = try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": "stop", "message": ["refusal": "No"]]]])
        XCTAssertThrowsError(try AIClient.decode(refusal, for: input(task)))
    }

    func testSingleJSONFenceIsAcceptedButProseAndWrongIDsAreNot() throws {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        let draft = AIDraft(suggestions: [suggestion(task)])
        let json = String(decoding: try JSONEncoder().encode(draft), as: UTF8.self)
        func wrapped(_ text: String) throws -> Data {
            try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": "stop", "message": ["content": text]]]])
        }
        XCTAssertEqual(try AIClient.decode(wrapped("```json\n\(json)\n```"), for: input(task)), draft)
        XCTAssertThrowsError(try AIClient.decode(wrapped("解释如下\n\(json)"), for: input(task)))
        XCTAssertThrowsError(try AIClient.decode(wrapped("```swift\n\(json)\n```"), for: input(task)))
    }

    func testOnlySelectedInboxItemsAreAppliedAndOriginalTextPreserved() throws {
        let store = makeStore(); store.capture("报告原始内容"); store.capture("留下这个想法")
        let snapshot = store.inbox
        let request = AIRequest(workflow: .organize, inputs: snapshot.map(AIInput.init(inbox:)))
        let item = AISuggestion(sourceID: snapshot[0].id, title: "清楚的想法标题", kind: "thought")
        let draft = AIDraft(suggestions: [item])
        try store.applyAIDraft(draft, request: request, selectedIDs: [], originalTasks: [], originalInbox: snapshot)
        XCTAssertEqual(store.inbox, snapshot)
        try store.applyAIDraft(draft, request: request, selectedIDs: [item.id], originalTasks: [], originalInbox: snapshot)
        XCTAssertEqual(store.inbox.count, 1)
        XCTAssertTrue(store.thoughts[0].notes?.contains(snapshot[0].text) == true)
        XCTAssertEqual(store.todayTotal, 0)
    }

    func testStaleBatchIsRejectedBeforeAnyMutation() throws {
        let store = makeStore()
        let a = store.addTask("A", to: .importantUrgent)!
        let b = store.addTask("B", to: .importantNotUrgent)!
        var first = suggestion(a); first.isKeyTask = true
        let second = suggestion(b)
        let request = AIRequest(workflow: .plan, inputs: [AIInput(task: a), AIInput(task: b)])
        store.setCompleted(b.id, true)
        XCTAssertThrowsError(try store.applyAIDraft(AIDraft(suggestions: [first, second]), request: request,
                                                   selectedIDs: [a.id, b.id], originalTasks: [a, b], originalInbox: []))
        XCTAssertNil(store.tasks.first(where: { $0.id == a.id })?.nextAction)
        XCTAssertFalse(store.tasks.first(where: { $0.id == a.id })!.isToday)
    }

    func testPlanPreservesOtherTodayTasksAndExistingEstimate() throws {
        let store = makeStore()
        let existing = store.addTask("原计划", to: .importantUrgent, addToToday: true)!
        let task = store.addTask("大任务", to: .importantNotUrgent, estimatedMinutes: 120)!
        var item = suggestion(task); item.isKeyTask = true
        try store.applyAIDraft(AIDraft(suggestions: [item]), request: input(task, workflow: .plan),
                               selectedIDs: [task.id], originalTasks: [task], originalInbox: [])
        XCTAssertTrue(store.tasks.first(where: { $0.id == existing.id })!.isToday)
        XCTAssertEqual(store.dailyWinTask?.id, task.id)
        XCTAssertEqual(store.dailyWinTask?.estimatedMinutes, 120)
        XCTAssertEqual(store.dailyWinTask?.nextAction, item.nextAction)
    }
}
