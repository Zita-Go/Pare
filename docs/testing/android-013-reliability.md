# Android 0.13 可靠性验收

仅使用独立包 `com.pare.app.qa`、隔离资料库与虚构任务，不需要真实账号、模型密钥或用户资料。当前只有模拟器；用户已确认真机验证暂缓。

**状态约定：**本文已核对测试源码中的覆盖范围，但不据此认定本轮构建通过。执行后在末尾记录实际结果；历史版本的通过记录不替代 0.13 验收。

## 已有自动化覆盖与边界

| 检查项 | 现有测试 | 实际覆盖；尚未代表的场景 |
| --- | --- | --- |
| 提醒注册、隐私与打开任务 | `AndroidCapabilitiesFlowTest.tenMinuteReminderRegistersAlarmAndPrivateNotificationOpensItsTask` | 真 Android `PendingIntent`、通知内容与点击回到同一 Activity；测试直接调用提醒处理器触发通知，**没有等待系统闹钟，也没有实际锁屏**。 |
| 提醒去重、稍后提醒、权限与恢复 | `MobileNotificationRuntimeTest`、`ReminderPlannerTest` | Robolectric / 纯逻辑验证去重、通知拒绝、近似闹钟、删除取消、时区变化、重建注册；恢复测试取消模拟闹钟后强制重建，**没有重启模拟器**。 |
| 专注恢复与完成历史 | `ExecutionControllerTest`、`ExecutionPersistenceTest` | 同次开机恢复、模拟 boot ID 变化后保守暂停、保存失败、历史提交后故障重试不重复计时；**没有杀真实进程**。 |
| 专注真实界面 | `AndroidCapabilitiesFlowTest.focusSurvivesPauseRotationAndCoexistsWithMultipleExternalRuns` | 暂停、Activity 重建、继续、多个外部记录、结束写入；`scenario.recreate()` **不是进程死亡**。 |
| 本机保存与队列 | `RepositoryTest`、`SyncStateTest`、`SyncEngineTest` | 数据库重开、事务失败不损坏内容、持久化请求重放、离线失败恢复、旧回执不覆盖新编辑。同步测试使用内存服务与虚构会话，**没有真实云端上传**。 |
| 并发与冲突 | `SyncStateTest.concurrentChangesPreserveBothVersionsUntilExplicitChoice`、`ContentEditGuardTest`、`PareMobileFlowTest` | 保留冲突双方、等待明确选择；编辑器打开时注入更新，拒绝过期保存并保留草稿。**没有两台真实设备同时编辑**。 |
| 调度与重试 | `SyncSchedulerTest`、`SyncRetryPolicyTest`、`SyncRequestLoopTest` | 网络约束、唯一后台任务、退避与前台请求合并；**不等于后台长时间投递实测**。 |
| 0.13 启动路径与大字体 | `TaskStartFlowTest`、`TaskStartPersistenceTest`、`PriorityVisibilityTest`、`EditorAccessibilityTest` | 真实 Dialog 的 1.6 字体、下一步在开始前、工具仍可达、保存后进入专注、未修改内容保持原样；本轮用例已通过；保留后续真机检查。 |

源码位置：[`src/test`](../../android/app/src/test/java/com/pare/app) 与 [`src/androidTest`](../../android/app/src/androidTest/java/com/pare/app)。

## 执行命令

在仓库根目录运行；连接的 Android 设备应仅为隔离测试模拟器。

```sh
./android/build.sh testDebugUnitTest assembleDebug lintDebug
./android/build.sh connectedQaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/data/com.pare.app.qa/files/ui-acceptance
```

可靠性与编辑器的定向重跑：

```sh
./android/build.sh connectedQaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pare.app.AndroidCapabilitiesFlowTest,com.pare.app.PareMobileFlowTest,com.pare.app.NotificationDisclosureTest,com.pare.app.TaskStartFlowTest,com.pare.app.TaskStartPersistenceTest,com.pare.app.PriorityVisibilityTest,com.pare.app.EditorAccessibilityTest \
  -Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/data/com.pare.app.qa/files/ui-acceptance
```

保留 Gradle 完整结果、失败测试名称、模拟器 API/尺寸/字体比例及虚构数据截图。报告位于 `android/app/build/reports/`，机器可读结果位于 `android/app/build/test-results/` 和 `android/app/build/outputs/androidTest-results/`。定向重跑只能证明列出的测试，不能写成全套通过。

## 模拟器待执行检查

以下各项在有实际记录前均为 **未验证**。自动化执行与手工生命周期检查分开进行，避免中途终止 instrumentation。

1. **锁屏与真实到点通知。** 在 QA 包创建虚构任务，通过现有“提醒我”设置十分钟后提醒；允许通知，记录精确闹钟权限与界面显示的时间精度。设置系统锁屏隐藏敏感通知，返回桌面并锁屏，等待系统实际投递。确认锁屏隐藏任务文字、解锁后可查看、点击打开正确任务且返回栈无重复页面。分别检查通知拒绝、近似时间提示、稍后提醒及任务完成后的取消。精确权限未授予时按近似投递记录，不能据此承诺整点准时。
2. **后台进程死亡后恢复。** 保存任务、下一步、今日安排，开始短时专注后返回桌面。结束 QA 后台进程，确认 PID 已消失，再启动 QA。检查已保存内容、同一次专注标识和暂停/到点状态；结束并记录一次，重开后历史与实际用时不重复增加。未保存编辑草稿只声称已有的 Activity 重建覆盖，进程死亡后的草稿恢复应另记实测结果。
3. **强制停止边界。** 单独执行强制停止，再主动打开 QA，检查内容和运行恢复。强制停止与普通后台进程回收分别记录；不能把强制停止期间没有闹钟当成普通后台提醒失效，也不能把重新打开后的恢复当成持续后台投递已验证。
4. **模拟器重启。** 预先保存一条未来提醒和一段未结束专注，正常重启模拟器；解锁后检查专注保守恢复、未来提醒重建，已处理提醒不重复。先观察系统恢复，再打开应用检查内容；只有打开应用后恢复的情况应如实记录。不要用手动广播 `BOOT_COMPLETED` 代替实际重启。
5. **离线编辑与恢复。** 保持 QA 未登录，关闭模拟器网络，新增并修改虚构任务、四象限与今日安排，重启应用确认本机数据仍在；恢复网络后再次确认。未登录的本机修改并不证明云端队列已上传：不可变请求重试与回执处理由 `SyncEngineTest` / `SyncStateTest` 的假服务测试验证，真实服务恢复上传仍列为未验证。
6. **同时编辑与旧草稿。** 执行 `PareMobileFlowTest` 的编辑器更新注入场景及 `ContentEditGuardTest`、`SyncStateTest`。确认手机草稿与注入版本均未被静默覆盖、冲突需明确选择。此项无需账号；真实跨设备网络竞态不在当前证据范围。
7. **详情页回归。** 普通字体与 1.6 字体分别检查标题 → 优先级 → 最小下一步 → 开始专注；“拆小一点”直接可达。展开其他操作检查安排、标记完成/未完成、AI、提醒、今日投入、加入今天/重点和关联想法；展开更多信息检查备注与预计时长。修改后操作应先保存，保存失败留在编辑器；开始仍进入时长选择，未点击确认不启动。

生命周期命令示例（先以 `adb devices` 确认序列号；这里只操作 QA 包）：

```sh
PARE_QA_SERIAL=emulator-5554
adb -s "$PARE_QA_SERIAL" shell am kill com.pare.app.qa
adb -s "$PARE_QA_SERIAL" shell pidof com.pare.app.qa
adb -s "$PARE_QA_SERIAL" shell am start -n com.pare.app.qa/com.pare.app.MainActivity
```

`pidof` 无输出才可记录后台进程已退出；未退出应记录为未完成该步骤。返回桌面和锁屏通过模拟器系统界面完成。强制停止用 `adb -s "$PARE_QA_SERIAL" shell am force-stop com.pare.app.qa`；实际重启用 `adb -s "$PARE_QA_SERIAL" reboot`，等待启动完成并解锁后继续。以上命令是待执行步骤，不是执行记录。

## 本轮证据登记

| 项目 | 当前记录 |
| --- | --- |
| 当前构建、单元测试、Lint | 2026-11-01：369项单元测试通过；Debug构建与签名通过；Lint 0错误/43警告/1提示 |
| 当前模拟器自动化 | 73条不同设备用例合并通过；初次72条66通过，修复后按受影响范围复验，最后10条及7条均通过。详见 android-013-evidence.json |
| 锁屏真实到点、后台进程死亡、强制停止、实际重启 | 真实闹钟投递和通知打开通过；活进程强停/新进程恢复通过；熄屏锁屏未观察，普通系统回收与设备重启未验证 |
| 真机、省电策略、长期待机 | 暂缓：用户当前没有可用真机 |
| 真实账号云端恢复上传、两台设备同时编辑 | 未验证；本清单不要求真实账号或真实数据 |


### 2026-11-01 实际补验

`MobileAlarmDeliveryTest` 注册真实 AlarmManager，未直接调用通知处理器。通知实际发布、私密元数据、明确打开目标任务通过；[原始时间与状态](../images/android/v0.13/alarm-delivery.json)。请求锁屏后未观察到屏幕关闭和 Keyguard 锁定，因此锁屏投递及视觉隐藏仍未验证。

`ProcessRecoveryTest` 分别执行 `seedOfflineFocus`（参数 `pareRecoveryPhase=seed`）和 `verifyAfterProcessRestart`（参数 `pareRecoveryPhase=verify`）。两阶段之间通过正式 MainActivity 启动 QA，观察活进程 PID 5761；执行 `am force-stop com.pare.app.qa` 后 `pidof` 无输出。校验阶段在不同真实 PID 中读取原 SQLite、AtomicFile 和离线队列，同一任务、投入、提醒、专注ID均保留；结束并重复核对三次后只有一条历史记录。两阶段各报告 `OK (1 test)`，最终清理 QA 虚构资料。这不等同于设备重启或普通系统后台回收。

本轮没有真实账号云端往返、真实模型服务或实体手机测试。用户明确选择先完成开发与模拟器验收。独立复核发现的相邻任务顺序变化后撤销问题已补充保护和回归测试；后续备注和历史不回退。
