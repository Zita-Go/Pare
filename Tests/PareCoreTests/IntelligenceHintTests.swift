import XCTest
@testable import PareCore

final class IntelligenceHintTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-10-29T10:00:00Z")!
    private var calendar: Calendar {
        var value = Calendar(identifier: .iso8601)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func hints(ideas: [ThoughtItem] = [], tasks: [TaskItem] = [],
                       archive: [TaskItem] = [], activeTimer: ActiveTimer? = nil,
                       parallelRuns: [ParallelTaskRun] = [], includeToday: Bool = false,
                       at: Date? = nil) -> [IntelligenceHint] {
        IntelligenceHintEngine.candidates(thoughts: ideas, tasks: tasks, archive: archive,
            activeTimer: activeTimer, parallelRuns: parallelRuns,
            includeToday: includeToday, now: at ?? now, calendar: calendar)
    }

    func testUserReviewDateTakesPriorityOverCompletedActionAndRepeatedBlocker() throws {
        let task = TaskItem(text: "Action", quadrant: .importantNotUrgent, completed: true,
                            completedAt: now.addingTimeInterval(-60))
        let completedIdea = ThoughtItem(text: "Results", updatedAt: now.addingTimeInterval(-600), linkedTaskIDs: [task.id])
        let review = ThoughtItem(text: "Review", reviewAt: now.addingTimeInterval(-60))
        let blocked = TaskItem(text: "Blocked", quadrant: .importantNotUrgent, blockerEntries: [
            .init(reason: .unclear, at: now.addingTimeInterval(-120)), .init(reason: .unclear, at: now)])
        let candidates = hints(ideas: [completedIdea, review], tasks: [task, blocked], includeToday: true)
        XCTAssertEqual(candidates.map(\.reason), [.ideaReview, .actionResult, .repeatedBlocker, .todayFocus])
        XCTAssertEqual(candidates.first?.subjectID, review.id)
    }

    func testDeletedOrArchivedIdeasAndArchivedBlockersCannotBeSuggested() {
        let archived = ThoughtItem(text: "Archived", reviewAt: now, stage: .archived)
        let blocked = TaskItem(text: "Archived task", quadrant: .importantNotUrgent, blockerEntries: [
            .init(reason: .unclear, at: now), .init(reason: .unclear, at: now)])
        XCTAssertTrue(hints(ideas: [archived], archive: [blocked]).isEmpty)
        XCTAssertTrue(hints().isEmpty)
    }

    func testFutureReviewAndMissingLinkedTaskDoNotTrigger() {
        let idea = ThoughtItem(text: "Later", reviewAt: now.addingTimeInterval(60), linkedTaskIDs: [UUID()])
        XCTAssertTrue(hints(ideas: [idea]).isEmpty)
    }

    func testParkedIdeaWithoutReviewDoesNotPromptForCompletedAction() {
        let task = TaskItem(text: "Completed action", quadrant: .importantNotUrgent,
                            completed: true, completedAt: now.addingTimeInterval(-60))
        let idea = ThoughtItem(text: "Set aside", updatedAt: now.addingTimeInterval(-600),
                               stage: .parked, linkedTaskIDs: [task.id])
        XCTAssertTrue(hints(ideas: [idea], tasks: [task]).isEmpty)
    }

    func testParkedIdeaWithDueReviewOnlyPromptsForExplicitReview() {
        let task = TaskItem(text: "Completed action", quadrant: .importantNotUrgent,
                            completed: true, completedAt: now.addingTimeInterval(-60))
        let idea = ThoughtItem(text: "Set aside", updatedAt: now.addingTimeInterval(-600),
                               reviewAt: now, stage: .parked, linkedTaskIDs: [task.id])
        let candidates = hints(ideas: [idea], tasks: [task])
        XCTAssertEqual(candidates.map(\.reason), [.ideaReview])
        XCTAssertEqual(candidates.first?.subjectID, idea.id)
    }

    func testNewExplorationRecordAcknowledgesCompletedAction() {
        let task = TaskItem(text: "Action", quadrant: .importantNotUrgent, completed: true, completedAt: now.addingTimeInterval(-60))
        let idea = ThoughtItem(text: "Explore", updatedAt: now.addingTimeInterval(-600),
                               entries: [.init(text: "Learned", createdAt: now)], linkedTaskIDs: [task.id])
        XCTAssertTrue(hints(ideas: [idea], tasks: [task]).isEmpty)
    }

    func testCompletedLinkedArchiveCanInformAnActiveIdea() {
        let task = TaskItem(text: "Result", quadrant: .importantNotUrgent, completed: true, completedAt: now.addingTimeInterval(-60))
        let idea = ThoughtItem(text: "Explore", updatedAt: now.addingTimeInterval(-600), linkedTaskIDs: [task.id])
        XCTAssertEqual(hints(ideas: [idea], archive: [task]).first?.reason, .actionResult)
    }

    func testNextQuestionChangeAcknowledgesResultsButUnrelatedEditDoesNot() {
        let task = TaskItem(text: "Result", quadrant: .importantNotUrgent, completed: true, completedAt: now.addingTimeInterval(-60))
        var idea = ThoughtItem(text: "Renamed", updatedAt: now, linkedTaskIDs: [task.id], nextQuestion: "Next?")
        idea.nextQuestionUpdatedAt = now.addingTimeInterval(-120)
        XCTAssertEqual(hints(ideas: [idea], tasks: [task]).first?.reason, .actionResult)
        idea.nextQuestionUpdatedAt = now
        XCTAssertTrue(hints(ideas: [idea], tasks: [task]).isEmpty)
    }

    func testActivePersonalFocusHidesAllHints() {
        let idea = ThoughtItem(text: "Review", reviewAt: now)
        XCTAssertTrue(hints(ideas: [idea], activeTimer: .init(taskID: UUID(), startedAt: now), includeToday: true).isEmpty)
    }

    func testRepeatedExplicitSameBlockerRequiredAndRunningActionExcluded() {
        var task = TaskItem(text: "Task", quadrant: .importantNotUrgent, blockerEntries: [
            .init(reason: .unclear, at: now.addingTimeInterval(-120)), .init(reason: .tooLarge, at: now)])
        XCTAssertTrue(hints(tasks: [task]).isEmpty)
        task.blockerEntries?.append(.init(reason: .unclear, at: now))
        XCTAssertEqual(hints(tasks: [task]).first?.reason, .repeatedBlocker)
        XCTAssertTrue(hints(tasks: [task], parallelRuns: [.init(taskID: task.id, startedAt: now)]).isEmpty)
        task.progressSessions = [.init(startedAt: now, endedAt: now.addingTimeInterval(60), minutes: 1)]
        XCTAssertTrue(hints(tasks: [task], at: now.addingTimeInterval(120)).isEmpty)
    }

    func testStaleBlockersAndUnrecordedWorkNeverImplyNewBlocker() {
        let stale = now.addingTimeInterval(-8 * 86400)
        let task = TaskItem(text: "Task", quadrant: .importantNotUrgent, blockerEntries: [
            .init(reason: .lowEnergy, at: stale), .init(reason: .lowEnergy, at: stale)])
        XCTAssertTrue(hints(tasks: [task]).isEmpty)
        XCTAssertTrue(hints(tasks: [.init(text: "No records", quadrant: .importantUrgent)]).isEmpty)
    }

    func testCompletedDailyWinStillCountsAsChosenFocus() {
        let task = TaskItem(text: "Chosen", quadrant: .importantUrgent, isToday: true,
                            completed: true, completedAt: now, isDailyWin: true)
        XCTAssertTrue(hints(tasks: [task], includeToday: true).isEmpty)
        XCTAssertEqual(hints(includeToday: true).first?.reason, .todayFocus)
    }

    func testDisplayDedupExpiresAtExactlyTwentyFourHoursAndIsEntitySpecific() throws {
        let first = try XCTUnwrap(hints(ideas: [.init(text: "One", reviewAt: now)]).first)
        let second = try XCTUnwrap(hints(ideas: [.init(text: "Two", reviewAt: now)]).first)
        var history = IntelligenceHintHistory()
        history.recordDisplay(first, now: now)
        XCTAssertFalse(history.allows(first, now: now.addingTimeInterval(86399)))
        XCTAssertTrue(history.allows(first, now: now.addingTimeInterval(86400)))
        XCTAssertTrue(history.allows(second, now: now))
        let data = try JSONEncoder().encode(history)
        XCTAssertFalse(try JSONDecoder().decode(IntelligenceHintHistory.self, from: data).allows(first, now: now))
    }

    func testPresentedCardRemainsUsableAfterDisplayRecordedButVanishesWhenSourceIsGone() throws {
        let hint = try XCTUnwrap(hints(ideas: [.init(text: "Review", reviewAt: now)]).first)
        var history = IntelligenceHintHistory()
        history.recordDisplay(hint, now: now)
        XCTAssertEqual(IntelligenceHintEngine.selection(current: hint, candidates: [hint], history: history, now: now), hint)
        XCTAssertNil(IntelligenceHintEngine.selection(current: nil, candidates: [hint], history: history, now: now))
        XCTAssertNil(IntelligenceHintEngine.selection(current: hint, candidates: [], history: history, now: now))
    }

    func testExplicitSnoozeReturnsAfterAnHourDespiteDisplayCooldown() throws {
        let hint = try XCTUnwrap(hints(includeToday: true).first)
        var history = IntelligenceHintHistory()
        history.recordDisplay(hint, now: now)
        history.snooze(hint, until: now.addingTimeInterval(3600))
        XCTAssertFalse(history.allows(hint, now: now.addingTimeInterval(3599)))
        XCTAssertTrue(history.allows(hint, now: now.addingTimeInterval(3600)))
        history.recordDisplay(hint, now: now.addingTimeInterval(3600))
        XCTAssertFalse(history.allows(hint, now: now.addingTimeInterval(3601)))
    }

    func testMuteUntilNextCalendarWeekDoesNotLeakIntoMonday() throws {
        let sunday = ISO8601DateFormatter().date(from: "2026-11-01T23:30:00Z")!
        let monday = ISO8601DateFormatter().date(from: "2026-11-02T00:00:00Z")!
        let hint = try XCTUnwrap(hints(includeToday: true).first)
        var history = IntelligenceHintHistory()
        history.recordDisplay(hint, now: sunday)
        history.snoozeForWeek(hint, now: sunday, calendar: calendar)
        XCTAssertFalse(history.allows(hint, now: monday.addingTimeInterval(-1)))
        XCTAssertTrue(history.allows(hint, now: monday))
    }
}
