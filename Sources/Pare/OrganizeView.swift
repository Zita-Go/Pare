import SwiftUI
import AppKit
import UniformTypeIdentifiers
import PareCore

private enum WorkspaceMode: String, CaseIterable {
    case matrix = "四象限"
    case today = "今日计划"
    case library = "想法与历史"
}

/// The "整理状态" — the four quadrants are the main act, each with its own quiet
/// color so you can read the map at a glance. Unprocessed captures sit in a
/// recessed tray on the left; drag each into a quadrant (or use its context
/// menu). Inside a quadrant you can complete, add to today, estimate duration,
/// set a reminder, or type a new task directly.
struct OrganizeView: View {
    @ObservedObject var store: Store
    /// RFC-0001 T2: 今日小结卡片的展开 / 收起状态，由整理窗控制器持有，好让晚间
    /// `review` 通知点击（或右键「今日小结」入口）把它强制展开。
    @ObservedObject var summaryState: TodaySummaryState
    /// RFC-0001 T3: 打开「本周回顾」窗口的整理窗入口回调（默认空操作，兼容其它调用点）。
    var onOpenWeeklyReview: () -> Void = {}
    /// RFC-0001: 整理窗全键盘归位的当前"光标" —— 选中的待整理项或象限任务。
    @StateObject private var focus = OrganizeFocus()
    @State private var workspace: WorkspaceMode = .matrix

    var body: some View {
        HStack(spacing: 0) {
            if inboxVisible {
                InboxColumn(store: store, focus: focus)
                    .frame(width: 288)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            } else {
                CollapsedInboxRail(store: store)
                    .frame(width: 64)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
            Rectangle().fill(Theme.hairline).frame(width: 1)
            VStack(spacing: 14) {
                topBar
                if shouldShowSummary {
                    TodaySummaryCard(store: store, expanded: $summaryState.expanded)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                workspaceContent
            }
            .padding(18)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 880, minHeight: 580, alignment: .top)
        .padding(.top, 10)
        .background(FrostBackground(tint: 0.66))
        // RFC-0001: 透明的按键捕获层，成为第一响应者后处理 ↑↓ / 1–4 / 空格 / esc。
        .background(KeyCaptureView { handleKey($0) })
        .overlay(alignment: .bottomTrailing) {
            UndoToast(store: store).padding(22)
        }
        .animation(.easeOut(duration: 0.18), value: store.undoMessage)
        .animation(.easeOut(duration: 0.2), value: inboxVisible)
    }

    private var inboxVisible: Bool { !store.inbox.isEmpty }

    private var shouldShowSummary: Bool {
        summaryState.expanded
            || (workspace == .today && Calendar.current.component(.hour, from: Date()) >= 17)
    }

    private var topBar: some View {
        VStack(spacing: 10) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 10) {
                    topTitle
                    WorkspaceSwitcher(selection: $workspace).frame(width: 270)
                    Spacer(minLength: 8)
                    topActions
                }
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        topTitle
                        Spacer(minLength: 8)
                        topActions
                    }
                    WorkspaceSwitcher(selection: $workspace)
                        .frame(maxWidth: 300)
                }
            }
            if store.todayTotal > 0 {
                ProgressBar(fraction: Double(store.todayCompleted) / Double(max(1, store.todayTotal)))
                    .animation(.easeOut(duration: 0.35), value: store.todayCompleted)
            }
            // RFC-0001 T4: 今日排太多时的一句软提示 —— 只提示、不阻止添加，随件数 / 时长跨阈值出现与消退。
            if let overload = store.todayOverload() {
                overloadHint(overload)
            }
        }
    }

    private var topTitle: some View {
        HStack(alignment: .firstTextBaseline, spacing: 9) {
            Text("整理")
                .font(Theme.font(22, weight: .semibold))
                .foregroundColor(Theme.text)
            Text(PareFormat.today())
                .font(Theme.font(13))
                .foregroundColor(Theme.textDim)
                .lineLimit(1)
        }
        .fixedSize()
    }

    private var topActions: some View {
        HStack(spacing: 10) {
            weeklyReviewButton
            Text(progressString)
                .font(Theme.font(store.todayTotal > 0 ? 14 : 12,
                                 weight: store.todayTotal > 0 ? .medium : .regular))
                .foregroundColor(store.todayTotal > 0 ? Theme.amber : Theme.textDim)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    @ViewBuilder private var workspaceContent: some View {
        switch workspace {
        case .matrix: quadrantGrid
        case .today: TodayPlannerView(store: store)
        case .library: LibraryView(store: store)
        }
    }

    /// RFC-0001 T4: 今日超载软提示 —— 克制的琥珀色胶囊，直接展示 `TodayOverload.message`。
    private func overloadHint(_ overload: TodayOverload) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "line.3.horizontal.decrease.circle").font(.system(size: 11))
            Text(overload.message)
                .font(Theme.font(11))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button("去精简") { workspace = .today }
                .buttonStyle(.plain)
                .font(Theme.font(11, weight: .semibold))
                .foregroundColor(Theme.amber)
        }
        .foregroundColor(Theme.amber.opacity(0.9))
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Theme.amberSoft))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.amber.opacity(0.3), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("今日安排偏多：\(overload.message)")
    }

    /// RFC-0001 T3: 整理窗里的「本周回顾」手动入口 —— 克制的描边按钮，不与琥珀色进度抢戏。
    private var weeklyReviewButton: some View {
        Button(action: onOpenWeeklyReview) {
            HStack(spacing: 4) {
                Image(systemName: "calendar").font(.system(size: 11))
                Text("本周回顾").font(Theme.font(12, weight: .medium))
            }
            .foregroundColor(Theme.textDim)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .help("打开本周回顾")
        .accessibilityLabel("本周回顾")
        .accessibilityHint("打开本周回顾窗口")
    }

    /// RFC-0001: 全键盘归位的发现性提示 —— 一行克制说明，不抢视觉。
    private var keyboardHint: some View {
        HStack(spacing: 6) {
            Image(systemName: "keyboard").font(.system(size: 10))
            Text("↑↓ 选择 · 1–4 归入象限 · 空格完成 · esc 取消")
                .font(Theme.font(11))
                .lineLimit(1)
                .minimumScaleFactor(0.65)
        }
        .foregroundColor(Theme.textFaint)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityLabel("整理窗支持全键盘操作：上下方向键选择，数字键 1 到 4 归入象限，空格键完成，esc 取消选择")
    }

    private var quadrantGrid: some View {
        GeometryReader { geo in
            let heights = quadrantRowHeights(total: geo.size.height)
            VStack(spacing: 12) {
                HStack(spacing: 12) {
                    QuadrantCard(store: store, quadrant: .importantUrgent, focus: focus)
                    QuadrantCard(store: store, quadrant: .importantNotUrgent, focus: focus)
                }
                .frame(height: heights.top)
                HStack(spacing: 12) {
                    QuadrantCard(store: store, quadrant: .urgentNotImportant, focus: focus)
                    QuadrantCard(store: store, quadrant: .neither, focus: focus)
                }
                .frame(height: heights.bottom)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func quadrantRowHeights(total: CGFloat) -> (top: CGFloat, bottom: CGFloat) {
        let available = max(240, total - 12)
        let topCount = store.tasks(in: .importantUrgent).filter { !$0.completed }.count
            + store.tasks(in: .importantNotUrgent).filter { !$0.completed }.count
        let bottomCount = store.tasks(in: .urgentNotImportant).filter { !$0.completed }.count
            + store.tasks(in: .neither).filter { !$0.completed }.count
        if bottomCount == 0, topCount > 0 {
            let bottom = min(120, available * 0.28)
            return (available - bottom, bottom)
        }
        if topCount == 0, bottomCount > 0 {
            let top = min(120, available * 0.28)
            return (top, available - top)
        }
        if topCount >= max(2, bottomCount * 2) {
            return (available * 0.64, available * 0.36)
        }
        if bottomCount >= max(2, topCount * 2) {
            return (available * 0.36, available * 0.64)
        }
        return (available * 0.5, available * 0.5)
    }

    private var progressString: String {
        if store.todayTotal > 0 {
            var s = "完成 \(store.todayCompleted) / \(store.todayTotal)"
            if store.todayEstimatedMinutes > 0 {
                s += " · 约 " + DurationLabel.short(store.todayEstimatedMinutes)
            }
            return s
        }
        return "把想法归位，再挑今天要做的"
    }

    // MARK: - Keyboard organizing (RFC-0001 T6)

    /// 所有可被键盘选中的项，按视觉顺序拍平：待整理 → 象限1 → 象限2 → 象限3 → 象限4。
    /// 每次读取实时数据，天然跟随增删与排序变化。
    private var navItems: [OrganizeSelection] {
        var items: [OrganizeSelection] = store.inbox.map { .inbox($0.id) }
        for q in Quadrant.allCases {
            items += store.tasks(in: q).map { .task($0.id) }
        }
        return items
    }

    /// ↑↓ 移动选中光标；无选中时按方向落在首/末项，到边界不回绕（可预期）。
    private func moveSelection(_ delta: Int) {
        let items = navItems
        guard !items.isEmpty else { focus.selection = nil; return }
        guard let current = focus.selection, let idx = items.firstIndex(of: current) else {
            focus.selection = delta >= 0 ? items.first : items.last
            return
        }
        focus.selection = items[max(0, min(items.count - 1, idx + delta))]
    }

    /// 数字键 1–4 把选中项归入对应象限。待整理项归位后，光标跟着它进入象限，
    /// 便于紧接着按空格完成或再次按数字调整。
    private func assignQuadrant(_ quadrant: Quadrant) {
        guard let selection = focus.selection else { return }
        switch selection {
        case .inbox(let id):
            if let task = store.organize(inboxID: id, to: quadrant) {
                focus.selection = .task(task.id)
            } else {
                focus.selection = nil
            }
        case .task(let id):
            store.changeQuadrant(id, to: quadrant)
        }
    }

    /// 空格完成/取消完成选中的象限任务；待整理项无"完成"语义，忽略。
    private func completeSelection() {
        if case .task(let id) = focus.selection { store.toggleComplete(id) }
    }

    /// 把一次按键翻译成动作。返回 true 表示已消费（不再让系统处理/发出提示音）。
    private func handleKey(_ key: OrganizeKey) -> Bool {
        guard workspace == .matrix else { return false }
        switch key {
        case .up:    moveSelection(-1); return true
        case .down:  moveSelection(1);  return true
        case .space: completeSelection(); return true
        case .escape:
            guard focus.selection != nil else { return false }
            focus.selection = nil
            return true
        case .digit(let n):
            guard let quadrant = Quadrant(rawValue: n - 1) else { return false }
            // 无选中时，第一次数字键先落在首项（给出可见反馈），不立即归位。
            guard focus.selection != nil else {
                focus.selection = navItems.first
                return true
            }
            assignQuadrant(quadrant)
            return true
        }
    }
}

private struct WorkspaceSwitcher: View {
    @Binding var selection: WorkspaceMode

    var body: some View {
        // The three controls must form one continuous hit region. A real HStack
        // spacing here creates a narrow dead strip between tabs: clicking the
        // visual boundary changes nothing even though it looks like one control.
        HStack(spacing: 0) {
            ForEach(WorkspaceMode.allCases, id: \.self) { mode in
                Button { selection = mode } label: {
                    Text(mode.rawValue)
                        .font(Theme.font(12, weight: .semibold))
                        .foregroundColor(selection == mode ? Theme.bg : Theme.textDim)
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .background(
                            RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .fill(selection == mode ? Theme.amber : Color.clear)
                        )
                        // Keep a small visual seam while leaving the padded
                        // area owned by one of the adjacent buttons.
                        .padding(.horizontal, 1.5)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .contentShape(Rectangle())
                .accessibilityAddTraits(selection == mode ? .isSelected : [])
            }
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 9).fill(Theme.panel.opacity(0.65)))
        .overlay(RoundedRectangle(cornerRadius: 9).stroke(Theme.border.opacity(0.45), lineWidth: 1))
        .help("四象限支持 ↑↓ 选择、1–4 归位、空格完成、esc 取消")
    }
}

private struct CollapsedInboxRail: View {
    @ObservedObject var store: Store

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray")
                .font(.system(size: 16, weight: .medium))
            Text("\(store.inboxCount)")
                .font(Theme.font(11, weight: .bold))
                .foregroundColor(Theme.amber)
        }
        .foregroundColor(Theme.textDim)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top, 22)
        .background(Color.black.opacity(0.12))
        .help("暂无待整理")
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("待整理为空")
    }
}

// MARK: - Inbox column

private struct InboxColumn: View {
    @ObservedObject var store: Store
    @ObservedObject var focus: OrganizeFocus
    @State private var showAI = false

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
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("待整理 \(store.inboxCount) 条")
            }
            Text("拖进右边的象限，或选中后按 1–4 归位")
                .font(Theme.font(11))
                .foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)

            Button("✦ 帮我整理") { showAI = true }
                .buttonStyle(.plain).font(Theme.font(12, weight: .medium))
                .foregroundColor(Theme.amber).disabled(store.inbox.isEmpty)
                .sheet(isPresented: $showAI) {
                    AIAssistantView(store: store, workflow: .organize)
                }

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
                            InboxCard(store: store, item: item, focus: focus)
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
    @ObservedObject var focus: OrganizeFocus
    @State private var hovering = false

    private var isSelected: Bool { focus.selection == .inbox(item.id) }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 5) {
                Text(item.text)
                    .font(Theme.font(13))
                    .foregroundColor(Theme.text)
                    .lineLimit(5)
                    .fixedSize(horizontal: false, vertical: true)
                if let date = item.suggestedRemindAt {
                    HStack(spacing: 3) {
                        Image(systemName: "bell.fill")
                        Text(ReminderFormat.short(date))
                    }
                    .font(Theme.font(10, weight: .medium))
                    .foregroundColor(Theme.amber)
                }
            }
            Spacer(minLength: 0)
            Button { _ = store.keepAsThought(inboxID: item.id) } label: {
                Image(systemName: "lightbulb").font(.system(size: 11, weight: .semibold))
            }
            .buttonStyle(.plain)
            .foregroundColor(Theme.amber)
            .help("保留为想法")
            .accessibilityLabel("保留为想法")
            if hovering {
                Button { store.deleteInbox(item.id) } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold))
                }
                .buttonStyle(.plain)
                .foregroundColor(Theme.textDim)
                .accessibilityLabel("删除")
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(corner: 10, highlighted: hovering)
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Theme.amber, lineWidth: 2)
                .opacity(isSelected ? 1 : 0)
        )
        .contentShape(Rectangle())
        .onTapGesture { focus.selection = .inbox(item.id) }
        .onHover { hovering = $0 }
        .animation(.easeOut(duration: 0.12), value: hovering)
        .onDrag { NSItemProvider(object: item.id.uuidString as NSString) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("待整理：\(item.text)")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint("选中后按 1–4 归入象限")
        .contextMenu {
            Button("保留为想法") { store.keepAsThought(inboxID: item.id) }
            Divider()
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
    @ObservedObject var focus: OrganizeFocus
    @State private var targeted = false
    @State private var newText = ""
    @FocusState private var addFocused: Bool
    /// RFC-0001 T4: 象限四「清空」的二次确认开关（只有 .neither 卡片会置真）。
    @State private var confirmingClear = false

    private var accent: Color { Theme.accent(for: quadrant) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            // RFC-0001 T4: 象限四（不重要 · 不紧急）积压时的一句清理引导 + 便捷清空。
            if quadrant == .neither, let cleanup = store.quadrantFourCleanup() {
                cleanupBanner(cleanup)
            }
            ScrollView {
                LazyVStack(spacing: 4) {
                    ForEach(store.tasks(in: quadrant)) { task in
                        QuadrantTaskRow(store: store, task: task, accent: accent, focus: focus)
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
        // RFC-0001 T4: 「清空」二次确认 —— 只删本象限未完成项，绝不触碰其它象限。
        .alert("清空「不重要 · 不紧急」？", isPresented: $confirmingClear) {
            Button("取消", role: .cancel) {}
            Button("清空", role: .destructive) { store.clearQuadrant(.neither) }
        } message: {
            Text("会删掉这一格里 \(store.tasks(in: .neither).filter { !$0.completed }.count) 件未完成的任务，其它三个象限不受影响。清空后可在 10 秒内撤销。")
        }
    }

    /// RFC-0001 T4: 象限四清理引导条 —— 一句 `QuadrantFourCleanup.message` + 「清空」入口，
    /// 配色压在象限自身色里，克制不喧宾夺主。逐条删除仍走每行右键菜单的「删除」。
    private func cleanupBanner(_ cleanup: QuadrantFourCleanup) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "line.3.horizontal.decrease.circle").font(.system(size: 11))
            Text(cleanup.message)
                .font(Theme.font(11))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 6)
            Button { confirmingClear = true } label: {
                Text("清空").font(Theme.font(11, weight: .semibold))
            }
            .buttonStyle(.plain)
            .foregroundColor(accent)
            .help("清空这一格里未完成的任务")
            .accessibilityLabel("清空不重要不紧急象限的未完成任务")
        }
        .foregroundColor(Theme.textDim)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 9)
        .padding(.vertical, 7)
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(accent.opacity(0.10)))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(accent.opacity(0.3), lineWidth: 1))
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Circle()
                    .fill(accent)
                    .frame(width: 7, height: 7)
                // RFC-0001: 象限对应的数字键提示，让全键盘归位一目了然（1–4 = 阅读顺序）。
                Text("\(quadrant.rawValue + 1)")
                    .font(Theme.font(10, weight: .bold))
                    .foregroundColor(Theme.textFaint)
                    .frame(width: 15, height: 15)
                    .background(RoundedRectangle(cornerRadius: 4, style: .continuous).stroke(Theme.border, lineWidth: 1))
                    .accessibilityHidden(true)
                Text(quadrant.title)
                    .font(Theme.font(13, weight: .semibold))
                    .foregroundColor(accent)
                    // RFC-0001: 象限名是四象限地图的核心信息，永远完整显示，
                    // 不再被提示语 / 估时标签挤断（此前会被截成「重要 ·…」）。
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(2)
                    .help(quadrant.hint)
                    .accessibilityLabel("\(quadrant.title)，\(quadrant.hint)")
                Spacer(minLength: 6)
                if quadrantEstimatedMinutes > 0 {
                    Text("约 " + DurationLabel.short(quadrantEstimatedMinutes))
                        .font(Theme.font(11, weight: .medium))
                        .foregroundColor(Theme.textDim)
                        .fixedSize()
                        .layoutPriority(1)
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
            TextField("添加任务", text: $newText)
                .textFieldStyle(.plain)
                .font(Theme.font(12))
                .foregroundColor(Theme.text)
                .tint(accent)
                .focused($addFocused)
                .onSubmit {
                    store.addTask(newText, to: quadrant)
                    newText = ""
                }
                .accessibilityLabel("加到「\(quadrant.title)」")
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.black.opacity(addFocused ? 0.18 : 0.08))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(addFocused ? accent.opacity(0.55) : Color.clear, lineWidth: 1)
        )
        .animation(.easeOut(duration: 0.12), value: addFocused)
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
    @ObservedObject var focus: OrganizeFocus
    @State private var hovering = false
    /// RFC-0001 T5: 刚完成、等待记录实际用时时展开的就地 chip 提示。
    @State private var showActualPrompt = false

    private var isSelected: Bool { focus.selection == .task(task.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                TaskCheckbox(completed: task.completed, label: task.text) { toggleComplete() }
                Text(task.text)
                    .font(Theme.font(13))
                    .foregroundColor(task.completed ? Theme.textDim : Theme.text)
                    .strikethrough(task.completed, color: Theme.textDim)
                    .lineLimit(2)
                    // RFC-0001: 任务文字优先拿宽度，短任务名不再被尾部控件挤到换行。
                    .layoutPriority(1)
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                Spacer(minLength: 6)
                if let due = task.dueAt { DueBadge(date: due, completed: task.completed) }
                // 已设置的状态常显；未设置项统一收进“…”详情，避免一排悬停按钮挤压任务标题。
                if task.estimatedMinutes != nil {
                    DurationPill(minutes: task.estimatedMinutes) { store.setEstimate(task.id, minutes: $0) }
                }
                // 完成后提醒已无意义（提醒本身也会随完成撤销），让位给「实际用时」控件。
                if task.completed {
                    actualControl
                } else if task.remindAt != nil {
                    ReminderPill(date: task.remindAt) { store.setRemind(task.id, at: $0) }
                }
                if !task.completed, (hovering || store.activeTimer?.taskID == task.id) {
                    FocusTimerButton(store: store, task: task)
                }
                if task.isToday || hovering {
                    Button { store.toggleToday(task.id) } label: {
                        Image(systemName: task.isToday ? "sun.max.fill" : "sun.max")
                            .font(.system(size: 12))
                            .foregroundColor(task.isToday ? Theme.amber : Theme.textDim)
                    }
                    .buttonStyle(.plain)
                    .help(task.isToday ? "移出今天" : "加入今天")
                    .accessibilityLabel(task.isToday ? "移出今天" : "加入今天")
                }
                TaskDetailsButton(store: store, task: task)
            }
            // RFC-0001 T5：打勾完成后就地弹出一排预设时长，点一下即记，点 ✕ 跳过。
            if task.completed, showActualPrompt, task.actualMinutes == nil {
                CompletionTimeChips(
                    onPick: { store.setActual(task.id, minutes: $0); showActualPrompt = false },
                    onSkip: { showActualPrompt = false }
                )
                .padding(.leading, 28)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(hovering ? Theme.panel.opacity(0.55) : Color.clear)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .stroke(Theme.amber, lineWidth: 2)
                .opacity(isSelected ? 1 : 0)
        )
        .contentShape(Rectangle())
        .onTapGesture { focus.selection = .task(task.id) }
        .onHover { hovering = $0 }
        .animation(.easeOut(duration: 0.15), value: showActualPrompt)
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
            Menu("实际用时") {
                ForEach(DurationPill.presets, id: \.self) { m in
                    Button(DurationLabel.short(m)) { store.setActual(task.id, minutes: m) }
                }
                if task.actualMinutes != nil {
                    Divider()
                    Button("清除实际用时") { store.setActual(task.id, minutes: nil) }
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

    /// 完成态右侧的「实际用时」控件：已记录→可改/清除的药丸；未记录且提示已收起→补记入口。
    @ViewBuilder private var actualControl: some View {
        if let actual = task.actualMinutes {
            ActualTimePill(minutes: actual) { store.setActual(task.id, minutes: $0) }
        } else if !showActualPrompt {
            RecordActualButton { withAnimation(.easeOut(duration: 0.15)) { showActualPrompt = true } }
        }
    }

    /// 打勾完成时顺手弹出用时提示；取消完成时收起。用当前 `task` 的旧值预判新状态。
    private func toggleComplete() {
        let willComplete = !task.completed
        store.toggleComplete(task.id)
        showActualPrompt = willComplete && task.actualMinutes == nil
    }
}

// MARK: - Keyboard organizing model & key capture (RFC-0001 T6)

/// The current keyboard "cursor": either an unprocessed inbox item or a task.
enum OrganizeSelection: Hashable {
    case inbox(UUID)
    case task(UUID)
}

/// Shared selection state for full-keyboard organizing.
/// RFC-0001: 用一个引用型模型贯穿整理窗，让按键层与各行共享同一个"选中光标"。
final class OrganizeFocus: ObservableObject {
    @Published var selection: OrganizeSelection?
}

/// A single decoded key command handled by the organize window.
enum OrganizeKey: Equatable {
    case up, down, space, escape
    case digit(Int)
}

/// An invisible AppKit layer that grabs the keyboard for the organize window.
/// RFC-0001: macOS 13 没有 SwiftUI `onKeyPress`，用 NSView.keyDown 精确接管方向键 /
/// 数字键 / 空格 / esc；因为只有它成为第一响应者时才收到按键，文本框获得焦点时天然
/// 不会被抢占（此时它不是第一响应者）。
struct KeyCaptureView: NSViewRepresentable {
    /// 返回 true 表示按键已被消费。
    let onKey: (OrganizeKey) -> Bool

    func makeNSView(context: Context) -> KeyCaptureNSView {
        let view = KeyCaptureNSView()
        view.onKey = onKey
        return view
    }

    func updateNSView(_ view: KeyCaptureNSView, context: Context) {
        view.onKey = onKey
    }
}

final class KeyCaptureNSView: NSView {
    var onKey: ((OrganizeKey) -> Bool)?

    override var acceptsFirstResponder: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        focusRingType = .none
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        // 窗口出现后主动接管键盘，纯键盘用户无需先点一下。
        guard window != nil else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window?.firstResponder is NSTextView == false else { return }
            self.window?.makeFirstResponder(self)
        }
    }

    override func keyDown(with event: NSEvent) {
        if let command = Self.command(for: event), onKey?(command) == true { return }
        super.keyDown(with: event)
    }

    /// 把一个 keyDown 事件解码成 `OrganizeKey`；带 ⌘/⌃/⌥ 的按键一律放行（不拦截
    /// 应用菜单快捷键），只处理裸方向键 / 空格 / esc / 数字 1–4。
    private static func command(for event: NSEvent) -> OrganizeKey? {
        let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if !mods.intersection([.command, .control, .option]).isEmpty { return nil }
        switch event.keyCode {
        case 126: return .up      // kVK_UpArrow
        case 125: return .down    // kVK_DownArrow
        case 49:  return .space   // kVK_Space
        case 53:  return .escape  // kVK_Escape
        default:
            if let chars = event.charactersIgnoringModifiers, let n = Int(chars), (1...4).contains(n) {
                return .digit(n)
            }
            return nil
        }
    }
}

// MARK: - Today summary card (RFC-0001 T2)

/// Shared expand/collapse state for the 今日小结 card. Owned by the organize
/// window controller so an evening-`review` notification tap (or the 右键
/// 「今日小结」 entry) can force it open. Defaults to collapsed — the card never
/// pops itself open; it only shows details when the user (or a tap) expands it.
final class TodaySummaryState: ObservableObject {
    @Published var expanded: Bool = false
}

/// 整理窗顶部的「今日小结」——一张可展开 / 收起的收束卡片：完成数、专注时长、
/// 预估 ↔ 实际偏差一瞥、留到明天，以及零完成时一句温和的收场。只读消费
/// `store.todaySummary()`；偏差区在没有可对比记录（T5 未落地或全部跳过）时
/// 自动隐藏。深色毛玻璃风格与四象限卡片一致。
private struct TodaySummaryCard: View {
    @ObservedObject var store: Store
    @Binding var expanded: Bool

    var body: some View {
        // 每次渲染算一次（数据量极小），随完成 / 归位实时刷新。
        let summary = store.todaySummary()
        VStack(alignment: .leading, spacing: expanded ? 10 : 0) {
            header(summary)
            if expanded { details(summary) }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .panel(corner: 14, accent: Theme.amber, highlighted: expanded)
        .animation(.easeOut(duration: 0.2), value: expanded)
    }

    // MARK: Header (always visible, tap to toggle)

    private func header(_ summary: TodaySummary) -> some View {
        Button {
            withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
        } label: {
            HStack(spacing: 9) {
                Image(systemName: "moon.stars")
                    .font(.system(size: 13))
                    .foregroundColor(Theme.amber)
                Text("今日小结")
                    .font(Theme.font(14, weight: .semibold))
                    .foregroundColor(Theme.text)
                    .fixedSize()
                if !expanded {
                    Text(teaser(summary))
                        .font(Theme.font(12))
                        .foregroundColor(Theme.textDim)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: 6)
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(Theme.textDim)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("今日小结" + (expanded ? "，已展开" : "，已收起"))
        .accessibilityValue(teaser(summary))
        .accessibilityHint(expanded ? "连按收起" : "连按展开，查看今天完成与留到明天的一览")
        .accessibilityAddTraits(.isButton)
    }

    // MARK: Expanded details

    @ViewBuilder
    private func details(_ summary: TodaySummary) -> some View {
        Rectangle().fill(Theme.hairline).frame(height: 1)

        if summary.isZeroCompletion && summary.progressSessionCount == 0 {
            // 温和收场，不制造愧疚：零完成时只留一句话，不罗列未完成清单。
            Text("今天先这样，明天见。")
                .font(Theme.font(14, weight: .medium))
                .foregroundColor(Theme.text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(alignment: .top, spacing: 22) {
                if summary.completedCount > 0 {
                    stat("\(summary.completedCount) 件", caption: "完成")
                }
                if summary.progressSessionCount > 0 {
                    stat("\(summary.progressSessionCount) 次", caption: "启动")
                }
                if summary.progressedTaskCount > 0 {
                    stat("\(summary.progressedTaskCount) 件", caption: "有推进")
                }
                if summary.focusMinutes > 0 {
                    stat("约 " + DurationLabel.short(summary.focusMinutes), caption: "专注")
                }
                Spacer(minLength: 0)
            }
            if summary.isZeroCompletion && summary.progressSessionCount > 0 {
                Text("今天还没收尾，但已经开始推进了。")
                    .font(Theme.font(12, weight: .medium))
                    .foregroundColor(Theme.text)
            }
            if let blocker = blockerHint(summary) {
                HStack(alignment: .top, spacing: 5) {
                    Image(systemName: "arrow.triangle.branch").font(.system(size: 10))
                    Text(blocker)
                        .font(Theme.font(11))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(Theme.textDim)
                .accessibilityElement(children: .combine)
            }
            if let hint = summary.biasHint {
                HStack(alignment: .top, spacing: 5) {
                    Image(systemName: "scalemass").font(.system(size: 10))
                    Text(hint)
                        .font(Theme.font(11))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(Theme.textDim)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("预估与实际：\(hint)")
            }
            if !summary.carryOver.isEmpty {
                carryOver(summary)
            }
        }
    }

    private func stat(_ value: String, caption: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(Theme.font(19, weight: .semibold))
                .foregroundColor(Theme.amber)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(caption)
                .font(Theme.font(11))
                .foregroundColor(Theme.textDim)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(caption) \(value)")
    }

    @ViewBuilder
    private func carryOver(_ summary: TodaySummary) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 5) {
                Image(systemName: "arrow.uturn.forward").font(.system(size: 10))
                Text("留到明天 · \(summary.carryOver.count) 件")
                    .font(Theme.font(12, weight: .medium))
            }
            .foregroundColor(Theme.textDim)

            ForEach(Array(summary.carryOver.prefix(4))) { task in
                HStack(spacing: 6) {
                    Circle()
                        .fill(Theme.accent(for: task.quadrant))
                        .frame(width: 5, height: 5)
                    Text(task.text)
                        .font(Theme.font(12))
                        .foregroundColor(Theme.text)
                        .lineLimit(1)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("留到明天：\(task.text)")
            }
            if summary.carryOver.count > 4 {
                Text("…还有 \(summary.carryOver.count - 4) 件")
                    .font(Theme.font(11))
                    .foregroundColor(Theme.textFaint)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Copy

    /// The one-line teaser shown when collapsed. Gentle when nothing's done.
    private func teaser(_ summary: TodaySummary) -> String {
        if summary.isZeroCompletion {
            if summary.progressSessionCount > 0 {
                return "启动 \(summary.progressSessionCount) 次 · 推进 \(summary.progressedTaskCount) 件"
            }
            return "今天先这样"
        }
        var s = "完成 \(summary.completedCount) 件"
        if summary.progressSessionCount > 0 { s += " · 启动 \(summary.progressSessionCount) 次" }
        if summary.focusMinutes > 0 { s += " · 专注 约" + DurationLabel.short(summary.focusMinutes) }
        return s
    }

    private func blockerHint(_ summary: TodaySummary) -> String? {
        let ranked = BlockerReason.allCases.compactMap { reason -> (BlockerReason, Int)? in
            guard let count = summary.blockerCounts[reason], count > 0 else { return nil }
            return (reason, count)
        }.sorted { lhs, rhs in
            lhs.1 == rhs.1
                ? BlockerReason.allCases.firstIndex(of: lhs.0)! < BlockerReason.allCases.firstIndex(of: rhs.0)!
                : lhs.1 > rhs.1
        }
        guard let top = ranked.first else { return nil }
        return "今天最常见的卡点：\(top.0.title)（\(top.1) 次）"
    }
}
