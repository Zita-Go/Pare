import XCTest
@testable import PareCore

final class SyncHTTPClientTests: XCTestCase {
    private let key = "sb_publishable_test_key"
    private let userID = UUID(uuidString: "33333333-3333-4333-8333-333333333333")!
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func client(_ reply: @escaping (URLRequest) throws -> SyncHTTPMock.Reply) throws -> SyncHTTPClient {
        let host = "\(UUID().uuidString.lowercased()).invalid"
        SyncHTTPMock.install(host: host, handler: reply)
        addTeardownBlock { SyncHTTPMock.remove(host: host) }
        let configuration = try SyncHTTPConfiguration(projectURL: URL(string: "https://\(host)")!, publishableKey: key)
        let session = URLSessionConfiguration.ephemeral
        session.protocolClasses = [SyncHTTPMock.self]
        let instant = now
        return SyncHTTPClient(configuration: configuration, sessionConfiguration: session, now: { instant })
    }

    private func sessionJSON(_ changes: [String: Any] = [:]) throws -> Data {
        var value: [String: Any] = ["access_token": "access-sensitive", "refresh_token": "refresh-sensitive",
            "token_type": "bearer", "expires_in": 3600,
            "user": ["id": userID.uuidString, "email": "person@example.com"]]
        changes.forEach { value[$0.key] = $0.value }
        return try JSONSerialization.data(withJSONObject: value)
    }

    private func body(_ request: URLRequest) throws -> [String: Any] {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(buffer, count: count)
            }
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testConfigurationRejectsCredentialURLsAndPrivilegedKeys() throws {
        for address in ["http://example.com", "https://user:password@example.com", "https://example.com?secret=x",
                        "https://example.com#secret", "https://example.com/auth/v1", "file:///tmp"] {
            XCTAssertThrowsError(try SyncHTTPConfiguration(projectURL: XCTUnwrap(URL(string: address)), publishableKey: key))
        }
        for invalidKey in ["", "sb_secret_sensitive", "sb_publishable_", "sb_publishable_bad\nvalue", jwt(role: "service_role")] {
            XCTAssertThrowsError(try SyncHTTPConfiguration(projectURL: URL(string: "https://example.com")!, publishableKey: invalidKey))
        }
        XCTAssertNoThrow(try SyncHTTPConfiguration(projectURL: URL(string: "https://example.com/")!, publishableKey: jwt(role: "anon")))
        XCTAssertThrowsError(try SyncHTTPConfiguration(projectURL: URL(string: "http://127.0.0.1:1234")!, publishableKey: key))
    }

    private func jwt(role: String) -> String {
        let payload = Data("{\"role\":\"\(role)\"}".utf8).base64EncodedString().replacingOccurrences(of: "=", with: "")
        return "eyJhbGciOiJIUzI1NiJ9.\(payload).c2lnbmF0dXJl"
    }

    func testSignInUsesPasswordGrantAndCalculatesPersistentSessionExpiry() async throws {
        let response = try sessionJSON()
        let client = try client { request in
            XCTAssertEqual(request.url?.path, "/auth/v1/token")
            XCTAssertEqual(request.url?.query, "grant_type=password")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "apikey"), self.key)
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertEqual(request.timeoutInterval, 30)
            let object = try self.body(request)
            XCTAssertEqual(object["email"] as? String, "person@example.com")
            XCTAssertEqual(object["password"] as? String, "  password preserved  ")
            return .init(data: response)
        }
        let session = try await client.signIn(email: " person@example.com ", password: "  password preserved  ")
        XCTAssertEqual(session.userID, userID)
        XCTAssertEqual(session.expiresAt, now.addingTimeInterval(3600))
        XCTAssertEqual(session.accessToken, "access-sensitive")
        XCTAssertEqual(session.refreshToken, "refresh-sensitive")
        XCTAssertEqual(session.email, "person@example.com")
        XCTAssertEqual(try JSONDecoder().decode(SyncAuthSession.self, from: JSONEncoder().encode(session)), session)
        XCTAssertFalse(String(describing: session).contains("sensitive"))
        XCTAssertFalse(String(reflecting: session).contains("sensitive"))
    }

    func testSignUpSupportsUserOnlyConfirmationAndImmediateSession() async throws {
        let data = try JSONSerialization.data(withJSONObject: ["id": userID.uuidString, "email": "person@example.com"])
        let waitingClient = try client { request in
            XCTAssertEqual(request.url?.path, "/auth/v1/signup")
            return .init(data: data)
        }
        let waiting = try await waitingClient.signUp(email: "person@example.com", password: "password")
        XCTAssertNil(waiting)
        let signedInData = try sessionJSON(["expires_at": now.timeIntervalSince1970 + 1200])
        let signedInClient = try client { _ in .init(data: signedInData) }
        let session = try await signedInClient.signUp(email: "person@example.com", password: "password")
        XCTAssertEqual(session?.expiresAt, now.addingTimeInterval(1200))
    }

    func testMalformedOrPartialSessionCannotMasqueradeAsConfirmation() async throws {
        for data in [Data("{}".utf8), try sessionJSON(["refresh_token": ""]),
                     try sessionJSON(["user": ["id": "invalid"]]), try sessionJSON(["expires_in": -1]),
                     try sessionJSON(["access_token": NSNull()])] {
            let client = try client { _ in .init(data: data) }
            do { _ = try await client.signUp(email: "person@example.com", password: "password"); XCTFail("Malformed auth response accepted") }
            catch { XCTAssertEqual(error as? SyncHTTPError, .invalidResponse) }
        }
    }

    func testRefreshAndLogoutUseDistinctCredentialsAndLocalLogoutScope() async throws {
        let response = try sessionJSON(["refresh_token": "rotated-refresh"])
        let client = try client { request in
            if request.url?.path == "/auth/v1/token" {
                XCTAssertEqual(request.url?.query, "grant_type=refresh_token")
                XCTAssertEqual(try self.body(request)["refresh_token"] as? String, "old-refresh")
                XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
                return .init(data: response)
            }
            XCTAssertEqual(request.url?.path, "/auth/v1/logout")
            XCTAssertEqual(request.url?.query, "scope=local")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-sensitive")
            return .init(status: 204)
        }
        let refreshed = try await client.refresh(refreshToken: "old-refresh")
        XCTAssertEqual(refreshed.refreshToken, "rotated-refresh")
        try await client.logout(accessToken: refreshed.accessToken)
    }

    func testInvalidInputNeverStartsARequest() async throws {
        let client = try client { _ in XCTFail("Invalid input reached network"); return .init(status: 500) }
        do { _ = try await client.signIn(email: "invalid", password: "password"); XCTFail() } catch { XCTAssertEqual(error as? SyncHTTPError, .invalidCredentials) }
        do { _ = try await client.signIn(email: "person@example.com", password: ""); XCTFail() } catch { XCTAssertEqual(error as? SyncHTTPError, .invalidCredentials) }
        do { _ = try await client.refresh(refreshToken: "bad\r\ntoken"); XCTFail() } catch { XCTAssertEqual(error as? SyncHTTPError, .invalidToken) }
        do { _ = try await client.pull(after: -1, accessToken: "access"); XCTFail() } catch { XCTAssertEqual(error as? SyncHTTPError, .invalidRequest) }
        do { _ = try await client.pull(after: 0, accessToken: "bad token"); XCTFail() } catch { XCTAssertEqual(error as? SyncHTTPError, .invalidToken) }
    }

    func testPullUsesAuthenticatedRPCAndValidatesCursorBeforeReturn() async throws {
        let client = try client { request in
            XCTAssertEqual(request.url?.path, "/rest/v1/rpc/pare_sync_pull")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
            XCTAssertEqual(try self.body(request)["p_after"] as? Int, 7)
            return .init(data: Data(#"{"protocol_version":1,"cursor":7,"has_more":false,"commits":[]}"#.utf8))
        }
        let page = try await client.pull(after: 7, accessToken: "access")
        XCTAssertEqual(page.cursor, 7)
        let invalidClient = try self.client { _ in .init(data: Data(#"{"protocol_version":1,"cursor":8,"has_more":false,"commits":[]}"#.utf8)) }
        do { _ = try await invalidClient.pull(after: 7, accessToken: "access"); XCTFail() }
        catch { XCTAssertEqual(error as? SyncHTTPError, .invalidResponse) }
    }

    func testPushPreservesOperationIDAndDoesNotRetryARejectedMutation() async throws {
        let operation = UUID(), device = UUID()
        let request = SyncPushRequest(operationID: operation, deviceID: device,
            mutations: [.init(key: .init(kind: .task, id: "item"), baseRevision: 0, payload: .object(["text": .string("hello")]))])
        let count = SyncHTTPCallCounter()
        let client = try client { outgoing in
            count.increment()
            XCTAssertEqual(outgoing.url?.path, "/rest/v1/rpc/pare_sync_apply")
            let object = try self.body(outgoing)
            XCTAssertEqual((object["p_operation_id"] as? String)?.lowercased(), operation.uuidString.lowercased())
            XCTAssertEqual((object["p_device_id"] as? String)?.lowercased(), device.uuidString.lowercased())
            return .init(status: 503, data: Data("server-sensitive".utf8))
        }
        do { _ = try await client.push(request, accessToken: "access-sensitive"); XCTFail() }
        catch { XCTAssertEqual(error as? SyncHTTPError, .serverUnavailable) }
        XCTAssertEqual(count.value, 1)
    }

    func testPushValidatesSuccessReceiptAndReturnsConflictsWithoutRetry() async throws {
        let device = UUID()
        let request = SyncPushRequest(deviceID: device,
            mutations: [.init(key: .init(kind: .task, id: "item"), baseRevision: 0, payload: .object(["text": .string("hello")]))])
        let entity: [String: Any] = ["kind": "task", "id": "item", "revision": 1, "payload_version": 1,
            "payload": ["text": "hello"], "deleted_at": NSNull(), "last_device_id": device.uuidString, "sequence": 1]
        let success = try JSONSerialization.data(withJSONObject: ["protocol_version": 1, "status": "applied", "sequence": 1,
            "entities": [entity], "conflicts": []])
        let successClient = try client { _ in .init(data: success) }
        let receipt = try await successClient.push(request, accessToken: "access")
        XCTAssertEqual(receipt.entities.first?.key, request.mutations.first?.key)
        let count = SyncHTTPCallCounter()
        let conflictClient = try client { _ in
            count.increment()
            return .init(data: Data(#"{"protocol_version":1,"status":"conflict","sequence":0,"entities":[],"conflicts":[{"kind":"task","id":"item","current":null}]}"#.utf8))
        }
        let conflict = try await conflictClient.push(request, accessToken: "access")
        XCTAssertEqual(conflict.status, .conflict)
        XCTAssertEqual(count.value, 1)
        let invalidClient = try client { _ in .init(data: Data(#"{"protocol_version":1,"status":"applied","sequence":1,"entities":[],"conflicts":[]}"#.utf8)) }
        do { _ = try await invalidClient.push(request, accessToken: "access"); XCTFail() }
        catch { XCTAssertEqual(error as? SyncHTTPError, .invalidResponse) }
    }

    func testInFlightCancellationFinishesPromptlyWithoutRetry() async throws {
        let started = expectation(description: "Request started")
        let count = SyncHTTPCallCounter()
        let client = try client { _ in count.increment(); started.fulfill(); return .init(hang: true) }
        let task = Task { try await client.pull(after: 0, accessToken: "access") }
        await fulfillment(of: [started], timeout: 2)
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancellation returned a page") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(count.value, 1)
    }

    func testTimeoutHasSafeActionableErrorAndNoRetry() async throws {
        let count = SyncHTTPCallCounter()
        let client = try client { _ in count.increment(); throw URLError(.timedOut) }
        do { _ = try await client.pull(after: 0, accessToken: "access"); XCTFail() }
        catch { XCTAssertEqual(error as? SyncHTTPError, .timedOut) }
        XCTAssertEqual(count.value, 1)
    }

    func testErrorsNeverEchoRemoteBodyOrTransportDetails() async throws {
        for status in [400, 401, 403, 429, 500] {
            let client = try client { _ in .init(status: status, data: Data("password-sensitive access-sensitive refresh-sensitive sb_secret_sensitive".utf8)) }
            do { _ = try await client.pull(after: 0, accessToken: "access-sensitive"); XCTFail() }
            catch {
                XCTAssertFalse(error.localizedDescription.contains("sensitive"))
                XCTAssertFalse(String(reflecting: error).contains("sensitive"))
                if status == 401 { XCTAssertEqual(error as? SyncHTTPError, .unauthorized) }
            }
        }
        let client = try client { _ in throw NSError(domain: "password-sensitive", code: 1, userInfo: [NSLocalizedDescriptionKey: "access-sensitive"]) }
        do { _ = try await client.pull(after: 0, accessToken: "access-sensitive"); XCTFail() }
        catch { XCTAssertEqual(error as? SyncHTTPError, .networkUnavailable); XCTAssertFalse(error.localizedDescription.contains("sensitive")) }
    }

    func testResponseSizeIsLimitedWithAndWithoutContentLength() async throws {
        for advertised in [true, false] {
            let client = try client { _ in
                .init(data: Data(repeating: 32, count: 8 * 1024 * 1024 + 1),
                      headers: advertised ? ["Content-Length": "8388609"] : [:])
            }
            do { _ = try await client.pull(after: 0, accessToken: "access"); XCTFail() }
            catch { XCTAssertEqual(error as? SyncHTTPError, .responseTooLarge) }
        }
    }

    func testRedirectIsRejectedWithoutContactingDestination() async throws {
        let client = try client { _ in .init(status: 307, headers: ["Location": "https://credential-sink.invalid/capture"]) }
        do { _ = try await client.signIn(email: "person@example.com", password: "password-sensitive"); XCTFail() }
        catch { XCTAssertEqual(error as? SyncHTTPError, .redirectRejected) }
    }
}

private final class SyncHTTPCallCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.lock(); defer { lock.unlock() }; count += 1 }
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}

private final class SyncHTTPMock: URLProtocol {
    struct Reply {
        var status: Int = 200
        var data: Data = Data()
        var headers: [String: String] = [:]
        var hang = false
    }
    private static let lock = NSLock()
    private static var handlers: [String: (URLRequest) throws -> Reply] = [:]
    static func install(host: String, handler: @escaping (URLRequest) throws -> Reply) {
        lock.lock(); defer { lock.unlock() }; handlers[host] = handler
    }
    static func remove(host: String) { lock.lock(); defer { lock.unlock() }; handlers[host] = nil }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let handler = Self.handlers[request.url!.host!]
        Self.lock.unlock()
        do {
            guard let handler else { XCTFail("Unexpected destination contacted"); throw URLError(.badURL) }
            let reply = try handler(request)
            if reply.hang { return }
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: reply.headers)!
            if let destination = reply.headers["Location"], let url = URL(string: destination) {
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: url), redirectResponse: response)
            } else {
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: reply.data)
                client?.urlProtocolDidFinishLoading(self)
            }
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
