import Foundation
import PareCore

/// Explicit development acceptance runner. Takes a local public-project config
/// and a mode-0600 file of temporary, pre-created test accounts. It never reads
/// the default Pare store, model settings, or the system keychain.
@main
struct SyncCloudAcceptance {
    struct Configuration: Decodable { let api_url: URL; let publishable_key: String }
    struct Account: Decodable { let id: UUID; let email: String; let password: String }
    struct Accounts: Decodable { let users: [Account] }
    struct KotlinFixture: Decodable { let task_id: UUID; let expected_payload: SyncJSON }
    enum Failure: Error { case check(String) }

    static func expect(_ condition: @autoclosure () -> Bool, _ description: String) throws {
        guard condition() else { throw Failure.check(description) }
    }

    @MainActor
    static func pull(_ store: Store, _ client: SyncHTTPClient, _ token: String) async throws {
        for _ in 0..<200 {
            let reply = try await client.pull(after: store.syncState?.cursor ?? 0, accessToken: token)
            try store.receiveSyncPullResponse(reply)
            if !reply.hasMore { return }
        }
        throw Failure.check("pagination did not finish")
    }

    @MainActor
    static func push(_ store: Store, _ client: SyncHTTPClient, _ token: String) async throws {
        for _ in 0..<200 {
            guard let request = try store.nextSyncPushRequest() else { return }
            let result = try await client.push(request, accessToken: token)
            try store.receiveSyncPushResponse(result, for: request)
            if result.status == .conflict { return }
        }
        throw Failure.check("outbox did not drain")
    }

    @MainActor
    static func main() async {
        do {
            guard (3...4).contains(CommandLine.arguments.count) else { throw Failure.check("two explicit config paths required; optional Kotlin result") }
            let configuration = try JSONDecoder().decode(Configuration.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
            let accounts = try JSONDecoder().decode(Accounts.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))).users
            try expect(accounts.count == 2 && accounts.allSatisfy { $0.email.hasSuffix("@pare.invalid") }, "only temporary test accounts permitted")
            let client = SyncHTTPClient(configuration: try SyncHTTPConfiguration(projectURL: configuration.api_url, publishableKey: configuration.publishable_key))
            let one = try await client.signIn(email: accounts[0].email, password: accounts[0].password)
            let two = try await client.signIn(email: accounts[1].email, password: accounts[1].password)
            try expect(one.userID == accounts[0].id && two.userID == accounts[1].id, "authenticated identities")
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("pare-sync-cloud-\(UUID())")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
            if CommandLine.arguments.count == 4 {
                let fixture = try SyncWire.decoder().decode(KotlinFixture.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[3])))
                let receiver = Store(fileURL: root.appendingPathComponent("kotlin-receiver.json"), calendar: calendar)
                receiver.rolloverIfNeeded()
                try receiver.bindSyncAccount(accountID: one.userID.uuidString)
                try await pull(receiver, client, one.accessToken)
                try receiver.confirmSyncMerge()
                guard let imported = receiver.tasks.first(where: { $0.id == fixture.task_id }) else { throw Failure.check("Kotlin task missing in Swift Store") }
                let importedPayload = try SyncPayload.task(imported, archived: false)
                let normalizedExpected = try SyncPayload.task(SyncPayload.decodeTask(fixture.expected_payload).0, archived: false)
                try expect(importedPayload == normalizedExpected, "Kotlin payload decoded by real Swift Store")
                try expect(receiver.syncState?.shadow.first(where: { $0.key == .uuid(kind: .task, id: fixture.task_id) })?.payload == fixture.expected_payload,
                           "original wire precision retained in shadow")
                try expect(receiver.pendingSyncChangeCount == 0, "Kotlin import must not echo normalized timestamps")
                print("PASS: Kotlin-created cloud task imported by Swift Store unchanged")
            }
            let a = Store(fileURL: root.appendingPathComponent("device-a.json"), calendar: calendar)
            let b = Store(fileURL: root.appendingPathComponent("device-b.json"), calendar: calendar)
            a.rolloverIfNeeded(); b.rolloverIfNeeded()
            let task = a.addTask("同步验收：整理虚构旅行清单", to: .importantNotUrgent, addToToday: true, estimatedMinutes: 15)!
            try a.bindSyncAccount(accountID: one.userID.uuidString)
            try await pull(a, client, one.accessToken)
            try expect(a.syncMergePreview != nil, "first merge preview")
            try expect(a.syncState?.mergeConfirmed == false, "no automatic consent")
            try a.confirmSyncMerge()
            // Re-running against the same disposable account may meet an older
            // plan for today's key. Make the test's first-merge choice explicit.
            for conflict in a.syncState?.conflicts ?? [] {
                if a.syncState?.conflicts.contains(where: { $0.key == conflict.key }) == true {
                    try a.resolveSyncConflict(key: conflict.key, choice: .local)
                }
            }
            guard let request = try a.nextSyncPushRequest() else { throw Failure.check("first operation missing") }
            let first = try await client.push(request, accessToken: one.accessToken)
            // Simulate a lost response: same operation is sent twice before ACK.
            let repeated = try await client.push(request, accessToken: one.accessToken)
            try expect(first.sequence == repeated.sequence && first.entities == repeated.entities, "idempotent server receipt")
            try a.receiveSyncPushResponse(repeated, for: request)
            try await push(a, client, one.accessToken)
            try await pull(a, client, one.accessToken)
            try b.bindSyncAccount(accountID: one.userID.uuidString)
            try await pull(b, client, one.accessToken)
            try b.confirmSyncMerge()
            try expect(b.syncState?.conflicts.isEmpty == true, "fresh second device merge")
            try expect(b.tasks.contains { $0.id == task.id && $0.text == task.text && $0.isToday }, "task and date plan cross device")
            try await push(b, client, one.accessToken)
            b.setCompleted(task.id, true)
            try await push(b, client, one.accessToken)
            try await pull(a, client, one.accessToken)
            try expect(a.tasks.contains { $0.id == task.id && $0.completed }, "completion round trip")
            a.updateTaskDetails(task.id, text: "设备 A 的离线修改", note: nil, nextAction: nil, dueAt: nil, remindAt: nil, recurrence: nil)
            b.updateTaskDetails(task.id, text: "设备 B 的云端修改", note: nil, nextAction: nil, dueAt: nil, remindAt: nil, recurrence: nil)
            try await push(b, client, one.accessToken)
            try await push(a, client, one.accessToken)
            try expect(a.syncState?.conflicts.isEmpty == false, "concurrent modifications preserved")
            try a.resolveSyncConflict(key: .uuid(kind: .task, id: task.id), choice: .local)
            try await push(a, client, one.accessToken)
            try await pull(b, client, one.accessToken)
            try expect(b.tasks.first { $0.id == task.id }?.text == "设备 A 的离线修改", "explicit conflict choice round trip")
            b.deleteTask(task.id)
            try await push(b, client, one.accessToken)
            try await pull(a, client, one.accessToken)
            try expect(!a.tasks.contains { $0.id == task.id }, "tombstone round trip")
            let isolated = try await client.pull(after: 0, accessToken: two.accessToken)
            try expect(isolated.commits.isEmpty && isolated.cursor == 0, "account isolation over authenticated HTTP")
            let renewed = try await client.refresh(refreshToken: one.refreshToken)
            try expect(renewed.userID == one.userID, "session refresh identity")
            try await client.logout(accessToken: renewed.accessToken)
            try await client.logout(accessToken: two.accessToken)
            print("PASS: authenticated HTTP; two independent stores; initial merge; date plan; idempotent retry; completion; conflict resolution; tombstone; account isolation; session refresh/logout")
            print("Temporary stores: \(root.path)")
        } catch let failure as Failure {
            print("FAIL: \(failure)"); exit(1)
        } catch {
            // Never dump token-bearing transport context or a raw response.
            print("FAIL: \(String(describing: type(of: error))) — \(error is SyncHTTPError || error is SyncStoreError || error is SyncProtocolError ? error.localizedDescription : "acceptance did not complete")")
            exit(1)
        }
    }
}
