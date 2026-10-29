import XCTest
@testable import PareCore

final class IdeaWorkspaceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func storeURL() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pare-ideas-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("store.json")
    }

    private func blockWrites(to url: URL) throws {
        let backup = url.appendingPathExtension("bak")
        if FileManager.default.fileExists(atPath: backup.path) {
            try FileManager.default.removeItem(at: backup)
        }
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)
    }

    func testLegacyPromotionPreservesOriginalIdeaAcrossReload() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.addThought("Explore a direction", notes: "Keep the context", now: now))
        let task = try XCTUnwrap(store.promoteThought(idea.id, now: now))

        XCTAssertEqual(store.thoughts.map(\.id), [idea.id])
        XCTAssertEqual(Store(fileURL: url).thoughts.map(\.id), [idea.id])
        XCTAssertEqual(task.note, "Keep the context")
        XCTAssertFalse(task.isToday)
    }

    func testFailedIdeaCaptureAndPromotionLeaveStateUnchanged() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.addThought("Existing idea", now: now))
        try blockWrites(to: url)

        XCTAssertNil(store.addThought("Unsaved idea", now: now))
        XCTAssertNil(store.promoteThought(idea.id, now: now))
        XCTAssertEqual(store.thoughts, [idea])
        XCTAssertTrue(store.tasks.isEmpty)
        XCTAssertNotNil(store.persistenceIssue)
    }

    func testVersionFourIdeasKeepIdentityAndReceiveWorkspaceDefaults() throws {
        let url = try storeURL()
        let legacy = #"{"schemaVersion":4,"inbox":[],"tasks":[],"thoughts":[{"id":"82F03AD9-EAC2-4961-A610-2D74A46F266F","text":"Legacy idea","notes":"Original context","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-02T09:00:00Z","reviewAt":"2026-10-05T09:00:00Z"}]}"#
        try Data(legacy.utf8).write(to: url)
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.thoughts.first)
        XCTAssertEqual(idea.id.uuidString, "82F03AD9-EAC2-4961-A610-2D74A46F266F")
        XCTAssertEqual(idea.notes, "Original context")
        XCTAssertNotNil(idea.reviewAt)
        XCTAssertTrue(store.retrySaving())
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let persisted = try XCTUnwrap((json["thoughts"] as? [[String: Any]])?.first)
        XCTAssertEqual(json["schemaVersion"] as? Int, 5)
        XCTAssertEqual(persisted["stage"] as? String, "seed")
        XCTAssertEqual(persisted["entries"] as? [String], [])
        XCTAssertEqual(persisted["linkedTaskIDs"] as? [String], [])
        XCTAssertEqual(Store(fileURL: url).thoughts, [idea])
    }

    func testIdeaExplorationAndLegacyEditingPreserveEntriesAndLinks() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.addThought(" Idea ", now: now))
        XCTAssertTrue(store.updateIdea(idea.id, text: " Refined idea ", notes: " Context ",
                                      nextQuestion: " Which assumption? ", stage: .exploring,
                                      reviewAt: now, now: now))
        XCTAssertTrue(store.addIdeaEntry(ideaID: idea.id, text: " A finding ", kind: .finding, now: now))
        XCTAssertTrue(store.addIdeaEntry(ideaID: idea.id, text: "Try a prototype", kind: .decision,
                                        now: now.addingTimeInterval(60)))
        let first = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: " Read evidence ", now: now))
        let second = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Build prototype", now: now))
        XCTAssertEqual(first.text, "Read evidence")
        XCTAssertEqual(first.quadrant, .importantNotUrgent)
        XCTAssertFalse(first.isToday)
        XCTAssertNil(first.remindAt)
        store.updateThought(idea.id, text: "Renamed idea", notes: "Updated context", reviewAt: now, now: now)

        let loaded = Store(fileURL: url)
        let saved = try XCTUnwrap(loaded.thoughts.first)
        XCTAssertEqual(saved.text, "Renamed idea")
        XCTAssertEqual(saved.notes, "Updated context")
        XCTAssertEqual(saved.stage, .exploring)
        XCTAssertEqual(saved.nextQuestion, "Which assumption?")
        XCTAssertEqual(saved.entries.map(\.text), ["A finding", "Try a prototype"])
        XCTAssertEqual(saved.entries.map(\.kind), [.finding, .decision])
        XCTAssertEqual(saved.entries.map(\.createdAt), [now, now.addingTimeInterval(60)])
        XCTAssertEqual(Set(saved.entries.map(\.id)).count, 2)
        XCTAssertEqual(saved.linkedTaskIDs, [first.id, second.id])
        XCTAssertEqual(loaded.ideaLinkedTasks(idea.id).map(\.id), [first.id, second.id])
        XCTAssertEqual(loaded.ideas(for: first.id).map(\.id), [idea.id])
    }

    func testLinkingRejectsMissingAndDuplicateReferencesAndUnlinkKeepsTasks() throws {
        let store = Store(fileURL: try storeURL())
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let task = try XCTUnwrap(store.addTask("Existing action", to: .neither, now: now))
        XCTAssertFalse(store.linkTaskToIdea(ideaID: UUID(), taskID: task.id))
        XCTAssertFalse(store.linkTaskToIdea(ideaID: idea.id, taskID: UUID()))
        XCTAssertTrue(store.linkTaskToIdea(ideaID: idea.id, taskID: task.id, now: now))
        XCTAssertFalse(store.linkTaskToIdea(ideaID: idea.id, taskID: task.id, now: now))
        XCTAssertEqual(store.ideaLinkedTasks(idea.id).map(\.id), [task.id])
        XCTAssertFalse(store.unlinkTaskFromIdea(ideaID: idea.id, taskID: UUID()))
        XCTAssertTrue(store.unlinkTaskFromIdea(ideaID: idea.id, taskID: task.id, now: now))
        XCTAssertTrue(store.ideaLinkedTasks(idea.id).isEmpty)
        XCTAssertTrue(store.ideas(for: task.id).isEmpty)
        XCTAssertEqual(store.tasks, [task])
    }

    func testCompletedAndArchivedTasksRetainIdeaLinksUntilExplicitlyUnlinked() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        store.rolloverIfNeeded(now: now)
        let idea = try XCTUnwrap(store.addThought("Long exploration", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "First step", now: now))
        store.toggleComplete(task.id, now: now)
        XCTAssertEqual(store.thoughts.first?.stage, .seed)
        XCTAssertEqual(store.ideaLinkedTasks(idea.id).first?.completed, true)
        XCTAssertTrue(store.rolloverIfNeeded(now: now.addingTimeInterval(86_400)))

        let loaded = Store(fileURL: url)
        XCTAssertTrue(loaded.tasks.isEmpty)
        XCTAssertEqual(loaded.ideaLinkedTasks(idea.id).map(\.id), [task.id])
        XCTAssertEqual(loaded.ideas(for: task.id).map(\.id), [idea.id])
        let secondIdea = try XCTUnwrap(loaded.addThought("Related idea", now: now))
        XCTAssertTrue(loaded.linkTaskToIdea(ideaID: secondIdea.id, taskID: task.id, now: now))
        XCTAssertEqual(loaded.ideas(for: task.id).count, 2)
        XCTAssertTrue(loaded.deleteThought(idea.id))
        XCTAssertEqual(loaded.archive.map(\.id), [task.id])
        XCTAssertEqual(loaded.ideas(for: task.id).map(\.id), [secondIdea.id])
        loaded.undoLastDeletion()
        XCTAssertEqual(loaded.ideaLinkedTasks(idea.id).map(\.id), [task.id])
    }

    func testReviewRequiresExplicitDateAndOmitsArchivedIdeas() throws {
        let store = Store(fileURL: try storeURL())
        for (title, stage, reviewAt) in [
            ("Seed", IdeaStage.seed, now),
            ("Parked with review", .parked, now.addingTimeInterval(-60)),
            ("Parked without review", .parked, nil),
            ("Archived", .archived, now.addingTimeInterval(-120)),
            ("Future", .building, now.addingTimeInterval(60))
        ] {
            let idea = try XCTUnwrap(store.addThought(title, now: now))
            XCTAssertTrue(store.updateIdea(idea.id, text: title, notes: nil, nextQuestion: nil,
                                          stage: stage, reviewAt: reviewAt, now: now))
        }
        XCTAssertEqual(store.thoughtsDueForReview(now: now).map(\.text), ["Parked with review", "Seed"])
    }

    func testBlankWorkspaceActionsDoNotCreateOrAlterAnything() throws {
        let store = Store(fileURL: try storeURL())
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        XCTAssertNil(store.addThought(" \n "))
        XCTAssertFalse(store.updateIdea(idea.id, text: " \n ", notes: "Discard", nextQuestion: nil,
                                       stage: .archived, reviewAt: now))
        XCTAssertFalse(store.addIdeaEntry(ideaID: idea.id, text: " \n "))
        XCTAssertNil(store.createTaskFromIdea(ideaID: idea.id, title: " \n "))
        XCTAssertNil(store.createTaskFromIdea(ideaID: UUID(), title: "Missing idea"))
        XCTAssertFalse(store.addIdeaEntry(ideaID: UUID(), text: "Missing idea"))
        XCTAssertEqual(store.thoughts, [idea])
        XCTAssertTrue(store.tasks.isEmpty)
    }

    func testFailedWorkspaceMutationsRollBackTogetherAndPermitCleanTermination() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.addThought("Idea", notes: "Context", now: now))
        let linked = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Linked", now: now))
        let other = try XCTUnwrap(store.addTask("Other", to: .neither, now: now))
        XCTAssertTrue(store.capture("Inbox capture"))
        let thoughts = store.thoughts
        let tasks = store.tasks
        let inbox = store.inbox
        let primary = try Data(contentsOf: url)
        try blockWrites(to: url)

        XCTAssertFalse(store.updateIdea(idea.id, text: "Changed", notes: nil, nextQuestion: "Question",
                                       stage: .building, reviewAt: now))
        XCTAssertFalse(store.addIdeaEntry(ideaID: idea.id, text: "Finding", kind: .finding))
        XCTAssertNil(store.createTaskFromIdea(ideaID: idea.id, title: "New action"))
        XCTAssertFalse(store.linkTaskToIdea(ideaID: idea.id, taskID: other.id))
        XCTAssertFalse(store.unlinkTaskFromIdea(ideaID: idea.id, taskID: linked.id))
        XCTAssertFalse(store.deleteThought(idea.id))
        XCTAssertNil(store.keepAsThought(inboxID: inbox[0].id))
        store.updateThought(idea.id, text: "Legacy edit", notes: nil)
        XCTAssertEqual(store.thoughts, thoughts)
        XCTAssertEqual(store.tasks, tasks)
        XCTAssertEqual(store.inbox, inbox)
        XCTAssertNil(store.undoMessage)
        XCTAssertEqual(try Data(contentsOf: url), primary)
        XCTAssertTrue(store.prepareForTermination(now: now))
        XCTAssertNotNil(store.persistenceIssue)

        try FileManager.default.removeItem(at: url.appendingPathExtension("bak"))
        XCTAssertTrue(store.addIdeaEntry(ideaID: idea.id, text: "Retry finding", kind: .finding, now: now))
        XCTAssertNil(store.persistenceIssue)
        XCTAssertEqual(Store(fileURL: url).thoughts.first?.entries.map(\.text), ["Retry finding"])
    }

    func testReadOnlyStoreRejectsWorkspaceActionsWithoutChangingExistingMemory() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.addThought("Existing", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Action", now: now))
        XCTAssertTrue(store.capture("Capture"))
        let previousIdeas = store.thoughts
        let previousInbox = store.inbox
        let future = Data(#"{"schemaVersion":99,"newStorage":{}}"#.utf8)
        try future.write(to: url)
        store.load()
        XCTAssertTrue(store.isPersistenceReadOnly)
        XCTAssertNil(store.addThought("Blocked"))
        XCTAssertNil(store.keepAsThought(inboxID: previousInbox[0].id))
        XCTAssertFalse(store.updateIdea(idea.id, text: "Changed", notes: nil, nextQuestion: nil,
                                       stage: .archived, reviewAt: nil))
        XCTAssertFalse(store.addIdeaEntry(ideaID: idea.id, text: "Blocked"))
        XCTAssertNil(store.createTaskFromIdea(ideaID: idea.id, title: "Blocked"))
        XCTAssertFalse(store.unlinkTaskFromIdea(ideaID: idea.id, taskID: task.id))
        XCTAssertFalse(store.deleteThought(idea.id))
        XCTAssertEqual(store.thoughts, previousIdeas)
        XCTAssertEqual(store.inbox, previousInbox)
        XCTAssertEqual(store.tasks, [task])
        XCTAssertEqual(try Data(contentsOf: url), future)
    }

    func testImportingOlderIdeasWritesCurrentSchemaBeforeLaterEdits() throws {
        let url = try storeURL()
        let imported = url.deletingLastPathComponent().appendingPathComponent("old.json")
        let legacy = #"{"schemaVersion":4,"inbox":[],"tasks":[],"thoughts":[{"id":"82F03AD9-EAC2-4961-A610-2D74A46F266F","text":"Imported idea","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-02T09:00:00Z"}]}"#
        try Data(legacy.utf8).write(to: imported)
        let store = Store(fileURL: url)
        try store.importData(from: imported)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(json["schemaVersion"] as? Int, 5)
        XCTAssertEqual(Store(fileURL: url).thoughts.first?.text, "Imported idea")
        XCTAssertEqual(Store(fileURL: url).thoughts.first?.stage, .seed)
    }

    func testLinkedTaskLookupDeduplicatesAndToleratesMissingReferences() throws {
        let url = try storeURL()
        let store = Store(fileURL: url)
        let idea = try XCTUnwrap(store.addThought("Idea", now: now))
        let task = try XCTUnwrap(store.createTaskFromIdea(ideaID: idea.id, title: "Task", now: now))
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        var persistedIdeas = try XCTUnwrap(json["thoughts"] as? [[String: Any]])
        let missingID = UUID()
        persistedIdeas[0]["linkedTaskIDs"] = [task.id.uuidString, missingID.uuidString, task.id.uuidString]
        json["thoughts"] = persistedIdeas
        try JSONSerialization.data(withJSONObject: json).write(to: url)
        let loaded = Store(fileURL: url)

        XCTAssertEqual(loaded.ideaLinkedTasks(idea.id).map(\.id), [task.id])
        XCTAssertTrue(loaded.ideaLinkedTasks(UUID()).isEmpty)
        XCTAssertTrue(loaded.unlinkTaskFromIdea(ideaID: idea.id, taskID: task.id, now: now))
        XCTAssertEqual(loaded.thoughts.first?.linkedTaskIDs, [missingID])
        XCTAssertTrue(loaded.unlinkTaskFromIdea(ideaID: idea.id, taskID: missingID, now: now))
        XCTAssertTrue(loaded.thoughts[0].linkedTaskIDs.isEmpty)
    }
}
