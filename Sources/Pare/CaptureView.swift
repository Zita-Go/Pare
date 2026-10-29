import SwiftUI
import PareCore

/// The "随时状态" — open and a big cursor is already waiting. Type a line, press
/// Return, done. Below it: today's focus tasks with a one-tap check, and a way
/// into 整理. Frosted-glass backdrop keeps it feeling light and present.
struct CaptureView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var store: Store
    /// 由 PopoverController 注入：每次唤出面板 focusTick 递增，驱动输入框重新聚焦，
    /// 保证「打开即写」在第二次以后也成立（面板复用同一视图，onAppear 只触发一次）。
    @ObservedObject var chrome: PopoverController.Chrome
    var onOpenOrganize: () -> Void

    @State private var draft: String = ""
    @FocusState private var focused: Bool
    /// RFC-0001: 纯净模式 —— 收起「今日任务」列表，让弹窗回到"只管记下"的最聚焦形态。
    /// 用 @AppStorage 记住选择，跨弹窗开合与重启保持一致。
    @AppStorage("pareCapturePureMode") private var pureMode = false
    /// 保存成功时短暂显示确认边框，保持输入区域的位置和尺寸稳定。
    @State private var justCaptured = false
    @State private var captureMessage: String?
    @State private var captureFailed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            inputField
            if store.todayTasks.isEmpty {
                emptyToday
            } else {
                todaySection
            }
            footer
        }
        .padding(20)
        .frame(width: 372)
        .background(FrostBackground(tint: 0.90))
        // 每次唤出面板都聚焦输入框：focusTick 首次订阅即发一次、之后每次 show 递增。
        .onReceive(chrome.$focusTick) { _ in
            focused = true
            captureMessage = nil
        }
        .onReceive(chrome.$suggestedDraft) { value in
            guard let value else { return }
            draft = value
            focused = true
            DispatchQueue.main.async { chrome.suggestedDraft = nil }
        }
    }

    // MARK: Pieces

    private var header: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text("随手记").font(Theme.Typography.title).foregroundColor(Theme.text)
                Spacer()
                Text(PareFormat.today()).font(Theme.font(11)).foregroundColor(Theme.textDim)
            }
            Text(progressString)
                .font(Theme.Typography.body)
                .foregroundColor(Theme.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if store.todayTotal > 0 {
                ProgressBar(fraction: Double(store.todayCompleted) / Double(max(1, store.todayTotal)))
                    .animation(Theme.motion(reduceMotion, duration: 0.24), value: store.todayCompleted)
            }
        }
    }

    private var inputField: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("此刻在想什么？记下就好。", text: $draft)
                .textFieldStyle(.plain)
                .font(Theme.Typography.section)
                .foregroundColor(Theme.text)
                .tint(Theme.amber)
                .focused($focused)
                .onSubmit(submit)
                .onChange(of: draft) { value in
                    if !value.isEmpty { captureMessage = nil }
                }
                .padding(.horizontal, 13)
                .padding(.vertical, 12)
                .inputSurface(focused: focused)
                // Confirmation changes paint only, keeping the field and feedback row stable.
                .overlay(
                    RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                        .stroke(Theme.success, lineWidth: 1.5)
                        .opacity(justCaptured ? 1 : 0)
                        .allowsHitTesting(false)
                )
                .accessibilityLabel("随手记输入框")
                .accessibilityHint("输入一句话，回车记下")
            HStack(spacing: 5) {
                Image(systemName: captureMessage == nil ? "return" :
                      (captureFailed ? "exclamationmark.circle.fill" : "checkmark.circle.fill"))
                Text(captureMessage ?? "回车记下 · 点别处关闭")
                    .lineLimit(1)
            }
            .font(Theme.Typography.secondary)
            .foregroundColor(captureMessage == nil ? Theme.textTertiary :
                             (captureFailed ? Theme.error : Theme.success))
            .frame(minHeight: 16, alignment: .leading)
            .accessibilityElement(children: .combine)
            if let issue = store.persistenceIssue {
                FeedbackBanner(text: issue, kind: .error)
            }
        }
    }

    private var todaySection: some View {
        VStack(alignment: .leading, spacing: 7) {
            Button {
                withAnimation(Theme.motion(reduceMotion)) { pureMode.toggle() }
                chrome.resizeTick += 1
            } label: {
                HStack(spacing: 6) {
                    Text("今日任务")
                        .font(Theme.Typography.secondary)
                    Text("\(store.todayTasks.count)")
                        .font(Theme.Typography.secondary)
                    Spacer()
                    Image(systemName: pureMode ? "chevron.down" : "chevron.up")
                        .font(.system(size: 9, weight: .bold))
                }
                .foregroundColor(Theme.textDim)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(pureMode ? "展开今日任务（\(store.todayTasks.count) 件）" : "收起今日任务，进入纯净模式")
            .accessibilityHint("纯净模式下只保留输入框，收起今日列表")

            if !pureMode {
                ScrollView {
                    LazyVStack(spacing: 3) {
                        ForEach(store.todayTasks) { task in
                            TodayRow(store: store, task: task)
                        }
                    }
                }
                .frame(maxHeight: 240)
                .transition(.opacity)
            }
        }
    }

    private var emptyToday: some View {
        Button(action: onOpenOrganize) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.textFaint)
                Text("今天还没挑事情 · 点这里打开「整理」选几件")
                    .font(Theme.font(12))
                    .foregroundColor(Theme.textDim)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("今天还没挑事情，点这里打开整理选几件")
        .accessibilityHint("打开整理窗口")
    }

    private var footer: some View {
        HStack(spacing: 12) {
            Button(action: onOpenOrganize) {
                HStack(spacing: 6) {
                    Image(systemName: "square.grid.2x2")
                    Text("整理")
                }
                .font(Theme.Typography.body)
            }
            .buttonStyle(SecondaryActionStyle())
            .accessibilityLabel("打开整理窗口")

            Spacer()

            if store.inboxCount > 0 {
                Button(action: onOpenOrganize) {
                    HStack(spacing: 5) {
                        Image(systemName: "tray.full").font(.system(size: 10))
                        Text("待整理").font(Theme.font(12, weight: .medium))
                        Text("\(store.inboxCount)")
                            .font(Theme.font(12, weight: .medium))
                            .contentTransition(.numericText())
                    }
                    .animation(Theme.motion(reduceMotion), value: store.inboxCount)
                }
                .buttonStyle(TertiaryActionStyle())
                .help("打开整理，处理待整理")
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("待整理 \(store.inboxCount) 条")
                .accessibilityHint("打开整理窗口")
            }
        }.padding(.top, 8)
            .overlay(alignment: .top) { Rectangle().fill(Theme.hairline).frame(height: 1) }
    }

    // MARK: Logic

    private var progressString: String {
        if store.todayTotal > 0 {
            var s = "今天 \(store.todayCompleted) / \(store.todayTotal)"
            if store.todayEstimatedMinutes > 0 {
                s += " · 约 " + DurationLabel.short(store.todayEstimatedMinutes)
            }
            return s
        }
        let done = store.completedTodayCount()
        return done > 0 ? "今天已完成 \(done) 件" : "把想法先放下来"
    }

    private func submit() {
        if let result = store.captureWithResult(draft) {
            captureFailed = false
            switch result {
            case .inbox: captureMessage = "已记下 · 待整理"
            case .reminder(let date): captureMessage = "已设置提醒 · \(ReminderFormat.short(date))"
            }
            draft = ""
            // Fade the confirmation border without shifting the field.
            justCaptured = true
            DispatchQueue.main.async {
                withAnimation(Theme.motion(reduceMotion, duration: 0.4)) { justCaptured = false }
            }
        } else if !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            captureFailed = true
            captureMessage = "未保存，输入已保留"
        }
        focused = true
    }
}

/// A single row in today's focus list. Estimate is shown read-only here; you
/// set it in the 整理 window. Completing a task offers a one-tap 实际用时 record
/// (RFC-0001 T5) so the quick popover closes the calibration loop too.
private struct TodayRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var store: Store
    let task: TaskItem
    /// RFC-0001 T5: 刚完成、等待记录实际用时时展开的就地 chip 提示。
    @State private var showActualPrompt = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                TaskCheckbox(completed: task.completed, label: task.text) { toggleComplete() }
                Text(task.text)
                    .font(Theme.Typography.body)
                    .foregroundColor(task.completed ? Theme.textDim : Theme.text)
                    .strikethrough(task.completed, color: Theme.textDim)
                    .lineLimit(2)
                Spacer(minLength: 0)
                if !task.completed, let scheduledAt = task.scheduledAt {
                    Label(ReminderFormat.short(scheduledAt), systemImage: "calendar")
                        .font(Theme.font(10, weight: .medium)).foregroundColor(Theme.amber)
                        .accessibilityLabel("安排时间 \(ReminderFormat.short(scheduledAt))")
                }
                if let minutes = task.estimatedMinutes {
                    Text(DurationLabel.short(minutes))
                        .font(Theme.font(11, weight: .medium))
                        .foregroundColor(Theme.textSecondary)
                }
                if task.completed {
                    actualControl
                } else if let d = task.remindAt {
                    HStack(spacing: 2) {
                        Image(systemName: "bell.fill").font(.system(size: 9))
                        Text(ReminderFormat.short(d)).font(Theme.font(10, weight: .medium))
                    }
                    .foregroundColor(Theme.amber.opacity(0.85))
                }
                TaskDetailsButton(store: store, task: task)
            }
            if !task.completed {
                HStack(spacing: 7) {
                    if let action = task.nextAction {
                        Text("下一步：\(action)").font(Theme.font(10)).foregroundColor(Theme.textDim).lineLimit(1)
                    }
                    if let due = task.dueAt { DueBadge(date: due) }
                    Spacer(minLength: 4)
                    FocusTimerButton(store: store, task: task, showsLabel: true,
                                     activeLabel: "暂停", showsCancel: false,
                                     asksCompletionOnStop: false)
                    QuickParallelRunButton(store: store, task: task)
                }
                .padding(.leading, 30)
            }
            if task.completed, showActualPrompt, task.actualMinutes == nil {
                CompletionTimeChips(
                    onPick: { store.setActual(task.id, minutes: $0); showActualPrompt = false },
                    onSkip: { showActualPrompt = false }
                )
                .padding(.leading, 30)
                .transition(.opacity)
            }
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 8)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.clear)
        )
        .animation(Theme.motion(reduceMotion), value: showActualPrompt)
    }

    /// 完成态尾部的实际用时控件：已记录→可改/清除；未记录且提示已收起→补记入口。
    @ViewBuilder private var actualControl: some View {
        if let actual = task.actualMinutes {
            ActualTimePill(minutes: actual) { store.setActual(task.id, minutes: $0) }
        } else if !showActualPrompt {
            RecordActualButton { withAnimation(Theme.motion(reduceMotion)) { showActualPrompt = true } }
        }
    }

    /// 打勾完成时顺手弹出用时提示；取消完成时收起。
    private func toggleComplete() {
        let willComplete = !task.completed
        store.toggleComplete(task.id)
        showActualPrompt = willComplete && task.actualMinutes == nil
    }
}

/// The capture panel is for immediate action: personal focus and work running
/// elsewhere both start or pause with one click. Scheduling a later check stays
/// in the larger organizer where there is room to explain it.
private struct QuickParallelRunButton: View {
    @ObservedObject var store: Store
    let task: TaskItem

    private var isRunning: Bool {
        store.parallelRuns.contains { $0.taskID == task.id }
    }

    private var focusActive: Bool {
        store.activeTimer?.taskID == task.id
    }

    var body: some View {
        Button {
            if isRunning {
                _ = store.pauseParallelRun(task.id)
            } else {
                _ = store.startParallelRun(task.id)
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: isRunning ? "pause.fill" : "arrow.triangle.2.circlepath")
                    .font(.system(size: 9, weight: .bold))
                Text(isRunning ? "暂停" : "并行")
                    .font(Theme.font(11, weight: .medium))
            }
            .foregroundColor(isRunning ? Theme.info : Theme.textSecondary)
            .padding(.horizontal, 8)
            .frame(minHeight: 28)
            .background(RoundedRectangle(cornerRadius: Theme.Radius.control)
                .fill(isRunning ? Theme.info.opacity(0.14) : Theme.surfaceRaised))
            .contentShape(Rectangle())
        }
        .contentShape(Rectangle())
        .buttonStyle(.plain)
        .disabled(task.completed || focusActive)
        .help(focusActive
              ? "这件事正在由你专注处理"
              : (isRunning ? "暂停并行进行" : "标记为并行进行，可与其它任务同时运行"))
        .accessibilityLabel(isRunning ? "暂停并行进行" : "开始并行进行")
        .fixedSize()
    }
}
