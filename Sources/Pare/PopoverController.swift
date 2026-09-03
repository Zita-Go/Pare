import AppKit
import SwiftUI
import PareCore

/// Hosts the "随手记" capture panel in a transient popover anchored to the menu
/// bar item. Transient behaviour means it auto-dismisses when you click away —
/// "记完关掉".
final class PopoverController {
    private let popover = NSPopover()
    var isShown: Bool { popover.isShown }

    init(store: Store, onOpenOrganize: @escaping () -> Void) {
        popover.behavior = .transient
        popover.animates = true
        popover.appearance = NSAppearance(named: .darkAqua)

        let root = CaptureView(store: store, onOpenOrganize: { [weak self] in
            self?.close()
            onOpenOrganize()
        })
        let hosting = NSHostingController(rootView: root)
        if #available(macOS 13.0, *) {
            hosting.sizingOptions = [.preferredContentSize]
        }
        popover.contentViewController = hosting
    }

    func toggle(relativeTo button: NSStatusBarButton?) {
        if popover.isShown {
            close()
        } else {
            show(relativeTo: button)
        }
    }

    func show(relativeTo button: NSStatusBarButton?) {
        guard let button = button else { return }
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        NSApp.activate(ignoringOtherApps: true)
        popover.contentViewController?.view.window?.makeKey()
    }

    func close() {
        popover.performClose(nil)
    }
}
