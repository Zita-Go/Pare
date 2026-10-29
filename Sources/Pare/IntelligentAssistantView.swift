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
    @ObservedObject private var requestHistory = AIRequestHistoryController.shared
    @Environment(\.dismiss) private var dismiss
    @State private var options = IntelligenceContextOptions()
    @State private var availableMinutes = 30
    @State private var energy = "一般"
    @State private var instruction = ""
    @State private var request: IntelligenceRequest?
    @State private var pendingRequest: IntelligenceRequest?
    @State private var lastRequestHistoryID: UUID?
    @State private var proposal: IntelligenceProposal?
    @State private var selectedActionIDs: Set<UUID> = []
    @State private var work: Task<Void, Never>?
    @State private var generationID: UUID?
    @State private var errorMessage: String?
    @State private var notice: String?
    @State private var noticeKind: FeedbackKind = .info
    @FocusState private var instructionFocused: Bool
    @State private var showScope = false
    @State private var showCustomMinutes = false
    @State private var showSupplement = false
    @State private var showSettings = false
    @State private var errorOffersConnectionSettings = false
    @State private var requiresFreshProposal = false
    @State private var receiptID: UUID?
    @State private var adoptedRequestID: UUID?
    @State private var undoneRequestID: UUID?
    @State private var memoryConfirmed = false
    @State private var feedbackSaved: IntelligenceFeedbackKind?
    @State private var lastSummary = ""
    @State private var userInstructions: [String] = []

    init(store: Store, intent: IntelligenceIntent, subjectID: UUID? = nil,
         initialInstruction: String = "", onAdoptNextStep: ((IntelligenceAction) -> Void)? = nil) {
        self.store = store; self.intent = intent; self.subjectID = subjectID
        self.initialInstruction = initialInstruction; self.onAdoptNextStep = onAdoptNextStep
        let minutes = min(480, max(5, store.agentPreferences.planningMinutes))
        _availableMinutes = State(initialValue: minutes)
        _showCustomMinutes = State(initialValue: ![15, 30, 60, 120].contains(minutes))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if let proposal {
                            resultPanel(proposal).id("result")
                            referencePanel.id("reference")
                            if intent == .today {
                                DisclosureGroup("调整本次时间与精力（\(availableMinutes) 分钟）") { planningControls }
                                    .font(Theme.font(12))
                            }
                        } else {
                            introduction
                            if intent == .today { planningControls }
                            inputPanel
                            referencePanel.id("reference")
                        }
                        if proposal == nil, let id = lastRequestHistoryID {
                            AIRequestHistoryEntry(selectedID: id, title: "最近请求详情")
                                .disabled(work != nil)
                        }
                    }.padding(20)
                }
                .onChange(of: proposal?.requestID) { value in
                    if value != nil { proxy.scrollTo("result", anchor: .top) }
                }
                .onChange(of: showScope) { expanded in
                    if expanded { withAnimation { proxy.scrollTo("reference", anchor: .top) } }
                }
            }
            footer
        }
        .frame(width: 570, height: 640)
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
            noticeKind = .info
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
        HStack {
            Label(intent.title, systemImage: "sparkles").font(Theme.Typography.title)
            Spacer()
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .buttonStyle(IconActionStyle()).help("AI 连接设置").accessibilityLabel("AI 连接设置")
            Button("关闭") { cancelRequest(); dismiss() }.buttonStyle(TertiaryActionStyle())
        }.buttonStyle(.plain).padding(.horizontal, 20).padding(.vertical, 16)
        .background(Theme.surface.opacity(0.45))
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.separator).frame(height: 1) }
    }

    private var requiredContent: String {
        switch intent {
        case .idea: return "必发：当前想法的标题、描述、阶段和下一问题。"
        case .today: return "必发：候选任务及原今日重点的内容、截止、今日安排与专注 / 并行状态。"
        case .unblock: return "必发：当前任务内容、下一步与安排状态。"
        }
    }

    private var referencePanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(displayRequest?.contextSummary ?? "本次参考范围").font(Theme.font(11, weight: .medium))
                Spacer()
                Button(showScope ? "收起" : "调整参考") { showScope.toggle() }.buttonStyle(TertiaryActionStyle())
            }
            Text("发送到 \(service.configuration.baseURL)")
                .font(Theme.font(10)).textSelection(.enabled)
            Text(requiredContent).font(Theme.font(10))
            if proposal == nil {
                Text("仅在点击生成时发送上述内容、本次时间 / 精力和补充要求。\(options.includeHistory ? "含相关历史；" : "不含相关历史；")\(options.includeMemories ? "含已确认偏好。" : "不含已确认偏好。")")
                    .font(Theme.font(10))
            }
            if showScope {
                Divider().padding(.vertical, 4)
                Text("额外参考；调整范围会清除当前建议，需重新生成。")
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
                        noticeKind = .success
                        notice = "已记住当前服务的范围；目标每次另行选择。"
                    }
                    Spacer()
                    Button("清除授权记忆") {
                        service.forgetIntelligenceContext()
                        options = IntelligenceContextOptions(includeIdeaRecords: false, includeLinkedTasks: false)
                        invalidateContext(); rebuildPreview()
                        noticeKind = .info
                        notice = "已清除当前服务的授权记忆，旧结果已作废。"
                    }
                }.font(Theme.font(10))
                DisclosureGroup(proposal == nil ? "查看本次参考内容" : "查看下次参考内容") {
                    if let request = displayRequest { ForEach(request.evidence) { evidenceRow($0) } }
                }
                DisclosureGroup("高级诊断 · 待发送参考数据预览") {
                    Text("这里只是下一次请求的参考数据，不含系统提示词与服务参数。已发送的固定记录请查看请求详情。")
                        .font(Theme.font(11))
                    if let request = displayRequest {
                        Text(request.preview).font(.system(size: 10, design: .monospaced))
                            .textSelection(.enabled).padding(.top, 8)
                    }
                }
            }
        }.font(Theme.font(12)).foregroundColor(Theme.textDim).padding(12).panel()
    }

    private var planningControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("这次有多少时间？\(availableMinutes) 分钟").font(Theme.font(13, weight: .medium))
            HStack(spacing: 8) {
                ForEach([15, 30, 60, 120], id: \.self) { minutes in
                    Button { availableMinutes = minutes; showCustomMinutes = false } label: {
                        Text("\(minutes) 分钟")
                            .foregroundColor(availableMinutes == minutes ? Theme.text : Theme.textDim)
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .background(RoundedRectangle(cornerRadius: Theme.Radius.control).fill(availableMinutes == minutes ? Theme.surfaceRaised : Theme.surface))
                            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control)
                                .stroke(availableMinutes == minutes ? Theme.focus.opacity(0.6) : .clear, lineWidth: 1)
                                .allowsHitTesting(false))
                    }.buttonStyle(.plain)
                        .accessibilityAddTraits(availableMinutes == minutes ? .isSelected : [])
                }
                Button("自定义") { showCustomMinutes.toggle() }.buttonStyle(TertiaryActionStyle())
            }
            if showCustomMinutes {
                HStack {
                    Text("本次分钟")
                    TextField("分钟", value: Binding(get: { availableMinutes }, set: { availableMinutes = min(480, max(5, $0)) }), format: .number)
                        .textFieldStyle(.roundedBorder).frame(width: 65)
                    Stepper("", value: $availableMinutes, in: 5...480, step: 5).labelsHidden()
                    Text("5–480 分钟").foregroundColor(Theme.textDim)
                }
            }
            DisclosureGroup("精力：\(energy)") {
                Picker("现在的精力", selection: $energy) {
                    Text("较低").tag("较低"); Text("一般").tag("一般"); Text("充足").tag("充足")
                }.pickerStyle(.segmented).labelsHidden().padding(.top, 5)
            }
            Text("整个今日清单：\(store.dailyLoad().label)")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
        }.font(Theme.font(12)).padding(12).panel().disabled(work != nil)
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
        let plans = IntelligencePresentation.plans(for: proposal, intent: intent)
        return VStack(alignment: .leading, spacing: 12) {
            if let first = plans.first { actionCard(first, primary: true) }
            AIRequestHistoryEntry(selectedID: proposal.requestID, title: "本次请求详情")
                .disabled(work != nil)
            if let id = lastRequestHistoryID, id != proposal.requestID, errorMessage != nil {
                AIRequestHistoryEntry(selectedID: id, title: "最近失败请求详情")
            }
            if !proposal.clarification.isEmpty {
                Text(proposal.clarification).font(Theme.font(16, weight: .medium)).foregroundColor(Theme.amber)
                Text("补充后再生成，当前没有需要采纳的操作。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
                inputPanel
            } else {
                followUpButtons
                DisclosureGroup("补充要求", isExpanded: $showSupplement) { inputPanel.padding(.top, 6) }
                    .font(Theme.font(12))
            }
            if plans.count > 1 {
                DisclosureGroup("其他方案（\(plans.count - 1)个，已选\(otherSelectedCount(plans))项）") {
                    VStack(spacing: 10) {
                        ForEach(Array(plans.dropFirst())) { actionCard($0, primary: false) }
                    }.padding(.top, 8)
                }.font(Theme.font(12))
            }
            if !proposal.actions.isEmpty && !currentProposalWasAdopted {
                DisclosureGroup("逐项查看采纳变化") { adoptionPreview(proposal) }.font(Theme.font(11))
            }
            DisclosureGroup("前情与依据") {
                VStack(alignment: .leading, spacing: 8) {
                    Text(proposal.summary).font(Theme.font(12)).textSelection(.enabled)
                    if !proposal.openQuestion.isEmpty {
                        Text("待确认：\(proposal.openQuestion)").font(Theme.font(12))
                    }
                    citations(proposal.evidenceIDs)
                }.padding(.top, 6)
            }.font(Theme.font(12)).foregroundColor(Theme.textDim)
            if currentProposalWasAdopted, receiptID != nil,
               let candidate = proposal.memoryCandidate, !candidate.isEmpty {
                memoryPanel(candidate)
            }
            DisclosureGroup("记录反馈与本轮管理") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) {
                        ForEach(IntelligenceFeedbackKind.allCases, id: \.self) { kind in
                            Button(kind.title) { saveFeedback(kind) }.disabled(feedbackSaved == kind)
                        }
                    }
                    Text("反馈仅保存在本地，不会请求新建议。")
                    if !userInstructions.isEmpty {
                        DisclosureGroup("本轮保留的明确要求（\(userInstructions.count)条）") {
                            ForEach(Array(userInstructions.enumerated()), id: \.offset) { _, text in
                                Text(text).textSelection(.enabled)
                            }
                        }
                    }
                    restartButton
                }.font(Theme.font(11)).padding(.top, 8)
            }.font(Theme.font(11)).foregroundColor(Theme.textDim)
        }
    }

    private func otherSelectedCount(_ plans: [IntelligenceActionPlan]) -> Int {
        plans.dropFirst().reduce(0) { $0 + $1.actionIDs.intersection(selectedActionIDs).count }
    }

    private var currentProposalWasAdopted: Bool {
        guard let proposal else { return false }
        return adoptedRequestID == proposal.requestID
    }

    private var currentProposalWasUndone: Bool {
        guard let proposal else { return false }
        return undoneRequestID == proposal.requestID
    }

    private func orderedActions(_ proposal: IntelligenceProposal) -> [IntelligenceAction] {
        IntelligencePresentation.plans(for: proposal, intent: intent).flatMap(\.actions)
    }

    private func defaultSelection(_ proposal: IntelligenceProposal) -> Set<UUID> {
        IntelligencePresentation.defaultSelection(for: proposal, intent: intent)
    }

    private func actionCard(_ plan: IntelligenceActionPlan, primary: Bool) -> some View {
        let action = plan.primary
        let selectedCount = plan.actionIDs.intersection(selectedActionIDs).count
        let headline = action.title.isEmpty
            ? (action.kind == .updateTaskNextStep ? sourceTitle(action) : action.kind.title)
            : action.title
        return VStack(alignment: .leading, spacing: 9) {
            Toggle(isOn: Binding(get: { plan.actionIDs.isSubset(of: selectedActionIDs) }, set: {
                selectedActionIDs = plan.selecting($0, from: selectedActionIDs)
            })) {
                Text(headline)
                    .font(Theme.font(primary ? 17 : 15, weight: .medium))
            }.disabled(currentProposalWasAdopted || requiresFreshProposal || work != nil)
            if plan.actions.count > 1, headline != sourceTitle(action) {
                Text("任务：\(sourceTitle(action))")
                    .font(Theme.font(12)).foregroundColor(Theme.textDim).textSelection(.enabled)
            }
            if currentProposalWasUndone {
                Text("已撤销 · 重新生成后才能再次采纳")
                    .font(Theme.font(11)).foregroundColor(Theme.amber)
            } else if requiresFreshProposal {
                Text("参考已变化 · 重新生成后再采纳")
                    .font(Theme.font(11)).foregroundColor(Theme.amber)
            }
            if !action.nextAction.isEmpty {
                Text(action.nextAction).font(Theme.font(15, weight: .medium)).lineSpacing(4).textSelection(.enabled)
            }
            if !action.text.isEmpty { Text(action.text).font(Theme.font(13)).textSelection(.enabled) }
            if let minutes = action.plannedMinutes {
                Text("本次投入 \(minutes) 分钟 · \(action.allocationKind.title)")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            if !action.definitionOfDone.isEmpty {
                Text("做到这里可以停：\(action.definitionOfDone)")
                    .font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            Text(plan.actions.count > 1
                 ? "包含 \(plan.actions.count)项：" + plan.actions.map(operationLabel).joined(separator: " · ") + "（已选 \(selectedCount)项）"
                 : actionDescription(action))
                .font(Theme.font(11)).foregroundColor(Theme.amber)
            if plan.actions.count > 1 {
                DisclosureGroup("逐项调整") {
                    ForEach(plan.actions) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            Toggle(actionDescription(item), isOn: actionSelection(item))
                                .disabled(currentProposalWasAdopted || requiresFreshProposal || work != nil)
                            if item.id != action.id, let minutes = item.plannedMinutes {
                                Text("\(minutes) 分钟 · \(item.allocationKind.title)")
                                    .foregroundColor(Theme.textDim)
                            }
                            if item.id != action.id { actionExplanation(item) }
                        }.padding(.vertical, 3)
                    }
                }.font(Theme.font(11))
            }
            actionExplanation(action)
        }.padding(14).warmPanel(emphasized: primary)
    }

    private func actionSelection(_ action: IntelligenceAction) -> Binding<Bool> {
        Binding(get: { selectedActionIDs.contains(action.id) }, set: {
            if $0 { selectedActionIDs.insert(action.id) } else { selectedActionIDs.remove(action.id) }
        })
    }

    private func operationLabel(_ action: IntelligenceAction) -> String {
        switch action.kind {
        case .setTodaySelection: return action.selected ? "加入 / 保留今天" : "从今天暂缓"
        case .setDailyWin: return "设为重点"
        default: return action.kind.title
        }
    }

    private func actionExplanation(_ action: IntelligenceAction) -> some View {
        Group {
            if !action.reason.isEmpty || !action.evidenceIDs.isEmpty {
                DisclosureGroup("理由与依据") {
                    if !action.reason.isEmpty { Text(action.reason).padding(.top, 5) }
                    ForEach(request?.evidence.filter { action.evidenceIDs.contains($0.id) } ?? []) { evidenceRow($0) }
                }.font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
        }
    }

    private func memoryPanel(_ candidate: String) -> some View {
        DisclosureGroup(memoryConfirmed ? "已保存这条偏好" : "可选：把这条偏好留到以后") {
            VStack(alignment: .leading, spacing: 8) {
                Text(candidate).font(Theme.font(12))
                Button(memoryConfirmed ? "已保存在本地" : "确认并保存偏好") { confirmMemory(candidate) }
                    .disabled(memoryConfirmed)
                Text("\(memoryIdeaID == nil ? "全局" : "当前想法")偏好；可在主窗口的「设置 → 已确认的记忆与轻提示」中修改或忘记。")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
                if !options.includeMemories {
                    Text("本服务尚未启用发送已确认偏好；保存不会改变参考授权。")
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    Button("打开参考设置") { showScope = true }
                }
            }.padding(.top, 6)
        }.font(Theme.font(12))
    }

    private func sourceTitle(_ action: IntelligenceAction) -> String {
        request?.evidence.first(where: { $0.id == action.sourceID })?.title ?? "当前对象"
    }

    private func actionDescription(_ action: IntelligenceAction) -> String {
        let target = sourceTitle(action)
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
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
    }

    private var inputPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            if proposal == nil { Text("这次想补充什么？").font(Theme.font(12, weight: .medium)) }
            TextField(intent == .unblock ? "例如：不知道第一段怎么写" : "例如：先找一个容易开始的办法（可不填）", text: $instruction, axis: .vertical)
                .lineLimit(2...5).textFieldStyle(.plain).font(Theme.Typography.body)
                .focused($instructionFocused)
                .padding(10).inputSurface(focused: instructionFocused).disabled(work != nil)
            if instruction.count > 1000 {
                Text("补充内容请控制在 1000 字以内。").font(Theme.font(11)).foregroundColor(Theme.qUrgent)
            }
            if let proposal, proposal.clarification.isEmpty {
                Button("按补充重新生成") { generate() }
                    .disabled(generationUnavailable || instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if proposal == nil && !userInstructions.isEmpty {
                DisclosureGroup("本轮要求与管理") {
                    ForEach(Array(userInstructions.enumerated()), id: \.offset) { _, text in
                        Text(text).font(Theme.font(11)).textSelection(.enabled)
                    }
                    restartButton
                }.font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
        }
    }

    private var followUpButtons: some View {
        HStack(spacing: 10) {
            Button("再小一点") { generate(followUp: "请把上一个建议缩小，给一个更容易开始且允许停止的动作。") }
            Button("换一种思路") { generate(followUp: "上一种思路不适合，请结合相同事实换一种具体办法。") }
            Text("点击会请求新建议").font(Theme.font(10)).foregroundColor(Theme.textDim)
        }.font(Theme.font(12)).buttonStyle(SecondaryActionStyle()).disabled(generationUnavailable)
    }

    private var restartButton: some View {
        Button("重新开始这一轮") {
            invalidateContext(); rebuildPreview()
            noticeKind = .info
            notice = "已清除本轮旧要求与模型摘要；当前补充仍保留。"
        }.font(Theme.font(11)).disabled(work != nil)
    }

    private var generationUnavailable: Bool {
        work != nil || service.isRunning || instruction.count > 1000
    }

    private func selectionSummary(_ proposal: IntelligenceProposal) -> String {
        let selected = proposal.actions.filter { selectedActionIDs.contains($0.id) }
        guard !selected.isEmpty else { return "还未选择操作；仅执行勾选项，暂缓需要主动选择。" }
        var labels: [String] = []
        for kind in IntelligenceActionKind.allCases {
            let actions = selected.filter { $0.kind == kind }
            if let first = actions.first {
                // Keep inclusion and deferral distinct even when their action kind matches.
                if kind == .setTodaySelection {
                    let included = actions.filter(\.selected).count
                    let deferred = actions.count - included
                    if included > 0 { labels.append("加入今天 \(included)项") }
                    if deferred > 0 { labels.append("暂缓 \(deferred)项") }
                } else {
                    labels.append(operationLabel(first) + (actions.count > 1 ? " \(actions.count)项" : ""))
                }
            }
        }
        return "已选 \(selected.count)项 · " + labels.joined(separator: " · ")
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let issue = requestHistory.persistenceIssue {
                FeedbackBanner(text: issue, kind: .warning)
            }
            if let errorMessage {
                HStack(alignment: .top, spacing: 8) {
                    FeedbackBanner(text: errorMessage, kind: .error)
                    Spacer(minLength: 0)
                    if errorOffersConnectionSettings {
                        Button("检查连接") { showSettings = true }.buttonStyle(SecondaryActionStyle())
                    }
                }
            }
            if let notice { FeedbackBanner(text: notice, kind: noticeKind) }
            if let proposal, !proposal.actions.isEmpty, !currentProposalWasAdopted {
                Text(selectionSummary(proposal)).font(Theme.font(11, weight: .medium))
                if intent == .today,
                   let load = try? store.projectedDailyLoad(for: proposal, selectedActionIDs: selectedActionIDs) {
                    Text("采纳后整个今日清单：\(load.label)").font(Theme.font(11)).foregroundColor(Theme.textDim)
                }
            }
            if let receiptID { undoPanel(receiptID) }
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("今日请求 \(service.requestCount)/\(service.configuration.dailyRequestLimit)").font(Theme.font(10))
                    Text(work != nil ? "正在生成，可随时取消" : "仅点击生成才发送 · 失败不自动重试")
                        .font(Theme.font(10)).foregroundColor(Theme.textDim)
                }
                Spacer()
                if work != nil {
                    ProgressView().controlSize(.small)
                    Button("取消请求") { cancelRequest(); noticeKind = .info; notice = "已取消，输入与已有建议仍保留。" }
                        .buttonStyle(SecondaryActionStyle())
                } else if currentProposalWasUndone || requiresFreshProposal {
                    Button(service.configuration.enabled ? "重新生成建议" : "连接 AI") {
                        if service.configuration.enabled { generate() } else { showSettings = true }
                    }.buttonStyle(AIPrimaryButtonStyle()).disabled(generationUnavailable)
                } else if currentProposalWasAdopted {
                    Button("完成") { dismiss() }.buttonStyle(AIPrimaryButtonStyle())
                } else if let proposal, !proposal.actions.isEmpty {
                    Button("采纳所选（\(selectedActionIDs.count)项）") { adopt(proposal) }
                        .buttonStyle(AIPrimaryButtonStyle()).disabled(selectedActionIDs.isEmpty)
                } else {
                    Button(service.configuration.enabled ? (proposal == nil ? "生成建议" : "补充后生成") : "连接 AI") {
                        if service.configuration.enabled { generate() } else { showSettings = true }
                    }.buttonStyle(AIPrimaryButtonStyle())
                        .disabled(generationUnavailable || (proposal != nil && instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                }
            }
        }.padding(.horizontal, 16).padding(.vertical, 12)
            .background(Theme.surface.opacity(0.5))
            .overlay(alignment: .top) { Rectangle().fill(Theme.separator).frame(height: 1) }
    }

    private func undoPanel(_ id: UUID) -> some View {
        HStack {
            Text(currentProposalWasAdopted ? "本次采纳已保存" : "上次采纳仍可撤销")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            Spacer()
            Button(currentProposalWasAdopted ? "撤销本次采纳" : "撤销上次采纳") {
                do {
                    try store.undoIntelligenceAdoption(id)
                    receiptID = nil; undoneRequestID = adoptedRequestID; errorMessage = nil; noticeKind = .info
                    if proposal != nil && !currentProposalWasAdopted {
                        // Undo changed the facts used by this newer proposal. Keep explicit
                        // instructions, but require a fresh request against the restored data.
                        invalidateContext(clearUserInstructions: false); rebuildPreview()
                        notice = "已撤销上次采纳，参考内容已变化；请重新生成。"
                    } else {
                        notice = "已撤销本次采纳；可重新生成建议。"
                    }
                    if intent == .unblock, let subjectID, let task = store.tasks.first(where: { $0.id == subjectID }) {
                        onAdoptNextStep?(IntelligenceAction(kind: .updateTaskNextStep, sourceID: subjectID,
                            nextAction: task.nextAction ?? "", definitionOfDone: task.definitionOfDone ?? ""))
                    }
                } catch { presentError(error) }
            }.buttonStyle(TertiaryActionStyle()).disabled(work != nil)
                .help("撤销会检查后续编辑；有冲突时保留后来修改的内容。")
        }
    }

    private var displayRequest: IntelligenceRequest? {
        work != nil ? pendingRequest : ((try? makeRequest()) ?? request)
    }

    private func loadScope() {
        options = service.intelligenceContextOptions() ?? IntelligenceContextOptions()
        options.selectedGoalIDs = []
        showScope = false
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
        catch { request = nil; presentError(error) }
    }

    private func cancelRequest() {
        generationID = nil; work?.cancel(); work = nil; pendingRequest = nil
    }

    private func invalidateContext(clearUserInstructions: Bool = true) {
        cancelRequest(); proposal = nil; request = nil; selectedActionIDs = []
        lastSummary = ""; feedbackSaved = nil; memoryConfirmed = false
        requiresFreshProposal = false
        if clearUserInstructions { userInstructions = [] }
    }

    private func generate(followUp: String = "") {
        guard work == nil, !service.isRunning else { return }
        let input: IntelligenceRequest
        do { input = try makeRequest(followUp: followUp) }
        catch { presentError(error); return }
        // Keep the currently reviewed proposal and its matching request until
        // a replacement succeeds. A failed/cancelled follow-up loses no work.
        pendingRequest = input; lastRequestHistoryID = input.id
        errorMessage = nil; notice = nil; noticeKind = .info
        let retainedInstructions = nextUserInstructions(followUp: followUp)
        let token = UUID(); generationID = token
        work = Task { @MainActor in
            defer { if generationID == token { work = nil; generationID = nil; pendingRequest = nil } }
            do {
                let result = try await service.generate(input)
                try Task.checkCancellation()
                guard generationID == token, input.options == options else { return }
                request = input; proposal = result
                requiresFreshProposal = false
                selectedActionIDs = defaultSelection(result)
                let firstAction = orderedActions(result).first
                let previousAction = firstAction.map { $0.nextAction.isEmpty ? $0.text : $0.nextAction } ?? ""
                lastSummary = result.summary + "\n" + result.openQuestion + "\n" + result.clarification + "\n" + previousAction
                userInstructions = retainedInstructions
                instruction = ""; feedbackSaved = nil; memoryConfirmed = false; showSupplement = false
            } catch {
                guard generationID == token, !Task.isCancelled else { return }
                presentError(error)
            }
        }
    }

    private func adopt(_ proposal: IntelligenceProposal) {
        guard let request else { return }
        do {
            let receipt = try store.applyIntelligenceProposal(proposal, request: request, selectedActionIDs: selectedActionIDs)
            receiptID = receipt.id; adoptedRequestID = proposal.requestID
            noticeKind = .success; notice = "已保存选中的操作。"; errorMessage = nil
            if let action = proposal.actions.first(where: { $0.kind == .updateTaskNextStep && selectedActionIDs.contains($0.id) }) {
                onAdoptNextStep?(action)
            }
        } catch { presentError(error) }
    }

    private func presentError(_ error: Error) {
        notice = nil; noticeKind = .info
        errorOffersConnectionSettings = false
        if let issue = error as? IntelligenceError {
            switch issue {
            case .stale, .crossDayUndo, .alreadyApplied: requiresFreshProposal = true
            default: break
            }
        }
        if case AIApplyError.stale = error { requiresFreshProposal = true }
        if case AIError.http(let code) = error {
            errorMessage = "服务返回 HTTP \(code)，本次未生成建议。"
            errorOffersConnectionSettings = true
        } else {
            errorMessage = error.localizedDescription
            errorOffersConnectionSettings = error is AILocalError || error is URLError
            if case AIError.invalidConfiguration = error { errorOffersConnectionSettings = true }
        }
    }

    private var memoryIdeaID: UUID? {
        if intent == .idea { return subjectID }
        if intent == .unblock { return request?.evidence.first(where: { $0.kind == "idea" })?.id }
        return nil
    }

    private func confirmMemory(_ text: String) {
        do {
            _ = try store.confirmPreference(text: text, ideaID: memoryIdeaID, source: "用户确认 AI 提议")
            memoryConfirmed = true; noticeKind = .success
            notice = options.includeMemories ? "偏好已保存在本地，当前参考范围已允许发送。" : "偏好已保存在本地；本服务尚未启用发送。"
        } catch { presentError(error) }
    }

    private func saveFeedback(_ kind: IntelligenceFeedbackKind) {
        guard let request, let proposal else { return }
        do {
            let actions = orderedActions(proposal)
            let action = actions.first(where: { selectedActionIDs.contains($0.id) }) ?? actions.first
            let taskID = intent == .unblock ? subjectID : (intent == .today ? action?.sourceID : nil)
            try store.recordIntelligenceFeedback(requestID: request.id, actionID: action?.id,
                ideaID: memoryIdeaID, sourceTaskID: taskID, kind: kind)
            feedbackSaved = kind; noticeKind = .success; notice = "反馈已保存在本地。"
        } catch { presentError(error) }
    }
}


private enum IntelligenceConversationError: LocalizedError {
    case instructionsTooLong
    var errorDescription: String? {
        "本轮累计明确要求超过 1400 字。请精简补充，或点击「重新开始这一轮」后填写仍需保留的要求；没有截断或发送这些内容。"
    }
}
