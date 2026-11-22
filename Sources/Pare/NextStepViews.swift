import SwiftUI
import PareCore

struct NextStepDraftBaseline {
    var action: String?
    var criterion: String?
    var lastCompletedID: UUID?
    init(_ task: TaskItem) { action = task.nextAction; criterion = task.definitionOfDone; lastCompletedID = task.completedSteps?.last?.id }
    func matches(_ task: TaskItem) -> Bool {
        Self.clean(action) == Self.clean(task.nextAction) && Self.clean(criterion) == Self.clean(task.definitionOfDone) && lastCompletedID == task.completedSteps?.last?.id
    }
    static func clean(_ value: String?) -> String { value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }
}

/// Completion is persisted independently from the surrounding inspector draft.
/// Bindings are cleared only after successful persistence, so a later inspector
/// Save cannot accidentally resurrect the step that was just completed.
struct NextStepCompletionControls: View {
    @ObservedObject var store: Store
    let taskID: UUID
    @Binding var nextAction: String
    @Binding var definitionOfDone: String
    let expectedCompletedStepID: UUID?
    var onProgress: () -> Void = {}
    @State private var completedID: UUID?
    @State private var error: String?

    private var current: TaskItem? { store.tasks.first { $0.id == taskID } }
    private var savedDraft: Bool {
        guard let task = current else { return false }
        return task.completedSteps?.last?.id == expectedCompletedStepID
            && NextStepDraftBaseline.clean(task.nextAction) == NextStepDraftBaseline.clean(nextAction)
            && NextStepDraftBaseline.clean(task.definitionOfDone) == NextStepDraftBaseline.clean(definitionOfDone)
    }
    private var hasAction: Bool { !NextStepDraftBaseline.clean(nextAction).isEmpty }
    private var canUndo: Bool {
        guard let task = current, let completedID else { return false }
        return !task.completed && task.completedSteps?.last?.id == completedID
            && NextStepDraftBaseline.clean(nextAction).isEmpty
            && NextStepDraftBaseline.clean(definitionOfDone).isEmpty
            && NextStepDraftBaseline.clean(task.nextAction).isEmpty
            && NextStepDraftBaseline.clean(task.definitionOfDone).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if hasAction && current?.completed == false {
                Button(action: complete) {
                    Label("完成这一步", systemImage: "checkmark.circle")
                }
                .buttonStyle(SecondaryActionStyle())
                .disabled(!savedDraft)
                .accessibilityHint("记录这一步并继续填写新的下一步，不完成整个任务")
                Text(savedDraft ? "只完成这一步，整个任务仍保留。" : "下一步有未保存的修改，请先保存。")
                    .font(Theme.Typography.secondary).foregroundColor(Theme.textDim)
            }
            if let completedID, current?.completedSteps?.contains(where: { $0.id == completedID }) == true {
                HStack {
                    Label("已完成一步，可以继续写下一步", systemImage: "checkmark.circle.fill")
                        .foregroundColor(Theme.success)
                    if canUndo {
                        Button("撤销") { undo() }.buttonStyle(TertiaryActionStyle())
                    }
                }.font(Theme.Typography.secondary)
            }
            if let error { Text(error).font(Theme.Typography.secondary).foregroundColor(Theme.qUrgent) }
            if let history = current?.completedSteps, !history.isEmpty {
                DisclosureGroup("已完成的步骤 · \(history.count)") {
                    ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(history.reversed())) { step in
                            VStack(alignment: .leading, spacing: 3) {
                                Label(step.text, systemImage: "checkmark")
                                    .foregroundColor(Theme.text).textSelection(.enabled)
                                Text(step.completedAt, format: .dateTime.month().day().hour().minute())
                                    .foregroundColor(Theme.textDim)
                                if let criterion = step.definitionOfDone, !criterion.isEmpty {
                                    Text("做到：\(criterion)").foregroundColor(Theme.textDim)
                                }
                            }.font(Theme.Typography.secondary).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }.padding(.top, 8)
                    }.frame(maxHeight: 160)
                }.font(Theme.Typography.secondary).foregroundColor(Theme.textSecondary)
            }
        }
    }

    private func complete() {
        guard savedDraft, let step = store.completeNextAction(taskID, expectedNextAction: nextAction,
                    expectedDefinitionOfDone: definitionOfDone, expectedCompletedStepID: expectedCompletedStepID) else {
            error = store.persistenceIssue ?? "下一步已更新，请查看最新内容后再完成。"; return
        }
        completedID = step.id; nextAction = ""; definitionOfDone = ""; error = nil
        onProgress()
    }
    private func undo() {
        guard let completedID, canUndo, store.undoCompletedNextAction(taskID, stepID: completedID), let task = current else {
            error = store.persistenceIssue ?? "已有新的下一步，未覆盖你的修改。"; return
        }
        nextAction = task.nextAction ?? ""; definitionOfDone = task.definitionOfDone ?? ""
        self.completedID = nil; error = nil; onProgress()
    }
}

/// A small entry point in Today and quick capture; the task checkbox keeps its
/// existing meaning of completing the entire task.
struct NextStepProgressButton: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            Label("下一步", systemImage: "checkmark.circle")
                .font(Theme.Typography.secondary)
        }
        .buttonStyle(TertiaryActionStyle())
        .accessibilityLabel("查看和完成下一步：\(task.text)")
        .popover(isPresented: $presented) {
            NextStepEditor(store: store, task: task, presented: $presented)
        }
    }
}

private struct NextStepEditor: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @Binding var presented: Bool
    @State private var action: String
    @State private var criterion: String
    @State private var baseline: NextStepDraftBaseline
    @State private var error: String?
    @FocusState private var focused: Bool
    init(store: Store, task: TaskItem, presented: Binding<Bool>) {
        self.store = store; self.task = task; _presented = presented
        _action = State(initialValue: task.nextAction ?? "")
        _criterion = State(initialValue: task.definitionOfDone ?? "")
        _baseline = State(initialValue: NextStepDraftBaseline(task))
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PageHeading(title: "下一步", subtitle: task.text)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    TextField("接下来做的一件小事", text: $action)
                        .textFieldStyle(.plain).focused($focused)
                        .padding(10).inputSurface(focused: focused)
                        .accessibilityLabel("最小下一步")
                    TextField("做到哪里就可以停（可选）", text: $criterion)
                        .textFieldStyle(.plain).padding(10).inputSurface(focused: false)
                        .accessibilityLabel("这一步的完成标准")
                    NextStepCompletionControls(store: store, taskID: task.id, nextAction: $action, definitionOfDone: $criterion, expectedCompletedStepID: baseline.lastCompletedID) {
                        if let current = store.tasks.first(where: { $0.id == task.id }) { baseline = NextStepDraftBaseline(current) }
                        focused = true
                    }
                    if let error { Text(error).foregroundColor(Theme.qUrgent).font(Theme.Typography.secondary) }
                }
            }.frame(height: 300)
            HStack {
                Button("关闭") { presented = false }.buttonStyle(TertiaryActionStyle())
                Spacer()
                Button("保存下一步") {
                    guard store.updateNextAction(task.id, nextAction: action, definitionOfDone: criterion,
                        expectedNextAction: baseline.action, expectedDefinitionOfDone: baseline.criterion, expectedCompletedStepID: baseline.lastCompletedID) else {
                        error = store.persistenceIssue ?? "下一步已在其他位置更新，草稿已保留。"; return
                    }
                    presented = false
                }.buttonStyle(AIPrimaryButtonStyle())
            }
        }.padding(18).frame(width: 400).background(Theme.bg)
        .onAppear { focused = true }
    }
}
