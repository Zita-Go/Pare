import Foundation
import PareCore

/// Explicit HTTP QA only: a public project config plus two pre-created disposable accounts.
/// Never initializes Store, application preferences, the app, or Keychain. Output contains no secrets.
@main struct PresenceLiveCheck {
    struct Configuration: Decodable { let api_url: URL; let publishable_key: String }
    struct User: Decodable { let id: UUID; let email: String; let password: String }
    struct Accounts: Decodable { let users: [User] }
    struct Bridge: Encodable { let mac_device: UUID; let mac_run: UUID; let started_at: Date }
    struct PythonReply: Decodable { let android_device: UUID; let android_run: UUID; let reply: ExecutionPresenceResponse }
    enum Failure: Error { case check }
    static func check(_ value: @autoclosure () throws -> Bool) throws { if try !value() { throw Failure.check } }
    static func readPrivate(_ path: String) throws -> Data {
        let info = try FileManager.default.attributesOfItem(atPath: path)
        try check((info[.posixPermissions] as? NSNumber)?.intValue == 0o600 && info[.type] as? FileAttributeType == .typeRegular)
        return try Data(contentsOf: URL(fileURLWithPath: path))
    }
    static func writePrivate(_ data: Data, to url: URL) throws {
        try check(FileManager.default.createFile(atPath: url.path, contents: data, attributes: [.posixPermissions: 0o600]))
    }
    static func own(_ response: ExecutionPresenceResponse, _ id: UUID) throws -> ExecutionPresenceDevice {
        guard let value=response.devices.first(where: { $0.deviceID == id }) else { throw Failure.check }; return value
    }
    static func main() async {
        var client: SyncHTTPClient?
        var sessions: [SyncAuthSession?] = []
        var checks=0
        var success=false
        var cleanup=true
        var temporary: URL?
        do {
            try check(CommandLine.arguments.count == 5)
            let config=try JSONDecoder().decode(Configuration.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
            let users=try JSONDecoder().decode(Accounts.self, from: readPrivate(CommandLine.arguments[2])).users
            try check(users.count == 2 && users.allSatisfy { $0.email == "presence-qa-\($0.id.uuidString.lowercased())@presence.pare.invalid" && $0.password.count >= 24 })
            try check(config.api_url.host == CommandLine.arguments[3] + ".supabase.co")
            let transport=SyncHTTPClient(configuration: try SyncHTTPConfiguration(projectURL: config.api_url, publishableKey: config.publishable_key)); client=transport
            let session=try await transport.signIn(email: users[0].email, password: users[0].password)
            sessions.append(session); try check(session.userID == users[0].id); checks += 1
            let device=UUID(), runID=UUID(), taskID=UUID(), start=Date()
            let run=ExecutionPresenceRun(runID: runID, taskID: taskID, kind: .personal, state: .running, startedAt: start, elapsedSeconds: 12, targetSeconds: 300)
            let first=try await transport.exchangePresence(.init(deviceID: device, sequence: 1, label: "Mac", runs: [run]), accessToken: session.accessToken)
            try check(first.devices.count == 1 && first.acceptedSequence == 1); checks += 1
            let root=FileManager.default.temporaryDirectory.appendingPathComponent("pare-presence-live-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); temporary=root
            let input=root.appendingPathComponent("bridge-in.json"), output=root.appendingPathComponent("bridge-out.json")
            // UUID encoder case is normalized explicitly for the raw-wire peer.
            let bridge: [String:Any] = ["mac_device":device.uuidString.lowercased(),"mac_run":runID.uuidString.lowercased(),
                "started_at":ISO8601DateFormatter().string(from:start)]
            try writePrivate(JSONSerialization.data(withJSONObject: bridge), to: input)
            let peer: PythonReply
            if CommandLine.arguments[4] == "--swift-peer" {
                // A separate authenticated client/session exercises the Android wire representation.
                // This mode does not claim that an Android process or GUI was launched.
                let otherClient=SyncHTTPClient(configuration:try SyncHTTPConfiguration(projectURL:config.api_url,publishableKey:config.publishable_key))
                let bSession=try await otherClient.signIn(email:users[0].email,password:users[0].password)
                let bIndex=sessions.count; sessions.append(bSession)
                let cSession=try await otherClient.signIn(email:users[1].email,password:users[1].password)
                let cIndex=sessions.count; sessions.append(cSession)
                try check(bSession.userID == users[0].id && cSession.userID == users[1].id); checks += 2
                let bDevice=UUID(), bRun=UUID()
                var waiting=ExecutionPresenceRun(runID:bRun,taskID:UUID(),kind:.waiting,state:.running,startedAt:start,elapsedSeconds:45,targetSeconds:nil)
                let bFirst=try await otherClient.exchangePresence(.init(deviceID:bDevice,sequence:1,label:"Android",runs:[waiting]),accessToken:bSession.accessToken)
                try check(bFirst.devices.contains { $0.deviceID == device && $0.runs.first?.runID == runID }); checks += 1
                waiting.state = .paused; waiting.elapsedSeconds = 78
                let bRequest=ExecutionPresenceRequest(deviceID:bDevice,sequence:2,label:"Android",runs:[waiting])
                let paused=try await otherClient.exchangePresence(bRequest,accessToken:bSession.accessToken)
                let retried=try await otherClient.exchangePresence(bRequest,accessToken:bSession.accessToken)
                try check(try own(paused,bDevice) == own(retried,bDevice)); checks += 1
                let bStale=try await otherClient.exchangePresence(.init(deviceID:bDevice,sequence:1,label:"Android",runs:[]),accessToken:bSession.accessToken)
                try check(bStale.acceptedSequence == 2 && (try own(bStale,bDevice)) == own(paused,bDevice)); checks += 1
                let isolated=try await otherClient.exchangePresence(.init(deviceID:device,sequence:1,label:"Android",runs:[]),accessToken:cSession.accessToken)
                try check(isolated.devices.count == 1 && (try own(isolated,device)).runs.isEmpty); checks += 1
                let bRenewed=try await otherClient.refresh(refreshToken:bSession.refreshToken)
                sessions[bIndex]=bRenewed; try check(bRenewed.userID == users[0].id); checks += 1
                waiting.elapsedSeconds = 180
                let bFinal=try await otherClient.exchangePresence(.init(deviceID:bDevice,sequence:3,label:"Android",runs:[waiting]),accessToken:bRenewed.accessToken)
                try await otherClient.logout(accessToken:bRenewed.accessToken); sessions[bIndex]=nil
                try await otherClient.logout(accessToken:cSession.accessToken); sessions[cIndex]=nil
                do {
                    _ = try await otherClient.exchangePresence(.init(deviceID:bDevice,sequence:4,label:"Android",runs:[]),accessToken:bRenewed.accessToken)
                    throw Failure.check
                } catch let error as SyncHTTPError { try check(error == .forbidden || error == .unauthorized) }
                checks += 1
                peer=PythonReply(android_device:bDevice,android_run:bRun,reply:bFinal)
            } else {
                let python=Process(); python.executableURL=URL(fileURLWithPath:"/usr/bin/env")
                python.arguments=["python3",CommandLine.arguments[4],CommandLine.arguments[1],CommandLine.arguments[2],input.path,output.path,CommandLine.arguments[3]]
                // The peer's stdout is restricted to PASS/FAIL and counts; stderr is never forwarded.
                let errors=Pipe(); python.standardError=errors
                try python.run(); python.waitUntilExit(); try check(python.terminationStatus == 0)
                peer=try SyncWire.decoder().decode(PythonReply.self,from:readPrivate(output.path))
            }
            try peer.reply.validate()
            try check(peer.reply.devices.contains { $0.deviceID == device && $0.runs.first?.runID == runID }); checks += 1
            let both=try await transport.exchangePresence(.init(deviceID: device, sequence: 2, label: "Mac", runs: [run]),accessToken:session.accessToken)
            let android=try own(both,peer.android_device)
            try check(android.label == "Android" && android.sequence == 3 && android.runs.first?.runID == peer.android_run && android.runs.first?.state == .paused)
            checks += 1
            let renewed=try await transport.refresh(refreshToken:session.refreshToken)
            sessions[0]=renewed; try check(renewed.userID == users[0].id); checks += 1
            let request=ExecutionPresenceRequest(deviceID:device,sequence:3,label:"Mac",runs:[run])
            let updated=try await transport.exchangePresence(request,accessToken:renewed.accessToken)
            let repeatReply=try await transport.exchangePresence(request,accessToken:renewed.accessToken)
            try check(try own(updated,device) == own(repeatReply,device)); checks += 1
            let stale=try await transport.exchangePresence(.init(deviceID:device,sequence:1,label:"Mac",runs:[]),accessToken:renewed.accessToken)
            try check(stale.acceptedSequence == 3 && (try own(stale,device)).runs.first?.runID == runID); checks += 1
            do {
                _ = try await transport.exchangePresence(.init(deviceID:device,sequence:3,label:"Mac",runs:[]),accessToken:renewed.accessToken)
                throw Failure.check
            } catch let error as SyncHTTPError { try check(error == .requestRejected) }
            checks += 1
            print("PASS: \(CommandLine.arguments[4] == "--swift-peer" ? "Swift dual-client" : "Swift/Python") two-device exchange, sequence and refresh checks=\(checks)")
            // Exercise a real 90-second server lease. No direct SQL timestamp mutation is involved.
            let remaining=max(0,android.expiresAt.timeIntervalSince(both.serverNow))+3
            try await Task.sleep(nanoseconds:UInt64(remaining*1_000_000_000))
            let expired=try await transport.exchangePresence(.init(deviceID:device,sequence:4,label:"Mac",runs:[]),accessToken:renewed.accessToken)
            let oldPeer=try own(expired,peer.android_device)
            try check(oldPeer.expiresAt <= expired.serverNow && oldPeer.lastSeen == android.lastSeen)
            try check((try own(expired,device)).runs.isEmpty); checks += 2
            success=true
        } catch {
            // SyncHTTPError has no associated data; only its fixed case name is safe to report.
            let category=(error as? SyncHTTPError).map { String(describing:$0) } ?? "local-check"
            print("FAIL: Swift presence acceptance; completed checks=\(checks); category=\(category)")
        }
        if let client {
            for session in sessions.compactMap({$0}) {
                do { try await client.logout(accessToken:session.accessToken) }
                catch { cleanup=false }
            }
        }
        if let temporary { try? FileManager.default.removeItem(at:temporary) }
        print("\(success && cleanup ? "PASS" : "FAIL"): Swift presence checks=\(checks); session cleanup=\(cleanup ? "PASS" : "REQUIRED")")
        if !success || !cleanup { exit(1) }
    }
}
