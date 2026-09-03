import AppKit
import SwiftUI
import PareCore

/// The "本周回顾" window (RFC-0001 T3). A read-only look back over the natural
/// week: how much got finished, where the effort landed (quadrant map), what
/// keeps getting dragged, and how the estimates fared. One button copies the
/// whole thing as Markdown so it can be pasted into any notes app.
///
/// It opens on demand — from the Sunday-evening `weekly.review` notification, the
/// menu-bar right-click menu, or the 整理 window — so it lives in its own window
/// (like the settings panel) rather than being welded into the organize view.
final class WeeklyReviewWindowController: NSWindowController, NSWindowDelegate {

    init(store: Store) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 620),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false)
        window.title = "本周回顾"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isOpaque = false
        window.backgroundColor = .clear
        window.appearance = NSAppearance(named: .darkAqua)
        window.isReleasedWhenClosed = false
        window.minSize = NSSize(width: 420, height: 480)

        let hosting = NSHostingController(rootView: WeeklyReviewView(store: store))
        window.contentViewController = hosting
        window.center()

        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func present() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        if !AppWindows.anyTitledVisible(excluding: window) {
            NSApp.setActivationPolicy(.accessory)
        }
    }
}

// MARK: - WeeklyReviewView

private struct WeeklyReviewView: View {
    @ObservedObject var store: Store
    /// Momentary "已复制 ✓" feedback after writing to the pasteboard.
    @State private var copied = false

    var body: some View {
        // Recomputed each render from the single source of truth, so opening the
        // window (or completing a task while it is open) always reflects fresh
        // data. The projection is pure and cheap.
        let review = store.weeklyReview()
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header(review)

                if review.isEmpty {
                    emptyState
                } else {
                    completedStat(review)
                    quadrantSection(review)
                    biasSection(review)
                }

                if !review.stalledFocus.isEmpty {
                    stalledSection(review)
                }

                copyButton(review)
            }
            .padding(.horizontal, 22)
            .padding(.top, 34)
            .padding(.bottom, 22)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minWidth: 420, minHeight: 480)
        .background(FrostBackground(tint: 0.62))
    }

    // MARK: Header

    private func header(_ review: WeeklyReview) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("本周回顾")
                .font(Theme.font(22, weight: .semibold))
                .foregroundColor(Theme.text)
            Text(Self.rangeText(review))
                .font(Theme.font(13))
                .foregroundColor(Theme.textDim)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Empty week — "刚开始"

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 17))
                    .foregroundColor(Theme.amber)
                Text("这周才刚开始")
                    .font(Theme.font(16, weight: .medium))
                    .foregroundColor(Theme.text)
            }
            Text("还没有完成的事。做完几件再回来看看，这里会长出你这周的轨迹。")
                .font(Theme.font(13))
                .foregroundColor(Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(corner: 14)
    }

    // MARK: Completed count

    private func completedStat(_ review: WeeklyReview) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("\(review.completedCount)")
                .font(Theme.font(40, weight: .semibold))
                .foregroundColor(Theme.amber)
            Text("件完成")
                .font(Theme.font(15))
                .foregroundColor(Theme.textDim)
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(corner: 14)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("本周完成 \(review.completedCount) 件")
    }

    // MARK: Quadrant distribution

    private func quadrantSection(_ review: WeeklyReview) -> some View {
        let maxCount = max(1, review.byQuadrant.values.max() ?? 0)
        return card(title: "按象限分布") {
            VStack(spacing: 9) {
                ForEach(Quadrant.allCases, id: \.self) { q in
                    QuadrantBar(quadrant: q,
                                count: review.byQuadrant[q] ?? 0,
                                maxCount: maxCount)
                }
            }
        }
    }

    // MARK: Estimate bias

    private func biasSection(_ review: WeeklyReview) -> some View {
        card(title: "估时校准") {
            Text(Self.biasText(review.bias))
                .font(Theme.font(13))
                .foregroundColor(review.bias.hasData ? Theme.text : Theme.textDim)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: Stalled quadrant-II items — "一直被拖的事"

    private func stalledSection(_ review: WeeklyReview) -> some View {
        card(title: "象限二 · 一直被拖的事") {
            VStack(alignment: .leading, spacing: 7) {
                ForEach(review.stalledFocus) { task in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle()
                            .fill(Theme.accent(for: .importantNotUrgent))
                            .frame(width: 6, height: 6)
                        Text(task.text)
                            .font(Theme.font(13))
                            .foregroundColor(Theme.text)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 6)
                        Text("已 \(Self.stalledDays(task)) 天没动")
                            .font(Theme.font(11))
                            .foregroundColor(Theme.textDim)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    // MARK: Copy Markdown

    private func copyButton(_ review: WeeklyReview) -> some View {
        HStack {
            Button { copyMarkdown(review.markdown) } label: {
                HStack(spacing: 6) {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 12, weight: .medium))
                    Text(copied ? "已复制 ✓" : "复制 Markdown")
                        .font(Theme.font(13, weight: .medium))
                }
                .foregroundColor(copied ? Theme.bg : Theme.text)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(copied ? Theme.amber : Color.clear)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .stroke(copied ? Theme.amber : Theme.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
            .help("把本周回顾复制成 Markdown，可粘贴到任意笔记")
            .accessibilityLabel(copied ? "已复制到剪贴板" : "复制 Markdown 到剪贴板")
            Spacer()
        }
        .padding(.top, 2)
    }

    private func copyMarkdown(_ markdown: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(markdown, forType: .string)
        copied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
    }

    // MARK: - Small building blocks

    @ViewBuilder
    private func card<Content: View>(title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(Theme.font(14, weight: .semibold))
                .foregroundColor(Theme.text)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(corner: 14)
    }

    // MARK: - Formatting helpers (kept local so T3 stays self-contained)

    private static let rangeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M月d日"
        return f
    }()

    /// "1月12日 – 1月18日" — the inclusive Monday–Sunday span. `weekEnd` is the
    /// exclusive next-Monday boundary, so the shown last day is one day before.
    private static func rangeText(_ review: WeeklyReview) -> String {
        let lastDay = Calendar.current.date(byAdding: .day, value: -1, to: review.weekEnd) ?? review.weekEnd
        return "\(rangeFormatter.string(from: review.weekStart)) – \(rangeFormatter.string(from: lastDay))"
    }

    /// Whole calendar days a stalled task has gone without progress, mirroring
    /// the Markdown ("已 N 天没动"): measured from `lastProgressAt`, falling back
    /// to `createdAt` when the task was never explicitly progressed.
    private static func stalledDays(_ task: TaskItem) -> Int {
        let cal = Calendar.current
        let ref = task.lastProgressAt ?? task.createdAt
        return cal.dateComponents([.day],
                                  from: cal.startOfDay(for: ref),
                                  to: cal.startOfDay(for: Date())).day ?? 0
    }

    /// A one-line "预估 vs 实际" summary built from the public bias fields, so the
    /// weekly view does not depend on PareCore's private Markdown helpers.
    private static func biasText(_ bias: EstimateBias) -> String {
        guard bias.hasData else {
            return "还没有可对比的「预估 vs 实际」记录。完成任务时顺手记一下实际用时，这里就会长出来。"
        }
        let est = DurationLabel.short(bias.totalEstimated)
        let act = DurationLabel.short(bias.totalActual)
        if bias.deltaMinutes == 0 {
            return "记录了 \(bias.comparableCount) 件的实际用时：预估与实际都是 \(act)，估得很准 👍"
        }
        let direction = bias.deltaMinutes > 0 ? "低估" : "高估"
        let avg = abs(bias.averageDeltaMinutes)
        let magnitude = avg >= 1
            ? "平均每件\(direction)约 \(DurationLabel.short(avg))"
            : "合计\(direction)约 \(DurationLabel.short(abs(bias.deltaMinutes)))"
        return "记录了 \(bias.comparableCount) 件：预估合计 \(est) · 实际合计 \(act) · \(magnitude)。"
    }
}

// MARK: - Quadrant distribution bar

/// One row of the quadrant map: a colored dot + title, a proportional bar in the
/// quadrant's own accent color, and the count. Zero-count quadrants stay dimmed
/// so the distribution reads at a glance without hiding the empty ones.
private struct QuadrantBar: View {
    let quadrant: Quadrant
    let count: Int
    let maxCount: Int

    private var accent: Color { Theme.accent(for: quadrant) }
    private var fraction: Double { maxCount > 0 ? Double(count) / Double(maxCount) : 0 }
    private var isEmpty: Bool { count == 0 }

    var body: some View {
        HStack(spacing: 9) {
            Circle()
                .fill(accent.opacity(isEmpty ? 0.35 : 1))
                .frame(width: 7, height: 7)
            Text(quadrant.title)
                .font(Theme.font(12, weight: .medium))
                .foregroundColor(isEmpty ? Theme.textDim : Theme.text)
                .lineLimit(1)
                .frame(width: 92, alignment: .leading)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.hairline)
                    Capsule()
                        .fill(accent.opacity(isEmpty ? 0 : 0.85))
                        .frame(width: max(isEmpty ? 0 : 6, fraction * geo.size.width))
                }
            }
            .frame(height: 7)
            Text("\(count)")
                .font(Theme.font(12, weight: .semibold))
                .foregroundColor(isEmpty ? Theme.textFaint : accent)
                .frame(minWidth: 16, alignment: .trailing)
                .monospacedDigit()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(quadrant.title)：完成 \(count) 件")
    }
}
