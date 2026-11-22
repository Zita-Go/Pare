import AppKit
import CryptoKit
import PareCore

/// Coordinates all copies of Pare that point at the same data location.
final class SingleInstanceCoordinator {
    private let lock: InstanceFileLock
    private var observer: NSObjectProtocol?
    private var pendingReopen = false
    var dataFileURL: URL { lock.dataFileURL }

    var onReopenRequested: (() -> Void)? {
        didSet {
            if pendingReopen, let onReopenRequested {
                pendingReopen = false
                onReopenRequested()
            }
        }
    }

    init(dataFileURL: URL) throws {
        lock = try InstanceFileLock(dataFileURL: dataFileURL)
        observer = DistributedNotificationCenter.default().addObserver(
            forName: Self.reopenNotification(for: lock.dataFileURL), object: nil, queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            if let onReopenRequested = self.onReopenRequested {
                onReopenRequested()
            } else {
                self.pendingReopen = true
            }
        }
    }

    deinit {
        if let observer { DistributedNotificationCenter.default().removeObserver(observer) }
    }

    /// The notification opens 整理 even when the existing copy is menu-bar-only.
    /// Process activation also covers a copy still finishing its initial launch.
    static func activateExistingInstance(dataFileURL: URL, processID: Int32?) -> Bool {
        DistributedNotificationCenter.default().postNotificationName(
            reopenNotification(for: dataFileURL), object: nil, userInfo: nil, deliverImmediately: true)
        let bundleID = Bundle.main.bundleIdentifier
        let byPID = processID.flatMap { NSRunningApplication(processIdentifier: $0) }
        let existing = byPID.flatMap { app -> NSRunningApplication? in
            guard bundleID == nil || app.bundleIdentifier == bundleID else { return nil }
            return app
        } ?? bundleID.flatMap { id in
            NSRunningApplication.runningApplications(withBundleIdentifier: id)
                .first { $0.processIdentifier != ProcessInfo.processInfo.processIdentifier }
        }
        guard let existing else { return false }
        // LaunchServices sends the standard reopen event, including when our
        // notification arrived just before the first copy installed its observer.
        let reopened: Bool
        if let bundleURL = existing.bundleURL, bundleURL.pathExtension == "app" {
            reopened = NSWorkspace.shared.open(bundleURL)
        } else {
            reopened = false
        }
        let activated = existing.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
        return reopened || activated
    }

    private static func reopenNotification(for dataFileURL: URL) -> Notification.Name {
        let path = dataFileURL.standardizedFileURL.resolvingSymlinksInPath().path
        let digest = SHA256.hash(data: Data(path.utf8)).map { String(format: "%02x", $0) }.joined()
        return Notification.Name("com.zitago.Pare.reopen.\(digest)")
    }
}
