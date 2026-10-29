import SwiftUI
import AppKit
import PareCore

/// Graphite and sage surfaces, warm readable text, and a restrained gold accent.
/// Semantic colors distinguish action, completion, errors and information.
enum Theme {
    static let bg             = Color(hex: 0x191E1C)
    static let text           = Color(hex: 0xF2F0EA)
    static let textSecondary  = Color(hex: 0xBDC6C0)
    static let textTertiary    = Color(hex: 0x9FAAA3)
    static let surface        = Color(hex: 0x29312D)
    static let surfaceRaised  = Color(hex: 0x333D37)
    static let field          = Color(hex: 0x202723)
    static let hover          = Color.white.opacity(0.055)
    static let border         = Color(hex: 0x526057)
    static let separator      = Color(hex: 0xD6DED8).opacity(0.10)
    static let amber          = Color(hex: 0xE8C87A)
    static let amberSoft      = amber.opacity(0.12)
    static let focus          = amber
    static let success        = Color(hex: 0x99C7AA)
    static let error          = Color(hex: 0xEFA494)
    static let info           = Color(hex: 0x9BBFD9)

    // Existing call sites keep the same API while sharing one surface hierarchy.
    static let panel = surface
    static let panelHi = surfaceRaised
    static let hairline = separator
    static let textDim = textSecondary
    static let textFaint = textTertiary
    static let nsBg = NSColor(hex: 0x191E1C)

    static func motion(_ reduceMotion: Bool, duration: Double = 0.16) -> Animation? {
        reduceMotion ? nil : .easeOut(duration: duration)
    }

    enum Radius {
        static let control: CGFloat = 8
        static let card: CGFloat = 12
        static let popover: CGFloat = 18
    }

    enum Typography {
        static let title = Theme.font(20, weight: .medium)
        static let section = Theme.font(14, weight: .medium)
        static let body = Theme.font(13)
        static let secondary = Theme.font(11)
    }

    static let qUrgent = error
    static let qFocus = amber
    static let qDelegate = info
    static let qTrivial = textTertiary

    static func accent(for q: Quadrant) -> Color {
        switch q {
        case .importantUrgent:    return qUrgent
        case .importantNotUrgent: return qFocus
        case .urgentNotImportant: return qDelegate
        case .neither:            return qTrivial
        }
    }

    // MARK: Type — PingFang SC for clean CJK + Latin mixed setting.
    /// A themed font at `size`.
    /// RFC-0001: 尊重系统动态字号 —— 用 `relativeTo:` 把自定义字体锚在一个语义文本
    /// 样式上，让它随「系统设置 → 辅助功能 → 显示 → 文字大小」缩放，而不是钉死像素。
    /// `size` 映射到最接近的文本样式，大字号下整体比例仍协调、不破版。
    static func font(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        Font.custom("PingFang SC", size: size, relativeTo: textStyle(for: size)).weight(weight)
    }

    /// 把像素字号映射到最接近的语义文本样式，作为动态缩放的锚点。
    private static func textStyle(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<11.5: return .caption2
        case ..<12.5: return .caption
        case ..<13.5: return .footnote
        case ..<15.5: return .subheadline
        case ..<17.5: return .body
        case ..<20.5: return .title3
        case ..<24.5: return .title2
        default:      return .title
        }
    }
}

// MARK: - Frosted glass

/// Thin SwiftUI wrapper over `NSVisualEffectView` for a real system blur.
struct VisualEffectView: NSViewRepresentable {
    var material: NSVisualEffectView.Material = .hudWindow
    var blending: NSVisualEffectView.BlendingMode = .behindWindow

    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = material
        v.blendingMode = blending
        v.state = .active
        return v
    }

    func updateNSView(_ v: NSVisualEffectView, context: Context) {
        v.material = material
        v.blendingMode = blending
    }
}

/// Frosted-glass backdrop: a system blur tinted with our near-black desk color,
/// so the 深夜书桌 mood survives the vibrancy. Use as the bottom-most layer of a
/// window / popover whose host window is transparent (see the window controllers
/// and `PopoverController`). A faint top-lit gradient adds a little depth.
struct FrostBackground: View {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    var tint: Double = 0.6
    var material: NSVisualEffectView.Material = .hudWindow

    var body: some View {
        Group {
            if reduceTransparency || contrast == .increased { Theme.bg }
            else {
                VisualEffectView(material: material, blending: .behindWindow)
                    .overlay(Theme.bg.opacity(max(0.88, tint)))
            }
        }
            .overlay(
                LinearGradient(
                    colors: [Color.white.opacity(0.025), Color.clear, Color.black.opacity(0.04)],
                    startPoint: .top, endPoint: .bottom)
            )
            .ignoresSafeArea()
    }
}

// MARK: - Reusable surfaces

/// Card surfaces use fill for hierarchy; a stronger boundary is reserved for
/// selection and increased-contrast settings. The existing API remains intact.
struct Panel: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    var corner: CGFloat = Theme.Radius.card
    var accent: Color? = nil
    var highlighted: Bool = false
    var fill: Double = 0.8

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .fill(Theme.surface.opacity(reduceTransparency ? 1 : max(0.72, fill)))
                    .overlay(
                        RoundedRectangle(cornerRadius: corner, style: .continuous)
                            .fill((accent ?? .clear).opacity(highlighted ? 0.07 : 0.025))
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .strokeBorder(strokeColor, lineWidth: highlighted ? 1.5 : 1)
                    .allowsHitTesting(false)
            )
    }

    private var strokeColor: Color {
        if highlighted { return accent ?? Theme.focus }
        return contrast == .increased ? Theme.textTertiary.opacity(0.75) : Theme.separator
    }
}

struct InputSurface: ViewModifier {
    @Environment(\.colorSchemeContrast) private var contrast
    var focused: Bool = false

    func body(content: Content) -> some View {
        content
            .background(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                .fill(Theme.field))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                .strokeBorder(focused ? Theme.focus : Theme.border.opacity(contrast == .increased ? 1 : 0.55),
                              lineWidth: focused ? 1.5 : 1)
                .allowsHitTesting(false))
    }
}

extension View {
    func panel(corner: CGFloat = Theme.Radius.card, accent: Color? = nil,
               highlighted: Bool = false, fill: Double = 0.8) -> some View {
        modifier(Panel(corner: corner, accent: accent, highlighted: highlighted, fill: fill))
    }

    /// Surface only. The caller owns its padding, size and actual input focus.
    func inputSurface(focused: Bool = false) -> some View {
        modifier(InputSurface(focused: focused))
    }
}

extension Color {
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: 1)
    }
}

extension NSColor {
    convenience init(hex: UInt32) {
        let r = CGFloat((hex >> 16) & 0xFF) / 255.0
        let g = CGFloat((hex >> 8) & 0xFF) / 255.0
        let b = CGFloat(hex & 0xFF) / 255.0
        self.init(srgbRed: r, green: g, blue: b, alpha: 1)
    }
}
