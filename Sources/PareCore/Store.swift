import Foundation
import Combine

/// The single source of truth. Owns all data, all business rules, and
/// persists every mutation to a JSON file so nothing is ever lost.
///
/// UI code observes `inbox` / `tasks` / `settings` via `@Published`. All
/// mutations are expected to happen on the main thread (they originate from UI
/// actions, the global-hotkey handler, or notification actions, all of which
/// run on the main run loop).
public final class Store: ObservableObject {

    @Published public private(set) var inbox: [InboxItem] = []
    @Published public private(set) var tasks: [TaskItem] = []
    @Published public private(set) var archive: [TaskItem] = []
    @Published public private(set) var thoughts: [ThoughtItem] = []
    @Published public private(set) var aiBackground = ""
    @Published public private(set) var aiSummaries: [AISummaryRecord] = []
    @Published public private(set) var goals: [LongTermGoal] = []
    @Published public private(set) var agentPreferences = AgentPreferences()
    @Published public private(set) var planningRuns: [PlanningRun] = []
    @Published public private(set) var confirmedPreferences: [ConfirmedPreference] = []
    @Published public private(set) var intelligenceFeedback: [IntelligenceFeedback] = []
    @Published public private(set) var intelligenceAdoptions: [IntelligenceAdoptionReceipt] = []
    @Published public private(set) var dailyAllocations: [DailyAllocation] = []
    @Published public private(set) var activeTimer: ActiveTimer?
    @Published public private(set) var parallelRuns: [ParallelTaskRun] = []
    /// Device coordination is a UI decision, never serialized into task or backup data.
    public var confirmFocusStart: ((UUID) -> Bool)?
    /// A short-lived, UI-friendly description of the last destructive action.
    @Published public private(set) var undoMessage: String?
    /// Persistence diagnostics surfaced wherever the user is working.
    @Published public private(set) var persistenceIssue: String?
    /// Reminder preferences. Persisted alongside the data.
    @Published public private(set) var settings: Settings = .default

    /// The day the store was last known to be active (start-of-day). Used to
    /// drive the once-per-day rollover ritual.
    public private(set) var lastActiveDay: Date?

    @Published public private(set) var syncState: SyncLocalState?
    @Published public private(set) var syncDayPlans: [SyncDayPlan] = []
    @Published public private(set) var planningTimeZone = TimeZone.current.identifier
    private var applyingSync = false
    private var localScheduleReminderSuppressions: [UUID: Date] = [:]

    private let fileURL: URL
    private var backupURL: URL { fileURL.appendingPathExtension("bak") }
    public var dataFileURL: URL { fileURL }
    /// Internal (not private) so the reminder planner extension can reuse it.
    let calendar: Calendar
    private var loading = false
    private var persistenceLocked = false
    private var hasUnsavedChanges = false
    private var unsupportedSchemaVersion: Int?
    public var isPersistenceReadOnly: Bool { persistenceLocked }
    /// Last successfully decoded/encoded generation. Reusing it for `.bak`
    /// avoids reading and decoding the whole JSON synchronously on every click.
    private var lastSavedData: Data?
    private enum UndoPayload {
        case inbox(InboxItem, Int)
        case task(TaskItem, Int)
        case thought(ThoughtItem, Int)
        case tasks([(Int, TaskItem)])
    }
    private var undoPayload: UndoPayload?
    private var undoToken = UUID()
    private var legacyAIAdoptionLinks: [UUID: UUID] = [:]

    // MARK: - Init

    public init(fileURL: URL, calendar: Calendar = .current) {
        self.fileURL = fileURL
        self.calendar = calendar
        self.planningTimeZone = calendar.timeZone.identifier
        load()
    }

    /// `~/Library/Application Support/Pare/store.json`. The application creates
    /// its directory while acquiring the single-instance lock, before loading.
    public static func defaultFileURL() -> URL {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("Pare", isDirectory: true)
        return dir.appendingPathComponent("store.json")
    }

    // MARK: - Persistence

    private struct Snapshot: Codable {
        var schemaVersion: Int?
        var inbox: [InboxItem]
        var tasks: [TaskItem]
        var archive: [TaskItem]?
        var thoughts: [ThoughtItem]?
        var activeTimer: ActiveTimer?
        var parallelRuns: [ParallelTaskRun]?
        var lastActiveDay: Date?
        var settings: Settings?
        var aiBackground: String?
        var aiSummaries: [AISummaryRecord]?
        var goals: [LongTermGoal]?
        var agentPreferences: AgentPreferences?
        var planningRuns: [PlanningRun]?
        var confirmedPreferences: [ConfirmedPreference]?
        var intelligenceFeedback: [IntelligenceFeedback]?
        var intelligenceAdoptions: [IntelligenceAdoptionReceipt]?
        var dailyAllocations: [DailyAllocation]?
        var legacyAIAdoptionLinks: [UUID: UUID]?
        var syncState: SyncLocalState?
        var syncDayPlans: [SyncDayPlan]?
        var planningTimeZone: String?
        var localScheduleReminderSuppressions: [UUID: Date]?
    }

    public enum DataTransferError: LocalizedError {
        case invalidBackup
        case unsupportedVersion(Int)
        case cannotWrite(String)

        public var errorDescription: String? {
            switch self {
            case .invalidBackup: return "这个文件不是有效的 Step 备份，数据没有改变。"
            case .unsupportedVersion(let version): return "这个备份来自更新的数据格式（v\(version)），请先升级 Step。"
            case .cannotWrite(let detail): return "无法写入数据：\(detail)"
            }
        }
    }

    public func load() {
        loading = true
        var rewriteRecoveredData = false
        defer {
            loading = false
            if rewriteRecoveredData { save(createBackup: false) }
        }
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        let snap: Snapshot
        do {
            let data = try Data(contentsOf: fileURL)
            snap = try decodeSnapshot(data, using: dec)
            lastSavedData = data
            hasUnsavedChanges = false
            persistenceLocked = false
            unsupportedSchemaVersion = nil
            persistenceIssue = nil
        } catch DataTransferError.unsupportedVersion(let version) {
            unsupportedSchemaVersion = version
            persistenceLocked = true
            persistenceIssue = "数据来自更新的 Step（格式 v\(version)）。请升级 Step；原文件和自动备份已保留，当前禁止写入。"
            return
        } catch {
            do {
                let data = try Data(contentsOf: backupURL)
                snap = try decodeSnapshot(data, using: dec)
                lastSavedData = data
                persistenceLocked = false
                unsupportedSchemaVersion = nil
                persistenceIssue = "主数据文件无法读取，已从自动备份恢复。"
                rewriteRecoveredData = true
            } catch {
                persistenceLocked = true
                persistenceIssue = "数据文件和自动备份都无法读取；Step 暂未覆盖原文件。"
                return
            }
        }
        apply(snap)
        if rewriteRecoveredData, let owner = syncState?.accountID {
            syncState = SyncLocalState(accountID: owner)
            syncState?.enabled = false
        }
    }

    private func apply(_ snap: Snapshot) {
        localScheduleReminderSuppressions = snap.localScheduleReminderSuppressions ?? [:]
        inbox = snap.inbox
        tasks = snap.tasks
        archive = snap.archive ?? []
        thoughts = snap.thoughts ?? []
        aiBackground = String((snap.aiBackground ?? "").prefix(1500))
        aiSummaries = Array((snap.aiSummaries ?? []).sorted { $0.date > $1.date }.prefix(50))
        goals = snap.goals ?? []
        agentPreferences = snap.agentPreferences ?? AgentPreferences()
        planningRuns = Array((snap.planningRuns ?? []).prefix(20)).map { run in
            var value = run
            if [.gathering, .thinking, .awaitingReview, .needsClarification].contains(value.phase) { value.phase = .interrupted }
            return value
        }
        confirmedPreferences = snap.confirmedPreferences ?? []
        intelligenceFeedback = snap.intelligenceFeedback ?? []
        intelligenceAdoptions = snap.intelligenceAdoptions ?? []
        dailyAllocations = snap.dailyAllocations ?? []
        legacyAIAdoptionLinks = snap.legacyAIAdoptionLinks ?? [:]
        for summary in aiSummaries where summary.feedback != nil && !intelligenceFeedback.contains(where: { $0.requestID == summary.id }) {
            mirrorLegacyFeedback(summary)
        }
        activeTimer = snap.activeTimer
        var seenParallel = Set<UUID>()
        parallelRuns = (snap.parallelRuns ?? []).filter { run in
            guard seenParallel.insert(run.taskID).inserted,
                  tasks.contains(where: { $0.id == run.taskID && !$0.completed }),
                  activeTimer?.taskID != run.taskID else { return false }
            return true
        }
        let parallelIDs = Set(parallelRuns.map(\.taskID))
        for index in tasks.indices where parallelIDs.contains(tasks[index].id) {
            tasks[index].scheduledAt = nil
            tasks[index].scheduledMinutes = nil
            tasks[index].scheduleReminderLeadMinutes = nil
        }
        lastActiveDay = snap.lastActiveDay
        settings = snap.settings ?? .default
        syncState = snap.syncState
        syncDayPlans = snap.syncDayPlans ?? []
        planningTimeZone = snap.planningTimeZone ?? calendar.timeZone.identifier
        if snap.syncDayPlans == nil { migrateSyncDayPlans() }
        else { projectSyncDayPlan(at: lastActiveDay ?? Date()) }
        if let timer = activeTimer,
           !tasks.contains(where: { $0.id == timer.taskID && !$0.completed }) {
            activeTimer = nil
        } else if let timer = activeTimer,
                  let index = tasks.firstIndex(where: { $0.id == timer.taskID }) {
            tasks[index].scheduleReminderLeadMinutes = nil
        }
    }

    private func decodeSnapshot(_ data: Data, using decoder: JSONDecoder) throws -> Snapshot {
        // Check the version before decoding fields whose structure may have
        // changed in a newer app. A new schema is not a corrupt old snapshot.
        struct Header: Decodable { var schemaVersion: Int? }
        let version = try decoder.decode(Header.self, from: data).schemaVersion ?? 1
        guard version <= 7 else { throw DataTransferError.unsupportedVersion(version) }
        return try decoder.decode(Snapshot.self, from: data)
    }

    private func currentSnapshot() -> Snapshot {
        Snapshot(schemaVersion: 7, inbox: inbox, tasks: tasks,
                 archive: archive, thoughts: thoughts, activeTimer: activeTimer,
                 parallelRuns: parallelRuns,
                 lastActiveDay: lastActiveDay, settings: settings,
                 aiBackground: aiBackground, aiSummaries: aiSummaries,
                 goals: goals, agentPreferences: agentPreferences, planningRuns: planningRuns,
                 confirmedPreferences: confirmedPreferences, intelligenceFeedback: intelligenceFeedback,
                 intelligenceAdoptions: intelligenceAdoptions, dailyAllocations: dailyAllocations, legacyAIAdoptionLinks: legacyAIAdoptionLinks, syncState: syncState, syncDayPlans: syncDayPlans, planningTimeZone: planningTimeZone, localScheduleReminderSuppressions: localScheduleReminderSuppressions)
    }

    private func encoder() -> JSONEncoder {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        return enc
    }

    @discardableResult
    public func retrySaving() -> Bool { save() }

    @discardableResult
    private func save(createBackup: Bool = true) -> Bool {
        guard !loading, !persistenceLocked else { return false }
        hasUnsavedChanges = true
        let previousSyncState = syncState, previousDayPlans = syncDayPlans
        do {
            if !applyingSync { captureSyncDayPlan() }
            try refreshSyncPending()
            let data = try encoder().encode(currentSnapshot())
            if createBackup, let previous = lastSavedData {
                try previous.write(to: backupURL, options: .atomic)
            }
            try data.write(to: fileURL, options: .atomic)
            lastSavedData = data
            hasUnsavedChanges = false
            if persistenceIssue?.contains("无法保存") == true { persistenceIssue = nil }
            return true
        } catch {
            // Existing local transactions may roll their content back after
            // save fails. Their derived queue and plans must roll back too.
            syncState = previousSyncState; syncDayPlans = previousDayPlans
            persistenceIssue = "无法保存数据：\(error.localizedDescription)"
            return false
        }
    }

    /// Keep only a decodable previous generation. A corrupted primary file must
    /// never be allowed to overwrite the last known-good backup.
    private func backupCurrentFileIfHealthy() throws {
        if let data = lastSavedData {
            try data.write(to: backupURL, options: .atomic)
            return
        }
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        let data = try Data(contentsOf: fileURL)
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        guard (try? decodeSnapshot(data, using: dec)) != nil else { return }
        lastSavedData = data
        try data.write(to: backupURL, options: .atomic)
    }

    public func exportData(to destination: URL) throws {
        if let version = unsupportedSchemaVersion {
            throw DataTransferError.unsupportedVersion(version)
        }
        do {
            try encoder().encode(currentSnapshot()).write(to: destination, options: .atomic)
        } catch {
            throw DataTransferError.cannotWrite(error.localizedDescription)
        }
    }

    public func importData(from source: URL) throws {
        if let version = unsupportedSchemaVersion {
            throw DataTransferError.unsupportedVersion(version)
        }
        let data = try Data(contentsOf: source)
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        let snapshot: Snapshot
        do { snapshot = try decodeSnapshot(data, using: dec) }
        catch let error as DataTransferError { throw error }
        catch { throw DataTransferError.invalidBackup }
        // An imported backup cannot tell us when a personal focus session
        // actually stopped. Preserve its recorded work, but never resume it.
        var imported = snapshot
        imported.schemaVersion = 7
        // Backups carry content, never authority to resume uploading. Retain a
        // disabled binding to prevent importing another account's queue.
        if let state = syncState ?? imported.syncState {
            var suspended = SyncLocalState(accountID: state.accountID)
            suspended.enabled = false
            imported.syncState = suspended
        } else { imported.syncState = nil }
        imported.activeTimer = nil
        var previousBackup: Data?
        var backedUpCurrentFile = false
        do {
            let encoded = try encoder().encode(imported)
            if FileManager.default.fileExists(atPath: backupURL.path) {
                previousBackup = try Data(contentsOf: backupURL)
            }
            try backupCurrentFileIfHealthy()
            backedUpCurrentFile = true
            try encoded.write(to: fileURL, options: .atomic)
            lastSavedData = encoded
        } catch {
            var detail = error.localizedDescription
            // In particular, restoring `.bak` must not destroy that recovery
            // source when writing the primary fails. Keep it retryable.
            if backedUpCurrentFile, let previousBackup {
                do { try previousBackup.write(to: backupURL, options: .atomic) }
                catch { detail += "；还原自动备份失败：\(error.localizedDescription)" }
            }
            persistenceIssue = "无法保存导入数据，当前内容没有改变：\(detail)"
            throw DataTransferError.cannotWrite(detail)
        }
        loading = true
        apply(imported)
        loading = false
        persistenceLocked = false
        persistenceIssue = nil
        hasUnsavedChanges = false
        clearUndo()
    }

    @discardableResult
    public func restoreAutomaticBackup() -> Bool {
        guard FileManager.default.fileExists(atPath: backupURL.path) else { return false }
        do {
            try importData(from: backupURL)
            return true
        } catch DataTransferError.cannotWrite {
            // importData already published the write failure and any backup
            // rollback failure; preserve that detail for retry and Settings.
            return false
        } catch {
            persistenceIssue = error.localizedDescription
            return false
        }
    }

    // MARK: - Daily rollover ritual

    /// Once per day: archive everything completed, clear the "today" focus so
    /// the user re-chooses it during the morning 整理 session. Incomplete tasks
    /// stay in their quadrants — nothing is deleted or lost.
    @discardableResult
    public func rolloverIfNeeded(now: Date = Date()) -> Bool {
        guard !persistenceLocked else { return false }
        let today = syncCalendar.startOfDay(for: now)
        if let last = lastActiveDay, syncCalendar.isDate(last, inSameDayAs: today) {
            return false
        }
        let firstRun = (lastActiveDay == nil)
        captureSyncDayPlan()
        lastActiveDay = today
        if firstRun {
            save()
            return false
        }
        // Today's UI is a projection; yesterday's plan remains a shared entity.
        // Preserve the established archive UX. Its lifecycle write uses the same
        // revision check as any edit, so a concurrent reopen becomes a conflict.
        archive.append(contentsOf: tasks.filter(\.completed))
        tasks.removeAll { $0.completed }
        projectSyncDayPlan(at: now)
        save()
        return true
    }

    // MARK: - Capture (随手记)

    public enum CaptureResult: Equatable {
        case inbox
        case reminder(Date)
    }

    @discardableResult
    public func capture(_ text: String) -> Bool {
        captureWithResult(text) != nil
    }

    /// A reminder capture becomes an actionable task immediately. Success is
    /// returned only after it is durable, so the UI can safely clear the draft.
    @discardableResult
    public func captureWithResult(_ text: String, now: Date = Date()) -> CaptureResult? {
        let parsed = CaptureParser.parse(text, now: now, calendar: calendar)
        let t = parsed.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !persistenceLocked else { return nil }
        if let date = parsed.remindAt, date > now {
            let task = TaskItem(text: t, quadrant: .importantNotUrgent,
                                createdAt: now, remindAt: date)
            tasks.insert(task, at: 0)
            guard save() else {
                tasks.removeAll { $0.id == task.id }
                return nil
            }
            return .reminder(date)
        }
        let item = InboxItem(text: parsed.remindAt == nil ? t : text.trimmingCharacters(in: .whitespacesAndNewlines),
                             createdAt: now)
        inbox.insert(item, at: 0)
        guard save() else {
            inbox.removeAll { $0.id == item.id }
            return nil
        }
        return .inbox
    }

    public func deleteInbox(_ id: UUID) {
        guard let index = inbox.firstIndex(where: { $0.id == id }) else { return }
        let item = inbox.remove(at: index)
        stageUndo(.inbox(item, index), message: "已删除一条随手记")
        save()
    }

    /// Keep a capture as an idea instead of forcing it into the task matrix.
    @discardableResult
    public func keepAsThought(inboxID: UUID, now: Date = Date()) -> ThoughtItem? {
        guard let index = inbox.firstIndex(where: { $0.id == inboxID }) else { return nil }
        let item = inbox[index]
        let thought = ThoughtItem(text: item.text, createdAt: item.createdAt, updatedAt: now)
        guard persistIdeaMutation({
            inbox.remove(at: index)
            thoughts.insert(thought, at: 0)
        }) else { return nil }
        return thought
    }

    @discardableResult
    public func addThought(_ text: String, notes: String? = nil, now: Date = Date()) -> ThoughtItem? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        let thought = ThoughtItem(text: t, notes: Self.nonBlank(notes), createdAt: now, updatedAt: now)
        guard persistIdeaMutation({ thoughts.insert(thought, at: 0) }) else { return nil }
        return thought
    }

    public func updateThought(_ id: UUID, text: String, notes: String?, reviewAt: Date? = nil,
                              now: Date = Date()) {
        guard let idea = thoughts.first(where: { $0.id == id }) else { return }
        updateIdea(id, text: text, notes: notes, nextQuestion: idea.nextQuestion,
                   stage: idea.stage, reviewAt: reviewAt, now: now)
    }

    @discardableResult
    public func updateIdea(_ id: UUID, text: String, notes: String?, nextQuestion: String?,
                           stage: IdeaStage, reviewAt: Date?, now: Date = Date()) -> Bool {
        guard let index = thoughts.firstIndex(where: { $0.id == id }) else { return false }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return false }
        return persistIdeaMutation {
            thoughts[index].text = t
            thoughts[index].notes = Self.nonBlank(notes)
            if thoughts[index].nextQuestion != Self.nonBlank(nextQuestion) { thoughts[index].nextQuestionUpdatedAt = now }
            thoughts[index].nextQuestion = Self.nonBlank(nextQuestion)
            thoughts[index].stage = stage
            thoughts[index].updatedAt = now
            thoughts[index].reviewAt = reviewAt
        }
    }

    @discardableResult
    public func addIdeaEntry(ideaID: UUID, text: String, kind: IdeaEntryKind = .note,
                             now: Date = Date()) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let index = thoughts.firstIndex(where: { $0.id == ideaID }) else { return false }
        return persistIdeaMutation {
            thoughts[index].entries.append(IdeaEntry(text: t, kind: kind, createdAt: now))
            thoughts[index].updatedAt = now
        }
    }

    @discardableResult
    public func deleteThought(_ id: UUID) -> Bool {
        guard let index = thoughts.firstIndex(where: { $0.id == id }) else { return false }
        let thought = thoughts[index]
        guard persistIdeaMutation({ thoughts.remove(at: index) }) else { return false }
        stageUndo(.thought(thought, index), message: "已删除一个想法")
        return true
    }

    /// Compatibility entry point: derive an action without consuming the idea.
    @discardableResult
    public func promoteThought(_ id: UUID, to quadrant: Quadrant = .importantNotUrgent,
                               addToToday: Bool = false, now: Date = Date()) -> TaskItem? {
        guard let thought = thoughts.first(where: { $0.id == id }) else { return nil }
        return createTaskFromIdea(ideaID: id, title: thought.text, quadrant: quadrant,
                                  addToToday: addToToday, now: now)
    }

    @discardableResult
    public func createTaskFromIdea(ideaID: UUID, title: String, quadrant: Quadrant = .importantNotUrgent,
                                    addToToday: Bool = false, now: Date = Date()) -> TaskItem? {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let index = thoughts.firstIndex(where: { $0.id == ideaID }) else { return nil }
        let thought = thoughts[index]
        let task = TaskItem(text: t, quadrant: quadrant, createdAt: now,
                            isToday: addToToday, lastProgressAt: now, note: thought.notes,
                            todayOrder: addToToday ? nextTodayOrder() : nil)
        guard persistIdeaMutation({
            tasks.append(task)
            thoughts[index].linkedTaskIDs.append(task.id)
            thoughts[index].updatedAt = now
        }) else { return nil }
        return task
    }

    @discardableResult
    public func linkTaskToIdea(ideaID: UUID, taskID: UUID, now: Date = Date()) -> Bool {
        guard let index = thoughts.firstIndex(where: { $0.id == ideaID }),
              !thoughts[index].linkedTaskIDs.contains(taskID),
              tasks.contains(where: { $0.id == taskID }) || archive.contains(where: { $0.id == taskID })
        else { return false }
        return persistIdeaMutation {
            thoughts[index].linkedTaskIDs.append(taskID)
            thoughts[index].updatedAt = now
        }
    }

    @discardableResult
    public func unlinkTaskFromIdea(ideaID: UUID, taskID: UUID, now: Date = Date()) -> Bool {
        guard let index = thoughts.firstIndex(where: { $0.id == ideaID }),
              thoughts[index].linkedTaskIDs.contains(taskID) else { return false }
        return persistIdeaMutation {
            thoughts[index].linkedTaskIDs.removeAll { $0 == taskID }
            thoughts[index].updatedAt = now
        }
    }

    public func ideaLinkedTasks(_ id: UUID) -> [TaskItem] {
        guard let idea = thoughts.first(where: { $0.id == id }) else { return [] }
        var seen = Set<UUID>()
        return idea.linkedTaskIDs.compactMap { taskID in
            guard seen.insert(taskID).inserted else { return nil }
            return tasks.first { $0.id == taskID } ?? archive.first { $0.id == taskID }
        }
    }

    public func ideas(for taskID: UUID) -> [ThoughtItem] {
        thoughts.filter { $0.linkedTaskIDs.contains(taskID) }
    }

    /// Idea drafts stay in the editor until a complete action is durable.
    /// Preserve pending changes from other features if this action rolls back.
    private func persistIdeaMutation(_ mutation: () -> Void) -> Bool {
        guard !loading, !persistenceLocked else { return false }
        let previousThoughts = thoughts
        let previousTasks = tasks
        let previousInbox = inbox
        let previouslyUnsaved = hasUnsavedChanges
        mutation()
        guard save() else {
            thoughts = previousThoughts
            tasks = previousTasks
            inbox = previousInbox
            hasUnsavedChanges = previouslyUnsaved
            return false
        }
        return true
    }

    public func thoughtsDueForReview(now: Date = Date()) -> [ThoughtItem] {
        thoughts.filter { $0.stage != .archived && ($0.reviewAt ?? .distantFuture) <= now }
            .sorted { ($0.reviewAt ?? .distantPast) < ($1.reviewAt ?? .distantPast) }
    }

    /// Bring a completed item back from history without losing its estimates or
    /// notes. Actual time remains as useful calibration context.
    public func restoreArchived(_ id: UUID, now: Date = Date()) {
        guard let index = archive.firstIndex(where: { $0.id == id }) else { return }
        var task = archive.remove(at: index)
        task.completed = false
        task.completedAt = nil
        task.isToday = false
        task.todayOrder = nil
        task.scheduledAt = nil
        task.scheduledMinutes = nil
        task.scheduleReminderLeadMinutes = nil
        task.lastProgressAt = now
        tasks.append(task)
        save()
    }

    // MARK: - Organize (整理)

    /// Move an inbox item into a quadrant, turning it into a task.
    ///
    /// Organizing is an explicit "progress" action, so the new task starts its
    /// staleness clock at `now` (both `createdAt` and `lastProgressAt`).
    @discardableResult
    public func organize(inboxID: UUID, to quadrant: Quadrant, addToToday: Bool = false,
                         estimatedMinutes: Int? = nil, now: Date = Date()) -> TaskItem? {
        guard let idx = inbox.firstIndex(where: { $0.id == inboxID }) else { return nil }
        let item = inbox.remove(at: idx)
        let task = TaskItem(text: item.text, quadrant: quadrant, createdAt: now,
                            isToday: addToToday, estimatedMinutes: estimatedMinutes,
                            remindAt: item.suggestedRemindAt, lastProgressAt: now,
                            todayOrder: addToToday ? nextTodayOrder() : nil)
        tasks.append(task)
        save()
        return task
    }

    /// Add a brand-new task directly inside a quadrant.
    ///
    /// A freshly placed task has not been progressed yet, so `lastProgressAt`
    /// stays `nil` and the review layer measures staleness from `createdAt`.
    @discardableResult
    public func addTask(_ text: String, to quadrant: Quadrant, addToToday: Bool = false,
                        estimatedMinutes: Int? = nil, now: Date = Date()) -> TaskItem? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        let task = TaskItem(text: t, quadrant: quadrant, createdAt: now,
                            isToday: addToToday, estimatedMinutes: estimatedMinutes,
                            todayOrder: addToToday ? nextTodayOrder() : nil)
        tasks.append(task)
        save()
        return task
    }

    /// Set (or clear, with `nil` / 0) a task's estimated minutes.
    public func setEstimate(_ id: UUID, minutes: Int?) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        let clamped = minutes.map { max(0, $0) }
        if tasks[i].scheduledAt != nil {
            let duration = clamped.flatMap { $0 > 0 ? min(480, max(15, $0)) : nil } ?? 30
            tasks[i].estimatedMinutes = duration
            tasks[i].scheduledMinutes = duration
        } else {
            tasks[i].estimatedMinutes = (clamped == 0) ? nil : clamped
        }
        save()
    }

    // MARK: - Time blocking

    /// Place a personal task on the day timeline. New blocks adopt the task's
    /// estimate (or 30 minutes); resizing keeps estimate and block duration in
    /// sync so capacity calculations remain honest.
    public func scheduleTask(_ id: UUID, at date: Date, durationMinutes: Int? = nil,
                             reminderLeadMinutes: Int? = 5, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id && !$0.completed }),
              !parallelRuns.contains(where: { $0.taskID == id }) else { return }
        let wasScheduled = tasks[i].scheduledAt != nil
        let rawDuration = durationMinutes ?? tasks[i].scheduledMinutes ?? tasks[i].estimatedMinutes ?? 30
        let duration = min(480, max(15, rawDuration))
        tasks[i].scheduledAt = date
        tasks[i].scheduledMinutes = duration
        tasks[i].estimatedMinutes = duration
        if !wasScheduled {
            tasks[i].scheduleReminderLeadMinutes = reminderLeadMinutes.map { min(120, max(0, $0)) }
        }
        tasks[i].isToday = true
        tasks[i].todayOrder = tasks[i].todayOrder ?? nextTodayOrder()
        tasks[i].lastProgressAt = now
        save()
    }

    public func setScheduleDuration(_ id: UUID, minutes: Int, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id && $0.scheduledAt != nil }) else { return }
        let duration = min(480, max(15, minutes))
        tasks[i].scheduledMinutes = duration
        tasks[i].estimatedMinutes = duration
        tasks[i].lastProgressAt = now
        save()
    }

    public func setScheduleReminder(_ id: UUID, leadMinutes: Int?) {
        guard let i = tasks.firstIndex(where: { $0.id == id && $0.scheduledAt != nil }) else { return }
        localScheduleReminderSuppressions.removeValue(forKey: id)
        tasks[i].scheduleReminderLeadMinutes = leadMinutes.map { min(120, max(0, $0)) }
        save()
    }

    public func shiftSchedule(_ id: UUID, by minutes: Int, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }),
              let scheduledAt = tasks[i].scheduledAt else { return }
        tasks[i].scheduledAt = scheduledAt.addingTimeInterval(Double(minutes) * 60)
        tasks[i].lastProgressAt = now
        save()
    }

    public func unscheduleTask(_ id: UUID, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].scheduledAt = nil
        tasks[i].scheduledMinutes = nil
        tasks[i].scheduleReminderLeadMinutes = nil
        tasks[i].lastProgressAt = now
        save()
    }

    public func scheduleConflicts(for id: UUID) -> [TaskItem] {
        guard let task = tasks.first(where: { $0.id == id }),
              let start = task.scheduledAt else { return [] }
        let end = start.addingTimeInterval(Double(task.scheduledMinutes ?? task.estimatedMinutes ?? 30) * 60)
        return tasks.filter { other in
            guard other.id != id, !other.completed, let otherStart = other.scheduledAt else { return false }
            let otherEnd = otherStart.addingTimeInterval(
                Double(other.scheduledMinutes ?? other.estimatedMinutes ?? 30) * 60)
            return start < otherEnd && otherStart < end
        }
    }

    /// Record (or clear, with `nil` / 0) a task's actual minutes. Recording the
    /// actual time is an explicit progress action, so it also refreshes
    /// `lastProgressAt`.
    public func setActual(_ id: UUID, minutes: Int?, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        let clamped = minutes.map { max(0, $0) }
        tasks[i].actualMinutes = (clamped == 0) ? nil : clamped
        tasks[i].lastProgressAt = now
        save()
    }

    /// Set (or clear, with `nil`) a task's one-shot reminder time.
    public func setRemind(_ id: UUID, at date: Date?) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].remindAt = date
        save()
    }

    /// Save the fields edited in the task inspector as one atomic user action.
    public func updateTaskDetails(_ id: UUID, text: String, note: String?, nextAction: String?,
                                  dueAt: Date?, remindAt: Date?, recurrence: RecurrenceRule?,
                                  definitionOfDone: String? = nil,
                                  implementationPlan: ImplementationPlan? = nil,
                                  isDailyWin: Bool? = nil,
                                  now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        tasks[i].text = t
        tasks[i].note = Self.nonBlank(note)
        tasks[i].nextAction = Self.nonBlank(nextAction)
        tasks[i].dueAt = dueAt
        tasks[i].remindAt = remindAt
        tasks[i].recurrence = remindAt == nil ? nil : recurrence
        tasks[i].definitionOfDone = Self.nonBlank(definitionOfDone)
        tasks[i].implementationPlan = implementationPlan
        if isDailyWin == true {
            for index in tasks.indices { tasks[index].isDailyWin = false }
            tasks[i].isDailyWin = true
            tasks[i].isToday = true
            tasks[i].todayOrder = tasks[i].todayOrder ?? nextTodayOrder()
        } else if isDailyWin == false {
            tasks[i].isDailyWin = false
        }
        tasks[i].lastProgressAt = now
        save()
    }

    public func setToday(_ id: UUID, _ value: Bool, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].isToday = value
        tasks[i].todayOrder = value ? (tasks[i].todayOrder ?? nextTodayOrder()) : nil
        if !value {
            tasks[i].isDailyWin = false
            tasks[i].scheduledAt = nil
            tasks[i].scheduledMinutes = nil
            tasks[i].scheduleReminderLeadMinutes = nil
        }
        tasks[i].lastProgressAt = now
        save()
    }

    public func toggleToday(_ id: UUID, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].isToday.toggle()
        tasks[i].todayOrder = tasks[i].isToday ? nextTodayOrder() : nil
        if !tasks[i].isToday {
            tasks[i].isDailyWin = false
            tasks[i].scheduledAt = nil
            tasks[i].scheduledMinutes = nil
            tasks[i].scheduleReminderLeadMinutes = nil
        }
        tasks[i].lastProgressAt = now
        save()
    }

    public func setCompleted(_ id: UUID, _ value: Bool, now: Date = Date()) {
        guard tasks.contains(where: { $0.id == id }) else { return }
        if value, activeTimer?.taskID == id { _ = stopTimer(now: now) }
        if value { _ = endParallelRun(id, reason: .completed, now: now) }
        guard let refreshed = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[refreshed].completed = value
        tasks[refreshed].completedAt = value ? now : nil
        tasks[refreshed].lastProgressAt = now
        save()
    }

    public func toggleComplete(_ id: UUID, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        setCompleted(id, !tasks[i].completed, now: now)
    }

    public func changeQuadrant(_ id: UUID, to quadrant: Quadrant, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].quadrant = quadrant
        tasks[i].lastProgressAt = now
        save()
    }

    public func setDailyWin(_ id: UUID?, now: Date = Date()) {
        for index in tasks.indices { tasks[index].isDailyWin = false }
        if let id, let index = tasks.firstIndex(where: { $0.id == id && !$0.completed }) {
            tasks[index].isDailyWin = true
            tasks[index].isToday = true
            tasks[index].todayOrder = tasks[index].todayOrder ?? nextTodayOrder()
            tasks[index].lastProgressAt = now
        }
        save()
    }

    public func setMomentumDetails(_ id: UUID, nextAction: String?, definitionOfDone: String?,
                                   now: Date = Date()) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].nextAction = Self.nonBlank(nextAction)
        tasks[index].definitionOfDone = Self.nonBlank(definitionOfDone)
        tasks[index].lastProgressAt = now
        save()
    }

    public func recordBlocker(_ id: UUID, reason: BlockerReason, now: Date = Date()) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].blockerEntries = (tasks[index].blockerEntries ?? [])
            + [BlockerEntry(reason: reason, at: now)]
        save()
    }

    public func markImplementationTriggered(_ id: UUID, now: Date = Date()) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].lastImplementationTriggeredAt = now
        save()
    }

    public func deleteTask(_ id: UUID) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        if activeTimer?.taskID == id { activeTimer = nil }
        parallelRuns.removeAll { $0.taskID == id }
        let task = tasks.remove(at: index)
        stageUndo(.task(task, index), message: "已删除任务“\(task.text)”")
        save()
    }

    /// Clear a whole quadrant in one gesture — the "做减法" action for the
    /// Quadrant-IV junk drawer. Only ever removes tasks in `quadrant`; tasks in
    /// every other quadrant are untouched. By default it leaves *completed*
    /// tasks alone so they still flow into today's summary and the normal
    /// archive at rollover; pass `includingCompleted: true` to wipe everything.
    /// Returns how many tasks were removed.
    @discardableResult
    public func clearQuadrant(_ quadrant: Quadrant, includingCompleted: Bool = false) -> Int {
        let removed = tasks.enumerated().compactMap { index, task -> (Int, TaskItem)? in
            task.quadrant == quadrant && (includingCompleted || !task.completed) ? (index, task) : nil
        }
        guard !removed.isEmpty else { return 0 }
        let ids = Set(removed.map { $0.1.id })
        tasks.removeAll { ids.contains($0.id) }
        if let id = activeTimer?.taskID, ids.contains(id) { activeTimer = nil }
        parallelRuns.removeAll { ids.contains($0.taskID) }
        stageUndo(.tasks(removed), message: "已清空 \(removed.count) 个任务")
        save()
        return removed.count
    }

    // MARK: - Undo

    public func undoLastDeletion() {
        guard let payload = undoPayload else { return }
        switch payload {
        case let .inbox(item, index):
            inbox.insert(item, at: min(index, inbox.count))
        case let .task(task, index):
            tasks.insert(task, at: min(index, tasks.count))
        case let .thought(thought, index):
            thoughts.insert(thought, at: min(index, thoughts.count))
        case let .tasks(items):
            for (index, task) in items.sorted(by: { $0.0 < $1.0 }) {
                tasks.insert(task, at: min(index, tasks.count))
            }
        }
        clearUndo()
        save()
    }

    public func clearUndo() {
        undoPayload = nil
        undoMessage = nil
        undoToken = UUID()
    }

    private func stageUndo(_ payload: UndoPayload, message: String) {
        undoPayload = payload
        undoMessage = message
        let token = UUID()
        undoToken = token
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard self?.undoToken == token else { return }
            self?.clearUndo()
        }
    }

    // MARK: - Focus timer

    /// Called once by the application at launch. Loading a snapshot alone
    /// preserves its contents; only the app knows it is recovering a session.
    @discardableResult
    public func recoverInterruptedFocus() -> String? {
        guard let timer = activeTimer else { return nil }
        let title = tasks.first { $0.id == timer.taskID }?.text ?? "上次任务"
        activeTimer = nil
        let saved = save()
        let detail = "无法确认离开期间的专注时长，未自动计入；可在任务详情补记实际用时。"
        return saved
            ? "已暂停上次未结束的专注「\(title)」。\(detail)"
            : "已在当前会话暂停「\(title)」，但暂停状态尚未保存。\(detail)"
    }

    /// A failed quit can be cancelled and retried. The session is accumulated
    /// once in memory, then subsequent attempts only retry the pending write.
    @discardableResult
    public func prepareForTermination(now: Date = Date()) -> Bool {
        guard activeTimer != nil else { return !hasUnsavedChanges || save() }
        _ = finishTimer(now: now)
        return save()
    }

    @discardableResult
    public func startTimer(_ taskID: UUID, now: Date = Date(), targetMinutes: Int? = nil) -> Bool {
        guard tasks.contains(where: { $0.id == taskID && !$0.completed }),
              !parallelRuns.contains(where: { $0.taskID == taskID }) else { return false }
        if activeTimer?.taskID == taskID { return true }
        guard confirmFocusStart?(taskID) ?? true else { return false }
        // A modal confirmation can run the main loop while sync changes the task list.
        guard let taskIndex = tasks.firstIndex(where: { $0.id == taskID && !$0.completed }),
              !parallelRuns.contains(where: { $0.taskID == taskID }) else { return false }
        if activeTimer?.taskID == taskID { return true }
        if activeTimer != nil { _ = stopTimer(now: now) }
        // Beginning the scheduled work fulfils its start reminder. Keep the
        // block visible, but do not alert again while the user is already in it.
        if let scheduledAt = tasks[taskIndex].scheduledAt {
            localScheduleReminderSuppressions[taskID] = scheduledAt
            tasks[taskIndex].scheduleReminderLeadMinutes = nil
        }
        activeTimer = ActiveTimer(taskID: taskID, startedAt: now,
                                  targetMinutes: targetMinutes.map { max(1, $0) })
        save()
        return true
    }

    /// Stop and accumulate the elapsed session into actual time. Even a very
    /// short intentional session records one minute instead of disappearing.
    @discardableResult
    public func stopTimer(now: Date = Date()) -> Int? {
        let minutes = finishTimer(now: now)
        save()
        return minutes
    }

    private func finishTimer(now: Date) -> Int? {
        guard let timer = activeTimer,
              let index = tasks.firstIndex(where: { $0.id == timer.taskID }) else {
            activeTimer = nil
            return nil
        }
        let seconds = max(0, now.timeIntervalSince(timer.startedAt))
        let minutes = max(1, Int(ceil(seconds / 60)))
        activeTimer = nil
        tasks[index].actualMinutes = (tasks[index].actualMinutes ?? 0) + minutes
        tasks[index].progressSessions = (tasks[index].progressSessions ?? [])
            + [ProgressSession(startedAt: timer.startedAt, endedAt: now,
                               minutes: minutes, targetMinutes: timer.targetMinutes)]
        tasks[index].lastProgressAt = now
        return minutes
    }

    public func cancelTimer() {
        guard activeTimer != nil else { return }
        activeTimer = nil
        save()
    }

    public func continueTimerWithoutTarget() {
        guard activeTimer != nil else { return }
        activeTimer?.targetMinutes = nil
        save()
    }

    public func timerElapsedMinutes(now: Date = Date()) -> Int {
        guard let timer = activeTimer else { return 0 }
        return max(0, Int(now.timeIntervalSince(timer.startedAt) / 60))
    }

    public var activeTimerTask: TaskItem? {
        guard let id = activeTimer?.taskID else { return nil }
        return tasks.first { $0.id == id }
    }

    // MARK: - Parallel task runs

    /// Mark an incomplete task as progressing elsewhere. Unlike focus, several
    /// runs can coexist and their elapsed time never increments `actualMinutes`.
    @discardableResult
    public func startParallelRun(_ taskID: UUID, now: Date = Date(), nextCheckAt: Date? = nil) -> Bool {
        guard let index = tasks.firstIndex(where: { $0.id == taskID && !$0.completed }),
              activeTimer?.taskID != taskID else { return false }
        if let existing = parallelRuns.firstIndex(where: { $0.taskID == taskID }) {
            if let nextCheckAt, nextCheckAt > now { parallelRuns[existing].nextCheckAt = nextCheckAt }
            save()
            return true
        }
        parallelRuns.append(ParallelTaskRun(taskID: taskID, startedAt: now,
            nextCheckAt: nextCheckAt.flatMap { $0 > now ? $0 : nil }))
        tasks[index].scheduledAt = nil
        tasks[index].scheduledMinutes = nil
        tasks[index].scheduleReminderLeadMinutes = nil
        tasks[index].isToday = true
        tasks[index].todayOrder = tasks[index].todayOrder ?? nextTodayOrder()
        tasks[index].lastProgressAt = now
        save()
        return true
    }

    public func setParallelCheck(_ taskID: UUID, at date: Date?, now: Date = Date()) {
        guard let index = parallelRuns.firstIndex(where: { $0.taskID == taskID }) else { return }
        parallelRuns[index].nextCheckAt = date.flatMap { $0 > now ? $0 : nil }
        save()
    }

    @discardableResult
    public func pauseParallelRun(_ taskID: UUID, now: Date = Date()) -> Int? {
        let minutes = endParallelRun(taskID, reason: .paused, now: now)
        if minutes != nil { save() }
        return minutes
    }

    @discardableResult
    public func completeParallelRun(_ taskID: UUID, now: Date = Date()) -> Int? {
        let minutes = endParallelRun(taskID, reason: .completed, now: now)
        guard let index = tasks.firstIndex(where: { $0.id == taskID }) else {
            if minutes != nil { save() }
            return minutes
        }
        tasks[index].completed = true
        tasks[index].completedAt = now
        tasks[index].lastProgressAt = now
        save()
        return minutes
    }

    public func parallelElapsedMinutes(_ taskID: UUID, now: Date = Date()) -> Int {
        guard let run = parallelRuns.first(where: { $0.taskID == taskID }) else { return 0 }
        return max(0, Int(now.timeIntervalSince(run.startedAt) / 60))
    }

    public func parallelRecordedMinutes(_ taskID: UUID) -> Int {
        tasks.first(where: { $0.id == taskID })?.parallelRunSessions?.reduce(0) { $0 + $1.elapsedMinutes } ?? 0
    }

    public var parallelRunningTasks: [TaskItem] {
        let starts = Dictionary(uniqueKeysWithValues: parallelRuns.map { ($0.taskID, $0.startedAt) })
        return tasks.filter { starts[$0.id] != nil }.sorted {
            (starts[$0.id] ?? .distantFuture) < (starts[$1.id] ?? .distantFuture)
        }
    }

    @discardableResult
    private func endParallelRun(_ taskID: UUID, reason: ParallelRunEndReason, now: Date) -> Int? {
        guard let runIndex = parallelRuns.firstIndex(where: { $0.taskID == taskID }),
              let taskIndex = tasks.firstIndex(where: { $0.id == taskID }) else { return nil }
        let run = parallelRuns.remove(at: runIndex)
        let minutes = max(1, Int(ceil(max(0, now.timeIntervalSince(run.startedAt)) / 60)))
        tasks[taskIndex].parallelRunSessions = (tasks[taskIndex].parallelRunSessions ?? [])
            + [ParallelRunSession(startedAt: run.startedAt, endedAt: now,
                                  elapsedMinutes: minutes, reason: reason)]
        tasks[taskIndex].lastProgressAt = now
        return minutes
    }

    // MARK: - Settings

    public func setAIBackground(_ text: String) {
        aiBackground = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1500))
        save()
    }

    @discardableResult
    public func recordAISummary(_ draft: AIDraft, request: AIRequest, now: Date = Date()) throws -> UUID? {
        try draft.validate(for: request)
        let summary = draft.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        guard draft.question.isEmpty, !summary.isEmpty else { return nil }
        let record = AISummaryRecord(date: now, workflow: request.workflow,
                                     sourceIDs: request.inputs.map(\.id), summary: summary)
        aiSummaries.insert(record, at: 0)
        aiSummaries = Array(aiSummaries.prefix(50))
        save()
        return record.id
    }

    public func updateAISummaryOutcome(_ id: UUID, outcome: AISummaryOutcome, adoptedCount: Int = 0) {
        guard let index = aiSummaries.firstIndex(where: { $0.id == id }) else { return }
        aiSummaries[index].outcome = outcome
        aiSummaries[index].adoptedCount = min(aiSummaries[index].sourceIDs.count, max(0, adoptedCount))
        save()
    }

    public func deleteAISummary(_ id: UUID) {
        aiSummaries.removeAll { $0.id == id }
        save()
    }

    @discardableResult
    public func addGoal(_ title: String, now: Date = Date()) -> UUID? {
        guard let title = Self.nonBlank(title), title.count <= 150 else { return nil }
        let goal = LongTermGoal(title: title, now: now)
        goals.append(goal); save(); return goal.id
    }

    public func updateGoal(_ goal: LongTermGoal, now: Date = Date()) {
        guard let index = goals.firstIndex(where: { $0.id == goal.id }),
              let title = Self.nonBlank(goal.title), title.count <= 150 else { return }
        var value = goal
        value.title = title; value.motivation = String(value.motivation.prefix(600))
        var seen = Set<UUID>()
        value.taskIDs = Array(value.taskIDs.filter { seen.insert($0).inserted }.prefix(100))
        value.updatedAt = now
        goals[index] = value; save()
    }

    public func updateAgentPreferences(_ preferences: AgentPreferences) {
        var value = preferences
        value.planningMinutes = min(480, max(5, value.planningMinutes))
        value.startingMinutes = min(15, max(1, value.startingMinutes))
        value.notes = String(value.notes.prefix(600))
        agentPreferences = value; save()
    }

    public func setAISummaryFeedback(_ id: UUID, feedback: AIUserFeedback?) {
        guard let index = aiSummaries.firstIndex(where: { $0.id == id }) else { return }
        let before = currentSnapshot(), previousUnsaved = hasUnsavedChanges
        aiSummaries[index].feedback = feedback
        intelligenceFeedback.removeAll { $0.requestID == id }
        mirrorLegacyFeedback(aiSummaries[index])
        if !save() { restoreIntelligenceState(before); hasUnsavedChanges = previousUnsaved }
    }

    private func mirrorLegacyFeedback(_ summary: AISummaryRecord) {
        guard let feedback = summary.feedback else { return }
        for sourceID in summary.sourceIDs {
            let target = legacyAIAdoptionLinks[sourceID] ?? sourceID
            let isTask = (tasks + archive).contains { $0.id == target }
            let ideaID = thoughts.contains { $0.id == target } ? target : nil
            intelligenceFeedback.append(IntelligenceFeedback(requestID: summary.id, actionID: sourceID, ideaID: ideaID,
                createdTaskID: legacyAIAdoptionLinks[sourceID] != nil && isTask ? target : nil,
                sourceTaskID: isTask ? target : nil, kind: feedback.helpful ? .helpful : .unsuitable,
                comment: feedback.comment, date: feedback.date))
        }
    }

    @discardableResult
    public func beginPlanningRun(_ request: AIRequest, now: Date = Date()) -> UUID {
        var tools = ["读取已选内容"]
        if request.history != nil { tools.append("读取获准的历史摘要") }
        if request.agentContext != nil { tools.append(contentsOf: ["读取已选目标", "读取用户偏好", "读取相关反馈"]) }
        let run = PlanningRun(date: now, workflow: request.workflow, toolsUsed: tools)
        planningRuns.insert(run, at: 0)
        planningRuns = Array(planningRuns.prefix(20)); save(); return run.id
    }

    public func updatePlanningRun(_ id: UUID, phase: PlanningPhase, summaryID: UUID? = nil, diagnosticCode: String? = nil) {
        guard let index = planningRuns.firstIndex(where: { $0.id == id }) else { return }
        planningRuns[index].phase = phase
        if phase == .applied { planningRuns[index].toolsUsed.append("经用户确认更新选中任务") }
        if phase == .draftFilled { planningRuns[index].toolsUsed.append("经用户确认填入草稿") }
        if let summaryID { planningRuns[index].summaryID = summaryID }
        if let diagnosticCode { planningRuns[index].diagnosticCode = String(diagnosticCode.prefix(80)) }
        save()
    }

    public func validatePlanningContext(_ request: AIRequest) throws {
        guard let context = request.agentContext else { return }
        guard context.preferences == agentPreferences,
              context.goals.allSatisfy({ snapshot in goals.first { $0.id == snapshot.goal.id } == snapshot.goal }) else {
            throw AIApplyError.stale
        }
    }

    /// One save, only after explicit adoption. Snapshots remain local and detect
    /// edits made while the model was generating its answer.
    public func applyAIDraft(_ draft: AIDraft, request: AIRequest, selectedIDs: Set<UUID>,
                             originalTasks: [TaskItem], originalInbox: [InboxItem], now: Date = Date()) throws {
        try draft.validate(for: request)
        let chosen = draft.suggestions.filter { selectedIDs.contains($0.id) }
        guard !chosen.isEmpty else { return }
        try validatePlanningContext(request)
        for item in chosen {
            if request.workflow == .organize {
                guard let original = originalInbox.first(where: { $0.id == item.id }),
                      inbox.first(where: { $0.id == item.id }) == original else { throw AIApplyError.stale }
            } else {
                guard let original = originalTasks.first(where: { $0.id == item.id }), !original.completed,
                      tasks.first(where: { $0.id == item.id }) == original else { throw AIApplyError.stale }
            }
        }
        let before = currentSnapshot()
        let previousUnsaved = hasUnsavedChanges
        for item in chosen {
            if request.workflow == .organize {
                guard let index = inbox.firstIndex(where: { $0.id == item.id }) else { continue }
                let source = inbox.remove(at: index)
                let note = "原始随手记：\(source.text)"
                if item.kind == "thought" {
                    let idea = ThoughtItem(text: item.title, notes: note, createdAt: source.createdAt, updatedAt: now)
                    thoughts.insert(idea, at: 0)
                    legacyAIAdoptionLinks[source.id] = idea.id
                    for i in intelligenceFeedback.indices where intelligenceFeedback[i].actionID == source.id {
                        intelligenceFeedback[i].ideaID = idea.id
                    }
                } else {
                    let task = TaskItem(text: item.title, quadrant: Quadrant(rawValue: item.quadrant)!,
                                        createdAt: now, estimatedMinutes: item.estimatedMinutes,
                                        remindAt: source.suggestedRemindAt, lastProgressAt: now,
                                        note: note, nextAction: Self.nonBlank(item.nextAction),
                                        definitionOfDone: Self.nonBlank(item.definitionOfDone))
                    tasks.append(task)
                    legacyAIAdoptionLinks[source.id] = task.id
                    for i in intelligenceFeedback.indices where intelligenceFeedback[i].actionID == source.id {
                        intelligenceFeedback[i].createdTaskID = task.id; intelligenceFeedback[i].sourceTaskID = task.id
                    }
                }
            } else if let index = tasks.firstIndex(where: { $0.id == item.id }) {
                tasks[index].nextAction = Self.nonBlank(item.nextAction)
                tasks[index].definitionOfDone = Self.nonBlank(item.definitionOfDone)
                tasks[index].lastProgressAt = now
                if request.workflow == .plan {
                    let order = tasks[index].todayOrder ?? nextTodayOrder()
                    tasks[index].isToday = true
                    tasks[index].todayOrder = order
                }
            }
        }
        if request.workflow == .plan, let key = chosen.first(where: \.isKeyTask) {
            for index in tasks.indices { tasks[index].isDailyWin = tasks[index].id == key.id }
        }
        guard save() else {
            restoreIntelligenceState(before)
            hasUnsavedChanges = previousUnsaved
            throw IntelligenceError.persistence
        }
    }

    /// Replace the reminder settings and persist. Publishing the change lets the
    /// app layer reschedule notifications to match.
    public func updateSettings(_ new: Settings) {
        settings = new
        save()
    }

    // MARK: - Queries

    /// Tasks in a quadrant, incomplete first then by creation time.
    public func tasks(in quadrant: Quadrant) -> [TaskItem] {
        tasks.filter { $0.quadrant == quadrant }
            .sorted { ($0.completed ? 1 : 0, $0.createdAt) < ($1.completed ? 1 : 0, $1.createdAt) }
    }

    /// Today's focus list, incomplete first.
    public var todayTasks: [TaskItem] {
        tasks.filter { $0.isToday }
            .sorted {
                let l = ($0.completed ? 1 : 0, $0.isDailyWin == true ? 0 : 1,
                         $0.todayOrder ?? Int.max, $0.createdAt)
                let r = ($1.completed ? 1 : 0, $1.isDailyWin == true ? 0 : 1,
                         $1.todayOrder ?? Int.max, $1.createdAt)
                return l < r
            }
    }

    public var dailyWinTask: TaskItem? {
        tasks.first { $0.isDailyWin == true && $0.isToday && !$0.completed }
    }

    public func progressSessionsToday(now: Date = Date()) -> [ProgressSession] {
        (tasks + archive).flatMap { $0.progressSessions ?? [] }
            .filter { syncCalendar.isDate($0.endedAt, inSameDayAs: now) }
            .sorted { $0.endedAt < $1.endedAt }
    }

    public func progressedTaskCountToday(now: Date = Date()) -> Int {
        Set((tasks + archive).filter { task in
            (task.progressSessions ?? []).contains { syncCalendar.isDate($0.endedAt, inSameDayAs: now) }
        }.map(\.id)).count
    }

    /// Reorder an item in the Today planner. Completed items remain at the end;
    /// user order is otherwise stable and persisted.
    public func moveToday(_ taskID: UUID, to destination: Int) {
        var ordered = todayTasks.filter { !$0.completed }
        guard let source = ordered.firstIndex(where: { $0.id == taskID }) else { return }
        let item = ordered.remove(at: source)
        ordered.insert(item, at: max(0, min(destination, ordered.count)))
        for (order, item) in ordered.enumerated() {
            if let index = tasks.firstIndex(where: { $0.id == item.id }) {
                tasks[index].todayOrder = order
            }
        }
        save()
    }

    public var todayTotal: Int { tasks.reduce(0) { $0 + ($1.isToday ? 1 : 0) } }
    public var todayCompleted: Int { tasks.reduce(0) { $0 + (($1.isToday && $1.completed) ? 1 : 0) } }
    public var todayRemaining: Int { todayTotal - todayCompleted }

    /// Sum of estimated minutes across today's focus (nil estimates count as 0).
    public var todayEstimatedMinutes: Int {
        tasks.reduce(0) { $0 + ($1.isToday ? ($1.estimatedMinutes ?? 0) : 0) }
    }
    /// Estimated minutes still left to do today (incomplete today tasks).
    public var todayRemainingEstimatedMinutes: Int {
        tasks.reduce(0) { $0 + (($1.isToday && !$1.completed) ? ($1.estimatedMinutes ?? 0) : 0) }
    }
    public var todayRemainingUnestimatedCount: Int {
        tasks.reduce(0) { total, task in
            total + ((task.isToday && !task.completed && task.estimatedMinutes == nil) ? 1 : 0)
        }
    }

    /// Everything finished today — the "成就感" number, whether or not the task
    /// was ever flagged for today.
    public func completedTodayCount(now: Date = Date()) -> Int {
        tasks.reduce(0) { acc, t in
            guard t.completed, let at = t.completedAt else { return acc }
            return acc + (syncCalendar.isDate(at, inSameDayAs: now) ? 1 : 0)
        }
    }

    public var inboxCount: Int { inbox.count }

    private func nextTodayOrder() -> Int {
        (tasks.compactMap { $0.isToday ? $0.todayOrder : nil }.max() ?? -1) + 1
    }

    private static func nonBlank(_ text: String?) -> String? {
        guard let value = text?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        return value
    }
}

extension Store {
    private func restoreIntelligenceState(_ snapshot: Snapshot) {
        inbox = snapshot.inbox; tasks = snapshot.tasks; thoughts = snapshot.thoughts ?? []
        aiSummaries = snapshot.aiSummaries ?? []
        legacyAIAdoptionLinks = snapshot.legacyAIAdoptionLinks ?? [:]
        confirmedPreferences = snapshot.confirmedPreferences ?? []
        intelligenceFeedback = snapshot.intelligenceFeedback ?? []
        intelligenceAdoptions = snapshot.intelligenceAdoptions ?? []
        dailyAllocations = snapshot.dailyAllocations ?? []
    }
    private func intelligenceTransaction<T>(_ body: () throws -> T) throws -> T {
        let before = currentSnapshot(), previousUnsaved = hasUnsavedChanges
        do {
            let value = try body()
            guard save() else { throw IntelligenceError.persistence }
            return value
        } catch {
            restoreIntelligenceState(before); hasUnsavedChanges = previousUnsaved
            throw error
        }
    }

    public func prepareIntelligenceRequest(intent: IntelligenceIntent, subjectID: UUID? = nil,
        availableMinutes: Int = 30, energy: String = "一般", instruction: String = "",
        options: IntelligenceContextOptions = .init(), now: Date = Date()) throws -> IntelligenceRequest {
        guard (2...480).contains(availableMinutes), instruction.count <= 2000, energy.count <= 100 else { throw IntelligenceError.invalidInput }
        var evidence: [IntelligenceEvidence] = []
        func add(id: UUID, kind: String, title: String, body: String, date: Date) {
            guard !evidence.contains(where: { $0.id == id }) else { return }
            let revision = intelligenceRevision([kind, title, body, String(date.timeIntervalSince1970)])
            evidence.append(IntelligenceEvidence(id: id, kind: kind, title: title, body: body, date: date, revision: revision))
        }
        let allTasks = tasks + archive
        var relatedIdeas: [ThoughtItem] = []
        var relatedTasks: [TaskItem] = []
        switch intent {
        case .idea:
            guard let idea = thoughts.first(where: { $0.id == subjectID }) else { throw IntelligenceError.missingSource }
            relatedIdeas = [idea]
            if options.includeLinkedTasks { relatedTasks = allTasks.filter { idea.linkedTaskIDs.contains($0.id) } }
        case .unblock:
            guard let task = tasks.first(where: { $0.id == subjectID && !$0.completed }) else { throw IntelligenceError.missingSource }
            relatedTasks = [task]
            if options.includeLinkedTasks { relatedIdeas = thoughts.filter { $0.linkedTaskIDs.contains(task.id) } }
        case .today:
            // Relation and current commitments first; unrelated tasks are bounded candidates.
            relatedTasks = tasks.filter { !$0.completed || $0.isDailyWin == true }.sorted {
                if $0.completed != $1.completed { return !$0.completed }
                if $0.isToday != $1.isToday { return $0.isToday }
                if ($0.dueAt != nil) != ($1.dueAt != nil) { return $0.dueAt != nil }
                return ($0.dueAt ?? $0.createdAt) < ($1.dueAt ?? $1.createdAt)
            }
            if options.includeLinkedTasks {
                let ids = Set(relatedTasks.map(\.id))
                relatedIdeas = thoughts.filter { !Set($0.linkedTaskIDs).isDisjoint(with: ids) }
            }
        }
        for idea in relatedIdeas {
            add(id: idea.id, kind: "idea", title: idea.text,
                body: "描述：\(idea.notes ?? "")\n阶段：\(idea.stage.title)\n下一问题：\(idea.nextQuestion ?? "未确定")", date: idea.updatedAt)
        }
        for task in relatedTasks {
            var body = "象限：\(task.quadrant.title)\n下一步：\(task.nextAction ?? "未确定")\n停止条件：\(task.definitionOfDone ?? "未确定")\n完成：\(task.completed)\n今天：\(task.isToday)\n今日重点：\(task.isDailyWin == true)\n整体估时（非本次投入）：\(task.estimatedMinutes.map(String.init) ?? "未知")"
            if let due = task.dueAt { body += "\n截止：\(due.ISO8601Format())" }
            if let run = parallelRuns.first(where: { $0.taskID == task.id }) {
                body += "\n正在并行运行（不计入个人容量），开始：\(run.startedAt.ISO8601Format())；检查：\(run.nextCheckAt?.ISO8601Format() ?? "未设")"
            }
            if activeTimer?.taskID == task.id { body += "\n正在个人专注" }
            if let scheduled = task.scheduledAt, syncCalendar.isDate(scheduled, inSameDayAs: now) {
                body += "\n今日时间块：\(scheduled.ISO8601Format())，\(task.scheduledMinutes.map(String.init) ?? "未知")分钟"
            }
            if options.includeHistory {
                body += "\n已记录个人投入：\(task.actualMinutes.map(String.init) ?? "未知")；完成时间：\(task.completedAt?.ISO8601Format() ?? "未记录")"
                body += "\n用户明确卡点：" + (task.blockerEntries ?? []).suffix(5).map { "\($0.at.ISO8601Format()) \($0.reason.title)" }.joined(separator: "；")
            }
            add(id: task.id, kind: "task", title: task.text, body: body, date: task.lastProgressAt ?? task.createdAt)
        }
        if options.includeIdeaRecords {
            for idea in relatedIdeas {
                for entry in idea.entries.sorted(by: { $0.createdAt > $1.createdAt }) {
                    add(id: entry.id, kind: "ideaEntry", title: "\(idea.text) · \(entry.kind.title)", body: "来源：\(entry.author ?? "用户记录")\n\(entry.text)", date: entry.createdAt)
                }
            }
        }
        let taskIDs = Set(relatedTasks.map(\.id))
        let ideaIDs = intent == .idea ? Set(subjectID.map { [$0] } ?? [])
            : Set(thoughts.filter { !Set($0.linkedTaskIDs).isDisjoint(with: taskIDs) }.map(\.id))
        for allocation in dailyAllocations where taskIDs.contains(allocation.taskID) && syncCalendar.isDate(allocation.day, inSameDayAs: now) {
            add(id: allocation.id, kind: "dailyAllocation", title: "当日\(allocation.kind.title)", body: "taskID: \(allocation.taskID)；确认投入：\(allocation.plannedMinutes.map(String.init) ?? "未知")分钟", date: allocation.day)
        }
        for goal in goals where options.selectedGoalIDs.contains(goal.id) {
            add(id: goal.id, kind: "goal", title: goal.title, body: "\(goal.motivation)；状态：\(goal.status.title)", date: goal.updatedAt)
        }
        if options.includeMemories {
            let applicable = confirmedPreferences.filter { ($0.ideaID == nil || ideaIDs.contains($0.ideaID!)) && ($0.expiresAt == nil || $0.expiresAt! > now) }.sorted { ($0.ideaID != nil ? 0 : 1, -$0.updatedAt.timeIntervalSince1970) < ($1.ideaID != nil ? 0 : 1, -$1.updatedAt.timeIntervalSince1970) }
            for preference in applicable {
                add(id: preference.id, kind: "preference", title: preference.scopeLabel, body: "\(preference.text)\n来源：\(preference.source)；有效期：\(preference.expiresAt?.ISO8601Format() ?? "未设")", date: preference.updatedAt)
            }
        }
        if options.includeHistory {
            for summary in aiSummaries.sorted(by: { $0.date > $1.date }) {
                let linked = Set(summary.sourceIDs.map { legacyAIAdoptionLinks[$0] ?? $0 })
                guard !linked.isDisjoint(with: taskIDs) || !linked.isDisjoint(with: ideaIDs) else { continue }
                add(id: summary.id, kind: "aiSummary", title: summary.workflow.title,
                    body: "AI 生成的旧分析，不是用户确认的事实：\(summary.summary)\n状态：\(summary.outcome.title)", date: summary.date)
            }
            for feedback in intelligenceFeedback.sorted(by: { $0.date > $1.date }) where feedback.ideaID.map(ideaIDs.contains) == true || feedback.createdTaskID.map(taskIDs.contains) == true || feedback.sourceTaskID.map(taskIDs.contains) == true {
                add(id: feedback.id, kind: "feedback", title: feedback.kind.title, body: "用户对建议的反馈，不代表稳定习惯：\(feedback.comment)", date: feedback.date)
            }
        }
        var request = IntelligenceRequest(intent: intent, subjectID: subjectID, availableMinutes: availableMinutes, energy: energy, instruction: instruction, options: options, evidence: [], now: now, timeZone: syncCalendar.timeZone.identifier)
        // The manifest detects new authorized facts while unrelated records stay outside this scope.
        request.scopeRevision = intelligenceRevision(evidence.map { "\($0.kind):\($0.id.uuidString):\($0.revision)" }.sorted())
        let requiredSourceID = subjectID ?? relatedTasks.first?.id
        // Never truncate the current subject silently. Confirmed preferences come before optional history.
        let ordered = evidence.enumerated().sorted { left, right in
            func priority(_ record: IntelligenceEvidence) -> Int {
                if record.id == requiredSourceID { return 0 }
                if record.kind == "preference" { return 1 }
                if record.kind == "ideaEntry" || record.kind == "feedback" { return 2 }
                if record.kind == "idea" { return 3 }
                if record.kind == "task" { return 4 }
                if record.kind == "aiSummary" { return 6 }
                return 5
            }
            return (priority(left.element), left.offset) < (priority(right.element), right.offset)
        }.map(\.element)
        for record in ordered {
            request.evidence.append(record)
            do { _ = try request.data() }
            catch {
                request.evidence.removeLast(); request.omittedRecordCount += 1
                if record.id == requiredSourceID || request.evidence.isEmpty { throw IntelligenceError.contextTooLarge }
            }
        }
        // The omission counter can slightly increase serialized size.
        while (try? request.data()) == nil && request.evidence.count > 1 {
            request.evidence.removeLast(); request.omittedRecordCount += 1
        }
        _ = try request.data()
        return request
    }

    public func applyIntelligenceProposal(_ proposal: IntelligenceProposal, request: IntelligenceRequest,
        selectedActionIDs: Set<UUID>, now: Date = Date()) throws -> IntelligenceAdoptionReceipt {
        try proposal.validate(for: request)
        guard !selectedActionIDs.isEmpty, selectedActionIDs.isSubset(of: Set(proposal.actions.map(\.id))) else { throw IntelligenceError.invalidProposal }
        if let receipt = intelligenceAdoptions.first(where: { $0.requestID == request.id }) {
            guard receipt.undoneAt == nil, Set(receipt.actionIDs) == selectedActionIDs else { throw IntelligenceError.alreadyApplied }
            return receipt
        }
        guard request.timeZone == syncCalendar.timeZone.identifier,
              syncCalendar.isDate(request.now, inSameDayAs: now) else { throw IntelligenceError.stale }
        let refreshed = try prepareIntelligenceRequest(intent: request.intent, subjectID: request.subjectID,
            availableMinutes: request.availableMinutes, energy: request.energy, instruction: request.instruction, options: request.options, now: now)
        let currentEvidence = Dictionary(uniqueKeysWithValues: refreshed.evidence.map { ($0.id, $0.revision) })
        guard request.scopeRevision == refreshed.scopeRevision,
              request.evidence.allSatisfy({ currentEvidence[$0.id] == $0.revision }) else { throw IntelligenceError.stale }
        let chosen = proposal.actions.filter { selectedActionIDs.contains($0.id) }
        guard intelligenceAdoptions.allSatisfy({ Set($0.actionIDs).isDisjoint(with: selectedActionIDs) }) else { throw IntelligenceError.alreadyApplied }
        if chosen.contains(where: { $0.kind == .setDailyWin }) {
            let knownIDs = Set(request.evidence.filter { $0.kind == "task" }.map(\.id))
            guard tasks.filter({ $0.isDailyWin == true }).allSatisfy({ knownIDs.contains($0.id) }) else { throw IntelligenceError.stale }
        }
        return try intelligenceTransaction {
            let beforeTasks = tasks, beforeIdeas = thoughts, beforeAllocations = dailyAllocations
            var created: [UUID] = [], mapping: [UUID: UUID] = [:]
            for action in chosen {
                switch action.kind {
                case .createLinkedTask:
                    guard let index = thoughts.firstIndex(where: { $0.id == action.sourceID }) else { throw IntelligenceError.stale }
                    let task = TaskItem(text: action.title.trimmingCharacters(in: .whitespacesAndNewlines), quadrant: .importantNotUrgent, createdAt: now, nextAction: action.nextAction, definitionOfDone: action.definitionOfDone)
                    tasks.append(task); thoughts[index].linkedTaskIDs.append(task.id); thoughts[index].updatedAt = now
                    created.append(task.id); mapping[action.id] = task.id
                case .updateIdeaNextQuestion:
                    guard let index = thoughts.firstIndex(where: { $0.id == action.sourceID }) else { throw IntelligenceError.stale }
                    if thoughts[index].nextQuestion != action.text { thoughts[index].nextQuestionUpdatedAt = now }
                    thoughts[index].nextQuestion = action.text; thoughts[index].updatedAt = now
                case .appendAIExplorationRecord:
                    guard let index = thoughts.firstIndex(where: { $0.id == action.sourceID }) else { throw IntelligenceError.stale }
                    thoughts[index].entries.append(IdeaEntry(text: action.text, createdAt: now, author: "AI 辅助探索")); thoughts[index].updatedAt = now
                case .updateTaskNextStep, .setTodaySelection, .setDailyWin:
                    guard let index = tasks.firstIndex(where: { $0.id == action.sourceID && !$0.completed }) else { throw IntelligenceError.stale }
                    mapping[action.id] = action.sourceID
                    if action.kind == .updateTaskNextStep {
                        tasks[index].nextAction = action.nextAction; tasks[index].definitionOfDone = action.definitionOfDone
                    } else if action.kind == .setTodaySelection {
                        guard action.selected || activeTimer?.taskID != action.sourceID else { throw IntelligenceError.outOfScope }
                        tasks[index].isToday = action.selected
                        tasks[index].todayOrder = action.selected ? (tasks[index].todayOrder ?? nextTodayOrder()) : nil
                        if !action.selected { tasks[index].isDailyWin = false; tasks[index].scheduledAt = nil; tasks[index].scheduledMinutes = nil; tasks[index].scheduleReminderLeadMinutes = nil }
                    } else {
                        for i in tasks.indices where tasks[i].isDailyWin == true { tasks[i].isDailyWin = false }
                        tasks[index].isDailyWin = true
                        tasks[index].isToday = true; tasks[index].todayOrder = tasks[index].todayOrder ?? nextTodayOrder()
                    }
                    if action.kind == .setTodaySelection && action.selected {
                        try upsertDailyAllocation(taskID: action.sourceID, plannedMinutes: action.plannedMinutes, kind: action.allocationKind, now: now)
                    }
                    tasks[index].lastProgressAt = now
                }
            }
            let changedTaskIDs = Set(tasks.filter { task in beforeTasks.first { $0.id == task.id } != task }.map(\.id))
            let changedIdeaIDs = Set(thoughts.filter { idea in beforeIdeas.first { $0.id == idea.id } != idea }.map(\.id))
            let changedAllocationIDs = Set(dailyAllocations.filter { item in beforeAllocations.first { $0.id == item.id } != item }.map(\.id))
            let receipt = IntelligenceAdoptionReceipt(requestID: request.id, actionIDs: chosen.map(\.id), createdTaskIDs: created, date: now, timeZone: syncCalendar.timeZone.identifier, actionTaskIDs: mapping,
                beforeTasks: beforeTasks.filter { changedTaskIDs.contains($0.id) }, afterTasks: tasks.filter { changedTaskIDs.contains($0.id) },
                beforeIdeas: beforeIdeas.filter { changedIdeaIDs.contains($0.id) }, afterIdeas: thoughts.filter { changedIdeaIDs.contains($0.id) },
                beforeAllocations: beforeAllocations.filter { changedAllocationIDs.contains($0.id) }, afterAllocations: dailyAllocations.filter { changedAllocationIDs.contains($0.id) })
            intelligenceAdoptions.append(receipt)
            return receipt
        }
    }

    public func undoIntelligenceAdoption(_ receiptID: UUID, now: Date = Date()) throws {
        guard let index = intelligenceAdoptions.firstIndex(where: { $0.id == receiptID }), intelligenceAdoptions[index].undoneAt == nil else { throw IntelligenceError.alreadyApplied }
        let receipt = intelligenceAdoptions[index]
        if !receipt.afterTasks.isEmpty || !receipt.afterAllocations.isEmpty {
            guard syncCalendar.isDate(receipt.date, inSameDayAs: now), receipt.timeZone == nil || receipt.timeZone == syncCalendar.timeZone.identifier else { throw IntelligenceError.crossDayUndo }
        }
        // Conservative entity-level comparison also protects scheduling and timer edits.
        guard receipt.afterTasks.allSatisfy({ after in tasks.first { $0.id == after.id } == after }),
              receipt.afterIdeas.allSatisfy({ after in thoughts.first { $0.id == after.id } == after }),
              receipt.afterAllocations.allSatisfy({ after in dailyAllocations.first { $0.id == after.id } == after }),
              !receipt.createdTaskIDs.contains(where: { taskID in
                  activeTimer?.taskID == taskID || parallelRuns.contains { $0.taskID == taskID }
                  || goals.contains { $0.taskIDs.contains(taskID) }
                  || thoughts.contains { idea in idea.linkedTaskIDs.contains(taskID) && !receipt.afterIdeas.contains(where: { $0.id == idea.id }) }
                  || dailyAllocations.contains { $0.taskID == taskID }
              }) else { throw IntelligenceError.conflict }
        try intelligenceTransaction {
            let taskIDs = Set(receipt.afterTasks.map(\.id)), allocationIDs = Set(receipt.afterAllocations.map(\.id))
            tasks.removeAll { taskIDs.contains($0.id) }; tasks.append(contentsOf: receipt.beforeTasks)
            for idea in receipt.beforeIdeas { if let i = thoughts.firstIndex(where: { $0.id == idea.id }) { thoughts[i] = idea } }
            dailyAllocations.removeAll { allocationIDs.contains($0.id) }; dailyAllocations.append(contentsOf: receipt.beforeAllocations)
            intelligenceAdoptions[index].undoneAt = now
        }
    }

    public func confirmPreference(text: String, ideaID: UUID? = nil, source: String = "用户明确确认", expiresAt: Date? = nil, now: Date = Date()) throws -> ConfirmedPreference {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 500, source.count <= 500, ideaID == nil || thoughts.contains(where: { $0.id == ideaID }) else { throw IntelligenceError.invalidInput }
        return try intelligenceTransaction {
            let preference = ConfirmedPreference(text: text, ideaID: ideaID, source: source, updatedAt: now, expiresAt: expiresAt)
            confirmedPreferences.append(preference); return preference
        }
    }
    public func updateConfirmedPreference(_ id: UUID, text: String, expiresAt: Date? = nil, now: Date = Date()) throws {
        guard let index = confirmedPreferences.firstIndex(where: { $0.id == id }), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.count <= 500 else { throw IntelligenceError.invalidInput }
        try intelligenceTransaction { confirmedPreferences[index].text = text; confirmedPreferences[index].updatedAt = now; confirmedPreferences[index].expiresAt = expiresAt }
    }
    public func forgetConfirmedPreference(_ id: UUID) throws {
        try intelligenceTransaction { confirmedPreferences.removeAll { $0.id == id } }
    }
    public func recordIntelligenceFeedback(requestID: UUID, actionID: UUID? = nil, ideaID: UUID? = nil, sourceTaskID: UUID? = nil, kind: IntelligenceFeedbackKind, comment: String = "", now: Date = Date()) throws {
        guard comment.count <= 500, sourceTaskID == nil || (tasks + archive).contains(where: { $0.id == sourceTaskID }) else { throw IntelligenceError.invalidInput }
        let receipt = intelligenceAdoptions.first { $0.requestID == requestID }
        let taskID = actionID.flatMap { receipt?.actionTaskIDs[$0] }
        try intelligenceTransaction {
            intelligenceFeedback.append(IntelligenceFeedback(requestID: requestID, actionID: actionID, ideaID: ideaID, createdTaskID: taskID, sourceTaskID: sourceTaskID, kind: kind, comment: comment, date: now))
        }
    }
    private func upsertDailyAllocation(taskID: UUID, plannedMinutes: Int?, kind: DailyAllocationKind, now: Date) throws {
        guard tasks.contains(where: { $0.id == taskID }), plannedMinutes.map({ (1...1440).contains($0) }) ?? true,
              !parallelRuns.contains(where: { $0.taskID == taskID }) || kind == .check else { throw IntelligenceError.invalidInput }
        let day = syncCalendar.startOfDay(for: now)
        if let index = dailyAllocations.firstIndex(where: { $0.taskID == taskID && syncCalendar.isDate($0.day, inSameDayAs: now) }) {
            dailyAllocations[index].plannedMinutes = plannedMinutes; dailyAllocations[index].kind = kind
        } else { dailyAllocations.append(DailyAllocation(taskID: taskID, day: day, plannedMinutes: plannedMinutes, kind: kind)) }
    }
    public func setDailyAllocation(taskID: UUID, plannedMinutes: Int?, kind: DailyAllocationKind = .personal, now: Date = Date()) throws {
        try intelligenceTransaction { try upsertDailyAllocation(taskID: taskID, plannedMinutes: plannedMinutes, kind: kind, now: now) }
    }
    public func dailyLoad(now: Date = Date()) -> DailyLoad {
        calculateDailyLoad(tasks: tasks, allocations: dailyAllocations, now: now)
    }
    public func projectedDailyLoad(for proposal: IntelligenceProposal, selectedActionIDs: Set<UUID>, now: Date = Date()) throws -> DailyLoad {
        guard selectedActionIDs.isSubset(of: Set(proposal.actions.map(\.id))) else { throw IntelligenceError.invalidProposal }
        var projectedTasks = tasks, projectedAllocations = dailyAllocations
        for action in proposal.actions where selectedActionIDs.contains(action.id) {
            guard [.setTodaySelection, .setDailyWin].contains(action.kind) else { continue }
            guard let index = projectedTasks.firstIndex(where: { $0.id == action.sourceID && !$0.completed }) else { throw IntelligenceError.missingSource }
            projectedTasks[index].isToday = action.kind == .setDailyWin || action.selected
            if action.kind == .setTodaySelection && action.selected {
                projectedAllocations.removeAll { $0.taskID == action.sourceID && syncCalendar.isDate($0.day, inSameDayAs: now) }
                projectedAllocations.append(DailyAllocation(taskID: action.sourceID, day: syncCalendar.startOfDay(for: now), plannedMinutes: action.plannedMinutes, kind: action.allocationKind))
            }
        }
        return calculateDailyLoad(tasks: projectedTasks, allocations: projectedAllocations, now: now)
    }
    private func calculateDailyLoad(tasks: [TaskItem], allocations: [DailyAllocation], now: Date) -> DailyLoad {
        var result = DailyLoad(personalMinutes: 0, checkMinutes: 0, unknownCount: 0)
        for task in tasks where task.isToday && !task.completed {
            let allocation = allocations.first { $0.taskID == task.id && syncCalendar.isDate($0.day, inSameDayAs: now) }
            let parallel = parallelRuns.contains { $0.taskID == task.id }
            if parallel {
                if allocation?.kind == .check, let minutes = allocation?.plannedMinutes { result.checkMinutes += minutes }
                else { result.unknownCount += 1 }
            } else if let at = task.scheduledAt, syncCalendar.isDate(at, inSameDayAs: now), let minutes = task.scheduledMinutes {
                if allocation?.kind == .check { result.checkMinutes += minutes } else { result.personalMinutes += minutes }
            } else if let minutes = allocation?.plannedMinutes {
                if allocation?.kind == .check { result.checkMinutes += minutes } else { result.personalMinutes += minutes }
            } else { result.unknownCount += 1 }
        }
        return result
    }
}

// MARK: - Atomic, account-scoped sync
extension Store {
    public var pendingSyncChangeCount: Int { syncState?.pending.count ?? 0 }
    private var syncCalendar: Calendar {
        var value = calendar
        value.timeZone = TimeZone(identifier: planningTimeZone) ?? calendar.timeZone
        return value
    }
    private func migrateSyncDayPlans() {
        captureSyncDayPlan()
        for allocation in dailyAllocations {
            let day = SyncDayPlan.dayKey(for: allocation.day, timeZone: syncCalendar.timeZone)
            let key = SyncEntityKey(kind: .dayPlan, id: "\(day)@\(planningTimeZone)")
            let index: Int
            if let existing = syncDayPlans.firstIndex(where: { $0.key == key }) { index = existing }
            else {
                syncDayPlans.append(SyncDayPlan(dayKey: day, timeZone: planningTimeZone, orderedTaskIDs: []))
                index = syncDayPlans.count - 1
            }
            let value = SyncTaskAllocation(id: allocation.id, plannedMinutes: allocation.plannedMinutes, kind: allocation.kind.rawValue)
            if let entry = syncDayPlans[index].taskPlans.firstIndex(where: { $0.taskID == allocation.taskID }) {
                syncDayPlans[index].taskPlans[entry].allocation = value
            } else {
                syncDayPlans[index].taskPlans.append(SyncTaskPlan(taskID: allocation.taskID, allocation: value))
            }
            syncDayPlans[index].taskPlans.sort { $0.taskID.uuidString < $1.taskID.uuidString }
        }
    }
    private func captureSyncDayPlan() {
        let date = lastActiveDay ?? Date(), zone = syncCalendar.timeZone
        let key = SyncDayPlan.dayKey(for: date, timeZone: zone)
        let previousPlan = syncDayPlans.first { $0.dayKey == key && $0.timeZone == planningTimeZone }
        let selected = tasks.filter(\.isToday).sorted {
            if $0.todayOrder != $1.todayOrder { return ($0.todayOrder ?? Int.max) < ($1.todayOrder ?? Int.max) }
            return $0.id.uuidString < $1.id.uuidString
        }
        let activeIDs = Set(tasks.map(\.id))
        var orderedIDs = selected.map(\.id)
        for (position, id) in (previousPlan?.orderedTaskIDs ?? []).enumerated() where !activeIDs.contains(id) {
            orderedIDs.insert(id, at: min(position, orderedIDs.count))
        }
        // References can precede their tasks in paginated pulls, and archived
        // tasks still belong to historical plans. Preserve those existing slots.
        var entries = (previousPlan?.taskPlans ?? []).filter { !activeIDs.contains($0.taskID) }
        for task in tasks {
            let allocation = dailyAllocations.first { $0.taskID == task.id && syncCalendar.isDate($0.day, inSameDayAs: date) }
            if task.scheduledAt != nil || allocation != nil {
                let suppressed = task.scheduledAt != nil && localScheduleReminderSuppressions[task.id] == task.scheduledAt
                let lead = suppressed ? previousPlan?.taskPlans.first { $0.taskID == task.id }?.scheduleReminderLeadMinutes : task.scheduleReminderLeadMinutes
                entries.append(SyncTaskPlan(taskID: task.id, scheduledAt: task.scheduledAt,
                    scheduledMinutes: task.scheduledMinutes, scheduleReminderLeadMinutes: lead,
                    allocation: allocation.map { SyncTaskAllocation(id: $0.id, plannedMinutes: $0.plannedMinutes, kind: $0.kind.rawValue) }))
            }
        }
        let previousOrder = previousPlan?.taskPlans.map(\.taskID) ?? []
        entries.sort {
            let lhs = previousOrder.firstIndex(of: $0.taskID), rhs = previousOrder.firstIndex(of: $1.taskID)
            if lhs != rhs { return (lhs ?? Int.max) < (rhs ?? Int.max) }
            return $0.taskID.uuidString < $1.taskID.uuidString
        }
        let hiddenWin = previousPlan?.dailyWinTaskID.flatMap { activeIDs.contains($0) ? nil : $0 }
        let plan = SyncDayPlan(dayKey: key, timeZone: planningTimeZone, orderedTaskIDs: orderedIDs,
                               dailyWinTaskID: selected.first { $0.isDailyWin == true }?.id ?? hiddenWin,
                               taskPlans: entries)
        if let index = syncDayPlans.firstIndex(where: { $0.key == plan.key }) { syncDayPlans[index] = plan }
        else if !selected.isEmpty || !entries.isEmpty { syncDayPlans.append(plan) }
    }
    private func projectSyncDayPlan(at date: Date) {
        let day = SyncDayPlan.dayKey(for: date, timeZone: syncCalendar.timeZone)
        let plan = syncDayPlans.first { $0.dayKey == day && $0.timeZone == planningTimeZone }
        for index in tasks.indices {
            let id = tasks[index].id, position = plan?.orderedTaskIDs.firstIndex(of: tasks[index].id)
            tasks[index].isToday = position != nil
            tasks[index].todayOrder = position
            if tasks[index].isDailyWin == true || plan?.dailyWinTaskID != nil {
                tasks[index].isDailyWin = plan?.dailyWinTaskID == id
            }
            let entry = plan?.taskPlans.first { $0.taskID == id }
            tasks[index].scheduledAt = entry?.scheduledAt
            tasks[index].scheduledMinutes = entry?.scheduledMinutes
            let suppressed = entry?.scheduledAt != nil && localScheduleReminderSuppressions[id] == entry?.scheduledAt
            tasks[index].scheduleReminderLeadMinutes = suppressed ? nil : entry?.scheduleReminderLeadMinutes
        }
        dailyAllocations.removeAll { syncCalendar.isDate($0.day, inSameDayAs: date) }
        for entry in plan?.taskPlans ?? [] {
            if let a = entry.allocation, let kind = DailyAllocationKind(rawValue: a.kind) {
                dailyAllocations.append(DailyAllocation(id: a.id, taskID: entry.taskID,
                    day: syncCalendar.startOfDay(for: date), plannedMinutes: a.plannedMinutes, kind: kind))
            }
        }
    }
    private func syncPayloads() throws -> [SyncEntityKey: SyncJSON] {
        var values: [SyncEntityKey: SyncJSON] = [:]
        for value in inbox { values[.uuid(kind: .inbox, id: value.id)] = try SyncPayload.wrap(value) }
        for value in tasks { values[.uuid(kind: .task, id: value.id)] = try SyncPayload.task(value, archived: false) }
        for value in archive { values[.uuid(kind: .task, id: value.id)] = try SyncPayload.task(value, archived: true) }
        for value in thoughts { values[.uuid(kind: .idea, id: value.id)] = try SyncPayload.wrap(value) }
        for value in goals { values[.uuid(kind: .goal, id: value.id)] = try SyncPayload.wrap(value) }
        for value in syncDayPlans { values[value.key] = try SyncPayload.wrap(value) }
        values[.init(kind: .profile, id: "profile")] = try SyncPayload.wrap(SyncProfile(background: aiBackground,
            preferences: agentPreferences, confirmedPreferences: confirmedPreferences, planningTimeZone: planningTimeZone))
        if syncState?.shadow.contains(where: { $0.kind == .profile && $0.deletedAt != nil }) == true,
           aiBackground.isEmpty, agentPreferences == AgentPreferences(), confirmedPreferences.isEmpty {
            values.removeValue(forKey: .init(kind: .profile, id: "profile"))
        }
        for remote in syncState?.shadow ?? [] where remote.deletedAt == nil {
            if let value = values[remote.key] {
                values[remote.key] = try SyncPayload.preserveUnknown(value, raw: remote.payload, kind: remote.kind)
            }
        }
        return values
    }
    private func refreshSyncPending() throws {
        guard var state = syncState else { return }
        let previousPending = Dictionary(uniqueKeysWithValues: state.pending.map { ($0.key, $0) })
        let values = try syncPayloads(), shadows = Dictionary(uniqueKeysWithValues: state.shadow.map { ($0.key, $0) })
        let keys = Set(values.keys).union(shadows.keys)
        state.pending = keys.compactMap { key in
            let baseline = shadows[key], payload = values[key]
            let oldPayload = baseline?.deletedAt == nil ? baseline?.payload : nil
            guard payload != oldPayload else { return nil }
            return SyncMutation(key: key, baseRevision: baseline?.revision ?? 0, payload: payload, deleted: payload == nil)
        }.sorted { ($0.kind.rawValue, $0.id) < ($1.kind.rawValue, $1.id) }
        // An edit may temporarily match the old shadow while a create/update
        // is in flight. Its dependency group must survive until that receipt
        // establishes the new baseline (e.g. bulk-delete in-flight creates).
        let future = Set((state.inflight?.mutations ?? []).filter { mutation in
            values[mutation.key] != (mutation.deleted ? nil : mutation.payload)
        }.map(\.key))
        let active = Set(state.pending.map(\.key)).union(future)
        var groups: [Set<SyncEntityKey>] = []
        for prior in state.pendingGroups {
            var joined = Set(prior).intersection(active)
            guard !joined.isEmpty else { continue }
            groups.removeAll { group in
                guard !group.isDisjoint(with: joined) else { return false }
                joined.formUnion(group); return true
            }
            groups.append(joined)
        }
        let changed = Set(state.pending.filter { mutation in
            previousPending[mutation.key]?.payload != mutation.payload || previousPending[mutation.key]?.deleted != mutation.deleted
        }.map(\.key)).union(future.filter { previousPending[$0]?.payload != values[$0] })
        if state.mergeConfirmed && !applyingSync && !changed.isEmpty {
            var joined = changed
            groups.removeAll { group in
                guard !group.isDisjoint(with: changed) else { return false }
                joined.formUnion(group); return true
            }
            groups.append(joined)
        }
        let assigned = groups.reduce(into: Set<SyncEntityKey>()) { $0.formUnion($1) }
        for key in active.subtracting(assigned) { groups.append([key]) }
        state.pendingGroups = groups.map { $0.sorted { ($0.kind.rawValue, $0.id) < ($1.kind.rawValue, $1.id) } }
        // A choice authorizes the exact versions shown when it was made.
        // Editing any member invalidates the whole unfinished transaction's
        // choices, so the final sibling choice cannot discard a newer edit.
        let changedConflictGroups = Set(state.conflicts.filter {
            $0.local != values[$0.key]
        }.compactMap(\.groupID))
        for index in state.conflicts.indices {
            if state.conflicts[index].groupID.map(changedConflictGroups.contains) == true {
                state.conflicts[index].choice = nil
            }
            state.conflicts[index].local = values[state.conflicts[index].key]
        }
        syncState = state
    }
    /// Restore in-memory sync/content on write failure without load-time timer,
    /// planning-history, or notification side effects.
    private func syncTransaction(_ work: () throws -> Void) throws {
        guard !persistenceLocked else { throw SyncStoreError.persistence }
        guard !hasUnsavedChanges else { throw SyncStoreError.unsavedChanges }
        let before = currentSnapshot()
        applyingSync = true
        defer { applyingSync = false }
        do {
            try work()
            guard save() else { throw SyncStoreError.persistence }
        } catch {
            inbox = before.inbox; tasks = before.tasks; archive = before.archive ?? []; thoughts = before.thoughts ?? []
            goals = before.goals ?? []; aiBackground = before.aiBackground ?? ""
            agentPreferences = before.agentPreferences ?? AgentPreferences(); confirmedPreferences = before.confirmedPreferences ?? []
            dailyAllocations = before.dailyAllocations ?? []
            syncState = before.syncState; syncDayPlans = before.syncDayPlans ?? []
            planningTimeZone = before.planningTimeZone ?? calendar.timeZone.identifier
            lastActiveDay = before.lastActiveDay
            hasUnsavedChanges = false
            throw error
        }
    }
    public func bindSyncAccount(accountID: String) throws {
        let owner = accountID.lowercased()
        guard !owner.isEmpty else { throw SyncStoreError.accountMismatch }
        if let state = syncState, state.accountID != owner { throw SyncStoreError.accountMismatch }
        try syncTransaction {
            if syncState == nil { syncState = SyncLocalState(accountID: owner) }
            syncState?.enabled = true
        }
    }
    public func suspendSync() throws {
        try syncTransaction { syncState?.enabled = false }
    }
    private func initialSyncGroups(_ state: SyncLocalState) -> [Set<SyncEntityKey>] {
        var groups: [Set<SyncEntityKey>] = []
        for commit in state.initialCommits {
            var keys = Set(commit.entities.map(\.key))
            groups.removeAll { group in
                guard !group.isDisjoint(with: keys) else { return false }
                keys.formUnion(group); return true
            }
            groups.append(keys)
        }
        let assigned = groups.reduce(into: Set<SyncEntityKey>()) { $0.formUnion($1) }
        for entity in state.initialRemote where !assigned.contains(entity.key) { groups.append([entity.key]) }
        return groups
    }
    public var syncMergePreview: SyncMergePreview? {
        guard let state = syncState, state.initialPullComplete, !state.mergeConfirmed,
              let values = try? syncPayloads() else { return nil }
        let cloud = Dictionary(uniqueKeysWithValues: state.initialRemote.map { ($0.key, $0) })
        var conflicts = Set(cloud.keys.filter { key in
            let remote = cloud[key]!
            return (values[key] != nil && values[key] != (remote.deletedAt == nil ? remote.payload : nil)) || blocksRunningSyncTask(remote)
        })
        for group in initialSyncGroups(state) where !group.isDisjoint(with: conflicts) { conflicts.formUnion(group) }
        var preview = SyncMergePreview(localOnlyCount: 0, cloudOnlyCount: 0, matchingCount: 0, conflictCount: 0)
        for key in Set(values.keys).union(cloud.keys) {
            let local = values[key], remote = cloud[key]
            let remotePayload = remote?.deletedAt == nil ? remote?.payload : nil
            if conflicts.contains(key) { preview.conflictCount += 1 }
            else if local == nil, remotePayload != nil { preview.cloudOnlyCount += 1 }
            else if local != nil, remote == nil { preview.localOnlyCount += 1 }
            else if local == remotePayload { preview.matchingCount += 1 }
            else { preview.conflictCount += 1 }
        }
        return preview
    }
    public func confirmSyncMerge() throws {
        guard let state = syncState, state.enabled, state.initialPullComplete else { throw SyncStoreError.initialPullRequired }
        if state.mergeConfirmed { return }
        try syncTransaction {
            for group in initialSyncGroups(state) {
                try mergeSyncCommit(state.initialRemote.filter { group.contains($0.key) }, groupID: UUID(), initial: true)
            }
            syncState?.initialRemote = []
            syncState?.initialCommits = []
            syncState?.mergeConfirmed = true
            projectSyncDayPlan(at: lastActiveDay ?? Date())
        }
    }
    public func nextSyncPushRequest() throws -> SyncPushRequest? {
        guard let state = syncState, state.enabled else { throw SyncStoreError.notConnected }
        guard !hasUnsavedChanges else { throw SyncStoreError.unsavedChanges }
        guard state.mergeConfirmed else { throw SyncStoreError.initialPullRequired }
        if let request = state.inflight { return request }
        guard state.conflicts.isEmpty else { throw SyncStoreError.conflictsPending }
        guard !state.pending.isEmpty else { return nil }
        // Each local save records a dependency group; overlapping edits join
        // groups. Pack whole groups, never slice through a user action. During
        // bootstrap, publish tasks before records that reference them.
        func rank(_ key: SyncEntityKey) -> Int {
            switch key.kind { case .task: return 0; case .inbox: return 1; case .goal: return 2
            case .idea: return 3; case .dayPlan: return 4; case .profile: return 5 }
        }
        let groups = state.pendingGroups.sorted {
            let left = $0.map(rank).min() ?? 9, right = $1.map(rank).min() ?? 9
            if left != right { return left < right }
            return ($0.first?.id ?? "") < ($1.first?.id ?? "")
        }
        var mutations: [SyncMutation] = []
        for group in groups {
            let keys = Set(group)
            let candidate = mutations + state.pending.filter { keys.contains($0.key) }
            do { try SyncPushRequest(deviceID: state.deviceID, mutations: candidate).validate() }
            catch SyncProtocolError.batchTooLarge {
                if mutations.isEmpty { throw SyncProtocolError.batchTooLarge }
                break
            }
            mutations = candidate
        }
        guard !mutations.isEmpty else { throw SyncStoreError.invalidResponse }
        let request = SyncPushRequest(deviceID: state.deviceID, mutations: mutations)
        try syncTransaction { syncState?.inflight = request }
        return request
    }
    public func receiveSyncPushResponse(_ response: SyncPushResponse, for request: SyncPushRequest) throws {
        guard let state = syncState, state.enabled, state.inflight == request else { throw SyncStoreError.invalidResponse }
        try response.validate(for: request)
        for entity in response.entities { try SyncPayload.validate(entity) }
        for conflict in response.conflicts { if let entity = conflict.current { try SyncPayload.validate(entity) } }
        try syncTransaction {
            if response.status == .applied {
                for entity in response.entities { setSyncShadow(entity) }
            } else {
                let values = try syncPayloads()
                for conflict in response.conflicts {
                    let key = SyncEntityKey(kind: conflict.kind, id: conflict.id)
                    syncState?.conflicts.removeAll { $0.key == key }
                    syncState?.conflicts.append(SyncLocalConflict(key: key, base: state.shadow.first { $0.key == key },
                        local: values[key], remote: conflict.current))
                }
            }
            // Never jump the pull cursor to a push receipt: intervening commits
            // from another device must still be fetched and validated.
            syncState?.inflight = nil
        }
    }
    public func receiveSyncPullResponse(_ response: SyncPullResponse) throws {
        guard let state = syncState, state.enabled else { throw SyncStoreError.notConnected }
        try response.validate(after: state.cursor)
        for commit in response.commits { for entity in commit.entities { try SyncPayload.validate(entity) } }
        try syncTransaction {
            if !state.mergeConfirmed {
                syncState?.initialCommits.append(contentsOf: response.commits)
                for commit in response.commits { for entity in commit.entities {
                    syncState?.initialRemote.removeAll { $0.key == entity.key }
                    syncState?.initialRemote.append(entity)
                } }
                syncState?.initialPullComplete = !response.hasMore
            } else {
                for commit in response.commits {
                    // Retry a lost push receipt before pulling; otherwise a
                    // later local edit could conflict with our own accepted push.
                    guard state.inflight == nil else { throw SyncStoreError.invalidResponse }
                    try mergeSyncCommit(commit.entities, groupID: commit.operationID, initial: false)
                }
                projectSyncDayPlan(at: lastActiveDay ?? Date())
            }
            syncState?.cursor = response.cursor
        }
    }
    private func isRunningSyncTask(_ key: SyncEntityKey) -> Bool {
        guard key.kind == .task, let id = UUID(uuidString: key.id) else { return false }
        return activeTimer?.taskID == id || parallelRuns.contains { $0.taskID == id }
    }
    private func blocksRunningSyncTask(_ entity: SyncRemoteEntity) -> Bool {
        guard isRunningSyncTask(entity.key) else { return false }
        if entity.deletedAt != nil { return true }
        guard let payload = entity.payload, let (task, archived) = try? SyncPayload.decodeTask(payload) else { return true }
        return archived || task.completed
    }
    private func mergeSyncCommit(_ incoming: [SyncRemoteEntity], groupID: UUID, initial: Bool) throws {
        // An acknowledged newer revision is not part of this old commit's
        // unresolved work. Never expose it as an older conflict choice.
        let entities = incoming.filter { entity in
            (syncState?.shadow.first { $0.key == entity.key }?.revision ?? 0) < entity.revision
        }
        let values = try syncPayloads()
        let mustHold = entities.contains { entity in
            if blocksRunningSyncTask(entity) { return true }
            if syncState?.conflicts.contains(where: { $0.key == entity.key }) == true { return true }
            let base = syncState?.shadow.first { $0.key == entity.key }
            let local = values[entity.key], baseline = base?.deletedAt == nil ? base?.payload : nil
            let remote = entity.deletedAt == nil ? entity.payload : nil
            return (initial ? local != nil : local != baseline) && local != remote
        }
        guard mustHold else {
            for entity in entities { try mergeSyncRemote(entity, initial: initial) }
            return
        }
        let previousGroups = Set((syncState?.conflicts ?? []).filter { conflict in
            entities.contains { $0.key == conflict.key }
        }.compactMap(\.groupID))
        for index in syncState!.conflicts.indices where syncState!.conflicts[index].groupID.map(previousGroups.contains) == true {
            syncState!.conflicts[index].groupID = groupID
            syncState!.conflicts[index].choice = nil
        }
        for entity in entities {
            let previous = syncState?.conflicts.first { $0.key == entity.key }
            let base = syncState?.shadow.first { $0.key == entity.key }
            syncState?.conflicts.removeAll { $0.key == entity.key }
            syncState?.conflicts.append(SyncLocalConflict(key: entity.key, base: previous?.base ?? base,
                local: values[entity.key], remote: (previous?.remote?.revision ?? 0) > entity.revision ? previous?.remote : entity, groupID: groupID))
        }
    }
    private func setSyncShadow(_ entity: SyncRemoteEntity) {
        if let old = syncState?.shadow.first(where: { $0.key == entity.key }), old.revision > entity.revision { return }
        syncState?.shadow.removeAll { $0.key == entity.key }
        syncState?.shadow.append(entity)
    }
    private func mergeSyncRemote(_ entity: SyncRemoteEntity, initial: Bool) throws {
        let base = syncState?.shadow.first { $0.key == entity.key }
        if let base, base.revision >= entity.revision { return }
        let values = try syncPayloads(), local = values[entity.key]
        let remote = entity.deletedAt == nil ? entity.payload : nil
        let baseline = base?.deletedAt == nil ? base?.payload : nil
        let dirty = initial ? local != nil : local != baseline
        if (dirty && local != remote) || blocksRunningSyncTask(entity) {
            let oldConflict = syncState?.conflicts.first { $0.key == entity.key }
            syncState?.conflicts.removeAll { $0.key == entity.key }
            syncState?.conflicts.append(SyncLocalConflict(key: entity.key, base: oldConflict?.base ?? base, local: local, remote: entity))
            return
        }
        if local != remote { try applySyncEntity(entity) }
        setSyncShadow(entity)
    }
    public func resolveSyncConflict(key: SyncEntityKey, choice: SyncConflictChoice) throws {
        guard let state = syncState, state.enabled,
              let conflict = state.conflicts.first(where: { $0.key == key }), state.inflight == nil else { throw SyncStoreError.invalidResponse }
        if choice == .cloud, isRunningSyncTask(key), conflict.remote.map(blocksRunningSyncTask) ?? true {
            throw SyncStoreError.activeTaskRunning
        }
        try syncTransaction {
            let resolutions: [SyncLocalConflict]
            if let group = conflict.groupID {
                let index = syncState!.conflicts.firstIndex { $0.key == key }!
                syncState!.conflicts[index].choice = choice
                resolutions = syncState!.conflicts.filter { $0.groupID == group }
                guard resolutions.allSatisfy({ $0.choice != nil }) else { return }
            } else {
                var selected = conflict; selected.choice = choice; resolutions = [selected]
            }
            for resolution in resolutions {
                if let remote = resolution.remote,
                   let shadow = syncState?.shadow.first(where: { $0.key == resolution.key }), shadow.revision > remote.revision {
                    throw SyncStoreError.invalidResponse
                }
                if resolution.choice == .cloud, isRunningSyncTask(resolution.key), resolution.remote.map(blocksRunningSyncTask) ?? true {
                    throw SyncStoreError.activeTaskRunning
                }
            }
            // Remaining local choices from a remote transaction form one
            // outgoing transaction too, even when mixed with cloud choices.
            syncState?.pendingGroups.append(resolutions.map(\.key))
            for resolution in resolutions {
                if resolution.choice == .cloud {
                    if let remote = resolution.remote { try applySyncEntity(remote) }
                    else { removeSyncEntity(resolution.key) }
                }
                if let remote = resolution.remote { setSyncShadow(remote) }
                else { syncState?.shadow.removeAll { $0.key == resolution.key } }
                syncState?.conflicts.removeAll { $0.key == resolution.key }
            }
            projectSyncDayPlan(at: lastActiveDay ?? Date())
        }
    }
    private func removeSyncEntity(_ key: SyncEntityKey) {
        let id = UUID(uuidString: key.id)
        switch key.kind {
        case .task: tasks.removeAll { $0.id == id }; archive.removeAll { $0.id == id }
        case .inbox: inbox.removeAll { $0.id == id }
        case .idea: thoughts.removeAll { $0.id == id }
        case .goal: goals.removeAll { $0.id == id }
        case .dayPlan: syncDayPlans.removeAll { $0.key == key }
        case .profile: aiBackground = ""; agentPreferences = AgentPreferences(); confirmedPreferences = []
        }
    }
    private func applySyncEntity(_ entity: SyncRemoteEntity) throws {
        guard !blocksRunningSyncTask(entity) else { throw SyncStoreError.activeTaskRunning }
        if entity.deletedAt != nil { removeSyncEntity(entity.key); return }
        guard let payload = entity.payload else { throw SyncProtocolError.invalidPayload }
        switch entity.kind {
        case .task:
            var (task, archived) = try SyncPayload.decodeTask(payload)
            task.lastImplementationTriggeredAt = (tasks + archive).first { $0.id == task.id }?.lastImplementationTriggeredAt
            removeSyncEntity(entity.key)
            if archived { archive.append(task) } else { tasks.append(task) }
        case .inbox:
            let value = try SyncPayload.content(InboxItem.self, payload); removeSyncEntity(entity.key); inbox.append(value)
        case .idea:
            let value = try SyncPayload.content(ThoughtItem.self, payload); removeSyncEntity(entity.key); thoughts.append(value)
        case .goal:
            let value = try SyncPayload.content(LongTermGoal.self, payload); removeSyncEntity(entity.key); goals.append(value)
        case .dayPlan:
            let value = try SyncPayload.content(SyncDayPlan.self, payload); removeSyncEntity(entity.key); syncDayPlans.append(value)
        case .profile:
            let value = try SyncPayload.content(SyncProfile.self, payload)
            aiBackground = value.background; agentPreferences = value.preferences
            confirmedPreferences = value.confirmedPreferences
            if planningTimeZone != value.planningTimeZone {
                planningTimeZone = value.planningTimeZone
                lastActiveDay = syncCalendar.startOfDay(for: Date())
            }
        }
    }
}
