import AppKit
import Combine
import PareCore

/// Content operations own credential refresh serially. Presence uses a captured access token
/// and an independent cancellation fence, so disabling sharing never cancels content sync.
/// The Store owns durable content state; process exit leaves an identical retry behind.
final class SyncCoordinator: ObservableObject {
    @Published private(set) var email: String?
    @Published private(set) var isBusy = false
    @Published private(set) var status = "仅保存在本机"
    @Published private(set) var errorMessage: String?
    @Published private(set) var lastSyncedAt: Date?
    @Published private(set) var isConfigured = false
    @Published private(set) var isSignedIn = false
    @Published private(set) var remotePresenceDevices: [ExecutionPresenceDevice] = []
    @Published private(set) var presenceStatus = "运行状态共享默认关闭；内容同步不受影响"
    @Published private(set) var presenceError: String?
    @Published private(set) var presenceLastUpdatedAt: Date?
    @Published private(set) var presenceOmittedCount = 0
    @Published private(set) var presenceSharingEnabled = false
    @Published private(set) var isPresenceBusy = false

    let store: Store
    private var client: SyncHTTPClient?
    private var credentials: SyncCredentialStore?
    private var session: SyncAuthSession?
    private var subscriptions = Set<AnyCancellable>()
    private var timer: AnyCancellable?
    private var presenceTimer: AnyCancellable?
    private var operation: Task<Void, Never>?
    private var generation: UInt64 = 0
    private let presenceSequences = ExecutionPresenceSequenceStore()
    private let presencePreferences = ExecutionPresencePreferences()
    private var presenceOperation: Task<Void, Never>?
    private var presenceGeneration: UInt64 = 0
    private var presenceSnapshot: ExecutionPresenceSnapshot?
    private struct PresenceContext: Equatable { let accountID: UUID; let deviceID: UUID }
    private var presenceContext: PresenceContext?

    /// Advisory only: this does not stop, take over, or change another device's timer.
    var hasOtherActiveFocus: Bool {
        remotePresenceDevices.contains { isPresenceFresh($0) && $0.runs.contains { $0.kind == .personal && $0.state == .running } }
    }
    func isPresenceFresh(_ device: ExecutionPresenceDevice) -> Bool {
        guard let presenceContext, presenceContext == eligiblePresenceContext() else { return false }
        return presenceSnapshot?.isFresh(device) == true
    }
    func hasOtherActiveRun(taskID: UUID) -> Bool {
        remotePresenceDevices.contains { isPresenceFresh($0) && $0.runs.contains { $0.taskID == taskID && $0.state == .running } }
    }

    /// Called synchronously by the local start guard. It never sends requests or controls a remote run.
    /// Cancel is the default action so keyboard confirmation cannot accidentally start a second focus.
    func confirmOtherFocus(taskID: UUID) -> Bool {
        let peers = remotePresenceDevices.filter { device in
            isPresenceFresh(device) && device.runs.contains {
                $0.state == .running && ($0.kind == .personal || $0.taskID == taskID)
            }
        }
        guard !peers.isEmpty else { return true }
        guard Thread.isMainThread else { return false }
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "其他设备还有进行中的记录"
        let details = peers.flatMap { device in
            device.runs.filter { $0.state == .running && ($0.kind == .personal || $0.taskID == taskID) }.map { run in
                let title = (store.tasks + store.archive).first { $0.id == run.taskID }?.text ?? "尚未同步到本机的任务"
                return "\(device.label) · \(run.kind == .personal ? "自己专注" : "等待结果") · \(title)"
            }
        }.prefix(5).joined(separator: "\n")
        alert.informativeText = details + "\n\n请先确认那边是否已经结束。继续只会在这台 Mac 开始专注，其他设备上的记录仍会保留。"
        alert.addButton(withTitle: "先不开始")
        alert.addButton(withTitle: "仍在这台 Mac 开始")
        return alert.runModal() == .alertSecondButtonReturn
    }

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
        presenceTimer = Timer.publish(every: 15, on: .main, in: .common).autoconnect().sink { [weak self] _ in self?.refreshPresence() }
        store.objectWillChange.debounce(for: .seconds(1), scheduler: RunLoop.main)
            .sink { [weak self] _ in self?.refreshPresence() }.store(in: &subscriptions)
        store.$syncState.receive(on: RunLoop.main).sink { [weak self] _ in self?.reconcilePresenceAuthorization() }.store(in: &subscriptions)
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
        cancelPresenceForContentSync()
        isBusy = true; errorMessage = nil; status = "正在登录…"
        let fence = generation
        operation = Task { @MainActor in
            defer { if generation == fence { isBusy = false; operation = nil } }
            do {
                let result: SyncAuthSession?
                if createAccount { result = try await client.signUp(email: email, password: password) }
                else { result = try await client.signIn(email: email, password: password) }
                guard generation == fence, !Task.isCancelled else { return }
                guard let result else {
                    status = "请先打开确认邮件，完成验证后再登录。"
                    return
                }
                try checkAccount(result)
                try credentials?.save(result)
                try store.bindSyncAccount(accountID: result.userID.uuidString.lowercased())
                acceptInMemory(result)
                try await exchange()
            } catch { if generation == fence && !(error is CancellationError) { show(error) } }
        }
    }

    func syncNow() {
        guard !isBusy, isSignedIn, store.syncState?.enabled == true else { return }
        cancelPresenceForContentSync()
        isBusy = true; errorMessage = nil
        let fence = generation
        operation = Task { @MainActor in
            defer { if generation == fence { isBusy = false; operation = nil } }
            do { try await exchange() }
            catch { if generation == fence && !(error is CancellationError) { show(error) } }
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
        let previousContext = presenceContext ?? eligiblePresenceContext()
        let token = session?.accessToken
        let clear = previousContext.flatMap { emptyPresenceRequest($0) }
        invalidateSyncOperation(); invalidatePresence()
        do {
            // Persist the off switch before deleting credentials or making a
            // remote request. Offline sign-out still stops future uploads.
            try store.suspendSync()
            try credentials?.remove()
        } catch { show(error); return }
        session = nil; email = nil; isSignedIn = false; presenceSharingEnabled = false
        errorMessage = nil; status = "已退出，本机内容与待同步修改已保留"
        if let token, let client {
            Task {
                if let clear { _ = try? await client.exchangePresence(clear, accessToken: token) }
                try? await client.logout(accessToken: token)
            }
        }
    }

    func pauseSync() {
        let previous = presenceContext ?? eligiblePresenceContext()
        let token = session?.accessToken
        let clear = previous.flatMap { emptyPresenceRequest($0) }
        invalidateSyncOperation(); invalidatePresence()
        do { try store.suspendSync(); errorMessage = nil; status = "已暂停同步，本机修改会保留" }
        catch { show(error); return }
        if let token, let client, let clear {
            Task { _ = try? await client.exchangePresence(clear, accessToken: token) }
        }
    }

    private func checkAccount(_ value: SyncAuthSession) throws {
        if let account = store.syncState?.accountID,
           account.lowercased() != value.userID.uuidString.lowercased() {
            throw SyncCredentialError.accountMismatch
        }
    }

    private func acceptInMemory(_ value: SyncAuthSession) {
        if session?.userID != nil && session?.userID != value.userID { invalidatePresence() }
        session = value; email = value.email; isSignedIn = true
        updatePresencePreference()
    }

    @MainActor
    private func accessToken() async throws -> String {
        try Task.checkCancellation()
        guard let current = session, let client else { throw SyncCredentialError.missingSession }
        let fence = generation
        try checkAccount(current)
        if current.expiresAt.timeIntervalSinceNow > 90 { return current.accessToken }
        let renewed = try await client.refresh(refreshToken: current.refreshToken)
        try validateFence(fence, account: current.userID)
        guard renewed.userID == current.userID else { throw SyncCredentialError.accountMismatch }
        try credentials?.save(renewed)
        acceptInMemory(renewed)
        return renewed.accessToken
    }

    @MainActor
    private func pullAll() async throws -> Bool {
        guard let client else { throw SyncCredentialError.missingConfiguration }
        let fence = generation
        guard let account = session?.userID else { throw SyncCredentialError.missingSession }
        var pages = 0
        while store.syncState?.enabled == true {
            let token = try await accessToken()
            let page = try await client.pull(after: store.syncState?.cursor ?? 0, accessToken: token)
            try validateFence(fence, account: account)
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
        let fence = generation
        guard let account = session?.userID else { throw SyncCredentialError.missingSession }
        status = "正在同步…"
        // An outstanding immutable request must be retried before pulling its
        // own commit. The Store also recognizes its operation ID during pull.
        if let inflight = store.syncState?.inflight {
            let reply = try await client.push(inflight, accessToken: try await accessToken())
            try validateFence(fence, account: account)
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
            try validateFence(fence, account: account)
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
        // Start a separate presence task after this MainActor content operation returns. Turning
        // sharing off can then cancel presence without cancelling content persistence or sync.
        startPresenceRefresh(allowContentCompletion: true)
    }

    private func eligiblePresenceContext() -> PresenceContext? {
        guard isSignedIn, let account = session?.userID, let state = store.syncState, state.enabled, state.mergeConfirmed,
              state.accountID.lowercased() == account.uuidString.lowercased(),
              presencePreferences.isEnabled(accountID: account, deviceID: state.deviceID) else { return nil }
        return .init(accountID: account, deviceID: state.deviceID)
    }

    private func preferenceContext() -> PresenceContext? {
        guard isSignedIn, let account = session?.userID, let state = store.syncState,
              state.accountID.lowercased() == account.uuidString.lowercased() else { return nil }
        return .init(accountID: account, deviceID: state.deviceID)
    }

    private func updatePresencePreference() {
        presenceSharingEnabled = preferenceContext().map {
            presencePreferences.isEnabled(accountID: $0.accountID, deviceID: $0.deviceID)
        } ?? false
    }

    /// Disabling only revokes presence. The existing token and a newer sequence are captured
    /// synchronously, before a rapid re-enable can reserve its next running report.
    func setPresenceSharingEnabled(_ enabled: Bool) {
        guard let preference = preferenceContext() else { return }
        let previous = presenceContext ?? eligiblePresenceContext()
        let token = session?.userID == previous?.accountID ? session?.accessToken : nil
        var saveFailure = false
        do { try presencePreferences.setEnabled(enabled, accountID: preference.accountID, deviceID: preference.deviceID) }
        catch { saveFailure = true }
        updatePresencePreference()
        if !presenceSharingEnabled {
            let clear = previous.flatMap { emptyPresenceRequest($0) }
            invalidatePresence()
            if let token, let client, let clear {
                Task { _ = try? await client.exchangePresence(clear, accessToken: token) }
            }
        } else {
            presenceError = nil
            presenceStatus = "已允许本机共享运行摘要；确认内容同步后开始更新"
            refreshPresence()
        }
        if saveFailure { presenceError = "运行状态共享设置未能保存，本次已停止共享；请重试关闭或重新开启。" }
    }

    private func invalidateSyncOperation() {
        generation &+= 1; operation?.cancel(); operation = nil; isBusy = false
    }

    /// Content always takes priority over an optional presence poll. Preserve the last
    /// snapshot and its original expiry; only the old request/response lease is revoked.
    private func cancelPresenceForContentSync() {
        presenceGeneration &+= 1; presenceOperation?.cancel(); presenceOperation = nil; isPresenceBusy = false
    }

    private func invalidatePresence() {
        cancelPresenceForContentSync()
        presenceContext = nil; presenceSnapshot = nil; remotePresenceDevices = []
        presenceLastUpdatedAt = nil; presenceError = nil; presenceOmittedCount = 0
        presenceStatus = "运行状态未共享；离线设备的旧状态会在90秒后过期"
    }

    private func emptyPresenceRequest(_ context: PresenceContext) -> ExecutionPresenceRequest? {
        guard let sequence = try? presenceSequences.next(accountID: context.accountID, deviceID: context.deviceID) else { return nil }
        return .init(deviceID: context.deviceID, sequence: sequence, label: "Mac", runs: [])
    }

    private func reconcilePresenceAuthorization() {
        updatePresencePreference()
        let current = eligiblePresenceContext()
        if let previous = presenceContext, previous != current {
            let token = session?.userID == previous.accountID ? session?.accessToken : nil
            let clear = emptyPresenceRequest(previous)
            invalidatePresence()
            // Use only a captured existing access token. Clearing never refreshes a paused/logged-out session.
            if let token, let client, let clear {
                Task { _ = try? await client.exchangePresence(clear, accessToken: token) }
            }
        }
        if let current { presenceContext = current }
        else if presenceContext == nil && !presenceSharingEnabled {
            presenceStatus = "运行状态共享已关闭；内容同步不受影响"
        }
    }

    func refreshPresence() {
        startPresenceRefresh()
    }

    private func startPresenceRefresh(allowContentCompletion: Bool = false) {
        reconcilePresenceAuthorization()
        objectWillChange.send()
        guard (!isBusy || allowContentCompletion), !isPresenceBusy, eligiblePresenceContext() != nil else { return }
        isPresenceBusy = true
        let fence = presenceGeneration
        let contentFence = generation
        presenceOperation = Task { @MainActor in
            defer { if presenceGeneration == fence { isPresenceBusy = false; presenceOperation = nil } }
            do { try await exchangePresence(presenceFence: fence) }
            catch {
                if presenceGeneration == fence, generation == contentFence, !(error is CancellationError) { showPresence(error) }
            }
        }
    }

    @MainActor private func validateFence(_ fence: UInt64, account: UUID) throws {
        try Task.checkCancellation()
        guard generation == fence, session?.userID == account, store.syncState?.enabled == true,
              store.syncState?.accountID.lowercased() == account.uuidString.lowercased() else { throw CancellationError() }
    }

    @MainActor private func exchangePresence(presenceFence: UInt64) async throws {
        guard let context = eligiblePresenceContext(), let client else { return }
        presenceContext = context
        let fence = generation
        try validateFence(fence, account: context.accountID)
        guard presenceGeneration == presenceFence, eligiblePresenceContext() == context,
              let currentSession = session, currentSession.userID == context.accountID else { throw CancellationError() }
        // Presence never rotates refresh tokens or writes credentials. Ordinary content sync
        // renews the session and starts a fresh presence attempt afterwards.
        guard currentSession.expiresAt.timeIntervalSinceNow > 90 else {
            presenceStatus = "等待内容同步更新登录状态；旧运行状态会按时过期"
            return
        }
        let token = currentSession.accessToken
        let now = Date(), uptime = ProcessInfo.processInfo.systemUptime
        let runs = ExecutionPresenceMapper.localRuns(timer: store.activeTimer, parallelRuns: store.parallelRuns, deviceID: context.deviceID, now: now)
        let sequence = try presenceSequences.next(accountID: context.accountID, deviceID: context.deviceID)
        let request = ExecutionPresenceRequest(deviceID: context.deviceID, sequence: sequence, label: "Mac", runs: runs)
        let response = try await client.exchangePresence(request, accessToken: token)
        try validateFence(fence, account: context.accountID)
        guard presenceGeneration == presenceFence, eligiblePresenceContext() == context else { throw CancellationError() }
        guard let accepted = response.acceptedSequence else { throw SyncHTTPError.invalidResponse }
        try presenceSequences.advance(accountID: context.accountID, deviceID: context.deviceID, to: accepted)
        presenceSnapshot = .init(response: response, observedAt: now, observedUptime: uptime)
        remotePresenceDevices = response.devices.filter { $0.deviceID != context.deviceID }.sorted { $0.lastSeen > $1.lastSeen }
        presenceLastUpdatedAt = Date(); presenceError = nil
        presenceOmittedCount = max(0, store.parallelRuns.count + (store.activeTimer == nil ? 0 : 1) - 32)
        presenceStatus = accepted > sequence ? "已读取设备状态，本机运行状态将在下次刷新更新" :
            (remotePresenceDevices.isEmpty ? "暂未看到其他设备的运行状态" : "设备运行状态已更新")
    }

    private func showPresence(_ error: Error) {
        presenceStatus = "运行状态暂未更新；请留意过期标记"
        presenceError = (error as? SyncHTTPError)?.localizedDescription ?? "设备状态连接或保存未完成，请稍后重试。"
        // The content coordinator owns authentication recovery. A presence response may have
        // used an older captured JWT while content sync successfully renewed credentials.
    }

    private func show(_ error: Error) {
        status = "同步暂未完成，本机修改已保留"
        if let failure = error as? SyncHTTPError, failure == .unauthorized || failure == .invalidToken {
            invalidateSyncOperation(); invalidatePresence()
            session = nil; isSignedIn = false; email = nil; presenceSharingEnabled = false
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
