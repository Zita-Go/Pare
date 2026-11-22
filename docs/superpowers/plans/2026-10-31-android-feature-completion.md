# Pare Feature Completion Implementation Plan

**Goal:** 完成目标/偏好、资料管理、多工作流AI、跨日期日程和跨设备状态协作的可用流程。
**Spec:** `docs/superpowers/specs/2026-10-31-android-feature-completion.md`。
**Architecture:** 新领域/界面模块通过现有原子编辑通道写入；运行状态采用独立私有表与版本化RPC，保持内容流兼容。

- [x] Goals/Preferences领域与工作区、Mac字段兼容、草稿/冲突/分组限制测试。
- [x] Library/IdeaManagement/TaskMetadata及持久LocalTrash，搜索、归档、删除恢复全路径。
- [x] PlanningAI四流程、限定上下文、预算/旧安排保护、采纳与撤销、假服务验收。
- [x] Timeline跨日拖动与日期表单、目标冲突、DST、恢复与真实手势测试。
- [x] 独立presence RPC、数据库权限和回滚验证、Android/Mac客户端与开始前提示。
- [x] Main/VM入口与运行时整合，避免增加常驻菜单层级，补齐回看统计/复制等剩余流程。
- [x] 全套构建/领域与设备测试、Mac测试、截图、覆盖清单、安装包和本地提交。

基线8126097；208项Android单元测试与17条设备流程。代理按文件所有权并行；Gradle串行。用户授权实施，重复选型与许可问题不作为开发阻塞。真实云端修改仅为经过审查的新增私有状态表/RPC，不改既有任务内容。

验收：Android 324项单元测试、29条不同界面流程（日期语义定位修正后5条主界面流程重跑）；Mac 343项测试及Release构建；新旧Kotlin载荷Mac解码通过；云端SQL回滚测试与18项真实Swift双会话HTTP检查通过。临时账号九类数据清理为0。Android实体手机联网、生产签名与应用商店发布不在已验收范围。详见docs/releases/android-v0.9.0.md。
