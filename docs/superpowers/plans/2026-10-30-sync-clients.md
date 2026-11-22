# Android 与 Mac 同步客户端执行记录

Spec: `docs/superpowers/specs/2026-10-29-android-sync-design.md`

用户于 2026-10-30 明确同意 Google Android SDK 许可及 JDK、SDK、构建组件安装。沿用 `codex/android-sync` 开发分支；不安装或重启正式 Mac App，不上传真实任务，不发布远端。

## 分工与接口

1. Android：安装工具链，创建 Kotlin/Compose 客户端，离线存储、账号、任务/想法与同步；输出可编译 APK。
2. Mac 数据：schema 7、日期计划、数据投影、同文件原子同步队列、远端合并及显式冲突处理。
3. HTTP：Supabase Auth 与 RPC，有限响应、禁止跨站重定向携带凭据、可读且不泄露数据的错误。
4. 集成：Mac 设置入口、钥匙串会话、同步协调器、首次合并预览、构建配置与端到端验收。

共同约束：客户端只使用公开客户端键和用户会话；实际配置使用忽略文件，不写入 Git。云端权限已验证，客户端上线仍需要额外验收。数据与队列必须一起持久化，不能丢失响应后生成新操作 ID。退出账号不丢弃待同步内容，跨账号不得复用队列。

共享载荷：inbox/idea/goal 使用 `{content: model}`；task 使用 `{content: model, lifecycle: active|archived}`。模型字段沿用 Swift camelCase，UUID 小写，时间 ISO8601。day_plan 使用 `{content: SyncDayPlan}`，字段 snake_case。设备计时、API 配置、AI 原始请求不进入同步。

Ruling: 使用现有开发分支并按独立目录并行实施，无需创建第二个工作副本。Swift 核心、HTTP、Android 的文件归属分开，集成后统一构建与审查。

## 验收记录

- [x] 开发环境与 Android APK 构建：JDK17、SDK36、AGP8.13.2、Gradle8.13；Android 8.0+。
- [x] Mac 迁移、隐私过滤、跨日计划、重试、冲突与持久化测试；完整322项测试通过。
- [x] HTTP 认证与错误保护测试：14项，含重定向拒绝与有限响应。
- [x] Kotlin 客户端与实际云端虚构数据往返，原始载荷接入 Swift Store；没有访问正式资料库。
- [x] Mac 同步表单登录/注册切换、同步范围折叠界面已通过隔离预览检查。
- [x] Android 29项测试通过；lint 0错误/10项非阻断建议，APK签名有效，API36 ARM64模拟器安装成功。
- [ ] Android 点击与截图：CUA不能绑定官方模拟器的独立QEMU窗口；已停止模拟器，未用其他UI自动化绕过工具限制。真实手机通知与省电策略未验收。
- [x] 独立代码审查：修复大队列分批、事务整体冲突、过时冲突回执、计时中任务保护、恢复后暂停入口、跨时区计划、SQLite大内容读取与载荷校验。

Ruling: 保留既有完成任务跨日归档体验；归档沿用同一任务UUID并经过服务端CAS，不清空已存的旧日期计划。
Ruling: Android首个开发预览先交付任务/想法/计划同步闭环，移动端专注、提醒、AI不冒充已实现。源码说明与交付说明明确列出。
Ruling: 同组冲突先逐项选择再原子应用；选择期间内容又变化时清除该组未完成选择，避免覆盖刚产生的修改。

安装后的真实用户登录、手机通知省电限制及正式发布不等同于开发构建验证。
