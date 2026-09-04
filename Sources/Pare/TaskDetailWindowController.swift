import AppKit
import SwiftUI
import PareCore

/// One pre-created, reusable task-detail panel. SwiftUI's per-row `.popover`
/// creates a new AppKit Scene/window on every click; keeping one borderless
/// panel alive removes that cost from the interaction path.
final class TaskDetailWindowController {
    private let panel: TaskDetailPanel
    private let selection: TaskDetailSelection

    init(store: Store) {
        let panel = TaskDetailPanel()
        let selection = TaskDetailSelection()
        selection.taskID = store.tasks.first?.id // prewarm the heavy editor tree when possible
        self.panel = panel
        self.selection = selection

        let root = TaskDetailHost(store: store, selection: selection) { [weak panel] in
            panel?.orderOut(nil)
        }
        let hosting = NSHostingController(rootView: root)
        panel.contentViewController = hosting
        panel.setContentSize(NSSize(width: 420, height: 700))
        hosting.view.layoutSubtreeIfNeeded()
    }

    func present(taskID: UUID) {
        selection.taskID = taskID
        positionNearPointer()
        panel.makeKeyAndOrderFront(nil)
    }

    func close() { panel.orderOut(nil) }

    private func positionNearPointer() {
        let pointer = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(pointer, $0.frame, false) } ?? NSScreen.main
        let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let size = panel.frame.size
        var x = pointer.x + 16
        if x + size.width > visible.maxX { x = pointer.x - size.width - 16 }
        x = min(max(x, visible.minX), visible.maxX - size.width)
        let y = min(max(pointer.y - size.height / 2, visible.minY), visible.maxY - size.height)
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }
}

private final class TaskDetailSelection: ObservableObject {
    @Published var taskID: UUID?
}

private struct TaskDetailHost: View {
    @ObservedObject var store: Store
    @ObservedObject var selection: TaskDetailSelection
    let onDismiss: () -> Void

    var body: some View {
        Group {
            if let id = selection.taskID,
               let task = store.tasks.first(where: { $0.id == id }) {
                ScrollView {
                    TaskDetailEditorView(store: store, task: task, onDismiss: onDismiss)
                        .id(task.id)
                }
            } else {
                Text("任务已不存在")
                    .font(Theme.font(13))
                    .foregroundColor(Theme.textDim)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(width: 420, height: 700)
        .background(CaptureBackground())
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Theme.border.opacity(0.8), lineWidth: 1)
        )
    }
}

private final class TaskDetailPanel: NSPanel {
    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 420, height: 700),
                   styleMask: [.borderless, .nonactivatingPanel],
                   backing: .buffered, defer: false)
        isFloatingPanel = true
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        hidesOnDeactivate = false
        animationBehavior = .none
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 { orderOut(nil); return }
        super.keyDown(with: event)
    }
}
