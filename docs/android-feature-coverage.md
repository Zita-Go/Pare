# Android 与 Mac 功能覆盖核对

核对日期：2026-10-31。比较基线为 `031dd947e9a0d914d2d02ce47e0ccc9fedd10636`（Android 0.7 界面版本），以可从产品界面进入的操作及其保存路径为准。数据结构能解码、同步或被 AI 读取，不代表手机已有相应编辑功能。

这是源码核对，不是本轮真机、真实账号、真实模型或双端网络同步验收。“已实现”只指该行所述范围；不能据此宣称 Android 已完整移植 Mac。后续修改应在文末记录增量，不改写基线事实。

| 用户流程 | Android 基线状态与实际范围 | Mac 差距及源码依据 |
|---|---|---|
| 随手记、任务与四象限 | **已实现基础流程**：创建与编辑随手记、任务，归入四象限、加入今天、完成与恢复未完成；手机按象限分组显示。 | Mac 另有拖入象限、键盘归位、随手记保留为想法、清空第四象限等操作。Android：[MainActivity](../android/app/src/main/java/com/pare/app/MainActivity.kt)、[OrganizeDialog](../android/app/src/main/java/com/pare/app/OrganizeDialog.kt)；Mac：[OrganizeView](../Sources/Pare/OrganizeView.swift) 的 `InboxCard`、`QuadrantCard`。 |
| 今日重点与投入 | **已实现**：加入/移出今天、一个今日重点；区分个人投入和检查时间，保留总估时；已有当天时间块可随投入调整时长。 | 不是只显示一张待办清单，但基础界面没有今日任务排序或可操作时间轴。Android：[TodayAllocationDialog](../android/app/src/main/java/com/pare/app/TodayAllocationDialog.kt)、[WorkflowActions](../android/app/src/main/java/com/pare/app/WorkflowActions.kt) 的 `DayPlanActions`、[PareViewModel](../android/app/src/main/java/com/pare/app/PareViewModel.kt)；Mac：[DailyAllocationPill](../Sources/Pare/DailyAllocationPill.swift)。 |
| 今日排序与时间线 | **未实现交互**：能读取同步来的顺序、时间块和提醒；没有拖动排序、在时间轴安排/移动任务、拖边调整时长的手机入口。**本次优先补齐。** | Mac 已有清单拖动排序、待安排区、15 分钟格、时间块移动与缩放、重叠分栏、检查点、取消安排。Mac：[PlanningViews](../Sources/Pare/PlanningViews.swift) 的 `TodayPlannerRow`、`DayTimelineView`、`TimelineTaskBlock`；Android 基线入口：[MainActivity](../android/app/src/main/java/com/pare/app/MainActivity.kt) 的今天分支。 |
| 想法孵化 | **已实现主流程**：创建/编辑想法，设置种子/探索/推进/暂放/归档阶段与问题，追加记录/发现/决定，拆出关联行动、加入今天、回到来源想法。 | **部分移植**：手机没有关联已有任务/解除关联、修改想法回看日期、想法搜索与阶段筛选、删除想法入口；同步来的回看日期可触发本地回看。Android：[IdeaWorkspace](../android/app/src/main/java/com/pare/app/IdeaWorkspace.kt)、[MobileReview](../android/app/src/main/java/com/pare/app/MobileReview.kt)；Mac：[IdeaWorkspaceView](../Sources/Pare/IdeaWorkspaceView.swift) 的 `linkedActionsSection`、`reviewSection`。 |
| 卡点与最小下一步 | **已实现**：选择四类卡点，写下具体动作和做到哪里就停，安排 5/10/15 分钟或自定投入，保留检查类型；保存不自动启动。 | Mac 还提供换一个较轻任务、保存后直接开始 5 分钟等连贯操作。Android：[TaskSupportDialog](../android/app/src/main/java/com/pare/app/TaskSupportDialog.kt)、[TodayWorkflowModels](../android/app/src/main/java/com/pare/app/TodayWorkflowModels.kt)；Mac：[PlanningViews](../Sources/Pare/PlanningViews.swift) 的 `MomentumRescueView`。 |
| 自己专注、交给 AI 或别人后等结果 | **已实现本机记录**：单个个人专注、多个等待记录；开始/暂停/继续/结束/取消、检查提醒。结束历史随任务同步。 | **未实现跨设备实时协调**：手机与 Mac 不共享正在运行的计时控制，Pare 不连接或控制用户在别处使用的 AI。Android：[ExecutionDialog](../android/app/src/main/java/com/pare/app/ExecutionDialog.kt)、[ExecutionController](../android/app/src/main/java/com/pare/app/ExecutionController.kt)、[ExecutionPersistence](../android/app/src/main/java/com/pare/app/ExecutionPersistence.kt)；Mac：[PlanningViews](../Sources/Pare/PlanningViews.swift) 的 `FocusTimerButton`、`ParallelRunButton`。 |
| 提醒与任务日期 | **已实现提醒**：延后 10/30/60 分钟、自选日期时间、每日/每周/每月重复、清除；系统通知打开任务/稍后/关闭，时间块与计时检查可通知。 | **部分移植**：提醒不是截止日期；手机没有单独编辑截止时间、手填实际用时或“如果…就…”行动触发条件的界面。Android：[TaskReminderDialog](../android/app/src/main/java/com/pare/app/TaskReminderDialog.kt)、[MobileNotificationRuntime](../android/app/src/main/java/com/pare/app/MobileNotificationRuntime.kt)；Mac：[PlanningViews](../Sources/Pare/PlanningViews.swift) 的 `TaskDetailView`。 |
| 有前情的单任务 AI | **已实现**：默认当前任务，可明确附带关联想法/目标/偏好/历史；预览、主动请求、2–5 分钟下一步/停止标准/理由、采纳与条件撤销、本机原始记录。 | 这是单任务下一步功能。不能用顶部“AI 助手”名称推断具备 Mac 全部 AI 工作流。Android：[MobileAIViews](../android/app/src/main/java/com/pare/app/MobileAIViews.kt)、[MobileAI](../android/app/src/main/java/com/pare/app/MobileAI.kt) 的 `MobileAIProposal`、`MobileAIActions`；Mac：[AIViews](../Sources/Pare/AIViews.swift)、[IntelligentAssistantView](../Sources/Pare/IntelligentAssistantView.swift)。 |
| AI 想法探索、归位、今日与目标规划 | **未移植这些工作流**：手机 AI 的采纳只更新当前任务的下一步、停止标准与进展标记，不创建想法探索建议、批量归位或整日计划。 | Mac 已有 `.idea`、`.today`、`.unblock` 智能助手，以及 `.organize`、`.plan`、`.nextStep` 流程，可选择采纳动作。依据：[IntelligentAssistantView](../Sources/Pare/IntelligentAssistantView.swift)、[AIViews](../Sources/Pare/AIViews.swift) 的 `AIAssistantView`，对照 [MobileAI](../android/app/src/main/java/com/pare/app/MobileAI.kt)。 |
| 长期目标与可管理偏好 | **仅能用已有数据作授权参考**：手机 AI 可读取同步来的关联目标和已确认偏好；没有目标创建/编辑/关联任务、偏好添加/改写/忘记界面。 | Mac 有目标编辑与规划、显式规划偏好、带范围与有效期的确认偏好。Android：[MobileAI](../android/app/src/main/java/com/pare/app/MobileAI.kt) 的 `MobileAIContext.evidence`；Mac：[GoalPlanningView](../Sources/Pare/GoalPlanningView.swift)、[IntelligenceSupportViews](../Sources/Pare/IntelligenceSupportViews.swift)。 |
| 回看与轻提示 | **已实现近 7 天本地回看**：完成数、实际专注/等待时长、卡点、到期想法与久未记录推进的任务；可暂放或关闭首页轻提示，不后台调用 AI。 | **部分移植**：没有 Mac 的象限完成分布、估时校准、最近 8 周趋势与复制周回顾。两端统计窗口也不同（滚动 7 天/本周），数字不应直接当成同口径。Android：[MobileReview](../android/app/src/main/java/com/pare/app/MobileReview.kt)、[MobileReviewDialog](../android/app/src/main/java/com/pare/app/MobileReviewDialog.kt)；Mac：[WeeklyReviewWindowController](../Sources/Pare/WeeklyReviewWindowController.swift)。 |
| 搜索、任务归档与删除恢复 | **未移植完整管理流程**：手机可展开已完成任务；想法可以通过阶段归档/恢复，但没有全局内容搜索、任务归档库、任务/随手记/想法删除与撤销入口。 | Mac 有分类搜索、截止清单、归档恢复与删除撤销。Android 基线导航：[MainActivity](../android/app/src/main/java/com/pare/app/MainActivity.kt)、[VisualComponents](../android/app/src/main/java/com/pare/app/VisualComponents.kt)；Mac：[PlanningViews](../Sources/Pare/PlanningViews.swift) 的 `LibraryView`、`ArchiveRow`、`UndoToast`。 |
| 账号、内容同步与冲突 | **已实现内容同步路径**：本地编辑、明确开启/暂停同步、查看冲突并选择版本。 | 不能等同于运行状态、密钥或 AI 原始记录同步，也不是本轮真实服务验收。Android：[AccountDialog](../android/app/src/main/java/com/pare/app/AccountDialog.kt)、[SyncEngine](../android/app/src/main/java/com/pare/app/SyncEngine.kt)、[MobileAIVault](../android/app/src/main/java/com/pare/app/MobileAIVault.kt)；Mac：[SyncSettingsView](../Sources/Pare/SyncSettingsView.swift)。 |

## 本轮界面调整

- 执行弹窗默认“自己做”，次级入口“任务已交给 AI 或别人？”打开“等结果”。“等待结果”只代表用户手动记录的等待，不暗示 Pare 能控制或观察其他 AI。
- 今日安排使用“自己做 / 等结果”，分别询问今天想做几分钟、检查结果要几分钟。检查投入、等待时长、总估时、已经计下的实际专注各有含义。
- 卡点页继续保留最小下一步、停止标准与当日投入；回看继续单独显示专注和等待记录。
- 以上措辞与入口整理不改变 `personal` / `check` 数据类型、执行命令、计时校验或同步格式。

## 0.8已验收增量

上述表格保留0.7基线。0.8已经加入可操作日期时间轴：点空白创建、待安排排序/拖入、已排事项移动/缩放、重叠分栏、跨午夜显示、精确时间表单和条件撤销。任务菜单缩为三项，其他工具集中到详情；今日重点与投入反馈保留。未来日期快速记录不会误写今天，跨午夜提前提醒与旧Mac未来日程迁移已覆盖。

本轮208项单元测试、17条模拟器流程通过，真实手势验证拖动中不写入、松手才保存和撤销恢复。Mac解码兼容通过虚构载荷；不等同于真实账号网络同步或实体手机验收。查看[0.8用法与截图](releases/android-v0.8.0.md)。其余表内缺口继续存在，未通过统一改名宣称完成。

## 0.9 实现增量

以下记录0.9开发版已实现并验收的流程，不改写上面的0.7基线或0.8验收记录。云端状态服务已部署，应用安装包供本地验收，尚未发布新的公开Release。

| 用户流程 | 0.9 实现范围 | 保留的边界与依据 |
|---|---|---|
| 日期时间轴 | 原有日内拖动之外，可长按时间块拖到前一天/后一天的落区，保持原当地时刻和时长；编辑表单可选任意日期。源/目标及受影响旧快照作为一组保存，目标已有同任务安排时拒绝覆盖。 | 不自动挪开其他预约。DST 无效或有歧义的时刻拒绝自动纠正；不同规划时区须先确认。条件撤销以尚未被后续修改的保存结果为准。[TimelineActions](../android/app/src/main/java/com/pare/app/TimelineActions.kt)、[TodayTimeline](../android/app/src/main/java/com/pare/app/TodayTimeline.kt)。 |
| 搜索与资料库 | 从“更多”或任务/想法入口查询任务、想法、随手记和目标；支持分类、截止清单、任务归档/恢复、显式删除。 | 不替代四象限取舍、想法孵化或今日容量。[LibraryDialog](../android/app/src/main/java/com/pare/app/LibraryDialog.kt)、[LibraryActions](../android/app/src/main/java/com/pare/app/LibraryActions.kt)。 |
| 本机回收站 | 删除与恢复资料在同一次本地事务中保存，重启后仍可管理；原删除状态未变时恢复原记录，否则可明确恢复为新副本。 | 回收资料留在本机，不进内容同步流；最多 100 项/2 MB，满额时拒绝新的删除。30 天仅作为可清理提示，不自动清除。副本不自动占用原任务日程。[LocalTrash](../android/app/src/main/java/com/pare/app/LocalTrash.kt)。 |
| 想法管理 | 关联/解除已有任务，编辑回看日期，结合资料库搜索和阶段管理；原探索记录、问题和拆出行动流程保留。 | 解除关联不删除任务，删除想法不连带删除行动。[IdeaManagementDialog](../android/app/src/main/java/com/pare/app/IdeaManagementDialog.kt)、[IdeaManagementActions](../android/app/src/main/java/com/pare/app/IdeaManagementActions.kt)。 |
| 长期目标与偏好 | 创建/编辑/删除目标，关联已有任务或明确创建一个起步任务；管理背景、规划与起步分钟、文字偏好，以及带范围和有效期的确认偏好。 | 不自动推断偏好，不把未确认的模型建议写成长期记忆；保留不认识的同步字段，冲突或陈旧编辑须重新确认。[GoalsWorkspace](../android/app/src/main/java/com/pare/app/GoalsWorkspace.kt)、[PreferencesWorkspace](../android/app/src/main/java/com/pare/app/PreferencesWorkspace.kt)。 |
| 多种 AI 协助 | 顶部默认“安排这一天”，参考所选日期、候选任务、容量与精力；想法内可探索并追加发现/问题或创建关联行动；随手记可审阅后归为任务或想法；目标可生成一件新关联行动，也可为显式选择的已有任务更新下一步。 | 每次先预览再主动发送、逐项采纳。目标默认仅发送该目标资料；新任务不自动加入今日。保留现有时间块、个人/检查类型和未选安排；不是自动路线图或后台代理。非法、越权、过量或陈旧结果不能写入。[PlanningAI](../android/app/src/main/java/com/pare/app/PlanningAI.kt)、[PlanningReview](../android/app/src/main/java/com/pare/app/PlanningReview.kt)。 |
| AI 隐私与历史 | 新旧流程共用本机加密服务配置、密钥与有界请求历史；完整发送内容可展开，额外目标、想法、偏好和历史均由用户明确选择。 | 不新增固定服务地址；不自动调用真实模型。历史只在授权的工作流/主题/候选任务范围内参与后续请求，先前原始请求不作为隐含授权。[MobileAIModel](../android/app/src/main/java/com/pare/app/MobileAIModel.kt)、[MobileAIVault](../android/app/src/main/java/com/pare/app/MobileAIVault.kt)。 |
| 卡点到开始 | 保留“只保存”，另可选择“保存并开始5分钟”；仅在保存成功后请求启动，失败不启动，旋转后保留待完成的意图并防止重复触发。 | 真实启动仍经过本机运行状态与跨端提示；有其他设备状态时先确认，不把保存成功称作已经计时。[TaskSupportDialog](../android/app/src/main/java/com/pare/app/TaskSupportDialog.kt)、[TodayWorkflowModels](../android/app/src/main/java/com/pare/app/TodayWorkflowModels.kt)。 |
| 任务日期与行动触发 | 可独立编辑截止日期、记录实际用时；编辑 `time/hour/minute/action` 型“如果到这个时刻，就先做……”计划，并使用已有通知机制提醒当天已选任务。 | 截止、普通提醒、日程时间块和行动提示彼此独立。过点新设置不补发；返回电脑/打开其他应用的触发条件只读保留，手机不冒充能监听这些事件，不自动开始计时或 AI。[TaskMetadataDialog](../android/app/src/main/java/com/pare/app/TaskMetadataDialog.kt)、[ImplementationPlanActions](../android/app/src/main/java/com/pare/app/ImplementationPlanActions.kt)。 |
| 回顾与复制 | 默认保留滚动近 7 天回看；按需展开规划时区下的 8 个自然周实际完成/专注/等待趋势、象限完成分布和估时校准。可主动复制本地汇总。 | 本周未结束会注明；校准只用同时有预计和实际分钟的已完成样本，缺失时明确无数据。等待不算个人专注，复制不含任务标题、备注或 AI 原始记录。[ReviewAnalytics](../android/app/src/main/java/com/pare/app/ReviewAnalytics.kt)、[MobileReviewDialog](../android/app/src/main/java/com/pare/app/MobileReviewDialog.kt)。 |
| 跨端运行状态 | Mac/Android 实现独立的短期运行摘要协议与设备展示：专注/等待状态、计时摘要、新鲜度及重复开始前的提示；共享默认关闭，由本机针对账号和设备明确开启。 | 状态参考不是远程计时控制：不能从手机暂停/接管 Mac，也不能控制其他 AI。关闭共享不影响内容同步；暂停同步或退出停止共享，旧报告超时后视为未知。服务部署与实际 HTTP 联通验收另记，不用界面入口代替验收。[DevicePresenceController](../android/app/src/main/java/com/pare/app/DevicePresenceController.kt)、[SyncCoordinator](../Sources/Pare/SyncCoordinator.swift)。 |

仍未宣称完整移植：桌面全局快捷键与窗口操作、返回电脑/应用启动监听、远程控制另一设备计时、自动管理外部 AI、账号间独立资料库切换，以及生产签名发布。手机采用适合触控的入口，不逐项复制 Mac 或竞品菜单。

0.9 验收记录：

- Android：324项单元测试、29条不同设备流程通过；跨日表单测试修正日期语义定位后，5条主界面流程重跑通过。Debug构建成功，Lint 0错误、26警告、1提示。
- Mac：343项测试、Release构建及签名检查通过；Android 0.9虚构目标/偏好/行动触发/跨日计划等载荷通过正式Mac解码器。
- 云端：状态接口已部署；SQL回滚回归和18项真实HTTPS检查通过。使用两个独立Swift客户端模拟设备协议，不等同于Android进程或双端GUI联网验收；测试账号与关联资料已清理。
- 产物：Android 0.9.0 debug APK，Mac 2.14.0 ad-hoc包；均为开发验收产物。Android普通模拟器已升级并保留原数据，真实Mac安装未替换。截图为隔离虚构资料的1080像素原图，已目视检查。详见[0.9用法](releases/android-v0.9.0.md)。

## 0.9.2 日常查看与安排

今天默认紧凑清单，按实际时段和未定时间归组，完成项按需展开；时间轴保留全部安排手势。两种视图共用日期并保留滚动位置，清单提供调整时间、开始/暂停入口。保存提示改为明确短时显示，并保留撤销和关闭。327项单元测试及16条相关界面流程完成验收，详情见[0.9.2说明](releases/android-v0.9.2.md)。
