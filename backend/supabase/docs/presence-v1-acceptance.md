# 运行状态共享 v1：部署与验收

验收日期：2026-10-31。已部署迁移 [`20261031074438_pare_presence_v1.sql`](../migrations/20261031074438_pare_presence_v1.sql)。本记录仅覆盖独立运行状态通道，不扩大内容同步或应用界面的验收范围。

## 已验证结果

| 验证 | 结果与范围 |
| --- | --- |
| PostgreSQL 回滚测试 | [`presence_v1.sql`](../tests/presence_v1.sql) 通过；原有 [`sync_v1.sql`](../tests/sync_v1.sql) 回归重试后通过。测试使用随机虚构账号，以 `ROLLBACK` 清除数据和临时授权。 |
| 真实 HTTPS | **18 项检查通过**。两个独立 Swift Core HTTP client 和普通登录会话分别发送 Mac、Android 设备协议；另一个独立 QA 账号验证隔离。覆盖状态互见、等待暂停、刷新凭据、退出后拒绝请求、序号保护、清空状态及实际等待 90 秒后的过期。 |
| 适用边界 | 这是 Swift 双 client 的真实服务联调，**不是 Android 应用进程或 Mac/Android 双端 GUI 验收**。备用 Python 请求器在本机子进程传输阶段失败，未计入通过结果。 |
| 清理 | 普通 logout 后按精确随机账号标识、测试邮箱域及本轮标记删除两个 QA 账号。下列九项残留均为 **0**；临时凭据和清理清单文件已删除。 |

九项清理计数：`auth.users`、`auth.identities`、`auth.sessions`、`auth.refresh_tokens`、`pare_sync.presence_devices`、`pare_sync.streams`、`pare_sync.items`、`pare_sync.commits`、`pare_sync.receipts`。计数仅针对本轮 fixture；没有读取真实任务或真实账号凭据，没有发送邮件。

## 协议与权限边界

- 运行状态共享有独立开关，**默认关闭**；按账号和设备保存在本机，不能随同步 profile 传播。开启且已登录、内容同步已确认后，才按约 15 秒周期交换状态。关闭不暂停内容同步；暂停同步或退出账号也会停止状态共享。
- 请求只含设备/任务/运行标识、运行类别与状态、计时摘要；任务正文、密钥和 AI 原始历史不进入此通道。只能查看其他设备，不能远程接管、暂停或结束计时。
- 新表位于私有 `pare_sync` schema，启用 RLS 且无 policy，撤销客户端直接访问权限。新 RPC 仅授权 `authenticated`；固定空 `search_path` 与 UTC，检查现存用户及仍有效的 `auth.sessions`，所有读写按调用者归属过滤。本次迁移未改动既有 schema 权限。
- 服务端租约为 **90 秒**；客户端以单调时钟判断过期。每账号最多保留 32 台设备，每设备最多 32 项运行；返回最近 24 小时记录，过期状态必须明确标记。超过保留期清除设备行后，不再保留该行的历史序号水位。
- 按账号串行处理序号：更新的序号可写入；旧序号只返回当前回执；同序号同载荷重试不续期，同序号不同载荷拒绝。本机回执必须匹配 `accepted_sequence`，客户端持久推进服务端序号水位，防止恢复旧本机计数后长期无法更新。

## 安全 Advisor 记录

`rls_enabled_no_policy` 对应私有表的默认拒绝设计；`authenticated_security_definer_function_executable` 对应经过身份、会话和归属检查的 RPC。这两类提示按实际授权模型审阅后保留，不代表忽略其他安全问题。参见 [Supabase Advisors](https://supabase.com/docs/guides/observability/advisors)。

原有 **Leaked Password Protection 未开启**提示仍待处理，本轮未更改 Auth 密码策略。应按 [官方密码安全配置](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection) 检查并启用；该能力要求 Pro 或更高套餐，本轮未执行套餐升级。

## 显式复验

仅在获得测试授权后，预建两个随机虚构 QA 账号，使用脚本要求的测试邮箱格式，并把凭据写入权限 `0600` 的临时文件。项目配置必须来自被 Git 忽略的本地文件，不能使用真实用户、服务角色密钥，或触发注册邮件。

将 [`PresenceLiveCheck.swift`](../../../Scripts/PresenceLiveCheck.swift) 与当前 PareCore 编译后，使用占位参数替换为本轮明确准备的路径：

```sh
"<BUILD_DIR>/PresenceLiveCheck" \
  "<IGNORED_PROJECT_CONFIG>" \
  "<MODE_0600_QA_ACCOUNTS_JSON>" \
  "<EXPECTED_PROJECT_REF>" \
  --swift-peer
```

脚本负责退出测试会话；执行者仍须按精确 fixture 标识清理测试账号及临时文件，并再次核对九项计数。失败时保留受限清理清单直至确认清理完成，不把日志、密码、token、项目公钥或项目地址写入仓库。
