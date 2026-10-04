import SwiftUI

@MainActor
struct UsageAlertSettingsView: View {
    @ObservedObject var alerts: UsageNotifications
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingInfo = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack(spacing: 10) {
                Text("额度提醒").font(.custom("MiSans-Regular", size: 24)).fontWeight(.semibold)
                Button { showingInfo = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain).foregroundStyle(.secondary).help("提醒说明")
                Spacer()
            }
            Toggle("开启提醒", isOn: Binding(get: { alerts.enabled }, set: { value in
                Task { await alerts.setEnabled(value) }
            })).toggleStyle(.switch).disabled(alerts.busy)
            VStack(alignment: .leading, spacing: 12) {
                Text("剩余额度").fontWeight(.medium)
                Picker("剩余额度", selection: Binding(get: { alerts.lowThreshold }, set: { alerts.setLowThreshold($0) })) {
                    Text("关闭").tag(0)
                    ForEach([5, 10, 20], id: \.self) { Text("≤ \($0)%").tag($0) }
                }.pickerStyle(.segmented).labelsHidden().disabled(alerts.busy)
            }
            VStack(alignment: .leading, spacing: 12) {
                Text("重置前").fontWeight(.medium)
                Picker("重置前", selection: Binding(get: { alerts.resetMinutes }, set: { alerts.setResetMinutes($0) })) {
                    Text("关闭").tag(0)
                    ForEach([5, 15, 30], id: \.self) { Text("\($0) 分钟").tag($0) }
                }.pickerStyle(.segmented).labelsHidden().disabled(alerts.busy)
            }
            HStack {
                Text(alerts.permission).foregroundStyle(.secondary)
                Spacer()
                Button("测试提醒") { Task { await alerts.sendTest() } }.disabled(alerts.busy)
            }
            if !alerts.message.isEmpty {
                Text(alerts.message).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            HStack { Spacer(); Button("完成") { dismiss() }.buttonStyle(.borderedProminent).controlSize(.large) }
        }
        .font(.custom("MiSans-Regular", size: 14)).padding(30).frame(width: 440)
        .task { await alerts.refreshPermission() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await alerts.refreshPermission() } }
        }
        .sheet(isPresented: $showingInfo) {
            VStack(alignment: .leading, spacing: 18) {
                Text("关于提醒").font(.custom("MiSans-Regular", size: 24)).fontWeight(.semibold)
                Text("仅依据新鲜的真实额度提醒，每个额度周期内，同一种提醒只发送一次。")
                Text("通知只显示 Claude Code 或 Codex，不包含额度、电脑名或任务内容。点击通知可进入用量页。")
                Text("Monitor 运行时持续检查，关闭窗口后仍有效；退出 App 或 Mac 睡眠期间不会检查。横幅、声音和锁屏展示由系统通知设置决定。")
                    .foregroundStyle(.secondary)
                Button("知道了") { showingInfo = false }.buttonStyle(.borderedProminent).controlSize(.large)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }.padding(30).frame(width: 410)
        }
    }
}
