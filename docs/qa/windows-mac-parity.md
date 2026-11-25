# Windows 与 Mac 功能及快捷交互核对

核对日期：2026-11-25。当前版本为 **Windows 0.4.1**，产品源码 `3c6bc2163d2cc0cb28a91b117772e079f07a0d34`。核心日常流程已覆盖，仍未完全移植所有 Mac 功能；本轮补齐与剩余差异见下方。当前测试证据见 [0.4.1 验证清单](../releases/windows-v0.4.1-verification.json)。

后半部分保留0.3.0的历史能力矩阵，用于对照本轮变化，不代表0.4.0当前状态。历史发布源码为 `91b8d636e918b6003dfd4c174ca692c5e03e9ef8`；文件链接随main更新，复核旧版应读取对应提交。
核对依据是“从真实界面能进入并完成操作”，不把类、共享数据字段或后台算法的存在视为完整功能。本次审查未运行真实用户资料库、读取账号或 AI 凭据，旧版原生结果只作为历史记录。产品边界以[产品原则](../product-principles.md)为准：保留四象限、想法孵化、今日规划和最小下一步，不以通用待办清单代替它们。

## 0.4.1 完成保留与拖动修正

当天完成项保留在原象限底部，按实际规划时区跨日后从象限收起，完成记录不删除。显示列表与键盘共用投影，可拖动/可安排的活动查询继续排除完成项；不产生额外归档或同步写入。

拖动把手扩大，增加hover说明、来源状态、目标高亮和松手提示。短窗口支持板块滚动、边缘拖动滚动和键盘选中项进入视口。Windows构建完成304项核心和186项桌面测试，零失败/零跳过；0.4.0覆盖升级保留任务、步骤和资料。此补丁没有重新执行原生快捷键/输入法/通知全套验收，下面0.4.0原生结果仅作为历史参考。

## 0.4.0 本轮补齐

以下是相对下文0.3.0基线的变化：

- 主工作区改为四象限、今日计划、搜索与记录、想法与项目；目标从想法页进入，本周回顾与设置保持可见。
- 象限内回车添加；切页面保留草稿，持久化失败不清空，连续回车不重复创建。
- 任务旁可直接启动并行；独立暂停/继续/结束及设置/取消检查时间，个人与并行历史不混算。
- 随手记单行Return/Ctrl+Enter保存后留窗、Esc收起、全量今日与未选今日的运行任务、折叠、详情、完成和整理入口。
- 四象限键盘选择/1–4归类/空格完成恢复/Esc清选，输入框保留正常数字与空格；Ctrl+F进入并聚焦搜索。
- 想法回看日期支持编辑/清除，与背景草稿独立保存，保留时区与并发保护。

组件回归和窗口渲染均使用隔离虚构数据。0.4.0 的 Windows 构建完成304项核心、170项桌面测试，零失败、零跳过；0.3.0覆盖升级保留资料和步骤历史。

本版独立原生验收确认：首次输入、真实快捷键连续10次开合、草稿保留、普通Return保存留窗与防重复、真实复制粘贴、Microsoft Pinyin空格确认中文及两次Enter区分确认/保存、退出后提醒和锁屏期间投递。42项检查通过、5项环境/观察能力不支持，报告整体仍为`incomplete`，不能宣称所有场景已验。物理多屏、候选窗完整布局、通知可见性/点击、独占全屏等范围保持未验证。证据见[0.4.0验证清单](../releases/windows-v0.4.0-verification.json)。

仍未移植：跨应用外点关闭随手记、中文时间提醒解析、早晨规划/晚间收尾/每周回顾定时提醒、完整的如果—那么编辑与系统触发、Mac的AI历史范围精细选择及完成后就地实际用时选择。物理多屏DPI、独占全屏、可见锁屏通知和通知点击仍需对应Windows实机验证。

## 能力矩阵：Windows 0.3.0

| 能力 | Windows 0.3.0 状态 | Mac 对照及差异 | 代码证据 |
|---|---|---|---|
| 四象限、今日选择、重点和排序 | 已实现、可达 | 核心概念相同；Mac 是横向主导航、可折叠待整理栏，Windows 为固定侧导航与独立收件页 | [DesktopUi](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopUi.kt)、[UiTaskDrag](../../desktop/app/src/main/kotlin/com/step/desktop/UiTaskDrag.kt)、[OrganizeView](../../Sources/Pare/OrganizeView.swift) |
| 象限内直接添加 | 入口缺失 | Windows 新建需要任务编辑器；Mac 每个象限底部可以直接输入 | DesktopUi 的 QuadrantPane；OrganizeView 的 QuadrantCard/addField |
| 普通随手记 | 已实现，交互不同 | Windows 多行、Ctrl+Enter、成功后关窗；Mac 单行 Enter 保存后留窗，并显示保存结果 | DesktopUi 的 QuickCaptureUi；[CaptureView](../../Sources/Pare/CaptureView.swift) |
| 自然语言随手记提醒 | 未移植 | Mac 的有限本地解析识别“30分钟后提醒我…”、“明天下午3点提醒…”；Windows 原文存入 inbox。Mac 不是任意自然语言解析，也不后台调用 AI | [CaptureParser](../../Sources/PareCore/CaptureParser.swift)、Store.captureWithResult；DesktopUi 的 checkedContentSave |
| 快捷面板的今日列表 | 入口与显示范围不同 | Windows 只取今日未完成前3项；Mac 完整今日列表、纯净模式、整理入口、详情、专注、并行及完成操作。Windows 当前运行但未入今日的任务不在此列表 | [UiDesktopWidget](../../desktop/app/src/main/kotlin/com/step/desktop/UiDesktopWidget.kt) 的 CaptureTodayControls；CaptureView |
| 个人专注、多个外部并行 | 已实现，快捷入口缺失 | Windows 任务详情“专注 / 等待”可启动等待并设置检查时间，快捷行只有专注启动；两端都区分个人投入和外部运行，不监控其他 AI | [UiEditors](../../desktop/app/src/main/kotlin/com/step/desktop/UiEditors.kt) 的 ExecutionPanel、[UiQuickActions](../../desktop/app/src/main/kotlin/com/step/desktop/UiQuickActions.kt)、[ExecutionController](../../android/app/src/main/java/com/pare/app/ExecutionController.kt)、[PlanningViews](../../Sources/Pare/PlanningViews.swift) |
| 实际用时 | 已实现，快捷闭环不同 | Windows 专注结束记账，详情可校正累计实际投入；Mac 另有完成后就地选择用时、跳过与修改入口。不能把入口缺失说成未实现实际用时 | [UiMetadata](../../desktop/app/src/main/kotlin/com/step/desktop/UiMetadata.kt)、DesktopRuntime.setTaskCompleted；[CompletionTimePicker](../../Sources/Pare/CompletionTimePicker.swift) |
| 最小下一步、完成标准、卡点和5分钟启动 | 已实现、可达 | 快捷任务行和详情可用；完成一步不会自动结束整个任务 | UiQuickActions 的 QuickSupportSheet、UiEditors 的 StepPanel；[NextStepViews](../../Sources/Pare/NextStepViews.swift) |
| 时间轴、跨日安排、个人/检查投入 | 已实现、可达 | 安排与实际运行分别操作，不因安排时间自动开始计时；界面调整不能丢失这些能力 | [UiPlanningTimeline](../../desktop/app/src/main/kotlin/com/step/desktop/UiPlanningTimeline.kt)、UiEditors 的 SchedulePanel；PlanningViews |
| 单次、每日、每周、每月任务提醒 | 已实现、可达 | Windows 在“安排与提醒”中设置重复规则，不能列为缺失能力 | UiEditors 的 SchedulePanel、[TaskReminderActions](../../android/app/src/main/java/com/pare/app/TaskReminderActions.kt)、[ReminderPlanner](../../android/app/src/main/java/com/pare/app/ReminderPlanner.kt) |
| 早晨规划、晚间收尾、每周回顾提醒 | 未移植 | Windows 有回顾页及任务提醒，不等于已有这三类日/周调度 | [Reminders](../../Sources/PareCore/Reminders.swift)、[SettingsWindowController](../../Sources/Pare/SettingsWindowController.swift)；[DesktopReminders](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopReminders.kt)、ReminderPlanner |
| “如果—那么”的时刻计划 | 共享逻辑存在，Windows 无编辑入口 | ReminderPlanner 会消费同步来的时刻计划；desktop UI 无创建、编辑或清除入口 | [ImplementationPlanActions](../../android/app/src/main/java/com/pare/app/ImplementationPlanActions.kt)、ReminderPlanner；Mac PlanningViews |
| “回到电脑”“打开指定应用”触发 | 未移植 | Mac 实际注册回到电脑与应用激活监听；Windows 不能用无效开关表示已支持 | [MomentumManager](../../Sources/Pare/MomentumManager.swift) |
| 想法孵化、阶段、探索记录、关联行动 | 已实现、可达 | Windows 支持笔记/发现/决定、新建或关联已有行动、解除关联和打开子任务，不能简化为普通备注 | UiEditors 的 IdeaEditor；[IdeaWorkspaceView](../../Sources/Pare/IdeaWorkspaceView.swift) |
| 想法回看日期 | 共享逻辑存在，Windows 无编辑入口 | Windows 能消费同步的 reviewAt 并显示本地回看提示，但0.3.0不能自行设置/清除日期 | [IdeaManagementActions](../../android/app/src/main/java/com/pare/app/IdeaManagementActions.kt)、[MobileReview](../../android/app/src/main/java/com/pare/app/MobileReview.kt)、UiEditors；Mac IdeaWorkspaceView |
| 搜索、记录、归档、回收站 | 已实现、可达 | Windows 搜索标题、背景、下一步、想法记录及完成步骤；缺 Mac ⌘F 对应的 Ctrl+F 聚焦入口 | [UiLibrary](../../desktop/app/src/main/kotlin/com/step/desktop/UiLibrary.kt)；OrganizeView、PlanningViews 的 LibraryView |
| ↑↓、数字1–4、空格键归类 | 未移植 | Mac 按视觉顺序选中，数字归类，空格完成/恢复，Esc清除选中；首次无选择按数字只选择首项。Windows 拖动的Esc处理不是这套键盘流程 | OrganizeView 的 navItems/handleKey/KeyCaptureNSView；UiTaskDrag |
| AI 上下文、发送预览、采纳、撤销 | 已实现、可达 | Windows 可授权相关想法、目标、偏好、本机历史、个人背景、同对象最近3条反馈；有精确发送预览和新鲜度检查 | [UiAI](../../desktop/app/src/main/kotlin/com/step/desktop/UiAI.kt)、[DesktopAIConsent](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopAIConsent.kt) |
| AI 参考范围记忆、历史天数、旧总结选择 | 部分不同 | Mac 可记忆范围、选择历史天数和逐条旧总结；Windows 主要采用布尔授权，不宜宣称全部一致 | [AIViews](../../Sources/Pare/AIViews.swift)、[AIService](../../Sources/Pare/AIService.swift)；UiAI、[PlanningAI](../../android/app/src/main/java/com/pare/app/PlanningAI.kt) |
| 本地轻提示和注意力提示 | 已有实现，规则不同 | Windows 有停滞、想法回看、个人投入过载和应用切换提示；Mac 另有待整理堆积、象限四清理等参数与入口 | [UiIntelligence](../../desktop/app/src/main/kotlin/com/step/desktop/UiIntelligence.kt)、[DesktopIntelligencePolicy](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopIntelligencePolicy.kt)、[DesktopAttention](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopAttention.kt)；SettingsWindowController |
| 备份、导入预览与恢复 | 已实现、可达 | Windows 有导入预览、导入前恢复点和损坏恢复；只接受自身内容备份格式，不能宣称 Mac/Windows 导出文件直接互通 | [UiSettings](../../desktop/app/src/main/kotlin/com/step/desktop/UiSettings.kt)、[DesktopBackup](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopBackup.kt) |
| 同账号同步和冲突处理 | 已实现、可达 | 登录先拉取预览，确认才开启上传；本机更改、退出账号与冲突选择有明确状态。密钥和 AI 原始历史不随内容同步 | UiSettings、[DesktopRuntime](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopRuntime.kt)、[DesktopVault](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopVault.kt) |
| 跨设备正在运行状态 | 有时效的只读参考 | 不接管远程计时。Windows presence v2 与旧 Mac/Android 的设备显示兼容性须按实际版本说明，不能等同跨设备实时运行协调 | [DesktopPresence](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopPresence.kt)、[desktop README](../../desktop/README.md) |

## 本轮处理边界

入口与窗口行为优先：Mac式主导航与折叠待整理栏、缩小冗余留白、象限内输入、快捷并行、完整今日/运行任务入口、详情与整理入口、输入焦点、Enter与Esc。跨应用外点关闭仍未实现。想法回看日期可以复用现有 `IdeaManagementActions.review`，保留草稿、来源时区和并发更新检查。

自然语言解析、日/周提醒、应用/回到电脑触发、AI范围记忆等仍按上表记录；不因本轮外观对齐自动标为完成，不增加没有实现的入口。本表不追随本轮代码变化改写历史基线；完成后的验收应另附版本与测试证据。

## 已有验证：区分 Compose 与 Windows 原生

### Compose/共享核心验证

0.3.0发布清单记录 core 304、desktop 142、Android 398项测试零失败，Windows构建跳过0项。`DesktopInputScaleAcceptanceTest` 覆盖注入中英文本、选择替换、草稿保持、保存和不同 Compose 密度；`DesktopWindowBoundsTest` 覆盖工作区几何规则。这些不能单独证明物理输入法候选窗、真实多屏切换或系统通知交付。

### Windows 原生验证

0.3.0已有真实 Windows 交互运行：[native acceptance run](https://github.com/Zita-Go/Pare/actions/runs/45419230623)，摘要见[验证清单](../releases/windows-v0.3.0-verification.json)，具体操作定义见 [windows-interactive-acceptance.ps1](../../.github/scripts/windows-interactive-acceptance.ps1)。整体结论明确为 `incomplete`，不能改称全部通过。

- 已通过原生 Ctrl+Shift+Space 唤出，验证窗口可见且位于前台，未通过强制聚焦伪造结果。
- 已通过实际窗口 DPI 读取、主窗/随手记窗口适配当前屏幕工作区。
- 已通过真实 Microsoft Pinyin 激活：清空旧草稿后仅发送 Latin `nihao` 和空格选词，再用 Ctrl+Enter 保存，要求新增 inbox 内容恰为“你好”。这不是 Compose 注入“你好”的替代测试。
- 已通过原生 Unicode输入、全选替换、剪贴板粘贴/替换/编辑保存及虚构剪贴板清理。
- 已通过通知注册、Show API和历史记录；正常退出后的计划提醒处理/投递，以及工作站锁定期间的提醒处理/投递有通过证据。
- **尚未验证/当前环境不支持**：物理多显示器和混合DPI、输入法候选窗完整位置与裁切、实际可见通知像素、锁屏可见通知像素，以及通知按钮点击。候选窗被观察到不等于候选布局通过；锁定期间处理和投递通过不等于看见锁屏通知。独占全屏也没有验收结论。

上述通过项只支持0.3.0对应的具体路径。将保存改为普通 Enter、加入自动关闭监听或改变窗口结构后，必须重跑相应原生路径；不能直接沿用 Ctrl+Enter 的旧结果证明新行为。

## 快捷面板状态验收清单

以下是修改后的验收要求，**未打勾不代表0.3.0从未测过，也不代表本轮已经通过**。每次执行记录版本、源提交、操作方式和证据，不把“存在测试”写成“测试通过”。全部使用隔离虚构资料库，不保存真实任务、凭据或 AI 原始历史。

| 场景 | 操作与预期 | 证据方式 |
|---|---|---|
| 重复开合与焦点 | 主窗可见、托盘驻留、其他应用前台、刚关闭子详情分别连续唤出/收起至少10次；每次输入焦点正确、原草稿保留、热键不重复触发 | Compose可测焦点请求和草稿；原生验证真正前台与全局热键 |
| 快捷键冲突 | 更改快捷键后验证新组合；占用组合登记失败时旧组合继续有效，设置显示失败原因 | Windows原生 |
| 中文 IME Enter | 组字中 Enter 只确认候选，不提前保存；确认后再次 Enter 只保存一次。覆盖中英混排、数字、空格、连续回车、保存中按键和空输入 | Compose编辑状态回归加实际Pinyin；候选位置单独截图审查 |
| 全选/复制/粘贴 | Ctrl+A/C/V/X/Z只作用于当前输入，包括子详情与日期框；数字/空格不能被四象限监听截走；剪贴板暂不可用仍保留文本并可重试 | Compose加原生剪贴板 |
| Esc与外点关闭 | Esc先关闭最内层菜单/详情，再关闭随手记；外点关闭；点击并行菜单、日期选择器、子详情和候选窗不误关父窗口 | Compose模态和原生窗口生命周期 |
| 保存反馈 | 普通输入成功后按新设计显示反馈；失败不清空、不消失；保存过程中连续提交不重复创建，隐藏后再唤出可重试 | 隔离存储失败与重复提交测试 |
| 子详情与父草稿 | 快捷任务详情、最小下一步与并行菜单往返后，任务ID、来源日计划和父输入草稿正确；切换任务不默默丢弃编辑 | Compose交互 |
| 任务范围 | 空今日、超过3项、已完成、长标题、缺少下一步、多个并行和一项专注；运行但未入今日的任务仍可暂停、结束、打开详情 | Compose projection及操作 |
| 并行与个人投入 | 多项外部运行可共存；只允许一项个人专注；同任务不能同时个人/外部运行；外部等待不计个人投入 | 共享核心加真实UI路径 |
| 完成与记账 | 结束/完成重试不重复记账；完成一步不结束整个任务；可选实际用时不无故覆盖计时记录 | 共享核心与UI |
| 重启恢复 | 隐藏再开保留草稿；退出有未保存保护；已保存数据重启可见；重启后按恢复规则暂停不可确认时长，不补记臆测时长，不重复交付提醒 | 隔离进程重启、原生退出路径 |
| 初始化和外部变更 | 尚未初始化、任务冲突、同步完成/删除、账号退出、提醒过期时不操作错误对象；明确错误并保留草稿 | 隔离运行时与UI |
| 尺寸与缩放 | 窄/宽窗口、100/125/150/200%字体与密度、长文本仍可读且控件可达 | Compose渲染与原生实际DPI分别记录 |
| 多屏和全屏 | 不同DPI物理显示器之间移动、任务栏位置变化、断开/重接屏幕、普通全屏与独占全屏分别验证 | 必须Windows实机，不能用窗口几何单测代替 |
| 通知 | 运行、正常退出、锁定三种状态；可见通知和完成/暂缓/打开按钮分别核对，操作前再次验证任务有效性 | 原生可见证据与点击交付；不以API调用成功替代 |

快捷面板基线窗口在 [Main.kt](../../desktop/app/src/main/kotlin/com/step/desktop/Main.kt) 中为常驻 `Window(visible=...)`、`alwaysOnTop=true`；Mac [PopoverController](../../Sources/Pare/PopoverController.swift) 则明确管理每次唤出焦点、Esc、外点及子窗口边界。Windows [DesktopWindowBounds](../../desktop/app/src/main/kotlin/com/step/desktop/DesktopWindowBounds.kt) 已有工作区约束，但不构成真实多屏或独占全屏通过证明。
