import AppKit
import SwiftUI
import PareCore

/// Hosts the "随手记" capture panel.
///
/// Why a custom `NSPanel` instead of `NSPopover`: an `NSPopover` anchored to the
/// status item gets clipped by the top of the screen when another app is in
/// native full screen (its own Space) — the popover's window can't join that
/// Space and its position isn't clamped, so the top of the card is cut off
/// (that's the "全屏下展示不全" bug). This panel declares
/// `.canJoinAllSpaces + .fullScreenAuxiliary` and clamps its frame to the active
/// screen's `visibleFrame`, so it always shows *whole*, floating above the
/// full-screen app — and because it's a non-activating panel we don't steal the
/// full-screen app away. It also lets us fully own the present/dismiss motion.
///
/// Transient behaviour ("记完关掉") is reproduced with event monitors: click
/// outside or press Esc to dismiss.
final class PopoverController {

    /// Bridges AppKit show/close into the SwiftUI entrance/exit animation.
    final class Chrome: ObservableObject {
        @Published var visible = false
        /// Bumped on every show so the capture field re-focuses each time the
        /// panel appears. The panel reuses one SwiftUI view for the app's whole
        /// lifetime, so `onAppear` fires only once — without this, "打开即写"
        /// (cursor already waiting) would only hold on the very first open.
        @Published var focusTick = 0
        @Published var suggestedDraft: String?
    }

    private let store: Store
    private let chrome = Chrome()
    private let panel: CapturePanel
    private let hosting: NSHostingView<AnyView>

    /// Transparent breathing room around the card so its soft shadow isn't
    /// clipped by the (borderless, transparent) window bounds.
    private let shadowMargin: CGFloat = 26

    private var showing = false
    /// Debounces the "click the menu-bar icon to close" path: the outside-click
    /// monitor may fire for the same click that also drives the toggle action.
    private var lastCloseAt = Date.distantPast
    private var monitors: [Any] = []

    var isShown: Bool { showing }

    init(store: Store, onOpenOrganize: @escaping () -> Void,
         onOpenTaskDetails: @escaping (UUID) -> Void) {
        self.store = store
        self.panel = CapturePanel()
        self.hosting = NSHostingView(rootView: AnyView(EmptyView()))

        // All stored properties are initialized; `self` is now usable, so the
        // 整理 button can close the panel before opening the organize window.
        let container = CapturePanelContainer(chrome: chrome, margin: shadowMargin) {
            CaptureView(store: store, chrome: chrome, onOpenOrganize: { [weak self] in
                self?.close()
                onOpenOrganize()
            })
            .environment(\.openTaskDetails, { [weak self] id in
                self?.close()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                    onOpenTaskDetails(id)
                }
            })
        }
        hosting.rootView = AnyView(container)
        hosting.translatesAutoresizingMaskIntoConstraints = true
        panel.contentView = hosting
    }

    func toggle(relativeTo button: NSStatusBarButton?) {
        if showing { close() } else { show(relativeTo: button) }
    }

    func show(relativeTo button: NSStatusBarButton?, prefill: String? = nil) {
        // Swallow the re-open that a click-on-icon close would otherwise trigger.
        if Date().timeIntervalSince(lastCloseAt) < 0.2 { return }
        showing = true
        if let prefill { chrome.suggestedDraft = prefill }

        // Size to the current content (today's list length changes the height).
        hosting.layoutSubtreeIfNeeded()
        panel.setContentSize(hosting.fittingSize)
        position(relativeTo: button)

        chrome.visible = false
        panel.alphaValue = 0
        panel.makeKeyAndOrderFront(nil)

        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.18
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            panel.animator().alphaValue = 1
        }
        // Flip on the next runloop so SwiftUI animates from the hidden state,
        // and bump focusTick so the capture field re-focuses on every open.
        DispatchQueue.main.async { [weak self] in
            self?.chrome.visible = true
            self?.chrome.focusTick += 1
        }
        installMonitors()
    }

    func close() {
        guard showing else { return }
        showing = false
        lastCloseAt = Date()
        removeMonitors()

        chrome.visible = false
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.16
            ctx.timingFunction = CAMediaTimingFunction(name: .easeIn)
            panel.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            guard let self = self, !self.showing else { return }
            self.panel.orderOut(nil)
        })
    }

    // MARK: Positioning

    /// Places the panel just below the menu-bar icon, then clamps it fully inside
    /// the active screen's visible area. The clamp is what fixes the full-screen
    /// top-clipping: even if the (auto-hidden) menu bar reports an edge position,
    /// the whole card — including its transparent shadow margin — stays on screen.
    private func position(relativeTo button: NSStatusBarButton?) {
        let size = panel.frame.size
        let screen = button?.window?.screen ?? NSScreen.main
        let vf = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)

        var origin: NSPoint
        if let button = button, let win = button.window {
            let b = win.convertToScreen(button.convert(button.bounds, to: nil))
            origin = NSPoint(x: b.midX - size.width / 2, y: b.minY - size.height)
        } else {
            origin = NSPoint(x: vf.maxX - size.width, y: vf.maxY - size.height)
        }
        origin.x = min(max(origin.x, vf.minX), vf.maxX - size.width)
        origin.y = min(max(origin.y, vf.minY), vf.maxY - size.height)
        panel.setFrameOrigin(origin)
    }

    // MARK: Transient dismissal

    private func installMonitors() {
        removeMonitors()
        // Click in another app / the desktop / the full-screen app → dismiss.
        if let g = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] _ in
            self?.close()
        }) {
            monitors.append(g)
        }
        // Click in our own space but outside the panel → dismiss (let it through).
        if let l = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] event in
            guard let self = self else { return event }
            // SwiftUI popovers (task details, menus and date pickers) live in
            // separate borderless windows. Treat those and true child windows
            // as part of the capture interaction instead of closing their
            // parent panel on the first click.
            if !self.isCaptureInteractionWindow(event.window) { self.close() }
            return event
        }) {
            monitors.append(l)
        }
        // Esc → dismiss (swallow the key).
        if let k = NSEvent.addLocalMonitorForEvents(matching: [.keyDown], handler: { [weak self] event in
            // A detail popover gets first chance to handle Esc. Only swallow it
            // when keyboard focus belongs to the capture panel itself.
            if event.keyCode == 53, event.window === self?.panel {
                self?.close()
                return nil
            }
            return event
        }) {
            monitors.append(k)
        }
    }

    private func isCaptureInteractionWindow(_ window: NSWindow?) -> Bool {
        guard let window else { return false }
        if window === panel { return true }
        var ancestor = window.parent
        while let current = ancestor {
            if current === panel { return true }
            ancestor = current.parent
        }
        // Native SwiftUI popovers and menus are borderless auxiliary windows.
        // Other real Pare windows are titled and should still dismiss capture.
        return !window.styleMask.contains(.titled)
    }

    private func removeMonitors() {
        monitors.forEach { NSEvent.removeMonitor($0) }
        monitors.removeAll()
    }
}

// MARK: - Custom panel

/// Borderless, non-activating, transparent panel that can float above other
/// apps' full-screen Spaces. Transparency lets the SwiftUI `FrostBackground`
/// provide the real blur; the rounded card + shadow are drawn in SwiftUI.
private final class CapturePanel: NSPanel {
    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 424, height: 320),
                   styleMask: [.borderless, .nonactivatingPanel],
                   backing: .buffered, defer: false)
        isFloatingPanel = true
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        isMovableByWindowBackground = false
        hidesOnDeactivate = false
        animationBehavior = .none
    }

    // Borderless panels don't become key by default; we need it for text input.
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

// MARK: - Animated container

/// Wraps the capture card with the rounded clip, shadow and the present/dismiss
/// motion — a lively spring with a little travel, depth and a brief blur-in
/// (the "更炫" tone the user chose). Anchored at the top so it drops in from the
/// menu bar.
private struct CapturePanelContainer<Content: View>: View {
    @ObservedObject var chrome: PopoverController.Chrome
    let margin: CGFloat
    private let content: Content

    init(chrome: PopoverController.Chrome, margin: CGFloat, @ViewBuilder content: () -> Content) {
        self.chrome = chrome
        self.margin = margin
        self.content = content()
    }

    var body: some View {
        content
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Theme.border.opacity(0.85), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.42), radius: 26, x: 0, y: 13)
            .scaleEffect(chrome.visible ? 1 : 0.90, anchor: .top)
            .offset(y: chrome.visible ? 0 : -16)
            .opacity(chrome.visible ? 1 : 0)
            .blur(radius: chrome.visible ? 0 : 6)
            .padding(margin)
            .animation(.spring(response: 0.42, dampingFraction: 0.70), value: chrome.visible)
    }
}
