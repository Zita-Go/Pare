import Foundation
import CryptoKit

/// Ephemeral advisory state. Never store these objects in Store snapshots or the content mutation stream.
public struct ExecutionPresenceRun: Codable, Equatable, Sendable, Identifiable {
    public enum Kind: String, Codable, Sendable { case personal, waiting }
    public enum State: String, Codable, Sendable { case running, paused }
    public var runID: UUID
    public var taskID: UUID
    public var kind: Kind
    public var state: State
    public var startedAt: Date
    public var elapsedSeconds: Int
    public var targetSeconds: Int?
    public var id: UUID { runID }
    enum CodingKeys: String, CodingKey {
        case runID = "run_id", taskID = "task_id", kind, state, startedAt = "started_at"
        case elapsedSeconds = "elapsed_seconds", targetSeconds = "target_seconds"
    }
    public init(runID: UUID, taskID: UUID, kind: Kind, state: State, startedAt: Date, elapsedSeconds: Int, targetSeconds: Int?) {
        self.runID=runID; self.taskID=taskID; self.kind=kind; self.state=state
        self.startedAt=startedAt; self.elapsedSeconds=elapsedSeconds; self.targetSeconds=targetSeconds
    }
    public func encode(to encoder: Encoder) throws {
        var c=encoder.container(keyedBy: CodingKeys.self)
        try c.encode(runID.uuidString.lowercased(), forKey: .runID); try c.encode(taskID.uuidString.lowercased(), forKey: .taskID)
        try c.encode(kind, forKey: .kind); try c.encode(state, forKey: .state); try c.encode(startedAt, forKey: .startedAt)
        try c.encode(elapsedSeconds, forKey: .elapsedSeconds); try c.encodeIfPresent(targetSeconds, forKey: .targetSeconds)
    }
    public func validate() throws {
        guard (0...31_536_000).contains(elapsedSeconds), targetSeconds.map({ (1...31_536_000).contains($0) }) ?? true,
              startedAt.timeIntervalSince1970.isFinite, (0...253_402_300_799).contains(startedAt.timeIntervalSince1970) else { throw SyncHTTPError.invalidResponse }
    }
}

public struct ExecutionPresenceDevice: Codable, Equatable, Sendable, Identifiable {
    public var deviceID: UUID
    public var sequence: Int64
    public var label: String
    public var lastSeen: Date
    public var expiresAt: Date
    public var runs: [ExecutionPresenceRun]
    public var id: UUID { deviceID }
    enum CodingKeys: String, CodingKey { case deviceID = "device_id", sequence, label, lastSeen = "last_seen", expiresAt = "expires_at", runs }
    public init(deviceID: UUID, sequence: Int64, label: String, lastSeen: Date, expiresAt: Date, runs: [ExecutionPresenceRun]) {
        self.deviceID=deviceID; self.sequence=sequence; self.label=label; self.lastSeen=lastSeen; self.expiresAt=expiresAt; self.runs=runs
    }
}

public struct ExecutionPresenceRequest: Encodable, Sendable {
    public var deviceID: UUID
    public var sequence: Int64
    public var label: String
    public var runs: [ExecutionPresenceRun]
    enum CodingKeys: String, CodingKey { case deviceID = "p_device_id", sequence = "p_sequence", label = "p_label", runs = "p_runs" }
    public init(deviceID: UUID, sequence: Int64, label: String, runs: [ExecutionPresenceRun]) {
        self.deviceID=deviceID; self.sequence=sequence; self.label=label; self.runs=runs
    }
    public func encode(to encoder: Encoder) throws {
        var c=encoder.container(keyedBy: CodingKeys.self)
        try c.encode(deviceID.uuidString.lowercased(), forKey: .deviceID); try c.encode(sequence, forKey: .sequence)
        try c.encode(label, forKey: .label); try c.encode(runs, forKey: .runs)
    }
    public func data() throws -> Data {
        do {
            guard (1...9_007_199_254_740_991).contains(sequence), ["Mac", "Android", "Windows"].contains(label) else { throw SyncHTTPError.invalidRequest }
            try validatePresenceRuns(runs)
            let data=try SyncWire.encoder().encode(self)
            guard data.count <= 32_768 else { throw SyncHTTPError.invalidRequest }
            return data
        } catch { throw SyncHTTPError.invalidRequest }
    }
}

public struct ExecutionPresenceResponse: Codable, Equatable, Sendable {
    public var version: Int
    public var serverNow: Date
    public var devices: [ExecutionPresenceDevice]
    public var acceptedSequence: Int64?
    enum CodingKeys: String, CodingKey { case version, serverNow = "server_now", devices, acceptedSequence = "accepted_sequence" }
    public init(version: Int, serverNow: Date, devices: [ExecutionPresenceDevice], acceptedSequence: Int64? = nil) {
        self.version=version; self.serverNow=serverNow; self.devices=devices; self.acceptedSequence=acceptedSequence
    }
    public func validate() throws {
        guard version == 1, devices.count <= 32, Set(devices.map(\.deviceID)).count == devices.count,
              acceptedSequence.map({ (1...9_007_199_254_740_991).contains($0) }) ?? true, serverNow.timeIntervalSince1970.isFinite,
              (0...253_402_300_799).contains(serverNow.timeIntervalSince1970) else { throw SyncHTTPError.invalidResponse }
        for device in devices {
            guard (1...9_007_199_254_740_991).contains(device.sequence), ["Mac", "Android", "Windows"].contains(device.label),
                  device.lastSeen <= serverNow, serverNow.timeIntervalSince(device.lastSeen) <= 86_400,
                  device.expiresAt > device.lastSeen, device.expiresAt.timeIntervalSince(device.lastSeen) <= 90 else { throw SyncHTTPError.invalidResponse }
            try validatePresenceRuns(device.runs)
        }
    }
    public static func decode(_ data: Data) throws -> Self {
        guard data.count <= 1_048_576 else { throw SyncHTTPError.responseTooLarge }
        do { let value=try SyncWire.decoder().decode(Self.self, from: data); try value.validate(); return value }
        catch { throw SyncHTTPError.invalidResponse }
    }
}

private func validatePresenceRuns(_ runs: [ExecutionPresenceRun]) throws {
    guard runs.count <= 32, Set(runs.map(\.runID)).count == runs.count, Set(runs.map(\.taskID)).count == runs.count,
          runs.filter({ $0.kind == .personal }).count <= 1 else { throw SyncHTTPError.invalidResponse }
    try runs.forEach { try $0.validate() }
}

/// The server timestamp is anchored to monotonic time before a request starts. Transport delay therefore
/// ages a lease conservatively. Client wall-clock changes cannot expire or revive a lease.
public struct ExecutionPresenceSnapshot: Sendable {
    public let response: ExecutionPresenceResponse
    public let observedAt: Date
    public let observedUptime: TimeInterval
    public init(response: ExecutionPresenceResponse, observedAt: Date, observedUptime: TimeInterval) {
        self.response=response; self.observedAt=observedAt; self.observedUptime=observedUptime
    }
    public func isFresh(_ device: ExecutionPresenceDevice, now: Date = Date(), uptime: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Bool {
        guard uptime >= observedUptime else { return false }
        let age=uptime-observedUptime
        return device.expiresAt > response.serverNow.addingTimeInterval(age)
    }
}

public enum ExecutionPresenceMapper {
    public static func localRuns(timer: ActiveTimer?, parallelRuns: [ParallelTaskRun], deviceID: UUID, now: Date) -> [ExecutionPresenceRun] {
        func run(taskID: UUID, startedAt: Date, kind: ExecutionPresenceRun.Kind, target: Int?) -> ExecutionPresenceRun {
            let raw=max(0, now.timeIntervalSince(startedAt))
            let elapsed=raw.isFinite ? Int(min(31_536_000, raw)) : 0
            let targetSeconds=target.map { min(525_600, max(1, $0)) * 60 }
            // Store persists ISO-8601 at whole-second precision. Match that precision so a relaunch
            // does not invent a new identity for a timer that was already advertised.
            let storedStart=startedAt.timeIntervalSince1970.rounded(.down)
            let seed="\(deviceID.uuidString.lowercased())|\(kind.rawValue)|\(taskID.uuidString.lowercased())|\(storedStart.bitPattern)"
            var bytes=Array(SHA256.hash(data: Data(seed.utf8)).prefix(16)); bytes[6]=(bytes[6] & 0x0f) | 0x80; bytes[8]=(bytes[8] & 0x3f) | 0x80
            let id=UUID(uuid: (bytes[0],bytes[1],bytes[2],bytes[3],bytes[4],bytes[5],bytes[6],bytes[7],bytes[8],bytes[9],bytes[10],bytes[11],bytes[12],bytes[13],bytes[14],bytes[15]))
            // A Mac checkpoint is a reminder; the real timer keeps accumulating until the user stops it.
            return .init(runID: id, taskID: taskID, kind: kind, state: .running, startedAt: startedAt, elapsedSeconds: elapsed, targetSeconds: targetSeconds)
        }
        var result=timer.map { [run(taskID: $0.taskID, startedAt: $0.startedAt, kind: .personal, target: $0.targetMinutes)] } ?? []
        for item in parallelRuns.sorted(by: { ($0.startedAt, $0.taskID.uuidString) < ($1.startedAt, $1.taskID.uuidString) }) where item.taskID != timer?.taskID {
            result.append(run(taskID: item.taskID, startedAt: item.startedAt, kind: .waiting, target: nil))
        }
        return Array(result.prefix(32))
    }
}

/// Only a monotonically increasing integer is persisted; it contains no task content or credentials.
public final class ExecutionPresenceSequenceStore {
    private let defaults: UserDefaults
    private static let lock=NSLock()
    public init(defaults: UserDefaults = .standard) { self.defaults=defaults }
    public func next(accountID: UUID, deviceID: UUID) throws -> Int64 {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let key=storageKey(accountID:accountID,deviceID:deviceID)
        let previous=try read(key)
        guard previous < 9_007_199_254_740_991 else { throw SyncHTTPError.invalidRequest }
        let next=previous+1
        try persist(next,key:key)
        return next
    }
    /// A restored installation can have a lower counter than the server. Observe its acknowledged
    /// high watermark durably, without moving backward if a newer local reservation already exists.
    public func advance(accountID:UUID,deviceID:UUID,to accepted:Int64) throws {
        guard (1...9_007_199_254_740_991).contains(accepted) else { throw SyncHTTPError.invalidRequest }
        Self.lock.lock(); defer { Self.lock.unlock() }
        let key=storageKey(accountID:accountID,deviceID:deviceID)
        try persist(max(try read(key),accepted),key:key)
    }
    private func storageKey(accountID:UUID,deviceID:UUID)->String {
        "pare.presence.sequence.\(accountID.uuidString.lowercased()).\(deviceID.uuidString.lowercased())"
    }
    private func read(_ key:String) throws -> Int64 {
        let stored=defaults.string(forKey:key)
        guard stored == nil || stored.flatMap(Int64.init) != nil else { throw SyncHTTPError.invalidRequest }
        let value=stored.flatMap(Int64.init) ?? 0
        guard (0...9_007_199_254_740_991).contains(value) else { throw SyncHTTPError.invalidRequest }
        return value
    }
    private func persist(_ value:Int64,key:String) throws {
        defaults.set(String(value),forKey:key)
        guard defaults.synchronize(), defaults.string(forKey:key)==String(value) else { throw SyncHTTPError.invalidRequest }
    }
}
