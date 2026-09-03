import Foundation

/// A concrete reminder the app should schedule.
///
/// This is pure data computed by the planner below, so the *what / when / text*
/// of every reminder is unit-testable without any UserNotifications plumbing.
/// The app layer only has to translate these into `UNNotificationRequest`s.
public struct PlannedReminder: Equatable {
    public enum Kind: Equatable {
        case plan          // morning "整理一下今天"
        case review        // evening "收尾"
        case weeklyReview  // Sunday-evening "本周回顾"
        case task(UUID)    // a single task's one-shot reminder
    }

    public let kind: Kind
    public let identifier: String
    public let fireDate: Date
    public let title: String
    public let body: String
    public let repeatRule: RecurrenceRule?

    public init(kind: Kind, identifier: String, fireDate: Date, title: String, body: String,
                repeatRule: RecurrenceRule? = nil) {
        self.kind = kind
        self.identifier = identifier
        self.fireDate = fireDate
        self.title = title
        self.body = body
        self.repeatRule = repeatRule
    }

    /// The task this reminder belongs to, if any.
    public var taskID: UUID? {
        if case let .task(id) = kind { return id }
        return nil
    }

    /// Whether a "完成" action makes sense (task reminders only).
    public var isTaskActionable: Bool { taskID != nil }
}

extension Store {

    /// The next wall-clock occurrence of `hour:minute` strictly after `now`.
    public func nextOccurrence(hour: Int, minute: Int, after now: Date) -> Date {
        var comps = calendar.dateComponents([.year, .month, .day], from: now)
        comps.hour = hour
        comps.minute = minute
        comps.second = 0
        let candidate = calendar.date(from: comps) ?? now
        if candidate > now { return candidate }
        return calendar.date(byAdding: .day, value: 1, to: candidate) ?? candidate.addingTimeInterval(86_400)
    }

    /// The next `weekday`/`hour`/`minute` strictly after `now`. `weekday` uses
    /// `Calendar`'s convention (1 = Sunday … 7 = Saturday).
    public func nextWeeklyOccurrence(weekday: Int, hour: Int, minute: Int, after now: Date) -> Date {
        var comps = DateComponents()
        comps.weekday = weekday
        comps.hour = hour
        comps.minute = minute
        comps.second = 0
        return calendar.nextDate(after: now, matching: comps, matchingPolicy: .nextTime) ?? now
    }

    /// Everything that should currently be scheduled, given `settings` + tasks.
    ///
    /// The app layer calls this whenever data changes; because the content is
    /// recomputed from `now` each time, the daily reminders always reflect the
    /// latest counts (the app reschedules while it's running).
    public func plannedReminders(now: Date = Date()) -> [PlannedReminder] {
        var result: [PlannedReminder] = []
        let inbox = inboxCount
        let pileup = settings.inboxPileupEnabled && inbox >= settings.inboxPileupThreshold

        // Morning: plan the day.
        if settings.planReminderEnabled {
            var body: String
            if pileup {
                body = "待整理已堆了 \(inbox) 条 · 花几分钟清一清，挑出今天要做的几件事。"
            } else {
                body = "花几分钟把想法归位，挑出今天要做的几件事。"
            }
            // Gently mention Quadrant-II items that have stalled — no new
            // notification, just an extra sentence on the existing nudge.
            let stalled = stalledFocusTasks(now: now)
            if !stalled.isEmpty {
                body += "（象限二有 \(stalled.count) 件 ≥\(settings.focusStalenessDays) 天没推进）"
            }
            let thoughts = thoughtsDueForReview(now: now)
            if !thoughts.isEmpty {
                body += "（有 \(thoughts.count) 个想法到了回看时间）"
            }
            result.append(PlannedReminder(
                kind: .plan,
                identifier: "daily.plan",
                fireDate: nextOccurrence(hour: settings.planHour, minute: settings.planMinute, after: now),
                title: "整理一下今天",
                body: body))
        }

        // Evening: wrap up. Reflects today's remaining / done counts.
        if settings.reviewReminderEnabled {
            let remaining = todayRemaining
            let remainingMinutes = todayRemainingEstimatedMinutes
            let doneToday = completedTodayCount(now: now)
            var title = "今天收个尾"
            var body: String
            if remaining > 0 {
                body = "今天还有 \(remaining) 件没做"
                if remainingMinutes > 0 { body += " · 约 " + DurationLabel.short(remainingMinutes) }
                body += "，看看要不要收个尾。"
            } else if doneToday > 0 {
                title = "今天辛苦了"
                body = "完成了 \(doneToday) 件，收工 🎉"
            } else {
                body = "今天先这样，明天见。"
            }
            if pileup { body += "（待整理 \(inbox) 条）" }
            result.append(PlannedReminder(
                kind: .review,
                identifier: "daily.review",
                fireDate: nextOccurrence(hour: settings.reviewHour, minute: settings.reviewMinute, after: now),
                title: title,
                body: body))
        }

        // Weekly: a Sunday-evening look back over the whole week. The body stays
        // generic (no counts) because it fires days from now — the numbers live
        // on the review panel itself.
        if settings.weeklyReviewEnabled {
            result.append(PlannedReminder(
                kind: .weeklyReview,
                identifier: "weekly.review",
                fireDate: nextWeeklyOccurrence(weekday: settings.weeklyReviewWeekday,
                                               hour: settings.weeklyReviewHour,
                                               minute: settings.weeklyReviewMinute,
                                               after: now),
                title: "本周回顾",
                body: "点开看看这周做成了哪些事，几分钟收束一整周。"))
        }

        // Per-task reminders. A recurring reminder is materialised as the next
        // four one-shot requests so its chosen start date is honoured.
        for task in tasks where !task.completed {
            guard let at = task.remindAt else { continue }
            if let rule = task.recurrence {
                for (index, fireDate) in recurringOccurrences(start: at, rule: rule,
                                                               after: now, count: 4).enumerated() {
                    result.append(PlannedReminder(
                        kind: .task(task.id),
                        identifier: "task.\(task.id.uuidString).repeat.\(index)",
                        fireDate: fireDate,
                        title: "该做这件事了",
                        body: task.text,
                        repeatRule: rule))
                }
            } else if at > now {
                result.append(PlannedReminder(
                    kind: .task(task.id),
                    identifier: "task.\(task.id.uuidString)",
                    fireDate: at,
                    title: "该做这件事了",
                    body: task.text))
            }
        }

        return result
    }

    private func recurringOccurrences(start: Date, rule: RecurrenceRule, after now: Date,
                                      count: Int) -> [Date] {
        var candidate = start
        var safety = 0
        while candidate <= now, safety < 1_000 {
            candidate = nextRecurringDate(after: candidate, rule: rule)
            safety += 1
        }
        var result: [Date] = []
        while result.count < count {
            result.append(candidate)
            candidate = nextRecurringDate(after: candidate, rule: rule)
        }
        return result
    }

    private func nextRecurringDate(after date: Date, rule: RecurrenceRule) -> Date {
        let component: Calendar.Component
        switch rule {
        case .daily: component = .day
        case .weekly: component = .weekOfYear
        case .monthly: component = .month
        }
        return calendar.date(byAdding: component, value: 1, to: date)
            ?? date.addingTimeInterval(86_400)
    }
}
