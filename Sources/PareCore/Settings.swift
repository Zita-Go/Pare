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

    public init(planReminderEnabled: Bool = true,
                planHour: Int = 9,
                planMinute: Int = 0,
                reviewReminderEnabled: Bool = true,
                reviewHour: Int = 18,
                reviewMinute: Int = 0,
                inboxPileupEnabled: Bool = true,
                inboxPileupThreshold: Int = 8) {
        self.planReminderEnabled = planReminderEnabled
        self.planHour = planHour
        self.planMinute = planMinute
        self.reviewReminderEnabled = reviewReminderEnabled
        self.reviewHour = reviewHour
        self.reviewMinute = reviewMinute
        self.inboxPileupEnabled = inboxPileupEnabled
        self.inboxPileupThreshold = inboxPileupThreshold
    }

    public static let `default` = Settings()
}
