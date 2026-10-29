import XCTest
@testable import PareCore

final class IntelligenceTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    func makeStore() throws -> Store {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pare-intelligence-test-\(UUID())")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return Store(fileURL: dir.appendingPathComponent("store.json"))
    }
    func testIdeaWithoutTasksAndIndependentActionsAreIdempotentAfterReload() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Investigate needs", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        let action = IntelligenceAction(kind: .createLinkedTask, sourceID: idea.id, evidenceIDs: [idea.id], title: "Ask one person", nextAction: "Write one question", definitionOfDone: "One question ready", plannedMinutes: 5)
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Find one example", actions: [action])
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        XCTAssertEqual(store.tasks.count, 1)
        XCTAssertFalse(store.tasks[0].isToday)
        let loaded = Store(fileURL: store.dataFileURL)
        XCTAssertEqual(try loaded.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now).id, receipt.id)
        XCTAssertEqual(loaded.tasks.count, 1)
        try loaded.undoIntelligenceAdoption(receipt.id, now: now)
        XCTAssertTrue(loaded.tasks.isEmpty)
        XCTAssertEqual(loaded.thoughts, [idea])
    }
    func testDeniedContextAndStaleRelatedEvidence() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        XCTAssertTrue(store.addIdeaEntry(ideaID: idea.id, text: "Private exploration", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Linked", now: now))
        let options = IntelligenceContextOptions(includeIdeaRecords: false, includeLinkedTasks: false)
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, options: options, now: now)
        XCTAssertEqual(request.evidence.map(\.kind), ["idea"])
        XCTAssertFalse(request.preview.contains("Private exploration"))
        XCTAssertFalse(request.evidence.contains { $0.title == task.text })
        let action = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id, text: "Next?")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Question", actions: [action])
        store.updateThought(idea.id, text: "Changed", notes: nil, now: now)
        XCTAssertThrowsError(try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now))
    }
    func testBatchSaveFailureRollsBackAndUndoRefusesLaterEdits() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        let action = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id, text: "Next?")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Question", actions: [action])
        let backup = store.dataFileURL.appendingPathExtension("bak")
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)
        XCTAssertThrowsError(try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now))
        XCTAssertEqual(store.thoughts, [idea])
        XCTAssertTrue(store.intelligenceAdoptions.isEmpty)
        try FileManager.default.removeItem(at: backup)
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        store.updateThought(idea.id, text: "Edited later", notes: nil, now: now)
        XCTAssertThrowsError(try store.undoIntelligenceAdoption(receipt.id, now: now))
        XCTAssertEqual(store.thoughts[0].text, "Edited later")
    }
    func testUnrelatedEditsDoNotStaleIdeaAndAIRecordsKeepAttribution() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let unrelated = try XCTUnwrap(store.addTask("Unrelated", to: .neither, now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        store.setEstimate(unrelated.id, minutes: 99)
        let first = IntelligenceAction(kind: .appendAIExplorationRecord, sourceID: idea.id, text: "Hypothesis, not fact")
        let second = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id, text: "What evidence?")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Explore", actions: [first, second])
        _ = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [first.id], now: now)
        XCTAssertEqual(store.thoughts[0].entries[0].author, "AI 辅助探索")
        XCTAssertNil(store.thoughts[0].nextQuestion)
        XCTAssertEqual(store.thoughts[0].stage, .seed)
    }
    func testProposalRejectsUnknownSourcesWrongScopeAndExcessBudget() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, availableMinutes: 5, now: now)
        var action = IntelligenceAction(kind: .createLinkedTask, sourceID: idea.id, title: "One interview", nextAction: "Ask", definitionOfDone: "One answer", plannedMinutes: 6)
        func proposal(_ action: IntelligenceAction) -> IntelligenceProposal { IntelligenceProposal(requestID: request.id, summary: "Explore", actions: [action]) }
        XCTAssertThrowsError(try proposal(action).validate(for: request))
        action.plannedMinutes = 5; action.evidenceIDs = [UUID()]
        XCTAssertThrowsError(try proposal(action).validate(for: request))
        action.evidenceIDs = []; action.kind = .setDailyWin
        XCTAssertThrowsError(try proposal(action).validate(for: request))
        action.kind = .createLinkedTask; action.sourceID = UUID()
        XCTAssertThrowsError(try proposal(action).validate(for: request))
    }
    func testDailyLoadUsesAllocationOrTimeBlockAndExcludesParallelWallTime() throws {
        let store = try makeStore()
        let first = try XCTUnwrap(store.addTask("Work", to: .importantUrgent, addToToday: true, estimatedMinutes: 300, now: now))
        let unknown = try XCTUnwrap(store.addTask("Unknown", to: .importantUrgent, addToToday: true, now: now))
        let parallel = try XCTUnwrap(store.addTask("External", to: .importantUrgent, addToToday: true, estimatedMinutes: 400, now: now))
        try store.setDailyAllocation(taskID: first.id, plannedMinutes: 15, now: now)
        XCTAssertTrue(store.startParallelRun(parallel.id, now: now))
        XCTAssertThrowsError(try store.setDailyAllocation(taskID: parallel.id, plannedMinutes: 400, now: now))
        try store.setDailyAllocation(taskID: parallel.id, plannedMinutes: 5, kind: .check, now: now)
        XCTAssertEqual(store.dailyLoad(now: now), DailyLoad(personalMinutes: 15, checkMinutes: 5, unknownCount: 1))
        XCTAssertEqual(store.tasks.first { $0.id == first.id }?.estimatedMinutes, 300)
        store.scheduleTask(first.id, at: now, durationMinutes: 30)
        XCTAssertEqual(store.dailyLoad(now: now).plannedMinutes, 35)
        let proposal = IntelligenceProposal(requestID: UUID(), summary: "Plan", actions: [IntelligenceAction(kind: .setTodaySelection, sourceID: unknown.id, plannedMinutes: 10)])
        let before = store.tasks
        XCTAssertEqual(try store.projectedDailyLoad(for: proposal, selectedActionIDs: Set(proposal.actions.map(\.id)), now: now).plannedMinutes, 45)
        XCTAssertEqual(store.tasks, before)
        XCTAssertEqual(store.dailyLoad(now: now.addingTimeInterval(86400)).plannedMinutes, 0)
        XCTAssertEqual(store.dailyLoad(now: now.addingTimeInterval(86400)).unknownCount, 3)
    }
    func testMemoriesAreExplicitScopedExpireAndFeedbackSurvivesReload() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let other = try XCTUnwrap(store.addThought("Other", now: now))
        _ = try store.confirmPreference(text: "Global", now: now)
        _ = try store.confirmPreference(text: "Local", ideaID: idea.id, now: now)
        _ = try store.confirmPreference(text: "Other scope", ideaID: other.id, now: now)
        _ = try store.confirmPreference(text: "Expired", expiresAt: now.addingTimeInterval(-1), now: now)
        let denied = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        XCTAssertFalse(denied.evidence.contains { $0.kind == "preference" })
        let allowed = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, options: .init(includeMemories: true), now: now)
        XCTAssertEqual(allowed.evidence.filter { $0.kind == "preference" }.count, 2)
        let action = IntelligenceAction(kind: .createLinkedTask, sourceID: idea.id, title: "Ask", nextAction: "Write", definitionOfDone: "One line", plannedMinutes: 5)
        let proposal = IntelligenceProposal(requestID: allowed.id, summary: "Explore", actions: [action], memoryCandidate: "New preference")
        let receipt = try store.applyIntelligenceProposal(proposal, request: allowed, selectedActionIDs: [action.id], now: now)
        XCTAssertEqual(store.confirmedPreferences.count, 4)
        try store.recordIntelligenceFeedback(requestID: allowed.id, actionID: action.id, ideaID: idea.id, kind: .tooLarge, now: now)
        let loaded = Store(fileURL: store.dataFileURL)
        XCTAssertEqual(loaded.intelligenceFeedback.first?.createdTaskID, receipt.createdTaskIDs.first)
        XCTAssertEqual(loaded.confirmedPreferences.count, 4)
        try loaded.forgetConfirmedPreference(loaded.confirmedPreferences[0].id)
        XCTAssertEqual(Store(fileURL: store.dataFileURL).confirmedPreferences.count, 3)
    }
    func testContextBoundAndTaskPrivateNotesNeverSent() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Linked", now: now))
        store.updateTaskDetails(task.id, text: "Linked", note: "Secret note", nextAction: nil, dueAt: nil, remindAt: nil, recurrence: nil, now: now)
        for _ in 0..<30 { XCTAssertTrue(store.addIdeaEntry(ideaID: idea.id, text: String(repeating: "record ", count: 300), now: now)) }
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, options: .init(includeHistory: true), now: now)
        XCTAssertLessThanOrEqual(try request.data().count, 32000)
        XCTAssertGreaterThan(request.omittedRecordCount, 0)
        XCTAssertFalse(request.preview.contains("Secret note"))
        XCTAssertTrue(request.evidence.contains { $0.id == idea.id })
    }
    func testLegacyAIDraftSaveFailureRollsBack() throws {
        let store = try makeStore()
        let task = try XCTUnwrap(store.addTask("Task", to: .importantUrgent, now: now))
        let request = AIRequest(workflow: .nextStep, inputs: [AIInput(task: task)])
        let draft = AIDraft(suggestions: [AISuggestion(sourceID: task.id, title: "Task", nextAction: "Start", definitionOfDone: "One line", estimatedMinutes: 5)])
        try FileManager.default.createDirectory(at: store.dataFileURL.appendingPathExtension("bak"), withIntermediateDirectories: false)
        XCTAssertThrowsError(try store.applyAIDraft(draft, request: request, selectedIDs: [task.id], originalTasks: [task], originalInbox: [], now: now))
        XCTAssertEqual(store.tasks, [task])
    }

    func testPreferenceMutationsRollbackOnWriteFailure() throws {
        let store = try makeStore()
        let preference = try store.confirmPreference(text: "Explicit", now: now)
        try FileManager.default.createDirectory(at: store.dataFileURL.appendingPathExtension("bak"), withIntermediateDirectories: false)
        XCTAssertThrowsError(try store.confirmPreference(text: "New", now: now))
        XCTAssertThrowsError(try store.updateConfirmedPreference(preference.id, text: "Changed", now: now))
        XCTAssertThrowsError(try store.forgetConfirmedPreference(preference.id))
        XCTAssertEqual(store.confirmedPreferences, [preference])
    }
    func testUnblockRequiresTwoToFiveMinutesAndRejectsCrossDayAdoption() throws {
        let store = try makeStore()
        let task = try XCTUnwrap(store.addTask("Write", to: .importantUrgent, now: now))
        let request = try store.prepareIntelligenceRequest(intent: .unblock, subjectID: task.id, now: now)
        var action = IntelligenceAction(kind: .updateTaskNextStep, sourceID: task.id, nextAction: "Write one sentence", definitionOfDone: "One sentence", plannedMinutes: 6)
        var proposal = IntelligenceProposal(requestID: request.id, summary: "Start", actions: [action])
        XCTAssertThrowsError(try proposal.validate(for: request))
        action.plannedMinutes = 3; proposal.actions = [action]
        XCTAssertNoThrow(try proposal.validate(for: request))
        XCTAssertThrowsError(try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now.addingTimeInterval(86400)))
        XCTAssertEqual(store.tasks, [task])
    }
    func testParallelEvidenceHasUniqueIDsAndTodayAllocationDoesNotChangeEstimate() throws {
        let store = try makeStore()
        let task = try XCTUnwrap(store.addTask("External process", to: .importantUrgent, estimatedMinutes: 300, now: now))
        XCTAssertTrue(store.startParallelRun(task.id, now: now))
        let request = try store.prepareIntelligenceRequest(intent: .today, now: now)
        XCTAssertEqual(Set(request.evidence.map(\.id)).count, request.evidence.count)
        let action = IntelligenceAction(kind: .setTodaySelection, sourceID: task.id, plannedMinutes: 5, allocationKind: .check)
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Check", actions: [action])
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        XCTAssertEqual(store.tasks[0].estimatedMinutes, 300)
        XCTAssertEqual(store.dailyLoad(now: now).checkMinutes, 5)
        let loaded = Store(fileURL: store.dataFileURL)
        try loaded.undoIntelligenceAdoption(receipt.id, now: now)
        XCTAssertTrue(loaded.dailyAllocations.isEmpty)
        XCTAssertEqual(loaded.tasks[0].estimatedMinutes, 300)
    }
    func testUndoDoesNotDeleteCreatedTaskWithNewExternalRelationship() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let other = try XCTUnwrap(store.addThought("Other", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        let action = IntelligenceAction(kind: .createLinkedTask, sourceID: idea.id, title: "Created", nextAction: "Start", definitionOfDone: "Done", plannedMinutes: 3)
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Start", actions: [action])
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        XCTAssertTrue(store.linkTaskToIdea(ideaID: other.id, taskID: receipt.createdTaskIDs[0], now: now))
        XCTAssertThrowsError(try store.undoIntelligenceAdoption(receipt.id, now: now))
        XCTAssertEqual(store.tasks.count, 1)
    }

    func testRelatedIdeaAndExplorationHaveSeparateAuthorizations() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Origin idea", now: now))
        XCTAssertTrue(store.addIdeaEntry(ideaID: idea.id, text: "Exploration", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Linked", now: now))
        for intent in [IntelligenceIntent.today, .unblock] {
            let linkedOnly = try store.prepareIntelligenceRequest(intent: intent, subjectID: intent == .unblock ? task.id : nil,
                options: .init(includeIdeaRecords: false, includeLinkedTasks: true), now: now)
            XCTAssertTrue(linkedOnly.evidence.contains { $0.id == idea.id })
            XCTAssertFalse(linkedOnly.evidence.contains { $0.kind == "ideaEntry" })
            let denied = try store.prepareIntelligenceRequest(intent: intent, subjectID: intent == .unblock ? task.id : nil,
                options: .init(includeIdeaRecords: true, includeLinkedTasks: false), now: now)
            XCTAssertFalse(denied.evidence.contains { $0.kind == "idea" || $0.kind == "ideaEntry" })
        }
    }
    func testUnadoptedFeedbackIsAvailableToNextRelatedRequest() throws {
        let store = try makeStore()
        let task = try XCTUnwrap(store.addTask("Task", to: .importantUrgent, now: now))
        try store.recordIntelligenceFeedback(requestID: UUID(), actionID: UUID(), sourceTaskID: task.id, kind: .unsuitable, comment: "Prefer one concrete question", now: now)
        let request = try store.prepareIntelligenceRequest(intent: .unblock, subjectID: task.id, options: .init(includeHistory: true), now: now)
        XCTAssertTrue(request.evidence.contains { $0.kind == "feedback" && $0.body.contains("one concrete question") })
        XCTAssertTrue(store.confirmedPreferences.isEmpty)
        XCTAssertThrowsError(try store.recordIntelligenceFeedback(requestID: UUID(), sourceTaskID: UUID(), kind: .unsuitable, now: now))
    }

    func testUndoSaveFailureAndDifferentRepeatedSelectionRemainAtomic() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        let first = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id, text: "Question")
        let second = IntelligenceAction(kind: .appendAIExplorationRecord, sourceID: idea.id, text: "Hypothesis")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Explore", actions: [first, second])
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [first.id], now: now)
        XCTAssertThrowsError(try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [second.id], now: now))
        let after = store.thoughts
        let backup = store.dataFileURL.appendingPathExtension("bak")
        try FileManager.default.removeItem(at: backup)
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)
        XCTAssertThrowsError(try store.undoIntelligenceAdoption(receipt.id, now: now))
        XCTAssertEqual(store.thoughts, after)
        XCTAssertNil(store.intelligenceAdoptions[0].undoneAt)
    }
    func testSharedTaskDoesNotExpandIdeaMemoryScope() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Current", now: now))
        let other = try XCTUnwrap(store.addThought("Other", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Shared", now: now))
        XCTAssertTrue(store.linkTaskToIdea(ideaID: other.id, taskID: task.id, now: now))
        _ = try store.confirmPreference(text: "Other idea secret preference", ideaID: other.id, now: now)
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, options: .init(includeMemories: true), now: now)
        XCTAssertFalse(request.preview.contains("Other idea secret preference"))
    }

    func testAddedAuthorizedPreferenceInvalidatesOldRequest() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, options: .init(includeMemories: true), now: now)
        _ = try store.confirmPreference(text: "New constraint", now: now)
        let action = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id, text: "Question")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Explore", actions: [action])
        XCTAssertThrowsError(try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now))
        XCTAssertNil(store.thoughts[0].nextQuestion)
    }
    func testAddedLinkedTaskWithSameTimestampInvalidatesManifest() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        _ = store.createTaskFromIdea(ideaID: idea.id, title: "New fact", now: now)
        let action = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id, text: "Question")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Explore", actions: [action])
        XCTAssertThrowsError(try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now))
    }

    func testLegacyOrganizeFeedbackFollowsNewTaskAndSurvivesSummaryRetention() throws {
        let store = try makeStore()
        XCTAssertTrue(store.capture("Raw idea"))
        let inbox = try XCTUnwrap(store.inbox.first)
        let request = AIRequest(workflow: .organize, inputs: [AIInput(inbox: inbox)])
        let draft = AIDraft(summary: "Organize", suggestions: [AISuggestion(sourceID: inbox.id, title: "Concrete task", nextAction: "One line", definitionOfDone: "Written")])
        let summaryID = try XCTUnwrap(store.recordAISummary(draft, request: request, now: now))
        store.setAISummaryFeedback(summaryID, feedback: AIUserFeedback(helpful: false, comment: "Too broad", date: now))
        try store.applyAIDraft(draft, request: request, selectedIDs: [inbox.id], originalTasks: [], originalInbox: [inbox], now: now)
        let task = try XCTUnwrap(store.tasks.first)
        XCTAssertEqual(store.intelligenceFeedback.first?.createdTaskID, task.id)
        let loaded = Store(fileURL: store.dataFileURL)
        loaded.setAISummaryFeedback(summaryID, feedback: AIUserFeedback(helpful: true, comment: "Concrete now", date: now))
        XCTAssertEqual(loaded.intelligenceFeedback.first?.createdTaskID, task.id)
        let nextRequest = AIRequest(workflow: .nextStep, inputs: [AIInput(task: task)])
        for index in 0..<51 {
            _ = try loaded.recordAISummary(AIDraft(summary: "Later \(index)", suggestions: [AISuggestion(sourceID: task.id, title: task.text, nextAction: "One step", definitionOfDone: "Ready")]), request: nextRequest, now: now)
        }
        XCTAssertEqual(loaded.aiSummaries.count, 50)
        XCTAssertFalse(loaded.aiSummaries.contains { $0.id == summaryID })
        let latest = Store(fileURL: store.dataFileURL)
        XCTAssertEqual(latest.intelligenceFeedback.first?.requestID, summaryID)
        let input = try latest.prepareIntelligenceRequest(intent: .unblock, subjectID: task.id, options: .init(includeHistory: true), now: now)
        XCTAssertTrue(input.evidence.contains { $0.kind == "feedback" && $0.body.contains("Concrete now") })
    }

    func testCompletedDailyWinDoesNotBlockChoosingNewWin() throws {
        let store = try makeStore()
        let old = try XCTUnwrap(store.addTask("Finished focus", to: .importantUrgent, now: now))
        let next = try XCTUnwrap(store.addTask("Next focus", to: .importantUrgent, now: now))
        store.setDailyWin(old.id, now: now); store.setCompleted(old.id, true, now: now)
        let request = try store.prepareIntelligenceRequest(intent: .today, now: now)
        XCTAssertTrue(request.evidence.contains { $0.id == old.id })
        let action = IntelligenceAction(kind: .setDailyWin, sourceID: next.id)
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Next focus", actions: [action])
        _ = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        XCTAssertEqual(store.dailyWinTask?.id, next.id)
        XCTAssertFalse(store.tasks.first { $0.id == old.id }?.isDailyWin == true)
    }

    func testCrossDayUndoNeverRestoresYesterdaySchedule() throws {
        let store = try makeStore()
        _ = store.rolloverIfNeeded(now: now)
        let task = try XCTUnwrap(store.addTask("Scheduled", to: .importantUrgent, now: now))
        store.scheduleTask(task.id, at: now, durationMinutes: 30)
        let request = try store.prepareIntelligenceRequest(intent: .today, now: now)
        let action = IntelligenceAction(kind: .setTodaySelection, sourceID: task.id, selected: false)
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Defer", actions: [action])
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        let tomorrow = now.addingTimeInterval(86400)
        XCTAssertTrue(store.rolloverIfNeeded(now: tomorrow))
        XCTAssertThrowsError(try store.undoIntelligenceAdoption(receipt.id, now: tomorrow))
        XCTAssertFalse(store.tasks[0].isToday)
        XCTAssertNil(store.tasks[0].scheduledAt)
    }
    func testPureIdeaAdoptionCanBeUndoneOnLaterDay() throws {
        let store = try makeStore()
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id, now: now)
        let action = IntelligenceAction(kind: .appendAIExplorationRecord, sourceID: idea.id, text: "Hypothesis")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "Explore", actions: [action])
        let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: [action.id], now: now)
        try store.undoIntelligenceAdoption(receipt.id, now: now.addingTimeInterval(86400))
        XCTAssertEqual(store.thoughts, [idea])
    }

    func testOldAISummariesRequireHistoryPermissionAndRelatedSource() throws {
        let store = try makeStore()
        let task = try XCTUnwrap(store.addTask("Current", to: .importantUrgent, now: now))
        let unrelated = try XCTUnwrap(store.addTask("Unrelated", to: .importantUrgent, now: now))
        for item in [task, unrelated] {
            let request = AIRequest(workflow: .nextStep, inputs: [AIInput(task: item)])
            _ = try store.recordAISummary(AIDraft(summary: "Analysis for \(item.text)", suggestions: [AISuggestion(sourceID: item.id, title: item.text, nextAction: "Start", definitionOfDone: "One step")]), request: request, now: now)
        }
        let denied = try store.prepareIntelligenceRequest(intent: .unblock, subjectID: task.id, now: now)
        XCTAssertFalse(denied.evidence.contains { $0.kind == "aiSummary" })
        let allowed = try store.prepareIntelligenceRequest(intent: .unblock, subjectID: task.id, options: .init(includeHistory: true), now: now)
        let summaries = allowed.evidence.filter { $0.kind == "aiSummary" }
        XCTAssertEqual(summaries.count, 1)
        XCTAssertTrue(summaries[0].body.contains("Analysis for Current"))
        XCTAssertFalse(allowed.preview.contains("Analysis for Unrelated"))
        XCTAssertTrue(summaries[0].body.contains("不是用户确认的事实"))
    }

}
