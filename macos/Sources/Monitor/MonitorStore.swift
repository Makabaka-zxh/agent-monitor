import AppKit
import Foundation
import SwiftUI

@MainActor
final class MonitorStore: ObservableObject {
    @Published private(set) var origin: String
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
    let localCollector = LocalCollector()
    @Published var pairingCode: ComputerPairingCode?
    @Published private(set) var qrLogin: QRLoginSession?
    @Published private(set) var qrBusy = false
    @Published private(set) var qrError = ""
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
    private var pairingLoopTask: Task<Void, Never>?
    private var qrLoopTask: Task<Void, Never>?
    private var qrPollInFlight = false
    private var usageRetryAfter = Date.distantPast

    init() {
        origin = MonitorAPI.origin
        tool = ToolFilter(rawValue: UserDefaults.standard.string(forKey: "tool") ?? "") ?? .all
        scope = TaskScope(rawValue: UserDefaults.standard.string(forKey: "scope") ?? "") ?? .active
        loadCredentialsForCurrentOrigin()
        usageAlerts.setAccount(connected ? tokenFingerprint : nil)
        usageAlerts.onOpenUsage = { [weak self] _ in
            guard let self, self.connected else { return }
            self.usageNavigationRequest = UUID()
            self.openMainWindow?()
        }
    }

    private func loadCredentialsForCurrentOrigin() {
        credential = nil
        pairing = nil
        connected = false
        pendingLogin = false
        guard !origin.isEmpty else { return }
        do {
            if let saved = try Keychain.read(LoginSecret.self, key: "login"),
               saved.expiresAt > Date(),
               MonitorSecurity.matches(saved.token, "nrd_[A-Za-z0-9_-]{43}") {
                credential = saved
                connected = true
            }
            if !connected,
               let saved = try Keychain.read(PairingSecret.self, key: "pairing"),
               saved.expiresAt > Date(),
               MonitorSecurity.matches(saved.id, "[A-Za-z0-9_-]{32}"),
               MonitorSecurity.matches(saved.verifier, "[A-Za-z0-9_-]{43,128}") {
                pairing = saved
                pendingLogin = true
            }
        } catch {
            notice = error.localizedDescription
        }
    }

    var fresh: Bool { lastSuccess.map { Date().timeIntervalSince($0) < 20 } ?? false }
    var visibleTasks: [MonitorTask] { tasks.filter { $0.matches(tool: tool, scope: scope, fresh: fresh) } }
    var onlineCount: Int { fresh ? devices.filter(\.online).count : 0 }
    var tokenFingerprint: String { MonitorSecurity.hash(Data((credential?.token ?? "").utf8)) }
    var pendingVerificationURL: String? {
        guard let pairing, pairing.expiresAt > Date(), !origin.isEmpty else { return nil }
        return verificationURL(pairing.id).absoluteString
    }
    func task(_ id: String) -> MonitorTask? { tasks.first { $0.id == id } }
    func device(_ id: String) -> Computer? { devices.first { $0.id == id } }
    func current(_ token: String) -> Bool { connected && credential?.token == token }

    @discardableResult
    func saveServerOrigin(_ input: String) -> Bool {
        guard !connected, !loginBusy, !ServerOrigin.isEnvironmentControlled else { return false }
        if pendingLogin { clearPendingLogin() }
        clearQRLogin()
        guard let normalized = ServerOrigin.save(input) else {
            notice = "请输入有效的 HTTPS 服务器根地址（如 https://monitor.example.com，不带路径或查询参数）"
            return false
        }
        origin = normalized
        generation = UUID()
        cooldownUntil = .distantPast
        notice = nil
        loadCredentialsForCurrentOrigin()
        usageAlerts.setAccount(connected ? tokenFingerprint : nil)
        return true
    }

    func clearQRLogin() {
        qrLoopTask?.cancel()
        qrLoopTask = nil
        qrLogin = nil
        qrBusy = false
        qrPollInFlight = false
        qrError = ""
    }

    func startQRLogin(serverInput: String? = nil) async {
        guard !connected, !qrBusy else { return }
        if let serverInput, !ServerOrigin.isEnvironmentControlled {
            let trimmed = serverInput.trimmingCharacters(in: .whitespacesAndNewlines)
            if origin.isEmpty || (!trimmed.isEmpty && ServerOrigin.normalize(trimmed) != origin) {
                guard saveServerOrigin(trimmed) else { return }
                if connected {
                    await refresh()
                    await refreshAccount()
                    return
                }
            }
        }
        guard !origin.isEmpty else {
            qrError = "请先填写有效的 HTTPS 服务器地址"
            return
        }
        guard Date() >= cooldownUntil else {
            qrError = "请求较多，请稍后再试"
            return
        }
        clearQRLogin()
        qrBusy = true
        let epoch = generation
        defer { if epoch == generation { qrBusy = false } }
        await localCollector.refresh()
        guard epoch == generation, !connected else { return }
        let needsCollector = (localCollector.status?.supportsServerBinding == true) && (localCollector.status?.paired != true)
        do {
            let verifier = try MonitorSecurity.verifier()
            let hostName = String((Host.current().localizedName ?? "Mac").prefix(44))
            let readerName = String(("Monitor · " + hostName).prefix(60))
            let payload: [String: Any] = [
                "name": hostName,
                "platform": "macOS",
                "include_collector": needsCollector,
                "issue_collector_code": needsCollector,
                "include_reader": true,
                "reader_name": readerName,
                "code_challenge": MonitorSecurity.challenge(verifier)
            ]
            let value = try await api.json(QRPairingStartResponse.self, .qrPairingStart, method: "POST", body: payload)
            guard epoch == generation, !connected else { return }
            let expectedURL = origin + "/#/pairing-confirm/" + value.requestId
            guard MonitorSecurity.matches(value.requestId, "[A-Za-z0-9_-]{32}"),
                  MonitorSecurity.matches(value.pollSecret, "[A-Za-z0-9_-]{32,128}"),
                  value.verificationUrl == expectedURL,
                  let expiry = MonitorDate.parse(value.expiresAt),
                  expiry > Date() else { throw MonitorError.invalidResponse }
            let interval = max(3, min(10, value.interval ?? 3))
            qrLogin = QRLoginSession(
                id: value.requestId,
                pollSecret: value.pollSecret,
                verifier: verifier,
                verificationURL: value.verificationUrl,
                expiresAt: expiry,
                includeCollector: needsCollector,
                intervalSeconds: interval
            )
            qrError = ""
            startQRPollingLoop(intervalSeconds: interval)
        } catch {
            guard epoch == generation else { return }
            if let failure = error as? MonitorError, failure.status == 429 {
                cooldownUntil = Date().addingTimeInterval(failure.retryAfter)
            }
            qrError = error is MonitorError ? error.localizedDescription : "暂时无法生成登录二维码，请检查服务器地址或改用浏览器登录"
        }
    }

    private func startQRPollingLoop(intervalSeconds: Int) {
        qrLoopTask?.cancel()
        let epoch = generation
        qrLoopTask = Task { @MainActor [weak self] in
            while !Task.isCancelled, let self, self.generation == epoch, self.qrLogin != nil, !self.connected {
                try? await Task.sleep(for: .seconds(intervalSeconds))
                guard !Task.isCancelled, self.generation == epoch, self.qrLogin != nil, !self.connected else { return }
                await self.pollQRLogin()
            }
        }
    }

    func pollQRLogin() async {
        guard !qrPollInFlight, !loginBusy, let active = qrLogin, !connected, !origin.isEmpty, Date() >= cooldownUntil else { return }
        guard active.expiresAt > Date() else {
            clearQRLogin()
            qrError = "二维码已过期，请点击刷新重新生成"
            return
        }
        qrPollInFlight = true
        let epoch = generation
        defer { if epoch == generation { qrPollInFlight = false } }
        do {
            let value = try await api.json(
                QRPairingPollResponse.self,
                .qrPairingPoll(active.id),
                method: "POST",
                body: ["poll_secret": active.pollSecret, "code_verifier": active.verifier]
            )
            guard epoch == generation, qrLogin?.id == active.id, !connected else { return }
            if value.status == "pending" { return }
            guard value.status == "approved",
                  value.readerMode == "full_app",
                  let token = value.readerToken,
                  MonitorSecurity.matches(token, "nrd_[A-Za-z0-9_-]{43}"),
                  let stamp = value.readerExpiresAt,
                  let expiry = MonitorDate.parse(stamp),
                  expiry > Date() else { throw MonitorError.invalidResponse }
            let login = LoginSecret(token: token, expiresAt: expiry)
            do { try Keychain.write(login, key: "login") }
            catch {
                try? await api.mutate(.session, method: "DELETE", token: token)
                throw error
            }
            if pendingLogin { try? Keychain.remove("pairing") }
            pairingLoopTask?.cancel()
            pairingLoopTask = nil
            clearQRLogin()
            credential = login
            pairing = nil
            pendingLogin = false
            connected = true
            usageAlerts.setAccount(tokenFingerprint)
            connectionMessage = ""
            NSApplication.shared.activate(ignoringOtherApps: true)
            if let collectorCode = value.collectorCode, MonitorSecurity.matches(collectorCode, "[A-F0-9]{12}") {
                await localCollector.pair(code: collectorCode)
            }
            await refresh()
            await refreshAccount()
        } catch {
            guard epoch == generation else { return }
            if let failure = error as? MonitorError {
                if failure.status == 429 {
                    cooldownUntil = Date().addingTimeInterval(failure.retryAfter)
                    return
                }
                if [401, 403, 404, 410].contains(failure.status) {
                    clearQRLogin()
                    qrError = failure.localizedDescription
                    return
                }
            }
        }
    }

    func copyVerificationURL() {
        guard let link = pendingVerificationURL else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(link, forType: .string)
    }

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

    private func startFastPairingPoll() {
        clearQRLogin()
        pairingLoopTask?.cancel()
        let epoch = generation
        pairingLoopTask = Task { @MainActor [weak self] in
            while !Task.isCancelled, let self, self.generation == epoch, self.pendingLogin, !self.connected {
                try? await Task.sleep(for: .milliseconds(2500))
                guard !Task.isCancelled, self.generation == epoch, self.pendingLogin, !self.connected else { return }
                await self.pollLogin()
            }
        }
    }

    func beginLogin(serverInput: String? = nil) async {
        guard !connected, !loginBusy else { return }
        if let serverInput, !ServerOrigin.isEnvironmentControlled {
            let trimmed = serverInput.trimmingCharacters(in: .whitespacesAndNewlines)
            if origin.isEmpty || (!trimmed.isEmpty && ServerOrigin.normalize(trimmed) != origin) {
                guard saveServerOrigin(trimmed) else { return }
                if connected {
                    await refresh()
                    await refreshAccount()
                    return
                }
            }
        }
        guard !origin.isEmpty else {
            notice = "请先填写有效的 HTTPS 服务器地址"
            return
        }
        guard Date() >= cooldownUntil else {
            notice = "请求较多，请稍后再试"
            return
        }
        if let pairing, pairing.expiresAt > Date() {
            NSWorkspace.shared.open(verificationURL(pairing.id))
            startFastPairingPoll()
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
            startFastPairingPoll()
        } catch { if epoch == generation { report(error) } }
    }
    private func verificationURL(_ id: String) -> URL { URL(string: origin + "/#/native-connect/" + id)! }
    func pollLogin() async {
        guard !loginBusy, let pairing, !connected, !origin.isEmpty, Date() >= cooldownUntil else { return }
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
            clearQRLogin()
        pairingLoopTask?.cancel()
            pairingLoopTask = nil
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
        clearQRLogin()
        pairingLoopTask?.cancel()
        pairingLoopTask = nil
        if !origin.isEmpty {
            do { try Keychain.remove("pairing") } catch { report(error); return }
        }
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
        clearQRLogin()
        pairingLoopTask?.cancel()
        pairingLoopTask = nil
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

    @discardableResult
    func openDevPreview(_ urlString: String?, allowLoopback: Bool) -> Bool {
        guard let url = DevPreviewPolicy.validPreviewURL(urlString, allowLoopback: allowLoopback) else { return false }
        return NSWorkspace.shared.open(url)
    }

    func startNewTask(
        deviceId: String,
        projectId: String,
        tool: String,
        prompt: String,
        images: [DraftImageAttachment] = [],
        onProgress: ((String) -> Void)? = nil
    ) async -> (createdTaskId: String?, error: String?) {
        guard let login = credential, connected, !origin.isEmpty else {
            return (nil, "请先登录并连接同步服务")
        }
        guard MonitorSecurity.matches(deviceId, "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") else {
            return (nil, "请选择有效的在线电脑")
        }
        guard MonitorSecurity.matches(projectId, "[0-9a-f]{24}") else {
            return (nil, "请选择有效的项目目录")
        }
        guard ["codex", "claude", "antigravity"].contains(tool) else {
            return (nil, "请选择支持的工具")
        }
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (!trimmed.isEmpty || !images.isEmpty), trimmed.count <= 8000 else {
            return (nil, "请输入 1–8000 字的首条任务指令（或附带图片）")
        }
        let requestId = UUID().uuidString.lowercased()
        let syntheticTaskId = "\(deviceId):\(tool):new_\(projectId)"
        let epoch = generation
        var body: [String: Any] = [
            "device_id": deviceId,
            "project_id": projectId,
            "tool": tool,
            "request_id": requestId,
            "text": trimmed
        ]
        if !images.isEmpty {
            body["images"] = Array(images.prefix(QuickReplySuggestionPolicy.maxAttachments)).map(\.wireDictionary)
        }
        do {
            onProgress?("正在向电脑发送新建任务请求…")
            let initial = try await api.json(ReplyState.self, .startTask, method: "POST", body: body, token: login.token)
            if let immediate = initial.createdTaskId ?? initial.latest?.createdTaskId, !immediate.isEmpty {
                if epoch == generation { await refresh() }
                return (immediate, nil)
            }
            for _ in 0..<28 {
                if Task.isCancelled { return (nil, nil) }
                try await Task.sleep(for: .milliseconds(450))
                guard current(login.token), epoch == generation else { return (nil, "登录状态已变更") }
                let poll = try await api.json(ReplyState.self, .reply(syntheticTaskId, requestId), token: login.token)
                if let created = poll.createdTaskId ?? poll.latest?.createdTaskId, !created.isEmpty {
                    await refresh()
                    return (created, nil)
                }
                let st = poll.state ?? poll.latest?.state ?? ""
                let reason = poll.reason ?? poll.latest?.reason
                if st == "failed" || st == "blocked" {
                    return (nil, (reason?.isEmpty == false) ? reason : "电脑端未能启动新会话，请检查对应工具状态")
                }
                switch st {
                case "running":
                    onProgress?("电脑已启动 \(MonitorTask.brand(for: tool))，正在创建会话…")
                case "dispatching":
                    onProgress?("电脑已接收请求，正在准备项目目录…")
                default:
                    onProgress?("等待目标电脑接收并启动新会话…")
                }
            }
            if epoch == generation { await refresh() }
            return (nil, "电脑正在后台启动新会话，稍后将自动出现在工作台列表中")
        } catch is CancellationError {
            return (nil, nil)
        } catch {
            handle(error, token: login.token, alert: false)
            return (nil, error is MonitorError ? error.localizedDescription : "发送新建任务请求失败，请检查网络连接")
        }
    }
}
