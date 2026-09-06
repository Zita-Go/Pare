import SwiftUI

/// Shared reading hierarchy. Secondary actions never rebuild rows on hover.
struct SecondaryActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(12, weight: .medium))
            .foregroundColor(enabled ? Theme.text : Theme.textFaint)
            .padding(.horizontal, 11).padding(.vertical, 7)
            .frame(minHeight: 30)
            .background(RoundedRectangle(cornerRadius: 8).fill(Theme.panel.opacity(configuration.isPressed ? 0.95 : 0.55)))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.border.opacity(0.55)))
            .opacity(enabled ? 1 : 0.55)
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
            Text(title).font(Theme.font(21, weight: .semibold)).foregroundColor(Theme.text)
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
                        .foregroundColor(selection == value ? Theme.bg : Theme.textDim)
                        .background(RoundedRectangle(cornerRadius: 7).fill(selection == value ? Theme.amber : .clear))
                        .padding(.horizontal, 1.5).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityAddTraits(selection == value ? .isSelected : [])
            }
        }.padding(3).background(RoundedRectangle(cornerRadius: 10).fill(Theme.panel.opacity(0.45)))
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
                    .buttonStyle(.plain).font(Theme.font(11)).foregroundColor(Theme.amber)
                    .padding(.vertical, 4).contentShape(Rectangle())
            }
            Text(text).font(Theme.font(13)).foregroundColor(Theme.textDim)
                .lineSpacing(5).lineLimit(expanded ? nil : 2)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.panel.opacity(0.25)))
    }
}
