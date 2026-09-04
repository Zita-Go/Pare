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
        XCTAssertTrue(organize.contains("CollapsedInboxRail"))
        XCTAssertTrue(organize.contains("quadrantRowHeights"))
        XCTAssertFalse(organize.contains("inboxManuallyExpanded"))
        XCTAssertFalse(organize.contains("rotationEffect(.degrees(-90))"))
        XCTAssertFalse(organize.contains("Text(quadrant.hint)"),
                       "行动提示应只保留在 help/VoiceOver，不再常驻象限标题")
        XCTAssertTrue(planning.contains("编辑任务详情"))
        XCTAssertTrue(planning.contains("放弃本次专注计时"))
        XCTAssertFalse(planning.contains("showsLabel || isActive"),
                       "紧凑计时按钮不应因运行中而强行展开文字")
        XCTAssertTrue(planning.contains("openTaskDetails(task.id)"),
                      "随手记应能把任务详情切换到当前面板内")
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
        let popover = try source("Sources/Pare/PopoverController.swift")
        XCTAssertTrue(popover.contains("isCaptureInteractionWindow"))
        XCTAssertTrue(popover.contains("event.window === self?.panel"),
                      "Esc 只能由随手记父面板吞掉，详情弹窗必须先处理")
        let capture = try source("Sources/Pare/CaptureView.swift")
        XCTAssertFalse(capture.contains(".onHover"),
                       "随手记悬停不能再触发列表结构重绘")
        XCTAssertTrue(capture.contains("CaptureBackground"))
        XCTAssertFalse(capture.contains("FrostBackground"),
                       "高频随手记窗口不应使用实时毛玻璃合成")
        XCTAssertTrue(capture.contains("inlineTaskDetail"))
        XCTAssertTrue(capture.contains("返回今日任务"),
                      "内嵌详情必须始终提供立即可见的关闭路径")
        XCTAssertFalse(popover.contains(".blur(radius:"),
                       "随手记入场不应触发整窗模糊重绘")
        let store = try source("Sources/PareCore/Store.swift")
        XCTAssertTrue(store.contains("lastSavedData"),
                      "点击保存应复用内存中的上一代数据，不应反复解码磁盘备份")
        XCTAssertTrue(store.contains("persistenceQueue"),
                      "生产环境持久化必须离开主线程")
    }
}
