import SwiftUI

struct AIPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(13, weight: .semibold))
            .foregroundColor(enabled ? Theme.bg : Theme.textDim)
            .padding(.horizontal, 18).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 9).fill(enabled ? Theme.amber : Theme.panel))
            .opacity(configuration.isPressed ? 0.82 : 1)
    }
}

struct AISelectionToggleStyle: ToggleStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.system(size: 15)).foregroundColor(configuration.isOn ? Theme.amber : Theme.textFaint)
                    .accessibilityHidden(true)
                configuration.label.foregroundColor(Theme.text).frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.vertical, 3).contentShape(Rectangle())
        }
        .buttonStyle(.plain).opacity(enabled ? 1 : 0.45)
        .accessibilityAddTraits(configuration.isOn ? .isSelected : [])
    }
}
