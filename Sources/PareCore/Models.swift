import Foundation

/// The four Eisenhower quadrants.
public enum Quadrant: Int, Codable, CaseIterable, Hashable {
    case importantUrgent = 0      // 重要 · 紧急
    case importantNotUrgent = 1   // 重要 · 不紧急
    case urgentNotImportant = 2   // 紧急 · 不重要
    case neither = 3              // 不重要 · 不紧急

    public var title: String {
        switch self {
        case .importantUrgent:    return "重要 · 紧急"
        case .importantNotUrgent: return "重要 · 不紧急"
        case .urgentNotImportant: return "紧急 · 不重要"
        case .neither:            return "不重要 · 不紧急"
        }
    }

    public var hint: String {
        switch self {
        case .importantUrgent:    return "马上做"
        case .importantNotUrgent: return "安排时间做"
        case .urgentNotImportant: return "尽快处理或委托"
        case .neither:            return "有空再说 / 删掉"
        }
    }
}

/// A deliberately small recurrence vocabulary for task reminders. Pare keeps
/// recurrence attached to the reminder rather than pretending it is a due
/// date: due dates describe the work, reminders describe when to interrupt.
public enum RecurrenceRule: String, Codable, CaseIterable, Hashable {
    case daily
    case weekly
    case monthly

    public var title: String {
        switch self {
        case .daily:   return "每天"
        case .weekly:  return "每周"
        case .monthly: return "每月"
        }
    }
}

public enum BlockerReason: String, Codable, CaseIterable, Hashable {
    case unclear
    case tooLarge
    case lowEnergy
    case resistance

    public var title: String {
        switch self {
        case .unclear: return "不知道下一步"
        case .tooLarge: return "任务太大"
        case .lowEnergy: return "没有精力"
        case .resistance: return "有压力或抗拒"
        }
    }
}

public struct BlockerEntry: Identifiable, Codable, Hashable {
    public var id: UUID
    public var reason: BlockerReason
    public var at: Date

    public init(id: UUID = UUID(), reason: BlockerReason, at: Date = Date()) {
        self.id = id; self.reason = reason; self.at = at
    }
}

public struct ProgressSession: Identifiable, Codable, Hashable {
    public var id: UUID
    public var startedAt: Date
    public var endedAt: Date
    public var minutes: Int
    public var targetMinutes: Int?

    public init(id: UUID = UUID(), startedAt: Date, endedAt: Date,
                minutes: Int, targetMinutes: Int? = nil) {
        self.id = id; self.startedAt = startedAt; self.endedAt = endedAt
        self.minutes = minutes; self.targetMinutes = targetMinutes
    }
}

public enum ImplementationCueKind: String, Codable, CaseIterable, Hashable {
    case time
    case returnToComputer
    case openApplication

    public var title: String {
        switch self {
        case .time: return "到指定时间"
        case .returnToComputer: return "回到电脑时"
        case .openApplication: return "打开应用时"
        }
    }
}

public struct ImplementationPlan: Codable, Hashable {
    public var kind: ImplementationCueKind
    public var action: String
    public var hour: Int?
    public var minute: Int?
    public var applicationBundleID: String?
    public var applicationName: String?

    public init(kind: ImplementationCueKind, action: String,
                hour: Int? = nil, minute: Int? = nil,
                applicationBundleID: String? = nil,
                applicationName: String? = nil) {
        self.kind = kind; self.action = action
        self.hour = hour; self.minute = minute
        self.applicationBundleID = applicationBundleID
        self.applicationName = applicationName
    }

    public var summary: String {
        switch kind {
        case .time:
            return String(format: "如果到了 %02d:%02d，那么%@", hour ?? 9, minute ?? 0, action)
        case .returnToComputer:
            return "如果回到电脑旁，那么\(action)"
        case .openApplication:
            return "如果打开\(applicationName ?? "指定应用")，那么\(action)"
        }
    }
}

/// A raw, unprocessed capture written in "随手记" mode.
public struct InboxItem: Identifiable, Codable, Hashable {
    public var id: UUID
    public var text: String
    public var createdAt: Date
    /// Filled when capture text contains a small, local natural-language
    /// reminder such as “30 分钟后提醒我喝水”. It follows the item into a task.
    public var suggestedRemindAt: Date?

    public init(id: UUID = UUID(), text: String, createdAt: Date = Date(),
                suggestedRemindAt: Date? = nil) {
        self.id = id
        self.text = text
        self.createdAt = createdAt
        self.suggestedRemindAt = suggestedRemindAt
    }
}

public enum IdeaStage: String, Codable, CaseIterable, Hashable {
    case seed, exploring, building, parked, archived

    public var title: String {
        switch self {
        case .seed: return "萌芽"
        case .exploring: return "探索"
        case .building: return "推进"
        case .parked: return "暂放"
        case .archived: return "归档"
        }
    }
}

public enum IdeaEntryKind: String, Codable, CaseIterable, Hashable {
    case note, finding, decision

    public var title: String {
        switch self {
        case .note: return "记录"
        case .finding: return "发现"
        case .decision: return "决定"
        }
    }
}

public struct IdeaEntry: Identifiable, Codable, Hashable {
    public var id: UUID
    public var text: String
    public var kind: IdeaEntryKind
    public var author: String?
    public var createdAt: Date

    public init(id: UUID = UUID(), text: String, kind: IdeaEntryKind = .note, createdAt: Date = Date(), author: String? = nil) {
        self.id = id
        self.text = text
        self.kind = kind
        self.author = author
        self.createdAt = createdAt
    }
}

/// An idea retains its evolving context while several concrete actions can
/// move through the task system independently.
public struct ThoughtItem: Identifiable, Codable, Hashable {
    public var id: UUID
    public var text: String
    public var notes: String?
    public var createdAt: Date
    public var updatedAt: Date
    /// Optional date to intentionally bring the idea back into the morning
    /// planning nudge, without turning it into a task or standalone alert.
    public var reviewAt: Date?
    public var stage: IdeaStage
    public var entries: [IdeaEntry]
    public var linkedTaskIDs: [UUID]
    public var nextQuestion: String?
    public var nextQuestionUpdatedAt: Date?

    public init(id: UUID = UUID(), text: String, notes: String? = nil,
                createdAt: Date = Date(), updatedAt: Date = Date(), reviewAt: Date? = nil,
                stage: IdeaStage = .seed, entries: [IdeaEntry] = [],
                linkedTaskIDs: [UUID] = [], nextQuestion: String? = nil, nextQuestionUpdatedAt: Date? = nil) {
        self.id = id
        self.text = text
        self.notes = notes
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.reviewAt = reviewAt
        self.stage = stage
        self.entries = entries
        self.linkedTaskIDs = linkedTaskIDs
        self.nextQuestion = nextQuestion
        self.nextQuestionUpdatedAt = nextQuestionUpdatedAt
    }

    private enum CodingKeys: String, CodingKey {
        case id, text, notes, createdAt, updatedAt, reviewAt, stage, entries, linkedTaskIDs, nextQuestion, nextQuestionUpdatedAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        text = try container.decode(String.self, forKey: .text)
        notes = try container.decodeIfPresent(String.self, forKey: .notes)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
        reviewAt = try container.decodeIfPresent(Date.self, forKey: .reviewAt)
        stage = try container.decodeIfPresent(IdeaStage.self, forKey: .stage) ?? .seed
        entries = try container.decodeIfPresent([IdeaEntry].self, forKey: .entries) ?? []
        linkedTaskIDs = try container.decodeIfPresent([UUID].self, forKey: .linkedTaskIDs) ?? []
        nextQuestion = try container.decodeIfPresent(String.self, forKey: .nextQuestion)
        nextQuestionUpdatedAt = try container.decodeIfPresent(Date.self, forKey: .nextQuestionUpdatedAt)
    }
}

/// The single running focus timer. Only its start instant is persisted, so a
/// relaunch resumes from real elapsed time without a drifting tick counter.
public struct ActiveTimer: Codable, Equatable {
    public var taskID: UUID
    public var startedAt: Date
    public var targetMinutes: Int?

    public init(taskID: UUID, startedAt: Date, targetMinutes: Int? = nil) {
        self.taskID = taskID
        self.startedAt = startedAt
        self.targetMinutes = targetMinutes
    }
}

/// A task that is progressing elsewhere (another machine, an external AI, or
/// another person). Several can coexist. This is wall-clock task state and is
/// deliberately separate from `ActiveTimer`, which measures the user's focus.
public struct ParallelTaskRun: Identifiable, Codable, Hashable {
    public var id: UUID { taskID }
    public var taskID: UUID
    public var startedAt: Date
    public var nextCheckAt: Date?

    public init(taskID: UUID, startedAt: Date = Date(), nextCheckAt: Date? = nil) {
        self.taskID = taskID
        self.startedAt = startedAt
        self.nextCheckAt = nextCheckAt
    }
}

public enum ParallelRunEndReason: String, Codable, Hashable {
    case paused
    case completed
}

/// Historical wall-clock interval for an externally progressing task. It does
/// not contribute to `actualMinutes` or focus calibration.
public struct ParallelRunSession: Identifiable, Codable, Hashable {
    public var id: UUID
    public var startedAt: Date
    public var endedAt: Date
    public var elapsedMinutes: Int
    public var reason: ParallelRunEndReason

    public init(id: UUID = UUID(), startedAt: Date, endedAt: Date,
                elapsedMinutes: Int, reason: ParallelRunEndReason) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.elapsedMinutes = max(1, elapsedMinutes)
        self.reason = reason
    }
}

/// A task that lives in a quadrant, optionally flagged as part of "today".
public struct TaskItem: Identifiable, Codable, Hashable {
    public var id: UUID
    public var text: String
    public var quadrant: Quadrant
    public var createdAt: Date
    public var isToday: Bool
    public var completed: Bool
    public var completedAt: Date?
    /// Estimated effort in minutes. `nil` means "not estimated". Optional so old
    /// store files (without this key) decode cleanly.
    public var estimatedMinutes: Int?
    /// Optional one-shot reminder time for this task. `nil` means "no reminder".
    /// Optional so old store files (without this key) decode cleanly.
    public var remindAt: Date?
    /// Actual effort in minutes, recorded on completion. `nil` means "not
    /// recorded" — the review layer leaves the estimate-vs-actual comparison
    /// blank rather than showing a misleading number. Optional so old store
    /// files (without this key) decode cleanly.
    public var actualMinutes: Int?
    /// The last time this task was explicitly progressed (organized, flagged
    /// for today, moved between quadrants, completed, or had its actual time
    /// recorded). `nil` means "never progressed" — the review layer falls back
    /// to `createdAt`. Optional so old store files (without this key) decode
    /// cleanly.
    public var lastProgressAt: Date?
    /// Optional detail fields keep the quadrant row terse while giving a task
    /// somewhere to hold thought, a concrete next action and a real deadline.
    public var note: String?
    public var nextAction: String?
    public var dueAt: Date?
    /// Recurrence applies to `remindAt`, never implicitly to `dueAt`.
    public var recurrence: RecurrenceRule?
    /// Stable user-defined order inside the Today planner.
    public var todayOrder: Int?
    /// The time the user intends to work on this task. This is deliberately
    /// separate from `dueAt` (latest acceptable finish) and `remindAt`
    /// (an arbitrary interruption). A scheduled task is rendered as a block
    /// on today's timeline.
    public var scheduledAt: Date?
    /// The visible size of the time block. It normally follows the estimate,
    /// but is persisted so resizing the block remains stable across relaunches.
    public var scheduledMinutes: Int?
    /// Minutes before `scheduledAt` to notify. `nil` means no schedule alert;
    /// zero means exactly at the start time.
    public var scheduleReminderLeadMinutes: Int?
    public var definitionOfDone: String?
    public var isDailyWin: Bool?
    public var implementationPlan: ImplementationPlan?
    public var lastImplementationTriggeredAt: Date?
    public var progressSessions: [ProgressSession]?
    public var parallelRunSessions: [ParallelRunSession]?
    public var blockerEntries: [BlockerEntry]?

    public init(id: UUID = UUID(),
                text: String,
                quadrant: Quadrant,
                createdAt: Date = Date(),
                isToday: Bool = false,
                completed: Bool = false,
                completedAt: Date? = nil,
                estimatedMinutes: Int? = nil,
                remindAt: Date? = nil,
                actualMinutes: Int? = nil,
                lastProgressAt: Date? = nil,
                note: String? = nil,
                nextAction: String? = nil,
                dueAt: Date? = nil,
                recurrence: RecurrenceRule? = nil,
                todayOrder: Int? = nil,
                scheduledAt: Date? = nil,
                scheduledMinutes: Int? = nil,
                scheduleReminderLeadMinutes: Int? = nil,
                definitionOfDone: String? = nil,
                isDailyWin: Bool? = nil,
                implementationPlan: ImplementationPlan? = nil,
                lastImplementationTriggeredAt: Date? = nil,
                progressSessions: [ProgressSession]? = nil,
                parallelRunSessions: [ParallelRunSession]? = nil,
                blockerEntries: [BlockerEntry]? = nil) {
        self.id = id
        self.text = text
        self.quadrant = quadrant
        self.createdAt = createdAt
        self.isToday = isToday
        self.completed = completed
        self.completedAt = completedAt
        self.estimatedMinutes = estimatedMinutes
        self.remindAt = remindAt
        self.actualMinutes = actualMinutes
        self.lastProgressAt = lastProgressAt
        self.note = note
        self.nextAction = nextAction
        self.dueAt = dueAt
        self.recurrence = recurrence
        self.todayOrder = todayOrder
        self.scheduledAt = scheduledAt
        self.scheduledMinutes = scheduledMinutes
        self.scheduleReminderLeadMinutes = scheduleReminderLeadMinutes
        self.definitionOfDone = definitionOfDone
        self.isDailyWin = isDailyWin
        self.implementationPlan = implementationPlan
        self.lastImplementationTriggeredAt = lastImplementationTriggeredAt
        self.progressSessions = progressSessions
        self.parallelRunSessions = parallelRunSessions
        self.blockerEntries = blockerEntries
    }
}

/// Compact duration formatting: 25 -> "25m", 60 -> "1h", 90 -> "1h30m".
public enum DurationLabel {
    public static func short(_ minutes: Int) -> String {
        guard minutes > 0 else { return "" }
        let h = minutes / 60
        let m = minutes % 60
        if h == 0 { return "\(m)m" }
        if m == 0 { return "\(h)h" }
        return "\(h)h\(m)m"
    }
}
