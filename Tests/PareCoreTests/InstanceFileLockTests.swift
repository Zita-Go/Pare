import XCTest
@testable import PareCore

final class InstanceFileLockTests: XCTestCase {
    private var directory: URL!
    private var dataURL: URL { directory.appendingPathComponent("store.json") }

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("pare-instance-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    func testSeparateDescriptorsContendAndReleaseWithoutRemovingLockFile() throws {
        var first: InstanceFileLock? = try InstanceFileLock(dataFileURL: dataURL)
        let lockURL = try XCTUnwrap(first).lockFileURL
        XCTAssertThrowsError(try InstanceFileLock(dataFileURL: dataURL)) { error in
            guard case InstanceFileLock.LockError.alreadyRunning(let pid) = error else {
                return XCTFail("Unexpected lock error: \(error)")
            }
            XCTAssertEqual(pid, ProcessInfo.processInfo.processIdentifier)
        }
        first = nil
        XCTAssertTrue(FileManager.default.fileExists(atPath: lockURL.path))
        let next = try InstanceFileLock(dataFileURL: dataURL)
        try withExtendedLifetime(next) {
            XCTAssertThrowsError(try InstanceFileLock(dataFileURL: dataURL))
        }
    }

    func testSymbolicDirectoryAliasesUseSameLock() throws {
        let real = directory.appendingPathComponent("real", isDirectory: true)
        let alias = directory.appendingPathComponent("alias", isDirectory: true)
        try FileManager.default.createDirectory(at: real, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: real)
        let first = try InstanceFileLock(dataFileURL: real.appendingPathComponent("store.json"))
        try withExtendedLifetime(first) {
            XCTAssertThrowsError(try InstanceFileLock(dataFileURL: alias.appendingPathComponent("store.json")))
        }
    }

    func testCreatesDataDirectoryButDoesNotCreateStore() throws {
        let nested = directory.appendingPathComponent("new/sub/store.json")
        let lock = try InstanceFileLock(dataFileURL: nested)
        withExtendedLifetime(lock) {
            XCTAssertTrue(FileManager.default.fileExists(atPath: nested.deletingLastPathComponent().path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: nested.path))
        }
    }

    func testInvalidDirectoryAndSymbolicLockFailInsteadOfAllowingWrites() throws {
        let obstruction = directory.appendingPathComponent("file")
        try Data("preserve".utf8).write(to: obstruction)
        XCTAssertThrowsError(try InstanceFileLock(dataFileURL: obstruction.appendingPathComponent("store.json")))
        try FileManager.default.createSymbolicLink(at: dataURL.appendingPathExtension("lock"), withDestinationURL: obstruction)
        XCTAssertThrowsError(try InstanceFileLock(dataFileURL: dataURL))
        XCTAssertEqual(try Data(contentsOf: obstruction), Data("preserve".utf8))
    }

    func testStaleProcessIDDoesNotPreventAcquisition() throws {
        try Data("2147483647\n".utf8).write(to: dataURL.appendingPathExtension("lock"))
        let lock = try InstanceFileLock(dataFileURL: dataURL)
        withExtendedLifetime(lock) {
            XCTAssertEqual(try? String(contentsOf: lock.lockFileURL, encoding: .utf8), "\(ProcessInfo.processInfo.processIdentifier)\n")
        }
    }

    func testAnotherProcessCannotAcquireHeldLock() throws {
        let lock = try InstanceFileLock(dataFileURL: dataURL)
        try withExtendedLifetime(lock) {
            let child = try pythonProcess(script: """
                import fcntl, os, sys
                fd = os.open(sys.argv[1], os.O_RDWR | os.O_CREAT, 0o600)
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    sys.exit(2)
                except BlockingIOError:
                    sys.exit(0)
                """, path: lock.lockFileURL.path)
            child.waitUntilExit()
            XCTAssertEqual(child.terminationStatus, 0)
        }
    }

    func testAbruptProcessExitReleasesLock() throws {
        let ready = Pipe()
        let child = try pythonProcess(script: """
            import fcntl, os, sys, time
            fd = os.open(sys.argv[1], os.O_RDWR | os.O_CREAT, 0o600)
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            os.write(fd, (str(os.getpid()) + '\\n').encode())
            print('ready', flush=True)
            time.sleep(30)
            """, path: dataURL.appendingPathExtension("lock").path, output: ready)
        defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
        XCTAssertFalse(ready.fileHandleForReading.availableData.isEmpty)
        XCTAssertThrowsError(try InstanceFileLock(dataFileURL: dataURL))
        child.terminate()
        child.waitUntilExit()
        let recovered = try InstanceFileLock(dataFileURL: dataURL)
        withExtendedLifetime(recovered) {
            XCTAssertTrue(FileManager.default.fileExists(atPath: recovered.lockFileURL.path))
        }
    }

    private func pythonProcess(script: String, path: String, output: Pipe? = nil) throws -> Process {
        let python = "/usr/bin/python3"
        guard FileManager.default.isExecutableFile(atPath: python) else {
            throw XCTSkip("Cross-process lock test requires the macOS developer Python runtime.")
        }
        let child = Process()
        child.executableURL = URL(fileURLWithPath: python)
        child.arguments = ["-c", script, path]
        child.standardOutput = output
        try child.run()
        return child
    }
}
