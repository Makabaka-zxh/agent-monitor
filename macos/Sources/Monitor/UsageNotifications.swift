import AppKit
import Foundation
import Combine
import UserNotifications

private struct UsageReceiptEnvelope: Codable {
    let account: String
    var receipts: UsageAlertReceipts
}

/// Delegate callbacks may arrive off the main thread, including during cold launch.
private final class UsageNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    var opened: (([AnyHashable: Any]) -> Void)?
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { completionHandler(); return }
        let info = response.notification.request.content.userInfo
        DispatchQueue.main.async { [weak self] in self?.opened?(info); completionHandler() }
    }
}

@MainActor
final class UsageNotifications: ObservableObject {
    @Published private(set) var enabled: Bool
    @Published private(set) var lowThreshold: Int
    @Published private(set) var resetMinutes: Int
    @Published private(set) var permission = "尚未检查"
    @Published private(set) var busy = false
    @Published private(set) var message = ""
    var onOpenUsage: ((String) -> Void)?

    private let defaults: UserDefaults
    private let center: UNUserNotificationCenter
    private let delegate = UsageNotificationDelegate()
    private var account: String?
    private var receipts = UsageAlertReceipts()
    private var epoch = UUID()
    private var checking = false
    private var suspended = false
    private var cleanupTask: Task<Void, Never>?
    private var registered = false
    private var pendingOpen: [AnyHashable: Any]?
    private static let receiptsKey = "usageAlerts.receipts.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        center = .current()
        enabled = defaults.bool(forKey: "usageAlerts.enabled")
        let low = defaults.object(forKey: "usageAlerts.low") as? Int ?? 10
        let reset = defaults.object(forKey: "usageAlerts.reset") as? Int ?? 15
        lowThreshold = [0, 5, 10, 20].contains(low) ? low : 10
        resetMinutes = [0, 5, 15, 30].contains(reset) ? reset : 15
    }
    func start() {
        guard !registered else { return }
        registered = true
        delegate.opened = { [weak self] info in self?.open(info) }
        center.delegate = delegate
        Task { await refreshPermission() }
    }
    func setAccount(_ newAccount: String?) {
        guard account != newAccount else { return }
        let old = account
        epoch = UUID()
        account = newAccount
        suspended = false
        receipts = UsageAlertReceipts()
        if let newAccount, let data = defaults.data(forKey: Self.receiptsKey),
           let saved = try? JSONDecoder().decode(UsageReceiptEnvelope.self, from: data), saved.account == newAccount {
            receipts = saved.receipts
            receipts.prune(now: Date())
        } else { defaults.removeObject(forKey: Self.receiptsKey) }
        if let old { removeNotifications(account: old) }
        if newAccount == nil {
            enabled = false
            defaults.set(false, forKey: "usageAlerts.enabled")
            pendingOpen = nil
        } else if let pendingOpen { open(pendingOpen) }
    }
    func refreshPermission() async {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional: permission = "已允许"
        case .denied: permission = "系统通知已关闭"
        case .notDetermined: permission = "尚未授权"
        default: permission = "系统通知不可用"
        }
    }
    private func authorize() async -> Bool {
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            do { _ = try await center.requestAuthorization(options: [.alert, .sound]) }
            catch { message = "暂时无法申请通知权限" }
        }
        await refreshPermission()
        return permission == "已允许"
    }
    func setEnabled(_ value: Bool) async {
        if !value {
            epoch = UUID(); enabled = false
            defaults.set(false, forKey: "usageAlerts.enabled")
            if let account { removeNotifications(account: account) }
            return
        }
        guard account != nil, !busy else { return }
        let generation = epoch
        busy = true; message = ""
        defer { busy = false }
        let allowed = await authorize()
        guard generation == epoch, account != nil else { return }
        enabled = allowed
        defaults.set(allowed, forKey: "usageAlerts.enabled")
        if !allowed { message = "请在系统设置 → 通知 → Monitor 中允许通知" }
    }
    func setLowThreshold(_ value: Int) {
        guard [0, 5, 10, 20].contains(value) else { return }
        epoch = UUID()
        lowThreshold = value; defaults.set(value, forKey: "usageAlerts.low")
    }
    func setResetMinutes(_ value: Int) {
        guard [0, 5, 15, 30].contains(value) else { return }
        epoch = UUID()
        resetMinutes = value; defaults.set(value, forKey: "usageAlerts.reset")
    }
    func process(_ summary: UsageSummary, account expected: String) async {
        guard enabled, account == expected, !checking, !busy, !suspended else { return }
        checking = true
        let generation = epoch
        defer { checking = false }
        await cleanupTask?.value
        let settings = await center.notificationSettings()
        guard generation == epoch, account == expected, enabled,
              [.authorized, .provisional].contains(settings.authorizationStatus) else { return }
        receipts.prune(now: Date())
        let candidates = UsageAlertPolicy.candidates(summary: summary, enabled: enabled,
                         lowThreshold: lowThreshold, resetMinutes: resetMinutes, now: Date())
        for pending in receipts.pendingBatch(candidates) {
            guard generation == epoch, account == expected, enabled else { return }
            let identifier = "monitor.usage.\(expected).\(pending.windowID)"
            let payload = UsageNotificationPayload(account: expected, tool: pending.tool)
            do {
                try await deliver(payload, identifier: identifier)
                guard generation == epoch, account == expected, enabled else {
                    center.removePendingNotificationRequests(withIdentifiers: [identifier])
                    center.removeDeliveredNotifications(withIdentifiers: [identifier])
                    return
                }
                receipts.recordDelivered(pending)
                persistReceipts()
            } catch { message = "有一条提醒未能发送，稍后重试" }
        }
    }
    func sendTest() async {
        guard let expected = account, !busy, !checking, !suspended else { return }
        busy = true; message = ""
        let generation = epoch
        defer { busy = false }
        await cleanupTask?.value
        let allowed = await authorize()
        guard generation == epoch, account == expected else { return }
        guard allowed else { message = "请在系统设置 → 通知 → Monitor 中允许通知"; return }
        let identifier = "monitor.usage.\(expected).test"
        do {
            try await deliver(.init(account: expected, tool: "", test: true), identifier: identifier)
            guard generation == epoch, account == expected else {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                center.removeDeliveredNotifications(withIdentifiers: [identifier]); return
            }
            message = "测试提醒已提交；横幅和声音由系统设置决定"
        } catch { message = "测试提醒未能发送，请稍后重试" }
    }
    private func deliver(_ payload: UsageNotificationPayload, identifier: String) async throws {
        let content = UNMutableNotificationContent()
        content.title = payload.title; content.body = payload.body
        content.userInfo = payload.userInfo; content.sound = .default
        content.threadIdentifier = "monitor.usage.\(payload.tool)"
        // Immediate delivery only: never schedule an assumed future balance/reset.
        try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }
    private func persistReceipts() {
        guard let account, let data = try? JSONEncoder().encode(UsageReceiptEnvelope(account: account, receipts: receipts)) else { return }
        defaults.set(data, forKey: Self.receiptsKey)
    }
    private func removeNotifications(account: String) {
        let prefix = "monitor.usage.\(account)."
        let previous = cleanupTask
        cleanupTask = Task { [center] in
            await previous?.value
            let requests = await center.pendingNotificationRequests()
            center.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier).filter { $0.hasPrefix(prefix) })
            let notifications = await center.deliveredNotifications()
            center.removeDeliveredNotifications(withIdentifiers: notifications.map { $0.request.identifier }.filter { $0.hasPrefix(prefix) })
        }
    }
    func suspendDelivery() { suspended = true; epoch = UUID() }
    func resumeDelivery() { suspended = false }
    private func open(_ info: [AnyHashable: Any]) {
        guard let account else { pendingOpen = info; return }
        pendingOpen = nil
        guard let tool = UsageNotificationPayload.route(info, currentAccount: account) else { return }
        onOpenUsage?(tool)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }
}
