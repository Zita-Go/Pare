# Android Visual Clarity Implementation Plan

**Goal:** 优化0.6各页面的布局、配色与文字层级，保持业务行为与扫描效率。
**Spec:** `docs/superpowers/specs/2026-10-30-android-visual-clarity.md`。
**Architecture:** 沿用Compose主题与控件；root负责主题/列表/共享输入/验证，三个代理按UI文件边界并行。

- [x] Root：PareTheme、FormVisualComponents、VisualComponents、MainActivity统一底色、文字与首页布局；不改业务回调。
- [x] 表单代理：EditorDialog、IdeaWorkspace、TodayAllocationDialog、TaskSupportDialog、OrganizeDialog。
- [x] 推进代理：ExecutionDialog、TaskReminderDialog、MobileNotificationSettings。
- [x] AI代理：MobileAIViews、AccountDialog、MobileReviewDialog，仅UI部分。
- [x] 校验浅深色正文/辅助/主按钮与重点的对比度，保存现有点击标签与48dp目标。
- [x] 运行现有功能回归、构建、lint；补充页面明暗/1.6倍字体的实际截图，独立复核修改。
- [x] 交付0.7测试APK、截图说明，安装可见模拟器，保存本地提交。

基线eb4ee57；169项单测与9条模拟器流程已通过。此次不改Mac/云端/真实模型配置，不引入网络依赖或新字体。Gradle串行执行，代理不自行运行或提交。

## 验收记录

171项单元测试通过，含明暗主题文字配色4.5:1对比检查。完整11条设备流程通过；主按钮圆角收尾后定向复验2条专注/提醒流程通过。

截图复核发现外层LocalDensity没有可靠进入Dialog的新AndroidComposeView；已改为QA专用Context资源字体设置，直接读取TextLayoutResult的实际fontScale。重新验收编辑器、规划与卡点共3条流程通过，实际fontScale=1.6，关键操作可达且至少48dp；未改系统或用户应用字号。

实际截图已检查主页明暗、专注、提醒、AI、账号、回看、想法及大字体表单。360×800标准字号视口完整显示6条任务，首项起点约143dp。实现代理交叉检查与独立代码审查无未处理实质问题。

交付0.7.0开发测试APK与前后对比说明，保留原生像素。只保存本地提交，不推送/正式发布。业务、同步和真实模型配置不变。
