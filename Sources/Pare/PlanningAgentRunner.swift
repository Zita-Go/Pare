import Foundation
import PareCore

/// A bounded workflow agent: Pare owns the local read tools and run state;
/// the model produces one validated proposal. No shell, autonomous writes,
/// unattended network calls, or provider-specific agent runtime is involved.
enum PlanningAgentRunner {
    @MainActor
    static func generate(_ request: AIRequest, runID: UUID, store: Store,
                         service: AIService) async throws -> (AIDraft, UUID?) {
        do {
            store.updatePlanningRun(runID, phase: .thinking)
            let draft = try await service.generate(request)
            try Task.checkCancellation()
            let summaryID = try store.recordAISummary(draft, request: request)
            store.updatePlanningRun(runID, phase: draft.question.isEmpty ? .awaitingReview : .needsClarification,
                                     summaryID: summaryID)
            return (draft, summaryID)
        } catch {
            let code: String
            if let rejected = error as? AIRejectedDraft { code = rejected.issue.code }
            else if let issue = error as? AIResponseIssue { code = issue.code }
            else if let urlError = error as? URLError { code = "network_\(urlError.errorCode)" }
            else if case AIError.http(let status) = error { code = "http_\(status)" }
            else if case AIError.truncated = error { code = "truncated" }
            else { code = "request_failed" }
            store.updatePlanningRun(runID, phase: Task.isCancelled ? .cancelled : .failed, diagnosticCode: code)
            throw error
        }
    }
}
