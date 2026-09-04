import AppKit
import PareCore

/// Emits live “if–then” cues that cannot be represented by calendar
/// notifications: returning to the computer and activating a chosen app.
final class MomentumManager {
    private let store: Store
    private let onCue: (TaskItem, ImplementationPlan) -> Void
    private var observers: [NSObjectProtocol] = []

    init(store: Store, onCue: @escaping (TaskItem, ImplementationPlan) -> Void) {
        self.store = store; self.onCue = onCue
    }

    func start() {
        let center = NSWorkspace.shared.notificationCenter
        observers.append(center.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil, queue: .main) { [weak self] note in
                let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
                self?.handle(kind: .openApplication, bundleID: app?.bundleIdentifier)
            })
        for name in [NSWorkspace.screensDidWakeNotification,
                     NSWorkspace.sessionDidBecomeActiveNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.handle(kind: .returnToComputer, bundleID: nil)
            })
        }
    }

    func stop() {
        let center = NSWorkspace.shared.notificationCenter
        observers.forEach(center.removeObserver)
        observers.removeAll()
    }

    private func handle(kind: ImplementationCueKind, bundleID: String?, now: Date = Date()) {
        let candidates = store.tasks.filter { task in
            guard !task.completed, let plan = task.implementationPlan, plan.kind == kind else { return false }
            if kind == .openApplication, plan.applicationBundleID != bundleID { return false }
            if let last = task.lastImplementationTriggeredAt,
               now.timeIntervalSince(last) < 4 * 60 * 60 { return false }
            return true
        }.sorted { lhs, rhs in
            let left = (lhs.isDailyWin == true ? 0 : 1, lhs.isToday ? 0 : 1, lhs.createdAt)
            let right = (rhs.isDailyWin == true ? 0 : 1, rhs.isToday ? 0 : 1, rhs.createdAt)
            return left < right
        }
        guard let task = candidates.first, let plan = task.implementationPlan else { return }
        store.markImplementationTriggered(task.id, now: now)
        onCue(task, plan)
    }
}
