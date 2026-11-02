import AppKit
import PareCore

let app = NSApplication.shared
app.setActivationPolicy(.accessory) // menu-bar-only; no Dock icon (LSUIElement)
let dataFileURL = Store.defaultFileURL()

do {
    // Acquire ownership before constructing anything that loads or writes data.
    let coordinator = try SingleInstanceCoordinator(dataFileURL: dataFileURL)
    let delegate = AppDelegate(fileURL: coordinator.dataFileURL)
    coordinator.onReopenRequested = { [weak delegate] in delegate?.requestReopen() }
    app.delegate = delegate
    withExtendedLifetime((coordinator, delegate)) { app.run() }
} catch InstanceFileLock.LockError.alreadyRunning(let processID) {
    if !SingleInstanceCoordinator.activateExistingInstance(dataFileURL: dataFileURL, processID: processID) {
        let alert = NSAlert()
        alert.messageText = "Step 已在运行"
        alert.informativeText = "已请求打开现有 Step（或旧版 Pare）。若窗口没有出现，请点击菜单栏图标打开整理。当前副本将退出，以保护正在使用的数据。"
        alert.addButton(withTitle: "好")
        app.activate(ignoringOtherApps: true)
        alert.runModal()
    }
} catch {
    let alert = NSAlert()
    alert.alertStyle = .critical
    alert.messageText = "无法安全启动 Step"
    alert.informativeText = error.localizedDescription
    alert.addButton(withTitle: "退出")
    app.activate(ignoringOtherApps: true)
    alert.runModal()
    exit(EXIT_FAILURE)
}
