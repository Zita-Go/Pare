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

/// Completion is a quiet semantic change. Keep the existing inline footprint,
/// accessible task label and click action without a glow, burst or bounce.
struct TaskCheckbox: View {
    let completed: Bool
    var label: String = ""
    let onToggle: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var hovered = false

    var body: some View {
        Button(action: onToggle) {
            ZStack {
                Circle().fill(completed ? Theme.success : (hovered ? Theme.hover : .clear))
                Circle().strokeBorder(completed ? Theme.success : outline, lineWidth: 1.5)
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(Theme.bg)
                    .opacity(completed ? 1 : 0)
            }
            .frame(width: 20, height: 20)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
        .animation(Theme.motion(reduceMotion), value: completed)
        .animation(Theme.motion(reduceMotion), value: hovered)
        .accessibilityLabel(a11yLabel)
        .accessibilityAddTraits(completed ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint(completed ? "连按取消完成" : "连按标记完成")
    }

    private var outline: Color {
        contrast == .increased || hovered ? Theme.textTertiary : Theme.border
    }

    private var a11yLabel: String {
        let state = completed ? "已完成" : "未完成"
        return label.isEmpty ? state : "\(state)：\(label)"
    }
}

/// A compact, tappable estimated-duration pill. Tap to pick a preset (or clear).
/// Editable form is used in the 整理 window; a read-only variant lives inline in
/// the capture popover.
struct DurationPill: View {
    let minutes: Int?
    var emptyLabel: String? = nil
    let onSelect: (Int?) -> Void
    @State private var pickerPresented = false

    /// Common planning durations (single source of truth, reused by pickers).
    static let presets: [Int] = [5, 10, 15, 25, 30, 45, 60, 90, 120]

    private var showsEmptyPrompt: Bool { minutes == nil && emptyLabel != nil }

    var body: some View {
        Button { pickerPresented = true } label: {
            HStack(spacing: 3) {
                Image(systemName: "clock").font(.system(size: 10))
                if let m = minutes {
                    Text(DurationLabel.short(m)).font(Theme.font(11, weight: .medium)).lineLimit(1)
                } else if let emptyLabel {
                    Text(emptyLabel).font(Theme.font(11, weight: .medium)).lineLimit(1)
                }
            }
            .foregroundColor(minutes == nil
                             ? Theme.textDim
                             : Theme.amber)
            .padding(.horizontal, minutes == nil && emptyLabel == nil ? 4 : 6)
            .padding(.vertical, 4)
            .frame(minHeight: 24)
            .contentShape(Rectangle())
            .background(
                RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                    .fill(minutes == nil ? (showsEmptyPrompt ? Theme.panel.opacity(0.4) : Color.clear)
                                         : Theme.amberSoft)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                    .stroke(minutes == nil
                            ? Theme.border.opacity(0.6)
                            : Theme.amber.opacity(0.35), lineWidth: 1)
            )
        }
        .buttonStyle(CompactPillStyle())
        .fixedSize()
        .help("预估时长")
        .accessibilityLabel("预估时长")
        .accessibilityValue(minutes.map { DurationLabel.short($0) } ?? "未设置")
        .popover(isPresented: $pickerPresented, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: 10) {
                Text("预估时长")
                    .font(Theme.font(13, weight: .medium))
                    .foregroundColor(Theme.text)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
                    ForEach(Self.presets, id: \.self) { m in
                        Button(DurationLabel.short(m)) { select(m) }
                            .buttonStyle(SecondaryActionStyle())
                    }
                }
                if minutes != nil {
                    Divider().overlay(Theme.hairline)
                    Button("清除时长") { select(nil) }
                        .buttonStyle(TertiaryActionStyle(tint: Theme.error))
                }
            }
            .padding(12)
            .frame(width: 224)
            .background(Theme.bg, in: RoundedRectangle(cornerRadius: Theme.Radius.popover, style: .continuous))
        }
    }

    private func select(_ value: Int?) {
        onSelect(value)
        pickerPresented = false
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
    @State private var pickerPresented = false

    var body: some View {
        Button { pickerPresented = true } label: {
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
                RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                    .fill(date == nil ? Color.clear : Theme.amberSoft)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                    .stroke(date == nil ? Theme.border : Theme.amber.opacity(0.35), lineWidth: 1)
            )
        }
        .buttonStyle(CompactPillStyle())
        .fixedSize()
        .help("提醒时间")
        .accessibilityLabel("提醒时间")
        .accessibilityValue(date.map { ReminderFormat.short($0) } ?? "未设置")
        .popover(isPresented: $pickerPresented) {
            VStack(alignment: .leading, spacing: 8) {
                Text("提醒时间").font(Theme.font(13, weight: .medium))
                ForEach(ReminderPresets.all(), id: \.label) { preset in
                    Button(preset.label) { onSelect(preset.date); pickerPresented = false }
                        .buttonStyle(SecondaryActionStyle())
                }
                if date != nil {
                    Button("清除提醒") { onSelect(nil); pickerPresented = false }
                        .buttonStyle(TertiaryActionStyle(tint: Theme.error))
                }
            }.padding(12).foregroundColor(Theme.text).background(Theme.bg, in: RoundedRectangle(cornerRadius: Theme.Radius.popover, style: .continuous))
        }
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
