import SwiftUI

@MainActor
struct CollectorSettingsView: View {
    @EnvironmentObject private var store: MonitorStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var collector: LocalCollector
    @State private var selected: CollectorMode = .statusOnly
    @State private var showingInfo = false
    @State private var confirmingAccountSync = false

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack {
                Text("这台 Mac 的同步").font(.custom("MiSans-Regular", size: 25)).fontWeight(.semibold)
                Button { showingInfo.toggle() } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain).foregroundStyle(.secondary).help("同步范围")
                    .popover(isPresented: $showingInfo) {
                        Text("本机设置决定这台 Mac 可以同步什么。结果与文件还需要开启账号结果同步。回复仅续接原会话，不会自动批准额外权限。")
                            .font(.custom("MiSans-Regular", size: 14)).lineSpacing(5).padding(22).frame(width: 320)
                    }
            }
            if collector.status?.supportsModeSelection == true {
                Picker("同步内容", selection: $selected) {
                    ForEach(CollectorMode.allCases) { mode in Text(mode.title).tag(mode) }
                }.pickerStyle(.radioGroup).labelsHidden().controlSize(.large).disabled(collector.busy)
                Text(selected.explanation).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if selected != .statusOnly {
                    Divider()
                    HStack {
                        Label("账号结果同步", systemImage: "icloud")
                        Spacer()
                        if store.syncOutput {
                            Text("已开启").foregroundStyle(.secondary)
                        } else {
                            Button("开启") { confirmingAccountSync = true }.disabled(store.mutating || collector.busy)
                        }
                    }
                }
                if collector.status?.service == "running", selected != collector.status?.selectedMode {
                    Text("切换会短暂重启同步；进行中的远程回复会停止。").foregroundStyle(.secondary)
                }
            } else {
                Text("更新本机连接器后，即可设置结果同步和回复。").foregroundStyle(.secondary)
            }
            if !collector.message.isEmpty { Text(collector.message).foregroundStyle(.secondary) }
            HStack {
                Button("取消") { dismiss() }.disabled(collector.busy)
                Spacer()
                if collector.busy { ProgressView().controlSize(.small) }
                Button("保存") {
                    let requestedMode = selected
                    Task { if await collector.setMode(requestedMode) { dismiss() } }
                }.buttonStyle(.borderedProminent)
                    .disabled(collector.busy || store.mutating || collector.status?.supportsModeSelection != true || selected == collector.status?.selectedMode)
            }.controlSize(.large)
        }
        .font(.custom("MiSans-Regular", size: 14)).padding(30).frame(width: 440)
        .onAppear { selected = collector.status?.selectedMode ?? .statusOnly }
        .interactiveDismissDisabled(collector.busy)
        .confirmationDialog("开启账号结果同步？", isPresented: $confirmingAccountSync) {
            Button("开启结果同步") { Task { await store.setResultSync(true) } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("已授权且允许结果同步的电脑，会将最终结果和引用的配套文件同步到你的 Monitor。此设置对账号生效，不只影响这台 Mac。")
        }
    }
}
