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

/// A raw, unprocessed capture written in "随手记" mode.
public struct InboxItem: Identifiable, Codable, Hashable {
    public var id: UUID
    public var text: String
    public var createdAt: Date

    public init(id: UUID = UUID(), text: String, createdAt: Date = Date()) {
        self.id = id
        self.text = text
        self.createdAt = createdAt
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

    public init(id: UUID = UUID(),
                text: String,
                quadrant: Quadrant,
                createdAt: Date = Date(),
                isToday: Bool = false,
                completed: Bool = false,
                completedAt: Date? = nil,
                estimatedMinutes: Int? = nil,
                remindAt: Date? = nil) {
        self.id = id
        self.text = text
        self.quadrant = quadrant
        self.createdAt = createdAt
        self.isToday = isToday
        self.completed = completed
        self.completedAt = completedAt
        self.estimatedMinutes = estimatedMinutes
        self.remindAt = remindAt
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
