import Foundation

/// One natural-week bucket, oldest-to-newest inside `LongTermTrends`.
public struct WeekBucket: Equatable, Identifiable {
    public var id: Date { weekStart }
    public let weekStart: Date
    public let weekEnd: Date
    public let completedCount: Int
    public let byQuadrant: [Quadrant: Int]
    public let bias: EstimateBias

    public init(weekStart: Date, weekEnd: Date, completedCount: Int,
                byQuadrant: [Quadrant: Int], bias: EstimateBias) {
        self.weekStart = weekStart
        self.weekEnd = weekEnd
        self.completedCount = completedCount
        self.byQuadrant = byQuadrant
        self.bias = bias
    }
}

/// A read-only mirror over recent history. The data stays in tasks/archive;
/// this type only aggregates it into small, explainable signals.
public struct LongTermTrends: Equatable {
    public let weeks: [WeekBucket]
    public let byQuadrant: [Quadrant: Int]
    public let bias: EstimateBias
    public let quadrantFourBacklog: Int

    public init(weeks: [WeekBucket], byQuadrant: [Quadrant: Int],
                bias: EstimateBias, quadrantFourBacklog: Int) {
        self.weeks = weeks
        self.byQuadrant = byQuadrant
        self.bias = bias
        self.quadrantFourBacklog = quadrantFourBacklog
    }

    public var hasHistory: Bool { weeks.reduce(0) { $0 + $1.completedCount } > 0 }
    public var completionSeries: [Int] { weeks.map(\.completedCount) }

    public var completionInsight: String {
        let nonEmpty = weeks.filter { $0.completedCount > 0 }
        guard nonEmpty.count >= 2 else { return "还在积累，多用几周这里会长出节奏。" }
        let split = max(1, weeks.count / 2)
        let earlier = weeks.prefix(split).map(\.completedCount)
        let later = weeks.suffix(weeks.count - split).map(\.completedCount)
        let a = Double(earlier.reduce(0, +)) / Double(max(1, earlier.count))
        let b = Double(later.reduce(0, +)) / Double(max(1, later.count))
        if b > a * 1.2 { return "最近几周完成节奏在上升，保持这个密度。" }
        if a > 0, b < a * 0.8 { return "最近几周节奏放缓了，今日计划可以再做减法。" }
        return "最近几周完成节奏比较稳定。"
    }

    public var focusInsight: String {
        let urgent = byQuadrant[.importantUrgent] ?? 0
        let focus = byQuadrant[.importantNotUrgent] ?? 0
        guard urgent + focus > 0 else { return "还没有足够的重点任务记录。" }
        if urgent > focus * 2 { return "完成更多集中在救火；试着提前给象限二留时间。" }
        if focus > urgent { return "重要不紧急的投入更多，深度工作正在站稳。" }
        return "救火与长期投入大致平衡。"
    }

    public var biasInsight: String {
        guard bias.hasData else { return "记录几次实际用时后，这里会帮你校准估时。" }
        if bias.deltaMinutes == 0 { return "最近的预估与实际基本一致。" }
        let direction = bias.deltaMinutes > 0 ? "低估" : "高估"
        let average = abs(bias.averageDeltaMinutes)
        let magnitude = average > 0
            ? "平均每件\(direction)约 \(DurationLabel.short(average))"
            : "合计\(direction)约 \(DurationLabel.short(abs(bias.deltaMinutes)))"
        return "最近 \(bias.comparableCount) 件可比任务，\(magnitude)。"
    }

    public var cleanupInsight: String {
        quadrantFourBacklog == 0
            ? "象限四目前很干净。"
            : "象限四还有 \(quadrantFourBacklog) 件未完成，适合定期删减。"
    }
}

extension Store {
    public func longTermTrends(now: Date = Date(), weekCount: Int = 8) -> LongTermTrends {
        let count = max(1, weekCount)
        let current = naturalWeek(containing: now).start
        let all = tasks + archive
        var buckets: [WeekBucket] = []

        for offset in stride(from: count - 1, through: 0, by: -1) {
            let start = calendar.date(byAdding: .day, value: -(offset * 7), to: current) ?? current
            let end = calendar.date(byAdding: .day, value: 7, to: start) ?? start.addingTimeInterval(604_800)
            let completed = all.filter { task in
                guard task.completed, let at = task.completedAt else { return false }
                return at >= start && at < end
            }
            var distribution: [Quadrant: Int] = [:]
            for task in completed { distribution[task.quadrant, default: 0] += 1 }
            buckets.append(WeekBucket(weekStart: start, weekEnd: end,
                                      completedCount: completed.count,
                                      byQuadrant: distribution,
                                      bias: Self.estimateBias(for: completed)))
        }

        var distribution: [Quadrant: Int] = [:]
        for bucket in buckets {
            for quadrant in Quadrant.allCases {
                distribution[quadrant, default: 0] += bucket.byQuadrant[quadrant] ?? 0
            }
        }
        let comparable = all.filter { task in
            guard task.completed, let at = task.completedAt,
                  task.estimatedMinutes != nil, task.actualMinutes != nil,
                  let first = buckets.first else { return false }
            return at >= first.weekStart && at < (buckets.last?.weekEnd ?? now)
        }
        let q4 = tasks.filter { $0.quadrant == .neither && !$0.completed }.count
        return LongTermTrends(weeks: buckets, byQuadrant: distribution,
                              bias: Self.estimateBias(for: comparable),
                              quadrantFourBacklog: q4)
    }
}
