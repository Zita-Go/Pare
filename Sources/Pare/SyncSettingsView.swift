import SwiftUI
import PareCore

struct SyncSettingsView: View {
    @ObservedObject var coordinator: SyncCoordinator
    @ObservedObject var store: Store
    @State private var email = ""
    @State private var password = ""
    @State private var createAccount = false
    @State private var showingSignOut = false
    @State private var expandedConflict: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "icloud").font(.system(size: 25)).foregroundColor(Theme.amber)
                VStack(alignment: .leading, spacing: 5) {
                    Text("在 Mac 与 Android 之间接着做")
                        .font(Theme.font(16, weight: .medium)).foregroundColor(Theme.text)
                    Text("离线也能记录；联网后同步同一账号的任务、想法和计划。")
                        .font(Theme.font(12)).foregroundColor(Theme.textDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            statusCard
            if coordinator.isConfigured {
                if coordinator.isSignedIn {
                    if store.syncState?.enabled == false {
                        Text("已暂停同步。备份恢复后需要重新查看合并预览，确认后才会上传。")
                            .font(Theme.font(12)).foregroundColor(Theme.amber)
                        Button("重新查看合并预览") { coordinator.resumeAfterRestore() }
                            .buttonStyle(SecondaryActionStyle()).disabled(coordinator.isBusy)
                    }
                    if let preview = store.syncMergePreview { mergePreview(preview) }
                    conflicts
                    HStack {
                        Button("立即同步") { coordinator.syncNow() }
                            .buttonStyle(SecondaryActionStyle())
                            .disabled(store.syncState?.enabled != true)
                        Spacer()
                        Button("退出同步账号") { showingSignOut = true }
                            .buttonStyle(TertiaryActionStyle())
                    }.disabled(coordinator.isBusy)
                } else { loginForm }
            }
            DisclosureGroup("哪些内容会同步？") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("任务与备注、想法与探索记录、长期目标、每日计划、已结束的进展记录，以及你填写的规划背景与偏好。")
                        .fixedSize(horizontal: false, vertical: true)
                    Text("正在运行的计时、系统通知设置、AI 服务密钥和 AI 原始请求历史留在本机。")
                        .fixedSize(horizontal: false, vertical: true)
                    Text("采用加密连接和账号隔离；云服务管理员仍可能访问服务端数据。同步开启后，修改会自动上传。")
                        .fixedSize(horizontal: false, vertical: true)
                }.font(Theme.font(12)).foregroundColor(Theme.textDim)
                    .fixedSize(horizontal: false, vertical: true).padding(.top, 6)
            }.font(Theme.font(12)).foregroundColor(Theme.textDim)
        }
        .alert("退出同步账号？", isPresented: $showingSignOut) {
            Button("取消", role: .cancel) {}
            Button("退出") { coordinator.signOut() }
        } message: {
            Text("本机内容和待同步修改会保留，云端数据也不会删除。再次登录同一账号后可以继续。")
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                if coordinator.isBusy { ProgressView().controlSize(.small) }
                else {
                    Image(systemName: coordinator.errorMessage == nil ? "checkmark.shield" : "exclamationmark.circle")
                        .foregroundColor(coordinator.errorMessage == nil ? Theme.success : Theme.amber)
                }
                Text(coordinator.status).font(Theme.font(13, weight: .medium))
            }
            if let email = coordinator.email {
                Text(email).font(Theme.font(12)).foregroundColor(Theme.textDim)
            }
            if let date = coordinator.lastSyncedAt {
                Text("最近同步 \(date.formatted(date: .omitted, time: .shortened))")
                    .font(Theme.font(11)).foregroundColor(Theme.textFaint)
            }
            if store.pendingSyncChangeCount > 0 {
                Text("\(store.pendingSyncChangeCount) 项内容等待同步")
                    .font(Theme.font(12)).foregroundColor(Theme.amber)
            }
            if let error = coordinator.errorMessage {
                Text(error).font(Theme.font(12)).foregroundColor(Theme.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }.foregroundColor(Theme.text).padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: Theme.Radius.card))
    }

    private var loginForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(createAccount ? "创建 Pare 同步账号" : "登录 Pare 同步账号")
                .font(Theme.font(14, weight: .medium)).foregroundColor(Theme.text)
            Text("使用两台设备上的同一账号。Supabase 控制台账号与这里的同步账号相互独立。")
                .font(Theme.font(12)).foregroundColor(Theme.textDim)
            TextField("邮箱", text: $email)
            SecureField("密码", text: $password)
            HStack {
                Button(createAccount ? "注册并发送确认邮件" : "登录并查看合并预览") {
                    let value = password; password = ""
                    coordinator.authenticate(email: email, password: value, createAccount: createAccount)
                }.buttonStyle(AIPrimaryButtonStyle())
                    .disabled(coordinator.isBusy || email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty)
                Spacer()
                Button(createAccount ? "已有账号，登录" : "创建账号") { createAccount.toggle() }
                    .buttonStyle(TertiaryActionStyle()).disabled(coordinator.isBusy)
            }
            Text("首次登录只读取合并预览；确认前不会上传本机内容。")
                .font(Theme.font(11)).foregroundColor(Theme.textFaint)
        }.textFieldStyle(.roundedBorder)
    }

    private func mergePreview(_ value: SyncMergePreview) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("首次合并预览").font(Theme.font(14, weight: .medium))
            HStack(spacing: 20) {
                countLabel(value.localOnlyCount, "本机新增")
                countLabel(value.cloudOnlyCount, "云端新增")
                countLabel(value.conflictCount, "内容不同")
            }
            Text("确认后合并两边的内容；有差异的版本会保留，供你逐项选择。空的云端不会清空本机。")
                .font(Theme.font(12)).foregroundColor(Theme.textDim)
            Button("确认合并并开启同步") { coordinator.confirmMerge() }
                .buttonStyle(AIPrimaryButtonStyle()).disabled(coordinator.isBusy)
        }.padding(14).foregroundColor(Theme.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.amberSoft, in: RoundedRectangle(cornerRadius: Theme.Radius.card))
    }

    private func countLabel(_ number: Int, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("\(number)").font(Theme.font(20, weight: .medium)).foregroundColor(Theme.amber)
            Text(label).font(Theme.font(11)).foregroundColor(Theme.textDim)
        }
    }

    @ViewBuilder private var conflicts: some View {
        if let items = store.syncState?.conflicts, !items.isEmpty {
            Text("\(items.count) 项两端修改需要选择")
                .font(Theme.font(14, weight: .medium)).foregroundColor(Theme.amber)
            Text("先比较内容，再保留一个版本。相关的一组修改会一起处理。")
                .font(Theme.font(12)).foregroundColor(Theme.textDim)
            ForEach(items) { item in
                VStack(alignment: .leading, spacing: 10) {
                    Text(title(for: item)).font(Theme.font(13, weight: .medium)).foregroundColor(Theme.text)
                    DisclosureGroup("比较两个版本", isExpanded: Binding(
                        get: { expandedConflict == item.id },
                        set: { expandedConflict = $0 ? item.id : nil })) {
                        versionText("本机", payload: item.local)
                        versionText("云端", payload: item.remote?.deletedAt == nil ? item.remote?.payload : nil)
                    }.font(Theme.font(12)).foregroundColor(Theme.textDim)
                    HStack {
                        Button("保留本机版本") { coordinator.resolve(item.key, useCloud: false) }
                        Button("采用云端版本") { coordinator.resolve(item.key, useCloud: true) }
                    }.buttonStyle(SecondaryActionStyle()).disabled(coordinator.isBusy)
                    if let choice = item.choice {
                        Text(choice == .local ? "已选本机版本，等待同组其他内容的选择" : "已选云端版本，等待同组其他内容的选择")
                            .font(Theme.font(11)).foregroundColor(Theme.amber)
                    }
                }.padding(12).background(Theme.surfaceRaised, in: RoundedRectangle(cornerRadius: Theme.Radius.control))
            }
        }
    }

    private func title(for item: SyncLocalConflict) -> String {
        let payload = item.local ?? item.remote?.payload
        if case .object(let object) = payload, case .object(let content) = object["content"],
           case .string(let text) = content["text"] { return String(text.prefix(100)) }
        switch item.key.kind {
        case .dayPlan: return "每日计划 · \(item.key.id)"
        case .profile: return "规划背景与偏好"
        case .goal: return "长期目标"
        case .idea: return "想法"
        case .inbox: return "随手记"
        case .task: return "任务"
        }
    }

    private func versionText(_ label: String, payload: SyncJSON?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(Theme.font(12, weight: .medium))
            Text(summary(payload)).font(Theme.font(12)).textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            if payload != nil && payload != .null {
                DisclosureGroup("完整内容") {
                    Text(readable(payload)).font(.system(size: 11, design: .monospaced))
                        .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                }.font(Theme.font(11))
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
    }

    private func summary(_ payload: SyncJSON?) -> String {
        guard case .object(let wrapper) = payload, case .object(let content) = wrapper["content"] else { return "此版本已删除" }
        var lines: [String] = []
        for (field, label) in [("text", "内容"), ("title", "目标"), ("note", "备注"), ("notes", "备注"),
                               ("nextAction", "下一步"), ("definitionOfDone", "完成标准"), ("background", "规划背景")] {
            if case .string(let text) = content[field], !text.isEmpty { lines.append("\(label)：\(text)") }
        }
        if case .bool(let done) = content["completed"] { lines.append(done ? "状态：已完成" : "状态：未完成") }
        if case .integer(let value) = content["estimatedMinutes"] { lines.append("预估：\(value) 分钟") }
        if case .integer(let value) = content["quadrant"], let q = Quadrant(rawValue: Int(value)) { lines.append("象限：\(q.title)") }
        if case .array(let ids) = content["ordered_task_ids"] {
            let titles = ids.compactMap { value -> String? in
                guard case .string(let id) = value, let uuid = UUID(uuidString: id) else { return nil }
                return (store.tasks + store.archive).first { $0.id == uuid }?.text ?? "另一端的任务"
            }
            lines.append(titles.isEmpty ? "当日未安排任务" : "当日计划：\(titles.joined(separator: "、"))")
        }
        return lines.isEmpty ? "规划设置有所不同，可以展开完整内容比较。" : lines.joined(separator: "\n")
    }

    private func readable(_ payload: SyncJSON?) -> String {
        guard let payload, payload != .null else { return "此版本已删除" }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        guard let data = try? encoder.encode(payload), let value = String(data: data, encoding: .utf8) else { return "无法显示此版本" }
        return value
    }
}
