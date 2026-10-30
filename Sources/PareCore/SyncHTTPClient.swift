import Foundation

/// Only a project origin and a public client key belong in app configuration.
public struct SyncHTTPConfiguration: Sendable, CustomStringConvertible {
    public let projectURL: URL
    public let publishableKey: String

    public init(projectURL: URL, publishableKey: String) throws {
        guard let parts = URLComponents(url: projectURL, resolvingAgainstBaseURL: false),
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/",
              parts.scheme == "https",
              parts.port.map({ (1...65535).contains($0) }) ?? true,
              Self.isPublicKey(publishableKey) else { throw SyncHTTPError.invalidConfiguration }
        self.projectURL = projectURL
        self.publishableKey = publishableKey
    }

    private static func isPublicKey(_ key: String) -> Bool {
        guard key.utf8.count <= 8192, !key.isEmpty,
              key.utf8.allSatisfy({ (45...46).contains($0) || (48...57).contains($0) ||
                  (65...90).contains($0) || $0 == 95 || (97...122).contains($0) }) else { return false }
        if key.hasPrefix("sb_publishable_") { return key.count > "sb_publishable_".count }
        let segments = key.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3, segments.allSatisfy({ !$0.isEmpty }) else { return false }
        var base64 = String(segments[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        // This is only a key-type guard. The server verifies the JWT signature.
        return payload["role"] as? String == "anon"
    }

    public var description: String { "SyncHTTPConfiguration(key: <redacted>)" }
}

/// Persist this in OS credential storage, never in a task backup or settings JSON.
public struct SyncAuthSession: Codable, Equatable, Sendable, CustomStringConvertible {
    public let accessToken: String
    public let refreshToken: String
    public let expiresAt: Date
    public let userID: UUID
    public let email: String?

    public init(accessToken: String, refreshToken: String, expiresAt: Date, userID: UUID, email: String?) {
        self.accessToken = accessToken; self.refreshToken = refreshToken
        self.expiresAt = expiresAt; self.userID = userID; self.email = email
    }

    public var description: String { "SyncAuthSession(credentials: <redacted>)" }
}

/// Error cases intentionally contain no server text, request URLs, or underlying errors.
public enum SyncHTTPError: Error, Equatable, Sendable, LocalizedError {
    case invalidConfiguration, invalidCredentials, invalidToken, invalidRequest
    case unauthorized, forbidden, rateLimited, serverUnavailable, requestRejected
    case redirectRejected, invalidResponse, responseTooLarge, timedOut, networkUnavailable

    public var errorDescription: String? {
        switch self {
        case .invalidConfiguration: return "同步服务配置无效，请检查项目地址和公开客户端密钥。"
        case .invalidCredentials: return "请输入有效的邮箱和密码。"
        case .invalidToken: return "登录凭据无效，请重新登录。"
        case .invalidRequest: return "同步请求无效，尚未发送。"
        case .unauthorized: return "登录已失效或凭据不正确，请重新登录。"
        case .forbidden: return "当前账户无权执行此操作。"
        case .rateLimited: return "请求过于频繁，请稍后重试。"
        case .serverUnavailable: return "同步服务暂时不可用，请稍后重试。"
        case .requestRejected: return "服务未接受此请求，请检查登录信息或稍后重试。"
        case .redirectRejected: return "同步服务返回了不允许的重定向。"
        case .invalidResponse: return "同步服务返回的数据无效。"
        case .responseTooLarge: return "同步响应过大，未导入任何数据。"
        case .timedOut: return "同步请求超时，请稍后重试。"
        case .networkUnavailable: return "无法连接同步服务，请检查网络后重试。"
        }
    }
}

/// Stateless HTTP boundary. The caller owns credential persistence, refresh
/// serialization and retry policy, including retaining a push's operation ID.
/// All stored state is immutable; each request owns an independent transfer.
public final class SyncHTTPClient: @unchecked Sendable {
    private let configuration: SyncHTTPConfiguration
    private let sessionConfiguration: URLSessionConfiguration
    private let now: @Sendable () -> Date

    public convenience init(configuration: SyncHTTPConfiguration) {
        self.init(configuration: configuration, sessionConfiguration: .ephemeral, now: { Date() })
    }

    init(configuration: SyncHTTPConfiguration, sessionConfiguration: URLSessionConfiguration,
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.configuration = configuration
        let transport = sessionConfiguration.copy() as! URLSessionConfiguration
        transport.timeoutIntervalForRequest = 30
        transport.timeoutIntervalForResource = 30
        transport.requestCachePolicy = .reloadIgnoringLocalCacheData
        transport.urlCache = nil
        transport.httpCookieStorage = nil
        transport.httpShouldSetCookies = false
        transport.urlCredentialStorage = nil
        transport.httpAdditionalHeaders = nil
        transport.waitsForConnectivity = false
        self.sessionConfiguration = transport
        self.now = now
    }

    public func signIn(email: String, password: String) async throws -> SyncAuthSession {
        let body = try credentials(email: email, password: password)
        let data = try await send(path: "/auth/v1/token", query: "grant_type=password", body: body)
        return try parseSession(data)
    }

    /// Nil means signup succeeded without a session: confirm email, then sign in.
    public func signUp(email: String, password: String) async throws -> SyncAuthSession? {
        let body = try credentials(email: email, password: password)
        let data = try await send(path: "/auth/v1/signup", body: body)
        do {
            let response = try JSONDecoder().decode(AuthResponse.self, from: data)
            if response.accessToken != nil || response.refreshToken != nil {
                return try response.session(now: now())
            }
            // The Auth REST endpoint returns a user directly when confirmation is required.
            // Do not treat a malformed or partial token response as a successful signup.
            guard response.expiresIn == nil, response.expiresAt == nil, response.tokenType == nil else {
                throw SyncHTTPError.invalidResponse
            }
            _ = try JSONDecoder().decode(AuthUser.self, from: data)
            return nil
        } catch { throw SyncHTTPError.invalidResponse }
    }

    public func refresh(refreshToken: String) async throws -> SyncAuthSession {
        try validateToken(refreshToken)
        let body = try JSONEncoder().encode(["refresh_token": refreshToken])
        let data = try await send(path: "/auth/v1/token", query: "grant_type=refresh_token", body: body)
        return try parseSession(data)
    }

    public func logout(accessToken: String) async throws {
        _ = try await send(path: "/auth/v1/logout", query: "scope=local", body: Data("{}".utf8), accessToken: accessToken)
    }

    public func pull(after: Int64, accessToken: String) async throws -> SyncPullResponse {
        guard after >= 0 else { throw SyncHTTPError.invalidRequest }
        // A commit can be up to 1 MiB; one complete commit leaves response headroom.
        let body = try JSONEncoder().encode(["p_after": after, "p_limit": 1])
        let data = try await send(path: "/rest/v1/rpc/pare_sync_pull", body: body, accessToken: accessToken)
        do {
            let response = try SyncWire.decoder().decode(SyncPullResponse.self, from: data)
            try response.validate(after: after)
            return response
        } catch { throw SyncHTTPError.invalidResponse }
    }

    public func push(_ request: SyncPushRequest, accessToken: String) async throws -> SyncPushResponse {
        let body: Data
        do { body = try request.data() } catch { throw SyncHTTPError.invalidRequest }
        let data = try await send(path: "/rest/v1/rpc/pare_sync_apply", body: body, accessToken: accessToken)
        do {
            let response = try SyncWire.decoder().decode(SyncPushResponse.self, from: data)
            try response.validate(for: request)
            return response
        } catch { throw SyncHTTPError.invalidResponse }
    }

    private func credentials(email: String, password: String) throws -> Data {
        let email = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = email.split(separator: "@", omittingEmptySubsequences: false)
        guard email.utf8.count <= 254, parts.count == 2, parts.allSatisfy({ !$0.isEmpty }),
              email.unicodeScalars.allSatisfy({ !CharacterSet.whitespacesAndNewlines.contains($0) && !CharacterSet.controlCharacters.contains($0) }),
              !password.isEmpty, password.utf8.count <= 4096, !password.contains("\0") else {
            throw SyncHTTPError.invalidCredentials
        }
        return try JSONEncoder().encode(["email": email, "password": password])
    }

    private func parseSession(_ data: Data) throws -> SyncAuthSession {
        do { return try JSONDecoder().decode(AuthResponse.self, from: data).session(now: now()) }
        catch { throw SyncHTTPError.invalidResponse }
    }

    private func send(path: String, query: String? = nil, body: Data, accessToken: String? = nil) async throws -> Data {
        try Task.checkCancellation()
        if let accessToken { try validateToken(accessToken) }
        guard var components = URLComponents(url: configuration.projectURL, resolvingAgainstBaseURL: false) else {
            throw SyncHTTPError.invalidConfiguration
        }
        components.path = path
        components.percentEncodedQuery = query
        guard let url = components.url else { throw SyncHTTPError.invalidConfiguration }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(configuration.publishableKey, forHTTPHeaderField: "apikey")
        if let accessToken { request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization") }
        let result = try await SyncHTTPTransfer().perform(request: request, configuration: sessionConfiguration)
        switch result.status {
        case 200..<300: return result.data
        case 300..<400: throw SyncHTTPError.redirectRejected
        case 401: throw SyncHTTPError.unauthorized
        case 403: throw SyncHTTPError.forbidden
        case 429: throw SyncHTTPError.rateLimited
        case 500..<600: throw SyncHTTPError.serverUnavailable
        default: throw SyncHTTPError.requestRejected
        }
    }
}

private func validateToken(_ token: String) throws {
    guard !token.isEmpty, token.utf8.count <= 32768,
          token.utf8.allSatisfy({ (33...126).contains($0) }) else { throw SyncHTTPError.invalidToken }
}

private struct AuthUser: Decodable {
    var id: UUID
    var email: String?
}

private struct AuthResponse: Decodable {
    var accessToken: String?
    var refreshToken: String?
    var tokenType: String?
    var expiresIn: Double?
    var expiresAt: Double?
    var user: AuthUser?
    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token", refreshToken = "refresh_token", tokenType = "token_type"
        case expiresIn = "expires_in", expiresAt = "expires_at", user
    }

    func session(now: Date) throws -> SyncAuthSession {
        guard let accessToken, let refreshToken, let user,
              tokenType?.lowercased() == "bearer" else { throw SyncHTTPError.invalidResponse }
        try validateToken(accessToken); try validateToken(refreshToken)
        let expiry: Double
        if let expiresAt { expiry = expiresAt }
        else if let expiresIn, expiresIn > 0 { expiry = now.timeIntervalSince1970 + expiresIn }
        else { throw SyncHTTPError.invalidResponse }
        guard expiry.isFinite, expiry > now.timeIntervalSince1970,
              expiry <= Date.distantFuture.timeIntervalSince1970 else { throw SyncHTTPError.invalidResponse }
        return SyncAuthSession(accessToken: accessToken, refreshToken: refreshToken,
            expiresAt: Date(timeIntervalSince1970: expiry), userID: user.id, email: user.email)
    }
}

/// Each transfer has a private ephemeral session and serial delegate callbacks.
/// The lock additionally synchronizes Swift task cancellation with those callbacks.
private final class SyncHTTPTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    struct Response { var status: Int; var data: Data }
    private let lock = NSLock()
    private let maximumBytes = 8 * 1024 * 1024
    private var continuation: CheckedContinuation<Response, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var response: HTTPURLResponse?
    private var data = Data()
    private var cancelled = false
    private var finished = false

    func perform(request: URLRequest, configuration: URLSessionConfiguration) async throws -> Response {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if cancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                self.session = session
                let task = session.dataTask(with: request)
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: { self.cancel() }
    }

    private func cancel() {
        lock.lock()
        cancelled = true
        let started = continuation != nil
        lock.unlock()
        if started { finish(.failure(CancellationError())) }
    }

    private func finish(_ result: Result<Response, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let continuation = self.continuation
        let session = self.session
        self.continuation = nil; self.session = nil; self.task = nil
        data = Data(); response = nil
        lock.unlock()
        session?.invalidateAndCancel()
        continuation?.resume(with: result)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Deny even same-origin redirects: auth and mutation bodies must never be replayed.
        completionHandler(nil)
        finish(.failure(SyncHTTPError.redirectRejected))
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let response = response as? HTTPURLResponse else {
            completionHandler(.cancel); finish(.failure(SyncHTTPError.invalidResponse)); return
        }
        guard response.expectedContentLength <= maximumBytes else {
            completionHandler(.cancel); finish(.failure(SyncHTTPError.responseTooLarge)); return
        }
        lock.lock()
        let finished = self.finished
        if !finished { self.response = response }
        lock.unlock()
        completionHandler(finished ? .cancel : .allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        guard chunk.count <= maximumBytes - data.count else {
            lock.unlock(); finish(.failure(SyncHTTPError.responseTooLarge)); return
        }
        data.append(chunk)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            let safe: Error
            if let urlError = error as? URLError, urlError.code == .cancelled { safe = CancellationError() }
            else if let urlError = error as? URLError, urlError.code == .timedOut { safe = SyncHTTPError.timedOut }
            else { safe = SyncHTTPError.networkUnavailable }
            finish(.failure(safe))
            return
        }
        lock.lock()
        let response = self.response.map { Response(status: $0.statusCode, data: data) }
        lock.unlock()
        if let response { finish(.success(response)) }
        else { finish(.failure(SyncHTTPError.invalidResponse)) }
    }
}
