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
    @Published public private(set) var activeTimer: ActiveTimer?
    @Published public private(set) var parallelRuns: [ParallelTaskRun] = []
    /// A short-lived, UI-friendly description of the last destructive action.
    @Published public private(set) var undoMessage: String?
    /// Persistence diagnostics surfaced wherever the user is working.
    @Published public private(set) var persistenceIssue: String?
    /// Reminder preferences. Persisted alongside the data.
    @Published public private(set) var settings: Settings = .default

    /// The day the store was last known to be active (start-of-day). Used to
    /// drive the once-per-day rollover ritual.
    public private(set) var lastActiveDay: Date?

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

    // MARK: - Init

    public init(fileURL: URL, calendar: Calendar = .current) {
        self.fileURL = fileURL
        self.calendar = calendar
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
    }

    public enum DataTransferError: LocalizedError {
        case invalidBackup
        case unsupportedVersion(Int)
        case cannotWrite(String)

        public var errorDescription: String? {
            switch self {
            case .invalidBackup: return "这个文件不是有效的 Pare 备份，数据没有改变。"
            case .unsupportedVersion(let version): return "这个备份来自更新的数据格式（v\(version)），请先升级 Pare。"
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
            persistenceIssue = "数据来自更新的 Pare（格式 v\(version)）。请升级 Pare；原文件和自动备份已保留，当前禁止写入。"
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
                persistenceIssue = "数据文件和自动备份都无法读取；Pare 暂未覆盖原文件。"
                return
            }
        }
        apply(snap)
    }

    private func apply(_ snap: Snapshot) {
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
        guard version <= 4 else { throw DataTransferError.unsupportedVersion(version) }
        return try decoder.decode(Snapshot.self, from: data)
    }

    private func currentSnapshot() -> Snapshot {
        Snapshot(schemaVersion: 4, inbox: inbox, tasks: tasks,
                 archive: archive, thoughts: thoughts, activeTimer: activeTimer,
                 parallelRuns: parallelRuns,
                 lastActiveDay: lastActiveDay, settings: settings,
                 aiBackground: aiBackground, aiSummaries: aiSummaries,
                 goals: goals, agentPreferences: agentPreferences, planningRuns: planningRuns)
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
        do {
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
        let today = calendar.startOfDay(for: now)
        if let last = lastActiveDay, calendar.isDate(last, inSameDayAs: today) {
            return false
        }
        let firstRun = (lastActiveDay == nil)
        lastActiveDay = today
        if firstRun {
            save()
            return false
        }
        let done = tasks.filter { $0.completed }
        archive.append(contentsOf: done)
        tasks.removeAll { $0.completed }
        for i in tasks.indices {
            tasks[i].isToday = false
            tasks[i].todayOrder = nil
            tasks[i].isDailyWin = false
            tasks[i].scheduledAt = nil
            tasks[i].scheduledMinutes = nil
            tasks[i].scheduleReminderLeadMinutes = nil
        }
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
        let item = inbox.remove(at: index)
        let thought = ThoughtItem(text: item.text, createdAt: item.createdAt, updatedAt: now)
        thoughts.insert(thought, at: 0)
        save()
        return thought
    }

    public func addThought(_ text: String, notes: String? = nil, now: Date = Date()) -> ThoughtItem? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        let thought = ThoughtItem(text: t, notes: Self.nonBlank(notes), createdAt: now, updatedAt: now)
        thoughts.insert(thought, at: 0)
        save()
        return thought
    }

    public func updateThought(_ id: UUID, text: String, notes: String?, reviewAt: Date? = nil,
                              now: Date = Date()) {
        guard let index = thoughts.firstIndex(where: { $0.id == id }) else { return }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        thoughts[index].text = t
        thoughts[index].notes = Self.nonBlank(notes)
        thoughts[index].updatedAt = now
        thoughts[index].reviewAt = reviewAt
        save()
    }

    public func deleteThought(_ id: UUID) {
        guard let index = thoughts.firstIndex(where: { $0.id == id }) else { return }
        let thought = thoughts.remove(at: index)
        stageUndo(.thought(thought, index), message: "已删除一个想法")
        save()
    }

    /// Promote a clarified idea into an actionable quadrant task while keeping
    /// the notes that led to it.
    @discardableResult
    public func promoteThought(_ id: UUID, to quadrant: Quadrant = .importantNotUrgent,
                               addToToday: Bool = false, now: Date = Date()) -> TaskItem? {
        guard let index = thoughts.firstIndex(where: { $0.id == id }) else { return nil }
        let thought = thoughts.remove(at: index)
        let task = TaskItem(text: thought.text, quadrant: quadrant, createdAt: now,
                            isToday: addToToday, lastProgressAt: now, note: thought.notes,
                            todayOrder: addToToday ? nextTodayOrder() : nil)
        tasks.append(task)
        save()
        return task
    }

    public func thoughtsDueForReview(now: Date = Date()) -> [ThoughtItem] {
        thoughts.filter { ($0.reviewAt ?? .distantFuture) <= now }
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
        guard let taskIndex = tasks.firstIndex(where: { $0.id == taskID && !$0.completed }),
              !parallelRuns.contains(where: { $0.taskID == taskID }) else { return false }
        if activeTimer?.taskID == taskID { return true }
        if activeTimer != nil { _ = stopTimer(now: now) }
        // Beginning the scheduled work fulfils its start reminder. Keep the
        // block visible, but do not alert again while the user is already in it.
        if tasks[taskIndex].scheduledAt != nil {
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
        aiSummaries[index].feedback = feedback; save()
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
        for item in chosen {
            if request.workflow == .organize {
                guard let index = inbox.firstIndex(where: { $0.id == item.id }) else { continue }
                let source = inbox.remove(at: index)
                let note = "原始随手记：\(source.text)"
                if item.kind == "thought" {
                    thoughts.insert(ThoughtItem(text: item.title, notes: note,
                                                createdAt: source.createdAt, updatedAt: now), at: 0)
                } else {
                    tasks.append(TaskItem(text: item.title, quadrant: Quadrant(rawValue: item.quadrant)!,
                                          createdAt: now, estimatedMinutes: item.estimatedMinutes,
                                          remindAt: source.suggestedRemindAt, lastProgressAt: now,
                                          note: note, nextAction: Self.nonBlank(item.nextAction),
                                          definitionOfDone: Self.nonBlank(item.definitionOfDone)))
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
        save()
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
            .filter { calendar.isDate($0.endedAt, inSameDayAs: now) }
            .sorted { $0.endedAt < $1.endedAt }
    }

    public func progressedTaskCountToday(now: Date = Date()) -> Int {
        Set((tasks + archive).filter { task in
            (task.progressSessions ?? []).contains { calendar.isDate($0.endedAt, inSameDayAs: now) }
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
            return acc + (calendar.isDate(at, inSameDayAs: now) ? 1 : 0)
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
