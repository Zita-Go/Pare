import SwiftUI
import PareCore

// RFC-0001 T5: 预估 ↔ 实际校准 —— 完成任务后「就地」记录实际用时。
//
// 关键设计决策 #3：用「完成时快速选预设」，不做计时器。打勾完成后紧接着弹一排预设
// 时长 chip，点一下即记 `actualMinutes`，点「跳过」即收起、不记录，零负担。已记录后
// 由 `ActualTimePill` 展示并可随时修改 / 清除。
//
// 「未记录时不显示误导数字」：未记录时这里只出现一个可选的记录入口，绝不显示任何由
// 空数据推算出的用时或偏差。偏差「一瞥」的呈现在今日小结 / 周回顾里（消费 PareCore 的
// `EstimateBias`，同样在无数据时自动隐藏）。

/// 完成任务后就地弹出的一排预设时长 chip。点一个即记录实际用时；点「跳过」不记录。
/// 用横向滚动容纳所有预设，窄象限卡片与大动态字号下都不破版。
struct CompletionTimeChips: View {
    /// 复用估时预设，保持「常用时长」的单一来源。
    static let presets: [Int] = DurationPill.presets
    let onPick: (Int) -> Void
    let onSkip: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text("实际用了多久？")
                .font(Theme.font(11))
                .foregroundColor(Theme.textDim)
                .fixedSize()
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 5) {
                    ForEach(Self.presets, id: \.self) { m in
                        Button { onPick(m) } label: {
                            Text(DurationLabel.short(m))
                                .font(Theme.font(11, weight: .medium))
                                .foregroundColor(Theme.amber)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(Theme.amberSoft))
                                .overlay(Capsule().stroke(Theme.amber.opacity(0.35), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("实际用了 \(DurationLabel.short(m))")
                    }
                }
                .padding(.vertical, 1)
            }
            Button(action: onSkip) {
                Image(systemName: "xmark")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(Theme.textFaint)
                    .padding(3)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("跳过（不记录用时）")
            .accessibilityLabel("跳过记录实际用时")
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("记录实际用时")
        .accessibilityHint("选择一个时长记录，或跳过")
    }
}

/// 已记录实际用时后的紧凑药丸：勾形图标 + 时长（区别于估时的时钟图标）。点开菜单可改到
/// 其它预设或清除。用于「修改」实际用时。
struct ActualTimePill: View {
    let minutes: Int
    let onSelect: (Int?) -> Void
    @State private var pickerPresented = false

    var body: some View {
        Button { pickerPresented = true } label: {
            HStack(spacing: 3) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 10))
                Text(DurationLabel.short(minutes))
                    .font(Theme.font(11, weight: .medium)).lineLimit(1)
            }
            .foregroundColor(Theme.amber)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Theme.amberSoft))
            .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).stroke(Theme.amber.opacity(0.45), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .fixedSize()
        .help("实际用时")
        .accessibilityLabel("实际用时")
        .accessibilityValue(DurationLabel.short(minutes))
        .popover(isPresented: $pickerPresented) {
            VStack(alignment: .leading, spacing: 10) {
                Text("实际用时").font(Theme.font(12, weight: .semibold))
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
                    ForEach(DurationPill.presets, id: \.self) { m in
                        Button(DurationLabel.short(m)) { onSelect(m); pickerPresented = false }
                            .buttonStyle(SecondaryActionStyle())
                    }
                }
                Button("清除实际用时") { onSelect(nil); pickerPresented = false }
                    .buttonStyle(.plain).foregroundColor(Theme.qUrgent)
            }.padding(12).frame(width: 224).foregroundColor(Theme.text).background(Theme.bg)
        }
    }
}

/// 未记录、且 chip 提示已被收起时的一个克制入口：点它再次弹出记录（补记）。让键盘 /
/// 稍后补记的路径也能记录实际用时。
struct RecordActualButton: View {
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 3) {
                Image(systemName: "clock.arrow.circlepath").font(.system(size: 10))
                Text("记用时").font(Theme.font(11, weight: .medium))
            }
            .foregroundColor(Theme.textDim)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).stroke(Theme.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .help("记录实际用时")
        .accessibilityLabel("记录实际用时")
    }
}
