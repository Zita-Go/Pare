import Foundation

public enum IntelligenceHintReason: String, Codable, Equatable {
    case ideaReview, actionResult, repeatedBlocker, todayFocus
}

/// A local suggestion to open the assistant. Computing or displaying it never
/// creates an AI request, changes a task, or schedules a notification.
public struct IntelligenceHint: Identifiable, Equatable {
    public var id: String { "\(subjectID?.uuidString ?? "today"):\(reason.rawValue)" }
    public var subjectID: UUID?
    public var reason: IntelligenceHintReason
    public var title: String
    public var detail: String
    public var date: Date

    public var intent: IntelligenceIntent {
        switch reason {
        case .ideaReview, .actionResult: return .idea
        case .repeatedBlocker: return .unblock
        case .todayFocus: return .today
        }
    }

    public var actionTitle: String {
        switch reason {
        case .ideaReview, .actionResult: return "接着想一想"
        case .repeatedBlocker: return "帮我开始"
        case .todayFocus: return "帮我安排"
        }
    }
}

public enum IntelligenceHintEngine {
    /// Keep the currently displayed card actionable after recording its display.
    /// Still revalidate the source on every change, so deleted/archived/completed
    /// subjects and changed review settings disappear immediately.
    public static func selection(current: IntelligenceHint?, candidates: [IntelligenceHint],
                                 history: IntelligenceHintHistory, now: Date) -> IntelligenceHint? {
        if let current, let live = candidates.first(where: { $0.id == current.id }) {
            return live
        }
        return candidates.first { history.allows($0, now: now) }
    }

    /// Explicit review dates precede results, repeated blockers, then planning.
    /// Archived tasks may provide results for a living idea, but cannot be
    /// suggested as a task to resume. Missing records are never treated as work
    /// not having happened.
    public static func candidates(thoughts: [ThoughtItem], tasks: [TaskItem],
                                  archive: [TaskItem] = [], activeTimer: ActiveTimer? = nil,
                                  parallelRuns: [ParallelTaskRun] = [], includeToday: Bool = true,
                                  now: Date = Date(), calendar: Calendar = .current) -> [IntelligenceHint] {
        guard activeTimer == nil else { return [] }
        let ideas = thoughts.filter { $0.stage != .archived }
        var reviews: [IntelligenceHint] = []
        var results: [IntelligenceHint] = []
        for idea in ideas {
            if let review = idea.reviewAt, review <= now {
                reviews.append(.init(subjectID: idea.id, reason: .ideaReview, title: idea.text,
                                     detail: "你为这个想法设置的回看日期已到。", date: review))
            }
            // Setting an idea aside opts out of automatic result prompts;
            // only a review date explicitly chosen by the user can surface it.
            guard idea.stage != .parked else { continue }
            let completedAt = (tasks + archive)
                .filter { idea.linkedTaskIDs.contains($0.id) && $0.completed }
                .compactMap(\.completedAt).filter { $0 <= now }.max()
            // Legacy records did not have a question-specific timestamp. When
            // a question exists, prefer conservative suppression to an old nudge.
            let questionDate = idea.nextQuestionUpdatedAt ??
                ((idea.nextQuestion?.isEmpty == false) ? idea.updatedAt : nil)
            let exploredAt = idea.entries.map(\.createdAt).max()
            if let completedAt,
               questionDate.map({ $0 < completedAt }) ?? true,
               exploredAt.map({ $0 < completedAt }) ?? true {
                results.append(.init(subjectID: idea.id, reason: .actionResult, title: idea.text,
                                     detail: "关联行动已有完成记录，可以回看它带来了什么。", date: completedAt))
            }
        }
        let running = Set(parallelRuns.map(\.taskID))
        let recentStart = calendar.date(byAdding: .day, value: -7, to: now) ?? now.addingTimeInterval(-7 * 86400)
        let blockers: [IntelligenceHint] = tasks.compactMap { task in
            guard !task.completed, !running.contains(task.id) else { return nil }
            let latestProgress = (task.progressSessions ?? []).map(\.endedAt).max()
            let entries = (task.blockerEntries ?? []).filter {
                $0.at >= recentStart && $0.at <= now
            }
            let unresolved = entries.filter { entry in latestProgress.map { $0 < entry.at } ?? true }
            let repeated = Dictionary(grouping: unresolved, by: \.reason).values.filter { $0.count >= 2 }
                .flatMap { $0 }.max { $0.at < $1.at }
            guard let repeated else { return nil }
            return .init(subjectID: task.id, reason: .repeatedBlocker, title: task.text,
                         detail: "你近期多次记录了「\(repeated.reason.title)」，可以试一个更小的起点。", date: repeated.at)
        }
        func ascending(_ left: IntelligenceHint, _ right: IntelligenceHint) -> Bool {
            left.date == right.date ? left.id < right.id : left.date < right.date
        }
        var candidates = reviews.sorted(by: ascending) + results.sorted { ascending($1, $0) } + blockers.sorted { ascending($1, $0) }
        // A completed chosen focus is still a choice. Do not erase that fact
        // or infer low energy from missing timers / incomplete checkboxes.
        if includeToday && !tasks.contains(where: { $0.isToday && $0.isDailyWin == true }) {
            candidates.append(.init(subjectID: nil, reason: .todayFocus, title: "今天想先推进哪一件？",
                                    detail: "尚未选定今日重点，可以按这次可用时间安排。", date: calendar.startOfDay(for: now)))
        }
        return candidates
    }
}

/// Persisted separately from user content. The caller supplies storage, time,
/// and calendar, keeping the policy deterministic and safe for isolated tests.
public struct IntelligenceHintHistory: Codable, Equatable {
    public struct Entry: Codable, Equatable {
        public var lastDisplayedAt: Date?
        public var snoozedUntil: Date?
    }

    public private(set) var entries: [String: Entry] = [:]
    public init() {}

    public func allows(_ hint: IntelligenceHint, now: Date) -> Bool {
        guard let entry = entries[hint.id] else { return true }
        // "Later" is an explicit request to show it again at that time and
        // takes precedence over the ordinary 24-hour display cooldown.
        if let until = entry.snoozedUntil { return now >= until }
        return entry.lastDisplayedAt.map { now.timeIntervalSince($0) >= 86400 } ?? true
    }

    public mutating func recordDisplay(_ hint: IntelligenceHint, now: Date) {
        entries[hint.id] = Entry(lastDisplayedAt: now, snoozedUntil: nil)
        if entries.count > 512 {
            let newest = entries.sorted {
                max($0.value.lastDisplayedAt ?? .distantPast, $0.value.snoozedUntil ?? .distantPast) >
                max($1.value.lastDisplayedAt ?? .distantPast, $1.value.snoozedUntil ?? .distantPast)
            }.prefix(512)
            entries = Dictionary(uniqueKeysWithValues: newest.map { ($0.key, $0.value) })
        }
    }

    public mutating func snooze(_ hint: IntelligenceHint, until: Date) {
        var entry = entries[hint.id] ?? Entry()
        entry.snoozedUntil = until
        entries[hint.id] = entry
    }

    public mutating func snoozeForWeek(_ hint: IntelligenceHint, now: Date, calendar: Calendar) {
        let end = calendar.dateInterval(of: .weekOfYear, for: now)?.end
            ?? calendar.date(byAdding: .day, value: 7, to: now) ?? now.addingTimeInterval(7 * 86400)
        snooze(hint, until: end)
    }
}
