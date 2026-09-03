import SwiftUI
import PareCore

/// Shared date formatting.
enum PareFormat {
    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M月d日 EEEE"
        return f
    }()

    static func today(_ date: Date = Date()) -> String {
        dayFormatter.string(from: date)
    }
}

/// A round check control. When it flips to complete it plays a one-time amber
/// ring burst — the only celebratory animation in the app, exactly once.
struct TaskCheckbox: View {
    let completed: Bool
    /// RFC-0001: 让 VoiceOver 读出这个勾选控件属于哪条任务。默认空串以兼容旧调用点。
    var label: String = ""
    let onToggle: () -> Void

    @State private var ringScale: CGFloat = 1
    @State private var ringOpacity: Double = 0

    var body: some View {
        Button(action: fire) {
            ZStack {
                Circle()
                    .fill(completed
                          ? AnyShapeStyle(RadialGradient(
                                colors: [Color(hex: 0xF3D896), Theme.amber],
                                center: .init(x: 0.35, y: 0.3), startRadius: 0, endRadius: 16))
                          : AnyShapeStyle(Color.clear))
                    .frame(width: 20, height: 20)
                Circle()
                    .stroke(completed ? Theme.amber : Theme.border, lineWidth: 1.5)
                    .frame(width: 20, height: 20)
                if completed {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Theme.bg)
                }
            }
            .shadow(color: Theme.amber.opacity(completed ? 0.45 : 0), radius: 5)
            .overlay(
                Circle()
                    .stroke(Theme.amber, lineWidth: 2)
                    .frame(width: 20, height: 20)
                    .scaleEffect(ringScale)
                    .opacity(ringOpacity)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .animation(.spring(response: 0.3, dampingFraction: 0.6), value: completed)
        .accessibilityLabel(a11yLabel)
        .accessibilityAddTraits(completed ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint(completed ? "连按取消完成" : "连按标记完成")
    }

    private var a11yLabel: String {
        let state = completed ? "已完成" : "未完成"
        return label.isEmpty ? state : "\(state)：\(label)"
    }

    private func fire() {
        if !completed {
            ringScale = 0.3
            ringOpacity = 0.9
            DispatchQueue.main.async {
                withAnimation(.easeOut(duration: 0.55)) {
                    ringScale = 2.0
                    ringOpacity = 0.0
                }
            }
        }
        onToggle()
    }
}

/// A compact, tappable estimated-duration pill. Tap to pick a preset (or clear).
/// Editable form is used in the 整理 window; a read-only variant lives inline in
/// the capture popover.
struct DurationPill: View {
    let minutes: Int?
    let onSelect: (Int?) -> Void

    /// Common planning durations (single source of truth, reused by menus).
    static let presets: [Int] = [5, 10, 15, 25, 30, 45, 60, 90, 120]

    var body: some View {
        Menu {
            ForEach(Self.presets, id: \.self) { m in
                Button(DurationLabel.short(m)) { onSelect(m) }
            }
            if minutes != nil {
                Divider()
                Button("清除时长", role: .destructive) { onSelect(nil) }
            }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "clock").font(.system(size: 10))
                if let m = minutes {
                    Text(DurationLabel.short(m)).font(Theme.font(11, weight: .medium)).lineLimit(1)
                }
            }
            .foregroundColor(minutes == nil ? Theme.textDim : Theme.amber)
            .padding(.horizontal, minutes == nil ? 4 : 6)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(minutes == nil ? Color.clear : Theme.amberSoft)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(minutes == nil ? Theme.border : Theme.amber.opacity(0.35), lineWidth: 1)
            )
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("预估时长")
        .accessibilityLabel("预估时长")
        .accessibilityValue(minutes.map { DurationLabel.short($0) } ?? "未设置")
    }
}

/// Compact reminder-time labels: "HH:mm" today, "明天 HH:mm" tomorrow, else "M/d HH:mm".
enum ReminderFormat {
    private static let hm: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "HH:mm"; return f
    }()
    private static let md: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "M/d HH:mm"; return f
    }()

    static func short(_ date: Date, now: Date = Date()) -> String {
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return hm.string(from: date) }
        if let tomorrow = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: now)),
           cal.isDate(date, inSameDayAs: tomorrow) {
            return "明天 " + hm.string(from: date)
        }
        return md.string(from: date)
    }
}

/// Quick reminder presets — relative for "soon", absolute for "later". Computed
/// from `now` so the menu always offers sensible next times.
enum ReminderPresets {
    static func all(now: Date = Date()) -> [(label: String, date: Date)] {
        let cal = Calendar.current
        func at(_ hour: Int, _ minute: Int, dayOffset: Int) -> Date {
            let base = cal.date(byAdding: .day, value: dayOffset, to: now) ?? now
            return cal.date(bySettingHour: hour, minute: minute, second: 0, of: base) ?? now
        }
        var list: [(String, Date)] = [
            ("15 分钟后", now.addingTimeInterval(15 * 60)),
            ("30 分钟后", now.addingTimeInterval(30 * 60)),
            ("1 小时后", now.addingTimeInterval(60 * 60)),
            ("2 小时后", now.addingTimeInterval(2 * 60 * 60)),
        ]
        let sevenPM = at(19, 0, dayOffset: 0)
        if sevenPM > now { list.append(("今晚 19:00", sevenPM)) }
        list.append(("明天上午 9:00", at(9, 0, dayOffset: 1)))
        return list
    }
}

/// A compact, tappable reminder-time pill. Icon-only when unset; bell + time
/// when a reminder is set. Used in the 整理 window; read-only elsewhere.
struct ReminderPill: View {
    let date: Date?
    let onSelect: (Date?) -> Void

    var body: some View {
        Menu {
            ForEach(ReminderPresets.all(), id: \.label) { preset in
                Button(preset.label) { onSelect(preset.date) }
            }
            if date != nil {
                Divider()
                Button("清除提醒", role: .destructive) { onSelect(nil) }
            }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: date == nil ? "bell" : "bell.fill").font(.system(size: 10))
                if let d = date {
                    Text(ReminderFormat.short(d)).font(Theme.font(11, weight: .medium)).lineLimit(1)
                }
            }
            .foregroundColor(date == nil ? Theme.textDim : Theme.amber)
            .padding(.horizontal, date == nil ? 4 : 6)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(date == nil ? Color.clear : Theme.amberSoft)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(date == nil ? Theme.border : Theme.amber.opacity(0.35), lineWidth: 1)
            )
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("提醒时间")
        .accessibilityLabel("提醒时间")
        .accessibilityValue(date.map { ReminderFormat.short($0) } ?? "未设置")
    }
}

/// A slim today-progress bar: an amber fill over a faint track. Purely a visual
/// reinforcement of the "N / M done" line.
struct ProgressBar: View {
    let fraction: Double        // 0...1
    var height: CGFloat = 4

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.hairline)
                Capsule()
                    .fill(LinearGradient(colors: [Theme.amber.opacity(0.8), Theme.amber],
                                         startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(0, min(1, fraction)) * geo.size.width)
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityLabel("今日进度")
        .accessibilityValue("\(Int((max(0, min(1, fraction)) * 100).rounded()))%")
    }
}
