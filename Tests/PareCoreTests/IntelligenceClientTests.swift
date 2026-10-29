import XCTest
@testable import PareCore

final class IntelligenceClientTests: XCTestCase {
    private func fixture() throws -> (IntelligenceRequest, IntelligenceProposal) {
        let store = Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("intelligence-network-\(UUID()).json"))
        let idea = try XCTUnwrap(store.addThought("为社区设计一个小型共享书架"))
        let request = try store.prepareIntelligenceRequest(intent: .idea, subjectID: idea.id)
        let action = IntelligenceAction(kind: .updateIdeaNextQuestion, sourceID: idea.id,
                                        evidenceIDs: [idea.id], text: "先问一位邻居最愿意分享哪本书", reason: "先确认需求")
        let proposal = IntelligenceProposal(requestID: request.id, summary: "还没有用户访谈记录。", actions: [action], evidenceIDs: [idea.id])
        return (request, proposal)
    }

    private func envelope(_ proposal: IntelligenceProposal, finish: String = "stop", fence: Bool = false) throws -> Data {
        let json = String(decoding: try JSONEncoder().encode(proposal), as: UTF8.self)
        return try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": finish,
            "message": ["content": fence ? "```json\n\(json)\n```" : json]]]])
    }

    func testRequestSeparatesCredentialsAndUsesStrictSchemaOnlyWhenConfigured() throws {
        let (input, _) = try fixture()
        var config = AIConfiguration(); config.model = "fixture"; config.baseURL = "http://127.0.0.1:8123/v1"
        let request = try IntelligenceClient.makeRequest(config: config, apiKey: "test-only-token", input: input)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: request.httpBody!) as? [String: Any])
        XCTAssertEqual(request.url?.path, "/v1/chat/completions")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-only-token")
        XCTAssertFalse(String(decoding: request.httpBody!, as: UTF8.self).contains("test-only-token"))
        XCTAssertEqual(body["store"] as? Bool, false)
        XCTAssertEqual(body["stream"] as? Bool, false)
        XCTAssertNotNil(body["response_format"])
        XCTAssertEqual(body["max_tokens"] as? Int, 2000)
        config.strictSchema = false
        let compatible = try IntelligenceClient.makeRequest(config: config, apiKey: "", input: input)
        XCTAssertNil(try (JSONSerialization.jsonObject(with: compatible.httpBody!) as? [String: Any])?["response_format"])
        XCTAssertNil(compatible.value(forHTTPHeaderField: "Authorization"))
    }

    func testCompatibleFenceStillValidatesActionSourceAndFinishReason() throws {
        let (input, valid) = try fixture()
        XCTAssertEqual(try IntelligenceClient.decode(envelope(valid, fence: true), for: input), valid)
        var wrongSource = valid; wrongSource.actions[0].sourceID = UUID()
        XCTAssertThrowsError(try IntelligenceClient.decode(envelope(wrongSource, fence: true), for: input))
        XCTAssertThrowsError(try IntelligenceClient.decode(envelope(valid, finish: "length"), for: input))
        XCTAssertThrowsError(try IntelligenceClient.decode(Data(repeating: 32, count: 128_001), for: input))
        XCTAssertThrowsError(try IntelligenceClient.decode(Data("{}".utf8), for: input))
    }

    func testUnsafeRemoteHTTPRejectedBeforeSending() throws {
        let (input, _) = try fixture()
        var config = AIConfiguration(); config.model = "fixture"; config.baseURL = "http://example.com/v1"
        XCTAssertThrowsError(try IntelligenceClient.makeRequest(config: config, apiKey: "", input: input))
    }

    private func withServer<T>(mode: String = "success", _ body: (URL) async throws -> T) async throws -> T {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        process.arguments = [root.appendingPathComponent("Scripts/intelligence-mock-server.py").path, "--port", "0", "--mode", mode]
        let output = Pipe(); process.standardOutput = output; process.standardError = Pipe()
        try process.run()
        defer { process.terminate(); process.waitUntilExit() }
        let line = String(decoding: output.fileHandleForReading.availableData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let port = Int(line) else { throw NSError(domain: "MockServerStartup", code: 1) }
        return try await body(URL(string: "http://127.0.0.1:\(port)")!)
    }

    private func requestCount(_ url: URL) async throws -> Int {
        let (data, _) = try await URLSession.shared.data(from: url.appendingPathComponent("stats"))
        return try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Int])?["requests"])
    }

    func testLoopbackMakesOneRequestAndReturnsValidatedProposal() async throws {
        let (input, _) = try fixture()
        try await withServer { url in
            var config = AIConfiguration(); config.model = "fixture"; config.baseURL = url.appendingPathComponent("v1").absoluteString
            let client = IntelligenceClient()
            let before = try await requestCount(url)
            XCTAssertEqual(before, 0)
            let result = try await client.generate(config: config, apiKey: "", input: input)
            XCTAssertEqual(result.requestID, input.id)
            XCTAssertEqual(result.actions.count, 3)
            let after = try await requestCount(url)
            XCTAssertEqual(after, 1)
        }
    }

    func testLoopbackFailuresNeverRetryOrFollowRedirects() async throws {
        let (input, _) = try fixture()
        for mode in ["redirect", "error", "malformed", "truncated", "oversized"] {
            try await withServer(mode: mode) { url in
                var config = AIConfiguration(); config.model = "fixture"; config.baseURL = url.appendingPathComponent("v1").absoluteString
                do {
                    _ = try await IntelligenceClient().generate(config: config, apiKey: "test-only-token", input: input)
                    XCTFail("Expected rejection for \(mode)")
                } catch { }
                let count = try await requestCount(url)
                XCTAssertEqual(count, 1, "No retry or redirected request for \(mode)")
            }
        }
    }

    func testCancellationPreservesSingleRequest() async throws {
        let (input, _) = try fixture()
        try await withServer(mode: "slow") { url in
            var config = AIConfiguration(); config.model = "fixture"; config.baseURL = url.appendingPathComponent("v1").absoluteString
            let task = Task { try await IntelligenceClient().generate(config: config, apiKey: "", input: input) }
            try await Task.sleep(nanoseconds: 150_000_000)
            task.cancel()
            do { _ = try await task.value; XCTFail("Cancelled request must not return a proposal") } catch { }
            let count = try await requestCount(url)
            XCTAssertEqual(count, 1)
        }
    }


    func testOversizedInstructionRejectedBeforeTransport() throws {
        var (input, _) = try fixture()
        input.instruction = String(repeating: "x", count: 2001)
        var config = AIConfiguration(); config.model = "fixture"
        XCTAssertThrowsError(try IntelligenceClient.makeRequest(config: config, apiKey: "", input: input))
    }

    func testTimeoutDoesNotRetry() async throws {
        let (input, _) = try fixture()
        try await withServer(mode: "slow") { url in
            var config = AIConfiguration(); config.model = "fixture"; config.baseURL = url.appendingPathComponent("v1").absoluteString
            let sessionConfig = URLSessionConfiguration.ephemeral
            sessionConfig.timeoutIntervalForRequest = 0.2
            sessionConfig.timeoutIntervalForResource = 0.3
            do {
                _ = try await IntelligenceClient(configuration: sessionConfig).generate(config: config, apiKey: "", input: input)
                XCTFail("Expected bounded timeout")
            } catch let error as URLError {
                XCTAssertEqual(error.code, .timedOut)
            }
            let count = try await requestCount(url)
            XCTAssertEqual(count, 1)
        }
    }

}
