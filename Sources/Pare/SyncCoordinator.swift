import AppKit
import Combine
import PareCore

/// One serial network operation owns the session at a time. The Store owns all
/// durable sync state; a process exit can only leave an identical retry behind.
final class SyncCoordinator: ObservableObject {
    @Published private(set) var email: String?
    @Published private(set) var isBusy = false
    @Published private(set) var status = "仅保存在本机"
    @Published private(set) var errorMessage: String?
    @Published private(set) var lastSyncedAt: Date?
    @Published private(set) var isConfigured = false
    @Published private(set) var isSignedIn = false

    let store: Store
    private var client: SyncHTTPClient?
    private var credentials: SyncCredentialStore?
    private var session: SyncAuthSession?
    private var subscriptions = Set<AnyCancellable>()
    private var timer: AnyCancellable?

    init(store: Store) {
        self.store = store
        do {
            let config = try SyncAppConfiguration.load()
            client = SyncHTTPClient(configuration: config)
            credentials = SyncCredentialStore(projectURL: config.projectURL)
            isConfigured = true
        } catch { status = "当前构建未配置云同步" }
    }

    func start() {
        guard isConfigured else { return }
        store.objectWillChange
            .debounce(for: .seconds(2), scheduler: RunLoop.main)
            .sink { [weak self] _ in
                guard let self, self.isSignedIn,
                      self.store.syncState?.enabled == true,
                      self.store.syncState?.mergeConfirmed == true,
                      self.store.pendingSyncChangeCount > 0,
                      self.store.syncState?.conflicts.isEmpty == true else { return }
                self.syncNow()
            }.store(in: &subscriptions)
        timer = Timer.publish(every: 60, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self, self.isSignedIn, self.store.syncState?.enabled == true else { return }
            self.syncNow()
        }
        NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in
                guard let self, self.isSignedIn, self.store.syncState?.enabled == true else { return }
                self.syncNow()
            }.store(in: &subscriptions)
        // A saved login alone never opts an unconnected store into uploading.
        guard store.syncState?.enabled == true else { return }
        do {
            guard let saved = try credentials?.load() else { throw SyncCredentialError.missingSession }
            try checkAccount(saved)
            acceptInMemory(saved)
            syncNow()
        } catch { show(error) }
    }

    func authenticate(email: String, password: String, createAccount: Bool) {
        guard !isBusy, let client else { return }
        isBusy = true; errorMessage = nil; status = "正在登录…"
        Task { @MainActor in
            defer { isBusy = false }
            do {
                let result: SyncAuthSession?
                if createAccount { result = try await client.signUp(email: email, password: password) }
                else { result = try await client.signIn(email: email, password: password) }
                guard let result else {
                    status = "请先打开确认邮件，完成验证后再登录。"
                    return
                }
                try checkAccount(result)
                try credentials?.save(result)
                try store.bindSyncAccount(accountID: result.userID.uuidString.lowercased())
                acceptInMemory(result)
                try await exchange()
            } catch { show(error) }
        }
    }

    func syncNow() {
        guard !isBusy, isSignedIn, store.syncState?.enabled == true else { return }
        isBusy = true; errorMessage = nil
        Task { @MainActor in
            defer { isBusy = false }
            do { try await exchange() }
            catch { show(error) }
        }
    }

    func confirmMerge() {
        guard !isBusy else { return }
        do { try store.confirmSyncMerge(); syncNow() }
        catch { show(error) }
    }

    func resumeAfterRestore() {
        guard !isBusy, let session else { return }
        do {
            try checkAccount(session)
            try store.bindSyncAccount(accountID: session.userID.uuidString.lowercased())
            syncNow()
        } catch { show(error) }
    }

    func resolve(_ key: SyncEntityKey, useCloud: Bool) {
        guard !isBusy else { return }
        do {
            try store.resolveSyncConflict(key: key, choice: useCloud ? .cloud : .local)
            if store.syncState?.conflicts.isEmpty == true { syncNow() }
        } catch { show(error) }
    }

    func signOut() {
        guard !isBusy else { return }
        do {
            // Persist the off switch before deleting credentials or making a
            // remote request. Offline sign-out still stops future uploads.
            try store.suspendSync()
            try credentials?.remove()
        } catch { show(error); return }
        let token = session?.accessToken
        session = nil; email = nil; isSignedIn = false
        errorMessage = nil; status = "已退出，本机内容与待同步修改已保留"
        if let token, let client {
            Task { try? await client.logout(accessToken: token) }
        }
    }

    private func checkAccount(_ value: SyncAuthSession) throws {
        if let account = store.syncState?.accountID,
           account.lowercased() != value.userID.uuidString.lowercased() {
            throw SyncCredentialError.accountMismatch
        }
    }

    private func acceptInMemory(_ value: SyncAuthSession) {
        session = value; email = value.email; isSignedIn = true
    }

    @MainActor
    private func accessToken() async throws -> String {
        guard let current = session, let client else { throw SyncCredentialError.missingSession }
        try checkAccount(current)
        if current.expiresAt.timeIntervalSinceNow > 90 { return current.accessToken }
        let renewed = try await client.refresh(refreshToken: current.refreshToken)
        guard renewed.userID == current.userID else { throw SyncCredentialError.accountMismatch }
        try credentials?.save(renewed)
        acceptInMemory(renewed)
        return renewed.accessToken
    }

    @MainActor
    private func pullAll() async throws -> Bool {
        guard let client else { throw SyncCredentialError.missingConfiguration }
        var pages = 0
        while store.syncState?.enabled == true {
            let token = try await accessToken()
            let page = try await client.pull(after: store.syncState?.cursor ?? 0, accessToken: token)
            try store.receiveSyncPullResponse(page)
            if !page.hasMore { return true }
            pages += 1
            // Yield to AppKit between pages; persistence is atomic per page.
            await Task.yield()
            if pages >= 100 { status = "正在分批同步，稍后继续"; return false }
        }
        return false
    }

    @MainActor
    private func exchange() async throws {
        guard let client, store.syncState?.enabled == true else { return }
        status = "正在同步…"
        // An outstanding immutable request must be retried before pulling its
        // own commit. The Store also recognizes its operation ID during pull.
        if let inflight = store.syncState?.inflight {
            let reply = try await client.push(inflight, accessToken: try await accessToken())
            try store.receiveSyncPushResponse(reply, for: inflight)
        }
        guard try await pullAll() else { return }
        guard store.syncState?.initialPullComplete == true else { return }
        guard store.syncState?.mergeConfirmed == true else {
            status = "合并预览已准备好，确认后开始同步"
            return
        }
        guard store.syncState?.conflicts.isEmpty == true else {
            status = "有两端修改需要你选择"
            return
        }
        var batches = 0
        while let request = try store.nextSyncPushRequest() {
            let reply = try await client.push(request, accessToken: try await accessToken())
            try store.receiveSyncPushResponse(reply, for: request)
            if reply.status == .conflict { status = "有两端修改需要你选择"; return }
            batches += 1
            if batches >= 100 { status = "正在分批同步，稍后继续"; return }
            await Task.yield()
        }
        guard try await pullAll() else { return }
        if store.syncState?.conflicts.isEmpty == false { status = "有两端修改需要你选择" }
        else if store.pendingSyncChangeCount > 0 { status = "有修改等待下一轮同步" }
        else { lastSyncedAt = Date(); status = "已同步" }
    }

    private func show(_ error: Error) {
        status = "同步暂未完成，本机修改已保留"
        if let failure = error as? SyncHTTPError, failure == .unauthorized || failure == .invalidToken {
            session = nil; isSignedIn = false; email = nil
            try? credentials?.remove()
        }
        if error is SyncHTTPError || error is SyncCredentialError || error is SyncStoreError || error is SyncProtocolError {
            errorMessage = error.localizedDescription
        } else {
            // Do not forward server response text, request URLs or raw values.
            errorMessage = "连接或保存未完成，请稍后重试。"
        }
    }
}
