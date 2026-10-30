import Foundation

/// Deliberately explicit profile whitelist. AI responses, settings, diagnostics,
/// adoption history, credentials, and active timers cannot enter this payload.
public struct SyncProfile: Codable, Equatable {
    public var background: String
    public var preferences: AgentPreferences
    public var confirmedPreferences: [ConfirmedPreference]
    public var planningTimeZone: String
}
public enum SyncPayload {
    static let taskLocalKeys: Set<String> = ["isToday", "todayOrder", "isDailyWin", "scheduledAt", "scheduledMinutes", "scheduleReminderLeadMinutes", "lastImplementationTriggeredAt"]
    public static func json<T: Encodable>(_ value: T) throws -> SyncJSON {
        normalize(try SyncWire.decoder().decode(SyncJSON.self, from: SyncWire.encoder().encode(value)))
    }
    public static func wrap<T: Encodable>(_ value: T) throws -> SyncJSON { .object(["content": try json(value)]) }
    public static func task(_ task: TaskItem, archived: Bool) throws -> SyncJSON {
        guard case .object(var content) = try json(task) else { throw SyncProtocolError.invalidPayload }
        for key in taskLocalKeys { content.removeValue(forKey: key) }
        return .object(["content": .object(content), "lifecycle": .string(archived ? "archived" : "active")])
    }
    public static func content<T: Decodable>(_ type: T.Type, _ payload: SyncJSON) throws -> T {
        guard case .object(let fields) = payload, let content = fields["content"] else { throw SyncProtocolError.invalidPayload }
        return try SyncWire.decoder().decode(type, from: SyncWire.encoder().encode(content))
    }
    public static func decodeTask(_ payload: SyncJSON) throws -> (TaskItem, Bool) {
        guard case .object(let fields) = payload, case .object(var content) = fields["content"],
              case .string(let lifecycle) = fields["lifecycle"], ["active", "archived"].contains(lifecycle) else { throw SyncProtocolError.invalidPayload }
        for key in taskLocalKeys { content.removeValue(forKey: key) }
        content["isToday"] = .bool(false)
        return (try SyncWire.decoder().decode(TaskItem.self, from: SyncWire.encoder().encode(SyncJSON.object(content))), lifecycle == "archived")
    }
    static func normalize(_ value: SyncJSON) -> SyncJSON {
        switch value {
        case .string(let s): return .string(UUID(uuidString: s)?.uuidString.lowercased() ?? s)
        case .array(let list): return .array(list.map(normalize))
        case .object(let fields): return .object(fields.mapValues(normalize))
        default: return value
        }
    }
    /// Decode before advancing the cursor, even for a conflicted entity.
    static func validate(_ entity: SyncRemoteEntity) throws {
        try entity.validate()
        if entity.deletedAt != nil { return }
        guard let payload = entity.payload else { throw SyncProtocolError.invalidPayload }
        let id: UUID?
        switch entity.kind {
        case .task: id = try decodeTask(payload).0.id
        case .inbox: id = try content(InboxItem.self, payload).id
        case .idea: id = try content(ThoughtItem.self, payload).id
        case .goal: id = try content(LongTermGoal.self, payload).id
        case .dayPlan:
            let plan = try content(SyncDayPlan.self, payload)
            try plan.validate()
            guard plan.key == entity.key else { throw SyncProtocolError.invalidKey }; return
        case .profile:
            let profile = try content(SyncProfile.self, payload)
            guard entity.id == "profile", TimeZone(identifier: profile.planningTimeZone) != nil else { throw SyncProtocolError.invalidPayload }; return
        }
        guard id?.uuidString.lowercased() == entity.id else { throw SyncProtocolError.invalidKey }
    }
    /// Preserve fields introduced by another client while replacing known optional
    /// values (including their intentional removal). Nested records match by ID.
    static func preserveUnknown(_ local: SyncJSON, raw: SyncJSON?, kind: SyncEntityKind) throws -> SyncJSON {
        guard let raw else { return local }
        let known: SyncJSON
        switch kind {
        case .task: let (value, archived) = try decodeTask(raw); known = try task(value, archived: archived)
        case .inbox: known = try wrap(content(InboxItem.self, raw))
        case .idea: known = try wrap(content(ThoughtItem.self, raw))
        case .goal: known = try wrap(content(LongTermGoal.self, raw))
        case .dayPlan: known = try wrap(content(SyncDayPlan.self, raw))
        case .profile: known = try wrap(content(SyncProfile.self, raw))
        }
        if local == known, kind != .task { return normalize(raw) }
        func merge(_ current: SyncJSON, _ original: SyncJSON?, _ decoded: SyncJSON?) -> SyncJSON {
            if current == decoded, let original { return original }
            if case .object(let values) = current, case .object(let old) = original {
                let oldKnown: [String: SyncJSON]
                if case .object(let k) = decoded { oldKnown = k } else { oldKnown = [:] }
                var result = old.filter { oldKnown[$0.key] == nil }
                for (key, value) in values { result[key] = merge(value, old[key], oldKnown[key]) }
                return .object(result)
            }
            if case .array(let values) = current, case .array(let old) = original, case .array(let oldKnown) = decoded {
                func identity(_ value: SyncJSON) -> SyncJSON? { if case .object(let f) = value { return f["id"] ?? f["task_id"] }; return nil }
                return .array(values.map { value in
                    guard let id = identity(value), let i = oldKnown.firstIndex(where: { identity($0) == id }), i < old.count else { return value }
                    return merge(value, old[i], oldKnown[i])
                })
            }
            return current
        }
        var result = normalize(merge(local, raw, known))
        if kind == .task, case .object(var wrapper) = result, case .object(var content) = wrapper["content"] {
            for key in taskLocalKeys { content.removeValue(forKey: key) }
            wrapper["content"] = .object(content); result = .object(wrapper)
        }
        return result
    }
}
