# Android 推进、提醒与 AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将0.5设计中尚未接入的专注、外部并行推进、手机提醒和有前情的AI做成可实际使用的手机流程，并补上本地回看。

**Architecture:** 保留现有Compose/SQLite同步核心。运行中的计时状态、模型配置与请求历史仅保存在设备；完成的推进记录和用户采纳的任务字段通过既有内容协议同步。原生AlarmManager/通知承担提醒，不在后台每秒唤醒或自动调用模型。

**Tech Stack:** Kotlin、Compose、Android 8.0+、现有协程/序列化、Keystore、AtomicFile、AlarmManager、HTTPS。

**Spec:** `docs/superpowers/specs/2026-10-30-pare-distinctive-workflows.md` 的后续能力设计，用户本轮明确要求添加未实现功能。

## Global Constraints

- 继续现有`codex/android-sync`开发分支，0.5已提交并通过114项单元测试和4条设备流程；本轮不改Mac正式数据、云端配置或真实密钥。
- 一个设备只运行一个个人专注，可运行多个外部任务；同任务不能同时属于这两类，外部等待不增加个人用时。
- 所有网络AI调用必须由用户点击发起；提供内容预览和明确的历史/想法/目标/偏好选择。密钥、原始请求留本机，测试使用虚构资料与假传输。
- Android通知需要系统权限；精确闹钟不可用时说明可能延迟，不承诺强制停止/厂商省电后仍准时。
- 保存、失败恢复、取消、重复点击、重新启动和跨端修改有明确行为；不显示无功能按钮。
- 保留现有视觉规范、列表密度、草稿保护；大部分能力从具体任务进入，首页只增加必要入口与进行状态。

## Review Focus

- 崩溃发生在写入共享会话后、本地清理前：稳定ID重放不能重复计时。
- 重启或系统时间变化：保守恢复，不把不可靠时间虚算为专注。
- 删除/完成/冲突中的任务：通知不复活旧任务，运行状态不覆盖新内容。
- 权限拒绝、提醒时间改变、通知延后：准确说明，已发提醒不在每次同步重复。
- AI网络失败、带围栏/错误JSON、陈旧建议和撤销：保留错误证据，不自动改任务或泄漏鉴权信息。

## Task 1: 个人专注与并行推进

Files: 新增`Execution*.kt`及`ExecutionControllerTest.kt`；root集成`SyncRuntime.kt`、`PareViewModel.kt`、`MainActivity.kt`。

Interface: `ExecutionController`暴露`StateFlow<ExecutionSnapshot>`，初始化/重核及startFocus/startExternal/pause/resume/finish/cancel/setCheck；IO线程执行命令。`ExecutionDialog`接收任务、快照与命令回调。完成记录复用Mac的progressSessions/parallelRunSessions。

- [x] 用时排除暂停、单专注/多外部、恢复、时钟异常、完成重放、生命周期失效的失败测试。
- [x] 实现持久化状态与纯计算；结束记录与云端编辑保持稳定ID幂等。
- [x] 接入任务菜单和进行中快捷操作，UI刷新不等于后台计时任务。
- [x] 单元/设备验收并独立复核。

## Task 2: 系统提醒

Files: 新增`Reminder*.kt`、`MobileNotification*.kt`及`ReminderPlannerTest.kt`；root新增`TaskReminderDialog.kt`，集成Manifest/深链。

Interface: `MobileNotificationRuntime(context).refresh(state,force)`；`MobileNotificationSettings()`只在上下文中提供权限入口；`TASK_ID_EXTRA`打开对应任务。

- [x] 先测试重复提醒、改期、完成/删除、重复周期/DST、日历提前提醒和运行截止时间。
- [x] 实现持久提醒账本、AlarmManager、通知动作、重启重排；正常同步不重复通知。
- [x] 提醒编辑支持快捷延时、日期时间、重复与清除，保存使用字段基线。
- [x] 拒绝权限/大致提醒状态可见，隔离应用中验收真实通知。

## Task 3: 有前情的 AI

Files: 新增`MobileAI*.kt`及测试；root提供VM编辑回调与入口。

Interface: `MobileAIDialog(state,taskId,onEdit,onDismiss)`，`PareViewModel.editMobileAI(change,onResult)`进入同一事务通道。

- [x] 内容授权、输入预览、结构化建议、语义校验、鉴权保护、采纳/撤销测试。
- [x] 设备本地加密配置与有界请求历史；HTTPS传输限时限量、拒绝重定向和含用户信息URL。
- [x] 从具体任务生成一个可开始的建议，明确下一步、停止标准与建议投入；采纳不改总估时/既定个人检查分配。
- [x] 原始输入输出可看可删，错误可理解，失败不自动重试；假服务端验收，不用真实密钥。

## Task 4: 回看与整体接线

Files: 新增`MobileReview.kt`、`MobileReviewDialog.kt`、`MobileWorkflowTest.kt`，修改主页及版本文档。

- [x] 本地回看按过去7天汇总真实完成、专注、外部记录与卡点；缺数据不编造。
- [x] 最多一个本地轻提示，有明确依据、可暂放，可全局关闭；不自动调用AI或发营销式通知。
- [x] 新增完整模拟器流程与截图；确认原有流程、Mac解码兼容、构建/lint。
- [x] 对各子系统交叉审查，再完整复核；打包0.6测试APK、安装模拟器、更新README与实现范围。

## 实施记录

- 分工：android_execution负责运行状态；android_notifications负责系统提醒；android_ai负责本机模型能力；root负责共享文件、提醒编辑、回看、整体验收。各自只改所属新文件，Gradle串行使用。
- 采用已确认的轻度主动方式，无新增设计审批环节。模型服务地址/密钥由用户在手机里填写；本轮不迁移Mac凭据。

## 最终验证与范围

2026-10-30 完整构建、169项单元测试、9条模拟器UI流程通过，lint0错误/23项非阻断建议。三名实现代理交叉审查后，由独立代理复核；确认并修复时区切换重复提醒与暂放存储失败。Mac解码器通过个人会话、外部会话与提醒载荷。

设备验收修正了ActivityScenario在通知更新Intent后的监测失配，并验证同一Activity/taskId；键盘操作等待布局稳定，截图在同一AVD重启渲染后通过。AI截图验收发现结果被参考资料挤下方，已改为建议置顶、选项/原始JSON按需展开，并重新通过两轮UI流程。

交付为0.6.0本地开发测试包，未推送/正式上架。运行状态留本机，完成记录同步；AI使用假传输验收，手机真实服务需自行配置后验证。实体手机省电行为与跨设备运行协调仍非本轮完成项。
