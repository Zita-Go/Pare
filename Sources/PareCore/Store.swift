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
    @Published public private(set) var activeTimer: ActiveTimer?
    /// A short-lived, UI-friendly description of the last destructive action.
    @Published public private(set) var undoMessage: String?
    /// Non-fatal persistence diagnostics surfaced in Settings instead of
    /// silently making a damaged store look empty.
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
    private let synchronousPersistence: Bool
    private let persistenceQueue = DispatchQueue(label: "com.zitago.Pare.persistence", qos: .utility)
    private var loading = false
    private var persistenceLocked = false
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

    public init(fileURL: URL, calendar: Calendar = .current,
                synchronousPersistence: Bool = true) {
        self.fileURL = fileURL
        self.calendar = calendar
        self.synchronousPersistence = synchronousPersistence
        load()
    }

    /// `~/Library/Application Support/Pare/store.json`, creating the folder.
    public static func defaultFileURL() -> URL {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("Pare", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
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
        var lastActiveDay: Date?
        var settings: Settings?
    }

    public enum DataTransferError: LocalizedError {
        case invalidBackup
        case unsupportedVersion(Int)
        case cannotWrite(String)

        public var errorDescription: String? {
            switch self {
            case .invalidBackup: return "这个文件不是有效的 Pare 备份，数据没有改变。"
            case .unsupportedVersion(let version): return "这个备份来自更新的数据格式（v\(version)），请先升级 Pare。"
            case .cannotWrite(let detail): return "无法写入备份：\(detail)"
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
            persistenceLocked = false
            persistenceIssue = nil
        } catch {
            do {
                let data = try Data(contentsOf: backupURL)
                snap = try decodeSnapshot(data, using: dec)
                lastSavedData = data
                persistenceLocked = false
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
        activeTimer = snap.activeTimer
        lastActiveDay = snap.lastActiveDay
        settings = snap.settings ?? .default
        if let timer = activeTimer,
           !tasks.contains(where: { $0.id == timer.taskID && !$0.completed }) {
            activeTimer = nil
        }
    }

    private func decodeSnapshot(_ data: Data, using decoder: JSONDecoder) throws -> Snapshot {
        let snapshot = try decoder.decode(Snapshot.self, from: data)
        let version = snapshot.schemaVersion ?? 1
        guard version <= 2 else { throw DataTransferError.unsupportedVersion(version) }
        return snapshot
    }

    private func currentSnapshot() -> Snapshot {
        Snapshot(schemaVersion: 2, inbox: inbox, tasks: tasks,
                 archive: archive, thoughts: thoughts, activeTimer: activeTimer,
                 lastActiveDay: lastActiveDay, settings: settings)
    }

    private func encoder() -> JSONEncoder {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        return enc
    }

    private func save(createBackup: Bool = true) {
        guard !loading, !persistenceLocked else { return }
        let snapshot = currentSnapshot()
        if synchronousPersistence {
            persist(snapshot, createBackup: createBackup)
        } else {
            persistenceQueue.async { [self] in
                persist(snapshot, createBackup: createBackup)
            }
        }
    }

    private func persist(_ snapshot: Snapshot, createBackup: Bool) {
        do {
            let data = try encoder().encode(snapshot)
            if createBackup, let previous = lastSavedData {
                try previous.write(to: backupURL, options: .atomic)
            }
            try data.write(to: fileURL, options: .atomic)
            lastSavedData = data
            if synchronousPersistence {
                if persistenceIssue?.contains("无法保存") == true { persistenceIssue = nil }
            } else {
                DispatchQueue.main.async { [weak self] in
                    if self?.persistenceIssue?.contains("无法保存") == true {
                        self?.persistenceIssue = nil
                    }
                }
            }
        } catch {
            let message = "无法保存数据：\(error.localizedDescription)"
            if synchronousPersistence {
                persistenceIssue = message
            } else {
                DispatchQueue.main.async { [weak self] in self?.persistenceIssue = message }
            }
        }
    }

    /// Wait for queued production writes at lifecycle boundaries and before a
    /// destructive import. Unit tests use synchronous persistence by default.
    public func flushPersistence() {
        guard !synchronousPersistence else { return }
        persistenceQueue.sync {}
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
        do {
            try encoder().encode(currentSnapshot()).write(to: destination, options: .atomic)
        } catch {
            throw DataTransferError.cannotWrite(error.localizedDescription)
        }
    }

    public func importData(from source: URL) throws {
        flushPersistence()
        let data = try Data(contentsOf: source)
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        let snapshot: Snapshot
        do { snapshot = try decodeSnapshot(data, using: dec) }
        catch let error as DataTransferError { throw error }
        catch { throw DataTransferError.invalidBackup }
        try? backupCurrentFileIfHealthy()
        loading = true
        apply(snapshot)
        loading = false
        persistenceLocked = false
        persistenceIssue = nil
        save(createBackup: false)
        flushPersistence()
    }

    @discardableResult
    public func restoreAutomaticBackup() -> Bool {
        guard FileManager.default.fileExists(atPath: backupURL.path) else { return false }
        do {
            try importData(from: backupURL)
            return true
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
        }
        save()
        return true
    }

    // MARK: - Capture (随手记)

    @discardableResult
    public func capture(_ text: String) -> Bool {
        let parsed = CaptureParser.parse(text, calendar: calendar)
        let t = parsed.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return false }
        inbox.insert(InboxItem(text: t, suggestedRemindAt: parsed.remindAt), at: 0)
        save()
        return true
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
        tasks[i].estimatedMinutes = (clamped == 0) ? nil : clamped
        save()
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
        tasks[i].lastProgressAt = now
        save()
    }

    public func setToday(_ id: UUID, _ value: Bool, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].isToday = value
        tasks[i].todayOrder = value ? (tasks[i].todayOrder ?? nextTodayOrder()) : nil
        tasks[i].lastProgressAt = now
        save()
    }

    public func toggleToday(_ id: UUID, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].isToday.toggle()
        tasks[i].todayOrder = tasks[i].isToday ? nextTodayOrder() : nil
        tasks[i].lastProgressAt = now
        save()
    }

    public func setCompleted(_ id: UUID, _ value: Bool, now: Date = Date()) {
        guard tasks.contains(where: { $0.id == id }) else { return }
        if value, activeTimer?.taskID == id { _ = stopTimer(now: now) }
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

    public func deleteTask(_ id: UUID) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        if activeTimer?.taskID == id { activeTimer = nil }
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

    @discardableResult
    public func startTimer(_ taskID: UUID, now: Date = Date()) -> Bool {
        guard tasks.contains(where: { $0.id == taskID && !$0.completed }) else { return false }
        if activeTimer?.taskID == taskID { return true }
        if activeTimer != nil { _ = stopTimer(now: now) }
        activeTimer = ActiveTimer(taskID: taskID, startedAt: now)
        save()
        return true
    }

    /// Stop and accumulate the elapsed session into actual time. Even a very
    /// short intentional session records one minute instead of disappearing.
    @discardableResult
    public func stopTimer(now: Date = Date()) -> Int? {
        guard let timer = activeTimer,
              let index = tasks.firstIndex(where: { $0.id == timer.taskID }) else {
            activeTimer = nil
            save()
            return nil
        }
        let seconds = max(0, now.timeIntervalSince(timer.startedAt))
        let minutes = max(1, Int(ceil(seconds / 60)))
        activeTimer = nil
        tasks[index].actualMinutes = (tasks[index].actualMinutes ?? 0) + minutes
        tasks[index].lastProgressAt = now
        save()
        return minutes
    }

    public func cancelTimer() {
        guard activeTimer != nil else { return }
        activeTimer = nil
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

    // MARK: - Settings

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
                let l = ($0.completed ? 1 : 0, $0.todayOrder ?? Int.max, $0.createdAt)
                let r = ($1.completed ? 1 : 0, $1.todayOrder ?? Int.max, $1.createdAt)
                return l < r
            }
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
