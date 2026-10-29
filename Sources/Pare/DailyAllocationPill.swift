import SwiftUI
import PareCore

/// Today's personal effort is independent from the whole task estimate.
struct DailyAllocationPill: View {
    @ObservedObject var store: Store
    let task: TaskItem
    @State private var presented = false
    @State private var error: String?
    @State private var selectedKind: DailyAllocationKind = .personal

    private var isParallel: Bool { store.parallelRuns.contains { $0.taskID == task.id } }
    private var scheduledMinutes: Int? {
        guard !isParallel, let at = task.scheduledAt, Calendar.current.isDateInToday(at) else { return nil }
        return task.scheduledMinutes
    }
    private var minutes: Int? {
        if let scheduledMinutes { return scheduledMinutes }
        if isParallel && allocation?.kind != .check { return nil }
        return allocation?.plannedMinutes
    }
    private var allocation: DailyAllocation? {
        store.dailyAllocations.first { $0.taskID == task.id && Calendar.current.isDateInToday($0.day) }
    }
    private var effectiveKind: DailyAllocationKind {
        isParallel ? .check : (allocation?.kind ?? .personal)
    }

    var body: some View {
        Button { selectedKind = effectiveKind; presented = true; error = nil } label: {
            Text((effectiveKind == .check ? "检查 " : "今天 ") + (minutes.map(DurationLabel.short) ?? "未定"))
                .font(Theme.font(11, weight: .medium)).foregroundColor(effectiveKind == .check ? Theme.info : Theme.textDim)
                .padding(.horizontal, 7).frame(minHeight: 26)
                .background(RoundedRectangle(cornerRadius: Theme.Radius.control).fill(Theme.surface))
                .contentShape(Rectangle())
        }
        .buttonStyle(CompactPillStyle()).fixedSize()
        .accessibilityLabel(effectiveKind == .check ? "设置今日检查投入" : "设置今日计划投入")
        .accessibilityValue(minutes.map(DurationLabel.short) ?? "未确定")
        .popover(isPresented: $presented) {
            VStack(alignment: .leading, spacing: 10) {
                Text(effectiveKind == .check ? "今天预计花多久检查？" : "今天准备投入多久？")
                    .font(Theme.font(13, weight: .medium))
                if let scheduledMinutes {
                    Text("已安排时间块 \(DurationLabel.short(scheduledMinutes))。请在时间轴调整，避免重复计算。")
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                } else {
                    if !isParallel {
                        Picker("投入类型", selection: $selectedKind) {
                            Text("个人行动").tag(DailyAllocationKind.personal)
                            Text("人工检查").tag(DailyAllocationKind.check)
                        }.pickerStyle(.segmented)
                    }
                    Text("只计算今天的投入，不改变整项任务估时。")
                        .font(Theme.font(11)).foregroundColor(Theme.textDim)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 6) {
                        ForEach(DurationPill.presets, id: \.self) { value in
                            Button(DurationLabel.short(value)) { save(value) }
                                .buttonStyle(SecondaryActionStyle())
                        }
                    }
                    Button("暂不确定") { save(nil) }.buttonStyle(TertiaryActionStyle())
                }
                if let error { FeedbackBanner(text: error, kind: .error) }
            }.padding(14).frame(width: 250).foregroundColor(Theme.text).background(Theme.bg, in: RoundedRectangle(cornerRadius: Theme.Radius.popover, style: .continuous))
        }
    }

    private func save(_ minutes: Int?) {
        do {
            try store.setDailyAllocation(taskID: task.id, plannedMinutes: minutes,
                                         kind: selectedKind)
            presented = false
        } catch { self.error = error.localizedDescription }
    }
}
