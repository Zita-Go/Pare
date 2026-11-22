import XCTest
@testable import PareCore

final class ExecutionPresenceHTTPTests: XCTestCase {
    private func client(_ handler: @escaping (URLRequest) throws -> PresenceHTTPMock.Reply) throws -> SyncHTTPClient {
        let host="\(UUID().uuidString.lowercased()).invalid"
        PresenceHTTPMock.set(host: host, handler: handler)
        addTeardownBlock { PresenceHTTPMock.set(host: host, handler: nil) }
        let configuration=try SyncHTTPConfiguration(projectURL: URL(string: "https://\(host)")!, publishableKey: "sb_publishable_fictional_test")
        let session=URLSessionConfiguration.ephemeral; session.protocolClasses=[PresenceHTTPMock.self]
        return SyncHTTPClient(configuration: configuration, sessionConfiguration: session)
    }
    private func body(_ request: URLRequest) throws -> [String: Any] {
        var data=request.httpBody ?? Data()
        if data.isEmpty, let stream=request.httpBodyStream {
            stream.open(); defer {stream.close()}; var buffer=[UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {let count=stream.read(&buffer, maxLength: buffer.count); if count <= 0 {break}; data.append(buffer, count: count)}
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    private func reply(deviceID: UUID, accepted: Int64?, ownSequence: Int64?, label:String="Mac", runs:[ExecutionPresenceRun]=[]) throws -> Data {
        var value: [String: Any] = ["version":1,"server_now":"2026-10-31T08:00:00.123Z","devices":[]]
        if let accepted {value["accepted_sequence"]=accepted}
        if let ownSequence {
            value["devices"]=[["device_id":deviceID.uuidString.lowercased(),"sequence":ownSequence,"label":label,
                "last_seen":"2026-10-31T08:00:00.123Z","expires_at":"2026-10-31T08:01:30.123Z",
                "runs":try JSONSerialization.jsonObject(with:SyncWire.encoder().encode(runs))] as [String:Any]]
        }
        return try JSONSerialization.data(withJSONObject:value)
    }
    func testAuthenticatedRPCIsSeparateFromContentAndAcceptsServerAcknowledgement() async throws {
        let request=ExecutionPresenceRequest(deviceID: UUID(), sequence: 4, label: "Mac", runs: [])
        let client=try client { outgoing in
            XCTAssertEqual(outgoing.url?.path, "/rest/v1/rpc/pare_presence_exchange_v2")
            XCTAssertEqual(outgoing.value(forHTTPHeaderField: "Authorization"), "Bearer fictional-access")
            let body=try self.body(outgoing)
            XCTAssertEqual(Set(body.keys), Set(["p_device_id","p_sequence","p_label","p_runs"]))
            XCTAssertEqual(body["p_label"] as? String, "Mac"); XCTAssertEqual(body["p_sequence"] as? Int, 4)
            return .init(data: try self.reply(deviceID:request.deviceID,accepted:4,ownSequence:4))
        }
        let response=try await client.exchangePresence(request, accessToken: "fictional-access")
        XCTAssertEqual(response.acceptedSequence, 4)
    }
    func testActualRPCRequiresAcknowledgementAndMatchingOwnDeviceReceipt() async throws {
        let request=ExecutionPresenceRequest(deviceID:UUID(),sequence:4,label:"Mac",runs:[])
        let invalidReplies = try [
            reply(deviceID:request.deviceID,accepted:nil,ownSequence:4),
            reply(deviceID:request.deviceID,accepted:4,ownSequence:nil),
            reply(deviceID:UUID(),accepted:4,ownSequence:4),
            reply(deviceID:request.deviceID,accepted:4,ownSequence:3),
            reply(deviceID:request.deviceID,accepted:4,ownSequence:5),
            reply(deviceID:request.deviceID,accepted:3,ownSequence:3),
            reply(deviceID:request.deviceID,accepted:4,ownSequence:4,label:"Android"),
            reply(deviceID:request.deviceID,accepted:4,ownSequence:4,runs:[.init(runID:UUID(),taskID:UUID(),kind:.personal,state:.running,
                startedAt:Date(timeIntervalSince1970:1_800_000_000),elapsedSeconds:30,targetSeconds:nil)])
        ]
        for data in invalidReplies {
            let client=try client {_ in .init(data:data)}
            do {_ = try await client.exchangePresence(request,accessToken:"fictional");XCTFail("Incomplete or mismatched own receipt accepted")}
            catch {XCTAssertEqual(error as? SyncHTTPError,.invalidResponse)}
        }
    }
    func testHigherServerAcknowledgementReturnsItsMatchingOwnDeviceForCounterRecovery() async throws {
        let request=ExecutionPresenceRequest(deviceID:UUID(),sequence:1,label:"Mac",runs:[])
        let client=try client {_ in .init(data:try self.reply(deviceID:request.deviceID,accepted:50_000,ownSequence:50_000))}
        let response=try await client.exchangePresence(request,accessToken:"fictional")
        XCTAssertEqual(response.acceptedSequence,50_000)
        XCTAssertEqual(response.devices.first?.deviceID,request.deviceID)
    }
    func testOwnReceiptComparesTransmittedDatePrecisionRatherThanInMemoryFraction() async throws {
        let run=ExecutionPresenceRun(runID:UUID(),taskID:UUID(),kind:.personal,state:.running,
            startedAt:Date(timeIntervalSince1970:1_800_000_000.456),elapsedSeconds:10,targetSeconds:300)
        let request=ExecutionPresenceRequest(deviceID:UUID(),sequence:2,label:"Mac",runs:[run])
        let client=try client {_ in .init(data:try self.reply(deviceID:request.deviceID,accepted:2,ownSequence:2,runs:[run]))}
        let response=try await client.exchangePresence(request,accessToken:"fictional")
        XCTAssertEqual(response.devices.first?.runs.first?.startedAt,Date(timeIntervalSince1970:1_800_000_000))
    }
    func testInvalidInputNeverSendsAndInvalidResponseIsRedacted() async throws {
        let offline=try client { _ in XCTFail("Invalid input must not send"); return .init(status: 500) }
        do {_ = try await offline.exchangePresence(.init(deviceID: UUID(), sequence: 0, label: "Mac", runs: []), accessToken: "fictional"); XCTFail()}
        catch {XCTAssertEqual(error as? SyncHTTPError, .invalidRequest)}
        for data in [Data("{\"sensitive-task-title\":true}".utf8), Data(#"{"version":1,"server_now":"2026-10-31T08:00:00Z","devices":[],"accepted_sequence":1}"#.utf8)] {
            let client=try client {_ in .init(data: data)}
            do {_ = try await client.exchangePresence(.init(deviceID: UUID(), sequence: 2, label: "Mac", runs: []), accessToken: "fictional"); XCTFail()}
            catch {XCTAssertEqual(error as? SyncHTTPError, .invalidResponse); XCTAssertFalse(error.localizedDescription.contains("sensitive"))}
        }
    }
    func testPresenceTransportRejectsOverOneMiBEvenWithoutLengthAndDoesNotExposeServerBody() async throws {
        for advertised in [false,true] {
            let client=try client {_ in .init(data: Data(repeating: 32, count: 1_048_577), headers: advertised ? ["Content-Length":"1048577"] : [:])}
            do {_ = try await client.exchangePresence(.init(deviceID: UUID(), sequence: 1, label: "Mac", runs: []), accessToken: "fictional"); XCTFail()}
            catch {XCTAssertEqual(error as? SyncHTTPError, .responseTooLarge)}
        }
        let client=try client {_ in .init(status: 401, data: Data("sensitive-server-content".utf8))}
        do {_ = try await client.exchangePresence(.init(deviceID: UUID(), sequence: 1, label: "Mac", runs: []), accessToken: "fictional"); XCTFail()}
        catch {XCTAssertEqual(error as? SyncHTTPError, .unauthorized); XCTAssertFalse(String(reflecting: error).contains("sensitive"))}
    }
}

private final class PresenceHTTPMock: URLProtocol {
    struct Reply {var status=200;var data=Data();var headers:[String:String]=[:]}
    private static let lock=NSLock()
    private static var handlers:[String:(URLRequest)throws->Reply]=[:]
    static func set(host:String,handler:((URLRequest)throws->Reply)?) {lock.lock();defer {lock.unlock()};handlers[host]=handler}
    override class func canInit(with request:URLRequest)->Bool {true}
    override class func canonicalRequest(for request:URLRequest)->URLRequest {request}
    override func startLoading() {
        Self.lock.lock();let handler=Self.handlers[request.url!.host!];Self.lock.unlock()
        do {
            guard let handler else {XCTFail("Unexpected external destination");throw URLError(.badURL)}
            let reply=try handler(request)
            let response=HTTPURLResponse(url:request.url!,statusCode:reply.status,httpVersion:"HTTP/1.1",headerFields:reply.headers)!
            client?.urlProtocol(self,didReceive:response,cacheStoragePolicy:.notAllowed)
            client?.urlProtocol(self,didLoad:reply.data);client?.urlProtocolDidFinishLoading(self)
        } catch {client?.urlProtocol(self,didFailWithError:error)}
    }
    override func stopLoading() {}
}
