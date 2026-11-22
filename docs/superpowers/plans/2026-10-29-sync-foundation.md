# Hosted Sync Foundation Implementation Plan

**Goal:** 准备可验证的跨端同步协议和云端迁移；不在协议未验收时修改正式App数据。
**Spec:** `docs/superpowers/specs/2026-10-29-android-sync-design.md`
**Status:** Supabase账号授权与工具已恢复；已在用户指定组织创建0美元/月Free开发项目pare-dev（新加坡），部署同步v1并通过实际PostgreSQL回滚验收。仍未接入正式App数据。

## Contract v1

- JSON字段snake_case，protocol_version=1，payload_version=1。实体kind为inbox/task/idea/goal/day_plan/profile；id为稳定ASCII字符串，长度1–160，仅允许字母/数字及`_:@+./-`（涵盖UUID、日期与IANA时区）。
- push RPC `pare_sync_apply(p_operation_id UUID, p_device_id UUID, p_mutations JSONB)`。每条变更含kind/id/base_revision（0表示期望不存在）/payload_version/deleted/payload（object或删除时null）。单批1–100条、序列化变更≤1MiB，不允许重复实体键。
- apply返回 `{protocol_version,status,sequence,entities,conflicts}`，status为applied/conflict。实体含kind/id/revision/payload_version/payload/deleted_at/last_device_id/sequence；conflict含kind/id/current（可null）。成功提交中所有实体使用同一个sequence，每条revision递增。
- pull RPC `pare_sync_pull(p_after BIGINT=0,p_limit INT=50)`按完整提交分页，返回 `{protocol_version,cursor,has_more,commits}`；commit含sequence/operation_id/device_id/entities。不能在一个事务中途推进游标。
- 同用户事务通过stream行锁串行，operation_id幂等；同ID不同载荷拒绝。冲突不部分修改实体。删除保留tombstone。
- RPC使用auth.uid获取owner；匿名不允许执行，表在私有schema、启用RLS、客户端无直接写权限。服务端不读取OS文件、不执行系统命令。

## Tasks

- [x] backend agent：`backend/supabase/migrations/20261029161731_pare_sync_v1.sql`和回滚式SQL验收脚本；版本号与实际云端迁移记录一致。
- [x] root：`Sources/PareCore/SyncProtocol.swift`、协议/初始DayPlan验证测试，跨日计划独立于本机翻篇。
- [x] root：运行Swift回归、只读复核SQL权限与事务。
- [x] 读取组织/费用/项目，创建0美元开发项目、检查既有表、迁移与RLS实测，仅虚构数据。
- [x] schema6迁移与原子outbox、Mac账号/冲突UI、Android构建环境和客户端已在2026-10-30开发预览实现；见同目录 `2026-10-30-sync-clients.md`。
- [ ] 真机双端验收与正式发布；开发构建不等于完整移动功能或所有机型已验证。

验证：协议测试10项通过；完整Swift测试278项通过。首次全套运行停在既有模拟服务的Process.waitUntilExit清理阶段，清理该测试进程后终端模式复跑通过。未修改正式App或用户任务。实际SQL验收测试全部回滚，数据和测试账号为0；匿名URLSession请求按预期401拒绝。

权限审查保留两类与设计一致的advisor提示，详见backend/supabase/README.md。后续认证HTTP、Swift/Kotlin互通与持久化测试已完成；独立数据库会话压力并发和真机体验仍待验证。用户2026-10-30同意Android SDK许可与安装，开发环境已安装。
