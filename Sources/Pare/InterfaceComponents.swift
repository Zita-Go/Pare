import SwiftUI

/// Pointer feedback changes paint only. Geometry and focus remain owned by the
/// real Button, so a hover never rebuilds rows or adds a keyboard stop.
struct ActionPaint: ViewModifier {
    enum Kind { case primary, warm, secondary, tertiary, icon, pill }
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var hovered = false
    let kind: Kind
    let pressed: Bool
    var tint: Color = Theme.textDim

    func body(content: Content) -> some View {
        content
            .foregroundColor(foreground)
            .background(
                RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                    .fill(fill)
            )
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                .strokeBorder(LinearGradient(
                    colors: [Theme.selectionText.opacity(enabled && (kind == .primary || kind == .warm) ? 0.14 : 0), .clear],
                    startPoint: .top, endPoint: .bottom), lineWidth: 1)
                .allowsHitTesting(false))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                .fill(enabled && pressed ? Color.black.opacity(0.12) : (enabled && hovered ? Theme.hover : .clear))
                .allowsHitTesting(false))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                .strokeBorder(boundary, lineWidth: 1)
                .allowsHitTesting(false))
            .opacity(enabled ? 1 : 0.55)
            .onHover { hovered = $0 }
            .animation(Theme.motion(reduceMotion), value: hovered)
            .animation(Theme.motion(reduceMotion, duration: 0.08), value: pressed)
    }

    private var foreground: Color {
        guard enabled else { return Theme.textTertiary }
        switch kind {
        case .primary: return Theme.bg
        case .warm: return Theme.amber
        case .secondary: return Theme.text
        case .tertiary, .icon, .pill: return tint
        }
    }

    private var fill: LinearGradient {
        let colors: [Color]
        switch kind {
        case .primary: colors = enabled ? [Theme.goldTop, Theme.goldBase] : [Theme.surfaceRaised, Theme.surfaceRaised]
        case .warm: colors = [Theme.warmTop, Theme.warmBase]
        case .secondary: colors = [Theme.surfaceRaised, Theme.surfaceRaised]
        case .tertiary, .icon, .pill: colors = [.clear, .clear]
        }
        return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private var boundary: Color {
        if contrast == .increased { return Theme.textTertiary.opacity(0.8) }
        if kind == .warm { return Theme.amber.opacity(0.22) }
        return kind == .secondary ? Theme.separator : .clear
    }
}

struct SecondaryActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(12, weight: .medium))
            .padding(.horizontal, 11).padding(.vertical, 7)
            .frame(minHeight: 30)
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.control))
            .modifier(ActionPaint(kind: .secondary, pressed: configuration.isPressed))
    }
}

/// Same hit area as a secondary action, with quiet warmth for the AI entry.
struct WarmActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(12, weight: .medium))
            .padding(.horizontal, 11).padding(.vertical, 7)
            .frame(minHeight: 30)
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.control))
            .modifier(ActionPaint(kind: .warm, pressed: configuration.isPressed))
    }
}

struct TertiaryActionStyle: ButtonStyle {
    var tint: Color = Theme.textDim
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(12))
            .padding(.horizontal, 8).padding(.vertical, 5)
            .frame(minHeight: 28)
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.control))
            .modifier(ActionPaint(kind: .tertiary, pressed: configuration.isPressed, tint: tint))
    }
}

struct IconActionStyle: ButtonStyle {
    var tint: Color = Theme.textDim
    var size: CGFloat = 28
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(width: size, height: size)
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.control))
            .modifier(ActionPaint(kind: .icon, pressed: configuration.isPressed, tint: tint))
    }
}

/// Inline pills keep their existing measured width and their on-demand popover.
struct CompactPillStyle: ButtonStyle {
    var tint: Color = Theme.textDim
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(ActionPaint(kind: .pill, pressed: configuration.isPressed, tint: tint))
    }
}

enum FeedbackKind {
    case success, error, info, warning

    var color: Color {
        switch self {
        case .success: return Theme.success
        case .error: return Theme.error
        case .info: return Theme.info
        case .warning: return Theme.amber
        }
    }

    var symbol: String {
        switch self {
        case .success: return "checkmark.circle.fill"
        case .error: return "exclamationmark.circle.fill"
        case .info: return "info.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        }
    }
}

struct FeedbackBanner: View {
    let text: String
    let kind: FeedbackKind
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: kind.symbol)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(kind.color)
                .padding(.top, 1)
                .accessibilityHidden(true)
            Text(text).font(Theme.font(12)).foregroundColor(Theme.text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
            .fill(kind.color.opacity(0.09)))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
            .strokeBorder(kind.color.opacity(contrast == .increased ? 0.8 : 0.25), lineWidth: 1)
            .allowsHitTesting(false))
        .accessibilityElement(children: .combine)
    }
}

struct InfoBadge: View {
    let text: String
    var symbol: String? = nil
    var accent: Color = Theme.textDim
    var body: some View {
        HStack(spacing: 4) {
            if let symbol { Image(systemName: symbol).accessibilityHidden(true) }
            Text(text)
        }
        .font(Theme.font(11, weight: .medium)).foregroundColor(accent)
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(Capsule().fill(accent.opacity(0.09)))
        .fixedSize()
    }
}

struct PageHeading: View {
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(Theme.Typography.title).foregroundColor(Theme.text)
            Text(subtitle).font(Theme.font(12)).foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SegmentedTabs<Value: Hashable>: View {
    let values: [Value]
    @Binding var selection: Value
    let label: (Value) -> String
    var body: some View {
        HStack(spacing: 0) {
            ForEach(values, id: \.self) { value in
                Button { selection = value } label: {
                    Text(label(value)).font(Theme.font(12, weight: .medium))
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .foregroundColor(selection == value ? Theme.selectionText : Theme.textDim)
                        .selectionSurface(selected: selection == value)
                        .padding(.horizontal, 1.5).contentShape(Rectangle())
                }.buttonStyle(CompactPillStyle()).accessibilityAddTraits(selection == value ? .isSelected : [])
            }
        }.padding(3).background(RoundedRectangle(cornerRadius: Theme.Radius.card).fill(Theme.panel.opacity(0.45)))
    }
}

/// Keep the actual answer visible as a preview; expanding always reveals the
/// complete original text, without rewriting or dropping model information.
struct ExpandableNote: View {
    let title: String
    let text: String
    @State private var expanded = false
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text(title).font(Theme.font(11, weight: .medium)).foregroundColor(Theme.textDim)
                Spacer()
                Button(expanded ? "收起分析" : "展开分析") { expanded.toggle() }
                    .buttonStyle(TertiaryActionStyle(tint: Theme.amber))
            }
            Text(text).font(Theme.font(13)).foregroundColor(Theme.textDim)
                .lineSpacing(5).lineLimit(expanded ? nil : 2)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Theme.Radius.card).fill(Theme.panel.opacity(0.25)))
    }
}
