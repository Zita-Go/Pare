import XCTest
@testable import PareCore

final class SyncProtocolTests: XCTestCase {
    private let device = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
    private let taskID = UUID(uuidString: "22222222-2222-4222-8222-222222222222")!
    private var key: SyncEntityKey { .uuid(kind: .task, id: taskID) }
    private var mutation: SyncMutation { .init(key: key, baseRevision: 0, payload: .object(["text": .string("手机记下，Mac 接着做")])) }

    func testDeletionWireContainsExplicitNullPayload() throws {
        let request = SyncPushRequest(deviceID: device, mutations: [.init(key: key, baseRevision: 3, payload: nil, deleted: true)])
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: request.data()) as? [String: Any])
        let changes = try XCTUnwrap(object["p_mutations"] as? [[String: Any]])
        XCTAssertTrue(changes[0].keys.contains("payload"))
        XCTAssertTrue(changes[0]["payload"] is NSNull)
        XCTAssertEqual(changes[0]["base_revision"] as? Int, 3)
        XCTAssertEqual(changes[0]["deleted"] as? Bool, true)
    }

    func testNumbersAndUnicodeRoundTripWithoutDoublePrecisionLoss() throws {
        let json = SyncJSON.object(["large": .integer(9_007_199_254_740_993), "note": .string("想法 🌱\n下一步"), "done": .bool(false)])
        let data = try SyncWire.encoder().encode(json)
        XCTAssertEqual(try SyncWire.decoder().decode(SyncJSON.self, from: data), json)
    }

    func testRejectsDuplicateKeysUnsupportedVersionsAndInvalidPayload() throws {
        XCTAssertThrowsError(try SyncPushRequest(deviceID: device, mutations: [mutation, mutation]).validate())
        var invalid = mutation; invalid.payloadVersion = 2
        XCTAssertThrowsError(try invalid.validate())
        invalid = mutation; invalid.baseRevision = Int64.max
        XCTAssertThrowsError(try invalid.validate())
        invalid = mutation; invalid.payload = .array([])
        XCTAssertThrowsError(try invalid.validate())
        invalid = mutation; invalid.payload = .object(["text": .string("bad\0value")])
        XCTAssertThrowsError(try invalid.validate())
        XCTAssertThrowsError(try SyncWire.decoder().decode(SyncEntityKind.self, from: Data("\"ai_request_history\"".utf8)))
    }

    func testBatchLimitsAndUTF8KeyLimits() throws {
        XCTAssertThrowsError(try SyncPushRequest(deviceID: device, mutations: []).validate())
        let long = SyncMutation(key: key, baseRevision: 0, payload: .object(["note": .string(String(repeating: "x", count: 1_048_576))]))
        XCTAssertThrowsError(try SyncPushRequest(deviceID: device, mutations: [long]).validate())
        XCTAssertThrowsError(try SyncEntityKey(kind: .task, id: String(repeating: "字", count: 54)).validate())
        XCTAssertThrowsError(try SyncEntityKey(kind: .task, id: " leading").validate())
    }

    private func entity(revision: Int = 1, sequence: Int = 1) -> [String: Any] {
        ["kind":"task", "id":taskID.uuidString.lowercased(), "revision":revision, "payload_version":1,
         "payload":["text":"手机记下，Mac 接着做"], "deleted_at":NSNull(),
         "last_device_id":device.uuidString.lowercased(), "sequence":sequence]
    }
    private func decode<T: Decodable>(_ type: T.Type, _ value: [String: Any]) throws -> T {
        try SyncWire.decoder().decode(type, from: JSONSerialization.data(withJSONObject: value))
    }

    func testAppliedResponseMustCoverWholeRequestedTransaction() throws {
        let request = SyncPushRequest(deviceID: device, mutations: [mutation])
        var response: [String: Any] = ["protocol_version":1,"status":"applied","sequence":1,"entities":[entity()],"conflicts":[]]
        XCTAssertNoThrow(try decode(SyncPushResponse.self, response).validate(for: request))
        response["entities"] = []
        XCTAssertThrowsError(try decode(SyncPushResponse.self, response).validate(for: request))
        response["entities"] = [entity(revision: 3)]
        XCTAssertThrowsError(try decode(SyncPushResponse.self, response).validate(for: request))
    }

    func testConflictIsScopedToSubmittedEntities() throws {
        let request = SyncPushRequest(deviceID: device, mutations: [mutation])
        let response: [String: Any] = ["protocol_version":1,"status":"conflict","sequence":1,"entities":[],
            "conflicts":[["kind":"task","id":key.id,"current":entity()]]]
        XCTAssertNoThrow(try decode(SyncPushResponse.self, response).validate(for: request))
        let wrong: [String: Any] = ["protocol_version":1,"status":"conflict","sequence":1,"entities":[],
            "conflicts":[["kind":"idea","id":"unrelated","current":NSNull()]]]
        XCTAssertThrowsError(try decode(SyncPushResponse.self, wrong).validate(for: request))
    }

    func testPostgresTombstoneTimestampAndNullPayloadDecode() throws {
        var value = entity(revision: 2, sequence: 2)
        value["payload"] = NSNull()
        value["deleted_at"] = "2026-10-29T12:00:00.123456+00:00"
        let tombstone = try decode(SyncRemoteEntity.self, value)
        XCTAssertNotNil(tombstone.deletedAt)
        XCTAssertNil(tombstone.payload)
        XCTAssertNoThrow(try tombstone.validate())
        value["deleted_at"] = "not-a-date"
        XCTAssertThrowsError(try decode(SyncRemoteEntity.self, value))
    }

    func testPullRequiresContiguousCompleteCommitsAndHonestCursor() throws {
        var response: [String: Any] = ["protocol_version":1,"cursor":1,"has_more":false,
            "commits":[["sequence":1,"operation_id":UUID().uuidString,"device_id":device.uuidString,"entities":[entity()]]]]
        XCTAssertNoThrow(try decode(SyncPullResponse.self, response).validate(after: 0))
        response["cursor"] = 2
        XCTAssertThrowsError(try decode(SyncPullResponse.self, response).validate(after: 0))
        response["cursor"] = 1
        response["commits"] = [["sequence":2,"operation_id":UUID().uuidString,"device_id":device.uuidString,"entities":[entity(sequence: 2)]]]
        XCTAssertThrowsError(try decode(SyncPullResponse.self, response).validate(after: 0))
    }

    func testDayPlanUsesExplicitPlanningTimezoneAndKeepsOtherDates() throws {
        let instant = ISO8601DateFormatter().date(from: "2026-10-29T18:30:00Z")!
        let accountZone = TimeZone(identifier: "Asia/Shanghai")!
        let key = SyncDayPlan.dayKey(for: instant, timeZone: accountZone)
        XCTAssertEqual(key, "2026-10-30")
        XCTAssertEqual(SyncDayPlan.dayKey(for: instant, timeZone: TimeZone(identifier:"America/Los_Angeles")!), "2026-10-29")
        let yesterday = SyncDayPlan(dayKey: "2026-10-29", timeZone: accountZone.identifier, orderedTaskIDs: [taskID], dailyWinTaskID: taskID)
        let today = SyncDayPlan(dayKey: key, timeZone: accountZone.identifier, orderedTaskIDs: [])
        XCTAssertNoThrow(try yesterday.validate()); XCTAssertNoThrow(try today.validate())
        XCTAssertNotEqual(yesterday.key, today.key)
        XCTAssertEqual(yesterday.dailyWinTaskID, taskID)
    }

    func testDayPlanRejectsInvalidCalendarDateOrDanglingDailyWin() throws {
        for day in ["2026-02-30", "2026-13-01", "0000-01-01", "٢٠٢٦-١٠-٣٠"] {
            XCTAssertThrowsError(try SyncDayPlan(dayKey: day, timeZone: "Asia/Shanghai", orderedTaskIDs: []).validate())
        }
        XCTAssertThrowsError(try SyncDayPlan(dayKey: "2026-10-30", timeZone: "invalid-zone", orderedTaskIDs: []).validate())
        XCTAssertThrowsError(try SyncDayPlan(dayKey: "2026-10-30", timeZone: "UTC", orderedTaskIDs: [], dailyWinTaskID: taskID).validate())
        XCTAssertThrowsError(try SyncDayPlan(dayKey: "2026-10-30", timeZone: "UTC", orderedTaskIDs: [taskID, taskID]).validate())
    }
}
