import Foundation
import Darwin

/// An advisory lock tied to one data file, held for this object's lifetime.
public final class InstanceFileLock {
    public enum LockError: LocalizedError {
        case alreadyRunning(processID: Int32?)
        case unavailable(path: String, detail: String)

        public var errorDescription: String? {
            switch self {
            case .alreadyRunning:
                return "另一个 Step（或旧版 Pare）正在使用这份数据。"
            case .unavailable(let path, let detail):
                return "无法保护数据文件，Step 未启动。\n\(path)\n\(detail)"
            }
        }
    }

    public let dataFileURL: URL
    public let lockFileURL: URL
    private let descriptor: Int32

    public init(dataFileURL: URL) throws {
        guard dataFileURL.isFileURL else {
            throw LockError.unavailable(path: dataFileURL.absoluteString, detail: "数据位置必须是本地文件。")
        }
        self.dataFileURL = dataFileURL.standardizedFileURL.resolvingSymlinksInPath()
        self.lockFileURL = self.dataFileURL.appendingPathExtension("lock")
        do {
            try FileManager.default.createDirectory(at: self.dataFileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
        } catch {
            throw LockError.unavailable(path: lockFileURL.path, detail: error.localizedDescription)
        }

        // Never truncate or unlink before locking: replacing the inode would
        // allow two copies to hold locks on different files at the same path.
        let fd = Darwin.open(lockFileURL.path, O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK,
                             S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw Self.failure(path: lockFileURL.path) }
        var acquired = false
        defer { if !acquired { Darwin.close(fd) } }
        var attributes = stat()
        guard fstat(fd, &attributes) == 0 else { throw Self.failure(path: lockFileURL.path) }
        guard attributes.st_mode & S_IFMT == S_IFREG else {
            throw LockError.unavailable(path: lockFileURL.path, detail: "锁文件不是普通文件。")
        }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            if errno == EWOULDBLOCK || errno == EAGAIN {
                throw LockError.alreadyRunning(processID: Self.readProcessID(fd))
            }
            throw Self.failure(path: lockFileURL.path)
        }
        // The PID is only an activation hint. flock alone decides ownership;
        // stale metadata after a crash never blocks the next launch.
        let pid = Array("\(getpid())\n".utf8)
        guard ftruncate(fd, 0) == 0 else { throw Self.failure(path: lockFileURL.path) }
        let written = pid.withUnsafeBytes { pwrite(fd, $0.baseAddress, $0.count, 0) }
        guard written == pid.count else { throw Self.failure(path: lockFileURL.path) }
        descriptor = fd
        acquired = true
    }

    deinit {
        // The kernel also closes this descriptor after abnormal termination.
        // Leave the inode in place so no competing process can bypass it.
        Darwin.close(descriptor)
    }

    private static func readProcessID(_ fd: Int32) -> Int32? {
        var bytes = [UInt8](repeating: 0, count: 64)
        let count = pread(fd, &bytes, bytes.count, 0)
        guard count > 0,
              let value = Int32(String(decoding: bytes.prefix(count), as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)), value > 0 else { return nil }
        return value
    }

    private static func failure(path: String) -> LockError {
        let code = errno
        return .unavailable(path: path, detail: String(cString: strerror(code)))
    }
}
