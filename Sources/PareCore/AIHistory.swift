import Foundation

public enum AISummaryOutcome: String, Codable {
    case generated, applied, filledDraft, discarded
    public var title: String {
        switch self {
        case .generated: return "已生成，未记录采纳"
        case .applied: return "已采纳部分或全部建议，不代表完成"
        case .filledDraft: return "已填入草稿，尚不代表保存或执行"
        case .discarded: return "已丢弃建议"
        }
    }
}

public struct AISummaryRecord: Codable, Equatable, Identifiable {
    public var id: UUID
    public var date: Date
    public var workflow: AIWorkflow
    public var sourceIDs: [UUID]
    public var summary: String
    public var outcome: AISummaryOutcome
    public var adoptedCount: Int
    public var feedback: AIUserFeedback?

    public init(id: UUID = UUID(), date: Date = Date(), workflow: AIWorkflow,
                sourceIDs: [UUID], summary: String, outcome: AISummaryOutcome = .generated,
                adoptedCount: Int = 0) {
        self.id = id; self.date = date; self.workflow = workflow; self.sourceIDs = sourceIDs
        self.summary = summary; self.outcome = outcome; self.adoptedCount = adoptedCount
    }
}

public struct AIHistoryContext: Codable, Equatable {
    public struct Day: Codable, Equatable {
        public var date: Date
        public var completedCount: Int
        public var recordedSessionCount: Int
        public var recordedMinutes: Int
        public var blockers: [String: Int]
    }
    public struct TaskHistory: Codable, Equatable {
        public var id: UUID
        public var createdAt: Date
        public var lastRecordedChangeAt: Date?
        public var sessionCountInPeriod: Int
        public var recordedMinutesInPeriod: Int
        public var blockersInPeriod: [String: Int]
    }
    public struct PreviousAnalysis: Codable, Equatable {
        public var date: Date
        public var workflow: AIWorkflow
        public var summary: String
        public var outcome: String
        public var adoptedSuggestionCount: Int
        public var source: String = "AI 生成的分析，不是用户已确认的事实"
    }
    public var periodStart: Date
    public var periodEnd: Date
    public var days: [Day]
    public var selectedTaskHistory: [TaskHistory]
    public var comparableCompletedTaskCount: Int
    public var estimatedMinutesForComparableTasks: Int
    public var actualMinutesForComparableTasks: Int
    public var userBackground: String?
    public var previousAnalyses: [PreviousAnalysis]
    public var source: String = "Step 本地记录汇总；时间统计仅含已停止计时的会话，不含未记录工作"
}

extension Store {
    /// Read-only, bounded context assembly. No model call and no mutation.
    /// Unselected task titles/notes and browsing data are never included.
    public func aiHistoryContext(selectedTaskIDs: Set<UUID>, days requestedDays: Int = 7,
                                 includeBackground: Bool = false, summaryIDs: Set<UUID> = [],
                                 now: Date = Date()) -> AIHistoryContext {
        let count = min(30, max(1, requestedDays))
        let today = calendar.startOfDay(for: now)
        let start = calendar.date(byAdding: .day, value: -(count - 1), to: today) ?? today
        let all = tasks + archive
        func inPeriod(_ date: Date) -> Bool { date >= start && date <= now }
        let completed = all.filter { $0.completed && $0.completedAt.map(inPeriod) == true }
        let sessions = all.flatMap { $0.progressSessions ?? [] }.filter { inPeriod($0.endedAt) }
        let blockers = all.flatMap { $0.blockerEntries ?? [] }.filter { inPeriod($0.at) }
        func blockerCounts(_ entries: [BlockerEntry]) -> [String: Int] {
            entries.reduce(into: [:]) { $0[$1.reason.title, default: 0] += 1 }
        }
        let buckets = (0..<count).map { offset -> AIHistoryContext.Day in
            let day = calendar.date(byAdding: .day, value: offset, to: start) ?? start
            let dailySessions = sessions.filter { calendar.isDate($0.endedAt, inSameDayAs: day) }
            return .init(date: day,
                         completedCount: completed.filter { calendar.isDate($0.completedAt!, inSameDayAs: day) }.count,
                         recordedSessionCount: dailySessions.count,
                         recordedMinutes: dailySessions.reduce(0) { $0 + $1.minutes },
                         blockers: blockerCounts(blockers.filter { calendar.isDate($0.at, inSameDayAs: day) }))
        }
        let history = all.filter { selectedTaskIDs.contains($0.id) }.prefix(20).map { task in
            let taskSessions = (task.progressSessions ?? []).filter { inPeriod($0.endedAt) }
            return AIHistoryContext.TaskHistory(id: task.id, createdAt: task.createdAt,
                lastRecordedChangeAt: task.lastProgressAt.flatMap { $0 <= now ? $0 : nil },
                sessionCountInPeriod: taskSessions.count,
                recordedMinutesInPeriod: taskSessions.reduce(0) { $0 + $1.minutes },
                blockersInPeriod: blockerCounts((task.blockerEntries ?? []).filter { inPeriod($0.at) }))
        }
        let comparable = completed.filter { $0.actualMinutes != nil && $0.estimatedMinutes != nil }
        let analyses = aiSummaries.filter { summaryIDs.contains($0.id) && $0.date <= now }
            .sorted { $0.date > $1.date }.prefix(3).map { record in
                AIHistoryContext.PreviousAnalysis(date: record.date, workflow: record.workflow,
                    summary: record.summary, outcome: record.outcome.title, adoptedSuggestionCount: record.adoptedCount)
            }
        return AIHistoryContext(periodStart: start, periodEnd: now, days: buckets,
            selectedTaskHistory: history, comparableCompletedTaskCount: comparable.count,
            estimatedMinutesForComparableTasks: comparable.reduce(0) { $0 + ($1.estimatedMinutes ?? 0) },
            actualMinutesForComparableTasks: comparable.reduce(0) { $0 + ($1.actualMinutes ?? 0) },
            userBackground: includeBackground && !aiBackground.isEmpty ? String(aiBackground.prefix(1500)) : nil,
            previousAnalyses: analyses)
    }
}
