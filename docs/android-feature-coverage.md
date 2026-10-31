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
