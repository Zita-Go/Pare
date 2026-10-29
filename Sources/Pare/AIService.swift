import Foundation
import Combine
import Security
import PareCore

enum AILocalError: LocalizedError {
    case disabled, busy, dailyLimit, missingKey, keychain(OSStatus)
    var errorDescription: String? {
        switch self {
        case .disabled: return "请先在 AI 设置中开启并配置本机或云端服务。"
        case .busy: return "另一个 AI 请求正在进行，请稍候。"
        case .dailyLimit: return "已达到今天的请求上限，可在 AI 设置中调整。"
        case .missingKey: return "云端服务尚未配置密钥。请在 AI 设置中填写。"
        case .keychain(let status): return "钥匙串操作失败（\(status)），没有保存新的配置。"
        }
    }
}

final class AIService: ObservableObject {
    static let shared = AIService()
    @Published private(set) var configuration: AIConfiguration
    @Published private(set) var isRunning = false
    @Published private(set) var requestCount: Int = 0
    @Published private(set) var configurationRevision: Int = 0
    private let defaults: UserDefaults
    private let client: AIClient
    private let preferenceKey = "pareAIConfiguration"
    private let keychainService = "com.zitago.Pare.ai-api-key"

    init(defaults: UserDefaults = .standard, client: AIClient = AIClient()) {
        self.defaults = defaults; self.client = client
        configuration = defaults.data(forKey: preferenceKey)
            .flatMap { try? JSONDecoder().decode(AIConfiguration.self, from: $0) } ?? AIConfiguration()
        refreshCount()
    }

    func refreshCount() {
        let day = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970
        if defaults.double(forKey: "pareAIUsageDay") != day {
            defaults.set(day, forKey: "pareAIUsageDay")
            defaults.set(0, forKey: "pareAIRequestCount")
        }
        requestCount = defaults.integer(forKey: "pareAIRequestCount")
    }

    func contextOptions() -> AIContextOptions? {
        guard let endpoint = try? configuration.endpoint() else { return nil }
        return AIContextOptions.load(endpoint: endpoint, defaults: defaults)
    }

    func rememberContext(_ options: AIContextOptions) {
        guard let endpoint = try? configuration.endpoint() else { return }
        options.save(endpoint: endpoint, defaults: defaults)
    }

    func forgetContext() {
        guard let endpoint = try? configuration.endpoint() else { return }
        AIContextOptions.forget(endpoint: endpoint, defaults: defaults)
    }

    private func keyQuery(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: keychainService, kSecAttrAccount as String: account]
    }

    private func readKey(account: String) throws -> String {
        var query = keyQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess else { throw AILocalError.keychain(status) }
        return (result as? Data).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    // Scope credentials to the endpoint; changing provider never reuses a key.
    func save(_ config: AIConfiguration, newKey: String) throws {
        let endpoint = try config.endpoint()
        guard !config.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              (256...8000).contains(config.maxOutputTokens), (1...200).contains(config.dailyRequestLimit) else {
            throw AIError.invalidConfiguration
        }
        let key = newKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !key.isEmpty {
            let query = keyQuery(endpoint.absoluteString)
            let attributes = [kSecValueData as String: Data(key.utf8)]
            var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if status == errSecItemNotFound {
                var insert = query
                insert[kSecValueData as String] = Data(key.utf8)
                insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                status = SecItemAdd(insert as CFDictionary, nil)
            }
            guard status == errSecSuccess else { throw AILocalError.keychain(status) }
        }
        defaults.set(try JSONEncoder().encode(config), forKey: preferenceKey)
        configuration = config
        configurationRevision += 1
    }

    func deleteKey(for config: AIConfiguration) throws {
        let status = SecItemDelete(keyQuery(try config.endpoint().absoluteString) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw AILocalError.keychain(status) }
        configurationRevision += 1
    }

    @MainActor
    func generate(_ input: AIRequest, historyID: UUID = UUID()) async throws -> AIDraft {
        guard configuration.enabled else { throw AILocalError.disabled }
        guard !isRunning else { throw AILocalError.busy }
        let config = configuration
        let endpoint = try config.endpoint()
        _ = try input.data()
        let key = try readKey(account: endpoint.absoluteString)
        let local = ["127.0.0.1", "localhost", "::1"].contains(endpoint.host ?? "")
        guard local || !key.isEmpty else { throw AILocalError.missingKey }
        refreshCount()
        guard requestCount < config.dailyRequestLimit else { throw AILocalError.dailyLimit }
        requestCount += 1
        defaults.set(requestCount, forKey: "pareAIRequestCount")
        isRunning = true
        defer { isRunning = false }
        let history = AIRequestHistoryController.shared
        let started = Date()
        do {
            let result = try await client.generate(config: config, apiKey: key, input: input, trace: { event in
                await history.capture(event, id: historyID, kind: input.workflow.title,
                                      model: config.model, startedAt: started, secret: key)
            })
            try Task.checkCancellation()
            await history.finish(id: historyID, outcome: .succeeded, secret: key)
            return result
        } catch {
            await history.finish(id: historyID, outcome: Self.wasCancelled(error) ? .cancelled : .failed,
                                 error: error, secret: key)
            throw error
        }
    }
}

extension AIService {
    /// New idea/relationship permissions are deliberately separate from the old
    /// notes/history switches, and are scoped to the resolved provider endpoint.
    func intelligenceContextOptions() -> IntelligenceContextOptions? {
        guard let endpoint = try? configuration.endpoint(),
              let data = defaults.data(forKey: "pareIntelligenceContext.v1." + endpoint.absoluteString) else { return nil }
        return try? JSONDecoder().decode(IntelligenceContextOptions.self, from: data)
    }

    func rememberIntelligenceContext(_ options: IntelligenceContextOptions) {
        guard let endpoint = try? configuration.endpoint(), let data = try? JSONEncoder().encode(options) else { return }
        defaults.set(data, forKey: "pareIntelligenceContext.v1." + endpoint.absoluteString)
    }

    func forgetIntelligenceContext() {
        guard let endpoint = try? configuration.endpoint() else { return }
        defaults.removeObject(forKey: "pareIntelligenceContext.v1." + endpoint.absoluteString)
    }

    @MainActor
    func generate(_ input: IntelligenceRequest) async throws -> IntelligenceProposal {
        guard configuration.enabled else { throw AILocalError.disabled }
        guard !isRunning else { throw AILocalError.busy }
        let config = configuration
        let revision = configurationRevision
        let endpoint = try config.endpoint()
        _ = try IntelligenceClient.makeRequest(config: config, apiKey: "", input: input)
        try Task.checkCancellation()
        let key = try readKey(account: endpoint.absoluteString)
        let local = ["127.0.0.1", "localhost", "::1"].contains(endpoint.host ?? "")
        guard local || !key.isEmpty else { throw AILocalError.missingKey }
        refreshCount()
        guard requestCount < config.dailyRequestLimit else { throw AILocalError.dailyLimit }
        requestCount += 1
        defaults.set(requestCount, forKey: "pareAIRequestCount")
        isRunning = true
        defer { isRunning = false }
        let history = AIRequestHistoryController.shared
        let started = Date()
        do {
            let result = try await IntelligenceClient().generate(config: config, apiKey: key, input: input, trace: { event in
                await history.capture(event, id: input.id, kind: input.intent.title,
                                      model: config.model, startedAt: started, secret: key)
            })
            try Task.checkCancellation()
            guard configuration == config, configurationRevision == revision else { throw AIApplyError.stale }
            await history.finish(id: input.id, outcome: .succeeded, secret: key)
            return result
        } catch {
            await history.finish(id: input.id, outcome: Self.wasCancelled(error) ? .cancelled : .failed,
                                 error: error, secret: key)
            throw error
        }
    }

    private static func wasCancelled(_ error: Error) -> Bool {
        Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
    }
}
