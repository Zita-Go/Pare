import Foundation
import ServiceManagement

/// Thin observable wrapper around the macOS 13 login-item API. Keeping Pare
/// running is part of reminder reliability, so this belongs in Settings rather
/// than a README-only setup step.
final class LoginItemManager: ObservableObject {
    @Published private(set) var enabled = false
    @Published private(set) var statusText = "检查中…"

    init() { refresh() }

    func refresh() {
        let status = SMAppService.mainApp.status
        enabled = status == .enabled
        switch status {
        case .enabled: statusText = "已开启"
        case .requiresApproval: statusText = "等待在系统设置中批准"
        case .notRegistered: statusText = "未开启"
        case .notFound: statusText = "当前应用位置不支持"
        @unknown default: statusText = "状态未知"
        }
    }

    func setEnabled(_ value: Bool) {
        do {
            if value {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            refresh()
        } catch {
            refresh()
            statusText = error.localizedDescription
        }
    }
}
