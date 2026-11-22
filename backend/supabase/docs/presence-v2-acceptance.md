# Windows 设备状态兼容验收

2026-11-22 已部署 [presence v2 迁移](../migrations/20261122123824_pare_presence_v2_windows.sql)。`apply_migration` 返回成功，服务端迁移历史编号为 `20261122123824`；本地迁移文件由CLI真实生成后对齐该编号，SQL正文未改变。

## 兼容行为

- 新 `pare_presence_exchange_v2` 接受 Mac、Android、Windows，返回三种设备；响应包、租约和序列规则仍为版本1。
- 旧 `pare_presence_exchange` 维持 Mac/Android 输入限制，并过滤Windows设备响应。已安装的旧客户端不会因为不认识Windows标签而整包解析失败。
- 新客户端使用v2。旧客户端的任务内容同步照常工作，但升级前不会显示Windows运行状态。
- 函数身份与有效会话校验、按owner隔离、空search_path、UTC、每账号锁、数量/大小限制、90秒租约和直接表访问拒绝保持原样。

## 已完成验证

| 项目 | 结果 |
| --- | --- |
| 部署前v1基线 | 全部数据库断言执行成功，fixture事务回滚 |
| 部署后v2 | [完整回滚测试](../tests/presence_v2.sql)返回 `presence_v2 assertions passed and fixture transaction rolled back`；覆盖三端互见、旧接口过滤、账户隔离和非法标签等 |
| 部署后v1兼容 | [原回滚测试](../tests/presence_v1.sql)由根代理重新执行成功；临时增加15秒statement timeout及3秒lock timeout，SQL正文断言不变 |
| 遗留操作与清理 | 复核无在运行的本轮fixture查询，随机UUID邮箱 `@pare.invalid` 测试账号计数为0；测试数据及临时权限变更均由ROLLBACK清除 |
| 客户端 | Mac360项、Android389项回归通过，包括Windows标签校验和新RPC路径 |

一次部署后v1工具调用长时间未返回后被中断，未将那次调用计为通过；以上兼容结果来自后续有明确成功响应的独立复验。

## 权限审阅

部署后Security Advisor仍报告私有表RLS无policy（默认拒绝设计）以及authenticated可执行SECURITY DEFINER函数。已检查v2与原RPC相同的显式ACL、现存用户/会话和owner过滤，未放开原始表权限。原有Leaked Password Protection未开启提示仍存在，本轮未修改Auth套餐或密码策略；详见[原验收说明](presence-v1-acceptance.md#安全-advisor-记录)。

本记录是数据库协议及客户端代码回归验收，不是Mac、Android与Windows三台设备的GUI联调。未读取真实任务、密钥或模型历史，未发送注册邮件。
