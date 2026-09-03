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
    /// Reminder preferences. Persisted alongside the data.
    @Published public private(set) var settings: Settings = .default

    /// The day the store was last known to be active (start-of-day). Used to
    /// drive the once-per-day rollover ritual.
    public private(set) var lastActiveDay: Date?

    private let fileURL: URL
    /// Internal (not private) so the reminder planner extension can reuse it.
    let calendar: Calendar
    private var loading = false

    // MARK: - Init

    public init(fileURL: URL, calendar: Calendar = .current) {
        self.fileURL = fileURL
        self.calendar = calendar
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
        var inbox: [InboxItem]
        var tasks: [TaskItem]
        var archive: [TaskItem]?
        var lastActiveDay: Date?
        var settings: Settings?
    }

    public func load() {
        loading = true
        defer { loading = false }
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        guard let snap = try? dec.decode(Snapshot.self, from: data) else { return }
        inbox = snap.inbox
        tasks = snap.tasks
        archive = snap.archive ?? []
        lastActiveDay = snap.lastActiveDay
        settings = snap.settings ?? .default
    }

    private func save() {
        guard !loading else { return }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        let snap = Snapshot(inbox: inbox, tasks: tasks,
                            archive: archive, lastActiveDay: lastActiveDay,
                            settings: settings)
        guard let data = try? enc.encode(snap) else { return }
        try? data.write(to: fileURL, options: .atomic)
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
        for i in tasks.indices { tasks[i].isToday = false }
        save()
        return true
    }

    // MARK: - Capture (随手记)

    @discardableResult
    public func capture(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return false }
        inbox.insert(InboxItem(text: t), at: 0)
        save()
        return true
    }

    public func deleteInbox(_ id: UUID) {
        inbox.removeAll { $0.id == id }
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
                            lastProgressAt: now)
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
                            isToday: addToToday, estimatedMinutes: estimatedMinutes)
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

    public func setToday(_ id: UUID, _ value: Bool, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].isToday = value
        tasks[i].lastProgressAt = now
        save()
    }

    public func toggleToday(_ id: UUID, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].isToday.toggle()
        tasks[i].lastProgressAt = now
        save()
    }

    public func setCompleted(_ id: UUID, _ value: Bool, now: Date = Date()) {
        guard let i = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[i].completed = value
        tasks[i].completedAt = value ? now : nil
        tasks[i].lastProgressAt = now
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
        tasks.removeAll { $0.id == id }
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
        let before = tasks.count
        tasks.removeAll { $0.quadrant == quadrant && (includingCompleted || !$0.completed) }
        let removed = before - tasks.count
        if removed > 0 { save() }
        return removed
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
            .sorted { ($0.completed ? 1 : 0, $0.createdAt) < ($1.completed ? 1 : 0, $1.createdAt) }
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

    /// Everything finished today — the "成就感" number, whether or not the task
    /// was ever flagged for today.
    public func completedTodayCount(now: Date = Date()) -> Int {
        tasks.reduce(0) { acc, t in
            guard t.completed, let at = t.completedAt else { return acc }
            return acc + (calendar.isDate(at, inSameDayAs: now) ? 1 : 0)
        }
    }

    public var inboxCount: Int { inbox.count }
}
