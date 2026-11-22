import XCTest
@testable import PareCore

final class ExecutionPresenceTests: XCTestCase {
    func testWindowsPeerIsAcceptedWithoutAcceptingArbitraryDeviceNames() throws {
        var reply = response()
        reply.devices[0].label = "Windows"
        XCTAssertNoThrow(try reply.validate())
        XCTAssertNoThrow(try ExecutionPresenceRequest(deviceID: device, sequence: 1, label: "Windows", runs: []).data())
        reply.devices[0].label = "Workstation title"
        XCTAssertThrowsError(try reply.validate())
    }
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let device = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
    private let task = UUID(uuidString: "22222222-2222-4222-8222-222222222222")!

    private func response(lastSeen: Date? = nil, expires: Date? = nil, runs: [ExecutionPresenceRun]? = nil) -> ExecutionPresenceResponse {
        .init(version: 1, serverNow: now, devices: [.init(deviceID: device, sequence: 1, label: "Android", lastSeen: lastSeen ?? now,
            expiresAt: expires ?? now.addingTimeInterval(90), runs: runs ?? [.init(runID: UUID(), taskID: task, kind: .personal,
                state: .running, startedAt: now.addingTimeInterval(-30), elapsedSeconds: 30, targetSeconds: 300)])])
    }

    func testLocalMappingHasStableIDsRealElapsedSecondsAndSeparateWaiting() throws {
        let timer = ActiveTimer(taskID: task, startedAt: now.addingTimeInterval(-62), targetMinutes: 5)
        let waiting = ParallelTaskRun(taskID: UUID(), startedAt: now.addingTimeInterval(-123), nextCheckAt: now.addingTimeInterval(30))
        let runs = ExecutionPresenceMapper.localRuns(timer: timer, parallelRuns: [waiting], deviceID: device, now: now)
        XCTAssertEqual(runs.count, 2); XCTAssertEqual(runs[0].elapsedSeconds, 62); XCTAssertEqual(runs[0].targetSeconds, 300)
        XCTAssertEqual(runs[1].kind, .waiting); XCTAssertEqual(runs[1].elapsedSeconds, 123); XCTAssertNil(runs[1].targetSeconds)
        var continued = timer; continued.targetMinutes = nil
        let later = ExecutionPresenceMapper.localRuns(timer: continued, parallelRuns: [waiting], deviceID: device, now: now.addingTimeInterval(15))
        XCTAssertEqual(runs.map(\.runID), later.map(\.runID)); XCTAssertEqual(later[0].elapsedSeconds, 77)
        XCTAssertNil(later[0].targetSeconds)
        let checkpoint=ExecutionPresenceMapper.localRuns(timer: timer, parallelRuns: [], deviceID: device, now: now.addingTimeInterval(500))[0]
        XCTAssertEqual(checkpoint.state, .running); XCTAssertEqual(checkpoint.elapsedSeconds, 562)
        XCTAssertTrue(ExecutionPresenceMapper.localRuns(timer: nil, parallelRuns: [], deviceID: device, now: now).isEmpty)
        XCTAssertNotEqual(runs[0].runID, ExecutionPresenceMapper.localRuns(timer: timer, parallelRuns: [], deviceID: UUID(), now: now)[0].runID)
    }

    func testServerAnchoredExpiryIgnoresClientClockSkewAndExpiresDuringOfflineTime() throws {
        let reply = response()
        let snapshot = ExecutionPresenceSnapshot(response: reply, observedAt: now.addingTimeInterval(86400), observedUptime: 100)
        XCTAssertTrue(snapshot.isFresh(reply.devices[0], now: now.addingTimeInterval(86400 + 89), uptime: 189))
        XCTAssertFalse(snapshot.isFresh(reply.devices[0], now: now.addingTimeInterval(86400 + 90), uptime: 190))
        // Wall-clock changes in either direction cannot change a lease's freshness.
        XCTAssertFalse(snapshot.isFresh(reply.devices[0], now: now, uptime: 191))
        XCTAssertTrue(snapshot.isFresh(reply.devices[0], now: now.addingTimeInterval(172800), uptime: 105))
        XCTAssertFalse(snapshot.isFresh(reply.devices[0], now: now, uptime: 10))
    }

    func testMalformedBoundsDuplicatesLabelsAndVersionAreRejected() throws {
        var valid = response(); XCTAssertNoThrow(try valid.validate())
        valid.version = 2; XCTAssertThrowsError(try valid.validate()); valid.version = 1
        valid.acceptedSequence = 9_007_199_254_740_992; XCTAssertThrowsError(try valid.validate()); valid.acceptedSequence = nil
        valid.devices[0].label = "untrusted arbitrary title"; XCTAssertThrowsError(try valid.validate()); valid.devices[0].label = "Mac"
        valid.devices[0].expiresAt = now.addingTimeInterval(91); XCTAssertThrowsError(try valid.validate()); valid.devices[0].expiresAt = now.addingTimeInterval(90)
        valid.devices[0].runs[0].elapsedSeconds = -1; XCTAssertThrowsError(try valid.validate()); valid.devices[0].runs[0].elapsedSeconds = 0
        valid.devices[0].runs[0].targetSeconds = 0; XCTAssertThrowsError(try valid.validate()); valid.devices[0].runs[0].targetSeconds = nil
        valid.devices.append(valid.devices[0]); XCTAssertThrowsError(try valid.validate())
        var historical = response(lastSeen: now.addingTimeInterval(-86401), expires: now.addingTimeInterval(-86311))
        XCTAssertThrowsError(try historical.validate())
        historical = response(runs: Array(repeating: valid.devices[0].runs[0], count: 33)); XCTAssertThrowsError(try historical.validate())
    }

    func testWireContainsOnlyIdentifiersAndBoundedStateAndCanDecodeFractionalTimestamp() throws {
        let run = ExecutionPresenceMapper.localRuns(timer: ActiveTimer(taskID: task, startedAt: now), parallelRuns: [], deviceID: device, now: now)[0]
        let request = ExecutionPresenceRequest(deviceID: device, sequence: 3, label: "Mac", runs: [run])
        let data = try request.data()
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(object.keys), Set(["p_device_id", "p_sequence", "p_label", "p_runs"]))
        let item = try XCTUnwrap((object["p_runs"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(item.keys), Set(["run_id", "task_id", "kind", "state", "started_at", "elapsed_seconds"]))
        XCTAssertEqual(item["task_id"] as? String, task.uuidString.lowercased())
        var encoded = String(data: try SyncWire.encoder().encode(response()), encoding: .utf8)!
        encoded = encoded.replacingOccurrences(of: "Z\"", with: ".123Z\"")
        let decoded = try ExecutionPresenceResponse.decode(Data(encoded.utf8))
        XCTAssertEqual(decoded.devices.count, 1)
        XCTAssertThrowsError(try ExecutionPresenceResponse.decode(Data("{\"secret\":\"should not leak\"}".utf8))) { error in
            XCTAssertEqual(error as? SyncHTTPError, .invalidResponse); XCTAssertFalse(error.localizedDescription.contains("secret"))
        }
    }

    func testSequenceSurvivesRecreationAndIsScopedToAccountAndDevice() throws {
        let suite = "ParePresenceTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite)); defer { defaults.removePersistentDomain(forName: suite) }
        let account = UUID(); let sequences = ExecutionPresenceSequenceStore(defaults: defaults)
        XCTAssertEqual(try sequences.next(accountID: account, deviceID: device), 1)
        XCTAssertEqual(try ExecutionPresenceSequenceStore(defaults: defaults).next(accountID: account, deviceID: device), 2)
        XCTAssertEqual(try sequences.next(accountID: UUID(), deviceID: device), 1)
        XCTAssertEqual(try sequences.next(accountID: account, deviceID: UUID()), 1)
    }

    func testRunIdentitySurvivesStoresSecondPrecisionDatePersistence() throws {
        let timer=ActiveTimer(taskID: task, startedAt: now.addingTimeInterval(-2.345), targetMinutes: 5)
        let encoder=JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder=JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let restored=try decoder.decode(ActiveTimer.self, from: encoder.encode(timer))
        let before=ExecutionPresenceMapper.localRuns(timer: timer, parallelRuns: [], deviceID: device, now: now)
        let after=ExecutionPresenceMapper.localRuns(timer: restored, parallelRuns: [], deviceID: device, now: now)
        XCTAssertEqual(before[0].runID, after[0].runID)
    }

    func testServerSequenceRecoveryPersistsHighWatermarkWithoutMovingBackwardOrCrossingScopes() throws {
        let suite="ParePresenceTests.\(UUID().uuidString)"
        let defaults=try XCTUnwrap(UserDefaults(suiteName:suite));defer {defaults.removePersistentDomain(forName:suite)}
        let account=UUID(), otherAccount=UUID(), otherDevice=UUID()
        let sequences=ExecutionPresenceSequenceStore(defaults:defaults)
        XCTAssertEqual(try sequences.next(accountID:account,deviceID:device),1)
        try sequences.advance(accountID:account,deviceID:device,to:50_000)
        let restored=ExecutionPresenceSequenceStore(defaults:try XCTUnwrap(UserDefaults(suiteName:suite)))
        XCTAssertEqual(try restored.next(accountID:account,deviceID:device),50_001)
        try sequences.advance(accountID:account,deviceID:device,to:20)
        XCTAssertEqual(try restored.next(accountID:account,deviceID:device),50_002)
        try sequences.advance(accountID:otherAccount,deviceID:device,to:90_000)
        try sequences.advance(accountID:account,deviceID:otherDevice,to:80_000)
        XCTAssertEqual(try restored.next(accountID:account,deviceID:device),50_003)
        XCTAssertEqual(try restored.next(accountID:otherAccount,deviceID:device),90_001)
        XCTAssertEqual(try restored.next(accountID:account,deviceID:otherDevice),80_001)
        for invalid:Int64 in [0,-1,9_007_199_254_740_992] {
            XCTAssertThrowsError(try sequences.advance(accountID:account,deviceID:device,to:invalid))
        }
        XCTAssertEqual(try restored.next(accountID:account,deviceID:device),50_004)
    }
}
