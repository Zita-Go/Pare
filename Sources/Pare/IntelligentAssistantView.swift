import SwiftUI
import PareCore

/// Contextual assistance with explicit generation, review, atomic adoption and undo.
struct IntelligentAssistantView: View {
    @ObservedObject var store: Store
    let intent: IntelligenceIntent
    var subjectID: UUID?
    var initialInstruction: String = ""
    var onAdoptNextStep: ((IntelligenceAction) -> Void)? = nil
    @ObservedObject private var service = AIService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var options = IntelligenceContextOptions()
    @State private var availableMinutes = 30
    @State private var energy = "一般"
    @State private var instruction = ""
    @State private var request: IntelligenceRequest?
    @State private var pendingRequest: IntelligenceRequest?
    @State private var proposal: IntelligenceProposal?
    @State private var selectedActionIDs: Set<UUID> = []
    @State private var work: Task<Void, Never>?
    @State private var generationID: UUID?
    @State private var errorMessage: String?
    @State private var notice: String?
    @State private var showScope = true
    @State private var showSettings = false
    @State private var receiptID: UUID?
    @State private var adoptedRequestID: UUID?
    @State private var memoryConfirmed = false
    @State private var feedbackSaved: IntelligenceFeedbackKind?
    @State private var lastSummary = ""
    @State private var userInstructions: [String] = []

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    referencePanel
                    if intent == .today { planningControls }
                    if let proposal { resultPanel(proposal) }
                    else { introduction }
                    inputPanel
                    if let errorMessage {
                        Label(errorMessage, systemImage: "exclamationmark.circle")
                            .font(Theme.font(12)).foregroundColor(Theme.qUrgent).textSelection(.enabled)
                    }
                    if let notice { Text(notice).font(Theme.font(12)).foregroundColor(Theme.amber) }
                    if let receiptID { undoPanel(receiptID) }
                }.padding(20)
            }
            footer
        }
        .frame(width: 570, height: 740)
        .background(Theme.bg).foregroundColor(Theme.text).tint(Theme.amber)
        .onAppear {
            instruction = initialInstruction
            loadScope()
            rebuildPreview()
            service.refreshCount()
        }
        .onDisappear { cancelRequest() }
        .onChange(of: options) { _ in invalidateContext(); rebuildPreview() }
        .onChange(of: availableMinutes) { _ in invalidateContext(clearUserInstructions: false); rebuildPreview() }
        .onChange(of: energy) { _ in invalidateContext(clearUserInstructions: false); rebuildPreview() }
        .onChange(of: instruction) { _ in if work == nil && proposal == nil { rebuildPreview() } }
        .onChange(of: service.configurationRevision) { _ in
            invalidateContext(); loadScope(); rebuildPreview()
            notice = "服务配置已更新，请检查参考范围后重新生成。"
        }
        .sheet(isPresented: $showSettings) {
            VStack(alignment: .leading, spacing: 16) {
                AIConfigurationView()
                Button("返回助手") { showSettings = false }
            }.padding(20).frame(width: 490).background(Theme.bg)
        }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 5) {
                Text(intent.title).font(Theme.font(20, weight: .semibold))
                Text("记得前情，找到一个可以开始的动作").font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            Spacer()
            Button { showSettings = true } label: { Image(systemName: "gearshape") }.help("AI 设置")
            Button("关闭") { cancelRequest(); dismiss() }
        }.buttonStyle(.plain).padding(20)
        .overlay(alignment: .bottom) { Divider() }
    }

    private var referencePanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("本次参考", systemImage: "doc.text.magnifyingglass").font(Theme.font(12, weight: .medium))
                Spacer()
                Button(showScope ? "收起" : "调整") { showScope.toggle() }.buttonStyle(.plain)
            }
            Text(displayRequest?.contextSummary ?? "请调整参考范围")
                .font(Theme.font(12)).foregroundColor(Theme.textDim)
            if showScope {
                Text("点击生成后，以下勾选内容会发送到 \(service.configuration.baseURL)。仅打开此面板不会发送。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim).textSelection(.enabled)
                Text(intent == .idea ? "必需：当前想法的标题、描述、阶段和下一问题。" : intent == .today ? "必需：候选任务及原今日重点（可能已完成）的标题、下一步、截止、今日安排与专注/并行状态。" : "必需：当前任务内容、下一步和安排状态。")
                    .font(Theme.font(11))
                Toggle(intent == .idea ? "当前想法的直接关联行动" : "直接关联来源想法的描述", isOn: $options.includeLinkedTasks)
                Toggle(intent == .idea ? "当前想法的探索记录" : "来源想法的探索记录", isOn: $options.includeIdeaRecords)
                    .disabled(intent != .idea && !options.includeLinkedTasks)
                Toggle("相关反馈与行为记录", isOn: $options.includeHistory)
                Toggle("已明确确认的偏好", isOn: $options.includeMemories)
                if !store.goals.isEmpty {
                    DisclosureGroup("另行选择目标（默认不发送）") {
                        ForEach(store.goals) { goal in
                            Toggle(goal.title, isOn: Binding(get: { options.selectedGoalIDs.contains(goal.id) }, set: {
                                if $0 { options.selectedGoalIDs.insert(goal.id) } else { options.selectedGoalIDs.remove(goal.id) }
                            }))
                        }
                    }
                }
                HStack {
                    Button("记住当前服务的范围") {
                        var remembered = options; remembered.selectedGoalIDs = []
                        service.rememberIntelligenceContext(remembered)
                        notice = "已记住当前服务的范围；目标每次另行选择。"
                    }
                    Spacer()
                    Button("清除授权记忆") {
                        service.forgetIntelligenceContext()
                        options = IntelligenceContextOptions(includeIdeaRecords: false, includeLinkedTasks: false)
                        invalidateContext(); rebuildPreview()
                        notice = "已清除当前服务的授权记忆，旧结果已作废。"
                    }
                }.font(Theme.font(10))
            }
            DisclosureGroup(proposal == nil ? "查看完整发送预览与来源" : "查看下次发送预览与来源") {
                if let request = displayRequest {
                    ForEach(request.evidence) { evidence in evidenceRow(evidence) }
                    Text(request.preview).font(.system(size: 10, design: .monospaced))
                        .textSelection(.enabled).padding(.top, 8)
                }
            }.font(Theme.font(11))
        }.font(Theme.font(12)).padding(14).panel()
    }

    private var planningControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            Stepper("本次安排预算：\(availableMinutes) 分钟", value: $availableMinutes, in: 5...480, step: 5)
            Picker("现在的精力", selection: $energy) {
                Text("较低").tag("较低"); Text("一般").tag("一般"); Text("充足").tag("充足")
            }
            Text("整个今日清单：\(store.dailyLoad().label)")
                .foregroundColor(Theme.textDim)
            Text("后台并行运行不计入个人容量；人工检查单独安排。")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
        }.font(Theme.font(12)).padding(14).panel()
    }

    private var introduction: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(intent == .idea ? "先找一个值得验证的问题" : intent == .unblock ? "先做 2–5 分钟，也可以停下来" : "选一件重点，留出一个起点")
                .font(Theme.font(17, weight: .medium))
            Text(intent == .idea ? "没有关联行动也可以开始。原始想法和探索记录会保留；建议采纳后标记为 AI 辅助。" : intent == .unblock ? "写下最卡的地方。采纳会更新任务下一步，开始计时仍由你决定。" : "预算只约束本次安排，采纳前还会显示整个今日清单的负荷。")
                .font(Theme.font(12)).foregroundColor(Theme.textDim)
        }.padding(.vertical, 4)
    }

    private func resultPanel(_ proposal: IntelligenceProposal) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("前情").font(Theme.font(11)).foregroundColor(Theme.textDim)
            Text(proposal.summary).font(Theme.font(14)).textSelection(.enabled)
            citations(proposal.evidenceIDs)
            if !proposal.openQuestion.isEmpty {
                Label("关键未知", systemImage: "questionmark.circle").font(Theme.font(12, weight: .medium))
                Text(proposal.openQuestion).font(Theme.font(13)).textSelection(.enabled)
            }
            if !proposal.clarification.isEmpty {
                Text(proposal.clarification).font(Theme.font(15, weight: .medium)).foregroundColor(Theme.amber)
                Text("补充下面的问题后，再点生成。当前没有需要采纳的操作。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            if let first = orderedActions(proposal).first { actionCard(first, primary: true) }
            if proposal.actions.count > 1 {
                DisclosureGroup("其它可选操作（\(proposal.actions.count - 1)）") {
                    VStack(spacing: 10) { ForEach(Array(orderedActions(proposal).dropFirst())) { actionCard($0, primary: false) } }.padding(.top, 8)
                }.font(Theme.font(12))
            }
            if !proposal.actions.isEmpty && adoptedRequestID != proposal.requestID {
                adoptionPreview(proposal)
                Button("采纳并更新（\(selectedActionIDs.count)项）") { adopt(proposal) }
                    .buttonStyle(AIPrimaryButtonStyle()).disabled(selectedActionIDs.isEmpty || work != nil)
            }
            if let candidate = proposal.memoryCandidate, !candidate.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("是否以后沿用？").font(Theme.font(12, weight: .medium))
                    Text(candidate).font(Theme.font(12))
                    Button(memoryConfirmed ? "已确认偏好" : "确认并记住这条偏好") { confirmMemory(candidate) }
                        .disabled(memoryConfirmed)
                    Text("\(memoryIdeaID == nil ? "全局" : "当前想法")偏好，可在设置中修改或忘记；反馈不会自动变成偏好。")
                        .font(Theme.font(10)).foregroundColor(Theme.textDim)
                }.padding(12).panel()
            }
            HStack(spacing: 10) {
                ForEach(IntelligenceFeedbackKind.allCases, id: \.self) { kind in
                    Button(kind.title) { saveFeedback(kind) }
                        .disabled(feedbackSaved == kind)
                }
            }.font(Theme.font(11)).buttonStyle(.plain).foregroundColor(Theme.textDim)
            Text("反馈只保存在本地。调整建议请用下面的按钮，才会再次请求模型。")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
        }
    }

    private func orderedActions(_ proposal: IntelligenceProposal) -> [IntelligenceAction] {
        let preferred: IntelligenceActionKind = intent == .idea ? .createLinkedTask : .updateTaskNextStep
        guard let primary = proposal.actions.first(where: { $0.kind == preferred }) else { return proposal.actions }
        return [primary] + proposal.actions.filter { $0.id != primary.id }
    }

    private func defaultSelection(_ proposal: IntelligenceProposal) -> Set<UUID> {
        guard let primary = orderedActions(proposal).first else { return [] }
        if intent == .today {
            // Keep the complete starting action and its safe plan changes
            // together. Other tasks and explicit deferrals require selection.
            return Set(proposal.actions.filter { action in
                action.sourceID == primary.sourceID && (action.kind == .updateTaskNextStep
                    || (action.kind == .setTodaySelection && action.selected)
                    || (action.kind == .setDailyWin && action.selected))
            }.map(\.id))
        }
        return primary.kind == .setTodaySelection && !primary.selected ? [] : [primary.id]
    }

    private func actionCard(_ action: IntelligenceAction, primary: Bool) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Toggle(isOn: Binding(get: { selectedActionIDs.contains(action.id) }, set: {
                if $0 { selectedActionIDs.insert(action.id) } else { selectedActionIDs.remove(action.id) }
            })) {
                Text(action.title.isEmpty ? action.kind.title : action.title).font(Theme.font(15, weight: .semibold))
            }.disabled(adoptedRequestID == proposal?.requestID)
            Text(actionDescription(action)).font(Theme.font(11)).foregroundColor(Theme.amber)
            if !action.text.isEmpty { Text(action.text).font(Theme.font(13)).textSelection(.enabled) }
            if !action.nextAction.isEmpty { Text(action.nextAction).font(Theme.font(15, weight: .medium)).textSelection(.enabled) }
            if let minutes = action.plannedMinutes {
                Text("本次投入 \(minutes) 分钟 · \(action.allocationKind.title)").font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            if !action.definitionOfDone.isEmpty {
                Text("做到这里可以停：\(action.definitionOfDone)").font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            if !action.reason.isEmpty {
                DisclosureGroup("建议理由") { Text(action.reason).font(Theme.font(12)).padding(.top, 5) }.font(Theme.font(11))
            }
            citations(action.evidenceIDs)
        }.padding(14).panel(accent: primary ? Theme.amber : nil)
    }

    private func actionDescription(_ action: IntelligenceAction) -> String {
        let target = request?.evidence.first(where: { $0.id == action.sourceID })?.title ?? "当前对象"
        switch action.kind {
        case .setTodaySelection: return "\(action.selected ? "加入 / 保留今天" : "从今天暂缓")：\(target)"
        case .setDailyWin: return "今日重点改为：\(target)"
        case .createLinkedTask: return "创建关联行动 · 不自动加入今天"
        default: return "\(action.kind.title)：\(target)"
        }
    }

    private func adoptionPreview(_ proposal: IntelligenceProposal) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("确认本次变化").font(Theme.font(12, weight: .medium))
            ForEach(proposal.actions.filter { selectedActionIDs.contains($0.id) }) { action in
                Text("• \(actionDescription(action))").font(Theme.font(11))
            }
            if intent == .today {
                if let load = try? store.projectedDailyLoad(for: proposal, selectedActionIDs: selectedActionIDs) {
                    Text("采纳后整个今日清单：\(load.label)").font(Theme.font(12, weight: .medium))
                }
                Text("原整项估时不改；日程调整为草稿，不自动移动时间块。")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
            }
            Text("未勾选的操作不会执行，暂缓需要明确勾选。")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
        }.padding(12).panel()
    }

    private func citations(_ ids: [UUID]) -> some View {
        Group {
            if !ids.isEmpty {
                DisclosureGroup("查看依据（\(ids.count)）") {
                    ForEach(request?.evidence.filter { ids.contains($0.id) } ?? []) { evidenceRow($0) }
                }.font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
        }
    }

    private func evidenceRow(_ evidence: IntelligenceEvidence) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(evidence.title).font(Theme.font(12, weight: .medium))
            Text(evidence.date.formatted(date: .abbreviated, time: .omitted)).font(Theme.font(10)).foregroundColor(Theme.textDim)
            Text(evidence.body).font(Theme.font(11)).textSelection(.enabled)
            Text("来源编号 \(evidence.id.uuidString)").font(.system(size: 9, design: .monospaced)).foregroundColor(Theme.textDim).textSelection(.enabled)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
    }

    private var inputPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(proposal == nil ? "这次想补充什么？" : "继续补充或调整建议").font(Theme.font(12, weight: .medium))
            TextField(intent == .unblock ? "例如：不知道第一段怎么写" : "当前要求优先于旧偏好", text: $instruction, axis: .vertical)
                .lineLimit(2...5).textFieldStyle(.roundedBorder).disabled(work != nil)
            if instruction.count > 1000 { Text("补充内容请控制在 1000 字以内。").font(Theme.font(11)).foregroundColor(Theme.qUrgent) }
            if !userInstructions.isEmpty {
                DisclosureGroup("本轮保留的明确要求（\(userInstructions.count)条）") {
                    ForEach(Array(userInstructions.enumerated()), id: \.offset) { _, text in
                        Text(text).font(Theme.font(11)).textSelection(.enabled)
                    }
                }.font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            if proposal != nil {
                HStack {
                    Button("再小一点") { generate(followUp: "请把上一个建议缩小，给一个更容易开始且允许停止的动作。") }
                    Button("换一种思路") { generate(followUp: "上一种思路不适合，请结合相同事实换一种具体办法。") }
                }.font(Theme.font(12)).disabled(work != nil || service.isRunning || instruction.count > 1000)
            }
            if proposal != nil || !userInstructions.isEmpty {
                Button("重新开始这一轮") {
                    invalidateContext(); rebuildPreview()
                    notice = "已清除本轮旧要求与模型摘要；当前补充仍保留。"
                }.font(Theme.font(11)).disabled(work != nil)
            }
        }
    }

    private var footer: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("今日请求 \(service.requestCount)/\(service.configuration.dailyRequestLimit)").font(Theme.font(11))
                Text("每次点击一次请求 · 失败不自动重试").font(Theme.font(10)).foregroundColor(Theme.textDim)
            }
            Spacer()
            if work != nil {
                ProgressView().controlSize(.small)
                Button("取消请求") { cancelRequest(); notice = "已取消，输入仍保留。" }
            } else {
                Button(service.configuration.enabled ? (proposal == nil ? "生成建议" : "补充后生成") : "连接 AI") {
                    if service.configuration.enabled { generate() } else { showSettings = true }
                }.buttonStyle(AIPrimaryButtonStyle())
                    .disabled(service.isRunning || instruction.count > 1000)
            }
        }.padding(16).overlay(alignment: .top) { Divider() }
    }

    private func undoPanel(_ id: UUID) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text("本次采纳已保存").font(Theme.font(12, weight: .medium))
                Text("撤销前会检查后续编辑，保留你后来修改的内容。")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
            }
            Spacer()
            Button("撤销本次采纳") {
                do {
                    try store.undoIntelligenceAdoption(id)
                    receiptID = nil; notice = "已撤销本次采纳。需要其它建议时可重新生成。"
                    if intent == .unblock, let subjectID, let task = store.tasks.first(where: { $0.id == subjectID }) {
                        onAdoptNextStep?(IntelligenceAction(kind: .updateTaskNextStep, sourceID: subjectID,
                            nextAction: task.nextAction ?? "", definitionOfDone: task.definitionOfDone ?? ""))
                    }
                } catch { errorMessage = error.localizedDescription }
            }.font(Theme.font(12))
        }.padding(12).panel(accent: Theme.amber)
    }

    private var displayRequest: IntelligenceRequest? {
        work != nil ? pendingRequest : ((try? makeRequest()) ?? request)
    }

    private func loadScope() {
        options = service.intelligenceContextOptions() ?? IntelligenceContextOptions()
        options.selectedGoalIDs = []
        showScope = service.intelligenceContextOptions() == nil
    }

    private func nextUserInstructions(followUp: String = "") -> [String] {
        var items = userInstructions
        for text in [followUp, instruction.trimmingCharacters(in: .whitespacesAndNewlines)] where !text.isEmpty {
            // A repeated requirement moves to the end, where current intent wins.
            items.removeAll { $0 == text }
            items.append(text)
        }
        return items
    }

    private func makeRequest(followUp: String = "") throws -> IntelligenceRequest {
        let explicitInstructions = nextUserInstructions(followUp: followUp)
        // Never silently truncate explicit constraints. Model summaries are only
        // context; user-authored requirements have their own bounded retention.
        guard explicitInstructions.joined(separator: "\n").count <= 1400 else {
            throw IntelligenceConversationError.instructionsTooLong
        }
        var parts: [String] = []
        if !lastSummary.isEmpty { parts.append("上一轮建议摘要（模型产物，仅供参考）：\(String(lastSummary.prefix(400)))") }
        if !explicitInstructions.isEmpty {
            parts.append("用户明确要求（按时间顺序，靠后的本次要求优先）：\n" + explicitInstructions.joined(separator: "\n"))
        }
        return try store.prepareIntelligenceRequest(intent: intent, subjectID: subjectID,
            availableMinutes: intent == .unblock ? 5 : availableMinutes, energy: energy,
            instruction: parts.joined(separator: "\n"), options: options)
    }

    private func rebuildPreview() {
        do { request = try makeRequest(); errorMessage = nil }
        catch { request = nil; errorMessage = error.localizedDescription }
    }

    private func cancelRequest() {
        generationID = nil; work?.cancel(); work = nil; pendingRequest = nil
    }

    private func invalidateContext(clearUserInstructions: Bool = true) {
        cancelRequest(); proposal = nil; request = nil; selectedActionIDs = []
        lastSummary = ""; feedbackSaved = nil; memoryConfirmed = false
        if clearUserInstructions { userInstructions = [] }
    }

    private func generate(followUp: String = "") {
        guard work == nil, !service.isRunning else { return }
        let input: IntelligenceRequest
        do { input = try makeRequest(followUp: followUp) }
        catch { errorMessage = error.localizedDescription; return }
        // Keep the currently reviewed proposal and its matching request until
        // a replacement succeeds. A failed/cancelled follow-up loses no work.
        pendingRequest = input; errorMessage = nil; notice = nil
        let retainedInstructions = nextUserInstructions(followUp: followUp)
        let token = UUID(); generationID = token
        work = Task { @MainActor in
            defer { if generationID == token { work = nil; generationID = nil; pendingRequest = nil } }
            do {
                let result = try await service.generate(input)
                try Task.checkCancellation()
                guard generationID == token, input.options == options else { return }
                request = input; proposal = result
                selectedActionIDs = defaultSelection(result)
                let firstAction = orderedActions(result).first
                let previousAction = firstAction.map { $0.nextAction.isEmpty ? $0.text : $0.nextAction } ?? ""
                lastSummary = result.summary + "\n" + result.openQuestion + "\n" + result.clarification + "\n" + previousAction
                userInstructions = retainedInstructions
                instruction = ""; feedbackSaved = nil; memoryConfirmed = false
            } catch {
                guard generationID == token, !Task.isCancelled else { return }
                errorMessage = error.localizedDescription
            }
        }
    }

    private func adopt(_ proposal: IntelligenceProposal) {
        guard let request else { return }
        do {
            let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: selectedActionIDs)
            receiptID = receipt.id; adoptedRequestID = proposal.requestID
            notice = "已保存选中的操作。"; errorMessage = nil
            if let action = proposal.actions.first(where: { $0.kind == .updateTaskNextStep && selectedActionIDs.contains($0.id) }) {
                onAdoptNextStep?(action)
            }
        } catch { errorMessage = error.localizedDescription }
    }

    private var memoryIdeaID: UUID? {
        if intent == .idea { return subjectID }
        if intent == .unblock { return request?.evidence.first(where: { $0.kind == "idea" })?.id }
        return nil
    }

    private func confirmMemory(_ text: String) {
        do {
            _ = try store.confirmPreference(text: text, ideaID: memoryIdeaID, source: "用户确认 AI 提议")
            memoryConfirmed = true; notice = "已记住这条明确确认的偏好。"
        } catch { errorMessage = error.localizedDescription }
    }

    private func saveFeedback(_ kind: IntelligenceFeedbackKind) {
        guard let request, let proposal else { return }
        do {
            let actions = orderedActions(proposal)
            let action = actions.first(where: { selectedActionIDs.contains($0.id) }) ?? actions.first
            let taskID = intent == .unblock ? subjectID : (intent == .today ? action?.sourceID : nil)
            try store.recordIntelligenceFeedback(requestID: request.id, actionID: action?.id,
                ideaID: memoryIdeaID, sourceTaskID: taskID, kind: kind)
            feedbackSaved = kind; notice = "反馈已保存在本地。"
        } catch { errorMessage = error.localizedDescription }
    }
}


private enum IntelligenceConversationError: LocalizedError {
    case instructionsTooLong
    var errorDescription: String? {
        "本轮累计明确要求超过 1400 字。请精简补充，或点击「重新开始这一轮」后填写仍需保留的要求；没有截断或发送这些内容。"
    }
}
