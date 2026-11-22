import SwiftUI
import PareCore

struct GoalPlanningView: View {
    @ObservedObject var store: Store
    @State private var title = ""
    @State private var editing: LongTermGoal?
    @State private var planning: LongTermGoal?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                PageHeading(title: "长期目标", subtitle: "明确想达成的结果，再关联具体行动。探索中的念头留在想法与项目中。")
                HStack {
                    TextField("写下一个长期目标", text: $title).textFieldStyle(.roundedBorder)
                        .onSubmit(addGoal)
                    Button("添加目标", action: addGoal)
                        .buttonStyle(AIPrimaryButtonStyle())
                        .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || title.count > 150)
                }
                ForEach(store.goals) { goal in goalCard(goal) }
                if store.goals.isEmpty {
                    Text("例如：这个月完成报告；每周留出时间阅读。先添加一个，再关联下一步任务。")
                        .font(Theme.font(12)).foregroundColor(Theme.textDim)
                }
                DisclosureGroup("最近分析状态（最多 20 次）") {
                    ForEach(store.planningRuns) { run in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(run.date.formatted(date: .abbreviated, time: .shortened)) · \(run.workflow.title)")
                                .font(Theme.font(11))
                            Text(run.phase.title).font(Theme.font(12, weight: .medium)).foregroundColor(Theme.amber)
                            Text(run.toolsUsed.joined(separator: " · "))
                                .font(Theme.font(10)).foregroundColor(Theme.textDim)
                            if let code = run.diagnosticCode {
                                Text("诊断代号：\(code)").font(Theme.font(10)).foregroundColor(Theme.textDim)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 7)
                    }
                    if store.planningRuns.isEmpty { Text("还没有分析记录。").font(Theme.font(12)) }
                }.padding(14).panel()
            }.padding(4)
        }
        .foregroundColor(Theme.text)
        .font(Theme.font(13)).tint(Theme.amber).buttonStyle(SecondaryActionStyle())
        .sheet(item: $editing) { goal in GoalEditorView(store: store, goal: goal) }
        .sheet(item: $planning) { goal in AIAssistantView(store: store, workflow: .plan, goal: goal) }
    }

    private func addGoal() {
        if store.addGoal(title) != nil { title = "" }
    }

    private func goalCard(_ goal: LongTermGoal) -> some View {
        let linked = (store.tasks + store.archive).filter { goal.taskIDs.contains($0.id) }
        let done = linked.filter(\.completed).count
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(goal.title).font(Theme.font(16, weight: .semibold))
                Spacer()
                InfoBadge(text: goal.status.title, accent: Theme.amber)
            }
            if !goal.motivation.isEmpty { Text(goal.motivation).font(Theme.font(13)).foregroundColor(Theme.textDim).lineSpacing(4) }
            if !linked.isEmpty {
                ProgressBar(fraction: Double(done) / Double(linked.count))
                    .accessibilityLabel("关联任务进度")
            }
            Text(linked.isEmpty ? "尚未关联任务" : "关联任务已完成 \(done) / \(linked.count)；目标是否达成由你确认")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if let date = goal.targetDate { Text("目标日期：\(date.formatted(date: .abbreviated, time: .omitted))").font(Theme.font(11)) }
            if Set(goal.taskIDs).subtracting(linked.map(\.id)).count > 0 {
                Text("部分关联任务已删除，进度可能不完整。").font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            HStack {
                Button("编辑 / 关联任务") { editing = goal }
                Button("生成目标推进建议") { planning = goal }
                    .buttonStyle(AIPrimaryButtonStyle())
                    .disabled(goal.status != .active || linked.allSatisfy(\.completed))
            }
        }.padding(14).panel(corner: 12, accent: Theme.amber)
    }
}

private struct GoalEditorView: View {
    @ObservedObject var store: Store
    @State var goal: LongTermGoal
    @State private var newStep = ""
    @State private var hasDate: Bool
    @State private var targetDate: Date
    @Environment(\.dismiss) private var dismiss

    init(store: Store, goal: LongTermGoal) {
        self.store = store
        _goal = State(initialValue: goal)
        _hasDate = State(initialValue: goal.targetDate != nil)
        _targetDate = State(initialValue: goal.targetDate ?? Date())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("长期目标").font(Theme.font(18, weight: .semibold))
            TextField("目标", text: $goal.title)
            TextField("为什么想做 / 怎样算达成（可选）", text: $goal.motivation, axis: .vertical).lineLimit(2...4)
            Picker("状态", selection: $goal.status) {
                ForEach(GoalStatus.allCases, id: \.self) { Text($0.title).tag($0) }
            }
            Toggle("设置目标日期", isOn: $hasDate)
            if hasDate { DatePicker("目标日期", selection: $targetDate, displayedComponents: .date) }
            Text("关联任务（含已归档任务，最多 100 条）").font(Theme.font(12, weight: .medium))
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(store.tasks + store.archive) { task in
                        Toggle(task.text + (task.completed ? " · 已完成" : ""), isOn: Binding(
                            get: { goal.taskIDs.contains(task.id) }, set: { value in
                                if value { goal.taskIDs.append(task.id) }
                                else { goal.taskIDs.removeAll { $0 == task.id } }
                            }))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.frame(maxHeight: 180)
            TextField("新增一个下一步任务（保存时创建并关联）", text: $newStep)
            HStack {
                Button("取消") { dismiss() }
                Spacer()
                Button("保存") {
                    goal.targetDate = hasDate ? targetDate : nil
                    if let task = store.addTask(newStep, to: .importantNotUrgent) { goal.taskIDs.append(task.id) }
                    store.updateGoal(goal); dismiss()
                }.disabled(goal.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                           || goal.title.count > 150 || goal.motivation.count > 600 || newStep.count > 200
                           || goal.taskIDs.count + (newStep.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0 : 1) > 100)
            }
        }
        .textFieldStyle(.roundedBorder).font(Theme.font(12)).foregroundColor(Theme.text)
        .padding(18).frame(width: 500).background(Theme.bg)
    }
}

struct AgentPreferencesView: View {
    @ObservedObject var store: Store
    @State private var preferences: AgentPreferences
    @State private var saved = false

    init(store: Store) {
        self.store = store
        _preferences = State(initialValue: store.agentPreferences)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Stepper("默认每次规划 \(preferences.planningMinutes) 分钟", value: $preferences.planningMinutes, in: 5...480, step: 5)
            Stepper("希望从 \(preferences.startingMinutes) 分钟的小步骤开始", value: $preferences.startingMinutes, in: 1...15)
            TextField("其他偏好，例如先给一个选择、任务尽量拆小", text: $preferences.notes, axis: .vertical)
                .textFieldStyle(.roundedBorder).lineLimit(2...4)
            Text("偏好由你填写，不自动推断。每次分析仍可以调整预算；这里不会修改现有提醒设置。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            Button(saved ? "已保存" : "保存偏好") { store.updateAgentPreferences(preferences); saved = true }
                .disabled(preferences.notes.count > 600)
        }.font(Theme.font(12)).foregroundColor(Theme.text)
        .onChange(of: preferences) { _ in saved = false }
    }
}

struct AISummaryFeedbackView: View {
    @ObservedObject var store: Store
    let record: AISummaryRecord
    @State private var comment: String

    init(store: Store, record: AISummaryRecord) {
        self.store = store; self.record = record
        _comment = State(initialValue: record.feedback?.comment ?? "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("反馈原因（可选，300 字内）", text: $comment).textFieldStyle(.roundedBorder)
            HStack {
                Button(record.feedback?.helpful == true ? "✓ 有帮助" : "有帮助") { save(true) }
                Button(record.feedback?.helpful == false ? "✓ 不适合" : "不适合") { save(false) }
                if record.feedback != nil { Button("清除反馈") { store.setAISummaryFeedback(record.id, feedback: nil) } }
            }.disabled(comment.count > 300)
        }.font(Theme.font(11))
    }
    private func save(_ helpful: Bool) {
        store.setAISummaryFeedback(record.id, feedback: AIUserFeedback(helpful: helpful, comment: comment))
    }
}
