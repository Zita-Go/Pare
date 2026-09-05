import SwiftUI
import PareCore

struct AIHistorySettingsView: View {
    @ObservedObject var store: Store
    @State private var background: String
    @State private var saved = false
    @State private var deleting: UUID?

    init(store: Store) {
        self.store = store
        _background = State(initialValue: store.aiBackground)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("长期背景与手写总结")
                .font(Theme.font(13, weight: .medium))
            Text("记录目标、偏好或你确认的经验，例如：本月先完成报告；晚上不排复杂任务。它只保存在本地，发送时另行勾选。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            TextEditor(text: $background)
                .font(Theme.font(12)).scrollContentBackground(.hidden)
                .frame(height: 90).padding(6)
                .background(Theme.panel.opacity(0.6))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Theme.border))
                .accessibilityLabel("长期背景与手写总结")
                .onChange(of: background) { _ in saved = false }
            HStack {
                Text("\(background.count)/1500 字").font(Theme.font(10)).foregroundColor(Theme.textDim)
                Spacer()
                Button(saved ? "已保存" : "保存背景") {
                    store.setAIBackground(background); saved = true
                }.disabled(background.count > 1500)
            }
            Text("有效 AI 建议的摘要会自动保存在本地，最多 50 条，并记录采纳状态。旧版没有保存的 AI 回复无法补回。摘要和背景会包含在你导出的任务备份中。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            DisclosureGroup("本地 AI 总结（\(store.aiSummaries.count) 条）") {
                ForEach(store.aiSummaries) { record in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text("\(record.date.formatted(date: .abbreviated, time: .shortened)) · \(record.workflow.title)")
                            Spacer()
                            Button("删除") { deleting = record.id }
                        }.font(Theme.font(10))
                        Text(record.summary).font(Theme.font(11)).textSelection(.enabled)
                        Text(record.outcome.title).font(Theme.font(10)).foregroundColor(Theme.textDim)
                        AISummaryFeedbackView(store: store, record: record)
                        Divider()
                    }.padding(.vertical, 5)
                }
                if store.aiSummaries.isEmpty {
                    Text("还没有保存的 AI 总结。").font(Theme.font(11)).foregroundColor(Theme.textDim)
                }
            }
        }
        .foregroundColor(Theme.text)
        .confirmationDialog("删除这条本地 AI 总结？原始任务和进展记录会保留。",
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("删除总结", role: .destructive) {
                if let deleting { store.deleteAISummary(deleting) }
                deleting = nil
            }
        }
    }
}
