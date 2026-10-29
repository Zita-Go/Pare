import XCTest
@testable import PareCore

final class ReminderSubmissionQueueTests: XCTestCase {
    private typealias Finish = (Result<Void, Error>) -> Void

    func testSubmissionsNeverOverlapAndPendingPlansCoalesceToNewest() {
        let queue = ReminderSubmissionQueue()
        var starts: [Int] = []
        var finishFirst: Finish!
        let finished = expectation(description: "latest plan submitted")
        queue.enqueue { finish in starts.append(1); finishFirst = finish }
        queue.enqueue { finish in starts.append(2); finish(.success(())) }
        queue.flush({ finish in starts.append(3); finish(.success(())) }, timeout: 1) { result in
            if case .failure(let error) = result { XCTFail("Unexpected failure: \(error)") }
            finished.fulfill()
        }
        XCTAssertEqual(starts, [1])
        finishFirst(.success(()))
        wait(for: [finished], timeout: 1)
        XCTAssertEqual(starts, [1, 3])
    }

    func testFlushWaitsUntilTheLatestSubmissionActuallyCompletes() {
        let queue = ReminderSubmissionQueue()
        var finish: Finish!
        var reported = false
        let finished = expectation(description: "submitted")
        queue.flush({ finish = $0 }, timeout: 1) { _ in reported = true; finished.fulfill() }
        XCTAssertFalse(reported)
        finish(.success(()))
        wait(for: [finished], timeout: 1)
        XCTAssertTrue(reported)
    }

    func testFlushPropagatesSubmissionFailure() {
        let queue = ReminderSubmissionQueue()
        let finished = expectation(description: "failure")
        queue.flush({ $0(.failure(NSError(domain: "submission", code: 42))) }, timeout: 1) { result in
            guard case .failure(let error) = result else { return XCTFail("Expected submission failure") }
            XCTAssertEqual((error as NSError).code, 42)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 1)
    }

    func testSynchronousSubmissionRepliesOnALaterTurn() {
        let queue = ReminderSubmissionQueue()
        var returned = false
        let finished = expectation(description: "async reply")
        queue.flush({ $0(.success(())) }, timeout: 1) { _ in
            XCTAssertTrue(returned, "NSApplication must receive terminateLater before its reply")
            finished.fulfill()
        }
        returned = true
        wait(for: [finished], timeout: 1)
    }

    func testOlderFailureDoesNotOverrideSuccessfulLatestFlush() {
        let queue = ReminderSubmissionQueue()
        var finishOld: Finish!
        let finished = expectation(description: "latest result")
        queue.enqueue { finishOld = $0 }
        queue.flush({ $0(.success(())) }, timeout: 1) { result in
            if case .failure(let error) = result { XCTFail("Unexpected failure: \(error)") }
            finished.fulfill()
        }
        finishOld(.failure(NSError(domain: "superseded", code: 1)))
        wait(for: [finished], timeout: 1)
    }

    func testFlushIncludesChangesEnqueuedWhileItWaits() {
        let queue = ReminderSubmissionQueue()
        var finishFirst: Finish!
        var starts: [Int] = []
        let finished = expectation(description: "latest changes included")
        queue.flush({ finish in starts.append(1); finishFirst = finish }, timeout: 1) { result in
            XCTAssertEqual(starts, [1, 2])
            if case .failure(let error) = result { XCTFail("Unexpected failure: \(error)") }
            finished.fulfill()
        }
        queue.enqueue { finish in starts.append(2); finish(.success(())) }
        finishFirst(.success(()))
        wait(for: [finished], timeout: 1)
    }

    func testTimeoutDoesNotAllowAnOlderSubmissionToRunAfterANewerOne() {
        let queue = ReminderSubmissionQueue()
        var finishStalled: Finish!
        var starts: [Int] = []
        var timeoutCount = 0
        let timedOut = expectation(description: "bounded flush")
        queue.enqueue { finish in starts.append(1); finishStalled = finish }
        queue.flush({ finish in starts.append(2); finish(.success(())) }, timeout: 0.01) { result in
            guard case .failure(let error) = result,
                  case ReminderSubmissionQueue.FlushError.timedOut = error else {
                return XCTFail("Expected a timeout")
            }
            timeoutCount += 1
            timedOut.fulfill()
        }
        wait(for: [timedOut], timeout: 1)
        XCTAssertEqual(starts, [1])
        let retried = expectation(description: "retry drained")
        queue.flush({ finish in starts.append(3); finish(.success(())) }, timeout: 1) { result in
            if case .failure(let error) = result { XCTFail("Unexpected failure: \(error)") }
            retried.fulfill()
        }
        XCTAssertEqual(starts, [1], "A timeout must not release the in-flight operation")
        finishStalled(.success(()))
        wait(for: [retried], timeout: 1)
        XCTAssertEqual(starts, [1, 3])
        XCTAssertEqual(timeoutCount, 1)
    }
}
