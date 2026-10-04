import AppKit
import SwiftUI
import UniformTypeIdentifiers

private enum MonitorPage: String, CaseIterable, Identifiable {
    case tasks, devices, usage, account
    var id: String { rawValue }
    var title: String { switch self { case .tasks: "任务"; case .devices: "电脑"; case .usage: "用量"; case .account: "我的" } }
    var symbol: String { switch self { case .tasks: "square.stack.3d.up"; case .devices: "desktopcomputer"; case .usage: "chart.bar.xaxis"; case .account: "person.crop.circle" } }
}
private struct GlassCard<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        content.padding(22).frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.quaternary, lineWidth: 0.7) }
    }
}
private struct BrandIcon: View {
    let tool: String
    var size: CGFloat = 32
    var body: some View {
        Group {
            if let image = MonitorResources.image(tool == "claude" ? "Claude" : "Codex") { Image(nsImage: image).resizable().scaledToFit() }
            else { Image(systemName: "terminal").resizable().scaledToFit() }
        }.frame(width: size, height: size).accessibilityLabel(tool == "claude" ? "Claude Code" : "Codex")
    }
}
private struct StatusBadge: View {
    let task: MonitorTask
    let fresh: Bool
    var tint: Color {
        guard fresh && task.stale != true else { return .secondary }
        switch task.status { case "running": return .green; case "waiting", "error": return .orange; default: return .secondary }
    }
    var body: some View {
        HStack(spacing: 6) { Circle().fill(tint).frame(width: 6, height: 6); Text(fresh && task.stale != true ? task.statusTitle : "状态待更新") }
            .font(.custom("MiSans-Regular", size: 12)).foregroundStyle(tint)
    }
}

@MainActor
struct RootView: View {
    @EnvironmentObject private var store: MonitorStore
    @Environment(\.openWindow) private var openWindow
    @State private var page: MonitorPage? = .tasks
    @State private var taskSelection: String?
    @State private var search = ""
    @State private var showingHelp = false
    private var filteredTasks: [MonitorTask] {
        store.visibleTasks.filter { search.isEmpty || ($0.title + " " + ($0.project ?? "") + " " + ($0.deviceName ?? "")).localizedCaseInsensitiveContains(search) }
    }
    var body: some View {
        Group {
            if store.connected { workspace } else { LoginView() }
        }
        .alert("Monitor", isPresented: Binding(get: { store.notice != nil }, set: { if !$0 { store.notice = nil } })) { Button("知道了", role: .cancel) { store.notice = nil } } message: { Text(store.notice ?? "") }
        .onAppear {
            store.openMainWindow = { openWindow(id: "main") }
            if store.usageNavigationRequest != nil { page = .usage }
            AcceptanceProbe.shared.rootDisplayed((page ?? .tasks).rawValue)
        }
        .onChange(of: page) { _, value in AcceptanceProbe.shared.rootDisplayed((value ?? .tasks).rawValue) }
        .onReceive(NotificationCenter.default.publisher(for: AcceptanceProbe.navigation)) { event in
            guard AcceptanceProbe.shared.active, let target = event.object as? String else { return }
            if target == "tasks" { page = .tasks }
            if target == "usage" { page = .usage }
        }
        .onChange(of: store.usageNavigationRequest) { _, request in
            if request != nil { page = .usage }
        }
    }
    private var workspace: some View {
        NavigationSplitView {
            List(MonitorPage.allCases, selection: $page) { item in Label(item.title, systemImage: item.symbol).tag(item).padding(.vertical, 5) }
                .listStyle(.sidebar)
                .navigationTitle("Monitor")
                .navigationSplitViewColumnWidth(min: 155, ideal: 175, max: 220)
                .safeAreaInset(edge: .bottom) {
                    HStack(spacing: 8) { Circle().fill(store.fresh ? Color.green : Color.secondary).frame(width: 6, height: 6); Text(store.fresh ? "\(store.onlineCount) 台在线" : "连接待更新").foregroundStyle(.secondary); Spacer() }
                        .font(.custom("MiSans-Regular", size: 12)).padding(18)
                }
        } detail: {
            switch page ?? .tasks {
            case .tasks:
                HSplitView {
                    taskList.frame(minWidth: 310, idealWidth: 360, maxWidth: 440)
                    Group {
                        if let id = taskSelection, let task = store.task(id) {
                            TaskDetailView(task: task, store: store).id(store.tokenFingerprint + id)
                        } else {
                            ContentUnavailableView("选择一个任务", systemImage: "square.stack.3d.up", description: Text("查看最终结果，或继续回复"))
                        }
                    }.frame(minWidth: 390, maxWidth: .infinity, maxHeight: .infinity)
                }
            case .devices: ComputersView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .usage: UsageView(store: store).id(store.tokenFingerprint).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .account: AccountView(onUsage: { page = .usage }).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .toolbar {
            ToolbarItemGroup {
                if page != .usage {
                if store.refreshing { ProgressView().controlSize(.small) }
                Button { Task { await store.refresh() } } label: { Image(systemName: "arrow.clockwise") }.help("刷新").disabled(store.refreshing)
                Button { showingHelp = true } label: { Image(systemName: "info.circle") }.help("关于连接")
                }
            }
        }
        .sheet(isPresented: $showingHelp) {
            VStack(alignment: .leading, spacing: 20) {
                Text("保持连接").font(.custom("MiSans-Regular", size: 24)).fontWeight(.semibold)
                Text("电脑连接后会同步任务状态。最终结果与回复沿用账号已授予的权限。")
                Text("离线时保留上次记录，恢复连接后自动更新。").foregroundStyle(.secondary)
                Button("知道了") { showingHelp = false }.buttonStyle(.borderedProminent).controlSize(.large).frame(maxWidth: .infinity, alignment: .trailing)
            }.padding(30).frame(width: 400)
        }
    }
    private var taskList: some View {
        VStack(spacing: 0) {
            VStack(spacing: 14) {
                Picker("工具", selection: $store.tool) { ForEach(ToolFilter.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented)
                Picker("任务范围", selection: $store.scope) { ForEach(TaskScope.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented)
            }.padding(18)
            if !store.connectionMessage.isEmpty {
                Button { Task { await store.refresh() } } label: { Label(store.connectionMessage, systemImage: "wifi.exclamationmark").font(.custom("MiSans-Regular", size: 12)).frame(maxWidth: .infinity) }
                    .buttonStyle(.plain).padding(.bottom, 12).disabled(store.refreshing)
            }
            List(filteredTasks, selection: $taskSelection) { task in
                HStack(alignment: .top, spacing: 13) {
                    BrandIcon(tool: task.tool)
                    VStack(alignment: .leading, spacing: 7) {
                        Text(task.title).fontWeight(.medium).lineLimit(2)
                        Text(task.deviceName ?? "电脑").font(.custom("MiSans-Regular", size: 12)).foregroundStyle(.secondary).lineLimit(1)
                        StatusBadge(task: task, fresh: store.fresh)
                    }
                    Spacer(minLength: 0)
                }.padding(.vertical, 10).tag(task.id)
                    .contextMenu { Button(task.archived ? "取消归档" : "归档") { Task { await store.archive(task) } }.disabled(store.mutating) }
            }
            .listStyle(.inset)
            .overlay { if filteredTasks.isEmpty { ContentUnavailableView(search.isEmpty ? "这里很清爽" : "没有找到任务", systemImage: "tray", description: Text(search.isEmpty ? "切换任务范围，查看其他工作" : "试试其他关键词")) } }
        }
        .searchable(text: $search, placement: .toolbar, prompt: "搜索任务")
        .navigationTitle("工作台")
    }
}

@MainActor
private struct LoginView: View {
    @EnvironmentObject var store: MonitorStore
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.accentColor.opacity(0.06), Color.clear, Color.purple.opacity(0.05)], startPoint: .topLeading, endPoint: .bottomTrailing)
            VStack(spacing: 24) {
                if let image = MonitorResources.image("AppIcon") { Image(nsImage: image).resizable().scaledToFit().frame(width: 76, height: 76) }
                Text("工作，尽在眼前").font(.custom("MiSans-Regular", size: 32)).fontWeight(.semibold)
                Text(MonitorAPI.origin.isEmpty ? "先配置自建服务器地址，再重新打开 Monitor。" : store.pendingLogin ? "在浏览器确认后，自动回到这里" : "查看每台电脑上的 Claude Code 与 Codex").foregroundStyle(.secondary)
                Button { Task { await store.beginLogin() } } label: {
                    HStack { if store.loginBusy { ProgressView().controlSize(.small) }; Text(store.pendingLogin ? "继续登录" : "登录 Monitor") }.frame(width: 190)
                }.buttonStyle(.borderedProminent).controlSize(.large).disabled(store.loginBusy || MonitorAPI.origin.isEmpty)
                if store.pendingLogin { Button("取消") { store.cancelLogin() }.buttonStyle(.plain).foregroundStyle(.secondary).disabled(store.loginBusy) }
            }.padding(40)
        }
    }
}

@MainActor
private struct TaskDetailView: View {
    let task: MonitorTask
    @ObservedObject var store: MonitorStore
    @StateObject private var model: TaskDetailModel
    init(task: MonitorTask, store: MonitorStore) {
        self.task = task
        self.store = store
        _model = StateObject(wrappedValue: TaskDetailModel(taskId: task.id, store: store))
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(alignment: .top, spacing: 15) {
                    BrandIcon(tool: task.tool, size: 42)
                    VStack(alignment: .leading, spacing: 10) {
                        Text(task.title).font(.custom("MiSans-Regular", size: 25)).fontWeight(.semibold).textSelection(.enabled)
                        HStack { Text(task.deviceName ?? "电脑"); Text("·"); StatusBadge(task: task, fresh: store.fresh) }.foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if let preview = task.preview, !preview.isEmpty { Text(preview).foregroundStyle(.secondary).lineLimit(3) }
                GlassCard {
                    VStack(alignment: .leading, spacing: 18) {
                        HStack {
                            Text("最终结果").font(.custom("MiSans-Regular", size: 18)).fontWeight(.semibold)
                            Spacer()
                            if model.loading { ProgressView().controlSize(.small) }
                            Button { Task { await model.loadResult(force: true) } } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(.plain).help("更新最终结果").disabled(model.loading || !store.syncOutput)
                        }
                        if store.syncOutput, let result = model.result, result.available, let text = result.text {
                            if !model.resultError.isEmpty { Label(model.resultError, systemImage: "exclamationmark.triangle").foregroundStyle(.secondary) }
                            else if model.resultIsStale { Text("正在更新，显示上次结果").foregroundStyle(.secondary) }
                            Text(text).textSelection(.enabled).lineSpacing(5).frame(maxWidth: .infinity, alignment: .leading)
                            if result.truncated == true { Text("内容较长，完整结果请在电脑查看").foregroundStyle(.secondary) }
                            HStack {
                                Button("复制", systemImage: "doc.on.doc") { model.copyResult() }
                                Button("保存 TXT", systemImage: "arrow.down.document") { Task { await model.saveTXT() } }.disabled(model.downloading || !model.canSaveTXT)
                            }.controlSize(.large)
                            if let files = result.files, !files.isEmpty {
                                Divider()
                                ForEach(files) { file in
                                    Button { Task { await model.saveFile(file) } } label: {
                                        HStack { Image(systemName: "doc"); Text(file.name).lineLimit(2); Spacer(); Text(file.ready == false ? "同步中" : ByteCountFormatter.string(fromByteCount: Int64(file.size), countStyle: .file)).foregroundStyle(.secondary); Image(systemName: "arrow.down") }
                                    }.buttonStyle(.plain).padding(.vertical, 5).disabled(file.ready == false || model.downloading)
                                }
                            }
                        } else { Text(model.resultError.isEmpty ? model.result?.reason ?? (model.loading ? "正在读取…" : "还没有最终结果") : model.resultError).foregroundStyle(.secondary) }
                    }
                }
                GlassCard {
                    VStack(alignment: .leading, spacing: 15) {
                        Text("继续任务").font(.custom("MiSans-Regular", size: 18)).fontWeight(.semibold)
                        TextEditor(text: $model.draft).scrollContentBackground(.hidden).frame(minHeight: 90, maxHeight: 180).disabled(model.unresolved || model.sending)
                            .overlay(alignment: .topLeading) { if model.draft.isEmpty { Text("告诉它下一步做什么…").foregroundStyle(.tertiary).padding(.top, 1).padding(.leading, 5).allowsHitTesting(false) } }
                        if !model.replyMessage.isEmpty { Text(model.replyMessage).foregroundStyle(.secondary) }
                        HStack { Spacer(); if model.sending { ProgressView().controlSize(.small) }; Button(model.sendTitle) { Task { await model.send() } }.buttonStyle(.borderedProminent).controlSize(.large).disabled(!model.canSend) }
                    }
                }
            }.padding(28).frame(maxWidth: 860)
        }
        .navigationTitle(task.brand)
        .toolbar { ToolbarItem { Button(task.archived ? "取消归档" : "归档", systemImage: "archivebox") { Task { await store.archive(task) } }.disabled(store.mutating) } }
        .task { await model.run() }
    }
}

@MainActor
private struct ComputersView: View {
    @EnvironmentObject var store: MonitorStore
    @StateObject private var collector = LocalCollector()
    @State private var showingPairing = false
    @State private var showingCollectorSettings = false
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                GlassCard {
                    VStack(alignment: .leading, spacing: 16) {
                        Label("这台 Mac", systemImage: "macmini").font(.custom("MiSans-Regular", size: 20)).fontWeight(.medium)
                        if let status = collector.status {
                            Text(status.paired ? (status.service == "running" ? "正在同步 · \(status.selectedMode?.title ?? "模式未知")" : "已连接 · 同步已暂停") : "连接后，手机也能看到这台 Mac 的任务").foregroundStyle(.secondary)
                            if status.paired {
                                HStack {
                                    Button(status.service == "running" ? "暂停同步" : "开始同步") { Task { await collector.setRunning(status.service != "running") } }.disabled(collector.busy)
                                    Button("同步设置", systemImage: "slider.horizontal.3") { showingCollectorSettings = true }.disabled(collector.busy)
                                }
                            } else {
                                Button("连接这台 Mac") {
                                    Task {
                                        store.pairingCode = nil
                                        await store.createPairingCode()
                                        if let code = store.pairingCode, MonitorDate.parse(code.expiresAt).map({ $0 > Date() }) == true {
                                            await collector.pair(code: code.code)
                                            store.pairingCode = nil
                                            await store.refresh()
                                        }
                                    }
                                }.buttonStyle(.borderedProminent).disabled(collector.busy || store.mutating)
                            }
                        } else { Text("本机连接器尚未安装").foregroundStyle(.secondary) }
                        if !collector.message.isEmpty { Text(collector.message).foregroundStyle(.secondary) }
                    }
                }
                ForEach(store.devices) { device in
                    GlassCard {
                        HStack(spacing: 15) {
                            Image(systemName: device.symbol).font(.system(size: 28)).frame(width: 38)
                            VStack(alignment: .leading, spacing: 7) { Text(device.name).fontWeight(.medium); Text(store.fresh && device.online ? "在线" : "离线").foregroundStyle(store.fresh && device.online ? Color.green : .secondary) }
                            Spacer()
                        }
                    }
                }
                Button("添加其他电脑", systemImage: "plus") { showingPairing = true }.controlSize(.large)
            }.padding(28).frame(maxWidth: 780).frame(maxWidth: .infinity)
        }
        .navigationTitle("我的电脑")
        .task { await collector.refresh() }
        .sheet(isPresented: $showingPairing) { PairingCodeView() }
        .sheet(isPresented: $showingCollectorSettings) { CollectorSettingsView(collector: collector) }
    }
}

@MainActor
private struct PairingCodeView: View {
    @EnvironmentObject var store: MonitorStore
    @Environment(\.dismiss) var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("添加电脑").font(.custom("MiSans-Regular", size: 25)).fontWeight(.semibold)
            Text("在要连接的电脑上输入配对码").foregroundStyle(.secondary)
            if let code = store.pairingCode {
                Text(code.code).font(.system(size: 28, weight: .medium, design: .monospaced)).tracking(3).textSelection(.enabled).frame(maxWidth: .infinity).padding(.vertical, 12)
                Text("10 分钟内有效，仅可使用一次").foregroundStyle(.secondary)
            }
            HStack {
                Button(store.pairingCode == nil ? "生成配对码" : "重新生成") { Task { await store.createPairingCode() } }.disabled(store.mutating)
                Spacer()
                Button("完成") { dismiss() }.buttonStyle(.borderedProminent)
            }.controlSize(.large)
        }.padding(30).frame(width: 390)
    }
}

@MainActor
private struct AccountView: View {
    let onUsage: () -> Void
    @EnvironmentObject var store: MonitorStore
    @State private var editing = false
    @State private var confirmingLogout = false
    @State private var showingAlerts = false
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                GlassCard {
                    HStack(spacing: 18) {
                        AvatarView(dataURL: store.account?.user.avatar ?? "", size: 64)
                        VStack(alignment: .leading, spacing: 7) {
                            Text(store.account?.user.name ?? "我的账号").font(.custom("MiSans-Regular", size: 23)).fontWeight(.medium)
                            Text(store.account?.user.username ?? "").foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                    }
                }
                Button("编辑资料", systemImage: "pencil") { editing = true }.controlSize(.large).disabled(store.account == nil)
                Button("用量", systemImage: "chart.bar.xaxis", action: onUsage).controlSize(.large)
                Button("额度提醒", systemImage: "bell") { showingAlerts = true }.controlSize(.large)
                if let connections = store.account?.nativeDevices, !connections.isEmpty {
                    GlassCard {
                        VStack(alignment: .leading, spacing: 17) {
                            Text("已登录设备").font(.custom("MiSans-Regular", size: 18)).fontWeight(.medium)
                            ForEach(connections) { connection in
                                HStack { Text(connection.name); Spacer(); if connection.current == true { Text("当前").foregroundStyle(.secondary) } }
                            }
                        }
                    }
                }
                Button("退出登录", role: .destructive) { confirmingLogout = true }.disabled(store.mutating)
                GlassCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("开源与许可").font(.custom("MiSans-Regular", size: 18)).fontWeight(.medium)
                        Text("本应用使用 MiSans 字体，由小米提供，按 MiSans 字体知识产权许可协议授权。Monitor 源码采用 MIT；第三方组件和商标遵循各自条款。")
                            .font(.custom("MiSans-Regular", size: 14)).foregroundStyle(.secondary)
                        Link("查看开源与第三方许可", destination: URL(string: "https://github.com/Makabaka-zxh/agent-monitor/blob/main/THIRD_PARTY_NOTICES.md")!)
                    }
                }
            }.padding(28).frame(maxWidth: 780).frame(maxWidth: .infinity)
        }
        .navigationTitle("我的")
        .task { await store.refreshAccount() }
        .sheet(isPresented: $editing) { ProfileEditor(profile: store.account?.user) }
        .sheet(isPresented: $showingAlerts) { UsageAlertSettingsView(alerts: store.usageAlerts) }
        .confirmationDialog("退出这台 Mac 的登录？", isPresented: $confirmingLogout) { Button("退出登录", role: .destructive) { Task { await store.logout() } }; Button("取消", role: .cancel) {} }
    }
}

private struct AvatarView: View {
    let dataURL: String
    let size: CGFloat
    private var image: NSImage? {
        guard dataURL.hasPrefix("data:image/"), let comma = dataURL.firstIndex(of: ","), let data = Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...])), data.count <= 1024 * 1024 else { return nil }
        return NSImage(data: data)
    }
    var body: some View {
        Group { if let image { Image(nsImage: image).resizable().scaledToFill() } else { Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary.opacity(0.45)) } }
            .frame(width: size, height: size).clipShape(Circle())
    }
}

@MainActor
private struct ProfileEditor: View {
    @EnvironmentObject var store: MonitorStore
    @Environment(\.dismiss) var dismiss
    @State private var name: String
    @State private var avatar: String
    init(profile: Profile?) {
        _name = State(initialValue: profile?.name ?? "")
        _avatar = State(initialValue: profile?.avatar ?? "")
    }
    var body: some View {
        VStack(spacing: 22) {
            Text("个人资料").font(.custom("MiSans-Regular", size: 25)).fontWeight(.semibold).frame(maxWidth: .infinity, alignment: .leading)
            AvatarView(dataURL: avatar, size: 88)
            HStack { Button("更换头像") { chooseAvatar() }; if !avatar.isEmpty { Button("移除") { avatar = "" } } }
            TextField("名字", text: $name).textFieldStyle(.roundedBorder).controlSize(.large)
            HStack { Button("取消") { dismiss() }; Spacer(); Button("保存") { Task { if await store.saveProfile(name: name, avatar: avatar) { dismiss() } } }.buttonStyle(.borderedProminent).disabled(store.mutating || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }.controlSize(.large)
        }.padding(30).frame(width: 370)
    }
    private func chooseAvatar() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.png, .jpeg, .heic]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url,
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 20 * 1024 * 1024,
              let original = NSImage(contentsOf: url), original.size.width > 0, original.size.height > 0,
              let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 256, pixelsHigh: 256, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let context = NSGraphicsContext(bitmapImageRep: bitmap) else { store.notice = "无法读取这张图片"; return }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        let side = min(original.size.width, original.size.height)
        original.draw(in: NSRect(x: 0, y: 0, width: 256, height: 256), from: NSRect(x: (original.size.width - side) / 2, y: (original.size.height - side) / 2, width: side, height: side), operation: .copy, fraction: 1)
        NSGraphicsContext.restoreGraphicsState()
        guard let data = bitmap.representation(using: .png, properties: [:]) else { return }
        avatar = "data:image/png;base64," + data.base64EncodedString()
    }
}
