import Foundation

/// User preferences for reminders, persisted inside the same store file.
///
/// Defaults match the "更主动" profile the user picked: a morning planning
/// nudge at 09:00, an evening wrap-up at 18:00, and an inbox-pileup mention
/// once unprocessed captures pile up. Everything is tunable in the settings UI.
public struct Settings: Codable, Equatable {
    /// Morning "整理一下今天" nudge.
    public var planReminderEnabled: Bool
    public var planHour: Int
    public var planMinute: Int

    /// Evening "收尾" review — summarises what's left / what got done today.
    public var reviewReminderEnabled: Bool
    public var reviewHour: Int
    public var reviewMinute: Int

    /// When enabled, the daily reminders mention that the inbox has piled up
    /// once it reaches `inboxPileupThreshold` unprocessed items.
    public var inboxPileupEnabled: Bool
    public var inboxPileupThreshold: Int

    /// Weekly review — a Sunday-evening look back over the natural week
    /// (Monday–Sunday). `weeklyReviewWeekday` uses `Calendar`'s convention
    /// (1 = Sunday … 7 = Saturday), so the default 1 is Sunday.
    public var weeklyReviewEnabled: Bool
    public var weeklyReviewWeekday: Int
    public var weeklyReviewHour: Int
    public var weeklyReviewMinute: Int

    /// Quadrant-II ("重要 · 不紧急") staleness watch. When enabled, tasks in
    /// that quadrant that have not been progressed for at least
    /// `focusStalenessDays` days surface a gentle sentence in the morning nudge.
    public var focusStalenessEnabled: Bool
    public var focusStalenessDays: Int

    /// Today overload watch. A soft (non-blocking) hint appears when today's
    /// focus exceeds `todayMaxCount` items or `todayCapacityMinutes` of
    /// estimated effort.
    public var todayOverloadEnabled: Bool
    public var todayMaxCount: Int
    public var todayCapacityMinutes: Int

    /// Quadrant-IV ("不重要 · 不紧急") cleanup nudge. When enabled, a gentle
    /// cleanup guidance appears in that quadrant once its incomplete items pile
    /// up to `quadrantCleanupThreshold` — the box whose action is "删掉".
    public var quadrantCleanupEnabled: Bool
    public var quadrantCleanupThreshold: Int

    public init(planReminderEnabled: Bool = true,
                planHour: Int = 9,
                planMinute: Int = 0,
                reviewReminderEnabled: Bool = true,
                reviewHour: Int = 18,
                reviewMinute: Int = 0,
                inboxPileupEnabled: Bool = true,
                inboxPileupThreshold: Int = 8,
                weeklyReviewEnabled: Bool = true,
                weeklyReviewWeekday: Int = 1,
                weeklyReviewHour: Int = 20,
                weeklyReviewMinute: Int = 0,
                focusStalenessEnabled: Bool = true,
                focusStalenessDays: Int = 3,
                todayOverloadEnabled: Bool = true,
                todayMaxCount: Int = 5,
                todayCapacityMinutes: Int = 240,
                quadrantCleanupEnabled: Bool = true,
                quadrantCleanupThreshold: Int = 5) {
        self.planReminderEnabled = planReminderEnabled
        self.planHour = planHour
        self.planMinute = planMinute
        self.reviewReminderEnabled = reviewReminderEnabled
        self.reviewHour = reviewHour
        self.reviewMinute = reviewMinute
        self.inboxPileupEnabled = inboxPileupEnabled
        self.inboxPileupThreshold = inboxPileupThreshold
        self.weeklyReviewEnabled = weeklyReviewEnabled
        self.weeklyReviewWeekday = weeklyReviewWeekday
        self.weeklyReviewHour = weeklyReviewHour
        self.weeklyReviewMinute = weeklyReviewMinute
        self.focusStalenessEnabled = focusStalenessEnabled
        self.focusStalenessDays = focusStalenessDays
        self.todayOverloadEnabled = todayOverloadEnabled
        self.todayMaxCount = todayMaxCount
        self.todayCapacityMinutes = todayCapacityMinutes
        self.quadrantCleanupEnabled = quadrantCleanupEnabled
        self.quadrantCleanupThreshold = quadrantCleanupThreshold
    }

    public static let `default` = Settings()

    // MARK: - Migration-safe decoding

    /// Decoding tolerates missing keys by falling back to the defaults above.
    ///
    /// This matters for backward compatibility: an existing `store.json` holds
    /// a `settings` object with only the v1 keys. Synthesised decoding would
    /// throw on the new (non-optional) keys, which — because `Snapshot.settings`
    /// is decoded with `decodeIfPresent` — would fail the *whole* snapshot
    /// decode and silently drop the user's inbox/tasks/archive. Falling back to
    /// defaults keeps old files loading cleanly. `encode(to:)` stays synthesised.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Settings.default
        self.init(
            planReminderEnabled: try c.decodeIfPresent(Bool.self, forKey: .planReminderEnabled) ?? d.planReminderEnabled,
            planHour: try c.decodeIfPresent(Int.self, forKey: .planHour) ?? d.planHour,
            planMinute: try c.decodeIfPresent(Int.self, forKey: .planMinute) ?? d.planMinute,
            reviewReminderEnabled: try c.decodeIfPresent(Bool.self, forKey: .reviewReminderEnabled) ?? d.reviewReminderEnabled,
            reviewHour: try c.decodeIfPresent(Int.self, forKey: .reviewHour) ?? d.reviewHour,
            reviewMinute: try c.decodeIfPresent(Int.self, forKey: .reviewMinute) ?? d.reviewMinute,
            inboxPileupEnabled: try c.decodeIfPresent(Bool.self, forKey: .inboxPileupEnabled) ?? d.inboxPileupEnabled,
            inboxPileupThreshold: try c.decodeIfPresent(Int.self, forKey: .inboxPileupThreshold) ?? d.inboxPileupThreshold,
            weeklyReviewEnabled: try c.decodeIfPresent(Bool.self, forKey: .weeklyReviewEnabled) ?? d.weeklyReviewEnabled,
            weeklyReviewWeekday: try c.decodeIfPresent(Int.self, forKey: .weeklyReviewWeekday) ?? d.weeklyReviewWeekday,
            weeklyReviewHour: try c.decodeIfPresent(Int.self, forKey: .weeklyReviewHour) ?? d.weeklyReviewHour,
            weeklyReviewMinute: try c.decodeIfPresent(Int.self, forKey: .weeklyReviewMinute) ?? d.weeklyReviewMinute,
            focusStalenessEnabled: try c.decodeIfPresent(Bool.self, forKey: .focusStalenessEnabled) ?? d.focusStalenessEnabled,
            focusStalenessDays: try c.decodeIfPresent(Int.self, forKey: .focusStalenessDays) ?? d.focusStalenessDays,
            todayOverloadEnabled: try c.decodeIfPresent(Bool.self, forKey: .todayOverloadEnabled) ?? d.todayOverloadEnabled,
            todayMaxCount: try c.decodeIfPresent(Int.self, forKey: .todayMaxCount) ?? d.todayMaxCount,
            todayCapacityMinutes: try c.decodeIfPresent(Int.self, forKey: .todayCapacityMinutes) ?? d.todayCapacityMinutes,
            quadrantCleanupEnabled: try c.decodeIfPresent(Bool.self, forKey: .quadrantCleanupEnabled) ?? d.quadrantCleanupEnabled,
            quadrantCleanupThreshold: try c.decodeIfPresent(Int.self, forKey: .quadrantCleanupThreshold) ?? d.quadrantCleanupThreshold)
    }
}
