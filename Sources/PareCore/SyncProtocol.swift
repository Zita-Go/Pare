import Foundation

/// Versioned cross-platform contract. Device settings, credentials and raw AI
/// histories deliberately have no entity kind in this protocol.
public enum SyncEntityKind: String, Codable, CaseIterable, Sendable {
    case inbox, task, idea, goal, profile
    case dayPlan = "day_plan"
}

public enum SyncProtocolError: LocalizedError, Equatable {
    case unsupportedVersion, invalidKey, invalidRevision, invalidPayload
    case batchTooLarge, duplicateEntity, invalidPage, invalidDayPlan
    public var errorDescription: String? {
        switch self {
        case .batchTooLarge: return "待同步事务超过 100 条记录或 1 MiB，已暂停上传以保持多记录操作完整。本地数据已保留。"
        case .unsupportedVersion: return "云端数据需要更新版本的 Step；已停止同步并保留本地内容。"
        default: return "同步数据未通过完整性检查；本地内容和拉取位置均已保留。"
        }
    }
}

public struct SyncEntityKey: Codable, Hashable, Sendable {
    public var kind: SyncEntityKind
    public var id: String
    public init(kind: SyncEntityKind, id: String) { self.kind = kind; self.id = id }
    public static func uuid(kind: SyncEntityKind, id: UUID) -> Self {
        .init(kind: kind, id: id.uuidString.lowercased())
    }
    public func validate() throws {
        guard !id.isEmpty, id.utf8.count <= 160,
              id.range(of: #"^[A-Za-z0-9_:@+./-]+$"#, options: .regularExpression) != nil,
              !id.contains("\n") else { throw SyncProtocolError.invalidKey }
    }
}

public indirect enum SyncJSON: Codable, Equatable, Sendable {
    case object([String: SyncJSON]), array([SyncJSON]), string(String)
    case integer(Int64), decimal(Double), bool(Bool), null

    public init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let x = try? value.decode(Bool.self) { self = .bool(x) }
        else if let x = try? value.decode(Int64.self) { self = .integer(x) }
        else if let x = try? value.decode(Double.self), x.isFinite { self = .decimal(x) }
        else if let x = try? value.decode(String.self) { self = .string(x) }
        else if let x = try? value.decode([SyncJSON].self) { self = .array(x) }
        else { self = .object(try value.decode([String: SyncJSON].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .object(let x): try value.encode(x)
        case .array(let x): try value.encode(x)
        case .string(let x): try value.encode(x)
        case .integer(let x): try value.encode(x)
        case .decimal(let x): try value.encode(x)
        case .bool(let x): try value.encode(x)
        case .null: try value.encodeNil()
        }
    }

    fileprivate func validate(depth: Int = 0) throws {
        guard depth <= 32 else { throw SyncProtocolError.invalidPayload }
        switch self {
        case .object(let fields):
            for (key, value) in fields {
                guard !key.contains("\0") else { throw SyncProtocolError.invalidPayload }
                try value.validate(depth: depth + 1)
            }
        case .array(let values): for value in values { try value.validate(depth: depth + 1) }
        case .decimal(let value): guard value.isFinite else { throw SyncProtocolError.invalidPayload }
        case .string(let value): guard !value.contains("\0") else { throw SyncProtocolError.invalidPayload }
        default: break
        }
    }
}

public struct SyncMutation: Codable, Equatable, Sendable {
    public var kind: SyncEntityKind
    public var id: String
    public var baseRevision: Int64
    public var payloadVersion: Int
    public var deleted: Bool
    public var payload: SyncJSON?
    public var key: SyncEntityKey { .init(kind: kind, id: id) }
    enum CodingKeys: String, CodingKey {
        case kind, id, deleted, payload
        case baseRevision = "base_revision", payloadVersion = "payload_version"
    }

    public init(key: SyncEntityKey, baseRevision: Int64, payload: SyncJSON?, deleted: Bool = false, payloadVersion: Int = 1) {
        self.kind = key.kind; self.id = key.id; self.baseRevision = baseRevision
        self.payload = payload; self.deleted = deleted; self.payloadVersion = payloadVersion
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(kind, forKey: .kind); try values.encode(id, forKey: .id)
        try values.encode(baseRevision, forKey: .baseRevision)
        try values.encode(payloadVersion, forKey: .payloadVersion)
        try values.encode(deleted, forKey: .deleted)
        if let payload { try values.encode(payload, forKey: .payload) }
        else { try values.encodeNil(forKey: .payload) }
    }

    public func validate() throws {
        try key.validate()
        guard payloadVersion == 1 else { throw SyncProtocolError.unsupportedVersion }
        guard baseRevision >= 0, baseRevision < Int64.max else { throw SyncProtocolError.invalidRevision }
        if deleted {
            guard payload == nil || payload == .null else { throw SyncProtocolError.invalidPayload }
        } else {
            guard case .object = payload else { throw SyncProtocolError.invalidPayload }
            try payload?.validate()
        }
    }
}

public struct SyncPushRequest: Codable, Equatable, Sendable {
    public var operationID: UUID
    public var deviceID: UUID
    public var mutations: [SyncMutation]
    enum CodingKeys: String, CodingKey {
        case operationID = "p_operation_id", deviceID = "p_device_id", mutations = "p_mutations"
    }
    public init(operationID: UUID = UUID(), deviceID: UUID, mutations: [SyncMutation]) {
        self.operationID = operationID; self.deviceID = deviceID; self.mutations = mutations
    }
    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(operationID.uuidString.lowercased(), forKey: .operationID)
        try values.encode(deviceID.uuidString.lowercased(), forKey: .deviceID)
        try values.encode(mutations, forKey: .mutations)
    }
    public func validate() throws {
        guard (1...100).contains(mutations.count) else { throw SyncProtocolError.batchTooLarge }
        guard Set(mutations.map(\.key)).count == mutations.count else { throw SyncProtocolError.duplicateEntity }
        for mutation in mutations { try mutation.validate() }
        guard try SyncWire.encoder().encode(mutations).count <= 1_048_576 else { throw SyncProtocolError.batchTooLarge }
    }
    public func data() throws -> Data { try validate(); return try SyncWire.encoder().encode(self) }
}

public struct SyncRemoteEntity: Codable, Equatable, Sendable {
    public var kind: SyncEntityKind
    public var id: String
    public var revision: Int64
    public var payloadVersion: Int
    public var payload: SyncJSON?
    public var deletedAt: Date?
    public var lastDeviceID: UUID
    public var sequence: Int64
    public var key: SyncEntityKey { .init(kind: kind, id: id) }
    enum CodingKeys: String, CodingKey {
        case kind, id, revision, payload, sequence
        case payloadVersion = "payload_version", deletedAt = "deleted_at", lastDeviceID = "last_device_id"
    }
    public func validate() throws {
        try key.validate()
        guard revision > 0, sequence > 0 else { throw SyncProtocolError.invalidRevision }
        guard payloadVersion == 1 else { throw SyncProtocolError.unsupportedVersion }
        if deletedAt != nil {
            guard payload == nil || payload == .null else { throw SyncProtocolError.invalidPayload }
        } else {
            guard case .object = payload else { throw SyncProtocolError.invalidPayload }
            try payload?.validate()
        }
    }
}

public struct SyncConflict: Codable, Equatable, Sendable {
    public var kind: SyncEntityKind
    public var id: String
    public var current: SyncRemoteEntity?
}

public struct SyncPushResponse: Decodable, Sendable {
    public enum Status: String, Codable, Sendable { case applied, conflict }
    public var protocolVersion: Int
    public var status: Status
    public var sequence: Int64
    public var entities: [SyncRemoteEntity]
    public var conflicts: [SyncConflict]
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version", status, sequence, entities, conflicts
    }
    public func validate(for request: SyncPushRequest) throws {
        try request.validate()
        guard protocolVersion == 1 else { throw SyncProtocolError.unsupportedVersion }
        guard sequence >= 0 else { throw SyncProtocolError.invalidRevision }
        let expected = Set(request.mutations.map(\.key))
        if status == .applied {
            guard sequence > 0, conflicts.isEmpty, entities.count == expected.count,
                  Set(entities.map(\.key)) == expected else { throw SyncProtocolError.invalidPage }
            for entity in entities {
                try entity.validate()
                guard let mutation = request.mutations.first(where: { $0.key == entity.key }),
                      entity.sequence == sequence, entity.revision == mutation.baseRevision + 1,
                      entity.lastDeviceID == request.deviceID,
                      (entity.deletedAt != nil) == mutation.deleted,
                      entity.payload == mutation.payload || (mutation.deleted && (entity.payload == nil || entity.payload == .null))
                else { throw SyncProtocolError.invalidPage }
            }
        } else {
            guard entities.isEmpty, !conflicts.isEmpty,
                  Set(conflicts.map { SyncEntityKey(kind: $0.kind, id: $0.id) }).count == conflicts.count else { throw SyncProtocolError.invalidPage }
            for conflict in conflicts {
                let key = SyncEntityKey(kind: conflict.kind, id: conflict.id)
                guard expected.contains(key) else { throw SyncProtocolError.invalidPage }
                if let current = conflict.current {
                    try current.validate()
                    guard current.key == key, current.sequence <= sequence else { throw SyncProtocolError.invalidPage }
                }
            }
        }
    }
}

public struct SyncCommit: Codable, Equatable, Sendable {
    public var sequence: Int64
    public var operationID: UUID
    public var deviceID: UUID
    public var entities: [SyncRemoteEntity]
    enum CodingKeys: String, CodingKey {
        case sequence, entities, operationID = "operation_id", deviceID = "device_id"
    }
}

public struct SyncPullResponse: Decodable, Sendable {
    public var protocolVersion: Int
    public var cursor: Int64
    public var hasMore: Bool
    public var commits: [SyncCommit]
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version", cursor, hasMore = "has_more", commits
    }
    public func validate(after: Int64) throws {
        guard protocolVersion == 1 else { throw SyncProtocolError.unsupportedVersion }
        guard after >= 0, cursor >= after, commits.count <= 100 else { throw SyncProtocolError.invalidPage }
        var previous = after
        var operationIDs = Set<UUID>()
        for commit in commits {
            guard previous < Int64.max, commit.sequence == previous + 1,
                  operationIDs.insert(commit.operationID).inserted,
                  !commit.entities.isEmpty, commit.entities.count <= 100,
                  Set(commit.entities.map(\.key)).count == commit.entities.count else { throw SyncProtocolError.invalidPage }
            for entity in commit.entities {
                try entity.validate()
                guard entity.sequence == commit.sequence, entity.lastDeviceID == commit.deviceID else { throw SyncProtocolError.invalidPage }
            }
            previous = commit.sequence
        }
        guard cursor == previous, !commits.isEmpty || !hasMore else { throw SyncProtocolError.invalidPage }
    }
}

public enum SyncWire {
    public static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
    public static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { value in
            let text = try value.singleValueContainer().decode(String.self)
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: text) { return date }
            formatter.formatOptions = [.withInternetDateTime]
            if let date = formatter.date(from: text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: value.codingPath, debugDescription: "Invalid sync timestamp"))
        }
        return decoder
    }
}

/// A shared calendar date, not a device's last launch day. Merely reading a new
/// date must never delete or reset another device's plan.
public struct SyncDayPlan: Codable, Equatable, Sendable {
    public var dayKey: String
    public var timeZone: String
    public var orderedTaskIDs: [UUID]
    public var dailyWinTaskID: UUID?
    public var taskPlans: [SyncTaskPlan]
    public var key: SyncEntityKey { .init(kind: .dayPlan, id: "\(dayKey)@\(timeZone)") }
    enum CodingKeys: String, CodingKey {
        case dayKey = "day_key", timeZone = "time_zone", orderedTaskIDs = "ordered_task_ids", dailyWinTaskID = "daily_win_task_id", taskPlans = "task_plans"
    }
    public init(dayKey: String, timeZone: String, orderedTaskIDs: [UUID], dailyWinTaskID: UUID? = nil, taskPlans: [SyncTaskPlan] = []) {
        self.dayKey = dayKey; self.timeZone = timeZone
        self.orderedTaskIDs = orderedTaskIDs; self.dailyWinTaskID = dailyWinTaskID; self.taskPlans = taskPlans
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dayKey = try c.decode(String.self, forKey: .dayKey)
        timeZone = try c.decode(String.self, forKey: .timeZone)
        orderedTaskIDs = try c.decode([UUID].self, forKey: .orderedTaskIDs)
        dailyWinTaskID = try c.decodeIfPresent(UUID.self, forKey: .dailyWinTaskID)
        taskPlans = try c.decodeIfPresent([SyncTaskPlan].self, forKey: .taskPlans) ?? []
    }
    public func validate() throws {
        guard Set(taskPlans.map(\.taskID)).count == taskPlans.count else { throw SyncProtocolError.invalidDayPlan }
        for plan in taskPlans {
            guard plan.scheduledMinutes.map({ $0 > 0 }) ?? true,
                  plan.scheduleReminderLeadMinutes.map({ $0 >= 0 }) ?? true,
                  plan.allocation.map({ ["personal", "check"].contains($0.kind) && ($0.plannedMinutes.map { $0 > 0 } ?? true) }) ?? true
            else { throw SyncProtocolError.invalidDayPlan }
        }
        guard let zone = TimeZone(identifier: timeZone), dayKey.count == 10,
              dayKey.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil,
              Set(orderedTaskIDs).count == orderedTaskIDs.count,
              dailyWinTaskID.map({ orderedTaskIDs.contains($0) }) ?? true else { throw SyncProtocolError.invalidDayPlan }
        let components = dayKey.split(separator: "-").compactMap { Int($0) }
        guard components.count == 3 else { throw SyncProtocolError.invalidDayPlan }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        guard let date = calendar.date(from: DateComponents(year: components[0], month: components[1], day: components[2], hour: 12)),
              Self.dayKey(for: date, timeZone: zone) == dayKey else { throw SyncProtocolError.invalidDayPlan }
        try key.validate()
    }
    public static func dayKey(for date: Date, timeZone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
    }
}
