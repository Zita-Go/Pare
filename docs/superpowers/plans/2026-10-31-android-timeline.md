# Android Timeline Implementation Plan

**Goal:** 让今天可直接安排时间、拖动任务和调整时长，同时减少不直观入口。
**Spec:** `docs/superpowers/specs/2026-10-31-android-timeline-design.md`。
**Architecture:** 时间轴投影/领域动作、Compose日程/表单、主页/任务详情接线分别实现，复用既有day_plan字段及原子同步操作组。

- [x] TimelineDomain/TimelineActions：日期、时区、DST、跨午夜、原子创建、陈旧保护、排序，领域测试。
- [x] TodayTimeline/TimelineScheduleDialog：比例网格、点击、排序/安排/移动/缩放、滚动、可保留草稿。
- [x] Main/VM/TaskEditor/VisualComponents：今天入口、三项菜单、集中任务工具、保存反馈。
- [x] 明确等待结果文案，功能覆盖差距文档；保留现有等待与专注逻辑。
- [x] 更新旧导航用例，新增真实拖拽验收与截图；Mac解码、构建、lint及独立审查。
- [x] 0.8开发测试包、本地安装、文档与本地提交；不推送或发布。

基线031dd94，171项单元测试及11条设备流程。实现不访问真实任务/服务密钥，QA使用独立包、虚构资料。Gradle串行使用。

## 验收记录

208项单元测试、17条模拟器流程通过，0失败/跳过。时间轴使用真实触摸事件测试松手提交、移动/resize、排序、拖入、撤销、部分裁剪坐标、未来日快速录入、重点/提醒权限。短事项向上缩短另定向复验，避免5分钟反变15分钟。Mac解码器接受实际测试输出的虚构task/day_plan载荷。

独立审查发现并修复：迁移undo的空计划不能写删除标记；裁剪行不能用裁剪原点计算落点；时间轴提醒需权限反馈；保留重点与投入摘要。兼容迁移清理旧noncanonical副本，保护目标/peer变化，组规模100成功101拒绝不落盘。

保留真实功能缺口清单。仅本地0.8测试包与提交，未推送/正式发布，不改真实Mac数据或调用真实模型。
