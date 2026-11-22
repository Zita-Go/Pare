import Foundation
import Combine
import PareCore

/// Raw history is independent from task backups and model context retrieval.
/// Only this controller hands sanitized strings across the disk boundary.
final class AIRequestHistoryController: ObservableObject {
    static let shared = AIRequestHistoryController()
    @Published private(set) var records: [AIRequestRecord] = []
    @Published private(set) var persistenceIssue: String?
    @Published private(set) var loaded = false
    private let archive: AIRequestHistoryArchive
    private var loading: Task<Void, Never>?
    private var deletedIDs = Set<UUID>()
    private var unsavedIDs = Set<UUID>()
    private var loadIssue: String?

    init(directory: URL? = nil) {
        let location: URL
        if let directory { location = directory }
        else if Bundle.main.bundleIdentifier == "com.zitago.Pare" {
            location = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Pare/AIRequestHistory", isDirectory: true)
        } else {
            // Demo and CLI builds must never write the production request history.
            location = FileManager.default.temporaryDirectory
                .appendingPathComponent("pare-ai-history-demo-\(UUID().uuidString)", isDirectory: true)
        }
        archive = AIRequestHistoryArchive(directory: location)
    }

    @MainActor func ensureLoaded() async {
        if loaded { return }
        if let loading { await loading.value; return }
        let task = Task { @MainActor in
            do { records = try await archive.load(recoverInterrupted: true) }
            catch { loadIssue = "部分请求历史无法读取，原文件已保留。新的记录仍会尝试保存。" }
            loaded = true
            updateIssue()
        }
        loading = task
        await task.value
        loading = nil
    }

    @MainActor func capture(_ event: AIRequestTraceEvent, id: UUID, kind: String,
                            model: String, startedAt: Date, secret: String) async {
        await ensureLoaded()
        guard !deletedIDs.contains(id) else { return }
        let secrets = secret.isEmpty ? [] : [secret]
        switch event {
        case let .request(body, endpoint):
            let safeBody = AITraceRedactor.redact(body, secrets: secrets)
            let record = AIRequestRecord(id: id, startedAt: startedAt, finishedAt: nil,
                kind: AITraceRedactor.redact(kind, secrets: secrets),
                model: AITraceRedactor.redact(model, secrets: secrets),
                endpoint: AITraceRedactor.redact(endpoint, secrets: secrets),
                requestBody: safeBody, responseBody: nil, httpStatus: nil, outcome: .running,
                errorMessage: nil, requestRedacted: safeBody != body,
                responseRedacted: false, responseTruncated: false)
            await persist(record)
        case let .response(body, statusCode, truncated):
            guard var record = records.first(where: { $0.id == id }) else { return }
            record.responseBody = AITraceRedactor.redact(body, secrets: secrets, truncated: truncated)
            record.responseRedacted = record.responseBody != body
            record.responseTruncated = truncated
            record.httpStatus = statusCode
            await persist(record)
        }
    }

    @MainActor func finish(id: UUID, outcome: AIRequestOutcome, error: Error? = nil, secret: String) async {
        guard !deletedIDs.contains(id), var record = records.first(where: { $0.id == id }) else { return }
        record.finishedAt = Date()
        record.outcome = outcome
        record.errorMessage = error.map { AITraceRedactor.redact($0.localizedDescription, secrets: secret.isEmpty ? [] : [secret]) }
        await persist(record)
    }

    @MainActor private func persist(_ record: AIRequestRecord) async {
        guard !deletedIDs.contains(record.id) else { return }
        records.removeAll { $0.id == record.id }
        records.append(record)
        records.sort { $0.startedAt > $1.startedAt }
        do {
            try await archive.upsert(record)
            unsavedIDs.remove(record.id)
            // Reflect archive retention rather than showing entries already pruned.
            let disk = try await archive.load(recoverInterrupted: false)
            let memoryOnly = records.filter { unsavedIDs.contains($0.id) && !deletedIDs.contains($0.id) }
            records = (disk.filter { !unsavedIDs.contains($0.id) && !deletedIDs.contains($0.id) } + memoryOnly)
                .sorted { $0.startedAt > $1.startedAt }
        } catch {
            unsavedIDs.insert(record.id)
        }
        updateIssue()
    }

    @MainActor func remove(_ id: UUID) async -> Bool {
        deletedIDs.insert(id) // A finishing network callback cannot resurrect it.
        do {
            try await archive.remove(id)
            records.removeAll { $0.id == id }; unsavedIDs.remove(id); updateIssue()
            return true
        } catch {
            deletedIDs.remove(id)
            persistenceIssue = "删除未完成，记录仍保留。请检查磁盘权限后重试。"
            return false
        }
    }

    @MainActor func removeAll() async -> Bool {
        let ids = Set(records.map(\.id))
        let memoryOnly = records.filter { unsavedIDs.contains($0.id) }
        deletedIDs.formUnion(ids)
        do {
            try await archive.removeAll()
            records.removeAll(); unsavedIDs.removeAll(); loadIssue = nil; updateIssue()
            return true
        } catch {
            deletedIDs.subtract(ids)
            // Re-read after a partial deletion; never claim all entries were removed.
            if let remaining = try? await archive.load(recoverInterrupted: false) {
                let memoryIDs = Set(memoryOnly.map(\.id))
                records = (remaining.filter { !memoryIDs.contains($0.id) } + memoryOnly)
                    .sorted { $0.startedAt > $1.startedAt }
            }
            persistenceIssue = "未能删除全部记录，请检查磁盘权限后重试。"
            return false
        }
    }

    @MainActor private func updateIssue() {
        persistenceIssue = !unsavedIDs.isEmpty
            ? "部分请求记录未保存到磁盘。当前内容仍可查看、复制，请在退出 App 前处理。"
            : loadIssue
    }
}
