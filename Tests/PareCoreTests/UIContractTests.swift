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
        XCTAssertTrue(organize.contains("HStack(spacing: 0)"),
                      "顶部工作区切换器不能在分页按钮之间留下点击死区")
        XCTAssertTrue(organize.contains(".padding(.horizontal, 1.5)"),
                      "分页按钮的视觉间隔必须属于按钮本身，而不是 HStack spacing")
        XCTAssertTrue(planning.contains("编辑任务详情"))
        XCTAssertTrue(planning.contains("放弃本次专注计时"))
        XCTAssertFalse(planning.contains("showsLabel || isActive"),
                       "紧凑计时按钮不应因运行中而强行展开文字")
        XCTAssertTrue(planning.contains("已逾期"))
        XCTAssertTrue(planning.contains("安排下次回看"))
        XCTAssertTrue(planning.contains("今天只赢一件"))
        XCTAssertTrue(planning.contains("开始 5 分钟"))
        XCTAssertTrue(planning.contains("我卡住了"))
        XCTAssertTrue(planning.contains("如果—那么启动计划"))
        XCTAssertTrue(planning.contains("最低完成版本"))
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
        XCTAssertTrue(popover.contains("scheduleResize"))
        XCTAssertTrue(popover.contains("hosting.invalidateIntrinsicContentSize"),
                      "收起/展开今日任务后必须重新计算 NSPanel 高度")
        let capture = try source("Sources/Pare/CaptureView.swift")
        XCTAssertTrue(capture.contains("chrome.resizeTick += 1"))
        XCTAssertFalse(capture.contains(".onHover"),
                       "随手记悬停不能再触发列表结构重绘")
        let store = try source("Sources/PareCore/Store.swift")
        XCTAssertTrue(store.contains("lastSavedData"),
                      "点击保存应复用内存中的上一代数据，不应反复解码磁盘备份")
    }

    func testSettingsEntryRoutesToSharedWindowAndStaysInTopActions() throws {
        let view = try source("Sources/Pare/OrganizeView.swift")
        let controller = try source("Sources/Pare/OrganizeWindowController.swift")
        let delegate = try source("Sources/Pare/AppDelegate.swift")
        XCTAssertTrue(view.contains("let onOpenSettings: () -> Void"), "Settings must not default to an inert callback")
        XCTAssertTrue(view.contains("Button(action: onOpenSettings)"))
        XCTAssertTrue(view.contains("Label(\"设置\", systemImage: \"gearshape\")"))
        XCTAssertTrue(view.contains(".keyboardShortcut(\",\", modifiers: .command)"))
        let start = try XCTUnwrap(view.range(of: "private var topActions:"))
        let end = try XCTUnwrap(view.range(of: "private var settingsButton:"))
        XCTAssertTrue(view[start.upperBound..<end.lowerBound].contains("settingsButton"))
        XCTAssertTrue(controller.contains("onOpenSettings: onOpenSettings"))
        XCTAssertTrue(delegate.contains("onOpenSettings: { [weak self] in self?.openSettings() }"))
        XCTAssertTrue(delegate.contains("if settingsWindowController == nil"), "Repeated entry must reuse the existing settings window")
    }

    func testAIResultsPutActionBeforeExpandableAnalysis() throws {
        let ai = try source("Sources/Pare/AIViews.swift")
        let cards = try XCTUnwrap(ai.range(of: "ForEach($suggestions)"))
        let analysis = try XCTUnwrap(ai.range(of: "ExpandableNote(title:"))
        XCTAssertLessThan(cards.lowerBound, analysis.lowerBound)
        XCTAssertTrue(ai.contains("为什么这样安排"))
        XCTAssertTrue(ai.contains("采纳会修改哪些内容？"))
        XCTAssertTrue(ai.contains("只修改勾选的内容；不会自动开始专注。"))
        let components = try source("Sources/Pare/InterfaceComponents.swift")
        XCTAssertTrue(components.contains(".lineLimit(expanded ? nil : 2)"))
        XCTAssertTrue(components.contains("Text(text)"), "完整分析必须保留，不能以截断文本替换原文")
    }

    func testSettingsCategoriesPreserveDraftsAndHideInactiveAccessibility() throws {
        let settings = try source("Sources/Pare/SettingsWindowController.swift")
        XCTAssertTrue(settings.contains("case general = \"通用\", reminders = \"提醒\", ai = \"AI\", data = \"数据\""))
        XCTAssertTrue(settings.contains("ZStack(alignment: .topLeading)"))
        XCTAssertTrue(settings.contains(".accessibilityHidden(category != tab)"))
        XCTAssertTrue(settings.contains(".allowsHitTesting(category == tab)"))
        XCTAssertTrue(settings.contains("shortcutController.cancelRecording()"))
    }

    func testReadingAndEmptyStateSafeguardsRemain() throws {
        let theme = try source("Sources/Pare/Theme.swift")
        let planning = try source("Sources/Pare/PlanningViews.swift")
        XCTAssertTrue(theme.contains("accessibilityReduceTransparency"))
        XCTAssertTrue(planning.contains("store.todayTasks.isEmpty ? \"先选一件值得推进的事\""))
        XCTAssertTrue(planning.contains("DisclosureGroup(\"日期与提醒\""))
        XCTAssertTrue(planning.contains(".frame(width: 420, height: 620)"))
    }
}
