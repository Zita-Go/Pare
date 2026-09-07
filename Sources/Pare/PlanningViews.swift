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
    var showsLabel = false
    @State private var presented = false

    var body: some View {
        Button { presented = true } label: {
            if showsLabel {
                Label("检查", systemImage: "doc.text.magnifyingglass")
                    .font(Theme.font(11, weight: .medium)).foregroundColor(Theme.text)
                    .padding(.horizontal, 9).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Theme.panel.opacity(0.55)))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border.opacity(0.55)))
            } else {
                Image(systemName: "ellipsis")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Theme.textFaint)
                    .frame(width: 36, height: 32)
                    .contentShape(Rectangle())
            }
        }
        .contentShape(Rectangle())
        .buttonStyle(.plain)
        .help("编辑任务详情")
        .accessibilityLabel("编辑任务详情")
        .accessibilityHint("打开任务详情")
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
    @State private var hasSchedule: Bool
    @State private var scheduledAt: Date
    @State private var scheduleReminderLeadMinutes: Int?
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
        let parallelCheck = store.parallelRuns.first { $0.taskID == task.id }?.nextCheckAt
        _hasSchedule = State(initialValue: task.scheduledAt != nil || parallelCheck != nil)
        _scheduledAt = State(initialValue: task.scheduledAt ?? parallelCheck ?? Date().addingTimeInterval(3600))
        _scheduleReminderLeadMinutes = State(initialValue: task.scheduleReminderLeadMinutes ?? 5)
        let plan = task.implementationPlan
        _hasImplementationPlan = State(initialValue: plan != nil)
        _implementationKind = State(initialValue: plan?.kind ?? .time)
        _implementationAction = State(initialValue: plan?.action ?? task.nextAction ?? "")
        _cueTime = State(initialValue: Calendar.current.date(
            bySettingHour: plan?.hour ?? 9, minute: plan?.minute ?? 0,
            second: 0, of: Date()) ?? Date())
        _applicationBundleID = State(initialValue: plan?.applicationBundleID ?? "")
        _applicationName = State(initialValue: plan?.applicationName ?? "")
        _showScheduling = State(initialValue: task.dueAt != nil || task.remindAt != nil
                                || task.scheduledAt != nil || parallelCheck != nil)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                PageHeading(title: "任务详情", subtitle: "写清下一步，再决定由你专注或让它并行进行。")
                ParallelRunButton(store: store, task: task, showsLabel: true)
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
                    .onChange(of: isToday) { value in
                        if !value { isDailyWin = false; hasSchedule = false }
                    }
                Toggle("今天只赢这一件", isOn: $isDailyWin)
                    .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
                    .onChange(of: isDailyWin) { value in if value { isToday = true } }

                HStack {
                    Text("预估").font(Theme.font(12)).foregroundColor(Theme.textDim)
                    Spacer()
                    let parallelMinutes = store.parallelRecordedMinutes(task.id)
                    if parallelMinutes > 0 {
                        InfoBadge(text: "进行累计 \(DurationLabel.short(parallelMinutes))",
                                  symbol: "arrow.triangle.2.circlepath", accent: Theme.qDelegate)
                    }
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
            Toggle(isParallelTask ? "安排检查时间" : "安排到今日时间轴", isOn: $hasSchedule)
                .toggleStyle(.switch).tint(Theme.amber).font(Theme.font(12))
                .onChange(of: hasSchedule) { value in if value { isToday = true } }
            if hasSchedule {
                DatePicker("", selection: $scheduledAt, displayedComponents: .hourAndMinute)
                    .labelsHidden().datePickerStyle(.field)
                if isParallelTask {
                    Text("并行任务会在时间轴显示为检查点，不占用个人时间。")
                        .font(Theme.font(10)).foregroundColor(Theme.textDim)
                } else {
                    HStack {
                        Text("开始提醒").font(Theme.font(12)).foregroundColor(Theme.textDim)
                        Spacer()
                        Picker("", selection: $scheduleReminderLeadMinutes) {
                            Text("关闭").tag(nil as Int?)
                            Text("准时").tag(0 as Int?)
                            Text("提前 5 分钟").tag(5 as Int?)
                            Text("提前 15 分钟").tag(15 as Int?)
                        }
                        .labelsHidden().pickerStyle(.menu).fixedSize()
                    }
                    Text("时间块使用上方预估时长；在时间轴拖动底边也可调整。")
                        .font(Theme.font(10)).foregroundColor(Theme.textDim)
                }
            }
            Divider().overlay(Theme.hairline)
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
        if estimate != task.estimatedMinutes { store.setEstimate(task.id, minutes: estimate) }
        if isParallelTask {
            store.setParallelCheck(task.id, at: hasSchedule ? scheduledAt : nil)
        } else if hasSchedule {
            store.scheduleTask(task.id, at: scheduledAt, durationMinutes: estimate,
                               reminderLeadMinutes: scheduleReminderLeadMinutes)
            store.setScheduleReminder(task.id, leadMinutes: scheduleReminderLeadMinutes)
        } else if task.scheduledAt != nil {
            store.unscheduleTask(task.id)
        }
        if isToday != task.isToday && !hasSchedule { store.setToday(task.id, isToday) }
        isPresented = false
    }

    private var isParallelTask: Bool {
        store.parallelRuns.contains { $0.taskID == task.id }
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
    var activeLabel: String? = nil
    var showsCancel = true
    var asksCompletionOnStop = true
    @State private var askComplete = false
    @State private var sessionMinutes = 0

    private var isActive: Bool { store.activeTimer?.taskID == task.id }
    private var isParallel: Bool { store.parallelRuns.contains { $0.taskID == task.id } }

    var body: some View {
        HStack(spacing: 3) {
            if isActive, showsLabel, showsCancel {
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
        .fixedSize()
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
                Image(systemName: isActive ? (activeLabel == nil ? "stop.fill" : "pause.fill") : "play.fill")
                    .font(.system(size: 9, weight: .bold))
                if showsLabel {
                    Text(isActive
                         ? (activeLabel ?? DurationLabel.short(max(1, store.timerElapsedMinutes(now: now))))
                         : "专注")
                        .font(Theme.font(11, weight: .medium))
                }
            }
            .foregroundColor(isActive ? Theme.bg : Theme.amber)
            .padding(.horizontal, showsLabel ? 8 : 6).padding(.vertical, 4)
            .background(Capsule().fill(isActive ? Theme.amber : Theme.amberSoft))
            .overlay(Capsule().stroke(Theme.amber.opacity(0.45), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(task.completed || isParallel)
        .help(isParallel ? "这件事正在并行进行；先暂停后再开始个人专注" : (isActive
              ? (activeLabel.map { "\($0)并记录实际用时" }
                 ?? (showsLabel ? "停止并记录实际用时" : "停止并记录实际用时；右键可放弃"))
              : "开始专注计时"))
        .accessibilityLabel(isActive ? "\(activeLabel ?? "停止")专注计时" : "开始专注计时")
    }

    private func toggle(now: Date) {
        if isActive {
            sessionMinutes = store.stopTimer(now: now) ?? 0
            askComplete = asksCompletionOnStop && sessionMinutes > 0
        } else {
            _ = store.startTimer(task.id, now: now)
        }
    }
}

// MARK: - Parallel task progress

/// Marks a task as progressing elsewhere. Multiple runs may coexist; their
/// wall-clock duration is deliberately separate from the user's focus time.
struct ParallelRunButton: View {
    @ObservedObject var store: Store
    let task: TaskItem
    var showsLabel = false

    private var run: ParallelTaskRun? { store.parallelRuns.first { $0.taskID == task.id } }
    private var focusActive: Bool { store.activeTimer?.taskID == task.id }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Menu {
                if run == nil {
                    Button("直接标记为进行中") { start(after: nil) }
                    Divider()
                    Text("提醒我检查")
                    ForEach([15, 30, 60, 120], id: \.self) { minutes in
                        Button("\(DurationLabel.short(minutes)) 后") { start(after: minutes) }
                    }
                } else {
                    Text("已进行 \(DurationLabel.short(max(1, store.parallelElapsedMinutes(task.id, now: context.date))))")
                    if let check = run?.nextCheckAt {
                        Text(check <= context.date ? "现在可以检查" : "下次检查：\(ReminderFormat.short(check, now: context.date))")
                    }
                    Divider()
                    Menu("调整检查时间") {
                        Button("不提醒") { store.setParallelCheck(task.id, at: nil) }
                        ForEach([15, 30, 60, 120], id: \.self) { minutes in
                            Button("\(DurationLabel.short(minutes)) 后") {
                                store.setParallelCheck(task.id,
                                    at: context.date.addingTimeInterval(Double(minutes) * 60), now: context.date)
                            }
                        }
                    }
                    Button("暂停进行") { _ = store.pauseParallelRun(task.id, now: context.date) }
                    Button("标记完成") { _ = store.completeParallelRun(task.id, now: context.date) }
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 9, weight: .bold))
                    if showsLabel {
                        Text(run == nil ? "进行" : DurationLabel.short(max(1, store.parallelElapsedMinutes(task.id, now: context.date))))
                            .font(Theme.font(11, weight: .medium))
                    }
                }
                .foregroundColor(run == nil ? Theme.qDelegate : Theme.bg)
                .padding(.horizontal, showsLabel ? 8 : 6).padding(.vertical, 4)
                .background(Capsule().fill(run == nil ? Theme.qDelegate.opacity(0.12) : Theme.qDelegate))
                .overlay(Capsule().stroke(Theme.qDelegate.opacity(0.55), lineWidth: 1))
            }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .disabled(task.completed || focusActive)
            .help(focusActive ? "这件事正在由你专注处理" :
                  (run == nil ? "标记为进行中；可与其它任务同时运行" : "管理进行状态和检查提醒"))
            .accessibilityLabel(run == nil ? "标记任务为进行中" : "管理进行中的任务")
        }
    }

    private func start(after minutes: Int?) {
        let now = Date()
        let check = minutes.map { now.addingTimeInterval(Double($0) * 60) }
        _ = store.startParallelRun(task.id, now: now, nextCheckAt: check)
    }
}

// MARK: - Today planner

private enum TodayPlannerMode: String, CaseIterable {
    case list = "清单"
    case timeline = "时间轴"
}

struct TodayPlannerView: View {
    @ObservedObject var store: Store
    @State private var quickTask = ""
    @State private var rescueTaskID: UUID?
    @State private var rescuePreset: BlockerReason?
    @State private var mode: TodayPlannerMode = .list

    private var runningIDs: Set<UUID> { Set(store.parallelRuns.map(\.taskID)) }
    private var running: [TaskItem] { store.parallelRunningTasks }
    private var incomplete: [TaskItem] { store.todayTasks.filter { !$0.completed && !runningIDs.contains($0.id) } }
    private var completed: [TaskItem] { store.todayTasks.filter(\.completed) }
    private var keyTask: TaskItem? {
        store.dailyWinTask ?? incomplete.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            modeSwitcher
            if mode == .timeline {
                DayTimelineView(store: store)
            } else {
                plannerHeader
                if store.todayTasks.isEmpty && running.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        LazyVStack(spacing: 7) {
                            if !running.isEmpty {
                                parallelSection
                            }
                            if !running.isEmpty && !incomplete.isEmpty {
                                sectionLabel("我来推进")
                            }
                            ForEach(Array(incomplete.enumerated()), id: \.element.id) { index, task in
                                TodayPlannerRow(store: store, task: task, index: index)
                                    .id("today-\(task.id.uuidString)")
                            }
                            if !completed.isEmpty {
                                sectionLabel("已完成 · \(completed.count)")
                                ForEach(completed) { task in
                                    TodayPlannerRow(store: store, task: task, index: nil)
                                        .id("completed-\(task.id.uuidString)")
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
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

    private var modeSwitcher: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(mode == .list ? "今天做什么" : "今天什么时候做")
                    .font(Theme.font(12, weight: .semibold)).foregroundColor(Theme.text)
                Text(mode == .list ? "选择重点并推进" : "把任务拖到时间轴；底边可调整时长")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
            }
            Spacer()
            SegmentedTabs(values: TodayPlannerMode.allCases, selection: $mode, label: { $0.rawValue })
                .frame(width: 164)
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
                    Text(keyTask?.text ?? headerEmptyTitle)
                    .font(Theme.font(18, weight: .semibold))
                    .foregroundColor(keyTask == nil ? Theme.textDim : Theme.text)
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
                if runningIDs.contains(task.id) {
                    HStack(spacing: 8) {
                        InfoBadge(text: "正在进行", symbol: "arrow.triangle.2.circlepath",
                                  accent: Theme.qDelegate)
                        ParallelRunButton(store: store, task: task, showsLabel: true)
                        TaskDetailsButton(store: store, task: task, showsLabel: true)
                    }
                } else {
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
        }
        .padding(16).panel(corner: 14, accent: Theme.amber)
    }

    private var headerEmptyTitle: String {
        if !running.isEmpty { return "有 \(running.count) 件事正在其它地方推进" }
        if !store.todayTasks.isEmpty && completed.count == store.todayTasks.count { return "今天已经收完了" }
        return "先选一件值得推进的事"
    }

    private var parallelSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("正在进行", systemImage: "arrow.triangle.2.circlepath")
                    .font(Theme.font(13, weight: .semibold)).foregroundColor(Theme.qDelegate)
                Text("\(running.count) 件可同时运行 · 不计入个人专注时长")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
                Spacer()
            }
            ForEach(Array(running.enumerated()), id: \.element.id) { offset, task in
                if offset > 0 { Divider().overlay(Theme.hairline) }
                ParallelRunRow(store: store, task: task)
                    .id("parallel-\(task.id.uuidString)")
            }
        }
        .padding(14).panel(corner: 12, accent: Theme.qDelegate)
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
        if incomplete.isEmpty {
            return running.isEmpty ? "剩余 0 件" : "进行中 \(running.count) 件"
        }
        let minutes = incomplete.reduce(0) { $0 + ($1.estimatedMinutes ?? 0) }
        let unestimated = incomplete.filter { $0.estimatedMinutes == nil }.count
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

private struct ParallelCheckItem: Identifiable {
    var id: UUID { task.id }
    let task: TaskItem
    let date: Date
}

/// A focused day planner rather than a second full calendar. Personal work
/// occupies time; tasks progressing elsewhere appear as lightweight check
/// points, so the two kinds of commitment never look interchangeable.
private struct DayTimelineView: View {
    @ObservedObject var store: Store

    private let startHour = 6
    private let endHour = 24
    private let minutesPerSlot = 15
    private let slotHeight: CGFloat = 24
    private let gutterWidth: CGFloat = 50

    private var calendar: Calendar { Calendar.current }
    private var dayStart: Date { calendar.startOfDay(for: Date()) }
    private var slotCount: Int { (endHour - startHour) * 60 / minutesPerSlot }
    private var contentHeight: CGFloat { CGFloat(slotCount) * slotHeight }
    private var runningIDs: Set<UUID> { Set(store.parallelRuns.map(\.taskID)) }

    private var scheduledTasks: [TaskItem] {
        store.todayTasks.filter { task in
            guard !task.completed, !runningIDs.contains(task.id), let date = task.scheduledAt else { return false }
            return calendar.isDate(date, inSameDayAs: dayStart)
        }
        .sorted { ($0.scheduledAt ?? .distantFuture) < ($1.scheduledAt ?? .distantFuture) }
    }

    private var parallelChecks: [ParallelCheckItem] {
        store.parallelRuns.compactMap { run in
            guard let date = run.nextCheckAt,
                  calendar.isDate(date, inSameDayAs: dayStart),
                  let task = store.tasks.first(where: { $0.id == run.taskID && !$0.completed }) else { return nil }
            return ParallelCheckItem(task: task, date: date)
        }
        .sorted { $0.date < $1.date }
    }

    private var unscheduledTasks: [TaskItem] {
        var seen = Set<UUID>()
        var result: [TaskItem] = []
        for task in store.todayTasks + store.parallelRunningTasks where !task.completed {
            guard seen.insert(task.id).inserted else { continue }
            if runningIDs.contains(task.id) {
                let check = store.parallelRuns.first { $0.taskID == task.id }?.nextCheckAt
                if let check {
                    if !calendar.isDate(check, inSameDayAs: dayStart) { result.append(task) }
                } else {
                    result.append(task)
                }
            } else if let scheduledAt = task.scheduledAt {
                if !calendar.isDate(scheduledAt, inSameDayAs: dayStart) { result.append(task) }
            } else {
                result.append(task)
            }
        }
        return result
    }

    private var conflictedCount: Int {
        scheduledTasks.filter { !store.scheduleConflicts(for: $0.id).isEmpty }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            timelineSummary
            unscheduledShelf
            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    ZStack(alignment: .topLeading) {
                        timeGrid
                        GeometryReader { geometry in
                            let available = max(160, geometry.size.width - gutterWidth - 8)
                            ForEach(scheduledTasks) { task in
                                let placement = lanePlacement(for: task)
                                let laneWidth = available / CGFloat(placement.count)
                                TimelineTaskBlock(store: store, task: task, slotHeight: slotHeight)
                                    .frame(width: max(96, laneWidth - 4), alignment: .leading)
                                    .offset(x: gutterWidth + CGFloat(placement.index) * laneWidth,
                                            y: yOffset(for: task.scheduledAt ?? dayStart))
                                    .zIndex(2)
                            }
                            ForEach(parallelChecks) { item in
                                TimelineParallelMarker(store: store, item: item)
                                    .frame(width: min(190, available * 0.46))
                                    .offset(x: geometry.size.width - min(190, available * 0.46) - 6,
                                            y: yOffset(for: item.date) - 13)
                                    .zIndex(4)
                            }
                            TimelineView(.periodic(from: .now, by: 60)) { context in
                                currentTimeLine(width: geometry.size.width, now: context.date)
                            }
                        }
                    }
                    .frame(height: contentHeight)
                    .onDrop(of: [UTType.text], delegate: TimelineDropDelegate(
                        store: store, dayStart: dayStart, startHour: startHour,
                        slotCount: slotCount, minutesPerSlot: minutesPerSlot,
                        slotHeight: slotHeight))
                }
                .onAppear {
                    let nowMinutes = calendar.component(.hour, from: Date()) * 60
                        + calendar.component(.minute, from: Date())
                    let slot = min(slotCount - 1, max(0, (nowMinutes - startHour * 60) / minutesPerSlot - 3))
                    DispatchQueue.main.async { proxy.scrollTo(slot, anchor: .top) }
                }
            }
            .panel(corner: 11)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var timelineSummary: some View {
        HStack(spacing: 8) {
            InfoBadge(text: "时间块 \(scheduledTasks.count)", symbol: "calendar.day.timeline.left", accent: Theme.amber)
            if !parallelChecks.isEmpty {
                InfoBadge(text: "检查点 \(parallelChecks.count)", symbol: "arrow.triangle.2.circlepath", accent: Theme.qDelegate)
            }
            if conflictedCount > 0 {
                InfoBadge(text: "\(conflictedCount) 项有冲突", symbol: "exclamationmark.triangle", accent: Theme.qUrgent)
            }
            Spacer()
            Text("06:00—24:00 · 15 分钟刻度")
                .font(Theme.font(10)).foregroundColor(Theme.textDim)
        }
    }

    private var unscheduledShelf: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("待安排").font(Theme.font(11, weight: .semibold)).foregroundColor(Theme.text)
                Text("拖到下方时间轴").font(Theme.font(10)).foregroundColor(Theme.textDim)
                Spacer()
            }
            if unscheduledTasks.isEmpty {
                Text("今天的任务都已有位置；拖回这里可取消安排。")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    .frame(maxWidth: .infinity, minHeight: 34, alignment: .leading)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 7) {
                        ForEach(unscheduledTasks) { task in
                            TimelineTaskChip(store: store, task: task, isParallel: runningIDs.contains(task.id))
                        }
                    }
                }
                .frame(minHeight: 34)
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.panel.opacity(0.38)))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.border.opacity(0.7)))
        .contentShape(Rectangle())
        .onDrop(of: [UTType.text], delegate: UnscheduledDropDelegate(store: store))
    }

    private var timeGrid: some View {
        VStack(spacing: 0) {
            ForEach(0..<slotCount, id: \.self) { slot in
                HStack(spacing: 0) {
                    Text(slot % 4 == 0 ? clockLabel(slot: slot) : "")
                        .font(Theme.font(9, weight: slot % 4 == 0 ? .medium : .regular))
                        .foregroundColor(Theme.textDim)
                        .frame(width: gutterWidth - 8, alignment: .trailing)
                        .padding(.trailing, 8)
                    Rectangle()
                        .fill(slot % 4 == 0 ? Theme.border.opacity(0.8) : Theme.hairline.opacity(0.45))
                        .frame(height: 1)
                }
                .frame(height: slotHeight, alignment: .top)
                .id(slot)
            }
        }
    }

    @ViewBuilder private func currentTimeLine(width: CGFloat, now: Date) -> some View {
        let minutes = calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now)
        if minutes >= startHour * 60 && minutes < endHour * 60 {
            HStack(spacing: 0) {
                Circle().fill(Theme.qUrgent).frame(width: 7, height: 7)
                Rectangle().fill(Theme.qUrgent.opacity(0.75)).frame(height: 1)
            }
            .frame(width: max(0, width - gutterWidth + 3))
            .offset(x: gutterWidth - 3,
                    y: CGFloat(minutes - startHour * 60) / CGFloat(minutesPerSlot) * slotHeight)
            .zIndex(6)
            .accessibilityHidden(true)
        }
    }

    private func clockLabel(slot: Int) -> String {
        let total = startHour * 60 + slot * minutesPerSlot
        return String(format: "%02d:%02d", total / 60, total % 60)
    }

    private func yOffset(for date: Date) -> CGFloat {
        let minutes = calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
        let raw = CGFloat(minutes - startHour * 60) / CGFloat(minutesPerSlot) * slotHeight
        return min(contentHeight - slotHeight, max(0, raw))
    }

    private func lanePlacement(for task: TaskItem) -> (index: Int, count: Int) {
        guard let start = task.scheduledAt else { return (0, 1) }
        let end = start.addingTimeInterval(Double(task.scheduledMinutes ?? task.estimatedMinutes ?? 30) * 60)
        let peers = scheduledTasks.filter { other in
            guard let otherStart = other.scheduledAt else { return false }
            let otherEnd = otherStart.addingTimeInterval(
                Double(other.scheduledMinutes ?? other.estimatedMinutes ?? 30) * 60)
            return start < otherEnd && otherStart < end
        }
        .sorted {
            if $0.scheduledAt == $1.scheduledAt { return $0.id.uuidString < $1.id.uuidString }
            return ($0.scheduledAt ?? .distantFuture) < ($1.scheduledAt ?? .distantFuture)
        }
        let count = min(3, max(1, peers.count))
        let index = peers.firstIndex(where: { $0.id == task.id }).map { $0 % count } ?? 0
        return (index, count)
    }
}

private struct TimelineTaskChip: View {
    @ObservedObject var store: Store
    let task: TaskItem
    let isParallel: Bool
    @State private var showPicker = false
    @State private var chosenTime = Self.nextQuarterHour()

    var body: some View {
        Button {
            chosenTime = Self.nextQuarterHour()
            showPicker = true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: isParallel ? "arrow.triangle.2.circlepath" : "circle.dashed")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(isParallel ? Theme.qDelegate : Theme.accent(for: task.quadrant))
                Text(task.text).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.text).lineLimit(1)
                if !isParallel, let minutes = task.estimatedMinutes {
                    Text(DurationLabel.short(minutes)).font(Theme.font(10)).foregroundColor(Theme.textDim)
                }
                Image(systemName: "clock").font(.system(size: 9)).foregroundColor(Theme.textDim)
            }
            .padding(.horizontal, 9).padding(.vertical, 7)
            .background(Capsule().fill(Theme.panelHi.opacity(0.8)))
            .overlay(Capsule().stroke((isParallel ? Theme.qDelegate : Theme.accent(for: task.quadrant)).opacity(0.45)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onDrag { NSItemProvider(object: task.id.uuidString as NSString) }
        .help(isParallel ? "点击选择检查时间，或拖到时间轴" : "点击选择开始时间，或拖到时间轴")
        .popover(isPresented: $showPicker, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: 12) {
                PageHeading(title: isParallel ? "安排检查" : "安排时间",
                            subtitle: isParallel ? "只设置回来看结果的时间，不占用个人时间。"
                                                 : "选择今天准备开始的时间。")
                DatePicker("时间", selection: $chosenTime, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.field)
                HStack {
                    Button("取消") { showPicker = false }.buttonStyle(SecondaryActionStyle())
                    Spacer()
                    Button("安排") {
                        if isParallel {
                            store.setParallelCheck(task.id, at: chosenTime)
                        } else {
                            store.scheduleTask(task.id, at: chosenTime)
                        }
                        showPicker = false
                    }
                    .buttonStyle(AIPrimaryButtonStyle())
                    .disabled(isParallel && chosenTime <= Date())
                }
            }
            .padding(16).frame(width: 280).background(Theme.bg)
        }
    }

    private static func nextQuarterHour(now: Date = Date()) -> Date {
        let calendar = Calendar.current
        let minute = calendar.component(.minute, from: now)
        let delta = 15 - minute % 15
        let shifted = calendar.date(byAdding: .minute, value: delta, to: now)
            ?? now.addingTimeInterval(15 * 60)
        var components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: shifted)
        components.second = 0
        return calendar.date(from: components) ?? shifted
    }
}

private struct TimelineTaskBlock: View {
    @ObservedObject var store: Store
    let task: TaskItem
    let slotHeight: CGFloat
    @State private var resizedMinutes: Int?
    @State private var showDetails = false

    private var baseMinutes: Int { task.scheduledMinutes ?? task.estimatedMinutes ?? 30 }
    private var effectiveMinutes: Int { resizedMinutes ?? baseMinutes }
    private var blockHeight: CGFloat {
        max(23, CGFloat(effectiveMinutes) / 15 * slotHeight - 2)
    }
    private var accent: Color {
        store.scheduleConflicts(for: task.id).isEmpty ? Theme.accent(for: task.quadrant) : Theme.qUrgent
    }
    private var isActive: Bool { store.activeTimer?.taskID == task.id }

    var body: some View {
        HStack(spacing: 6) {
            TaskCheckbox(completed: task.completed, label: task.text) { store.toggleComplete(task.id) }
                .scaleEffect(0.78)
                .frame(width: 17, height: 17)
            Text(task.text)
                .font(Theme.font(11, weight: .semibold)).foregroundColor(Theme.text)
                .lineLimit(effectiveMinutes >= 45 ? 2 : 1)
            Spacer(minLength: 2)
            if isActive {
                Image(systemName: "play.fill").font(.system(size: 8)).foregroundColor(Theme.amber)
            }
            if task.scheduleReminderLeadMinutes != nil {
                Image(systemName: "bell.fill").font(.system(size: 8)).foregroundColor(Theme.amber)
            }
            Text(DurationLabel.short(effectiveMinutes))
                .font(Theme.font(9, weight: .medium)).foregroundColor(Theme.textDim)
        }
        .padding(.horizontal, 7).padding(.vertical, 4)
        .frame(maxWidth: .infinity, minHeight: blockHeight, maxHeight: blockHeight, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 7).fill(accent.opacity(0.16)))
        .overlay(RoundedRectangle(cornerRadius: 7).stroke(accent.opacity(0.72), lineWidth: 1))
        .overlay(alignment: .bottom) {
            Capsule().fill(accent.opacity(0.75)).frame(width: 30, height: 4).padding(.bottom, 2)
                .contentShape(Rectangle().inset(by: -5))
                .gesture(DragGesture(minimumDistance: 2)
                    .onChanged { value in
                        let steps = Int((value.translation.height / slotHeight).rounded())
                        resizedMinutes = min(480, max(15, baseMinutes + steps * 15))
                    }
                    .onEnded { _ in
                        if let resizedMinutes { store.setScheduleDuration(task.id, minutes: resizedMinutes) }
                        resizedMinutes = nil
                    })
                .help("上下拖动调整时长")
        }
        .contentShape(Rectangle())
        .onDrag { NSItemProvider(object: task.id.uuidString as NSString) }
        .contextMenu {
            Button("开始专注") { _ = store.startTimer(task.id, targetMinutes: effectiveMinutes) }
            Button("编辑详情") { showDetails = true }
            Menu("开始提醒") {
                Button("关闭") { store.setScheduleReminder(task.id, leadMinutes: nil) }
                Button("准时") { store.setScheduleReminder(task.id, leadMinutes: 0) }
                Button("提前 5 分钟") { store.setScheduleReminder(task.id, leadMinutes: 5) }
                Button("提前 15 分钟") { store.setScheduleReminder(task.id, leadMinutes: 15) }
            }
            Divider()
            Button("取消安排") { store.unscheduleTask(task.id) }
        }
        .popover(isPresented: $showDetails, arrowEdge: .trailing) {
            TaskDetailView(store: store, task: task, isPresented: $showDetails)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("时间块：\(task.text)，\(DurationLabel.short(effectiveMinutes))")
        .accessibilityHint("拖动可改时间；拖动底边可改时长；右键可开始或取消安排")
    }
}

private struct TimelineParallelMarker: View {
    @ObservedObject var store: Store
    let item: ParallelCheckItem
    @State private var showDetails = false

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 9, weight: .bold))
            Text(Self.clock.string(from: item.date)).font(Theme.font(9, weight: .semibold))
            Text(item.task.text).font(Theme.font(10, weight: .medium)).lineLimit(1)
        }
        .foregroundColor(Theme.bg)
        .padding(.horizontal, 8).frame(height: 26)
        .background(Capsule().fill(Theme.qDelegate))
        .contentShape(Rectangle())
        .onDrag { NSItemProvider(object: item.task.id.uuidString as NSString) }
        .contextMenu {
            Button("编辑详情") { showDetails = true }
            Button("暂停进行") { _ = store.pauseParallelRun(item.task.id) }
            Button("取消检查时间") { store.setParallelCheck(item.task.id, at: nil) }
        }
        .popover(isPresented: $showDetails, arrowEdge: .trailing) {
            TaskDetailView(store: store, task: item.task, isPresented: $showDetails)
        }
        .accessibilityLabel("并行检查点：\(item.task.text)，\(Self.clock.string(from: item.date))")
    }

    private static let clock: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()
}

private struct TimelineDropDelegate: DropDelegate {
    let store: Store
    let dayStart: Date
    let startHour: Int
    let slotCount: Int
    let minutesPerSlot: Int
    let slotHeight: CGFloat

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        let slot = min(slotCount - 1, max(0, Int(floor(info.location.y / slotHeight))))
        guard let date = Calendar.current.date(byAdding: .minute,
                                               value: startHour * 60 + slot * minutesPerSlot,
                                               to: dayStart),
              let provider = info.itemProviders(for: [UTType.text]).first else { return false }
        loadTaskID(from: provider) { id in
            DispatchQueue.main.async {
                if store.parallelRuns.contains(where: { $0.taskID == id }) {
                    store.setParallelCheck(id, at: date)
                } else {
                    store.scheduleTask(id, at: date)
                }
            }
        }
        return true
    }
}

private struct UnscheduledDropDelegate: DropDelegate {
    let store: Store

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        guard let provider = info.itemProviders(for: [UTType.text]).first else { return false }
        loadTaskID(from: provider) { id in
            DispatchQueue.main.async {
                if store.parallelRuns.contains(where: { $0.taskID == id }) {
                    store.setParallelCheck(id, at: nil)
                } else {
                    store.unscheduleTask(id)
                }
            }
        }
        return true
    }
}

private func loadTaskID(from provider: NSItemProvider, completion: @escaping (UUID) -> Void) {
    provider.loadObject(ofClass: NSString.self) { object, _ in
        guard let raw = object as? String, let id = UUID(uuidString: raw) else { return }
        completion(id)
    }
}

private struct ParallelRunRow: View {
    @ObservedObject var store: Store
    let task: TaskItem

    private var run: ParallelTaskRun? { store.parallelRuns.first { $0.taskID == task.id } }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            HStack(spacing: 10) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .foregroundColor(Theme.qDelegate).frame(width: 18)
                VStack(alignment: .leading, spacing: 3) {
                    Text(task.text).font(Theme.font(13, weight: .medium)).foregroundColor(Theme.text).lineLimit(2)
                    if let check = run?.nextCheckAt {
                        Text(check <= context.date ? "现在可以检查" : "检查：\(ReminderFormat.short(check, now: context.date))")
                            .font(Theme.font(10, weight: check <= context.date ? .semibold : .regular))
                            .foregroundColor(check <= context.date ? Theme.amber : Theme.textDim)
                    } else {
                        Text("未设置检查提醒").font(Theme.font(10)).foregroundColor(Theme.textDim)
                    }
                }
                Spacer(minLength: 8)
                ParallelRunButton(store: store, task: task, showsLabel: true)
                Button("暂停") { _ = store.pauseParallelRun(task.id, now: context.date) }
                    .buttonStyle(SecondaryActionStyle())
                Button("完成") { _ = store.completeParallelRun(task.id, now: context.date) }
                    .buttonStyle(AIPrimaryButtonStyle())
                TaskDetailsButton(store: store, task: task, showsLabel: true)
            }
        }
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
                    if let scheduledAt = task.scheduledAt {
                        Label(ReminderFormat.short(scheduledAt), systemImage: "calendar")
                            .font(Theme.font(10, weight: .medium)).foregroundColor(Theme.amber)
                            .accessibilityLabel("安排时间 \(ReminderFormat.short(scheduledAt))")
                    }
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
            if !task.completed { ParallelRunButton(store: store, task: task, showsLabel: true) }
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
