# 开发与截图

[返回首页](../README.md) · [使用指南](guide.md)

## 本地构建

需要 macOS 13 及以上和 Swift 6 工具链。Swift Package 采用 Swift 5 语言模式；当前发布构建在 Apple Silicon Mac 上验证。

```bash
./build_app.sh
open dist/Pare.app
```

脚本使用 release 编译，组装 `dist/Pare.app`、生成图标并进行本地 ad-hoc 签名；不包含 Developer ID 公证或发布上传步骤。

```bash
swift test
```

数据逻辑测试位于 `Tests/PareCoreTests`。原生窗口验收参考 [UI 冒烟清单](../Tests/UI_SMOKE_CHECKLIST.md)。

## 更新 README 截图

```bash
bash Scripts/screenshot-demo.sh
```

脚本会启动一个独立的 **Pare Screenshots** 应用，直接编译并使用 `Sources/Pare` 中的真实界面组件，入口是 `Scripts/ReadmeDemo.swift`。它不构造正式的 `AppDelegate`，不启动通知调度、全局快捷键或注意力监测。

每次启动都在系统临时目录创建独立演示数据，并使用独立的应用标识 `com.zitago.Pare.ReadmeDemo`。正式 Pare 的任务和偏好不受影响。截图后按 `⌘Q` 退出演示程序即可。

通过演示程序菜单「演示」切换窗口：

1. `⌘1` 打开整理，截取四象限。
2. 点击「今日计划」，截取今日关键任务和五分钟推进入口。
3. 点击「我卡住了」，选择“任务太大”，截取引导面板。
4. `⌘2` 打开随手记，截取浮窗。

演示包括「完成报告提纲」「整理周末旅行计划」等虚构内容。文档图片保存到 `docs/images`，保留原生界面文字与布局。整理窗口统一为 1060 × 660 pt；可截取内容区域避免录屏状态标记进入图片，小浮窗按真实尺寸展示。

仓库提供窗口专用截图工具，按窗口编号捕获，因此不会把挡在前面的其他应用截进去。它仅接受 **Pare Screenshots** 演示程序的窗口；系统可能要求允许录屏。

```bash
# Apple Silicon；只需编译一次
xcrun swiftc -target arm64-apple-macosx13.0 Scripts/capture-window.swift -o /tmp/pare-capture-window

# 将 WINDOW_ID 替换为当前演示窗口编号
/tmp/pare-capture-window WINDOW_ID docs/images/matrix.png 42
```

最后的 `42` 表示拍摄时略过标题栏顶部 42 pt；浮窗和卡点面板无需这个参数。工具使用原生窗口捕获 API，生成 2× PNG，不重绘界面或替换文字。当前四张首页图片合计约 1.6 MB，README 为每张图片提供用途说明和替代文字。

需要验证原生快捷键控件时，可显式运行 `bash Scripts/screenshot-demo.sh --verify-shortcuts`，启动独立标识的 **Pare Shortcut Check**，再从「演示 → 验证快捷键设置」进入。这个模式会注册真实全局快捷键，但仍使用验收应用自己的偏好；验证默认组合前先退出正式 Pare，避免两者争用 `⌥Space`。退出验收会释放快捷键，再启动正式 Pare 即可。普通截图模式不注册快捷键。

## 代码位置

| 位置 | 内容 |
| --- | --- |
| `Sources/PareCore/Models.swift` | 任务、想法、计时与推进记录 |
| `Sources/PareCore/Store.swift` | 操作、持久化、备份、每日归档 |
| `Sources/PareCore/Reminders.swift` | 提醒计划与时间计算 |
| `Sources/PareCore/Review.swift` / `Trends.swift` | 今日、周与长期回顾 |
| `Sources/PareCore/AttentionDrift.swift` | 注意力漂移策略 |
| `Sources/Pare/PopoverController.swift` / `CaptureView.swift` | 随手记浮窗 |
| `Sources/Pare/OrganizeView.swift` / `PlanningViews.swift` | 四象限、今日计划、任务详情与卡点面板 |
| `Sources/Pare/NotificationManager.swift` | macOS 通知与通知动作 |
| `Sources/Pare/AttentionDriftDetector.swift` / `MomentumManager.swift` | 应用、浏览器与情境事件 |
| `Sources/Pare/Theme.swift` / `Components.swift` | 配色、字体与公共控件 |
| `Sources/PareCore/AIAssistance.swift` | AI 请求格式、网络客户端与建议校验 |
| `Sources/Pare/AIService.swift` / `AIViews.swift` | 钥匙串、本机 AI 配置、发送预览与采纳界面 |
| `Resources/AppIcon.png` | 图标母版 |
| `Scripts/ReadmeDemo.swift` | 文档用演示入口与示例数据 |

用户可直接在 App 设置顶部修改随手记快捷键。`Sources/Pare/ShortcutRecorder.swift` 负责窗口内录入，`Sources/PareCore/CaptureShortcut.swift` 管理偏好与注册恢复，`Sources/Pare/HotKey.swift` 负责 Carbon 注册及系统保留组合检查。默认 `⌥Space`，无需为修改组合重新编译。

AI 联调仅使用虚构内容。真实服务地址、模型配置和 API Key 不应放入源码、截图、测试 fixture 或 Git；分别通过 App 本机偏好和钥匙串保存。单元测试使用保留的示例域名与占位模型，不发送付费网络请求。
