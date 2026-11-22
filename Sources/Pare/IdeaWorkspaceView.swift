import SwiftUI
import PareCore

/// A place to keep developing an idea without having to turn it into a task.
struct IdeaWorkspaceView: View {
    @ObservedObject var store: Store
    var onOpenGoals: () -> Void = {}
    @State private var newTitle = ""
    @State private var filter: IdeaFilter = .active
    @State private var selectedIdea: ThoughtItem?
    @State private var addError: String?
    @State private var showIntelligence = false
    @State private var intelligenceSubjectID: UUID?
    @State private var intelligenceIntent: IntelligenceIntent = .idea
    @FocusState private var newTitleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                PageHeading(title: "想法与项目", subtitle: "留下还在生长的念头，慢慢探索，再走出具体的一步。")
                Button("长期目标", action: onOpenGoals)
                    .buttonStyle(SecondaryActionStyle())
                    .accessibilityLabel("打开长期目标")
            }
            HStack(spacing: 8) {
                Image(systemName: "lightbulb").foregroundColor(Theme.amber)
                TextField("记下一个想法，按回车保存", text: $newTitle)
                    .textFieldStyle(.plain).font(Theme.font(13))
                    .focused($newTitleFocused)
                    .onSubmit(addIdea)
                    .accessibilityLabel("新想法标题")
                Button("记下", action: addIdea)
                    .buttonStyle(SecondaryActionStyle())
                    .disabled(newTitle.ideaTrimmed.isEmpty)
                    .accessibilityLabel("保存新想法")
            }
            .padding(10).inputSurface(focused: newTitleFocused)
            IntelligenceHintCard(store: store, includeToday: false) { intent, id in
                intelligenceIntent = intent; intelligenceSubjectID = id; showIntelligence = true
            }
            if let addError {
                FeedbackBanner(text: addError, kind: .error)
            }
            HStack {
                Picker("阶段", selection: $filter) {
                    ForEach(IdeaFilter.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.menu).frame(width: 170)
                .accessibilityLabel("筛选想法阶段")
                Spacer()
                Text("\(visibleIdeas.count) 个想法").font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            ScrollView {
                LazyVStack(spacing: 9) {
                    if visibleIdeas.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(store.thoughts.isEmpty ? "先留下一个念头" : "这个阶段还没有想法")
                                .font(Theme.font(15, weight: .medium)).foregroundColor(Theme.text)
                            Text(store.thoughts.isEmpty
                                 ? "一个产品方向、想研究的问题，或一件想慢慢推进的事。现在不用想好所有步骤。"
                                 : "可以切换阶段，或记下一个新的想法。")
                                .font(Theme.font(12)).foregroundColor(Theme.textDim)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(20).panel()
                    }
                    ForEach(visibleIdeas) { idea in
                        Button { selectedIdea = idea } label: { IdeaListCard(idea: idea) }
                            .buttonStyle(.plain)
                            .accessibilityLabel("打开想法：\(idea.text)，\(idea.stage.title)")
                    }
                }.padding(.vertical, 2)
            }
        }
        .foregroundColor(Theme.text).tint(Theme.amber)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .sheet(item: $selectedIdea) { IdeaDetailView(store: store, ideaID: $0.id) }
        .sheet(isPresented: $showIntelligence) {
            IntelligentAssistantView(store: store, intent: intelligenceIntent, subjectID: intelligenceSubjectID)
        }
    }

    private var visibleIdeas: [ThoughtItem] {
        store.thoughts.filter { filter.includes($0.stage) }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    private func addIdea() {
        guard !newTitle.ideaTrimmed.isEmpty else { return }
        guard store.addThought(newTitle) != nil else {
            addError = store.persistenceIssue ?? "想法还没有保存，请重试。输入已保留。"
            return
        }
        newTitle = ""
        addError = nil
        filter = .active
    }
}

private enum IdeaFilter: String, Identifiable, CaseIterable {
    case active, all, seed, exploring, building, parked, archived
    var id: Self { self }
    var title: String {
        switch self {
        case .active: return "活跃"
        case .all: return "全部阶段"
        case .seed: return IdeaStage.seed.title
        case .exploring: return IdeaStage.exploring.title
        case .building: return IdeaStage.building.title
        case .parked: return IdeaStage.parked.title
        case .archived: return IdeaStage.archived.title
        }
    }
    func includes(_ stage: IdeaStage) -> Bool {
        switch self {
        case .active: return stage != .parked && stage != .archived
        case .all: return true
        default: return rawValue == stage.rawValue
        }
    }
}

private struct IdeaListCard: View {
    let idea: ThoughtItem
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovered = false
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: idea.stage == .archived ? "archivebox" : "lightbulb")
                .font(.system(size: 16)).foregroundColor(Theme.textSecondary).padding(.top, 3)
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    Text(idea.text).font(Theme.font(15, weight: .medium))
                        .foregroundColor(Theme.text).lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    InfoBadge(text: idea.stage.title)
                }
                if let latest = idea.entries.max(by: { $0.createdAt < $1.createdAt }) {
                    Text("\(latest.author ?? latest.kind.title) · \(latest.text)")
                        .font(Theme.font(12)).foregroundColor(Theme.textDim).lineLimit(2)
                } else if let question = idea.nextQuestion {
                    Text("待探索 · \(question)")
                        .font(Theme.font(12)).foregroundColor(Theme.textDim).lineLimit(2)
                } else if let notes = idea.notes {
                    Text(notes).font(Theme.font(12)).foregroundColor(Theme.textDim).lineLimit(2)
                }
                HStack(spacing: 10) {
                    Text("更新于 \(idea.updatedAt.formatted(date: .abbreviated, time: .omitted))")
                    if idea.stage != .archived, let date = idea.reviewAt {
                        Label(date <= Date() ? "可以回看" : "回看 \(date.formatted(date: .abbreviated, time: .omitted))",
                              systemImage: "calendar.badge.clock")
                    }
                }.font(Theme.font(10)).foregroundColor(Theme.textFaint)
            }
            Image(systemName: "chevron.right").font(.system(size: 10))
                .foregroundColor(Theme.textFaint).padding(.top, 7)
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle()).panel(corner: Theme.Radius.card, highlighted: hovered)
        .onHover { hovered = $0 }
        .animation(Theme.motion(reduceMotion), value: hovered)
    }
}

struct IdeaDetailView: View {
    @ObservedObject var store: Store
    let ideaID: UUID
    let sourceTaskID: UUID?
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var notes: String
    @State private var nextQuestion: String
    @State private var stage: IdeaStage
    @State private var hasReviewDate: Bool
    @State private var reviewDate: Date
    @State private var metadataBaseline: IdeaMetadata?
    @State private var entryText = ""
    @State private var entryKind: IdeaEntryKind = .note
    @State private var actionTitle = ""
    @State private var addToToday = false
    @State private var showActionComposer = false
    @State private var showExistingTasks = false
    @State private var taskSearch = ""
    @State private var feedback: String?
    @State private var feedbackIsError = false
    @State private var confirmDiscard = false
    @State private var showIntelligence = false
    @State private var confirmDeletion = false
    @State private var showNotes = false
    @State private var showAllEntries = false
    @State private var showAllLinkedTasks = false
    @State private var expandedEntryIDs: Set<UUID> = []
    @FocusState private var focusedField: String?

    init(store: Store, ideaID: UUID, sourceTaskID: UUID? = nil) {
        self.store = store
        self.ideaID = ideaID
        self.sourceTaskID = sourceTaskID
        let idea = store.thoughts.first { $0.id == ideaID }
        _title = State(initialValue: idea?.text ?? "")
        _notes = State(initialValue: idea?.notes ?? "")
        _nextQuestion = State(initialValue: idea?.nextQuestion ?? "")
        _stage = State(initialValue: idea?.stage ?? .seed)
        _hasReviewDate = State(initialValue: idea?.reviewAt != nil)
        _reviewDate = State(initialValue: idea?.reviewAt
                           ?? Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date())
        _metadataBaseline = State(initialValue: idea.map(IdeaMetadata.init))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                PageHeading(title: "想法详情", subtitle: "让理解逐渐清晰，也把探索过程留下来。")
                Button { showIntelligence = true } label: {
                    Label("接着想一想", systemImage: "sparkles")
                }
                .buttonStyle(SecondaryActionStyle())
                .disabled(hasDrafts || idea == nil)
                .help(hasDrafts ? "请先保存当前更改或追加记录，让 AI 参考最新内容" : "参考这个想法和获准的记录，提出下一步探索")
                .accessibilityLabel("AI 接着想一想")
                Button(action: requestClose) {
                    Image(systemName: "xmark")
                }
                .buttonStyle(IconActionStyle())
                .accessibilityLabel("关闭想法详情")
            }.padding(20).background(Theme.surface.opacity(0.45))
            Rectangle().fill(Theme.separator).frame(height: 1)
            if metadataConflict, idea != nil {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("这个想法已在别处更新，当前草稿仍保留。")
                        Text("载入最新内容会替换当前想法与回看草稿，待追加的记录与行动会保留。")
                            .foregroundColor(Theme.textDim)
                    }.font(Theme.font(11)).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button("载入最新内容", action: loadLatestMetadata)
                        .accessibilityLabel("载入想法最新内容，保留待追加记录与行动")
                }.padding(.horizontal, 20).padding(.vertical, 12)
                    .background(Theme.amberSoft)
            }
            if let idea {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        currentIdeaSection
                        linkedActionsSection
                        notesSection
                        explorationSection(idea)
                        reviewSection
                        deletionSection
                    }.padding(20)
                }
            } else {
                Text("这个想法已不存在。").foregroundColor(Theme.textDim).padding(20)
                Spacer()
            }
            Rectangle().fill(Theme.separator).frame(height: 1)
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    if let feedback {
                        Label(feedback, systemImage: feedbackIsError ? "exclamationmark.circle" : "checkmark.circle")
                            .foregroundColor(feedbackIsError ? Theme.error : Theme.success)
                            .fixedSize(horizontal: false, vertical: true)
                        if metadataChanged {
                            Text("当前想法或回看设置仍有未保存的更改").foregroundColor(Theme.textDim)
                        }
                    } else {
                        Text(metadataChanged ? "当前想法或回看设置有未保存的更改" : "探索记录与关联行动会分别保存")
                            .foregroundColor(Theme.textDim)
                    }
                }.font(Theme.font(11)).frame(maxWidth: .infinity, alignment: .leading)
                Button("关闭", action: requestClose).keyboardShortcut(.cancelAction)
                    .buttonStyle(TertiaryActionStyle())
                Button("保存想法信息") { saveMetadata() }
                    .buttonStyle(AIPrimaryButtonStyle())
                    .disabled(idea == nil || title.ideaTrimmed.isEmpty || !metadataChanged || metadataConflict)
                    .accessibilityLabel("保存想法信息与回看设置，不含待追加记录或行动")
            }.padding(.horizontal, 20).padding(.vertical, 14)
                .background(Theme.surface.opacity(0.45))
        }
        .frame(width: 680, height: 600)
        .background(Theme.bg).foregroundColor(Theme.text)
        .font(Theme.font(13)).tint(Theme.amber)
        .buttonStyle(SecondaryActionStyle())
        .interactiveDismissDisabled(hasDrafts)
        .sheet(isPresented: $showIntelligence) {
            IntelligentAssistantView(store: store, intent: .idea, subjectID: ideaID)
        }
        .onAppear(perform: synchronizeMetadataIfClean)
        .onChange(of: liveMetadata) { _ in synchronizeMetadataIfClean() }
        .onChange(of: metadataChanged) { _ in synchronizeMetadataIfClean() }
        .alert("还有未保存的输入", isPresented: $confirmDiscard) {
            Button("继续编辑", role: .cancel) {}
            Button("放弃草稿并关闭", role: .destructive) { dismiss() }
        } message: {
            Text("当前想法的更改，以及尚未追加的记录或行动仍是草稿。已保存的内容会保留。")
        }
        .alert("删除这个想法？", isPresented: $confirmDeletion) {
            Button("取消", role: .cancel) {}
            Button("删除想法", role: .destructive, action: deleteIdea)
        } message: {
            Text("想法及探索记录将删除，关联任务不会删除。删除后可通过“撤销”恢复。当前未保存的草稿不会保留。")
        }
    }

    private var idea: ThoughtItem? { store.thoughts.first { $0.id == ideaID } }
    private var linkedTasks: [TaskItem] {
        let tasks = store.ideaLinkedTasks(ideaID)
        return tasks.filter { !$0.completed } + tasks.filter(\.completed)
    }
    private var visibleLinkedTasks: [TaskItem] {
        showAllLinkedTasks ? linkedTasks : Array(linkedTasks.prefix(3))
    }
    private var missingLinkedTaskCount: Int {
        Set(idea?.linkedTaskIDs ?? []).subtracting(Set(linkedTasks.map(\.id))).count
    }
    private var archivedTaskIDs: Set<UUID> { Set(store.archive.map(\.id)) }
    private var liveMetadata: IdeaMetadata? { idea.map(IdeaMetadata.init) }
    private var draftMetadata: IdeaMetadata {
        IdeaMetadata(title: title.ideaTrimmed, notes: notes.ideaTrimmed,
                     nextQuestion: nextQuestion.ideaTrimmed, stage: stage,
                     reviewAt: hasReviewDate ? reviewDate : nil)
    }
    private var metadataChanged: Bool {
        guard let metadataBaseline else { return false }
        return draftMetadata != metadataBaseline
    }
    private var metadataConflict: Bool {
        metadataChanged && liveMetadata != metadataBaseline
    }
    private var hasDrafts: Bool {
        metadataChanged || !entryText.ideaTrimmed.isEmpty || !actionTitle.ideaTrimmed.isEmpty
    }
    private var notesChanged: Bool {
        notes.ideaTrimmed != (metadataBaseline?.notes ?? "")
    }

    private var currentIdeaSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                sectionHeading("当前想法", symbol: "lightbulb")
                Spacer()
                Picker("当前阶段", selection: $stage) {
                    ForEach(IdeaStage.allCases, id: \.self) { Text($0.title).tag($0) }
                }.pickerStyle(.menu).frame(width: 180)
                    .accessibilityLabel("想法当前阶段")
            }
            if idea?.stage == .archived {
                HStack {
                    Text("想法已归档，内容和探索记录仍保留。")
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    Spacer()
                    Button("恢复到探索") { stage = .exploring; saveMetadata() }
                        .disabled(title.ideaTrimmed.isEmpty || metadataConflict)
                        .accessibilityLabel("恢复已归档的想法到探索阶段")
                }
            }
            TextField("想探索什么？", text: $title, axis: .vertical)
                .font(Theme.font(16, weight: .medium)).lineLimit(1...3)
                .textFieldStyle(.plain).focused($focusedField, equals: "title")
                .padding(10).inputSurface(focused: focusedField == "title")
                .accessibilityLabel("想法标题")
            fieldLabel("下一步待探索的问题")
            TextField("例如：先找谁聊一聊，能验证哪个假设？", text: $nextQuestion, axis: .vertical)
                .lineLimit(1...3).textFieldStyle(.plain).focused($focusedField, equals: "question")
                .padding(10).inputSurface(focused: focusedField == "question")
                .accessibilityLabel("下一步待探索的问题")
        }
    }

    private var notesSection: some View {
        DisclosureGroup(isExpanded: Binding(
            get: { showNotes || notesChanged },
            set: { showNotes = $0 }
        )) {
            TextEditor(text: $notes)
                .font(Theme.font(13)).scrollContentBackground(.hidden)
                .focused($focusedField, equals: "notes")
                .padding(7).frame(height: 90).inputSurface(focused: focusedField == "notes")
                .accessibilityLabel("想法描述")
                .padding(.top, 8)
        } label: {
            HStack {
                fieldLabel("描述")
                if notesChanged {
                    Text("未保存，保存后可收起")
                        .font(Theme.font(10)).foregroundColor(Theme.amber)
                } else if !showNotes, !notes.ideaTrimmed.isEmpty {
                    Text(notes).font(Theme.font(11)).foregroundColor(Theme.textDim).lineLimit(1)
                }
            }
        }
    }

    private func explorationSection(_ idea: ThoughtItem) -> some View {
        let entries = idea.entries.sorted { $0.createdAt > $1.createdAt }
        let visibleEntries = showAllEntries ? entries : Array(entries.prefix(3))
        return VStack(alignment: .leading, spacing: 10) {
            sectionHeading("探索记录", symbol: "text.bubble")
            Text("新发现和决定在这里单独追加保存，早先的记录会一直保留。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            TextField("今天多想清楚了什么？", text: $entryText, axis: .vertical)
                .lineLimit(2...5).textFieldStyle(.plain).focused($focusedField, equals: "entry")
                .padding(10).inputSurface(focused: focusedField == "entry")
                .accessibilityLabel("新的探索记录")
            HStack {
                Picker("类型", selection: $entryKind) {
                    ForEach(IdeaEntryKind.allCases, id: \.self) { Text($0.title).tag($0) }
                }.pickerStyle(.menu).frame(width: 160).accessibilityLabel("探索记录类型")
                Spacer()
                Button("保存并追加记录", action: appendEntry)
                    .disabled(entryText.ideaTrimmed.isEmpty)
                    .accessibilityLabel("保存并追加探索记录")
            }
            if idea.entries.isEmpty {
                Text("还没有记录。一个观察、一条线索，也值得留下。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
            } else {
                ForEach(visibleEntries) { entry in
                    let foldsText = entry.text.count > 140 || entry.text.split(whereSeparator: { $0.isNewline }).count > 3
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(entry.author.map { "\(entry.kind.title) · \($0)" } ?? entry.kind.title)
                                .foregroundColor(Theme.amber)
                            Spacer()
                            Text(entry.createdAt.formatted(date: .abbreviated, time: .shortened))
                                .foregroundColor(Theme.textFaint)
                            if foldsText {
                            Button(expandedEntryIDs.contains(entry.id) ? "收起" : "展开全文") {
                                if expandedEntryIDs.contains(entry.id) {
                                    expandedEntryIDs.remove(entry.id)
                                } else {
                                    expandedEntryIDs.insert(entry.id)
                                }
                            }
                            .buttonStyle(CompactPillStyle())
                            .accessibilityLabel(expandedEntryIDs.contains(entry.id)
                                                ? "收起这条探索记录" : "展开这条探索记录全文")
                            }
                        }.font(Theme.font(10, weight: .medium))
                        Text(entry.text).font(Theme.font(12)).lineSpacing(3)
                            .lineLimit(foldsText && !expandedEntryIDs.contains(entry.id) ? 3 : nil)
                            .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading).panel(corner: Theme.Radius.card)
                }
                if entries.count > 3 {
                    Button(showAllEntries ? "收起历史记录" : "查看全部 \(entries.count) 条记录") {
                        showAllEntries.toggle()
                    }
                    .accessibilityLabel(showAllEntries ? "收起历史记录，仅显示最近三条" : "查看全部 \(entries.count) 条探索记录")
                }
            }
        }
    }

    private var linkedActionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeading("关联行动", symbol: "arrow.turn.down.right")
            if linkedTasks.isEmpty && missingLinkedTaskCount == 0 {
                Text("想法可以先留着。准备好时，再创建一个具体行动。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            if missingLinkedTaskCount > 0 {
                Text("\(missingLinkedTaskCount) 个关联行动已删除或暂不可用，历史关联仍保留。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(visibleLinkedTasks) { task in linkedTaskRow(task) }
            if linkedTasks.count > 3 {
                Button(showAllLinkedTasks ? "收起关联行动" : "查看全部 \(linkedTasks.count) 个行动") {
                    showAllLinkedTasks.toggle()
                }
            }
            DisclosureGroup("创建一个具体行动", isExpanded: $showActionComposer) {
                VStack(alignment: .leading, spacing: 10) {
                    TextField("例如：约一位使用者聊 20 分钟", text: $actionTitle, axis: .vertical)
                        .lineLimit(1...3).textFieldStyle(.plain).focused($focusedField, equals: "action")
                        .padding(10).inputSurface(focused: focusedField == "action")
                        .accessibilityLabel("新关联行动的标题")
                    HStack {
                        Toggle("加入今天", isOn: $addToToday).toggleStyle(.checkbox)
                            .accessibilityLabel("将新关联行动加入今天")
                        Spacer()
                        Button("创建并关联", action: createAction)
                            .disabled(actionTitle.ideaTrimmed.isEmpty)
                            .accessibilityLabel("创建并关联具体行动")
                    }
                    Text("新行动放入“重要 · 不紧急”，原想法和探索记录会继续保留。")
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                }.padding(.top, 10)
            }
            DisclosureGroup("关联已有行动", isExpanded: $showExistingTasks) {
                VStack(alignment: .leading, spacing: 9) {
                    TextField("搜索现有或已归档的任务", text: $taskSearch)
                        .textFieldStyle(.plain).focused($focusedField, equals: "search")
                        .padding(10).inputSurface(focused: focusedField == "search")
                        .accessibilityLabel("搜索可关联的任务")
                    if availableTasks.isEmpty {
                        Text(taskSearch.ideaTrimmed.isEmpty ? "没有可关联的任务。" : "没有找到匹配的任务。")
                            .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 8) {
                                ForEach(availableTasks) { task in
                                    HStack(spacing: 10) {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(task.text).font(Theme.font(12)).lineLimit(2)
                                            Text(taskStatus(task)).font(Theme.font(10)).foregroundColor(Theme.textDim)
                                        }.frame(maxWidth: .infinity, alignment: .leading)
                                        Button("关联") { link(task) }
                                            .accessibilityLabel("关联任务：\(task.text)")
                                    }.padding(9).panel(corner: Theme.Radius.card)
                                }
                            }
                        }.frame(maxHeight: 180)
                    }
                }.padding(.top, 10)
            }
        }
    }

    private func linkedTaskRow(_ task: TaskItem) -> some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: archivedTaskIDs.contains(task.id) ? "archivebox" : task.completed ? "checkmark.circle" : "circle")
                .foregroundColor(task.completed ? Theme.success : Theme.textDim)
            VStack(alignment: .leading, spacing: 4) {
                Text(task.text).font(Theme.font(12)).lineLimit(3)
                Text(taskStatus(task)).font(Theme.font(10)).foregroundColor(Theme.textDim)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if task.id == sourceTaskID {
                Text("当前打开的任务").font(Theme.font(10)).foregroundColor(Theme.textDim)
            } else if !archivedTaskIDs.contains(task.id) {
                TaskDetailsButton(store: store, task: task, allowsIdeaNavigation: false)
            }
            Button("解除关联") { unlink(task) }
            .buttonStyle(CompactPillStyle()).font(Theme.font(11))
            .help("解除关联，保留任务")
            .accessibilityLabel("解除与任务“\(task.text)”的关联，保留任务")
        }.padding(10).panel(corner: Theme.Radius.card)
    }

    private var availableTasks: [TaskItem] {
        let linkedIDs = Set(idea?.linkedTaskIDs ?? [])
        var seen = Set<UUID>()
        return (store.tasks + store.archive).filter {
            !linkedIDs.contains($0.id) && seen.insert($0.id).inserted
                && (taskSearch.ideaTrimmed.isEmpty || $0.text.localizedStandardContains(taskSearch.ideaTrimmed))
        }
    }

    private var reviewSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeading("回看安排", symbol: "calendar.badge.clock")
            Toggle("选一天再回看", isOn: $hasReviewDate).toggleStyle(.checkbox)
                .accessibilityLabel("设置想法回看日期")
            if hasReviewDate {
                DatePicker("回看日期", selection: $reviewDate, displayedComponents: .date)
                    .accessibilityLabel("想法回看日期")
            }
            Text(stage == .archived
                 ? "归档后保留全部内容，也不再提示回看。选择其他阶段并保存，就能继续探索。"
                 : "回看是给自己的邀请，无需赶着完成。暂放的想法只在设定日期后提示回看。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var deletionSection: some View {
        HStack {
            Text("删除想法不会删除关联行动。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            Spacer()
            Button("删除想法") { confirmDeletion = true }
                .buttonStyle(.plain).font(Theme.font(11)).foregroundColor(Theme.qUrgent)
                .accessibilityLabel("删除想法，保留关联行动")
        }.padding(.top, 6)
    }

    private func sectionHeading(_ title: String, symbol: String) -> some View {
        Label(title, systemImage: symbol).font(Theme.font(14, weight: .semibold))
            .foregroundColor(Theme.text)
    }
    private func fieldLabel(_ title: String) -> some View {
        Text(title).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.textDim)
    }
    private func taskStatus(_ task: TaskItem) -> String {
        if archivedTaskIDs.contains(task.id) { return task.completed ? "已完成 · 已归档" : "已归档" }
        if task.completed { return "已完成" }
        return task.isToday ? "进行中 · 今天" : "进行中 · \(task.quadrant.title)"
    }
    private func requestClose() {
        if hasDrafts { confirmDiscard = true } else { dismiss() }
    }
    private func report(_ message: String, error: Bool = false) {
        feedback = message
        feedbackIsError = error
    }
    private func reportFailure(_ fallback: String) {
        report(store.persistenceIssue ?? fallback, error: true)
    }
    /// Entry/link changes are deliberately excluded: they should never turn a
    /// clean metadata form into a draft or cause an unrelated edit to conflict.
    private func synchronizeMetadataIfClean() {
        guard !metadataChanged, let latest = liveMetadata, latest != metadataBaseline else { return }
        applyMetadata(latest)
    }
    private func applyMetadata(_ metadata: IdeaMetadata) {
        title = metadata.title
        notes = metadata.notes
        nextQuestion = metadata.nextQuestion
        stage = metadata.stage
        hasReviewDate = metadata.reviewAt != nil
        if let date = metadata.reviewAt { reviewDate = date }
        metadataBaseline = metadata
    }
    private func loadLatestMetadata() {
        guard let latest = liveMetadata else {
            report("这个想法已不存在，当前草稿仍保留。", error: true)
            return
        }
        applyMetadata(latest)
        report("已载入最新内容，待追加记录与行动仍保留")
    }
    private func saveMetadata() {
        guard !title.ideaTrimmed.isEmpty else { return }
        // Check again at the action boundary, even if SwiftUI has not yet
        // delivered onChange after an update from another open editor.
        guard let latest = liveMetadata else {
            report("这个想法已不存在，当前草稿仍保留。", error: true)
            return
        }
        guard latest == metadataBaseline else {
            report("想法已有更新，请先载入最新内容。当前草稿仍保留。", error: true)
            return
        }
        guard store.updateIdea(ideaID, text: title, notes: notes, nextQuestion: nextQuestion,
                               stage: stage, reviewAt: hasReviewDate ? reviewDate : nil) else {
            reportFailure("更改还没有保存，草稿已保留，请重试。")
            return
        }
        if let saved = liveMetadata { applyMetadata(saved) }
        report("当前想法与回看设置已保存")
    }
    private func deleteIdea() {
        guard store.deleteThought(ideaID) else {
            reportFailure("想法没有删除，当前草稿仍保留，请重试。")
            return
        }
        dismiss()
    }
    private func appendEntry() {
        guard !entryText.ideaTrimmed.isEmpty else { return }
        guard store.addIdeaEntry(ideaID: ideaID, text: entryText, kind: entryKind) else {
            reportFailure("探索记录还没有保存，输入已保留，请重试。")
            return
        }
        entryText = ""
        report("探索记录已追加")
    }
    private func createAction() {
        guard !actionTitle.ideaTrimmed.isEmpty else { return }
        guard store.createTaskFromIdea(ideaID: ideaID, title: actionTitle,
                                       quadrant: .importantNotUrgent, addToToday: addToToday) != nil else {
            reportFailure("行动还没有创建，输入已保留，请重试。")
            return
        }
        actionTitle = ""
        addToToday = false
        report("行动已创建并关联")
    }
    private func link(_ task: TaskItem) {
        if store.linkTaskToIdea(ideaID: ideaID, taskID: task.id) {
            report("已关联任务")
        } else { reportFailure("没有完成关联，请重试。") }
    }
    private func unlink(_ task: TaskItem) {
        if store.unlinkTaskFromIdea(ideaID: ideaID, taskID: task.id) {
            report("已解除关联，任务仍保留")
        } else { reportFailure("没有解除关联，请重试。") }
    }
}

/// Only the fields edited together by the metadata form belong in its baseline.
private struct IdeaMetadata: Equatable {
    var title: String
    var notes: String
    var nextQuestion: String
    var stage: IdeaStage
    var reviewAt: Date?

    init(title: String, notes: String, nextQuestion: String, stage: IdeaStage, reviewAt: Date?) {
        self.title = title
        self.notes = notes
        self.nextQuestion = nextQuestion
        self.stage = stage
        self.reviewAt = reviewAt
    }

    init(_ idea: ThoughtItem) {
        self.init(title: idea.text, notes: idea.notes ?? "", nextQuestion: idea.nextQuestion ?? "",
                  stage: idea.stage, reviewAt: idea.reviewAt)
    }
}

private extension String {
    var ideaTrimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
