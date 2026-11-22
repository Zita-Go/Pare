import XCTest
@testable import PareCore

final class AIRequestTraceTests: XCTestCase {
    private enum ClientKind: CaseIterable { case legacy, intelligence }

    private struct Fixture {
        let legacy: AIRequest
        let intelligence: IntelligenceRequest

        init() {
            let task = TaskItem(text: "Synthetic trace fixture", quadrant: .importantUrgent)
            legacy = AIRequest(workflow: .nextStep, inputs: [AIInput(task: task)])
            intelligence = IntelligenceRequest(intent: .idea, subjectID: task.id, availableMinutes: 30,
                energy: "一般", instruction: "", options: IntelligenceContextOptions(),
                evidence: [IntelligenceEvidence(id: task.id, kind: "idea", title: task.text,
                    body: "Fixture only", date: Date(), revision: "fixture")], now: Date(), timeZone: "UTC")
        }

        func success(_ kind: ClientKind) throws -> Data {
            let content: Data
            switch kind {
            case .legacy:
                content = try JSONEncoder().encode(AIDraft(question: "下一步需要什么？", suggestions: []))
            case .intelligence:
                content = try JSONEncoder().encode(IntelligenceProposal(requestID: intelligence.id,
                    summary: "Synthetic response", clarification: "下一步需要什么？", actions: []))
            }
            return try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": "stop",
                "message": ["content": String(decoding: content, as: UTF8.self)]]]])
        }

        func generate(_ kind: ClientKind, config: AIConfiguration, trace: @escaping AIRequestTraceHandler) async throws {
            let session = URLSessionConfiguration.ephemeral
            session.protocolClasses = [TraceURLProtocol.self]
            switch kind {
            case .legacy:
                _ = try await AIClient(configuration: session).generate(config: config,
                    apiKey: "synthetic-secret", input: legacy, trace: trace)
            case .intelligence:
                _ = try await IntelligenceClient(configuration: session).generate(config: config,
                    apiKey: "synthetic-secret", input: intelligence, trace: trace)
            }
        }
    }

    private func configuration(_ reply: TraceURLProtocol.Reply) -> AIConfiguration {
        var config = AIConfiguration()
        config.model = "trace-fixture"
        config.baseURL = "https://trace.invalid/\(UUID())"
        TraceURLProtocol.register(reply, for: try! config.endpoint())
        return config
    }

    func testSuccessfulTracesMatchActualTransportBytesAndOmitHeaders() async throws {
        let fixture = Fixture()
        for kind in ClientKind.allCases {
            let expected = try fixture.success(kind)
            let reply = TraceURLProtocol.Reply(body: expected)
            let config = configuration(reply)
            let log = TraceLog()
            try await fixture.generate(kind, config: config) { event in
                if case .request = event { XCTAssertEqual(reply.requestCount, 0, "Snapshot must precede sending") }
                await log.append(event)
            }
            let events = await log.events
            XCTAssertEqual(events.count, 2)
            guard events.count == 2,
                  case let .request(body, endpoint) = events[0],
                  case let .response(response, status, truncated) = events[1] else { return XCTFail("Expected ordered request/response") }
            XCTAssertEqual(Data(body.utf8), reply.receivedBody)
            XCTAssertEqual(endpoint, try config.endpoint().absoluteString)
            XCTAssertFalse(body.contains("synthetic-secret"))
            XCTAssertFalse(body.contains("Authorization"))
            XCTAssertEqual(response, String(decoding: expected, as: UTF8.self))
            XCTAssertEqual(status, 200)
            XCTAssertFalse(truncated)
            XCTAssertEqual(reply.requestCount, 1)
        }
    }

    func testHTTPErrorCapturesRawBodyAndPreservesStatusWithoutRetry() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(status: 429, body: Data("{\"error\":\"quota fixture\"}".utf8))
            let log = TraceLog()
            do {
                try await Fixture().generate(kind, config: configuration(reply)) { await log.append($0) }
                XCTFail("Expected HTTP failure")
            } catch AIError.http(let code) { XCTAssertEqual(code, 429) }
            let events = await log.events
            assertResponse(events, body: "{\"error\":\"quota fixture\"}", status: 429, truncated: false)
            XCTAssertEqual(reply.requestCount, 1)
        }
    }

    func testInvalidJSONStillEmitsRawResponseBeforeThrowing() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(body: Data("<html>provider failure</html>".utf8))
            let log = TraceLog()
            do {
                try await Fixture().generate(kind, config: configuration(reply)) { await log.append($0) }
                XCTFail("Expected invalid JSON")
            } catch { XCTAssertFalse(error is URLError) }
            let events = await log.events
            assertResponse(events, body: "<html>provider failure</html>", status: 200, truncated: false)
        }
    }

    func testUnknownLengthOversizeRetainsBoundedPrefixAndFails() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(body: Data(repeating: 120, count: 128_001))
            let log = TraceLog()
            do {
                try await Fixture().generate(kind, config: configuration(reply)) { await log.append($0) }
                XCTFail("Expected size rejection")
            } catch AIError.responseTooLarge { }
            let events = await log.events
            assertResponse(events, body: String(repeating: "x", count: 128_000), status: 200, truncated: true)
        }
    }

    func testOversizeHTTPErrorRetainsHTTPStatusAndBoundedBody() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(status: 503, body: Data(repeating: 120, count: 128_001))
            let log = TraceLog()
            do {
                try await Fixture().generate(kind, config: configuration(reply)) { await log.append($0) }
                XCTFail("Expected HTTP rejection")
            } catch AIError.http(let code) { XCTAssertEqual(code, 503) }
            let events = await log.events
            assertResponse(events, body: String(repeating: "x", count: 128_000), status: 503, truncated: true)
        }
    }

    func testExactResponseLimitIsNotReportedAsTruncated() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(body: Data(repeating: 120, count: 128_000))
            let log = TraceLog()
            do {
                try await Fixture().generate(kind, config: configuration(reply)) { await log.append($0) }
                XCTFail("Expected malformed response")
            } catch AIError.responseTooLarge { XCTFail("Exactly 128000 bytes is within the limit") }
            catch { }
            let events = await log.events
            assertResponse(events, body: String(repeating: "x", count: 128_000), status: 200, truncated: false)
        }
    }

    func testMalformedUTF8ReplacementTextIsAlsoBounded() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(status: 502, body: Data(repeating: 0xFF, count: 128_000))
            let log = TraceLog()
            do {
                try await Fixture().generate(kind, config: configuration(reply)) { await log.append($0) }
                XCTFail("Expected HTTP rejection")
            } catch AIError.http(let code) { XCTAssertEqual(code, 502) }
            let events = await log.events
            guard let last = events.last, case let .response(body, status, truncated) = last else {
                return XCTFail("Expected response trace")
            }
            XCTAssertLessThanOrEqual(body.utf8.count, 128_000)
            XCTAssertTrue(body.hasPrefix("\u{FFFD}"))
            XCTAssertEqual(status, 502)
            XCTAssertTrue(truncated)
        }
    }

    func testDeclaredOversizePreservesIntelligenceEarlyRejection() async throws {
        let reply = TraceURLProtocol.Reply(body: Data("unread body".utf8), declaredLength: 128_001)
        let log = TraceLog()
        do {
            try await Fixture().generate(.intelligence, config: configuration(reply)) { await log.append($0) }
            XCTFail("Expected declared length rejection")
        } catch AIError.responseTooLarge { }
        let events = await log.events
        assertResponse(events, body: "", status: 200, truncated: true)
    }

    func testCancellationDuringRequestSnapshotDoesNotSend() async throws {
        for kind in ClientKind.allCases {
            let reply = TraceURLProtocol.Reply(body: Data("unused".utf8))
            let config = configuration(reply)
            let log = TraceLog()
            let task = Task {
                try await Fixture().generate(kind, config: config) { event in
                    await log.append(event)
                    if case .request = event { withUnsafeCurrentTask { $0?.cancel() } }
                }
            }
            do { try await task.value; XCTFail("Expected cancellation") }
            catch is CancellationError { }
            XCTAssertEqual(reply.requestCount, 0)
            let events = await log.events
            XCTAssertEqual(events.count, 2)
            guard let last = events.last, case let .response(body, status, truncated) = last else {
                return XCTFail("Expected empty response trace")
            }
            XCTAssertEqual(body, "")
            XCTAssertNil(status)
            XCTAssertFalse(truncated)
        }
    }

    func testCancellationRecordsReceivedPrefix() async throws {
        for kind in ClientKind.allCases {
            let sent = expectation(description: "Sent partial body")
            let reply = TraceURLProtocol.Reply(body: Data("partial response".utf8), finish: false, sent: { sent.fulfill() })
            let config = configuration(reply)
            let log = TraceLog()
            let task = Task { try await Fixture().generate(kind, config: config) { await log.append($0) } }
            await fulfillment(of: [sent], timeout: 2)
            try await Task.sleep(nanoseconds: 100_000_000)
            task.cancel()
            do { try await task.value; XCTFail("Expected cancellation") }
            catch is CancellationError { }
            catch let error as URLError { XCTAssertEqual(error.code, .cancelled) }
            let events = await log.events
            assertResponse(events, body: "partial response", status: 200, truncated: true)
            XCTAssertEqual(reply.requestCount, 1)
        }
    }

    private func assertResponse(_ events: [AIRequestTraceEvent], body: String, status: Int,
                                truncated: Bool, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(events.count, 2, file: file, line: line)
        guard let last = events.last, case let .response(actual, code, cut) = last else {
            return XCTFail("Missing response trace", file: file, line: line)
        }
        XCTAssertEqual(actual, body, file: file, line: line)
        XCTAssertEqual(code, status, file: file, line: line)
        XCTAssertEqual(cut, truncated, file: file, line: line)
    }
}

private actor TraceLog {
    var events: [AIRequestTraceEvent] = []
    func append(_ event: AIRequestTraceEvent) { events.append(event) }
}

/// Replaces only the HTTP boundary; generate, serialization, streaming, and validation remain real.
private final class TraceURLProtocol: URLProtocol {
    final class Reply {
        let status: Int
        let body: Data
        let finish: Bool
        let declaredLength: Int?
        let sent: (() -> Void)?
        private let lock = NSLock()
        private var requests: [Data] = []
        init(status: Int = 200, body: Data, finish: Bool = true, declaredLength: Int? = nil, sent: (() -> Void)? = nil) {
            self.status = status; self.body = body; self.finish = finish
            self.declaredLength = declaredLength; self.sent = sent
        }
        var receivedBody: Data? { lock.lock(); defer { lock.unlock() }; return requests.first }
        var requestCount: Int { lock.lock(); defer { lock.unlock() }; return requests.count }
        func receive(_ body: Data) { lock.lock(); defer { lock.unlock() }; requests.append(body) }
    }
    private static let lock = NSLock()
    private static var replies: [URL: Reply] = [:]
    static func register(_ reply: Reply, for url: URL) { lock.lock(); defer { lock.unlock() }; replies[url] = reply }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "trace.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let reply = Self.replies.removeValue(forKey: request.url!)
        Self.lock.unlock()
        guard let reply else { client?.urlProtocol(self, didFailWithError: URLError(.badURL)); return }
        var body = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                guard count > 0 else { break }
                body.append(contentsOf: buffer.prefix(count))
            }
        }
        reply.receive(body)
        var headers = ["Content-Type": "application/json"]
        if let length = reply.declaredLength { headers["Content-Length"] = String(length) }
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: reply.body)
        reply.sent?()
        if reply.finish { client?.urlProtocolDidFinishLoading(self) }
    }
    override func stopLoading() { }
}
