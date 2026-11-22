import XCTest
@testable import PareCore

final class IntelligencePresentationTests: XCTestCase {
    func testTodayCombinesThePrimaryTasksSafeChangesAndLeavesOtherTasksUnselected() throws {
        let primaryTask = UUID(), otherTask = UUID()
        let nextStep = IntelligenceAction(kind: .updateTaskNextStep, sourceID: primaryTask, nextAction: "Write one line")
        let today = IntelligenceAction(kind: .setTodaySelection, sourceID: primaryTask)
        let win = IntelligenceAction(kind: .setDailyWin, sourceID: primaryTask)
        let otherToday = IntelligenceAction(kind: .setTodaySelection, sourceID: otherTask)
        let otherNextStep = IntelligenceAction(kind: .updateTaskNextStep, sourceID: otherTask, nextAction: "Read one page")
        let proposal = proposal([otherToday, today, win, nextStep, otherNextStep])

        let plans = IntelligencePresentation.plans(for: proposal, intent: .today)
        XCTAssertEqual(plans.count, 2)
        let primary = try XCTUnwrap(plans.first)
        XCTAssertEqual(primary.primary.id, nextStep.id)
        XCTAssertEqual(primary.actionIDs, [nextStep.id, today.id, win.id])
        let other = try XCTUnwrap(plans.first { $0.primary.sourceID == otherTask })
        XCTAssertEqual(other.actionIDs, [otherToday.id, otherNextStep.id])
        XCTAssertEqual(IntelligencePresentation.defaultSelection(for: proposal, intent: .today),
                       [nextStep.id, today.id, win.id])
    }

    func testDeferralForThePrimaryTaskIsSeparateAndRequiresExplicitSelection() throws {
        let task = UUID()
        let deferTask = IntelligenceAction(kind: .setTodaySelection, sourceID: task, selected: false)
        let nextStep = IntelligenceAction(kind: .updateTaskNextStep, sourceID: task, nextAction: "Name one blocker")
        let proposal = proposal([deferTask, nextStep])

        let plans = IntelligencePresentation.plans(for: proposal, intent: .today)
        XCTAssertEqual(plans.count, 2)
        let primary = try XCTUnwrap(plans.first)
        XCTAssertEqual(primary.actionIDs, [nextStep.id])
        XCTAssertEqual(IntelligencePresentation.defaultSelection(for: proposal, intent: .today), [nextStep.id])
        let deferral = try XCTUnwrap(plans.first { $0.id == deferTask.id })
        XCTAssertEqual(deferral.actionIDs, [deferTask.id])
        XCTAssertEqual(deferral.selecting(true, from: [nextStep.id]), [nextStep.id, deferTask.id])
    }

    func testAProposalStartingWithOnlyDeferralsDoesNotSelectAnyByDefault() {
        let first = IntelligenceAction(kind: .setTodaySelection, sourceID: UUID(), selected: false)
        let second = IntelligenceAction(kind: .setTodaySelection, sourceID: UUID(), selected: false)
        let proposal = proposal([first, second])

        XCTAssertEqual(IntelligencePresentation.plans(for: proposal, intent: .today).map(\.actionIDs),
                       [[first.id], [second.id]])
        XCTAssertTrue(IntelligencePresentation.defaultSelection(for: proposal, intent: .today).isEmpty)
    }

    func testIdeaTasksFromTheSameSourceRemainIndependentPlans() throws {
        let idea = UUID()
        let question = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea, text: "Who needs this?")
        let first = IntelligenceAction(kind: .createLinkedTask, sourceID: idea, title: "Ask one person")
        let second = IntelligenceAction(kind: .createLinkedTask, sourceID: idea, title: "Find one example")
        let third = IntelligenceAction(kind: .createLinkedTask, sourceID: idea, title: "Write a hypothesis")
        let proposal = proposal([question, first, second, third])

        let plans = IntelligencePresentation.plans(for: proposal, intent: .idea)
        XCTAssertEqual(plans.count, 4)
        XCTAssertEqual(plans.first?.primary.id, first.id)
        XCTAssertEqual(Set(plans.map(\.actionIDs)), [[question.id], [first.id], [second.id], [third.id]])
        XCTAssertEqual(IntelligencePresentation.defaultSelection(for: proposal, intent: .idea), [first.id])
        let secondPlan = try XCTUnwrap(plans.first { $0.id == second.id })
        XCTAssertEqual(secondPlan.selecting(true, from: [first.id]), [first.id, second.id])
    }

    func testGroupSelectionRestoresCancelledDetailsAndPreservesExplicitOtherSelections() throws {
        let task = UUID()
        let nextStep = IntelligenceAction(kind: .updateTaskNextStep, sourceID: task)
        let today = IntelligenceAction(kind: .setTodaySelection, sourceID: task)
        let win = IntelligenceAction(kind: .setDailyWin, sourceID: task)
        let otherTask = IntelligenceAction(kind: .setTodaySelection, sourceID: UUID())
        let deferral = IntelligenceAction(kind: .setTodaySelection, sourceID: UUID(), selected: false)
        let plans = IntelligencePresentation.plans(for: proposal([nextStep, today, win, otherTask, deferral]), intent: .today)
        let primary = try XCTUnwrap(plans.first)
        // The user individually cancelled the next-step and focus changes, while
        // explicitly selecting another task and a deferral.
        let partial: Set<UUID> = [today.id, otherTask.id, deferral.id]

        XCTAssertEqual(primary.selecting(true, from: partial),
                       [nextStep.id, today.id, win.id, otherTask.id, deferral.id])
        XCTAssertEqual(primary.selecting(false, from: partial), [otherTask.id, deferral.id])
    }

    func testClarificationHasNoPlansOrSelectedOperations() {
        let proposal = IntelligenceProposal(requestID: UUID(), summary: "Need one detail", clarification: "What is blocking you?", actions: [])
        for intent in IntelligenceIntent.allCases {
            XCTAssertTrue(IntelligencePresentation.plans(for: proposal, intent: intent).isEmpty)
            XCTAssertTrue(IntelligencePresentation.defaultSelection(for: proposal, intent: intent).isEmpty)
        }
    }

    private func proposal(_ actions: [IntelligenceAction]) -> IntelligenceProposal {
        IntelligenceProposal(requestID: UUID(), summary: "A small next step", actions: actions)
    }
}
