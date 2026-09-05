import Foundation

public enum GoalStatus: String, Codable, CaseIterable {
    case active, paused, achieved
    public var title: String {
        switch self { case .active: return "进行中"; case .paused: return "暂停"; case .achieved: return "已达成" }
    }
}

public struct LongTermGoal: Codable, Equatable, Identifiable {
    public var id: UUID
    public var title: String
    public var motivation: String
    public var targetDate: Date?
    public var taskIDs: [UUID]
    public var status: GoalStatus
    public var createdAt: Date
    public var updatedAt: Date
    public init(id: UUID = UUID(), title: String, motivation: String = "", targetDate: Date? = nil,
                taskIDs: [UUID] = [], status: GoalStatus = .active, now: Date = Date()) {
        self.id = id; self.title = title; self.motivation = motivation; self.targetDate = targetDate
        self.taskIDs = taskIDs; self.status = status; createdAt = now; updatedAt = now
    }
}

public struct AgentPreferences: Codable, Equatable {
    public var planningMinutes = 30
    public var startingMinutes = 5
    public var notes = ""
    public init() {}
}

public struct AIUserFeedback: Codable, Equatable {
    public var helpful: Bool
    public var comment: String
    public var date: Date
    public init(helpful: Bool, comment: String = "", date: Date = Date()) {
        self.helpful = helpful; self.comment = String(comment.prefix(300)); self.date = date
    }
}

public enum PlanningPhase: String, Codable {
    case gathering, thinking, needsClarification, awaitingReview, draftFilled, applied, discarded, reviewClosed, failed, cancelled, interrupted
    public var title: String {
        switch self {
        case .gathering: return "读取获准使用的本地上下文"
        case .thinking: return "正在分析"
        case .needsClarification: return "等待补充信息"
        case .awaitingReview: return "建议待确认"
        case .draftFilled: return "已填入草稿"
        case .applied: return "已采纳建议"
        case .discarded: return "已丢弃建议"
        case .reviewClosed: return "关闭时未采纳"
        case .failed: return "生成失败，可手动重试"
        case .cancelled: return "已取消"
        case .interrupted: return "上次分析中断，可重新生成"
        }
    }
}

public struct PlanningRun: Codable, Equatable, Identifiable {
    public var id = UUID()
    public var date: Date
    public var workflow: AIWorkflow
    public var phase: PlanningPhase = .gathering
    public var toolsUsed: [String]
    public var summaryID: UUID?
    public var diagnosticCode: String?
    public init(date: Date = Date(), workflow: AIWorkflow, toolsUsed: [String]) {
        self.date = date; self.workflow = workflow; self.toolsUsed = toolsUsed
    }
}

public struct PlanningAgentContext: Codable, Equatable {
    public struct Goal: Codable, Equatable {
        public var goal: LongTermGoal
        public var linkedTaskCount: Int
        public var completedLinkedTaskCount: Int
        public var missingLinkedTaskCount: Int
    }
    public struct Feedback: Codable, Equatable {
        public var date: Date
        public var helpful: Bool
        public var comment: String
        public var source: String = "用户对相关 AI 请求的反馈；不代表稳定习惯"
    }
    public var goals: [Goal]
    public var preferences: AgentPreferences
    public var recentRelatedFeedback: [Feedback]
}

extension Store {
    public func planningAgentContext(goalIDs: Set<UUID>, taskIDs: Set<UUID>) -> PlanningAgentContext {
        let all = tasks + archive
        let goalData = goals.filter { goalIDs.contains($0.id) }.prefix(3).map { goal in
            let linked = all.filter { goal.taskIDs.contains($0.id) }
            return PlanningAgentContext.Goal(goal: goal, linkedTaskCount: linked.count,
                completedLinkedTaskCount: linked.filter(\.completed).count,
                missingLinkedTaskCount: Set(goal.taskIDs).subtracting(linked.map(\.id)).count)
        }
        let feedback = aiSummaries.filter { !Set($0.sourceIDs).isDisjoint(with: taskIDs) }
            .compactMap(\.feedback).sorted { $0.date > $1.date }.prefix(5).map {
                PlanningAgentContext.Feedback(date: $0.date, helpful: $0.helpful, comment: $0.comment)
            }
        return PlanningAgentContext(goals: goalData, preferences: agentPreferences, recentRelatedFeedback: feedback)
    }
}
