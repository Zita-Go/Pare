import SwiftUI
import AppKit
import PareCore

/// Design tokens — "深夜书桌感": a near-black desk, warm off-white ink, and a
/// single amber accent for completion and today's focus. This version adds a
/// frosted-glass backdrop and a low-saturation per-quadrant palette so the four
/// quadrants read at a glance without shouting.
enum Theme {
    // MARK: Base
    static let bg        = Color(hex: 0x1A1F1C)   // 近黑底
    static let text      = Color(hex: 0xF0EDE6)   // 暖白字
    static let amber     = Color(hex: 0xE8C87A)   // 琥珀金（完成 / 今日焦点）
    static let amberSoft = Color(hex: 0xE8C87A).opacity(0.14)
    static let panel     = Color(hex: 0x2D3B35)   // 面板底
    static let panelHi   = Color(hex: 0x37473F)   // hover / 抬起态
    static let border    = Color(hex: 0x3D5046)   // 边界
    static let hairline  = Color(hex: 0xF0EDE6).opacity(0.07)
    // Small helper text appears on translucent green panels, where the old
    // 50%/28% values fell below comfortable contrast. Keep the hierarchy with
    // two steps, but both now remain readable at 11–13 pt.
    static let textDim   = Color(hex: 0xF0EDE6).opacity(0.68)
    static let textFaint = Color(hex: 0xF0EDE6).opacity(0.56)

    static let nsBg = NSColor(hex: 0x1A1F1C)

    // MARK: Quadrant accents — 低饱和，压在深色调里，克制不喧宾夺主
    static let qUrgent   = Color(hex: 0xE2836E)   // 重要·紧急  珊瑚红 · 马上做
    static let qFocus    = Color(hex: 0xE8C87A)   // 重要·不紧急 琥珀金 · 甜点区
    static let qDelegate = Color(hex: 0x82A9C9)   // 紧急·不重要 雾蓝 · 可委托
    static let qTrivial  = Color(hex: 0x8C9A90)   // 不重要     灰绿 · 有空再说

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
    var tint: Double = 0.6
    var material: NSVisualEffectView.Material = .hudWindow

    var body: some View {
        VisualEffectView(material: material, blending: .behindWindow)
            .overlay(Theme.bg.opacity(tint))
            .overlay(
                LinearGradient(
                    colors: [Color.white.opacity(0.05), Color.clear, Color.black.opacity(0.10)],
                    startPoint: .top, endPoint: .bottom)
            )
            .ignoresSafeArea()
    }
}

// MARK: - Reusable surfaces

/// A raised, rounded panel used for cards and wells. Optionally tinted by a
/// quadrant accent and highlighted (hover / drag-target).
struct Panel: ViewModifier {
    var corner: CGFloat = 12
    var accent: Color? = nil
    var highlighted: Bool = false
    var fill: Double = 0.5

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .fill(Theme.panel.opacity(fill))
                    .overlay(
                        RoundedRectangle(cornerRadius: corner, style: .continuous)
                            .fill((accent ?? .clear).opacity(0.05))
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .stroke(strokeColor, lineWidth: highlighted ? 1.6 : 1)
            )
            .shadow(color: Color.black.opacity(highlighted ? 0.28 : 0.16),
                    radius: highlighted ? 12 : 7, x: 0, y: highlighted ? 5 : 3)
    }

    private var strokeColor: Color {
        if highlighted { return (accent ?? Theme.amber).opacity(0.9) }
        return Theme.border.opacity(0.9)
    }
}

extension View {
    /// Rounded, raised panel surface. See `Panel`.
    func panel(corner: CGFloat = 12, accent: Color? = nil,
               highlighted: Bool = false, fill: Double = 0.5) -> some View {
        modifier(Panel(corner: corner, accent: accent, highlighted: highlighted, fill: fill))
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
