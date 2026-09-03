import AppKit
import SwiftUI

/// A one-time, contextual welcome. It explains the invisible menu-bar workflow
/// before asking macOS for notification permission.
final class OnboardingWindowController: NSWindowController, NSWindowDelegate {
    private let onFinish: () -> Void

    init(manager: NotificationManager, onOpenOrganize: @escaping () -> Void,
         onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 520, height: 430),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered, defer: false)
        window.title = "欢迎使用 Pare"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isOpaque = false
        window.backgroundColor = .clear
        window.appearance = NSAppearance(named: .darkAqua)
        window.isReleasedWhenClosed = false
        window.center()

        super.init(window: window)
        window.delegate = self
        window.contentViewController = NSHostingController(rootView: OnboardingView(
            onEnableNotifications: { [weak manager, weak self] in
                manager?.requestAuthorizationAgain()
                self?.finish()
            },
            onStart: { [weak self] in
                self?.finish()
                onOpenOrganize()
            },
            onLater: { [weak self] in self?.finish() }))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func present() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    private func finish() {
        UserDefaults.standard.set(true, forKey: "pareDidCompleteOnboarding")
        close()
        onFinish()
    }

    func windowWillClose(_ notification: Notification) {
        UserDefaults.standard.set(true, forKey: "pareDidCompleteOnboarding")
        if !AppWindows.anyTitledVisible(excluding: window) {
            NSApp.setActivationPolicy(.accessory)
        }
    }
}

private struct OnboardingView: View {
    let onEnableNotifications: () -> Void
    let onStart: () -> Void
    let onLater: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            VStack(alignment: .leading, spacing: 6) {
                Text("把脑子里的事，先放下来")
                    .font(Theme.font(25, weight: .semibold)).foregroundColor(Theme.text)
                Text("Pare 常驻菜单栏，整理注意力，不制造新的负担。")
                    .font(Theme.font(13)).foregroundColor(Theme.textDim)
            }

            VStack(spacing: 12) {
                step("bolt.fill", "随时记", "点击菜单栏清单，或按 ⌥Space；输入一句，回车即可。")
                step("square.grid.2x2", "每天整理", "把任务放进四象限，再按真实容量排入今日计划。")
                step("bell.badge.fill", "克制提醒", "早晚节奏、单项提醒和每周回顾都可随时关闭。")
            }

            Text("通知只用于你主动设置的提醒，数据始终留在这台 Mac。")
                .font(Theme.font(11)).foregroundColor(Theme.textDim)

            HStack {
                Button("稍后") { onLater() }.buttonStyle(.plain).foregroundColor(Theme.textDim)
                Spacer()
                Button("直接开始整理") { onStart() }
                    .buttonStyle(.plain).foregroundColor(Theme.text)
                Button("开启提醒") { onEnableNotifications() }
                    .buttonStyle(.plain).foregroundColor(Theme.bg)
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 9).fill(Theme.amber))
            }
        }
        .padding(28)
        .frame(width: 520, height: 430)
        .background(FrostBackground(tint: 0.66))
    }

    private func step(_ icon: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: icon).font(.system(size: 15)).foregroundColor(Theme.amber)
                .frame(width: 28, height: 28)
                .background(Circle().fill(Theme.amberSoft))
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(Theme.font(14, weight: .semibold)).foregroundColor(Theme.text)
                Text(detail).font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            Spacer()
        }
        .padding(12).panel(corner: 11)
    }
}
