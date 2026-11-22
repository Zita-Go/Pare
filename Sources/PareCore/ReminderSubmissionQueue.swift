import Foundation

/// Serializes asynchronous reminder batches and coalesces waiting plans.
/// All entry points are used on the main queue; submission callbacks may use any queue.
public final class ReminderSubmissionQueue {
    public typealias Completion = (Result<Void, Error>) -> Void
    public typealias Submission = (@escaping Completion) -> Void
    public enum FlushError: LocalizedError {
        case timedOut
        public var errorDescription: String? { "系统尚未确认提醒已提交，等待已超时。" }
    }

    private struct Waiter {
        let completion: Completion
        let timeout: DispatchWorkItem
    }
    private var pending: Submission?
    private var activeID: UUID?
    private var waiters: [UUID: Waiter] = [:]

    public init() {}
    public func enqueue(_ submission: @escaping Submission) {
        dispatchPrecondition(condition: .onQueue(.main))
        pending = submission
        startNextIfNeeded()
    }

    public func flush(_ submission: @escaping Submission, timeout: TimeInterval,
                      completion: @escaping Completion) {
        dispatchPrecondition(condition: .onQueue(.main))
        let id = UUID()
        let deadline = DispatchWorkItem { [weak self] in
            guard let waiter = self?.waiters.removeValue(forKey: id) else { return }
            // Stop waiting, but retain the in-flight slot: its late callback
            // must finish before a newer batch can touch system notifications.
            waiter.completion(.failure(FlushError.timedOut))
        }
        waiters[id] = Waiter(completion: completion, timeout: deadline)
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, timeout), execute: deadline)
        enqueue(submission)
    }

    private func startNextIfNeeded() {
        guard activeID == nil, let submission = pending else { return }
        pending = nil
        let id = UUID()
        activeID = id
        submission { [weak self] result in
            // Even synchronous submissions complete on a later turn, allowing
            // NSApplication to return terminateLater before it receives a reply.
            DispatchQueue.main.async {
                guard let self, self.activeID == id else { return }
                self.activeID = nil
                if self.pending != nil {
                    self.startNextIfNeeded()
                } else {
                    let finished = self.waiters.values
                    self.waiters.removeAll()
                    for waiter in finished {
                        waiter.timeout.cancel()
                        waiter.completion(result)
                    }
                }
            }
        }
    }
}
