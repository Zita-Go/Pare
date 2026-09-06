import AppKit
import SwiftUI
import UniformTypeIdentifiers
import PareCore

// MARK: - Task detail inspector

/// A consistent, always-visible way into task details. The quadrant rows stay
/// compact; editing, thinking, dates and recurrence live in one quiet popover.
struct TaskDetailsButton: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @State private var presented = false

    var body: some View {
        Button { presented = true } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(Theme.textFaint)
                .frame(width: 24, height: 22)
        }
        .buttonStyle(.plain)
        .help("编辑任务详情")
        .accessibilityLabel("编辑任务详情")
        .popover(isPresented: $presented, arrowEdge: .trailing) {
            TaskDetailView(store: store, task: task, isPresented: $presented)
        }
    }
}

private struct TaskDetailView: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @Binding var isPresented: Bool

    @State private var title: String
    @State private var nextAction: String
    @State private var definitionOfDone: String
    @State private var note: String
    @State private var quadrant: Quadrant
    @State private var isToday: Bool
    @State private var isDailyWin: Bool
    @State private var estimate: Int?
    @State private var hasDue: Bool
    @State private var dueAt: Date
    @State private var hasReminder: Bool
    @State private var remindAt: Date
    @State private var recurrence: RecurrenceRule?
    @State private var hasImplementationPlan: Bool
    @State private var implementationKind: ImplementationCueKind
    @State private var implementationAction: String
    @State private var cueTime: Date
    @State private var applicationBundleID: String
    @State private var applicationName: String
    @State private var showScheduling: Bool

    init(store: Store, task: TaskItem, isPresented: Binding<Bool>) {
        self.store = store
        self.task = task
        self._isPresented = isPresented
        _title = State(initialValue: task.text)
        _nextAction = State(initialValue: task.nextAction ?? "")
        _definitionOfDone = State(initialValue: task.definitionOfDone ?? "")
        _note = State(initialValue: task.note ?? "")
        _quadrant = State(initialValue: task.quadrant)
        _isToday = State(initialValue: task.isToday)
        _isDailyWin = State(initialValue: task.isDailyWin == true)
        _estimate = State(initialValue: task.estimatedMinutes)
        _hasDue = State(initialValue: task.dueAt != nil)
        _dueAt = State(initialValue: task.dueAt ?? Date().addingTimeInterval(3600))
        _hasReminder = State(initialValue: task.remindAt != nil)
        _remindAt = State(initialValue: task.remindAt ?? Date().addingTimeInterval(3600))
        _recurrence = State(initialValue: task.recurrence)
        let plan = task.implementationPlan
        _hasImplementationPlan = State(initialValue: plan != nil)
        _implementationKind = State(initialValue: plan?.kind ?? .time)
        _implementationAction = State(initialValue: plan?.action ?? task.nextAction ?? "")
        _cueTime = State(initialValue: Calendar.current.date(
            bySettingHour: plan?.hour ?? 9, minute: plan?.minute ?? 0,
            second: 0, of: Date()) ?? Date())
        _applicationBundleID = State(initialValue: plan?.applicationBundleID ?? "")
        _applicationName = State(initialValue: plan?.applicationName ?? "")
        _showScheduling = State(initialValue: task.dueAt != nil || task.remindAt != nil)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                PageHeading(title: "任务详情", subtitle: "写清下一步，再决定什么时候做。")
                FocusTimerButton(store: store, task: task, showsLabel: true)
            }.padding(18)
            Divider().overlay(Theme.hairline)
            ScrollView {
            VStack(alignment: .leading, spacing: 13) {
                field("任务", text: $title, prompt: "要完成什么？")
                field("下一步", text: $nextAction, prompt: "最小、具体的下一步动作")
                field("完成标准", text: $definitionOfDone, prompt: "做到什么程度就可以结束？")

                VStack(alignment: .leading, spacing: 6) {
                    Text("思考与备注").font(Theme.font(11, weight: .medium)).foregroundColor(Theme.textDim)
                    TextEditor(text: $note)
                        .font(Theme.font(13))
                        .foregroundColor(Theme.text)
                        .scrollContentBackground(.hidden)
                        .padding(7)
                        .frame(height: 82)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.18)))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border, lineWidth: 1))
                }

                HStack {
                    Text("象限").font(Theme.font(12)).foregroundColor(Theme.textDim)
                    Spacer()
                    Picker("", selection: $quadrant) {
                        ForEach(Quadrant.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .labelsHidden().pickerStyle(.menu).fixedSize()
                }

                Toggle("加入今日计划", isOn: $isToday)
                    .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
                    .onChange(of: isToday) { value in if !value { isDailyWin = false } }
                Toggle("今天只赢这一件", isOn: $isDailyWin)
                    .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
                    .onChange(of: isDailyWin) { value in if value { isToday = true } }

                HStack {
                    Text("预估").font(Theme.font(12)).foregroundColor(Theme.textDim)
                    Spacer()
                    DurationPill(minutes: estimate) { estimate = $0 }
                    if task.actualMinutes != nil {
                        ActualTimePill(minutes: task.actualMinutes ?? 0) { store.setActual(task.id, minutes: $0) }
                    }
                }

                DisclosureGroup("日期与提醒", isExpanded: $showScheduling) {
                    dateSection.padding(.top, 10)
                }.font(Theme.font(13, weight: .medium)).foregroundColor(Theme.text)
                implementationSection
            }
            .padding(18)
            }
            Divider().overlay(Theme.hairline)
            HStack {
                    Button("删除", role: .destructive) {
                        isPresented = false
                        store.deleteTask(task.id)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(Theme.qUrgent)
                    Spacer()
                    Button("取消") { isPresented = false }.buttonStyle(SecondaryActionStyle())
                    Button("保存") { save() }
                        .buttonStyle(AIPrimaryButtonStyle())
                        .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                  || !implementationPlanIsValid)
            }.padding(16)
        }
        .frame(width: 420, height: 620)
        .background(Theme.bg)
    }

    private var dateSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            Toggle("截止日期", isOn: $hasDue)
                .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
            if hasDue {
                DatePicker("", selection: $dueAt, displayedComponents: [.date, .hourAndMinute])
                    .labelsHidden().datePickerStyle(.field)
            }
            Toggle("提醒我", isOn: $hasReminder)
                .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
            if hasReminder {
                HStack {
                    DatePicker("", selection: $remindAt, displayedComponents: [.date, .hourAndMinute])
                        .labelsHidden().datePickerStyle(.field)
                    if hasDue {
                        Button("截止时") { remindAt = dueAt }
                            .buttonStyle(.plain).font(Theme.font(11)).foregroundColor(Theme.amber)
                    }
                }
                HStack {
                    Text("重复").font(Theme.font(12)).foregroundColor(Theme.textDim)
                    Spacer()
                    Picker("", selection: $recurrence) {
                        Text("不重复").tag(nil as RecurrenceRule?)
                        ForEach(RecurrenceRule.allCases, id: \.self) { rule in
                            Text(rule.title).tag(rule as RecurrenceRule?)
                        }
                    }
                    .labelsHidden().pickerStyle(.menu).fixedSize()
                }
            }
        }
    }

    private var implementationSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            Toggle("如果—那么启动计划", isOn: $hasImplementationPlan)
                .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
            if hasImplementationPlan {
                HStack {
                    Text("如果").font(Theme.font(12)).foregroundColor(Theme.textDim)
                    Spacer()
                    Picker("", selection: $implementationKind) {
                        ForEach(ImplementationCueKind.allCases, id: \.self) { kind in
                            Text(kind.title).tag(kind)
                        }
                    }
                    .labelsHidden().pickerStyle(.menu).fixedSize()
                }
                if implementationKind == .time {
                    DatePicker("", selection: $cueTime, displayedComponents: .hourAndMinute)
                        .labelsHidden().datePickerStyle(.stepperField)
                } else if implementationKind == .openApplication {
                    Picker("选择当前运行的应用", selection: $applicationBundleID) {
                        Text("请选择").tag("")
                        ForEach(runningApplications, id: \.bundleID) { app in
                            Text(app.name).tag(app.bundleID)
                        }
                    }
                    .onChange(of: applicationBundleID) { id in
                        applicationName = runningApplications.first { $0.bundleID == id }?.name ?? applicationName
                    }
                }
                field("那么", text: $implementationAction,
                      prompt: "例如：打开脚本，先运行 10 个样本")
            }
        }
        .padding(.top, 2)
    }

    private var runningApplications: [(bundleID: String, name: String)] {
        var seen = Set<String>()
        return NSWorkspace.shared.runningApplications.compactMap { app in
            guard let id = app.bundleIdentifier, let name = app.localizedName,
                  id != Bundle.main.bundleIdentifier, seen.insert(id).inserted else { return nil }
            return (id, name)
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private func field(_ label: String, text: Binding<String>, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.textDim)
            TextField(prompt, text: text)
                .textFieldStyle(.plain).font(Theme.font(13)).foregroundColor(Theme.text)
                .padding(.horizontal, 9).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.18)))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border, lineWidth: 1))
        }
    }

    private func save() {
        let comps = Calendar.current.dateComponents([.hour, .minute], from: cueTime)
        let plan: ImplementationPlan? = hasImplementationPlan
            ? ImplementationPlan(kind: implementationKind,
                                 action: implementationAction.trimmingCharacters(in: .whitespacesAndNewlines),
                                 hour: implementationKind == .time ? comps.hour : nil,
                                 minute: implementationKind == .time ? comps.minute : nil,
                                 applicationBundleID: implementationKind == .openApplication ? applicationBundleID : nil,
                                 applicationName: implementationKind == .openApplication ? applicationName : nil)
            : nil
        store.updateTaskDetails(task.id, text: title, note: note, nextAction: nextAction,
                                dueAt: hasDue ? dueAt : nil,
                                remindAt: hasReminder ? remindAt : nil,
                                recurrence: hasReminder ? recurrence : nil,
                                definitionOfDone: definitionOfDone,
                                implementationPlan: plan,
                                isDailyWin: isDailyWin)
        if quadrant != task.quadrant { store.changeQuadrant(task.id, to: quadrant) }
        if isToday != task.isToday { store.setToday(task.id, isToday) }
        if estimate != task.estimatedMinutes { store.setEstimate(task.id, minutes: estimate) }
        isPresented = false
    }

    private var implementationPlanIsValid: Bool {
        guard hasImplementationPlan else { return true }
        guard !implementationAction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        return implementationKind != .openApplication || !applicationBundleID.isEmpty
    }
}

// MARK: - Focus timer

struct FocusTimerButton: View {
    @ObservedObject var store: Store
    let task: TaskItem
    var showsLabel = false
    @State private var askComplete = false
    @State private var sessionMinutes = 0

    private var isActive: Bool { store.activeTimer?.taskID == task.id }

    var body: some View {
        HStack(spacing: 3) {
            if isActive, showsLabel {
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    timerButton(now: context.date)
                }
            } else {
                timerButton(now: Date())
            }
            if isActive, showsLabel {
                Button { store.cancelTimer() } label: {
                    Image(systemName: "xmark").font(.system(size: 8, weight: .bold))
                        .foregroundColor(Theme.textDim).frame(width: 18, height: 18)
                        .background(Circle().fill(Theme.panelHi))
                }
                .buttonStyle(.plain)
                .help("放弃本次计时，不记录用时")
                .accessibilityLabel("放弃本次专注计时")
            }
        }
        .contextMenu {
            if isActive {
                Button("放弃本次计时", role: .destructive) { store.cancelTimer() }
            }
        }
        .popover(isPresented: $askComplete, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: 12) {
                Text("记录了 \(DurationLabel.short(sessionMinutes))")
                    .font(Theme.font(14, weight: .semibold)).foregroundColor(Theme.text)
                Text("这件事完成了吗？").font(Theme.font(12)).foregroundColor(Theme.textDim)
                HStack {
                    Button("还没") { askComplete = false }
                    Spacer()
                    Button("完成") { store.setCompleted(task.id, true); askComplete = false }
                }
                .buttonStyle(.plain).foregroundColor(Theme.amber)
            }
            .padding(14).frame(width: 230).background(Theme.bg)
        }
    }

    private func timerButton(now: Date) -> some View {
        Button { toggle(now: Date()) } label: {
            HStack(spacing: 4) {
                Image(systemName: isActive ? "stop.fill" : "play.fill")
                    .font(.system(size: 9, weight: .bold))
                if showsLabel {
                    Text(isActive ? DurationLabel.short(max(1, store.timerElapsedMinutes(now: now))) : "专注")
                        .font(Theme.font(11, weight: .medium))
                }
            }
            .foregroundColor(isActive ? Theme.bg : Theme.amber)
            .padding(.horizontal, showsLabel ? 8 : 6).padding(.vertical, 4)
            .background(Capsule().fill(isActive ? Theme.amber : Theme.amberSoft))
            .overlay(Capsule().stroke(Theme.amber.opacity(0.45), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(task.completed)
        .help(isActive
              ? (showsLabel ? "停止并记录实际用时" : "停止并记录实际用时；右键可放弃")
              : "开始专注计时")
        .accessibilityLabel(isActive ? "停止专注计时" : "开始专注计时")
    }

    private func toggle(now: Date) {
        if isActive {
            sessionMinutes = store.stopTimer(now: now) ?? 0
            askComplete = sessionMinutes > 0
        } else {
            _ = store.startTimer(task.id, now: now)
        }
    }
}

// MARK: - Today planner

struct TodayPlannerView: View {
    @ObservedObject var store: Store
    @State private var quickTask = ""
    @State private var rescueTaskID: UUID?
    @State private var rescuePreset: BlockerReason?

    private var incomplete: [TaskItem] { store.todayTasks.filter { !$0.completed } }
    private var completed: [TaskItem] { store.todayTasks.filter(\.completed) }
    private var keyTask: TaskItem? { store.dailyWinTask ?? incomplete.first }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            plannerHeader
            if store.todayTasks.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(spacing: 7) {
                        ForEach(Array(incomplete.enumerated()), id: \.element.id) { index, task in
                            TodayPlannerRow(store: store, task: task, index: index)
                        }
                        if !completed.isEmpty {
                            sectionLabel("已完成 · \(completed.count)")
                            ForEach(completed) { task in TodayPlannerRow(store: store, task: task, index: nil) }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            quickAdd
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .sheet(isPresented: rescuePresented) {
            if let id = rescueTaskID, let task = store.tasks.first(where: { $0.id == id }) {
                MomentumRescueView(store: store, task: task, preset: rescuePreset,
                                   isPresented: rescuePresented)
            }
        }
    }

    private var rescuePresented: Binding<Bool> {
        Binding(get: { rescueTaskID != nil }, set: { visible in
            if !visible { rescueTaskID = nil; rescuePreset = nil }
        })
    }

    private var plannerHeader: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 18) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 5) {
                        Image(systemName: store.dailyWinTask == nil ? "star" : "star.fill")
                        Text("今天只赢一件")
                    }
                    .font(Theme.font(11, weight: .semibold)).foregroundColor(Theme.amber)
                    Text(keyTask?.text ?? (store.todayTasks.isEmpty ? "先选一件值得推进的事" : "今天已经收完了"))
                    .font(Theme.font(18, weight: .semibold))
                    .foregroundColor(incomplete.isEmpty ? Theme.textDim : Theme.text)
                    .lineLimit(2)
                    if let task = keyTask, let action = task.nextAction {
                        Text("下一步：\(action)").font(Theme.font(12)).foregroundColor(Theme.textDim)
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text(remainingText)
                        .font(Theme.font(13, weight: .medium)).foregroundColor(Theme.amber)
                    Text("容量 \(DurationLabel.short(store.settings.todayCapacityMinutes))")
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                }
            }
            if let task = keyTask, !task.completed {
                HStack(spacing: 8) {
                    momentumButton("开始 5 分钟", icon: "play.fill") {
                        store.setDailyWin(task.id)
                        _ = store.startTimer(task.id, targetMinutes: 5)
                    }
                    momentumButton("拆小一步", icon: "arrow.down.right.and.arrow.up.left") {
                        rescuePreset = .tooLarge; rescueTaskID = task.id
                    }
                    momentumButton("我卡住了", icon: "questionmark.bubble") {
                        rescuePreset = nil; rescueTaskID = task.id
                    }
                    if store.dailyWinTask == nil {
                        Button("设为今日关键") { store.setDailyWin(task.id) }
                            .buttonStyle(.plain).font(Theme.font(11, weight: .medium))
                            .foregroundColor(Theme.textDim)
                    }
                }
            }
        }
        .padding(16).panel(corner: 14, accent: Theme.amber)
    }

    private func momentumButton(_ title: String, icon: String,
                                action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 10, weight: .semibold))
                Text(title).font(Theme.font(11, weight: .semibold))
            }
            .foregroundColor(Theme.amber)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(Capsule().fill(Theme.amberSoft))
            .overlay(Capsule().stroke(Theme.amber.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var remainingText: String {
        let minutes = store.todayRemainingEstimatedMinutes
        let unestimated = store.todayRemainingUnestimatedCount
        if minutes > 0, unestimated > 0 {
            return "剩余 \(incomplete.count) 件 · 约 \(DurationLabel.short(minutes)) + \(unestimated) 件未估时"
        }
        if minutes > 0 { return "剩余 \(incomplete.count) 件 · 约 \(DurationLabel.short(minutes))" }
        return "剩余 \(incomplete.count) 件 · \(unestimated) 件未估时"
    }

    private var emptyState: some View {
        VStack(spacing: 9) {
            Image(systemName: "sun.max").font(.system(size: 25)).foregroundColor(Theme.amber)
            Text("今天留白也很好").font(Theme.font(15, weight: .medium)).foregroundColor(Theme.text)
            Text("从四象限点太阳加入，或在下方直接写下一件事。")
                .font(Theme.font(12)).foregroundColor(Theme.textDim)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var quickAdd: some View {
        HStack(spacing: 8) {
            Image(systemName: "plus").foregroundColor(Theme.amber)
            TextField("直接加入今天（默认：重要 · 不紧急）", text: $quickTask)
                .textFieldStyle(.plain).font(Theme.font(13)).foregroundColor(Theme.text)
                .onSubmit {
                    _ = store.addTask(quickTask, to: .importantNotUrgent, addToToday: true)
                    quickTask = ""
                }
        }
        .padding(.horizontal, 11).padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.18)))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.border, lineWidth: 1))
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text).font(Theme.font(11, weight: .semibold)).foregroundColor(Theme.textDim)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
    }
}

private struct TodayPlannerRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    let index: Int?

    var body: some View {
        HStack(spacing: 10) {
            if let index {
                Image(systemName: "line.3.horizontal")
                    .foregroundColor(Theme.textFaint).frame(width: 14)
                Text("\(index + 1)").font(Theme.font(11, weight: .bold)).foregroundColor(Theme.textDim)
                    .frame(width: 18)
            }
            TaskCheckbox(completed: task.completed, label: task.text) { store.toggleComplete(task.id) }
            if !task.completed {
                Button { store.setDailyWin(task.isDailyWin == true ? nil : task.id) } label: {
                    Image(systemName: task.isDailyWin == true ? "star.fill" : "star")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(task.isDailyWin == true ? Theme.amber : Theme.textFaint)
                }
                .buttonStyle(.plain)
                .help(task.isDailyWin == true ? "取消今日关键任务" : "设为今日关键任务")
                .accessibilityLabel(task.isDailyWin == true ? "取消今日关键任务" : "设为今日关键任务")
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(task.text).font(Theme.font(14, weight: .medium))
                    .foregroundColor(task.completed ? Theme.textDim : Theme.text)
                    .strikethrough(task.completed).lineLimit(2)
                HStack(spacing: 7) {
                    Text(task.quadrant.title).font(Theme.font(10)).foregroundColor(Theme.accent(for: task.quadrant))
                    if let action = task.nextAction {
                        Text(action).font(Theme.font(10)).foregroundColor(Theme.textDim).lineLimit(1)
                    }
                    if let due = task.dueAt { DueBadge(date: due, completed: task.completed) }
                }
            }
            Spacer(minLength: 6)
            if let minutes = task.estimatedMinutes {
                Text(DurationLabel.short(minutes)).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.textDim)
            }
            if !task.completed { FocusTimerButton(store: store, task: task, showsLabel: true) }
            TaskDetailsButton(store: store, task: task)
        }
        .padding(.horizontal, 11).padding(.vertical, 9)
        .panel(corner: 11, accent: task.isToday ? Theme.amber : nil)
        .onDrag { NSItemProvider(object: task.id.uuidString as NSString) }
        .onDrop(of: [UTType.text], isTargeted: nil) { providers in
            guard let destination = index, let provider = providers.first else { return false }
            provider.loadObject(ofClass: NSString.self) { object, _ in
                guard let raw = object as? String, let id = UUID(uuidString: raw) else { return }
                DispatchQueue.main.async { store.moveToday(id, to: destination) }
            }
            return true
        }
    }
}

private struct MomentumRescueView: View {
    @ObservedObject var store: Store
    let task: TaskItem
    let preset: BlockerReason?
    @Binding var isPresented: Bool
    @State private var reason: BlockerReason?
    @State private var nextAction: String
    @State private var definitionOfDone: String
    @State private var showAI = false

    init(store: Store, task: TaskItem, preset: BlockerReason?, isPresented: Binding<Bool>) {
        self.store = store; self.task = task; self.preset = preset; self._isPresented = isPresented
        _reason = State(initialValue: preset)
        _nextAction = State(initialValue: task.nextAction ?? "")
        _definitionOfDone = State(initialValue: task.definitionOfDone ?? "")
    }

    private let columns = [GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(preset == .tooLarge ? "拆小一步" : "现在为什么不想推进？")
                .font(Theme.font(18, weight: .semibold)).foregroundColor(Theme.text)
            Text(task.text).font(Theme.font(13)).foregroundColor(Theme.amber).lineLimit(2)

            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(BlockerReason.allCases, id: \.self) { item in
                    Button { choose(item) } label: {
                        Text(item.title).font(Theme.font(12, weight: .medium))
                            .foregroundColor(reason == item ? Theme.bg : Theme.text)
                            .frame(maxWidth: .infinity).padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 8)
                                .fill(reason == item ? Theme.amber : Theme.panel))
                    }
                    .buttonStyle(.plain)
                }
            }

            if let reason {
                Text(helpText(reason)).font(Theme.font(11)).foregroundColor(Theme.textDim)
                    .fixedSize(horizontal: false, vertical: true)
            }

            compactField("最小下一步", text: $nextAction,
                         prompt: "一个 5–15 分钟内能直接做的动作")
            compactField("最低完成版本", text: $definitionOfDone,
                         prompt: "做到什么程度，今天就算有进展？")

            Button("✦ 帮我想一个起点") { showAI = true }
                .buttonStyle(.plain).font(Theme.font(12, weight: .medium)).foregroundColor(Theme.amber)
                .sheet(isPresented: $showAI) {
                    AIAssistantView(store: store, workflow: .nextStep, task: task,
                                    blocker: reason?.title ?? "") { item in
                        nextAction = item.nextAction
                        definitionOfDone = item.definitionOfDone
                    }
                }

            if reason == .lowEnergy, let suggestion = lowEnergySuggestion {
                Button("改做较轻的：\(suggestion.text)") {
                    store.recordBlocker(task.id, reason: .lowEnergy)
                    store.setDailyWin(suggestion.id)
                    isPresented = false
                }
                .buttonStyle(.plain).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.amber)
            }

            HStack {
                Button("取消") { isPresented = false }
                    .buttonStyle(.plain).foregroundColor(Theme.textDim)
                Spacer()
                Button("只保存") { save(start: false) }
                    .buttonStyle(.plain).foregroundColor(Theme.text)
                Button("保存并开始 5 分钟") { save(start: true) }
                    .buttonStyle(.plain).foregroundColor(Theme.bg)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 9).fill(Theme.amber))
            }
        }
        .padding(18).frame(width: 430).background(Theme.bg)
        .onAppear { if let preset { choose(preset) } }
    }

    private func compactField(_ label: String, text: Binding<String>, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(Theme.font(11, weight: .semibold)).foregroundColor(Theme.textDim)
            TextField(prompt, text: text).textFieldStyle(.plain).font(Theme.font(13))
                .foregroundColor(Theme.text).padding(.horizontal, 9).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 8).fill(Theme.panel.opacity(0.55)))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border.opacity(0.6)))
        }
    }

    private func choose(_ value: BlockerReason) {
        reason = value
        if nextAction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            switch value {
            case .unclear: nextAction = "打开相关文件，写下第一个需要回答的问题"
            case .tooLarge: nextAction = "先完成一个 5–15 分钟的小步骤"
            case .lowEnergy: nextAction = "只做最轻的一步，不要求完成全部"
            case .resistance: nextAction = "先尝试 5 分钟，允许做得不完美"
            }
        }
        if value == .resistance,
           definitionOfDone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            definitionOfDone = "完成一个可以交付的最低版本"
        }
    }

    private func helpText(_ value: BlockerReason) -> String {
        switch value {
        case .unclear: return "先把模糊任务变成一个可以点击、打开或写下的动作。"
        case .tooLarge: return "不拆完整计划，只找出现在能推进的一小步。"
        case .lowEnergy: return "降低任务强度，或换做今日列表中更短的一件。"
        case .resistance: return "把标准降到最低可接受版本，只承诺尝试五分钟。"
        }
    }

    private var lowEnergySuggestion: TaskItem? {
        store.todayTasks.filter { !$0.completed && $0.id != task.id }
            .sorted { ($0.estimatedMinutes ?? Int.max) < ($1.estimatedMinutes ?? Int.max) }
            .first
    }

    private func save(start: Bool) {
        store.setMomentumDetails(task.id, nextAction: nextAction, definitionOfDone: definitionOfDone)
        if let reason { store.recordBlocker(task.id, reason: reason) }
        store.setDailyWin(task.id)
        if start { _ = store.startTimer(task.id, targetMinutes: 5) }
        isPresented = false
    }
}

struct DueBadge: View {
    let date: Date
    var completed = false

    var body: some View {
        let overdue = !completed && date < Date()
        HStack(spacing: 3) {
            Image(systemName: overdue ? "exclamationmark.circle.fill" : "calendar")
            Text(Self.formatter.string(from: date))
        }
        .font(Theme.font(10, weight: .medium))
        .foregroundColor(overdue ? Theme.qUrgent : Theme.textDim)
        .accessibilityLabel(overdue ? "已逾期 \(Self.formatter.string(from: date))" : "截止 \(Self.formatter.string(from: date))")
    }

    private static let formatter: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "M/d HH:mm"; return f
    }()

    static func shortDate(_ date: Date) -> String { formatter.string(from: date) }
}

// MARK: - Search, ideas and history

enum LibraryScope: String, CaseIterable {
    case thoughts = "想法"
    case deadlines = "截止"
    case tasks = "任务"
    case history = "历史"
}

struct LibraryView: View {
    @ObservedObject var store: Store
    @State private var query = ""
    @State private var scope: LibraryScope = .thoughts

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageHeading(title: "想法与历史", subtitle: "留住想法，也看见已经推进的事情。")
            HStack(spacing: 10) {
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass").foregroundColor(Theme.textDim)
                    TextField("搜索想法、任务和历史", text: $query)
                        .textFieldStyle(.plain).font(Theme.font(13)).foregroundColor(Theme.text)
                }
                .padding(.horizontal, 10).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 9).fill(Color.black.opacity(0.18)))
                .overlay(RoundedRectangle(cornerRadius: 9).stroke(Theme.border, lineWidth: 1))
                SegmentedTabs(values: LibraryScope.allCases, selection: $scope, label: { $0.rawValue })
                    .frame(width: 240)
            }

            ScrollView {
                LazyVStack(spacing: 8) {
                    switch scope {
                    case .thoughts:
                        if filteredThoughts.isEmpty { empty("还没有想法。待整理内容可以点灯泡保留在这里。") }
                        ForEach(filteredThoughts) { ThoughtRow(store: store, thought: $0) }
                    case .deadlines:
                        DeadlineList(store: store, tasks: filteredDeadlines)
                    case .tasks:
                        if filteredTasks.isEmpty { empty("没有匹配的进行中任务。") }
                        ForEach(filteredTasks) { SearchTaskRow(store: store, task: $0) }
                    case .history:
                        if filteredArchive.isEmpty { empty("完成任务后，历史会出现在这里。") }
                        ForEach(filteredArchive) { ArchiveRow(store: store, task: $0) }
                    }
                }
                .padding(.vertical, 2)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var filteredThoughts: [ThoughtItem] {
        store.thoughts.filter { matches($0.text + " " + ($0.notes ?? "")) }
    }
    private var filteredTasks: [TaskItem] {
        store.tasks.filter { matches($0.text + " " + ($0.note ?? "") + " " + ($0.nextAction ?? "")) }
    }
    private var filteredDeadlines: [TaskItem] {
        store.tasks.filter { !$0.completed && $0.dueAt != nil
            && matches($0.text + " " + ($0.note ?? "") + " " + ($0.nextAction ?? "")) }
            .sorted { ($0.dueAt ?? .distantFuture) < ($1.dueAt ?? .distantFuture) }
    }
    private var filteredArchive: [TaskItem] {
        store.archive.reversed().filter { matches($0.text + " " + ($0.note ?? "")) }
    }
    private func matches(_ value: String) -> Bool {
        query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || value.localizedCaseInsensitiveContains(query)
    }
    private func empty(_ message: String) -> some View {
        Text(message).font(Theme.font(13)).foregroundColor(Theme.textDim)
            .frame(maxWidth: .infinity, minHeight: 180, alignment: .center)
    }
}

private struct DeadlineList: View {
    @ObservedObject var store: Store
    let tasks: [TaskItem]

    private var now: Date { Date() }
    private var endOfToday: Date {
        Calendar.current.date(byAdding: .day, value: 1,
                              to: Calendar.current.startOfDay(for: now)) ?? now
    }
    private var endOfWeek: Date {
        Calendar.current.date(byAdding: .day, value: 7, to: endOfToday) ?? endOfToday
    }
    private var overdue: [TaskItem] { tasks.filter { ($0.dueAt ?? .distantFuture) < now } }
    private var today: [TaskItem] { tasks.filter { ($0.dueAt ?? .distantFuture) >= now && ($0.dueAt ?? .distantFuture) < endOfToday } }
    private var upcoming: [TaskItem] { tasks.filter { ($0.dueAt ?? .distantFuture) >= endOfToday && ($0.dueAt ?? .distantFuture) < endOfWeek } }
    private var later: [TaskItem] { tasks.filter { ($0.dueAt ?? .distantFuture) >= endOfWeek } }

    var body: some View {
        if tasks.isEmpty {
            Text("没有匹配的截止任务。")
                .font(Theme.font(13)).foregroundColor(Theme.textDim)
                .frame(maxWidth: .infinity, minHeight: 180, alignment: .center)
        } else {
            section("已逾期", tasks: overdue, color: Theme.qUrgent)
            section("今天截止", tasks: today, color: Theme.amber)
            section("未来 7 天", tasks: upcoming, color: Theme.qFocus)
            section("更晚", tasks: later, color: Theme.textDim)
        }
    }

    @ViewBuilder private func section(_ title: String, tasks: [TaskItem], color: Color) -> some View {
        if !tasks.isEmpty {
            Text("\(title) · \(tasks.count)")
                .font(Theme.font(11, weight: .semibold)).foregroundColor(color)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 5)
            ForEach(tasks) { SearchTaskRow(store: store, task: $0) }
        }
    }
}

private struct SearchTaskRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    var body: some View {
        HStack(spacing: 9) {
            Circle().fill(Theme.accent(for: task.quadrant)).frame(width: 7, height: 7)
            Text(task.text).font(Theme.font(13)).foregroundColor(Theme.text).lineLimit(2)
            Spacer()
            if let due = task.dueAt { DueBadge(date: due) }
            TaskDetailsButton(store: store, task: task)
        }
        .padding(11).panel(corner: 10)
    }
}

private struct ArchiveRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "checkmark.circle.fill").foregroundColor(Theme.amber)
            VStack(alignment: .leading, spacing: 3) {
                Text(task.text).font(Theme.font(13)).foregroundColor(Theme.text).lineLimit(2)
                Text(task.completedAt.map { Self.formatter.string(from: $0) } ?? "已完成")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
            }
            Spacer()
            Button("恢复") { store.restoreArchived(task.id) }
                .buttonStyle(.plain).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.amber)
        }
        .padding(11).panel(corner: 10)
    }
    private static let formatter: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "yyyy/M/d HH:mm"; return f
    }()
}

private struct ThoughtRow: View {
    @ObservedObject var store: Store
    let thought: ThoughtItem
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "lightbulb.fill").foregroundColor(Theme.amber)
                VStack(alignment: .leading, spacing: 4) {
                    Text(thought.text).font(Theme.font(13, weight: .medium)).foregroundColor(Theme.text)
                    if let notes = thought.notes {
                        Text(notes).font(Theme.font(11)).foregroundColor(Theme.textDim).lineLimit(2)
                    }
                    if let reviewAt = thought.reviewAt {
                        HStack(spacing: 3) {
                            Image(systemName: reviewAt <= Date() ? "bell.fill" : "calendar.badge.clock")
                            Text("回看 \(DueBadge.shortDate(reviewAt))")
                        }
                        .font(Theme.font(10, weight: .medium))
                        .foregroundColor(reviewAt <= Date() ? Theme.qUrgent : Theme.amber)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 10)).foregroundColor(Theme.textFaint)
            }
            .padding(11).contentShape(Rectangle()).panel(corner: 10)
        }
        .buttonStyle(.plain)
        .popover(isPresented: $presented, arrowEdge: .trailing) {
            ThoughtEditor(store: store, thought: thought, isPresented: $presented)
        }
    }
}

private struct ThoughtEditor: View {
    @ObservedObject var store: Store
    let thought: ThoughtItem
    @Binding var isPresented: Bool
    @State private var title: String
    @State private var notes: String
    @State private var hasReview: Bool
    @State private var reviewAt: Date

    init(store: Store, thought: ThoughtItem, isPresented: Binding<Bool>) {
        self.store = store; self.thought = thought; self._isPresented = isPresented
        _title = State(initialValue: thought.text)
        _notes = State(initialValue: thought.notes ?? "")
        _hasReview = State(initialValue: thought.reviewAt != nil)
        _reviewAt = State(initialValue: thought.reviewAt ?? Date().addingTimeInterval(86_400))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            Text("继续想").font(Theme.font(16, weight: .semibold)).foregroundColor(Theme.text)
            TextField("这个想法是什么？", text: $title)
                .textFieldStyle(.plain).font(Theme.font(14)).foregroundColor(Theme.text)
                .padding(9).overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border))
            Text("可以写：我想达成什么？为什么重要？还缺什么证据？下一次何时再看？")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            TextEditor(text: $notes)
                .font(Theme.font(13)).foregroundColor(Theme.text).scrollContentBackground(.hidden)
                .padding(7).frame(height: 150)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.18)))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border))
            Toggle("安排下次回看", isOn: $hasReview)
                .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
            if hasReview {
                DatePicker("", selection: $reviewAt, displayedComponents: [.date, .hourAndMinute])
                    .labelsHidden().datePickerStyle(.field)
            }
            HStack {
                Button("删除", role: .destructive) { store.deleteThought(thought.id); isPresented = false }
                    .buttonStyle(.plain).foregroundColor(Theme.qUrgent)
                Button("转为任务") {
                    store.updateThought(thought.id, text: title, notes: notes,
                                        reviewAt: hasReview ? reviewAt : nil)
                    _ = store.promoteThought(thought.id)
                    isPresented = false
                }
                .buttonStyle(.plain).foregroundColor(Theme.amber)
                Spacer()
                Button("保存") {
                    store.updateThought(thought.id, text: title, notes: notes,
                                        reviewAt: hasReview ? reviewAt : nil)
                    isPresented = false
                }
                .buttonStyle(.plain).foregroundColor(Theme.bg)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 8).fill(Theme.amber))
            }
        }
        .padding(16).frame(width: 400).background(Theme.bg)
    }
}

// MARK: - Recoverable deletion feedback

struct UndoToast: View {
    @ObservedObject var store: Store
    var body: some View {
        if let message = store.undoMessage {
            HStack(spacing: 10) {
                Text(message).font(Theme.font(12)).foregroundColor(Theme.text)
                Button("撤销") { store.undoLastDeletion() }
                    .buttonStyle(.plain).font(Theme.font(12, weight: .semibold)).foregroundColor(Theme.amber)
                Button { store.clearUndo() } label: {
                    Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundColor(Theme.textDim)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 13).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.panelHi))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.amber.opacity(0.4)))
            .shadow(color: .black.opacity(0.3), radius: 12, y: 6)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
