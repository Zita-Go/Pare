import SwiftUI
import PareCore

struct AIConfigurationView: View {
    @ObservedObject private var service = AIService.shared
    @State private var config = AIService.shared.configuration
    @State private var key = ""
    @State private var message: String?
    @State private var test: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle("开启 AI 辅助（默认关闭）", isOn: $config.enabled)
                .toggleStyle(.switch).tint(Theme.amber)
            Text("本机服务可离线使用。配置云端地址后，选中的内容会发送到该服务商；每次发送前可预览。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            TextField("API 基础地址，例如 http://127.0.0.1:11434/v1", text: $config.baseURL)
            TextField("模型 ID（与本机服务列出的名称一致）", text: $config.model)
            SecureField("API Key：本机 Ollama 可留空；留空保留已存密钥", text: $key)
            Text("密钥存入 macOS 钥匙串，按 API 地址隔离，不进入任务备份。")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
            Toggle("请求严格 JSON Schema", isOn: $config.strictSchema)
            Text("Ollama / OpenAI 支持时开启；服务报不兼容时可关闭。关闭后仍会校验 JSON 和建议内容。")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
            Stepper("每日最多 \(config.dailyRequestLimit) 次请求", value: $config.dailyRequestLimit, in: 1...200)
            Stepper("单次输出上限 \(config.maxOutputTokens) tokens", value: $config.maxOutputTokens, in: 256...8000, step: 256)
            HStack {
                Button("保存 AI 设置") { save() }
                Button("删除此地址的密钥") {
                    do { try service.deleteKey(for: config); key = ""; message = "已删除此地址的密钥。" }
                    catch { message = error.localizedDescription }
                }
            }
            Button("用示例测试连接（发送一条请求）") { testConnection() }
                .disabled(!config.enabled)
            if let message { Text(message).font(Theme.font(11)).foregroundColor(Theme.amber) }
            Text("今日尝试 \(service.requestCount) 次。失败或取消的请求也计数；这个次数上限不等于服务商账单上限。")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
        }
        .font(Theme.font(12)).foregroundColor(Theme.text)
        .textFieldStyle(.roundedBorder)
        .disabled(service.isRunning)
        .overlay(alignment: .bottomTrailing) {
            if test != nil {
                Button("取消测试") { test?.cancel(); test = nil; message = "已取消。" }
                    .disabled(false)
            }
        }
        .onChange(of: config.baseURL) { _ in key = "" }
        .onAppear { service.refreshCount() }
        .onDisappear { test?.cancel() }
    }

    @discardableResult private func save() -> Bool {
        do { try service.save(config, newKey: key); key = ""; message = "已保存。"; return true }
        catch { message = error.localizedDescription; return false }
    }

    private func testConnection() {
        guard save() else { return }
        message = "正在发送虚构示例，没有发送你的任务…"
        let example = TaskItem(text: "写一份报告提纲", quadrant: .importantNotUrgent)
        let input = AIRequest(workflow: .nextStep, inputs: [AIInput(task: example)])
        test = Task { @MainActor in
            defer { test = nil }
            do {
                _ = try await service.generate(input)
                try Task.checkCancellation()
                message = "连接成功，已收到并验证模型回答。"
            } catch {
                if !Task.isCancelled { message = error.localizedDescription }
            }
        }
    }
}

struct AIAssistantView: View {
    @ObservedObject var store: Store
    let workflow: AIWorkflow
    let blocker: String
    var onAdoptNextStep: ((AISuggestion) -> Void)?
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var service = AIService.shared
    @State private var tasks: [TaskItem]
    @State private var inbox: [InboxItem]
    @State private var inputIDs: Set<UUID>
    @State private var selectedIDs = Set<UUID>()
    @State private var available = 30
    @State private var energy = "一般"
    @State private var includeNotes = false
    @State private var clarification = ""
    @State private var suggestions: [AISuggestion] = []
    @State private var summary = ""
    @State private var question = ""
    @State private var request: AIRequest?
    @State private var work: Task<Void, Never>?
    @State private var errorMessage: String?
    @State private var showSettings = false
    private let snapshotTime = Date()

    init(store: Store, workflow: AIWorkflow, task: TaskItem? = nil, blocker: String = "",
         onAdoptNextStep: ((AISuggestion) -> Void)? = nil) {
        self.store = store; self.workflow = workflow; self.blocker = blocker
        self.onAdoptNextStep = onAdoptNextStep
        let tasks = task.map { [$0] } ?? Array(store.tasks.filter { !$0.completed }.sorted {
            ($0.isToday ? 0 : 1, $0.quadrant.rawValue, $0.createdAt)
                < ($1.isToday ? 0 : 1, $1.quadrant.rawValue, $1.createdAt)
        }.prefix(60))
        let inbox = Array(store.inbox.prefix(60))
        _tasks = State(initialValue: tasks); _inbox = State(initialValue: inbox)
        _inputIDs = State(initialValue: Set((workflow == .organize ? inbox.map(\.id) : tasks.map(\.id)).prefix(20)))
    }

    private var inputs: [AIInput] {
        if workflow == .organize { return inbox.filter { inputIDs.contains($0.id) }.map(AIInput.init(inbox:)) }
        return tasks.filter { inputIDs.contains($0.id) }.map { AIInput(task: $0, includeNotes: includeNotes) }
    }

    private func makeRequest() -> AIRequest {
        AIRequest(workflow: workflow, inputs: inputs, availableMinutes: available, energy: energy,
                  blocker: blocker, clarification: clarification, now: snapshotTime)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(workflow.title).font(Theme.font(18, weight: .semibold))
                Spacer()
                Button("AI 设置") { showSettings = true }.disabled(work != nil)
            }
            Text("目标：\(service.configuration.baseURL)\n模型：\(service.configuration.model)")
                .font(Theme.font(11)).foregroundColor(Theme.textDim).textSelection(.enabled)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if suggestions.isEmpty {
                        inputControls
                        if !question.isEmpty {
                            Text(question).font(Theme.font(13, weight: .medium)).foregroundColor(Theme.amber)
                        }
                        TextField("补充说明 / 回答问题（最多 2000 字）", text: $clarification, axis: .vertical)
                            .lineLimit(2...4).textFieldStyle(.roundedBorder).disabled(work != nil)
                            .onChange(of: clarification) { _ in request = nil }
                        DisclosureGroup("查看将发送的内容（不含密钥）") {
                            Text(payload).font(.system(size: 10, design: .monospaced))
                                .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Text("仅发送上面的选中内容、时间、精力与说明，以及 Pare 的通用规划指令。不发送浏览器标题、历史或其它任务。")
                            .font(Theme.font(11)).foregroundColor(Theme.textDim)
                        Button(work == nil ? "发送选中内容，生成建议" : "正在生成…") { generate() }
                            .disabled(work != nil || service.isRunning || !service.configuration.enabled
                                      || inputIDs.isEmpty || inputIDs.count > 20 || clarification.count > 2000)
                        if !service.configuration.enabled {
                            Text("AI 尚未开启。先点右上角「AI 设置」，本机 Ollama 不需要密钥。")
                                .font(Theme.font(11)).foregroundColor(Theme.amber)
                        }
                    } else {
                        Text(summary).font(Theme.font(12)).foregroundColor(Theme.textDim)
                        ForEach($suggestions) { $item in
                            suggestionCard($item)
                        }
                        Text(adoptionDescription).font(Theme.font(11)).foregroundColor(Theme.textDim)
                    }
                }.padding(.trailing, 6)
            }
            if let errorMessage {
                Text(errorMessage).font(Theme.font(11)).foregroundColor(Theme.qUrgent)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button(work == nil ? "关闭" : "取消请求") {
                    work?.cancel()
                    if work == nil { dismiss() } else { work = nil }
                }
                Spacer()
                if !suggestions.isEmpty {
                    Button("丢弃建议") { suggestions = []; request = nil; errorMessage = nil }
                    Button("采纳选中建议") { adopt() }.disabled(selectedIDs.isEmpty)
                }
            }
        }
        .font(Theme.font(12)).foregroundColor(Theme.text)
        .padding(18).frame(width: 560, height: 610).background(Theme.bg)
        .onDisappear { work?.cancel() }
        .onChange(of: service.configuration) { _ in
            work?.cancel(); work = nil; suggestions = []; question = ""; request = nil
        }
        .sheet(isPresented: $showSettings) {
            VStack {
                ScrollView { AIConfigurationView().padding(18) }
                Button("返回") { showSettings = false }.padding(.bottom, 12)
            }.frame(width: 480, height: 580).background(Theme.bg)
        }
    }

    private var payload: String {
        guard let data = try? (request ?? makeRequest()).data() else { return "输入为空或超过限制，请调整选择。" }
        return String(decoding: data, as: UTF8.self)
    }

    private var inputControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("选择发送范围（\(inputIDs.count)/20；展示前 60 条候选）")
                .font(Theme.font(11, weight: .medium))
            let candidates = workflow == .organize ? inbox.map { ($0.id, $0.text) } : tasks.map { ($0.id, $0.text) }
            ScrollView {
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(candidates, id: \.0) { id, title in
                        Toggle(title, isOn: Binding(get: { inputIDs.contains(id) }, set: {
                            if $0 { inputIDs.insert(id) } else { inputIDs.remove(id) }
                            request = nil
                        }))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.frame(maxHeight: 125)
            if workflow != .organize {
                Toggle("附带选中任务的思考备注", isOn: $includeNotes)
                    .onChange(of: includeNotes) { _ in request = nil }
            }
            if workflow == .plan {
                Stepper("本次可用 \(available) 分钟", value: $available, in: 5...480, step: 5)
                    .onChange(of: available) { _ in request = nil }
                Picker("当前精力", selection: $energy) {
                    ForEach(["较低", "一般", "充足"], id: \.self) { Text($0).tag($0) }
                }.onChange(of: energy) { _ in request = nil }
            }
        }.disabled(work != nil)
    }

    private func suggestionCard(_ binding: Binding<AISuggestion>) -> some View {
        let item = binding.wrappedValue
        return VStack(alignment: .leading, spacing: 7) {
            Toggle(item.isKeyTask ? "今日重点：\(item.title)" : item.title,
                   isOn: Binding(get: { selectedIDs.contains(item.id) }, set: {
                       if $0 { selectedIDs.insert(item.id) } else { selectedIDs.remove(item.id) }
                   })).font(Theme.font(13, weight: .medium))
            if workflow == .organize {
                TextField("标题", text: binding.title).textFieldStyle(.roundedBorder)
                HStack {
                    Picker("保留为", selection: binding.kind) {
                        Text("任务").tag("task"); Text("想法").tag("thought")
                    }
                    if item.kind == "task" {
                        Picker("象限", selection: binding.quadrant) {
                            ForEach(Quadrant.allCases, id: \.self) { Text($0.title).tag($0.rawValue) }
                        }
                    }
                }
            }
            Text(item.reason).font(Theme.font(11)).foregroundColor(Theme.textDim)
            if item.kind == "task" {
                Text("\(workflow == .plan ? "本次推进" : "建议预估")：\(item.estimatedMinutes) 分钟")
                    .font(Theme.font(11)).foregroundColor(Theme.amber)
                TextField("最小下一步", text: binding.nextAction, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                TextField("最低完成版本", text: binding.definitionOfDone, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
            }
        }.padding(12).panel(corner: 10, accent: Theme.amber)
    }

    private var adoptionDescription: String {
        switch workflow {
        case .nextStep: return "只填入下一步和完成标准；你仍可编辑并决定是否开始。"
        case .organize: return "仅归位勾选的随手记，原文保存在备注；不会自动加入今天。"
        case .plan: return "勾选任务加入今天并更新下一步/标准；已安排的其它任务保留，原有估时不覆盖。本次预算不是整个今日清单的总预算。"
        }
    }

    private func generate() {
        let input = makeRequest()
        request = input; errorMessage = nil; question = ""
        work = Task { @MainActor in
            defer { work = nil }
            do {
                let draft = try await service.generate(input)
                try Task.checkCancellation()
                summary = draft.summary; question = draft.question; suggestions = draft.suggestions
                selectedIDs = Set(draft.suggestions.map(\.id))
            } catch {
                if !Task.isCancelled { errorMessage = error.localizedDescription }
            }
        }
    }

    private func adopt() {
        guard let request else { return }
        let draft = AIDraft(summary: summary, suggestions: suggestions)
        do {
            try draft.validate(for: request)
            if workflow == .nextStep, let callback = onAdoptNextStep, let item = suggestions.first {
                guard store.tasks.first(where: { $0.id == item.id }) == tasks.first else { throw AIApplyError.stale }
                callback(item)
            } else {
                try store.applyAIDraft(draft, request: request, selectedIDs: selectedIDs,
                                       originalTasks: tasks, originalInbox: inbox)
            }
            dismiss()
        } catch { errorMessage = error.localizedDescription }
    }
}
