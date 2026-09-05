import Foundation

/// Diagnostics contain categories/counts only, never provider payloads or credentials.
public enum AIResponseIssue: Error, LocalizedError, Equatable {
    case envelope, json, missingFields, unknownTask, duplicateTask, textTooLong, invalidFields
    case noSuggestions, questionWithSuggestions, missingAction, primaryCount(Int), tooMany(Int, Int), overBudget(Int, Int)

    public var code: String {
        switch self {
        case .envelope: return "response_envelope"
        case .json: return "response_json"
        case .missingFields: return "missing_fields"
        case .unknownTask: return "unknown_task"
        case .duplicateTask: return "duplicate_task"
        case .textTooLong: return "text_too_long"
        case .invalidFields: return "invalid_fields"
        case .noSuggestions: return "empty_suggestions"
        case .questionWithSuggestions: return "question_and_plan"
        case .missingAction: return "missing_action"
        case .primaryCount: return "primary_count"
        case .tooMany: return "suggestion_count"
        case .overBudget: return "time_budget"
        }
    }

    public var errorDescription: String? {
        switch self {
        case .envelope: return "服务有返回，但格式不是预期的回答格式。请检查连接设置或重新生成。"
        case .json: return "回答的格式无法完整读取。没有改动任务，可以重新生成。"
        case .missingFields: return "回答缺少必要信息，暂时不能写入任务。请重新生成完整建议。"
        case .unknownTask: return "建议中的任务无法与所选任务对应。没有改动任务，请重新生成。"
        case .duplicateTask: return "同一任务在建议中重复出现。没有改动任务，请重新生成。"
        case .textTooLong: return "回答过长，暂未采纳。可以让 AI 生成更简短的版本。"
        case .invalidFields: return "建议中的分类或时长不有效，暂未采纳。请重新生成。"
        case .noSuggestions: return "这次没有返回可用建议。可以补充一句说明再生成。"
        case .questionWithSuggestions: return "AI 同时给出了问题和计划。请先回答下面的问题，再生成最终建议。"
        case .missingAction: return "建议缺少具体下一步或完成标准，暂未采纳。请重新生成。"
        case .primaryCount: return "建议没有明确唯一的重点任务，暂未采纳。可以重新生成一个重点明确的版本。"
        case .tooMany(let actual, let maximum): return "返回了 \(actual) 项建议，本次最多需要 \(maximum) 项。可以重新生成精简版。"
        case .overBudget(let actual, let allowed): return "这份计划需要 \(actual) 分钟，超过你设置的 \(allowed) 分钟。可以调整时间，或按当前预算重新生成。"
        }
    }

    public var retryGuidance: String {
        switch self {
        case .overBudget: return "上一轮计划超出时间预算。所有建议的 estimatedMinutes 合计必须不超过本次 availableMinutes；备选任务也计入合计。"
        case .unknownTask, .duplicateTask: return "上一轮任务匹配失败。仅使用 inputs 中的 id 作为 sourceID，逐字复制，每个 ID 最多一次。"
        case .primaryCount: return "上一轮没有唯一重点。只标记一项 isKeyTask=true，其余为 false。"
        case .questionWithSuggestions: return "请结合 followUpQuestion 和用户的 clarification 回答。给出计划时 question 必须为空；仍需澄清时 suggestions 必须为空。"
        default: return "上一轮输出未通过检查。请返回简短完整 JSON，严格遵守当前工作流的数量、字段与时间限制。"
        }
    }
}

/// A readable rejected draft stays in memory for inspection, never for adoption.
public struct AIRejectedDraft: Error, LocalizedError {
    public let issue: AIResponseIssue
    public let draft: AIDraft
    public var errorDescription: String? { issue.errorDescription }
}

public struct AIContextOptions: Codable, Equatable {
    public var notes = false
    public var history = false
    public var historyDays = 7
    public var background = false
    public var agent = false
    public init() {}

    private static let preferenceKey = "pareAIContextOptionsByEndpoint"
    public static func load(endpoint: URL, defaults: UserDefaults = .standard) -> AIContextOptions? {
        guard let data = defaults.data(forKey: preferenceKey),
              let map = try? JSONDecoder().decode([String: AIContextOptions].self, from: data) else { return nil }
        return map[endpoint.absoluteString]
    }
    public func save(endpoint: URL, defaults: UserDefaults = .standard) {
        var map = defaults.data(forKey: Self.preferenceKey)
            .flatMap { try? JSONDecoder().decode([String: AIContextOptions].self, from: $0) } ?? [:]
        var bounded = self
        bounded.historyDays = historyDays == 30 ? 30 : 7
        map[endpoint.absoluteString] = bounded
        if let data = try? JSONEncoder().encode(map) { defaults.set(data, forKey: Self.preferenceKey) }
    }
    public static func forget(endpoint: URL, defaults: UserDefaults = .standard) {
        guard let data = defaults.data(forKey: preferenceKey),
              var map = try? JSONDecoder().decode([String: AIContextOptions].self, from: data) else { return }
        map.removeValue(forKey: endpoint.absoluteString)
        if let data = try? JSONEncoder().encode(map) { defaults.set(data, forKey: preferenceKey) }
    }
}
