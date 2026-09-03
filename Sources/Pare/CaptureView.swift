import SwiftUI
import PareCore

/// The "随时状态" — open and a big cursor is already waiting. Type a line, press
/// Return, done. Below it: today's focus tasks with a one-tap check, and a way
/// into 整理. Frosted-glass backdrop keeps it feeling light and present.
struct CaptureView: View {
    @ObservedObject var store: Store
    var onOpenOrganize: () -> Void

    @State private var draft: String = ""
    @FocusState private var focused: Bool
    @State private var hoverOrganize = false
    /// RFC-0001: 纯净模式 —— 收起「今日任务」列表，让弹窗回到"只管记下"的最聚焦形态。
    /// 用 @AppStorage 记住选择，跨弹窗开合与重启保持一致。
    @AppStorage("pareCapturePureMode") private var pureMode = false

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
        .onAppear { focused = true }
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
        HStack(spacing: 8) {
            Image(systemName: "sparkles")
                .font(.system(size: 12))
                .foregroundColor(Theme.textFaint)
            Text("今天还没挑事情 · 打开「整理」选几件")
                .font(Theme.font(12))
                .foregroundColor(Theme.textDim)
        }
        .padding(.vertical, 8)
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
                        .fill(Theme.panel.opacity(hoverOrganize ? 0.9 : 0.55))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .stroke(hoverOrganize ? Theme.amber.opacity(0.6) : Theme.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
            .onHover { hoverOrganize = $0 }
            .animation(.easeOut(duration: 0.12), value: hoverOrganize)
            .accessibilityLabel("打开整理窗口")

            Spacer()

            if store.inboxCount > 0 {
                HStack(spacing: 5) {
                    Image(systemName: "tray.full").font(.system(size: 10))
                    Text("待整理 \(store.inboxCount)")
                        .font(Theme.font(12, weight: .medium))
                }
                .foregroundColor(Theme.amber)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(Capsule().fill(Theme.amberSoft))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("待整理 \(store.inboxCount) 条")
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
        if store.capture(draft) { draft = "" }
        focused = true
    }
}

/// A single row in today's focus list. Estimate is shown read-only here; you
/// set it in the 整理 window. Completing a task offers a one-tap 实际用时 record
/// (RFC-0001 T5) so the quick popover closes the calibration loop too.
private struct TodayRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @State private var hovering = false
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
                .fill(hovering ? Theme.panel.opacity(0.5) : Color.clear)
        )
        .onHover { hovering = $0 }
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
