import Foundation

/// A review unit; grouping never changes the proposal or its individually selected operations.
public struct IntelligenceActionPlan: Identifiable {
    public var id: UUID { primary.id }
    public let primary: IntelligenceAction
    public let actions: [IntelligenceAction]
    public var actionIDs: Set<UUID> { Set(actions.map(\.id)) }

    public func selecting(_ selected: Bool, from selection: Set<UUID>) -> Set<UUID> {
        selected ? selection.union(actionIDs) : selection.subtracting(actionIDs)
    }
}

public enum IntelligencePresentation {
    public static func plans(for proposal: IntelligenceProposal, intent: IntelligenceIntent) -> [IntelligenceActionPlan] {
        let preferred: IntelligenceActionKind = intent == .idea ? .createLinkedTask : .updateTaskNextStep
        let primary = proposal.actions.first(where: { $0.kind == preferred }) ?? proposal.actions.first
        guard let primary else { return [] }
        let ordered = [primary] + proposal.actions.filter { $0.id != primary.id }
        guard intent == .today else {
            // Multiple new actions from one idea are different choices, not one bundle.
            return ordered.map { IntelligenceActionPlan(primary: $0, actions: [$0]) }
        }
        var seen = Set<UUID>()
        var plans: [IntelligenceActionPlan] = []
        for action in ordered where !seen.contains(action.id) {
            let group = isSafePlanChange(action)
                ? ordered.filter { $0.sourceID == action.sourceID && isSafePlanChange($0) }
                : [action]
            let startingAction = group.first(where: { $0.kind == .updateTaskNextStep }) ?? action
            let actions = [startingAction] + group.filter { $0.id != startingAction.id }
            plans.append(IntelligenceActionPlan(primary: startingAction, actions: actions))
            seen.formUnion(actions.map(\.id))
        }
        return plans
    }

    public static func defaultSelection(for proposal: IntelligenceProposal, intent: IntelligenceIntent) -> Set<UUID> {
        guard let plan = plans(for: proposal, intent: intent).first else { return [] }
        let primary = plan.primary
        if intent == .today {
            return isSafePlanChange(primary) ? plan.actionIDs : []
        }
        return primary.kind == .setTodaySelection && !primary.selected ? [] : [primary.id]
    }

    private static func isSafePlanChange(_ action: IntelligenceAction) -> Bool {
        action.kind == .updateTaskNextStep
            || (action.kind == .setTodaySelection && action.selected)
            || (action.kind == .setDailyWin && action.selected)
    }
}
