import SwiftUI
import PareCore

/// A dedicated application suite prevents helper processes / development builds
/// from writing preferences into the global user domain. Histories are further
/// separated by the store location, including isolated UI acceptance stores.
private enum IntelligenceHintStorage {
    static let defaults = UserDefaults(suiteName: "com.pare.intelligence-hints.v1")!

    static func key(for store: Store) -> String { "history:\(store.dataFileURL.standardizedFileURL.path)" }

    static func history(for store: Store) -> IntelligenceHintHistory {
        guard let data = defaults.data(forKey: key(for: store)),
              let history = try? JSONDecoder().decode(IntelligenceHintHistory.self, from: data) else {
            return IntelligenceHintHistory()
        }
        return history
    }

    static func save(_ history: IntelligenceHintHistory, for store: Store) {
        if let data = try? JSONEncoder().encode(history) { defaults.set(data, forKey: key(for: store)) }
    }
}

struct IntelligenceHintCard: View {
    @ObservedObject var store: Store
    var ideaID: UUID? = nil
    var includeToday = true
    var onOpen: (IntelligenceIntent, UUID?) -> Void
    @AppStorage private var hintsEnabled: Bool
    @State private var presented: IntelligenceHint?
    @State private var pausedUntil: Date?
    private let tick = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    init(store: Store, ideaID: UUID? = nil, includeToday: Bool = true,
         onOpen: @escaping (IntelligenceIntent, UUID?) -> Void) {
        self.store = store
        self.ideaID = ideaID
        self.includeToday = includeToday
        self.onOpen = onOpen
        _hintsEnabled = AppStorage(wrappedValue: true, "enabled:\(store.dataFileURL.standardizedFileURL.path)", store: IntelligenceHintStorage.defaults)
    }

    var body: some View {
        // Keep a stable container even before the first candidate is selected;
        // attaching onAppear to an empty conditional Group can skip the initial
        // refresh or repeat lifecycle callbacks as the card appears.
        VStack(alignment: .leading, spacing: 0) {
            if hintsEnabled, store.activeTimer == nil, let hint = presented {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline) {
                        Label("轻提示", systemImage: "sparkle").font(Theme.font(11, weight: .medium)).foregroundColor(Theme.textDim)
                        Spacer()
                        Button { dismiss(hint) } label: { Image(systemName: "xmark") }
                            .buttonStyle(.plain).help("忽略这条提示（24 小时内不重复）")
                            .accessibilityLabel("忽略这条提示")
                    }
                    Text(hint.title).font(Theme.font(14, weight: .semibold)).lineLimit(2)
                    Text(hint.detail).font(Theme.font(12)).foregroundColor(Theme.textDim)
                    HStack(spacing: 10) {
                        Button(hint.actionTitle) {
                            dismiss(hint)
                            onOpen(hint.intent, hint.subjectID)
                        }.buttonStyle(AIPrimaryButtonStyle())
                        Button("稍后") { snooze(hint, forWeek: false) }
                        Button("本周不再提示") { snooze(hint, forWeek: true) }
                    }.font(Theme.font(11)).buttonStyle(SecondaryActionStyle())
                }
                .padding(12).panel(corner: 10, accent: Theme.amber)
                .foregroundColor(Theme.text)
                .accessibilityElement(children: .contain)
            }
        }
        .onAppear { refresh() }
        .onDisappear { presented = nil; pausedUntil = nil }
        .onChange(of: store.thoughts) { _ in refresh() }
        .onChange(of: store.tasks) { _ in refresh() }
        .onChange(of: store.archive) { _ in refresh() }
        .onChange(of: store.activeTimer) { _ in refresh() }
        .onChange(of: store.parallelRuns) { _ in refresh() }
        .onChange(of: hintsEnabled) { _ in refresh() }
        .onChange(of: ideaID) { _ in presented = nil; refresh() }
        .onChange(of: includeToday) { _ in refresh() }
        .onReceive(tick) { date in refresh(now: date) }
    }

    private func refresh(now: Date = Date()) {
        guard hintsEnabled, store.activeTimer == nil,
              pausedUntil.map({ now >= $0 }) ?? true else { presented = nil; return }
        let candidates = IntelligenceHintEngine.candidates(
            thoughts: store.thoughts, tasks: store.tasks, archive: store.archive,
            activeTimer: store.activeTimer, parallelRuns: store.parallelRuns,
            includeToday: includeToday, now: now).filter { candidate in
                guard let ideaID else { return true }
                return candidate.intent == .idea && candidate.subjectID == ideaID
            }
        var history = IntelligenceHintStorage.history(for: store)
        let next = IntelligenceHintEngine.selection(current: presented, candidates: candidates, history: history, now: now)
        if let next, next.id != presented?.id {
            history.recordDisplay(next, now: now)
            IntelligenceHintStorage.save(history, for: store)
        }
        presented = next
    }

    private func dismiss(_ hint: IntelligenceHint) {
        let now = Date()
        var history = IntelligenceHintStorage.history(for: store)
        history.recordDisplay(hint, now: now)
        IntelligenceHintStorage.save(history, for: store)
        presented = nil
        // Do not immediately replace a dismissed hint with another one.
        pausedUntil = now.addingTimeInterval(3600)
    }

    private func snooze(_ hint: IntelligenceHint, forWeek: Bool) {
        let now = Date()
        var history = IntelligenceHintStorage.history(for: store)
        if forWeek { history.snoozeForWeek(hint, now: now, calendar: .current) }
        else { history.snooze(hint, until: now.addingTimeInterval(3600)) }
        IntelligenceHintStorage.save(history, for: store)
        presented = nil
        pausedUntil = now.addingTimeInterval(3600)
    }
}

struct IntelligencePreferencesView: View {
    @ObservedObject var store: Store
    @AppStorage private var hintsEnabled: Bool
    @State private var editor: PreferenceEditRequest?
    @State private var issue: String?

    init(store: Store) {
        self.store = store
        _hintsEnabled = AppStorage(wrappedValue: true, "enabled:\(store.dataFileURL.standardizedFileURL.path)", store: IntelligenceHintStorage.defaults)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle("显示本地轻提示", isOn: $hintsEnabled)
            Text("回看日期、行动结果和你明确记录的卡点会生成一条提示；专注期间隐藏。打开助手后，由你点击生成建议。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            Divider()
            HStack {
                Text("已确认的偏好").font(Theme.font(13, weight: .semibold))
                Spacer()
                Button("添加偏好") { editor = .init(preference: nil) }
                    .disabled(store.isPersistenceReadOnly)
            }
            Text("只保存你明确填写或确认的内容。反馈不会自动变成长期偏好。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if store.confirmedPreferences.isEmpty {
                Text("还没有已确认的偏好。可以写下希望如何安排工作，或只适用于某个想法的限制。")
                    .font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            ForEach(store.confirmedPreferences.sorted { $0.updatedAt > $1.updatedAt }) { preference in
                preferenceRow(preference)
            }
            if let issue { Text(issue).font(Theme.font(12)).foregroundColor(Theme.qUrgent).textSelection(.enabled) }
        }
        .font(Theme.font(12)).foregroundColor(Theme.text)
        .sheet(item: $editor) { request in IntelligencePreferenceEditor(store: store, preference: request.preference) }
    }

    private func preferenceRow(_ preference: ConfirmedPreference) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(preference.text).textSelection(.enabled)
            Text(scopeLabel(preference)).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.amber)
            Text("来源：\(preference.source) · 已确认").font(Theme.font(11)).foregroundColor(Theme.textDim)
            if let expires = preference.expiresAt {
                Text("\(expires <= Date() ? "已过期" : "有效至")：\(expires.formatted(date: .abbreviated, time: .shortened))")
                    .font(Theme.font(11)).foregroundColor(Theme.textDim)
            } else { Text("有效期：长期").font(Theme.font(11)).foregroundColor(Theme.textDim) }
            HStack {
                Text("更新于 \(preference.updatedAt.formatted(date: .abbreviated, time: .omitted))")
                    .font(Theme.font(10)).foregroundColor(Theme.textDim)
                Spacer()
                Button("改写") { editor = .init(preference: preference) }
                Button("忘记") {
                    do { try store.forgetConfirmedPreference(preference.id); issue = nil }
                    catch { issue = error.localizedDescription }
                }
            }.disabled(store.isPersistenceReadOnly)
        }.padding(10).panel(corner: 8)
    }

    private func scopeLabel(_ preference: ConfirmedPreference) -> String {
        guard let id = preference.ideaID else { return "适用范围：全局" }
        guard let idea = store.thoughts.first(where: { $0.id == id }) else { return "适用范围：已删除的想法（不再使用）" }
        return "适用范围：\(idea.text)\(idea.stage == .archived ? "（已归档）" : "")"
    }
}

private struct PreferenceEditRequest: Identifiable {
    let id = UUID()
    var preference: ConfirmedPreference?
}

private struct IntelligencePreferenceEditor: View {
    @ObservedObject var store: Store
    var preference: ConfirmedPreference?
    @Environment(\.dismiss) private var dismiss
    @State private var text: String
    @State private var ideaID: UUID?
    @State private var hasExpiry: Bool
    @State private var expiry: Date
    @State private var issue: String?

    init(store: Store, preference: ConfirmedPreference?) {
        self.store = store
        self.preference = preference
        _text = State(initialValue: preference?.text ?? "")
        _ideaID = State(initialValue: preference?.ideaID)
        _hasExpiry = State(initialValue: preference?.expiresAt != nil)
        _expiry = State(initialValue: preference?.expiresAt ?? Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(preference == nil ? "确认一条偏好" : "改写已确认偏好").font(Theme.font(18, weight: .semibold))
            TextField("例如：这个项目先给十分钟以内的起点", text: $text, axis: .vertical)
                .lineLimit(3...8).textFieldStyle(.roundedBorder)
            Text("\(text.count) / 500").font(Theme.font(10)).foregroundColor(Theme.textDim)
            if preference == nil {
                Picker("适用范围", selection: $ideaID) {
                    Text("全局").tag(nil as UUID?)
                    ForEach(store.thoughts.filter { $0.stage != .archived }) { idea in
                        Text(idea.text).tag(Optional(idea.id))
                    }
                }
            } else {
                Text("适用范围：\(preference?.ideaID.flatMap { id in store.thoughts.first { $0.id == id }?.text } ?? (preference?.ideaID == nil ? "全局" : "已删除的想法"))")
                    .font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            Toggle("设置有效期", isOn: $hasExpiry)
            if hasExpiry { DatePicker("有效至", selection: $expiry, displayedComponents: [.date, .hourAndMinute]) }
            Text("只有点击保存才会记住；可随时改写或忘记。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)
            if let issue { Text(issue).foregroundColor(Theme.qUrgent).textSelection(.enabled) }
            HStack {
                Button("取消") { dismiss() }
                Spacer()
                Button("保存") { save() }
                    .buttonStyle(AIPrimaryButtonStyle())
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || text.count > 500 || store.isPersistenceReadOnly)
            }
        }
        .padding(20).frame(width: 470).font(Theme.font(12)).foregroundColor(Theme.text).background(Theme.bg)
    }

    private func save() {
        do {
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let preference {
                try store.updateConfirmedPreference(preference.id, text: value, expiresAt: hasExpiry ? expiry : nil)
            } else {
                _ = try store.confirmPreference(text: value, ideaID: ideaID, source: "用户填写", expiresAt: hasExpiry ? expiry : nil)
            }
            dismiss()
        } catch { issue = error.localizedDescription }
    }
}
