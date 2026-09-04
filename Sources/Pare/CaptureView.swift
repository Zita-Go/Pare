import SwiftUI
import PareCore

/// The "随时状态" — open and a big cursor is already waiting. Type a line, press
/// Return, done. Below it: today's focus tasks with a one-tap check, and a way
/// into 整理. Frosted-glass backdrop keeps it feeling light and present.
struct CaptureView: View {
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
    /// 记下成功瞬间的确认脉冲：输入框描边闪一下琥珀微光再淡出，给"记进去了"的即时反馈。
    @State private var justCaptured = false

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
        .padding(18)
        .frame(width: 372)
        .background(FrostBackground(tint: 0.55))
        // 每次唤出面板都聚焦输入框：focusTick 首次订阅即发一次、之后每次 show 递增。
        .onReceive(chrome.$focusTick) { _ in focused = true }
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
            Text(PareFormat.today())
                .font(Theme.font(12))
                .foregroundColor(Theme.textDim)
            Text(progressString)
                .font(Theme.font(16, weight: .medium))
                .foregroundColor(Theme.amber)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if store.todayTotal > 0 {
                ProgressBar(fraction: Double(store.todayCompleted) / Double(max(1, store.todayTotal)))
                    .animation(.easeOut(duration: 0.35), value: store.todayCompleted)
            }
        }
    }

    private var inputField: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("此刻在想什么？记下就好。", text: $draft)
                .textFieldStyle(.plain)
                .font(Theme.font(17))
                .foregroundColor(Theme.text)
                .tint(Theme.amber)
                .focused($focused)
                .onSubmit(submit)
                .padding(.horizontal, 13)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Theme.panel.opacity(0.7)))
                .overlay(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .stroke(focused ? Theme.amber.opacity(0.9) : Theme.border, lineWidth: focused ? 1.6 : 1)
                )
                // 记下瞬间的确认脉冲：一圈琥珀描边突现，再放大淡出，只闪一次。
                .overlay(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .stroke(Theme.amber, lineWidth: 2)
                        .scaleEffect(justCaptured ? 1.0 : 1.03)
                        .opacity(justCaptured ? 0.9 : 0)
                )
                .shadow(color: Theme.amber.opacity(focused ? 0.18 : 0), radius: 8)
                .animation(.easeOut(duration: 0.15), value: focused)
                .accessibilityLabel("随手记输入框")
                .accessibilityHint("输入一句话，回车记下")
            Text("回车记下 · 点别处关闭")
                .font(Theme.font(11))
                .foregroundColor(Theme.textFaint)
        }
    }

    private var todaySection: some View {
        VStack(alignment: .leading, spacing: 7) {
            Button {
                withAnimation(.easeOut(duration: 0.18)) { pureMode.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text("今日任务")
                        .font(Theme.font(11, weight: .semibold))
                        .tracking(0.6)
                    Text("\(store.todayTasks.count)")
                        .font(Theme.font(11, weight: .semibold))
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
                .transition(.opacity.combined(with: .move(edge: .top)))
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
                .font(Theme.font(13, weight: .medium))
                .foregroundColor(Theme.text)
                .padding(.horizontal, 13)
                .padding(.vertical, 7)
                .background(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(Theme.panel.opacity(0.55))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .stroke(Theme.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
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
                    .foregroundColor(Theme.amber)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(Theme.amberSoft))
                    .animation(.spring(response: 0.35, dampingFraction: 0.75), value: store.inboxCount)
                }
                .buttonStyle(.plain)
                .help("打开整理，处理待整理")
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("待整理 \(store.inboxCount) 条")
                .accessibilityHint("打开整理窗口")
            }
        }
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
        if store.capture(draft) {
            draft = ""
            // 记下确认脉冲：先亮起，下个 runloop 再放大淡出（同 TaskCheckbox 的一次性手法）。
            justCaptured = true
            DispatchQueue.main.async {
                withAnimation(.easeOut(duration: 0.5)) { justCaptured = false }
            }
        }
        focused = true
    }
}

/// A single row in today's focus list. Estimate is shown read-only here; you
/// set it in the 整理 window. Completing a task offers a one-tap 实际用时 record
/// (RFC-0001 T5) so the quick popover closes the calibration loop too.
private struct TodayRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    /// RFC-0001 T5: 刚完成、等待记录实际用时时展开的就地 chip 提示。
    @State private var showActualPrompt = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                TaskCheckbox(completed: task.completed, label: task.text) { toggleComplete() }
                Text(task.text)
                    .font(Theme.font(14))
                    .foregroundColor(task.completed ? Theme.textDim : Theme.text)
                    .strikethrough(task.completed, color: Theme.textDim)
                    .lineLimit(2)
                Spacer(minLength: 0)
                if let minutes = task.estimatedMinutes {
                    Text(DurationLabel.short(minutes))
                        .font(Theme.font(11, weight: .medium))
                        .foregroundColor(task.completed ? Theme.textDim : Theme.amber.opacity(0.85))
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
                if !task.completed {
                    FocusTimerButton(store: store, task: task)
                }
                TaskDetailsButton(store: store, task: task)
            }
            if !task.completed, task.nextAction != nil || task.dueAt != nil {
                HStack(spacing: 7) {
                    if let action = task.nextAction {
                        Text("下一步：\(action)").font(Theme.font(10)).foregroundColor(Theme.textDim).lineLimit(1)
                    }
                    if let due = task.dueAt { DueBadge(date: due) }
                }
                .padding(.leading, 30)
            }
            if task.completed, showActualPrompt, task.actualMinutes == nil {
                CompletionTimeChips(
                    onPick: { store.setActual(task.id, minutes: $0); showActualPrompt = false },
                    onSkip: { showActualPrompt = false }
                )
                .padding(.leading, 30)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 8)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.clear)
        )
        .animation(.easeOut(duration: 0.15), value: showActualPrompt)
    }

    /// 完成态尾部的实际用时控件：已记录→可改/清除；未记录且提示已收起→补记入口。
    @ViewBuilder private var actualControl: some View {
        if let actual = task.actualMinutes {
            ActualTimePill(minutes: actual) { store.setActual(task.id, minutes: $0) }
        } else if !showActualPrompt {
            RecordActualButton { withAnimation(.easeOut(duration: 0.15)) { showActualPrompt = true } }
        }
    }

    /// 打勾完成时顺手弹出用时提示；取消完成时收起。
    private func toggleComplete() {
        let willComplete = !task.completed
        store.toggleComplete(task.id)
        showActualPrompt = willComplete && task.actualMinutes == nil
    }
}
