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
            Text("回车记下 · 点别处关闭")
                .font(Theme.font(11))
                .foregroundColor(Theme.textFaint)
        }
    }

    private var todaySection: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("今日任务")
                .font(Theme.font(11, weight: .semibold))
                .foregroundColor(Theme.textDim)
                .tracking(0.6)
            ScrollView {
                LazyVStack(spacing: 3) {
                    ForEach(store.todayTasks) { task in
                        TodayRow(store: store, task: task)
                    }
                }
            }
            .frame(maxHeight: 240)
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

/// A single row in today's focus list. Estimate + reminder are shown read-only
/// here; you set them in the 整理 window.
private struct TodayRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 10) {
            TaskCheckbox(completed: task.completed) { store.toggleComplete(task.id) }
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
            if let d = task.remindAt, !task.completed {
                HStack(spacing: 2) {
                    Image(systemName: "bell.fill").font(.system(size: 9))
                    Text(ReminderFormat.short(d)).font(Theme.font(10, weight: .medium))
                }
                .foregroundColor(Theme.amber.opacity(0.85))
            }
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 8)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(hovering ? Theme.panel.opacity(0.5) : Color.clear)
        )
        .onHover { hovering = $0 }
    }
}
