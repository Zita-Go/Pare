import XCTest
@testable import PareCore

final class StoreSyncTests: XCTestCase {
    private func file() throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: folder) }
        return folder.appendingPathComponent("store.json")
    }
    func testSchemaSixMigrationWritesSevenAndPreservesPreviousGeneration() throws {
        let url = try file()
        let old = Data(#"{"schemaVersion":6,"inbox":[],"tasks":[]}"#.utf8)
        try old.write(to: url)
        let store = Store(fileURL: url)
        XCTAssertTrue(store.retrySaving())
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(json["schemaVersion"] as? Int, 7)
        XCTAssertEqual(try Data(contentsOf: url.appendingPathExtension("bak")), old)
    }

    private func connect(_ store: Store) throws {
        try store.bindSyncAccount(accountID: "test-owner")
        let response = try SyncWire.decoder().decode(SyncPullResponse.self, from: Data(#"{"protocol_version":1,"cursor":0,"has_more":false,"commits":[]}"#.utf8))
        try store.receiveSyncPullResponse(response)
        try store.confirmSyncMerge()
    }
    func testRetryIsDurableImmutableAndLocalEditsWaitBehindIt() throws {
        let url = try file(), store = Store(fileURL: try file())
        _ = store.addTask("initial", to: .importantUrgent)
        try connect(store)
        let first = try XCTUnwrap(store.nextSyncPushRequest())
        _ = store.addTask("later", to: .importantUrgent)
        XCTAssertEqual(try store.nextSyncPushRequest()?.data(), try first.data())
        try store.exportData(to: url)
        let reloaded = Store(fileURL: url)
        XCTAssertEqual(try reloaded.nextSyncPushRequest()?.data(), try first.data())
    }
    func testFirstConnectionRequiresConfirmationAndBlocksOtherAccount() throws {
        let store = Store(fileURL: try file())
        try store.bindSyncAccount(accountID: "alice")
        XCTAssertThrowsError(try store.nextSyncPushRequest())
        XCTAssertThrowsError(try store.bindSyncAccount(accountID: "bob"))
    }
    func testTaskPayloadExcludesDeviceAndDailyFields() throws {
        let task = TaskItem(text: "private timer", quadrant: .importantUrgent, isToday: true,
                            scheduledAt: Date(), scheduledMinutes: 25, lastImplementationTriggeredAt: Date())
        let payload = try SyncPayload.task(task, archived: false)
        let text = String(decoding: try SyncWire.encoder().encode(payload), as: UTF8.self)
        for key in ["isToday", "scheduledAt", "scheduledMinutes", "lastImplementationTriggeredAt"] { XCTAssertFalse(text.contains(key)) }
        XCTAssertFalse(try SyncPayload.decodeTask(payload).0.isToday)
        XCTAssertTrue(text.contains(task.id.uuidString.lowercased()))
    }

    private func remote(_ task: TaskItem, revision: Int64, sequence: Int64, deleted: Bool = false) throws -> SyncRemoteEntity {
        SyncRemoteEntity(kind: .task, id: task.id.uuidString.lowercased(), revision: revision, payloadVersion: 1,
                         payload: deleted ? nil : try SyncPayload.task(task, archived: false), deletedAt: deleted ? Date() : nil,
                         lastDeviceID: UUID(), sequence: sequence)
    }
    private func pull(_ entities: [SyncRemoteEntity], after: Int64) -> SyncPullResponse {
        SyncPullResponse(protocolVersion: 1, cursor: after + 1, hasMore: false,
            commits: [SyncCommit(sequence: after + 1, operationID: UUID(), deviceID: entities[0].lastDeviceID, entities: entities)])
    }
    private func acknowledge(_ store: Store, sequence: Int64 = 1) throws -> [SyncRemoteEntity] {
        let request = try XCTUnwrap(store.nextSyncPushRequest())
        let entities = request.mutations.map { m in
            SyncRemoteEntity(kind: m.kind, id: m.id, revision: m.baseRevision + 1, payloadVersion: 1,
                payload: m.payload, deletedAt: m.deleted ? Date() : nil, lastDeviceID: request.deviceID, sequence: sequence)
        }
        try store.receiveSyncPushResponse(SyncPushResponse(protocolVersion: 1, status: .applied, sequence: sequence,
            entities: entities, conflicts: []), for: request)
        let response = SyncPullResponse(protocolVersion: 1, cursor: sequence, hasMore: false,
            commits: [SyncCommit(sequence: sequence, operationID: request.operationID, deviceID: request.deviceID, entities: entities)])
        try store.receiveSyncPullResponse(response)
        return entities
    }
    func testRemoteApplyDoesNotEchoOrChangeLocalTimerOrSettings() throws {
        let store = Store(fileURL: try file())
        try connect(store); _ = try acknowledge(store)
        let task = TaskItem(text: "remote", quadrant: .importantUrgent)
        let entity = try remote(task, revision: 1, sequence: 2)
        let settings = store.settings
        try store.receiveSyncPullResponse(pull([entity], after: 1))
        XCTAssertEqual(store.tasks.first?.text, "remote")
        XCTAssertEqual(store.settings, settings)
        XCTAssertNil(store.activeTimer)
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
        XCTAssertNil(try store.nextSyncPushRequest())
    }
    func testConcurrentChangeRetainsAllVersionsAndResolutionUsesNewOperation() throws {
        let store = Store(fileURL: try file())
        let id = store.addTask("base", to: .importantUrgent)!.id
        try connect(store); _ = try acknowledge(store)
        store.setToday(id, true)
        let request = try XCTUnwrap(store.nextSyncPushRequest())
        var task = try XCTUnwrap(store.tasks.first); task.text = "cloud"
        let entity = try remote(task, revision: 2, sequence: 2)
        try store.receiveSyncPushResponse(SyncPushResponse(protocolVersion: 1, status: .conflict, sequence: 2,
            entities: [], conflicts: [SyncConflict(kind: .task, id: id.uuidString.lowercased(), current: entity)]), for: request)
        let conflict = try XCTUnwrap(store.syncState?.conflicts.first)
        XCTAssertNotNil(conflict.base); XCTAssertNotNil(conflict.local); XCTAssertEqual(conflict.remote, entity)
        XCTAssertThrowsError(try store.nextSyncPushRequest())
        try store.resolveSyncConflict(key: conflict.key, choice: .local)
        let retry = try XCTUnwrap(store.nextSyncPushRequest())
        XCTAssertNotEqual(request.operationID, retry.operationID)
        XCTAssertEqual(retry.mutations.first { $0.key == entity.key }?.baseRevision, 2)
    }
    func testInvalidSecondEntityDoesNotApplyFirstOrAdvanceCursor() throws {
        let store = Store(fileURL: try file()); try connect(store); _ = try acknowledge(store)
        let first = try remote(TaskItem(text: "valid", quadrant: .neither), revision: 1, sequence: 2)
        var invalid = try remote(TaskItem(text: "bad", quadrant: .neither), revision: 1, sequence: 2)
        invalid.payload = .object(["content": .object(["text": .string("missing fields")])]); invalid.lastDeviceID = first.lastDeviceID
        XCTAssertThrowsError(try store.receiveSyncPullResponse(pull([first, invalid], after: 1)))
        XCTAssertTrue(store.tasks.isEmpty); XCTAssertEqual(store.syncState?.cursor, 1)
    }
    func testDayRolloverRetainsOldPlanAndDoesNotDirtyTask() throws {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let day = Date(timeIntervalSince1970: 1_800_000_000), store = Store(fileURL: try file(), calendar: calendar)
        _ = store.rolloverIfNeeded(now: day)
        _ = store.addTask("today", to: .neither, addToToday: true, now: day)
        try connect(store); _ = try acknowledge(store)
        let plan = try XCTUnwrap(store.syncDayPlans.first)
        XCTAssertTrue(store.rolloverIfNeeded(now: day.addingTimeInterval(86400)))
        XCTAssertEqual(store.syncDayPlans.first { $0.key == plan.key }, plan)
        XCTAssertFalse(store.tasks[0].isToday)
        XCTAssertFalse(store.syncState!.pending.contains { $0.kind == .task })
    }
    func testBackupImportSuspendsSyncAndCannotReuseQueue() throws {
        let store = Store(fileURL: try file()), backup = try file()
        try connect(store); _ = try store.nextSyncPushRequest()
        try store.exportData(to: backup)
        try store.importData(from: backup)
        XCTAssertFalse(store.syncState!.enabled)
        XCTAssertNil(store.syncState!.inflight)
        XCTAssertFalse(store.syncState!.mergeConfirmed)
        XCTAssertThrowsError(try store.nextSyncPushRequest())
    }
    func testRemoteSaveFailureRollsBackContentAndCursor() throws {
        let url = try file(), store = Store(fileURL: url)
        try connect(store); _ = try acknowledge(store)
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        let entity = try remote(TaskItem(text: "not committed", quadrant: .neither), revision: 1, sequence: 2)
        XCTAssertThrowsError(try store.receiveSyncPullResponse(pull([entity], after: 1)))
        XCTAssertTrue(store.tasks.isEmpty); XCTAssertEqual(store.syncState?.cursor, 1)
    }

    func testConflictingPullHoldsWholeCommitUntilAllChoicesAreMade() throws {
        let store = Store(fileURL: try file())
        let original = store.addTask("base", to: .neither)!
        try connect(store); _ = try acknowledge(store)
        store.setToday(original.id, true)
        var changed = original; changed.text = "cloud"
        let first = try remote(changed, revision: 2, sequence: 2)
        var second = try remote(TaskItem(text: "paired new task", quadrant: .neither), revision: 1, sequence: 2)
        second.lastDeviceID = first.lastDeviceID
        try store.receiveSyncPullResponse(pull([first, second], after: 1))
        XCTAssertEqual(store.tasks.count, 1)
        XCTAssertEqual(store.syncState?.conflicts.count, 2)
        try store.resolveSyncConflict(key: first.key, choice: .cloud)
        XCTAssertEqual(store.tasks.first?.text, "base")
        try store.resolveSyncConflict(key: second.key, choice: .cloud)
        XCTAssertEqual(Set(store.tasks.map(\.text)), ["cloud", "paired new task"])
    }
    func testUnknownFieldsSurviveRemoteApplyAndSubsequentLocalEdit() throws {
        let store = Store(fileURL: try file()); try connect(store); _ = try acknowledge(store)
        let task = TaskItem(text: "raw", quadrant: .neither)
        var entity = try remote(task, revision: 1, sequence: 2)
        guard case .object(var wrapper) = entity.payload, case .object(var content) = wrapper["content"] else { return XCTFail() }
        content["futureFeature"] = .string("preserve me"); wrapper["content"] = .object(content); entity.payload = .object(wrapper)
        try store.receiveSyncPullResponse(pull([entity], after: 1))
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
        store.setToday(task.id, true)
        let request = try XCTUnwrap(store.nextSyncPushRequest())
        let changed = try XCTUnwrap(request.mutations.first { $0.key == entity.key }?.payload)
        guard case .object(let w) = changed, case .object(let c) = w["content"] else { return XCTFail() }
        XCTAssertEqual(c["futureFeature"], .string("preserve me"))
    }

    func testProfileDeletionDoesNotResurrectAsDefaultProfile() throws {
        let store = Store(fileURL: try file()); try connect(store); _ = try acknowledge(store)
        let entity = SyncRemoteEntity(kind: .profile, id: "profile", revision: 2, payloadVersion: 1,
            payload: nil, deletedAt: Date(), lastDeviceID: UUID(), sequence: 2)
        try store.receiveSyncPullResponse(pull([entity], after: 1))
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
        store.setAIBackground("new explicit background")
        XCTAssertEqual(store.syncState?.pending.first { $0.kind == .profile }?.baseRevision, 2)
    }
    func testFirstPullStagesChangesUntilConfirmation() throws {
        let store = Store(fileURL: try file()); try store.bindSyncAccount(accountID: "owner")
        let entity = try remote(TaskItem(text: "cloud only", quadrant: .neither), revision: 1, sequence: 1)
        try store.receiveSyncPullResponse(pull([entity], after: 0))
        XCTAssertTrue(store.tasks.isEmpty)
        XCTAssertEqual(store.syncMergePreview?.cloudOnlyCount, 1)
        try store.confirmSyncMerge()
        XCTAssertEqual(store.tasks.first?.text, "cloud only")
        XCTAssertFalse(store.syncState!.pending.contains { $0.kind == .task })
    }
    func testDeletingRemoteTaskPreservesRunningLocalTimerAndPrivateState() throws {
        let store = Store(fileURL: try file())
        let task = store.addTask("running", to: .neither)!
        try connect(store); _ = try acknowledge(store)
        XCTAssertTrue(store.startTimer(task.id))
        let timer = store.activeTimer, settings = store.settings, histories = store.planningRuns
        // Starting an unscheduled timer changes no shared task content.
        let entity = try remote(task, revision: 2, sequence: 2, deleted: true)
        try store.receiveSyncPullResponse(pull([entity], after: 1))
        XCTAssertEqual(store.activeTimer, timer)
        XCTAssertEqual(store.settings, settings)
        XCTAssertEqual(store.planningRuns, histories)
        XCTAssertEqual(store.tasks.first?.id, task.id)
        XCTAssertEqual(store.syncState?.conflicts.count, 1)
        XCTAssertThrowsError(try store.resolveSyncConflict(key: entity.key, choice: .cloud))
        XCTAssertEqual(store.stopTimer(now: timer!.startedAt.addingTimeInterval(60)), 1)
        XCTAssertNotNil(store.tasks.first?.progressSessions?.first)
        try store.resolveSyncConflict(key: entity.key, choice: .cloud)
        XCTAssertTrue(store.tasks.isEmpty)
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
    }
    func testWireWhitelistDoesNotContainSettingsOrAIHistory() throws {
        let store = Store(fileURL: try file())
        let task = store.addTask("running", to: .neither)!
        store.setAIBackground("explicit user background")
        XCTAssertTrue(store.startTimer(task.id))
        try connect(store)
        let data = try XCTUnwrap(store.nextSyncPushRequest()).data()
        let string = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(string.contains("explicit user background"))
        for key in ["settings", "activeTimer", "parallelRuns", "aiSummaries", "planningRuns", "intelligenceAdoptions", "intelligenceFeedback", "apiKey"] {
            XCTAssertFalse(string.contains("\"" + key + "\""))
        }
    }

    func testStartingScheduledTimerKeepsSharedReminderAndOnlySuppressesLocalDelivery() throws {
        let store = Store(fileURL: try file())
        let task = store.addTask("scheduled", to: .neither)!
        store.scheduleTask(task.id, at: Date().addingTimeInterval(3600), durationMinutes: 25)
        store.setScheduleReminder(task.id, leadMinutes: 5)
        try connect(store); _ = try acknowledge(store)
        XCTAssertTrue(store.startTimer(task.id))
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
        XCTAssertEqual(store.syncDayPlans.first?.taskPlans.first?.scheduleReminderLeadMinutes, 5)
        let reloaded = Store(fileURL: store.dataFileURL)
        XCTAssertNil(reloaded.tasks.first?.scheduleReminderLeadMinutes)
        XCTAssertEqual(reloaded.syncDayPlans.first?.taskPlans.first?.scheduleReminderLeadMinutes, 5)
    }

    func testMigrationRetainsHistoricalAllocationsAsSeparateDayPlans() throws {
        let url = try file(), store = Store(fileURL: url)
        let task = store.addTask("allocation", to: .neither)!
        let yesterday = Date().addingTimeInterval(-86400)
        try store.setDailyAllocation(taskID: task.id, plannedMinutes: 20, now: yesterday)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        json["schemaVersion"] = 6; json.removeValue(forKey: "syncDayPlans")
        try JSONSerialization.data(withJSONObject: json).write(to: url)
        let migrated = Store(fileURL: url)
        let dayKey = SyncDayPlan.dayKey(for: yesterday, timeZone: .current)
        XCTAssertEqual(migrated.syncDayPlans.first { $0.dayKey == dayKey }?.taskPlans.first?.allocation?.plannedMinutes, 20)
    }
    func testDayPlanLegacyPayloadAndNestedIDsRemainCompatible() throws {
        let id = UUID()
        let raw = Data("{\"day_key\":\"2026-10-30\",\"time_zone\":\"Asia/Shanghai\",\"ordered_task_ids\":[\"\(id.uuidString)\"]}".utf8)
        let plan = try SyncWire.decoder().decode(SyncDayPlan.self, from: raw)
        XCTAssertTrue(plan.taskPlans.isEmpty)
        let encoded = String(decoding: try SyncWire.encoder().encode(SyncPayload.wrap(plan)), as: UTF8.self)
        XCTAssertTrue(encoded.contains(id.uuidString.lowercased()))
    }

    func testFailedLocalTransactionCannotLeaveGhostOutboxMutation() throws {
        let url = try file(), store = Store(fileURL: url)
        let idea = store.addThought("must retain")!
        try connect(store); _ = try acknowledge(store)
        let backup = url.appendingPathExtension("bak")
        try FileManager.default.removeItem(at: backup)
        try FileManager.default.createDirectory(at: backup, withIntermediateDirectories: false)
        XCTAssertFalse(store.deleteThought(idea.id))
        XCTAssertEqual(store.thoughts.first?.id, idea.id)
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
        XCTAssertNil(try store.nextSyncPushRequest())
    }

    func testAutomaticRecoverySuspendsOldSyncAuthority() throws {
        let url = try file(), store = Store(fileURL: url)
        try connect(store); _ = try store.nextSyncPushRequest()
        XCTAssertTrue(store.retrySaving())
        try Data("corrupted".utf8).write(to: url)
        let restored = Store(fileURL: url)
        XCTAssertEqual(restored.syncState?.enabled, false)
        XCTAssertEqual(restored.syncState?.mergeConfirmed, false)
        XCTAssertNil(restored.syncState?.inflight)
        XCTAssertThrowsError(try restored.nextSyncPushRequest())
    }

    func testLargeIndependentLibraryUploadsInBoundedDurableBatches() throws {
        let store = Store(fileURL: try file())
        for i in 0..<105 { _ = store.addTask("task \(i)", to: .neither) }
        try connect(store)
        let first = try XCTUnwrap(store.nextSyncPushRequest())
        XCTAssertEqual(first.mutations.count, 100)
        _ = try acknowledge(store)
        XCTAssertEqual(store.pendingSyncChangeCount, 6)
        _ = try acknowledge(store, sequence: 2)
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
    }
    func testLocalMultiEntityActionIsNeverSplitAtBatchBoundary() throws {
        let store = Store(fileURL: try file())
        try connect(store); _ = try acknowledge(store)
        let idea = store.addThought("parent")!
        _ = try acknowledge(store, sequence: 2)
        for i in 0..<99 { _ = store.addTask("independent \(i)", to: .neither) }
        let linked = store.createTaskFromIdea(ideaID: idea.id, title: "atomic child")!
        let loaded = Store(fileURL: store.dataFileURL)
        let request = try XCTUnwrap(loaded.nextSyncPushRequest())
        let hasTask = request.mutations.contains { $0.id == linked.id.uuidString.lowercased() }
        let hasIdea = request.mutations.contains { $0.id == idea.id.uuidString.lowercased() }
        XCTAssertEqual(hasTask, hasIdea)
        XCTAssertLessThanOrEqual(request.mutations.count, 100)
    }
    func testInitialMergeHoldsConflictedCloudTransactionTogether() throws {
        let store = Store(fileURL: try file())
        let original = store.addTask("local", to: .neither)!
        var altered = original; altered.text = "cloud"
        let first = try remote(altered, revision: 1, sequence: 1)
        var second = try remote(TaskItem(text: "cloud pair", quadrant: .neither), revision: 1, sequence: 1)
        second.lastDeviceID = first.lastDeviceID
        try store.bindSyncAccount(accountID: "owner")
        try store.receiveSyncPullResponse(pull([first, second], after: 0))
        XCTAssertEqual(store.syncMergePreview?.conflictCount, 2)
        try store.confirmSyncMerge()
        XCTAssertEqual(store.tasks.count, 1)
        XCTAssertEqual(store.syncState?.conflicts.count, 2)
    }
    func testHeldOldCommitNeverRegressesAlreadyAcknowledgedRevision() throws {
        let store = Store(fileURL: try file())
        let a = store.addTask("A1", to: .neither)!, b = store.addTask("B1", to: .neither)!
        try connect(store); _ = try acknowledge(store)
        store.setEstimate(a.id, minutes: 10)
        let request = try XCTUnwrap(store.nextSyncPushRequest())
        let a2 = SyncRemoteEntity(kind: .task, id: a.id.uuidString.lowercased(), revision: 2, payloadVersion: 1,
            payload: request.mutations.first!.payload, deletedAt: nil, lastDeviceID: request.deviceID, sequence: 3)
        try store.receiveSyncPushResponse(SyncPushResponse(protocolVersion: 1, status: .applied, sequence: 3, entities: [a2], conflicts: []), for: request)
        store.setEstimate(b.id, minutes: 20)
        var oldA = try remote(a, revision: 1, sequence: 2)
        var changedB = b; changedB.text = "B cloud"
        let remoteB = try remote(changedB, revision: 2, sequence: 2)
        oldA.lastDeviceID = remoteB.lastDeviceID
        try store.receiveSyncPullResponse(pull([oldA, remoteB], after: 1))
        XCTAssertFalse(store.syncState!.conflicts.contains { $0.key == oldA.key })
        try store.resolveSyncConflict(key: remoteB.key, choice: .cloud)
        XCTAssertEqual(store.tasks.first { $0.id == a.id }?.estimatedMinutes, 10)
    }

    func testAllocationUsesAccountDayWhenDeviceIsInAnotherTimeZone() throws {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let url = try file(), store = Store(fileURL: url, calendar: calendar)
        let task = store.addTask("cross zone", to: .neither)!
        try connect(store); _ = try acknowledge(store)
        let profile = SyncProfile(background: "", preferences: AgentPreferences(), confirmedPreferences: [], planningTimeZone: "Asia/Shanghai")
        let entity = SyncRemoteEntity(kind: .profile, id: "profile", revision: 2, payloadVersion: 1,
            payload: try SyncPayload.wrap(profile), deletedAt: nil, lastDeviceID: UUID(), sequence: 2)
        try store.receiveSyncPullResponse(pull([entity], after: 1))
        let now = ISO8601DateFormatter().date(from: "2026-10-31T01:00:00Z")!
        _ = store.rolloverIfNeeded(now: now)
        store.setToday(task.id, true, now: now)
        try store.setDailyAllocation(taskID: task.id, plannedMinutes: 20, now: now)
        XCTAssertEqual(store.syncDayPlans.first { $0.dayKey == "2026-10-31" && $0.timeZone == "Asia/Shanghai" }?.taskPlans.first?.allocation?.plannedMinutes, 20)
        XCTAssertEqual(store.dailyLoad(now: now).plannedMinutes, 20)
        let reloaded = Store(fileURL: url, calendar: calendar)
        XCTAssertEqual(reloaded.dailyLoad(now: now).plannedMinutes, 20)
        XCTAssertEqual(try reloaded.prepareIntelligenceRequest(intent: .today, now: now).timeZone, "Asia/Shanghai")
    }

    func testDayPlanRetainsUnknownTaskReferencesWithoutEchoOnLaterSave() throws {
        let store = Store(fileURL: try file()); try connect(store); _ = try acknowledge(store)
        let taskID = UUID(), day = SyncDayPlan.dayKey(for: Date(), timeZone: .current)
        let plan = SyncDayPlan(dayKey: day, timeZone: TimeZone.current.identifier, orderedTaskIDs: [taskID], dailyWinTaskID: taskID,
            taskPlans: [SyncTaskPlan(taskID: taskID, scheduledAt: Date(), scheduledMinutes: 25)])
        let entity = SyncRemoteEntity(kind: .dayPlan, id: plan.key.id, revision: 1, payloadVersion: 1,
            payload: try SyncPayload.wrap(plan), deletedAt: nil, lastDeviceID: UUID(), sequence: 2)
        try store.receiveSyncPullResponse(pull([entity], after: 1))
        XCTAssertTrue(store.retrySaving())
        XCTAssertEqual(store.pendingSyncChangeCount, 0)
        XCTAssertEqual(store.syncDayPlans.first?.orderedTaskIDs, [taskID])
    }
    func testArchivedYesterdayScheduleDoesNotBecomeNewDaysPlan() throws {
        let store = Store(fileURL: try file()), now = Date()
        _ = store.rolloverIfNeeded(now: now)
        let task = store.addTask("completed schedule", to: .neither, addToToday: true)!
        store.scheduleTask(task.id, at: now, durationMinutes: 30)
        store.setCompleted(task.id, true, now: now)
        _ = store.rolloverIfNeeded(now: now.addingTimeInterval(86400))
        let tomorrow = SyncDayPlan.dayKey(for: now.addingTimeInterval(86400), timeZone: .current)
        XCTAssertNil(store.syncDayPlans.first { $0.dayKey == tomorrow })
    }

    func testAtomicDeletionOfInflightCreatesSurvivesAcknowledgement() throws {
        let ids = [UUID(uuidString: "00000000-0000-4000-8000-000000000000")!, UUID(uuidString: "ffffffff-ffff-4fff-bfff-ffffffffffff")!]
        let initial = ids.map { TaskItem(id: $0, text: "inflight", quadrant: .neither) }
        let taskJSON = try JSONSerialization.jsonObject(with: SyncWire.encoder().encode(initial))
        let url = try file()
        try JSONSerialization.data(withJSONObject: ["schemaVersion": 7, "inbox": [], "tasks": taskJSON]).write(to: url)
        let store = Store(fileURL: url); try connect(store)
        _ = try XCTUnwrap(store.nextSyncPushRequest())
        XCTAssertEqual(store.clearQuadrant(.neither), 2)
        for i in 0..<99 { _ = store.addTask("later \(i)", to: .importantUrgent) }
        _ = try acknowledge(store)
        let request = try XCTUnwrap(store.nextSyncPushRequest())
        let deletedIDs = Set(request.mutations.filter(\.deleted).map(\.id))
        XCTAssertTrue(deletedIDs.isEmpty || deletedIDs == Set(ids.map { $0.uuidString.lowercased() }))
    }

    func testLocalEditInvalidatesEveryPendingChoiceInConflictGroup() throws {
        let store = Store(fileURL: try file())
        let a = store.addTask("A local", to: .neither)!, b = store.addTask("B local", to: .neither)!
        try connect(store); _ = try acknowledge(store)
        store.setEstimate(a.id, minutes: 10)
        var cloudA = a; cloudA.text = "A cloud"
        var cloudB = b; cloudB.text = "B cloud"
        let first = try remote(cloudA, revision: 2, sequence: 2)
        var second = try remote(cloudB, revision: 2, sequence: 2)
        second.lastDeviceID = first.lastDeviceID
        try store.receiveSyncPullResponse(pull([first, second], after: 1))
        try store.resolveSyncConflict(key: first.key, choice: .cloud)
        XCTAssertEqual(store.syncState?.conflicts.first { $0.key == first.key }?.choice, .cloud)
        store.setEstimate(a.id, minutes: 20)
        XCTAssertTrue(store.syncState!.conflicts.allSatisfy { $0.choice == nil })
        let loaded = Store(fileURL: store.dataFileURL)
        try loaded.resolveSyncConflict(key: second.key, choice: .cloud)
        XCTAssertEqual(loaded.tasks.first { $0.id == a.id }?.estimatedMinutes, 20)
        XCTAssertEqual(loaded.tasks.first { $0.id == b.id }?.text, "B local")
        try loaded.resolveSyncConflict(key: first.key, choice: .local)
        XCTAssertEqual(loaded.tasks.first { $0.id == a.id }?.estimatedMinutes, 20)
        XCTAssertEqual(loaded.tasks.first { $0.id == b.id }?.text, "B cloud")
    }
}
