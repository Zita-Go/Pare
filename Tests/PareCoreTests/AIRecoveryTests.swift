import XCTest
@testable import PareCore

final class AIRecoveryTests: XCTestCase {
    private func request(_ tasks: [TaskItem]) -> AIRequest {
        AIRequest(workflow: .plan, inputs: tasks.map { AIInput(task: $0) }, availableMinutes: 30)
    }
    private func item(_ task: TaskItem, minutes: Int = 5, primary: Bool = true) -> AISuggestion {
        AISuggestion(sourceID: task.id, title: task.text, nextAction: "写三个标题",
                     definitionOfDone: "留下提纲", estimatedMinutes: minutes, isKeyTask: primary)
    }
    private func envelope(_ object: [String: Any]) throws -> Data {
        let content = String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        return try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": "stop", "message": ["content": content]]]])
    }
    private func object(_ draft: AIDraft) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(draft)) as? [String: Any])
    }

    func testNullPresentationFieldsAreHarmlessButActionsRemainRequired() throws {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        var value = try object(AIDraft(suggestions: [item(task)]))
        value["summary"] = NSNull(); value["question"] = NSNull()
        let draft = try AIClient.decode(envelope(value), for: request([task]))
        XCTAssertEqual(draft.question, "")
        XCTAssertEqual(draft.suggestions.count, 1)
        var suggestions = value["suggestions"] as! [[String: Any]]
        suggestions[0].removeValue(forKey: "sourceID")
        value["suggestions"] = suggestions
        XCTAssertThrowsError(try AIClient.decode(envelope(value), for: request([task]))) { error in
            XCTAssertEqual(error as? AIResponseIssue, .missingFields)
        }
    }

    func testOverBudgetPreservesReadOnlyDraftButCannotWriteTasks() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pare-recovery-\(UUID()).json")
        let store = Store(fileURL: url)
        let a = store.addTask("A", to: .importantUrgent)!
        let b = store.addTask("B", to: .importantNotUrgent)!
        let draft = AIDraft(summary: "安排两件事", suggestions: [item(a, minutes: 25), item(b, minutes: 15, primary: false)])
        XCTAssertThrowsError(try AIClient.decode(envelope(object(draft)), for: request([a, b]))) { error in
            let rejected = error as? AIRejectedDraft
            XCTAssertEqual(rejected?.issue, .overBudget(40, 30))
            XCTAssertEqual(rejected?.draft, draft)
        }
        XCTAssertThrowsError(try store.applyAIDraft(draft, request: request([a, b]), selectedIDs: [a.id, b.id],
                                                   originalTasks: [a, b], originalInbox: []))
        XCTAssertEqual(store.todayTotal, 0)
        XCTAssertNil(store.tasks[0].nextAction)
    }

    func testInvalidReferenceAndDuplicateHaveDifferentDiagnostics() throws {
        let a = TaskItem(text: "A", quadrant: .importantUrgent)
        let b = TaskItem(text: "B", quadrant: .importantUrgent)
        let unknown = TaskItem(text: "其它", quadrant: .neither)
        XCTAssertThrowsError(try AIDraft(suggestions: [item(unknown)]).validate(for: request([a]))) { error in
            XCTAssertEqual(error as? AIResponseIssue, .unknownTask)
        }
        XCTAssertThrowsError(try AIDraft(suggestions: [item(a), item(a, primary: false)]).validate(for: request([a, b]))) { error in
            XCTAssertEqual(error as? AIResponseIssue, .duplicateTask)
        }
    }

    func testExtremeDurationIsRejectedBeforeSumming() {
        let task = TaskItem(text: "A", quadrant: .importantUrgent)
        XCTAssertThrowsError(try AIDraft(suggestions: [item(task, minutes: Int.max)]).validate(for: request([task]))) { error in
            XCTAssertEqual(error as? AIResponseIssue, .invalidFields)
        }
    }

    func testSchemaRestrictsIDsAndPlanCountToThisRequest() throws {
        let task = TaskItem(text: "A", quadrant: .importantUrgent)
        let schema = AIDraft.schema(for: request([task]))
        let properties = schema["properties"] as! [String: Any]
        let list = properties["suggestions"] as! [String: Any]
        let fields = (list["items"] as! [String: Any])["properties"] as! [String: Any]
        XCTAssertEqual((fields["sourceID"] as! [String: Any])["enum"] as? [String], [task.id.uuidString])
        XCTAssertEqual(list["maxItems"] as? Int, 3)
    }

    func testSharingDefaultsAreExplicitAndScopedToProvider() throws {
        let suite = "pare-sharing-test-\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = URL(string: "https://first.example/v1/chat/completions")!
        let second = URL(string: "https://second.example/v1/chat/completions")!
        XCTAssertNil(AIContextOptions.load(endpoint: first, defaults: defaults))
        var options = AIContextOptions(); options.history = true; options.historyDays = 30
        options.save(endpoint: first, defaults: defaults)
        XCTAssertEqual(AIContextOptions.load(endpoint: first, defaults: defaults), options)
        XCTAssertNil(AIContextOptions.load(endpoint: second, defaults: defaults))
        AIContextOptions.forget(endpoint: first, defaults: defaults)
        XCTAssertNil(AIContextOptions.load(endpoint: first, defaults: defaults))
    }

    func testRememberingContextDoesNotRememberTaskOrGoalIDs() throws {
        let data = try JSONEncoder().encode(AIContextOptions())
        let keys = Set((try JSONSerialization.jsonObject(with: data) as! [String: Any]).keys)
        XCTAssertEqual(keys, ["notes", "history", "historyDays", "background", "agent"])
    }

    func testRetryCanCarryClarificationWithoutLosingOriginalInput() throws {
        let task = TaskItem(text: "报告", quadrant: .importantUrgent)
        let input = AIRequest(workflow: .plan, inputs: [AIInput(task: task)], clarification: "先验证效果",
            retryGuidance: AIResponseIssue.questionWithSuggestions.retryGuidance,
            followUpQuestion: "先验证效果还是速度？")
        let data = try input.data()
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(AIRequest.self, from: data)
        XCTAssertEqual(decoded.followUpQuestion, "先验证效果还是速度？")
        XCTAssertEqual(decoded.inputs[0].id, task.id)
    }
}
