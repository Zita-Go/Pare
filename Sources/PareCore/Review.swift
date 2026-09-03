import Foundation

// MARK: - Review value types
//
// The "回看层": pure, read-only projections of the data already in `Store`.
// Every type here is immutable and `Equatable`, so the review logic can be
// unit-tested by feeding fixed data and asserting on fields — no UI, no I/O.

/// The evening "今日小结": what got finished today, how much focus it took, and
/// what is carried over to tomorrow.
public struct TodaySummary: Equatable {
    /// Start-of-day of the day being summarised.
    public let date: Date
    /// How many tasks were completed today (across all quadrants).
    public let completedCount: Int
    /// The tasks completed today, oldest completion first.
    public let completedTasks: [TaskItem]
    /// Focus minutes today: `actualMinutes` when recorded, otherwise falling
    /// back to `estimatedMinutes`, otherwise 0.
    public let focusMinutes: Int
    /// Today-flagged tasks that are still incomplete — "留到明天".
    public let carryOver: [TaskItem]
    /// "预估 vs 实际" over today's completions that recorded *both* an estimate
    /// and an actual. Empty (`hasData == false`) when nothing is comparable —
    /// e.g. the actual-time recording (T5) hasn't landed or was skipped — in
    /// which case the summary's bias area hides itself rather than mislead.
    public let bias: EstimateBias

    public init(date: Date, completedCount: Int, completedTasks: [TaskItem],
                focusMinutes: Int, carryOver: [TaskItem], bias: EstimateBias) {
        self.date = date
        self.completedCount = completedCount
        self.completedTasks = completedTasks
        self.focusMinutes = focusMinutes
        self.carryOver = carryOver
        self.bias = bias
    }

    /// Nothing finished today — the summary uses a gentle closing line.
    public var isZeroCompletion: Bool { completedCount == 0 }

    /// A concise one-line "预估 vs 实际" glimpse for the summary card, or `nil`
    /// when there is nothing comparable (so the UI hides the whole area). Kept
    /// here — not in the App layer — so the wording stays unit-tested.
    public var biasHint: String? {
        guard bias.hasData else { return nil }
        let est = DurationLabel.short(bias.totalEstimated)
        let act = DurationLabel.short(bias.totalActual)
        if bias.deltaMinutes == 0 {
            return "预估 \(est) · 实际 \(act)，估得很准 👍"
        }
        let dir = bias.deltaMinutes > 0 ? "低估" : "高估"
        let avg = abs(bias.averageDeltaMinutes)
        if avg >= 1 {
            return "预估 \(est) · 实际 \(act)，平均每件\(dir)约 \(DurationLabel.short(avg))"
        }
        let total = DurationLabel.short(abs(bias.deltaMinutes))
        return "预估 \(est) · 实际 \(act)，合计\(dir)约 \(total)"
    }
}

/// A compact "预估 vs 实际" comparison over a set of tasks that recorded both.
public struct EstimateBias: Equatable {
    /// How many tasks had *both* an estimate and an actual (comparable).
    public let comparableCount: Int
    public let totalEstimated: Int
    public let totalActual: Int

    public init(comparableCount: Int, totalEstimated: Int, totalActual: Int) {
        self.comparableCount = comparableCount
        self.totalEstimated = totalEstimated
        self.totalActual = totalActual
    }

    /// Whether there is anything to compare at all.
    public var hasData: Bool { comparableCount > 0 }
    /// Positive = the work ran longer than estimated (underestimated).
    public var deltaMinutes: Int { totalActual - totalEstimated }
    /// Average per-task delta, rounded to the nearest minute.
    public var averageDeltaMinutes: Int {
        guard comparableCount > 0 else { return 0 }
        return Int((Double(deltaMinutes) / Double(comparableCount)).rounded())
    }
}

/// The Sunday-evening "本周回顾" over the natural week (Monday–Sunday).
public struct WeeklyReview: Equatable {
    /// Monday 00:00 of the reviewed week (inclusive).
    public let weekStart: Date
    /// The following Monday 00:00 (exclusive).
    public let weekEnd: Date
    public let completedCount: Int
    /// Completed-count keyed by quadrant.
    public let byQuadrant: [Quadrant: Int]
    /// Quadrant-II items that are being dragged out (incomplete & stalled).
    public let stalledFocus: [TaskItem]
    public let bias: EstimateBias
    /// A ready-to-paste Markdown rendering of this review.
    public let markdown: String

    public init(weekStart: Date, weekEnd: Date, completedCount: Int,
                byQuadrant: [Quadrant: Int], stalledFocus: [TaskItem],
                bias: EstimateBias, markdown: String) {
        self.weekStart = weekStart
        self.weekEnd = weekEnd
        self.completedCount = completedCount
        self.byQuadrant = byQuadrant
        self.stalledFocus = stalledFocus
        self.bias = bias
        self.markdown = markdown
    }

    /// The week has no completed work yet (new user / slow week).
    public var isEmpty: Bool { completedCount == 0 }
}

/// A soft, non-blocking "今日超载" hint. Present only when today's focus crosses
/// a threshold; `nil` otherwise.
public struct TodayOverload: Equatable {
    public let count: Int
    public let maxCount: Int
    public let estimatedMinutes: Int
    public let capacityMinutes: Int
    public let exceedsCount: Bool
    public let exceedsCapacity: Bool
    /// A single-sentence hint to show in the today area.
    public let message: String

    public init(count: Int, maxCount: Int, estimatedMinutes: Int, capacityMinutes: Int,
                exceedsCount: Bool, exceedsCapacity: Bool, message: String) {
        self.count = count
        self.maxCount = maxCount
        self.estimatedMinutes = estimatedMinutes
        self.capacityMinutes = capacityMinutes
        self.exceedsCount = exceedsCount
        self.exceedsCapacity = exceedsCapacity
        self.message = message
    }
}

/// A soft "象限四清理" nudge. Present only when Quadrant-IV ("不重要 · 不紧急")
/// has piled up to `threshold` incomplete items — the box whose Eisenhower
/// action is "删掉"; `nil` otherwise.
public struct QuadrantFourCleanup: Equatable {
    /// Incomplete items currently sitting in Quadrant-IV (the backlog).
    public let count: Int
    public let threshold: Int
    /// A single-sentence cleanup guidance to show above the quadrant.
    public let message: String

    public init(count: Int, threshold: Int, message: String) {
        self.count = count
        self.threshold = threshold
        self.message = message
    }
}

// MARK: - Store review queries

extension Store {

    /// The evening summary of `now`'s day. Read-only; never mutates data.
    public func todaySummary(now: Date = Date()) -> TodaySummary {
        let dayStart = calendar.startOfDay(for: now)
        // A task lives in `tasks` until the next-day rollover moves it to
        // `archive`; unioning both keeps the count correct across a rollover.
        let completed = (tasks + archive)
            .filter { t in
                guard t.completed, let at = t.completedAt else { return false }
                return calendar.isDate(at, inSameDayAs: now)
            }
            .sorted { ($0.completedAt ?? .distantPast) < ($1.completedAt ?? .distantPast) }
        let focusMinutes = completed.reduce(0) { $0 + ($1.actualMinutes ?? $1.estimatedMinutes ?? 0) }
        let carryOver = tasks
            .filter { $0.isToday && !$0.completed }
            .sorted { lhs, rhs in
                if lhs.quadrant.rawValue != rhs.quadrant.rawValue {
                    return lhs.quadrant.rawValue < rhs.quadrant.rawValue
                }
                return lhs.createdAt < rhs.createdAt
            }
        let bias = Self.estimateBias(for: completed)
        return TodaySummary(date: dayStart,
                            completedCount: completed.count,
                            completedTasks: completed,
                            focusMinutes: focusMinutes,
                            carryOver: carryOver,
                            bias: bias)
    }

    /// The review of the natural week (Mon–Sun) containing `now`.
    public func weeklyReview(now: Date = Date()) -> WeeklyReview {
        let (weekStart, weekEnd) = naturalWeek(containing: now)
        let completed = (tasks + archive)
            .filter { t in
                guard t.completed, let at = t.completedAt else { return false }
                return at >= weekStart && at < weekEnd
            }
            .sorted { ($0.completedAt ?? .distantPast) < ($1.completedAt ?? .distantPast) }

        var byQuadrant: [Quadrant: Int] = [:]
        for t in completed { byQuadrant[t.quadrant, default: 0] += 1 }

        let stalled = stalledFocusTasks(now: now)
        let bias = Self.estimateBias(for: completed)
        let markdown = weeklyMarkdown(weekStart: weekStart, weekEnd: weekEnd,
                                      completedCount: completed.count,
                                      byQuadrant: byQuadrant, stalled: stalled,
                                      bias: bias, now: now)

        return WeeklyReview(weekStart: weekStart, weekEnd: weekEnd,
                            completedCount: completed.count, byQuadrant: byQuadrant,
                            stalledFocus: stalled, bias: bias, markdown: markdown)
    }

    /// Quadrant-II ("重要 · 不紧急") tasks that are incomplete and have not been
    /// progressed for at least `focusStalenessDays`. Uses `lastProgressAt`, or
    /// `createdAt` when a task has never been explicitly progressed. Empty when
    /// the watch is disabled.
    public func stalledFocusTasks(now: Date = Date()) -> [TaskItem] {
        guard settings.focusStalenessEnabled else { return [] }
        let threshold = max(1, settings.focusStalenessDays)
        let today = calendar.startOfDay(for: now)
        return tasks
            .filter { t in
                guard t.quadrant == .importantNotUrgent, !t.completed else { return false }
                return daysSinceProgress(t, today: today) >= threshold
            }
            .sorted { ($0.lastProgressAt ?? $0.createdAt) < ($1.lastProgressAt ?? $1.createdAt) }
    }

    /// A soft overload hint when today's focus crosses the count/time budget,
    /// or `nil` when within budget or the watch is disabled. `now` is accepted
    /// for signature symmetry with the other review queries.
    public func todayOverload(now: Date = Date()) -> TodayOverload? {
        _ = now
        guard settings.todayOverloadEnabled else { return nil }
        // Capacity is about work still competing for today's attention. Finished
        // items remain in progress statistics but must not keep an overload
        // warning alive after the user has already done the work.
        let count = tasks.filter { $0.isToday && !$0.completed }.count
        let minutes = todayRemainingEstimatedMinutes
        let exceedsCount = count > settings.todayMaxCount
        let exceedsCapacity = minutes > settings.todayCapacityMinutes
        guard exceedsCount || exceedsCapacity else { return nil }

        let reason: String
        if exceedsCount && exceedsCapacity {
            reason = "今天排了 \(count) 件 · 约 \(DurationLabel.short(minutes))"
        } else if exceedsCount {
            reason = "今天排了 \(count) 件"
        } else {
            reason = "今天排了约 \(DurationLabel.short(minutes))"
        }
        let message = "\(reason)，挑出最要紧的几件，其余的往后放放。"
        return TodayOverload(count: count, maxCount: settings.todayMaxCount,
                             estimatedMinutes: minutes, capacityMinutes: settings.todayCapacityMinutes,
                             exceedsCount: exceedsCount, exceedsCapacity: exceedsCapacity,
                             message: message)
    }

    /// A soft cleanup nudge for Quadrant-IV ("不重要 · 不紧急") once its incomplete
    /// backlog reaches the threshold, or `nil` when below it or the watch is
    /// disabled. `now` is accepted for signature symmetry with the other review
    /// queries. Read-only — the actual clearing is an explicit `clearQuadrant`.
    public func quadrantFourCleanup(now: Date = Date()) -> QuadrantFourCleanup? {
        _ = now
        guard settings.quadrantCleanupEnabled else { return nil }
        let threshold = max(1, settings.quadrantCleanupThreshold)
        let count = tasks.reduce(0) { $0 + (($1.quadrant == .neither && !$1.completed) ? 1 : 0) }
        guard count >= threshold else { return nil }
        let message = "「不重要 · 不紧急」积了 \(count) 件 · 这格的动作是删掉，清一清腾出注意力。"
        return QuadrantFourCleanup(count: count, threshold: threshold, message: message)
    }

    // MARK: - Helpers (internal so tests and Reminders can reuse them)

    /// Whole calendar days elapsed since a task's last progress (or creation).
    func daysSinceProgress(_ task: TaskItem, today: Date) -> Int {
        let ref = task.lastProgressAt ?? task.createdAt
        return calendar.dateComponents([.day], from: calendar.startOfDay(for: ref), to: today).day ?? 0
    }

    /// The natural week (Monday 00:00 ..< next Monday 00:00) containing `date`.
    func naturalWeek(containing date: Date) -> (start: Date, end: Date) {
        let startOfToday = calendar.startOfDay(for: date)
        // `weekday`: 1 = Sunday … 7 = Saturday. Days since Monday (Monday = 0).
        let weekday = calendar.component(.weekday, from: startOfToday)
        let daysSinceMonday = (weekday + 5) % 7
        let start = calendar.date(byAdding: .day, value: -daysSinceMonday, to: startOfToday) ?? startOfToday
        let end = calendar.date(byAdding: .day, value: 7, to: start) ?? start.addingTimeInterval(7 * 86_400)
        return (start, end)
    }

    static func estimateBias(for tasks: [TaskItem]) -> EstimateBias {
        let comparable = tasks.filter { $0.estimatedMinutes != nil && $0.actualMinutes != nil }
        let est = comparable.reduce(0) { $0 + ($1.estimatedMinutes ?? 0) }
        let act = comparable.reduce(0) { $0 + ($1.actualMinutes ?? 0) }
        return EstimateBias(comparableCount: comparable.count, totalEstimated: est, totalActual: act)
    }

    // MARK: - Markdown

    private func monthDay(_ date: Date) -> String {
        let c = calendar.dateComponents([.month, .day], from: date)
        return "\(c.month ?? 0)月\(c.day ?? 0)日"
    }

    private func weeklyMarkdown(weekStart: Date, weekEnd: Date, completedCount: Int,
                                byQuadrant: [Quadrant: Int], stalled: [TaskItem],
                                bias: EstimateBias, now: Date) -> String {
        // Range end shown as the inclusive Sunday (weekEnd is exclusive).
        let lastDay = calendar.date(byAdding: .day, value: -1, to: weekEnd) ?? weekEnd
        var lines: [String] = []
        lines.append("# 本周回顾（\(monthDay(weekStart)) – \(monthDay(lastDay))）")
        lines.append("")

        if completedCount == 0 {
            lines.append("这周才刚开始，还没有完成的事。做完几件再回来看看。")
        } else {
            lines.append("本周完成 **\(completedCount)** 件。")
            lines.append("")
            lines.append("## 按象限分布")
            for q in Quadrant.allCases {
                let n = byQuadrant[q] ?? 0
                if n > 0 { lines.append("- \(q.title)：\(n)") }
            }
            lines.append("")
            lines.append("## 估时校准")
            lines.append(biasLine(bias))
        }

        if !stalled.isEmpty {
            let today = calendar.startOfDay(for: now)
            lines.append("")
            lines.append("## 象限二 · 一直被拖的事")
            for t in stalled {
                let days = daysSinceProgress(t, today: today)
                lines.append("- \(t.text)（已 \(days) 天没动）")
            }
        }

        return lines.joined(separator: "\n")
    }

    private func biasLine(_ bias: EstimateBias) -> String {
        guard bias.hasData else {
            return "还没有可对比的「预估 vs 实际」记录。"
        }
        let est = DurationLabel.short(bias.totalEstimated)
        let act = DurationLabel.short(bias.totalActual)
        if bias.deltaMinutes == 0 {
            return "记录了 \(bias.comparableCount) 件的实际用时：预估与实际都是 \(act)，估得很准 👍"
        }
        let dir = bias.deltaMinutes > 0 ? "低估" : "高估"
        let avg = abs(bias.averageDeltaMinutes)
        if avg >= 1 {
            return "记录了 \(bias.comparableCount) 件的实际用时：预估合计 \(est)，实际合计 \(act) · 平均每件\(dir)约 \(DurationLabel.short(avg))。"
        }
        let total = DurationLabel.short(abs(bias.deltaMinutes))
        return "记录了 \(bias.comparableCount) 件的实际用时：预估合计 \(est)，实际合计 \(act) · 合计\(dir)约 \(total)。"
    }
}
