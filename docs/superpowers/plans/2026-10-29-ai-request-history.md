# AI Request History Implementation Plan

**Goal:** 按已确认规格补齐可核对的本地AI输入输出。
**Spec:** `docs/superpowers/specs/2026-10-29-ai-request-history.md`
**Architecture:** PareCore负责记录模型、脱敏和独立有界存储；两个HTTP客户端发出真实传输快照事件；App历史controller和UI负责保存状态、查看/复制/删除。

- [x] history-core agent：新AIRequestHistory.swift与测试；持久化/重启/清理/删除/脱敏/坏文件与权限。
- [x] transport agent：新AIRequestTrace.swift，AIClient/IntelligenceClient真实request/response事件，默认无回调兼容旧调用；测试成功、HTTP失败、格式失败、取消、超限。
- [x] root：App历史controller、AIService接入两条流程、当前请求ID、详情/列表入口、修正动态预览命名。
- [x] root：独立复核、完整测试/构建、隔离UI成功/失败/删除验收；保留当前旧版可见结果后备份更新本机，核对任务数据不变。

## 共享合同

`AIRequestRecord`：id UUID、startedAt Date、finishedAt Date?、kind String、model String、endpoint String、requestBody String、responseBody String?、httpStatus Int?、outcome AIRequestOutcome、errorMessage String?、requestRedacted Bool、responseRedacted Bool、responseTruncated Bool；以上public var，public init。outcome为running/succeeded/failed/cancelled/interrupted。

`AITraceRedactor.redact(_ text: String, secrets: [String] = []) -> String`。
`AIRequestHistoryArchive` actor：`init(directory: URL, maxRecords: Int = 100, maxBytes: Int = 20*1024*1024)`，`load(recoverInterrupted: Bool = true) throws -> [AIRequestRecord]`，`upsert(_ record: AIRequestRecord) throws`，`remove(_ id: UUID) throws`，`removeAll() throws`。不接收/保留secret。存储边界再次通用脱敏，实际key由App在调用边界前脱敏。磁盘操作在actor执行，不阻塞主线程。

`AIRequestTraceEvent` Sendable：`.request(body: String, endpoint: String)`、`.response(body: String, statusCode: Int?, truncated: Bool)`；`AIRequestTraceHandler = @Sendable (AIRequestTraceEvent) async -> Void`。两个client现有generate新增默认nil的`trace: AIRequestTraceHandler?`参数；事件来自同一实际URLRequest/响应，网络前await request，解码前await response，错误/取消部分响应也能记录。

## 验证重点

- 真正发送的body与历史快照一致，不混入下一轮summary或新UUID。
- 原文仅脱敏/截断，不伪装成未经修改的完整字节；错误响应不绕过原校验。
- API key不会进入磁盘/复制/错误信息，history不会被Store.prepareIntelligenceRequest纳入。
- 删除与进行中写入不会让删掉的记录复活；存储失败显示warning且当前建议可继续查看。
- 原始记录不被任务备份包含，不改用户服务配置或模型。

## 验证结果

- 最终完整268项测试通过，0失败；包含18项归档/隐私测试与10项真实传输快照测试。release构建、Info.plist、签名和diff检查通过。
- 独立复核发现JSON Unicode转义与未闭合凭据字段两项脱敏缺口；已先复现失败、修复并独立复验。嵌套编码有深度上限，超限保守遮蔽，不把可解码凭据写入磁盘或复制内容。
- 隔离UI实测成功记录、原始正文、固定编号、后续HTTP503保留原建议并单独关联失败记录、单条删除和列表更新；最终构建检查JSON缩进显示。磁盘确认目录700、文件600，实际请求body含system且response.requestID与记录ID一致。
- 只使用本机假服务测试，不发送真实用户记录。真实服务配置仍为用户选择的模型，未修改钥匙串。
- 本机更新2.12.0 build39，更新前后任务JSON逐项相等。旧应用/数据及明确标记的旧版可见回复摘录备份在 `/private/tmp/pare-2.12.0-upgrade.yuPpOO`。旧原始报文不伪造补回。
- 新记录独立保存在Application Support/Pare/AIRequestHistory；不纳入Store上下文检索。尚未发布远端。
