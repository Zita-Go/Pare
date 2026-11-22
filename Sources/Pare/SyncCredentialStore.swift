import Foundation
import Security
import PareCore

/// Sync login is separate from both model credentials and the portable store.
final class SyncCredentialStore {
    private let account: String
    init(projectURL: URL) { account = projectURL.absoluteString }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.zitago.Pare.sync-session",
         kSecAttrAccount as String: account]
    }

    func load() throws -> SyncAuthSession? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw SyncCredentialError.unavailable }
        do { return try JSONDecoder().decode(SyncAuthSession.self, from: data) }
        catch { throw SyncCredentialError.unavailable }
    }

    func save(_ session: SyncAuthSession) throws {
        let data = try JSONEncoder().encode(session)
        let values = [kSecValueData as String: data]
        var status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(insert as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw SyncCredentialError.unavailable }
    }

    func remove() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SyncCredentialError.unavailable }
    }
}

enum SyncCredentialError: LocalizedError {
    case unavailable, accountMismatch, missingConfiguration, missingSession
    var errorDescription: String? {
        switch self {
        case .unavailable: return "无法读写同步登录凭据，请解锁系统钥匙串后重试。"
        case .accountMismatch: return "这个资料库已连接另一个账号。为避免混入其他账号的数据，暂未切换。"
        case .missingConfiguration: return "这个构建未配置云同步服务。请使用带同步配置的测试版本。"
        case .missingSession: return "请重新登录后继续同步。本机内容和待同步修改仍然保留。"
        }
    }
}

enum SyncAppConfiguration {
    private struct File: Decodable {
        let apiURL: URL
        let publishableKey: String
        enum CodingKeys: String, CodingKey { case apiURL = "api_url", publishableKey = "publishable_key" }
    }
    static func load() throws -> SyncHTTPConfiguration {
        // Explicit override is for isolated development builds, never a search
        // through the user's filesystem or a fallback to a model API endpoint.
        let override = ProcessInfo.processInfo.environment["PARE_SYNC_CONFIGURATION_FILE"]
        guard let url = override.map({ URL(fileURLWithPath: $0) })
                ?? Bundle.main.url(forResource: "SyncConfiguration", withExtension: "json") else {
            throw SyncCredentialError.missingConfiguration
        }
        let data = try Data(contentsOf: url)
        guard data.count < 16_384 else { throw SyncCredentialError.missingConfiguration }
        let value = try JSONDecoder().decode(File.self, from: data)
        return try SyncHTTPConfiguration(projectURL: value.apiURL, publishableKey: value.publishableKey)
    }
}
