import SwiftUI

struct AIPrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(13, weight: .medium))
            .padding(.horizontal, 18).padding(.vertical, 10)
            .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.control))
            .modifier(ActionPaint(kind: .primary, pressed: configuration.isPressed))
    }
}

struct AISelectionToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.system(size: 15)).foregroundColor(configuration.isOn ? Theme.amber : Theme.textFaint)
                    .accessibilityHidden(true)
                configuration.label.foregroundColor(Theme.text).frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.vertical, 3).contentShape(Rectangle())
        }
        .buttonStyle(CompactPillStyle())
        .accessibilityAddTraits(configuration.isOn ? .isSelected : [])
    }
}
