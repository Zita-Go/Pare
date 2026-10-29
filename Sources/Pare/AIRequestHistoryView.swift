import AppKit
import SwiftUI
import PareCore

struct AIRequestHistoryEntry: View {
    var selectedID: UUID? = nil
    var title = "请求历史"
    @State private var presented = false

    var body: some View {
        Button { presented = true } label: { Label(title, systemImage: "clock.arrow.circlepath") }
            .buttonStyle(TertiaryActionStyle())
            .sheet(isPresented: $presented) { AIRequestHistoryView(selectedID: selectedID) }
    }
}

struct AIRequestHistoryView: View {
    @ObservedObject private var history = AIRequestHistoryController.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selectedID: UUID?
    @State private var confirmDelete = false
    @State private var confirmClear = false
    @State private var deleting = false

    init(selectedID: UUID? = nil) { _selectedID = State(initialValue: selectedID) }

    private var selected: AIRequestRecord? { history.records.first { $0.id == selectedID } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                if selectedID != nil {
                    Button { selectedID = nil } label: { Label("全部记录", systemImage: "chevron.left") }
                        .buttonStyle(TertiaryActionStyle())
                }
                Text(selectedID == nil ? "AI 请求历史" : "本次请求详情")
                    .font(Theme.Typography.title)
                Spacer()
                Button { dismiss() } label: { Image(systemName: "xmark") }
                    .buttonStyle(IconActionStyle()).accessibilityLabel("关闭请求历史")
            }.padding(18)
            Rectangle().fill(Theme.separator).frame(height: 1)
            if let issue = history.persistenceIssue {
                FeedbackBanner(text: issue, kind: .warning).padding(.horizontal, 18).padding(.top, 12)
            }
            if !history.loaded {
                ProgressView("读取本地记录…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let selected {
                AIRequestRecordDetail(record: selected).id(selected.id)
            } else if selectedID != nil {
                VStack(spacing: 10) {
                    Image(systemName: "doc.text.magnifyingglass").font(.system(size: 24))
                    Text("没有这次请求的记录")
                    Text("请求可能尚未发出，或记录已删除、超过保留上限。旧版本的原始回复无法补回。")
                        .font(Theme.font(12)).foregroundColor(Theme.textDim).multilineTextAlignment(.center)
                }.padding(30).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 9) {
                        if history.records.isEmpty {
                            VStack(spacing: 10) {
                                Image(systemName: "clock.arrow.circlepath").font(.system(size: 24))
                                Text("还没有请求记录")
                                Text("从本版本起，发出的 AI 请求会记录在这里。旧版本的原始回复无法补回。")
                                    .font(Theme.font(12)).foregroundColor(Theme.textDim)
                            }.padding(30).frame(maxWidth: .infinity)
                        }
                        ForEach(history.records) { record in
                            Button { selectedID = record.id } label: {
                                HStack(alignment: .top, spacing: 12) {
                                    Image(systemName: record.outcome.symbol)
                                        .foregroundColor(record.outcome.tint).frame(width: 18)
                                    VStack(alignment: .leading, spacing: 5) {
                                        HStack {
                                            Text(record.kind).font(Theme.font(14, weight: .medium))
                                            Spacer()
                                            Text(record.outcome.label).foregroundColor(record.outcome.tint)
                                        }
                                        Text("\(record.model) · \(record.startedAt.formatted(date: .abbreviated, time: .standard))")
                                            .font(Theme.font(11)).foregroundColor(Theme.textDim)
                                        if let error = record.errorMessage {
                                            Text(error).font(Theme.font(11)).foregroundColor(Theme.textDim).lineLimit(2)
                                        }
                                    }
                                    Image(systemName: "chevron.right").foregroundColor(Theme.textFaint)
                                }.padding(13).frame(maxWidth: .infinity, alignment: .leading).panel()
                            }.buttonStyle(.plain)
                                .accessibilityLabel("查看\(record.kind)，\(record.model)，\(record.outcome.label)，\(record.startedAt.formatted())")
                        }
                    }.padding(18)
                }
            }
            Rectangle().fill(Theme.separator).frame(height: 1)
            HStack(alignment: .center, spacing: 12) {
                Text("仅存本机 · 最近100次 / 约20MB\n不自动作为 AI 参考，也不进入任务备份。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if selected != nil {
                    Button("删除这条记录", role: .destructive) { confirmDelete = true }
                        .buttonStyle(TertiaryActionStyle(tint: Theme.error)).disabled(deleting)
                } else if selectedID == nil, !history.records.isEmpty || history.persistenceIssue != nil {
                    Button("清空历史", role: .destructive) { confirmClear = true }
                        .buttonStyle(TertiaryActionStyle(tint: Theme.error)).disabled(deleting)
                }
                Button("关闭") { dismiss() }.buttonStyle(SecondaryActionStyle()).keyboardShortcut(.cancelAction)
            }.padding(18)
        }
        .frame(width: 720, height: 620).background(Theme.bg).foregroundColor(Theme.text)
        .task { await history.ensureLoaded() }
        .alert("删除这条请求记录？", isPresented: $confirmDelete) {
            Button("取消", role: .cancel) {}
            Button("删除记录", role: .destructive) {
                guard let id = selectedID else { return }
                deleting = true
                Task { @MainActor in
                    if await history.remove(id) { selectedID = nil }
                    deleting = false
                }
            }
        } message: {
            Text("只删除本地输入与回复记录，不改变已采纳的任务、想法或反馈。删除不可撤销。")
        }
        .alert("清空所有请求历史？", isPresented: $confirmClear) {
            Button("取消", role: .cancel) {}
            Button("清空历史", role: .destructive) {
                deleting = true
                Task { @MainActor in _ = await history.removeAll(); deleting = false }
            }
        } message: {
            Text("删除本机保存的输入与回复记录，不改变任务、想法、偏好或反馈。删除不可撤销。")
        }
    }
}

private struct AIRequestRecordDetail: View {
    let record: AIRequestRecord
    @State private var copied: String?
    @State private var showReply = true

    private var modelReply: String? {
        guard let body = record.responseBody, let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = object["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any] else { return nil }
        return message["content"] as? String
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(record.kind).font(Theme.font(17, weight: .medium))
                        Text(record.model).font(Theme.font(12)).textSelection(.enabled)
                        Text(record.startedAt.formatted(date: .abbreviated, time: .standard))
                            .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 5) {
                        Label(record.outcome.label, systemImage: record.outcome.symbol).foregroundColor(record.outcome.tint)
                        if let status = record.httpStatus { Text("HTTP \(status)").font(Theme.font(11)) }
                        if let end = record.finishedAt {
                            Text(String(format: "%.1f 秒", max(0, end.timeIntervalSince(record.startedAt))))
                                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                        }
                    }
                }
                Text(record.endpoint).font(Theme.font(11)).foregroundColor(Theme.textDim)
                    .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                Text("请求编号：\(record.id.uuidString)").font(Theme.font(10)).foregroundColor(Theme.textFaint)
                    .textSelection(.enabled)
                if let error = record.errorMessage { FeedbackBanner(text: error, kind: .error) }
                if record.requestRedacted || record.responseRedacted {
                    FeedbackBanner(text: "检测到的凭据已脱敏；下方不是包含密钥的逐字副本。", kind: .info)
                }
                if record.responseTruncated {
                    FeedbackBanner(text: "响应超过记录上限，或传输未完整结束；以下只保留已收到的有限内容。", kind: .warning)
                }
                DisclosureGroup("模型回复", isExpanded: $showReply) {
                    if let modelReply, !modelReply.isEmpty { contentBlock(modelReply, name: "模型回复") }
                    else {
                        Text(record.responseBody == nil ? "尚未收到响应正文。" : "响应没有可提取的模型正文，请查看下方原始响应。")
                            .font(Theme.font(12)).foregroundColor(Theme.textDim).padding(.top, 8)
                    }
                }
                DisclosureGroup("实际发送内容 · 含系统提示词与输入数据") {
                    contentBlock(record.requestBody, name: "实际发送内容")
                }
                DisclosureGroup("原始响应 · 含模型正文与服务元数据") {
                    if let response = record.responseBody { contentBlock(response, name: "原始响应") }
                    else { Text("没有收到可保存的响应正文。").font(Theme.font(12)).foregroundColor(Theme.textDim).padding(.top, 8) }
                }
                Text("这里展示发出前固定的请求内容与收到的响应，不随下一轮变化。取消或网络失败时，服务可能未收到请求；未返回的字段不会补造。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
                if let copied { Label("已复制\(copied)", systemImage: "checkmark.circle").font(Theme.font(11)).foregroundColor(Theme.success) }
            }.font(Theme.font(13)).padding(18)
        }
    }

    private func contentBlock(_ text: String, name: String) -> some View {
        let formatted = formattedJSON(text)
        return VStack(alignment: .leading, spacing: 9) {
            HStack {
                Spacer()
                Button { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string); copied = name } label: {
                    Label("复制\(name)", systemImage: "doc.on.doc")
                }.buttonStyle(TertiaryActionStyle())
            }
            if formatted != nil {
                Text("JSON 已缩进显示；复制保留脱敏原文。")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
            }
            Text(formatted ?? text).font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }.padding(12).panel().padding(.top, 8)
    }

    private func formattedJSON(_ text: String) -> String? {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]) else { return nil }
        return String(decoding: pretty, as: UTF8.self)
    }
}

private extension AIRequestOutcome {
    var label: String {
        switch self {
        case .running: return "请求中"
        case .succeeded: return "成功 · 已校验"
        case .failed: return "失败"
        case .cancelled: return "已取消"
        case .interrupted: return "上次中断"
        }
    }
    var symbol: String {
        switch self {
        case .running: return "arrow.triangle.2.circlepath"
        case .succeeded: return "checkmark.circle"
        case .failed: return "exclamationmark.circle"
        case .cancelled, .interrupted: return "pause.circle"
        }
    }
    var tint: Color {
        switch self {
        case .succeeded: return Theme.success
        case .failed: return Theme.error
        case .running: return Theme.info
        case .cancelled, .interrupted: return Theme.textDim
        }
    }
}
