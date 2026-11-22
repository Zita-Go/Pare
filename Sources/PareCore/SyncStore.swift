import Foundation

public enum SyncStoreError: LocalizedError {
    case accountMismatch, notConnected, initialPullRequired, conflictsPending, persistence, unsavedChanges, invalidResponse, activeTaskRunning
    public var errorDescription: String? {
        switch self {
        case .accountMismatch: return "此设备数据已绑定其他账号。请先导出并明确重置本地数据，避免跨账号上传。"
        case .notConnected: return "同步尚未连接或已暂停。"
        case .initialPullRequired: return "请先拉取云端数据并确认首次合并。"
        case .conflictsPending: return "请先处理同步冲突。"
        case .persistence: return "同步状态未能安全保存；不会继续上传或推进游标。"
        case .unsavedChanges: return "本地内容尚未保存，暂不上传。"
        case .activeTaskRunning: return "请先停止此任务的本机专注或并行计时，再选择云端版本；已记录的投入会保留供你选择。"
        case .invalidResponse: return "同步响应与当前请求不一致。"
        }
    }
}
public enum SyncConflictChoice: String, Codable, Equatable { case local, cloud }
public struct SyncLocalConflict: Codable, Equatable, Identifiable {
    public var key: SyncEntityKey
    public var base: SyncRemoteEntity?
    public var local: SyncJSON?
    public var remote: SyncRemoteEntity?
    public var groupID: UUID? = nil
    public var choice: SyncConflictChoice? = nil
    public var id: String { key.kind.rawValue + ":" + key.id }
}
public struct SyncMergePreview: Equatable {
    public var localOnlyCount: Int
    public var cloudOnlyCount: Int
    public var matchingCount: Int
    public var conflictCount: Int
}
/// Content, shadow, queue, and immutable retry request share Store's one atomic file.
/// Never place tokens or passwords here: the app owns authentication separately.
public struct SyncLocalState: Codable, Equatable {
    public var accountID: String
    public var deviceID: UUID = UUID()
    public var enabled = true
    public var cursor: Int64 = 0
    public var initialPullComplete = false
    public var mergeConfirmed = false
    public var shadow: [SyncRemoteEntity] = []
    public var initialRemote: [SyncRemoteEntity] = []
    public var initialCommits: [SyncCommit] = []
    public var pending: [SyncMutation] = []
    public var pendingGroups: [[SyncEntityKey]] = []
    public var inflight: SyncPushRequest?
    public var conflicts: [SyncLocalConflict] = []
    public init(accountID: String) { self.accountID = accountID.lowercased() }
    private enum CodingKeys: String, CodingKey {
        case accountID, deviceID, enabled, cursor, initialPullComplete, mergeConfirmed
        case shadow, initialRemote, initialCommits, pending, pendingGroups, inflight, conflicts
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accountID = try c.decode(String.self, forKey: .accountID).lowercased()
        deviceID = try c.decode(UUID.self, forKey: .deviceID)
        enabled = try c.decode(Bool.self, forKey: .enabled)
        cursor = try c.decode(Int64.self, forKey: .cursor)
        initialPullComplete = try c.decode(Bool.self, forKey: .initialPullComplete)
        mergeConfirmed = try c.decode(Bool.self, forKey: .mergeConfirmed)
        shadow = try c.decode([SyncRemoteEntity].self, forKey: .shadow)
        initialRemote = try c.decode([SyncRemoteEntity].self, forKey: .initialRemote)
        initialCommits = try c.decodeIfPresent([SyncCommit].self, forKey: .initialCommits) ?? []
        pending = try c.decode([SyncMutation].self, forKey: .pending)
        pendingGroups = try c.decodeIfPresent([[SyncEntityKey]].self, forKey: .pendingGroups) ?? pending.map { [$0.key] }
        inflight = try c.decodeIfPresent(SyncPushRequest.self, forKey: .inflight)
        conflicts = try c.decode([SyncLocalConflict].self, forKey: .conflicts)
    }
}

public struct SyncTaskAllocation: Codable, Equatable, Sendable {
    public var id: UUID
    public var plannedMinutes: Int?
    public var kind: String
    enum CodingKeys: String, CodingKey { case id, kind, plannedMinutes = "planned_minutes" }
    public init(id: UUID, plannedMinutes: Int?, kind: String) {
        self.id = id; self.plannedMinutes = plannedMinutes; self.kind = kind
    }
}
public struct SyncTaskPlan: Codable, Equatable, Sendable {
    public var taskID: UUID
    public var scheduledAt: Date?
    public var scheduledMinutes: Int?
    public var scheduleReminderLeadMinutes: Int?
    public var allocation: SyncTaskAllocation?
    enum CodingKeys: String, CodingKey {
        case taskID = "task_id", scheduledAt = "scheduled_at", scheduledMinutes = "scheduled_minutes"
        case scheduleReminderLeadMinutes = "schedule_reminder_lead_minutes", allocation
    }
    public init(taskID: UUID, scheduledAt: Date? = nil, scheduledMinutes: Int? = nil,
                scheduleReminderLeadMinutes: Int? = nil, allocation: SyncTaskAllocation? = nil) {
        self.taskID = taskID; self.scheduledAt = scheduledAt; self.scheduledMinutes = scheduledMinutes
        self.scheduleReminderLeadMinutes = scheduleReminderLeadMinutes; self.allocation = allocation
    }
}
