import XCTest

/// Pure Swift Package projects cannot host an XCUITest application bundle.
/// These smoke contracts keep the critical entry points, modes and accessible
/// actions from disappearing during refactors; the companion checklist covers
/// the final native-window pass.
final class UIContractTests: XCTestCase {
    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private func source(_ path: String) throws -> String {
        try String(contentsOf: repositoryRoot.appendingPathComponent(path), encoding: .utf8)
    }

    func testPrimaryWorkspaceAndTaskActionsRemainDiscoverable() throws {
        let organize = try source("Sources/Pare/OrganizeView.swift")
        let planning = try source("Sources/Pare/PlanningViews.swift")
        XCTAssertTrue(organize.contains("四象限"))
        XCTAssertTrue(organize.contains("今日计划"))
        XCTAssertTrue(organize.contains("想法与历史"))
        XCTAssertTrue(planning.contains("编辑任务详情"))
        XCTAssertTrue(planning.contains("放弃本次专注计时"))
        XCTAssertTrue(planning.contains("已逾期"))
        XCTAssertTrue(planning.contains("安排下次回看"))
        let settings = try source("Sources/Pare/SettingsWindowController.swift")
        XCTAssertTrue(settings.contains("检测应用来回切换"))
        XCTAssertTrue(settings.contains("浏览器页面增强检测"))
        XCTAssertTrue(settings.contains("请求辅助功能权限"))
    }

    func testOnboardingAndDataSafetyControlsRemainVisible() throws {
        let onboarding = try source("Sources/Pare/OnboardingWindowController.swift")
        let settings = try source("Sources/Pare/SettingsWindowController.swift")
        XCTAssertTrue(onboarding.contains("开启提醒"))
        XCTAssertTrue(onboarding.contains("⌥Space"))
        XCTAssertTrue(settings.contains("登录时自动启动 Pare"))
        XCTAssertTrue(settings.contains("导出备份"))
        XCTAssertTrue(settings.contains("恢复自动备份"))
    }
}
