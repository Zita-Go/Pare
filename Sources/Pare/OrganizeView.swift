import SwiftUI
import UniformTypeIdentifiers
import PareCore

/// The "整理状态" — the four quadrants are the main act, each with its own quiet
/// color so you can read the map at a glance. Unprocessed captures sit in a
/// recessed tray on the left; drag each into a quadrant (or use its context
/// menu). Inside a quadrant you can complete, add to today, estimate duration,
/// set a reminder, or type a new task directly.
struct OrganizeView: View {
    @ObservedObject var store: Store

    var body: some View {
        HStack(spacing: 0) {
            InboxColumn(store: store)
                .frame(width: 288)
            Rectangle().fill(Theme.hairline).frame(width: 1)
            VStack(spacing: 14) {
                topBar
                quadrantGrid
            }
            .padding(18)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 820, minHeight: 560, alignment: .top)
        .padding(.top, 28)
        .background(FrostBackground(tint: 0.66))
    }

    private var topBar: some View {
        VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("整理")
                    .font(Theme.font(22, weight: .semibold))
                    .foregroundColor(Theme.text)
                Text(PareFormat.today())
                    .font(Theme.font(13))
                    .foregroundColor(Theme.textDim)
                Spacer()
                Text(progressString)
                    .font(Theme.font(14, weight: .medium))
                    .foregroundColor(Theme.amber)
            }
            if store.todayTotal > 0 {
                ProgressBar(fraction: Double(store.todayCompleted) / Double(max(1, store.todayTotal)))
                    .animation(.easeOut(duration: 0.35), value: store.todayCompleted)
            }
        }
    }

    private var quadrantGrid: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                QuadrantCard(store: store, quadrant: .importantUrgent)
                QuadrantCard(store: store, quadrant: .importantNotUrgent)
            }
            HStack(spacing: 12) {
                QuadrantCard(store: store, quadrant: .urgentNotImportant)
                QuadrantCard(store: store, quadrant: .neither)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var progressString: String {
        if store.todayTotal > 0 {
            var s = "今天 \(store.todayCompleted) / \(store.todayTotal)"
            if store.todayEstimatedMinutes > 0 {
                s += " · 约 " + DurationLabel.short(store.todayEstimatedMinutes)
            }
            return s
        }
        return "把想法归位，再挑今天要做的"
    }
}

// MARK: - Inbox column

private struct InboxColumn: View {
    @ObservedObject var store: Store

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("待整理")
                    .font(Theme.font(16, weight: .semibold))
                    .foregroundColor(Theme.text)
                Spacer()
                Text("\(store.inboxCount)")
                    .font(Theme.font(12, weight: .semibold))
                    .foregroundColor(Theme.amber)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(Theme.amberSoft))
            }
            Text("拖进右边的象限")
                .font(Theme.font(11))
                .foregroundColor(Theme.textDim)

            if store.inbox.isEmpty {
                Spacer()
                VStack(spacing: 8) {
                    Image(systemName: "checkmark.circle")
                        .font(.system(size: 22))
                        .foregroundColor(Theme.textFaint)
                    Text("都整理完了")
                        .font(Theme.font(13))
                        .foregroundColor(Theme.textDim)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(store.inbox) { item in
                            InboxCard(store: store, item: item)
                        }
                    }
                    .padding(.top, 2)
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.black.opacity(0.14))
    }
}

private struct InboxCard: View {
    @ObservedObject var store: Store
    let item: InboxItem
    @State private var hovering = false

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(item.text)
                .font(Theme.font(13))
                .foregroundColor(Theme.text)
                .lineLimit(5)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if hovering {
                Button { store.deleteInbox(item.id) } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold))
                }
                .buttonStyle(.plain)
                .foregroundColor(Theme.textDim)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(corner: 10, highlighted: hovering)
        .onHover { hovering = $0 }
        .animation(.easeOut(duration: 0.12), value: hovering)
        .onDrag { NSItemProvider(object: item.id.uuidString as NSString) }
        .contextMenu {
            ForEach(Quadrant.allCases, id: \.self) { q in
                Button("移到「\(q.title)」") { store.organize(inboxID: item.id, to: q) }
            }
            Divider()
            Button("删除", role: .destructive) { store.deleteInbox(item.id) }
        }
    }
}

// MARK: - Quadrant card

private struct QuadrantCard: View {
    @ObservedObject var store: Store
    let quadrant: Quadrant
    @State private var targeted = false
    @State private var newText = ""

    private var accent: Color { Theme.accent(for: quadrant) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            ScrollView {
                LazyVStack(spacing: 4) {
                    ForEach(store.tasks(in: quadrant)) { task in
                        QuadrantTaskRow(store: store, task: task, accent: accent)
                    }
                }
                .padding(.top, 1)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            addField
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .panel(corner: 14, accent: accent, highlighted: targeted)
        .animation(.easeOut(duration: 0.12), value: targeted)
        .onDrop(of: [UTType.text], isTargeted: $targeted) { providers in
            handleDrop(providers)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Circle()
                    .fill(accent)
                    .frame(width: 7, height: 7)
                Text(quadrant.title)
                    .font(Theme.font(13, weight: .semibold))
                    .foregroundColor(accent)
                Text(quadrant.hint)
                    .font(Theme.font(11))
                    .foregroundColor(Theme.textDim)
                Spacer()
                if quadrantEstimatedMinutes > 0 {
                    Text("约 " + DurationLabel.short(quadrantEstimatedMinutes))
                        .font(Theme.font(11, weight: .medium))
                        .foregroundColor(Theme.textDim)
                }
            }
            Rectangle()
                .fill(accent.opacity(0.3))
                .frame(height: 1)
        }
    }

    private var addField: some View {
        HStack(spacing: 6) {
            Image(systemName: "plus")
                .font(.system(size: 11))
                .foregroundColor(accent.opacity(0.75))
            TextField("加到此象限", text: $newText)
                .textFieldStyle(.plain)
                .font(Theme.font(12))
                .foregroundColor(Theme.text)
                .tint(accent)
                .onSubmit {
                    store.addTask(newText, to: quadrant)
                    newText = ""
                }
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 7)
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.black.opacity(0.2)))
    }

    private var quadrantEstimatedMinutes: Int {
        store.tasks(in: quadrant).reduce(0) { $0 + ($1.completed ? 0 : ($1.estimatedMinutes ?? 0)) }
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first else { return false }
        provider.loadObject(ofClass: NSString.self) { object, _ in
            guard let string = object as? String, let id = UUID(uuidString: string) else { return }
            DispatchQueue.main.async { store.organize(inboxID: id, to: quadrant) }
        }
        return true
    }
}

private struct QuadrantTaskRow: View {
    @ObservedObject var store: Store
    let task: TaskItem
    let accent: Color
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            TaskCheckbox(completed: task.completed) { store.toggleComplete(task.id) }
            Text(task.text)
                .font(Theme.font(13))
                .foregroundColor(task.completed ? Theme.textDim : Theme.text)
                .strikethrough(task.completed, color: Theme.textDim)
                .lineLimit(2)
            Spacer(minLength: 0)
            DurationPill(minutes: task.estimatedMinutes) { store.setEstimate(task.id, minutes: $0) }
            ReminderPill(date: task.remindAt) { store.setRemind(task.id, at: $0) }
            Button { store.toggleToday(task.id) } label: {
                Image(systemName: task.isToday ? "sun.max.fill" : "sun.max")
                    .font(.system(size: 12))
                    .foregroundColor(task.isToday ? Theme.amber : Theme.textDim)
            }
            .buttonStyle(.plain)
            .help(task.isToday ? "移出今天" : "加入今天")
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(hovering ? Theme.panel.opacity(0.55) : Color.clear)
        )
        .onHover { hovering = $0 }
        .contextMenu {
            Button(task.isToday ? "移出今天" : "加入今天") { store.toggleToday(task.id) }
            Menu("预估时长") {
                ForEach(DurationPill.presets, id: \.self) { m in
                    Button(DurationLabel.short(m)) { store.setEstimate(task.id, minutes: m) }
                }
                if task.estimatedMinutes != nil {
                    Divider()
                    Button("清除") { store.setEstimate(task.id, minutes: nil) }
                }
            }
            Menu("提醒") {
                ForEach(ReminderPresets.all(), id: \.label) { preset in
                    Button(preset.label) { store.setRemind(task.id, at: preset.date) }
                }
                if task.remindAt != nil {
                    Divider()
                    Button("清除提醒") { store.setRemind(task.id, at: nil) }
                }
            }
            Menu("移到其它象限") {
                ForEach(Quadrant.allCases.filter { $0 != task.quadrant }, id: \.self) { q in
                    Button(q.title) { store.changeQuadrant(task.id, to: q) }
                }
            }
            Divider()
            Button("删除", role: .destructive) { store.deleteTask(task.id) }
        }
    }
}
