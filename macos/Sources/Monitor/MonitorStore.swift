import AppKit
import Foundation
import SwiftUI

@MainActor
final class MonitorStore: ObservableObject {
    @Published private(set) var connected = false
    @Published private(set) var loginBusy = false
    @Published private(set) var pendingLogin = false
    @Published private(set) var refreshing = false
    @Published private(set) var mutating = false
    @Published private(set) var devices: [Computer] = []
    @Published private(set) var tasks: [MonitorTask] = []
    @Published private(set) var account: Account?
    @Published private(set) var syncOutput = false
    @Published private(set) var lastSuccess: Date?
    @Published private(set) var connectionMessage = ""
    @Published private(set) var usage: UsageSummary?
    @Published private(set) var usageRefreshing = false
    @Published private(set) var usageError = ""
    @Published var notice: String?
    @Published private(set) var usageNavigationRequest: UUID?
    var openMainWindow: (() -> Void)?
    let usageAlerts = UsageNotifications()
    @Published var pairingCode: ComputerPairingCode?
    @Published var tool: ToolFilter { didSet { UserDefaults.standard.set(tool.rawValue, forKey: "tool") } }
    @Published var scope: TaskScope { didSet { UserDefaults.standard.set(scope.rawValue, forKey: "scope") } }

    let api = MonitorAPI()
    private(set) var credential: LoginSecret?
    private var pairing: PairingSecret?
    private var generation = UUID()
    private var cooldownUntil = Date.distantPast
    private var accountBusy = false
    private var preferenceGeneration = 0
    private var loopStarted = false
    private var usageRetryAfter = Date.distantPast

    init() {
        tool = ToolFilter(rawValue: UserDefaults.standard.string(forKey: "tool") ?? "") ?? .all
        scope = TaskScope(rawValue: UserDefaults.standard.string(forKey: "scope") ?? "") ?? .active
        do {
            if let saved = try Keychain.read(LoginSecret.self, key: "login"), saved.expiresAt > Date(), MonitorSecurity.matches(saved.token, "nrd_[A-Za-z0-9_-]{43}") {
                credential = saved
                connected = true
            }
            if !connected, let saved = try Keychain.read(PairingSecret.self, key: "pairing"), saved.expiresAt > Date(),
               MonitorSecurity.matches(saved.id, "[A-Za-z0-9_-]{32}"), MonitorSecurity.matches(saved.verifier, "[A-Za-z0-9_-]{43,128}") {
                pairing = saved
                pendingLogin = true
            }
        } catch { notice = error.localizedDescription }
        usageAlerts.setAccount(connected ? tokenFingerprint : nil)
        usageAlerts.onOpenUsage = { [weak self] _ in
            guard let self, self.connected else { return }
            self.usageNavigationRequest = UUID()
            self.openMainWindow?()
        }
    }
    var fresh: Bool { lastSuccess.map { Date().timeIntervalSince($0) < 20 } ?? false }
    var visibleTasks: [MonitorTask] { tasks.filter { $0.matches(tool: tool, scope: scope, fresh: fresh) } }
    var onlineCount: Int { fresh ? devices.filter(\.online).count : 0 }
    var tokenFingerprint: String { MonitorSecurity.hash(Data((credential?.token ?? "").utf8)) }
    func task(_ id: String) -> MonitorTask? { tasks.first { $0.id == id } }
    func current(_ token: String) -> Bool { connected && credential?.token == token }

    func run() async {
        guard !loopStarted else { return }
        loopStarted = true
        defer { loopStarted = false }
        while !Task.isCancelled {
            if connected {
                await refresh()
                if account == nil { await refreshAccount() }
            } else if pendingLogin { await pollLogin() }
            do { try await Task.sleep(for: .seconds(pendingLogin ? 3 : NSApplication.shared.isActive ? 5 : 15)) }
            catch { return }
        }
    }
    func beginLogin() async {
        guard !connected, !loginBusy, Date() >= cooldownUntil else { return }
        if let pairing, pairing.expiresAt > Date() {
            NSWorkspace.shared.open(verificationURL(pairing.id))
            await pollLogin()
            return
        }
        loginBusy = true
        let epoch = generation
        defer { if epoch == generation { loginBusy = false } }
        do {
            let verifier = try MonitorSecurity.verifier()
            let name = String(("Monitor · " + (Host.current().localizedName ?? "Mac")).prefix(60))
            let value = try await api.json(PairingStart.self, .pairingStart, method: "POST", body: ["device_name": name, "mode": "full_app", "code_challenge": MonitorSecurity.challenge(verifier)])
            guard epoch == generation else { return }
            guard MonitorSecurity.matches(value.requestId, "[A-Za-z0-9_-]{32}"), value.verificationUrl == verificationURL(value.requestId).absoluteString,
                  let expiry = MonitorDate.parse(value.expiresAt), expiry > Date() else { throw MonitorError.invalidResponse }
            let secret = PairingSecret(id: value.requestId, verifier: verifier, expiresAt: expiry)
            try Keychain.write(secret, key: "pairing")
            pairing = secret
            pendingLogin = true
            NSWorkspace.shared.open(verificationURL(secret.id))
        } catch { if epoch == generation { report(error) } }
    }
    private func verificationURL(_ id: String) -> URL { URL(string: MonitorAPI.origin + "/#/native-connect/" + id)! }
    func pollLogin() async {
        guard !loginBusy, let pairing, !connected, Date() >= cooldownUntil else { return }
        guard pairing.expiresAt > Date() else { cancelLogin(); notice = "连接请求已过期，请重新登录"; return }
        loginBusy = true
        let epoch = generation
        defer { if generation == epoch { loginBusy = false } }
        do {
            let value = try await api.json(PairingPoll.self, .pairingPoll(pairing.id), method: "POST", body: ["code_verifier": pairing.verifier])
            guard epoch == generation else { return }
            if value.status == "pending" { return }
            guard value.status == "approved", value.mode == "full_app", let token = value.readerToken,
                  MonitorSecurity.matches(token, "nrd_[A-Za-z0-9_-]{43}"), let stamp = value.expiresAt, let expiry = MonitorDate.parse(stamp), expiry > Date() else { throw MonitorError.invalidResponse }
            let login = LoginSecret(token: token, expiresAt: expiry)
            // Persist the claimed bearer before any account/workbench fetch; no web cookie exchange.
            do { try Keychain.write(login, key: "login") }
            catch {
                try? await api.mutate(.session, method: "DELETE", token: token)
                throw error
            }
            try? Keychain.remove("pairing")
            credential = login
            self.pairing = nil
            pendingLogin = false
            connected = true
            usageAlerts.setAccount(tokenFingerprint)
            connectionMessage = ""
            NSApplication.shared.activate(ignoringOtherApps: true)
            await refresh()
            await refreshAccount()
        } catch {
            guard epoch == generation else { return }
            if let failure = error as? MonitorError, [401, 403, 404, 410].contains(failure.status) { clearPendingLogin() }
            report(error)
        }
    }
    func handleURL(_ url: URL) async {
        guard url.scheme == "agentmonitor", url.host == "paired", url.user == nil, url.password == nil,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.queryItems?.count == 1, let item = components.queryItems?.first,
              item.name == "request_id", item.value == pairing?.id else { return }
        NSApplication.shared.activate(ignoringOtherApps: true)
        await pollLogin()
    }
    func cancelLogin() {
        // Only an unclaimed pending authorization is discarded; a live login is revoked explicitly.
        guard !connected, !loginBusy else { return }
        clearPendingLogin()
    }
    private func clearPendingLogin() {
        do { try Keychain.remove("pairing") } catch { report(error); return }
        generation = UUID()
        pairing = nil
        pendingLogin = false
        loginBusy = false
    }
    func refresh() async {
        guard let login = credential, !refreshing, connected, Date() >= cooldownUntil else { return }
        guard login.expiresAt > Date() else { invalidate(login.token); return }
        refreshing = true
        let epoch = generation
        let preferenceEpoch = preferenceGeneration
        defer { if epoch == generation { refreshing = false } }
        do {
            let result = try await api.json(Workbench.self, .workbench, token: login.token)
            guard current(login.token), generation == epoch else { return }
            devices = result.devices
            tasks = result.tasks
            if preferenceEpoch == preferenceGeneration { syncOutput = result.preferences.syncOutput }
            lastSuccess = Date()
            connectionMessage = ""
            if let quotaSnapshot = result.usage {
                await usageAlerts.process(quotaSnapshot, account: tokenFingerprint)
            }
        } catch {
            guard generation == epoch else { return }
            connectionMessage = "暂时无法连接"
            handle(error, token: login.token, alert: false)
        }
    }
    /// Full history is fetched only while its native page is visible.
    func refreshUsage() async {
        guard !Task.isCancelled, let login = credential, connected, !usageRefreshing,
              Date() >= cooldownUntil, Date() >= usageRetryAfter else { return }
        guard login.expiresAt > Date() else { invalidate(login.token); return }
        usageRefreshing = true
        let epoch = generation
        defer { if epoch == generation { usageRefreshing = false } }
        do {
            let result = try await api.json(UsageSummary.self, .usage, token: login.token)
            guard !Task.isCancelled, current(login.token), generation == epoch else { return }
            usage = result.merging(previous: usage)
            usageError = ""
            usageRetryAfter = Date.distantPast
            await usageAlerts.process(result, account: tokenFingerprint)
        } catch {
            guard !Task.isCancelled, current(login.token), generation == epoch else { return }
            usageError = usage == nil ? "暂时无法读取用量" : "暂未更新，显示上次记录"
            usageRetryAfter = Date().addingTimeInterval(30)
            handle(error, token: login.token, alert: false)
        }
    }
    func refreshAccount() async {
        guard let token = credential?.token, !accountBusy, Date() >= cooldownUntil else { return }
        accountBusy = true
        let epoch = generation
        let preferenceEpoch = preferenceGeneration
        defer { if epoch == generation { accountBusy = false } }
        do {
            let result = try await api.json(Account.self, .account, token: token)
            guard current(token), epoch == generation else { return }
            account = result
            if preferenceEpoch == preferenceGeneration { syncOutput = result.preferences.syncOutput }
        } catch { handle(error, token: token) }
    }
    func archive(_ task: MonitorTask) async {
        await mutation(.archive, method: "PATCH", body: ["task_id": task.id, "archived": !task.archived])
    }
    @discardableResult
    func setResultSync(_ enabled: Bool) async -> Bool {
        guard let token = credential?.token, !mutating, connected, Date() >= cooldownUntil else { return false }
        mutating = true
        let epoch = generation
        preferenceGeneration += 1
        defer { if epoch == generation { mutating = false; preferenceGeneration += 1 } }
        do {
            let confirmed = try await api.json(Preferences.self, .preferences, method: "PATCH",
                                               body: ["sync_output": enabled], token: token)
            guard current(token), epoch == generation else { return false }
            // Apply the confirmed write immediately; earlier reads cannot restore old consent.
            syncOutput = confirmed.syncOutput
            return confirmed.syncOutput == enabled
        } catch { handle(error, token: token); return false }
    }
    func saveProfile(name: String, avatar: String) async -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 40 else { notice = "名字请输入 1–40 个字"; return false }
        return await mutation(.profile, method: "PATCH", body: ["display_name": trimmed, "avatar": avatar], accountUpdate: true)
    }
    @discardableResult
    private func mutation(_ endpoint: Endpoint, method: String, body: [String: Any]? = nil, accountUpdate: Bool = false) async -> Bool {
        guard let token = credential?.token, !mutating else { return false }
        mutating = true
        let epoch = generation
        defer { if epoch == generation { mutating = false } }
        do {
            try await api.mutate(endpoint, method: method, body: body, token: token)
            guard current(token), epoch == generation else { return false }
            if accountUpdate { await refreshAccount() } else { await refresh() }
            return true
        } catch { handle(error, token: token); return false }
    }
    func createPairingCode() async {
        guard let token = credential?.token, !mutating else { return }
        mutating = true
        let epoch = generation
        defer { if epoch == generation { mutating = false } }
        do {
            let result = try await api.json(ComputerPairingCode.self, .pairingCode, method: "POST", token: token)
            guard current(token), epoch == generation else { return }
            guard MonitorSecurity.matches(result.code, "[A-F0-9]{12}"), let expiry = MonitorDate.parse(result.expiresAt), expiry > Date() else { throw MonitorError.invalidResponse }
            pairingCode = result
        } catch { handle(error, token: token) }
    }
    func logout() async {
        guard let token = credential?.token, !mutating else { return }
        mutating = true
        usageAlerts.suspendDelivery()
        do {
            try await api.mutate(.session, method: "DELETE", token: token)
            invalidate(token)
        } catch {
            if let failure = error as? MonitorError, failure.status == 401 { invalidate(token) }
            else { handle(error, token: token); mutating = false; usageAlerts.resumeDelivery() }
        }
    }
    func invalidate(_ token: String) {
        guard credential?.token == token else { return }
        do { try Keychain.remove("login") } catch { notice = error.localizedDescription }
        generation = UUID()
        credential = nil
        connected = false
        refreshing = false
        mutating = false
        accountBusy = false
        account = nil
        tasks = []
        devices = []
        lastSuccess = nil
        pairingCode = nil
        usage = nil
        usageRefreshing = false
        usageError = ""
        usageRetryAfter = Date.distantPast
        usageNavigationRequest = nil
        usageAlerts.setAccount(nil)
    }
    func handle(_ error: Error, token: String, alert: Bool = true) {
        guard current(token) else { return }
        if let failure = error as? MonitorError {
            if [401, 403].contains(failure.status) { invalidate(token); notice = "连接已失效，请重新登录"; return }
            if failure.status == 429 { cooldownUntil = Date().addingTimeInterval(failure.retryAfter); connectionMessage = "请求较多，稍后自动重试" }
        }
        if alert { report(error) }
    }
    private func report(_ error: Error) {
        if let failure = error as? MonitorError, failure.status == 429 { cooldownUntil = Date().addingTimeInterval(failure.retryAfter) }
        notice = error is MonitorError ? error.localizedDescription : "暂时无法连接，请稍后重试"
    }
}
