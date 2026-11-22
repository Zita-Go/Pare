import Foundation
import CryptoKit

public enum IntelligenceIntent: String, Codable, CaseIterable {
    case idea, today, unblock
    public var title: String { switch self { case .idea: return "接着想一想"; case .today: return "帮我安排"; case .unblock: return "帮我开始" } }
}
public struct IntelligenceContextOptions: Codable, Equatable {
    public var includeIdeaRecords: Bool
    public var includeLinkedTasks: Bool
    public var includeMemories: Bool
    public var includeHistory: Bool
    public var selectedGoalIDs: Set<UUID>
    public init(includeIdeaRecords: Bool = true, includeLinkedTasks: Bool = true, includeMemories: Bool = false, includeHistory: Bool = false, selectedGoalIDs: Set<UUID> = []) {
        self.includeIdeaRecords = includeIdeaRecords; self.includeLinkedTasks = includeLinkedTasks
        self.includeMemories = includeMemories; self.includeHistory = includeHistory; self.selectedGoalIDs = selectedGoalIDs
    }
}
public struct IntelligenceEvidence: Codable, Equatable, Identifiable {
    public var id: UUID
    public var kind: String
    public var title: String
    public var body: String
    public var date: Date
    public var revision: String
}
func intelligenceRevision<T: Encodable>(_ value: T) -> String {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; encoder.dateEncodingStrategy = .iso8601
    return SHA256.hash(data: (try? encoder.encode(value)) ?? Data()).map { String(format: "%02x", $0) }.joined()
}
public struct IntelligenceRequest: Codable, Equatable, Identifiable {
    public var id: UUID = UUID()
    public var version: Int = 1
    public var intent: IntelligenceIntent
    public var subjectID: UUID?
    public var availableMinutes: Int
    public var energy: String
    public var instruction: String
    public var options: IntelligenceContextOptions
    public var evidence: [IntelligenceEvidence]
    public var now: Date
    public var timeZone: String
    public var omittedRecordCount: Int = 0
    /// Detects additions/removals within this explicitly authorized relation scope.
    public var scopeRevision: String = ""
    public func data() throws -> Data {
        guard version == 1, (2...480).contains(availableMinutes), instruction.count <= 2000, energy.count <= 100,
              !evidence.isEmpty, Set(evidence.map(\.id)).count == evidence.count else { throw IntelligenceError.invalidInput }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]; encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(self)
        guard data.count <= 32_000 else { throw IntelligenceError.contextTooLarge }
        return data
    }
    public var preview: String { (try? data()).map { String(decoding: $0, as: UTF8.self) } ?? "上下文超过限制" }
    public var contextSummary: String {
        let counts = Dictionary(grouping: evidence, by: \.kind).map { kind, records in "\(records.count)条\(Self.label(kind))" }.sorted().joined(separator: "、")
        return "参考\(counts)" + (omittedRecordCount > 0 ? "；另有\(omittedRecordCount)条未纳入" : "")
    }
    static func label(_ kind: String) -> String {
        ["idea":"想法", "ideaEntry":"探索记录", "task":"行动", "preference":"确认偏好", "feedback":"反馈", "aiSummary":"AI旧分析", "goal":"目标", "dailyAllocation":"当日投入", "parallelRun":"并行状态"][kind] ?? kind
    }
}
public enum IntelligenceActionKind: String, Codable, CaseIterable {
    case createLinkedTask, updateIdeaNextQuestion, appendAIExplorationRecord, updateTaskNextStep, setTodaySelection, setDailyWin
    public var title: String {
        switch self { case .createLinkedTask: return "创建关联行动"; case .updateIdeaNextQuestion: return "设为下一问题"; case .appendAIExplorationRecord: return "保存 AI 探索记录"; case .updateTaskNextStep: return "更新下一步"; case .setTodaySelection: return "调整今日选择"; case .setDailyWin: return "设为今日重点" }
    }
}
public enum DailyAllocationKind: String, Codable, CaseIterable { case personal, check
    public var title: String { self == .personal ? "个人行动" : "人工检查" }
}
public struct IntelligenceAction: Codable, Equatable, Identifiable {
    public var id: UUID { actionID }
    public var actionID: UUID
    public var kind: IntelligenceActionKind
    public var sourceID: UUID
    public var evidenceIDs: [UUID]
    public var title: String
    public var text: String
    public var nextAction: String
    public var definitionOfDone: String
    public var plannedMinutes: Int?
    public var allocationKind: DailyAllocationKind
    public var selected: Bool
    public var reason: String
    public init(actionID: UUID = UUID(), kind: IntelligenceActionKind, sourceID: UUID, evidenceIDs: [UUID] = [], title: String = "", text: String = "", nextAction: String = "", definitionOfDone: String = "", plannedMinutes: Int? = nil, allocationKind: DailyAllocationKind = .personal, selected: Bool = true, reason: String = "") {
        self.actionID = actionID; self.kind = kind; self.sourceID = sourceID; self.evidenceIDs = evidenceIDs; self.title = title; self.text = text; self.nextAction = nextAction; self.definitionOfDone = definitionOfDone; self.plannedMinutes = plannedMinutes; self.allocationKind = allocationKind; self.selected = selected; self.reason = reason
    }
}
public struct IntelligenceProposal: Codable, Equatable {
    public var version: Int
    public var requestID: UUID
    public var summary: String
    public var openQuestion: String
    public var clarification: String
    public var actions: [IntelligenceAction]
    public var evidenceIDs: [UUID]
    public var memoryCandidate: String?
    public init(version: Int = 1, requestID: UUID, summary: String, openQuestion: String = "", clarification: String = "", actions: [IntelligenceAction], evidenceIDs: [UUID] = [], memoryCandidate: String? = nil) {
        self.version = version; self.requestID = requestID; self.summary = summary; self.openQuestion = openQuestion; self.clarification = clarification; self.actions = actions; self.evidenceIDs = evidenceIDs; self.memoryCandidate = memoryCandidate
    }
    public func validate(for request: IntelligenceRequest) throws {
        _ = try request.data()
        guard version == 1, requestID == request.id, summary.count <= 1000, openQuestion.count <= 500,
              clarification.count <= 500, (memoryCandidate?.count ?? 0) <= 500,
              actions.count <= 9, Set(actions.map(\.id)).count == actions.count,
              !actions.isEmpty || !clarification.isEmpty, clarification.isEmpty || actions.isEmpty else { throw IntelligenceError.invalidProposal }
        let records = Dictionary(uniqueKeysWithValues: request.evidence.map { ($0.id, $0) })
        guard evidenceIDs.allSatisfy({ records[$0] != nil }) else { throw IntelligenceError.unknownReference }
        var minutes: [UUID: Int] = [:]
        var newTaskMinutes = 0
        var writes = Set<String>()
        for action in actions {
            guard let source = records[action.sourceID], action.evidenceIDs.allSatisfy({ records[$0] != nil }) else { throw IntelligenceError.unknownReference }
            guard action.title.count <= 200, action.text.count <= 2000, action.nextAction.count <= 500, action.definitionOfDone.count <= 500, action.reason.count <= 500,
                  action.plannedMinutes.map({ (1...480).contains($0) }) ?? true else { throw IntelligenceError.invalidProposal }
            let isIdea = [.createLinkedTask, .updateIdeaNextQuestion, .appendAIExplorationRecord].contains(action.kind)
            guard source.kind == (isIdea ? "idea" : "task") else { throw IntelligenceError.outOfScope }
            if isIdea {
                guard request.intent == .idea, action.sourceID == request.subjectID else { throw IntelligenceError.outOfScope }
            } else {
                guard request.intent == .today || (request.intent == .unblock && request.subjectID == action.sourceID && action.kind == .updateTaskNextStep) else { throw IntelligenceError.outOfScope }
            }
            if action.kind != .createLinkedTask && action.kind != .appendAIExplorationRecord {
                guard writes.insert("\(action.sourceID)-\(action.kind.rawValue)").inserted else { throw IntelligenceError.invalidProposal }
            }
            switch action.kind {
            case .createLinkedTask:
                guard !action.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !action.nextAction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !action.definitionOfDone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let duration = action.plannedMinutes else { throw IntelligenceError.invalidProposal }
                newTaskMinutes += duration
            case .updateTaskNextStep:
                guard !action.nextAction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !action.definitionOfDone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let duration = action.plannedMinutes else { throw IntelligenceError.invalidProposal }
                if request.intent == .unblock && !(2...5).contains(duration) { throw IntelligenceError.overBudget }
            case .updateIdeaNextQuestion, .appendAIExplorationRecord:
                guard !action.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw IntelligenceError.invalidProposal }
            case .setDailyWin: guard action.selected else { throw IntelligenceError.invalidProposal }
            case .setTodaySelection: break
            }
            if action.kind != .createLinkedTask, let duration = action.plannedMinutes { minutes[action.sourceID] = max(minutes[action.sourceID] ?? 0, duration) }
        }
        let removed = Set(actions.filter { $0.kind == .setTodaySelection && !$0.selected }.map(\.sourceID))
        guard !actions.contains(where: { $0.kind == .setDailyWin && removed.contains($0.sourceID) }) else { throw IntelligenceError.invalidProposal }
        guard actions.filter({ $0.kind == .createLinkedTask }).count <= 3,
              Set(actions.filter({ records[$0.sourceID]?.kind == "task" }).map(\.sourceID)).count <= 3,
              actions.filter({ $0.kind == .setDailyWin }).count <= 1,
              request.intent != .unblock || actions.count <= 1,
              minutes.values.reduce(newTaskMinutes, +) <= request.availableMinutes else { throw IntelligenceError.overBudget }
    }
    public static func schema(for request: IntelligenceRequest) -> [String: Any] {
        let string: [String: Any] = ["type":"string"]
        let ids: [String: Any] = ["type":"array", "items":["type":"string", "enum":request.evidence.map { $0.id.uuidString }]]
        let fields: [String: Any] = ["actionID":string, "kind":["type":"string", "enum":IntelligenceActionKind.allCases.map(\.rawValue)], "sourceID":["type":"string", "enum":request.evidence.filter { ["idea","task"].contains($0.kind) }.map { $0.id.uuidString }], "evidenceIDs":ids, "title":string, "text":string, "nextAction":string, "definitionOfDone":string, "plannedMinutes":["type":["integer","null"]], "allocationKind":["type":"string", "enum":["personal","check"]], "selected":["type":"boolean"], "reason":string]
        let properties: [String: Any] = ["version":["type":"integer", "enum":[1]], "requestID":["type":"string", "enum":[request.id.uuidString]], "summary":string, "openQuestion":string, "clarification":string, "actions":["type":"array", "maxItems":9, "items":["type":"object", "additionalProperties":false, "required":fields.keys.sorted(), "properties":fields]], "evidenceIDs":ids, "memoryCandidate":["type":["string","null"]]]
        return ["type":"object", "additionalProperties":false, "required":properties.keys.sorted(), "properties":properties]
    }
}
public struct ConfirmedPreference: Codable, Equatable, Identifiable {
    public var id: UUID = UUID()
    public var text: String
    public var ideaID: UUID?
    public var source: String
    public var updatedAt: Date
    public var expiresAt: Date?
    public var scopeLabel: String { ideaID == nil ? "全局" : "当前想法" }
}
public enum IntelligenceFeedbackKind: String, Codable, CaseIterable {
    case helpful, tooLarge, unsuitable, clear
    public var title: String { switch self { case .helpful: return "有帮助"; case .tooLarge: return "还是太大"; case .unsuitable: return "不适合"; case .clear: return "已经清楚了" } }
}
public struct IntelligenceFeedback: Codable, Equatable, Identifiable {
    public var id: UUID = UUID()
    public var requestID: UUID
    public var actionID: UUID?
    public var ideaID: UUID?
    public var createdTaskID: UUID?
    public var sourceTaskID: UUID?
    public var kind: IntelligenceFeedbackKind
    public var comment: String
    public var date: Date
}
public struct DailyAllocation: Codable, Equatable, Identifiable {
    public var id: UUID = UUID()
    public var taskID: UUID
    public var day: Date
    public var plannedMinutes: Int?
    public var kind: DailyAllocationKind
}
public struct DailyLoad: Equatable {
    public var personalMinutes: Int
    public var checkMinutes: Int
    public var unknownCount: Int
    public var plannedMinutes: Int { personalMinutes + checkMinutes }
    public var label: String { "已计划\(plannedMinutes)分钟" + (checkMinutes > 0 ? "（含检查\(checkMinutes)分钟）" : "") + (unknownCount > 0 ? "，另有\(unknownCount)项未确定" : "") }
}
public struct IntelligenceAdoptionReceipt: Codable, Equatable, Identifiable {
    public var id: UUID = UUID()
    public var requestID: UUID
    public var actionIDs: [UUID]
    public var createdTaskIDs: [UUID]
    public var date: Date
    public var undoneAt: Date?
    var timeZone: String?
    var actionTaskIDs: [UUID: UUID]
    var beforeTasks: [TaskItem]
    var afterTasks: [TaskItem]
    var beforeIdeas: [ThoughtItem]
    var afterIdeas: [ThoughtItem]
    var beforeAllocations: [DailyAllocation]
    var afterAllocations: [DailyAllocation]
}
public enum IntelligenceError: LocalizedError {
    case invalidInput, contextTooLarge, invalidProposal, unknownReference, outOfScope, overBudget, stale, persistence, conflict, alreadyApplied, missingSource, crossDayUndo
    public var errorDescription: String? {
        switch self {
        case .invalidInput: return "请提供有效的对象、2–480分钟预算和不超过2000字的说明。"
        case .contextTooLarge: return "当前关键内容超过32KB，请缩短描述或缩小参考范围。"
        case .invalidProposal: return "建议格式或操作内容不完整，没有修改任何内容。"
        case .unknownReference: return "建议引用了未提供的来源，已拒绝采纳。"
        case .outOfScope: return "建议超出本次获准范围，已拒绝采纳。"
        case .overBudget: return "建议数量或投入超过本次预算，请重新生成。"
        case .stale: return "相关来源或日期已变化，请重新生成建议。"
        case .persistence: return "无法保存，本次操作已全部回滚。建议仍可重试。"
        case .conflict: return "采纳后相关内容又有编辑，无法安全撤销；已保留你的新编辑。"
        case .crossDayUndo: return "这次采纳涉及其他日期的任务或投入，无法安全撤销；今日安排没有改变。"
        case .alreadyApplied: return "本次建议已采纳或撤销，请生成新建议。"
        case .missingSource: return "当前对象已不存在，请重新打开助手。"
        }
    }
}
