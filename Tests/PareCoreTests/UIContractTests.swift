import XCTest
import ImageIO

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

    private func imageSize(_ path: String) throws -> (Int, Int) {
        let url = repositoryRoot.appendingPathComponent(path)
        let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        return (try XCTUnwrap(properties[kCGImagePropertyPixelWidth] as? Int),
                try XCTUnwrap(properties[kCGImagePropertyPixelHeight] as? Int))
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
        let detailsButtonStart = try XCTUnwrap(planning.range(of: "struct TaskDetailsButton"))
        let detailsButtonEnd = try XCTUnwrap(planning.range(of: "private struct TaskDetailView"))
        let detailsButton = planning[detailsButtonStart.lowerBound..<detailsButtonEnd.lowerBound]
        XCTAssertTrue(detailsButton.contains(".frame(width: 36, height: 32)"),
                      "省略号详情按钮必须保留易于命中的点击区域")
        XCTAssertTrue(detailsButton.contains(".contentShape(Rectangle())"),
                      "省略号周围的透明区域也必须响应点击")
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
        XCTAssertTrue(capture.contains("activeLabel: \"暂停\", showsCancel: false"),
                      "随手记必须直接提供个人专注的启动和暂停")
        XCTAssertTrue(capture.contains("asksCompletionOnStop: false"),
                      "随手记暂停专注时不能再弹出二次确认")
        XCTAssertTrue(capture.contains("QuickParallelRunButton(store: store, task: task)"),
                      "随手记必须直接提供并行任务的启动和暂停")
        XCTAssertTrue(capture.contains("store.startParallelRun(task.id)"))
        XCTAssertTrue(capture.contains("store.pauseParallelRun(task.id)"))
        let store = try source("Sources/PareCore/Store.swift")
        XCTAssertTrue(store.contains("lastSavedData"),
                      "点击保存应复用内存中的上一代数据，不应反复解码磁盘备份")
        let appDelegate = try source("Sources/Pare/AppDelegate.swift")
        XCTAssertTrue(appDelegate.contains("NSApp.applicationIconImage = icon"),
                      "菜单栏 App 启动时也要显式提供通知中心可解析的应用图标")
        let build = try source("build_app.sh")
        XCTAssertTrue(build.contains("<key>CFBundleIconFile</key><string>AppIcon.icns</string>"))
        XCTAssertTrue(build.contains("<key>CFBundleIconFiles</key>"))
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

    func testParallelProgressStaysSeparateAndDiscoverable() throws {
        let planning = try source("Sources/Pare/PlanningViews.swift")
        let capture = try source("Sources/Pare/CaptureView.swift")
        let notifications = try source("Sources/Pare/NotificationManager.swift")
        XCTAssertTrue(planning.contains("Label(\"正在进行\", systemImage: \"arrow.triangle.2.circlepath\")"))
        XCTAssertTrue(planning.contains("不计入个人专注时长"))
        XCTAssertTrue(planning.contains("ParallelRunButton(store: store, task: task"))
        XCTAssertTrue(planning.contains("store.pauseParallelRun"))
        XCTAssertTrue(planning.contains("store.completeParallelRun"))
        XCTAssertTrue(planning.contains(".id(\"parallel-\\(task.id.uuidString)\")"))
        XCTAssertTrue(planning.contains(".id(\"completed-\\(task.id.uuidString)\")"))
        XCTAssertTrue(capture.contains("store.parallelRuns.contains"))
        XCTAssertTrue(notifications.contains("PARE_PARALLEL_CHECK"))
        XCTAssertTrue(notifications.contains("15 分钟后再检查"))
        let status = try source("Sources/Pare/StatusItemController.swift")
        XCTAssertTrue(status.contains("↻\\(running)"))
        XCTAssertTrue(status.contains("件任务正在同时进行"))
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
        XCTAssertTrue(planning.contains("return \"先选一件值得推进的事\""))
        XCTAssertTrue(planning.contains("有 \\(running.count) 件事正在其它地方推进"))
        XCTAssertTrue(planning.contains("DisclosureGroup(\"日期与提醒\""))
        XCTAssertTrue(planning.contains(".frame(width: 420, height: 620)"))
    }

    func testReadmeScreenshotsUseNativeRetinaResolution() throws {
        let expected: [String: (Int, Int)] = [
            "docs/images/matrix.png": (2120, 1236),
            "docs/images/today.png": (2120, 1236),
            "docs/images/capture.png": (848, 890),
            "docs/images/momentum.png": (860, 852),
            "docs/images/settings.png": (1160, 1260),
            "docs/images/ai-plan.png": (1200, 1280),
        ]
        for (path, size) in expected {
            let actual = try imageSize(path)
            XCTAssertEqual(actual.0, size.0, "\(path) must remain a native 2× capture")
            XCTAssertEqual(actual.1, size.1, "\(path) must remain a native 2× capture")
        }
    }
}
