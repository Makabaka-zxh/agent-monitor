import AppKit
import Combine
import Foundation

@MainActor
final class TaskDetailModel: ObservableObject {
    @Published private(set) var result: TaskResult?
    @Published private(set) var loading = false
    @Published private(set) var downloading = false
    @Published private(set) var sending = false
    @Published private(set) var replyAvailable = false
    @Published private(set) var replyMessage = ""
    @Published private(set) var pending: PendingReply?
    @Published var draft = ""
    @Published private(set) var resultError = ""
    @Published private var cachedResultStale = false

    let taskId: String
    private let store: MonitorStore
    private let token: String
    private let ledgerKey: String
    private var refreshingReply = false
    private var resultMarker: String?
    private var initialResult = false
    private var resultRetryAt = Date.distantPast
    private var replyRetryAt = Date.distantPast
    private var active = true
    private var resultGeneration = UUID()
    private var retryResultSoon = false
    private var subscriptions = Set<AnyCancellable>()
    @Published private var rateLimitUntil = Date.distantPast

    init(taskId: String, store: MonitorStore) {
        self.taskId = taskId
        self.store = store
        token = store.credential?.token ?? ""
        ledgerKey = "reply:" + MonitorSecurity.hash(Data((store.tokenFingerprint + ":" + taskId).utf8))
        do {
            pending = try Keychain.read(PendingReply.self, key: ledgerKey)
            if let pending, pending.unresolved { draft = pending.text }
        } catch { store.notice = error.localizedDescription }
        store.$syncOutput.removeDuplicates().sink { [weak self] enabled in
            self?.resultPermissionChanged(enabled)
        }.store(in: &subscriptions)
    }
    var current: Bool { active && store.current(token) }
    var resultIsStale: Bool {
        result?.available == true && (cachedResultStale || resultMarker != (store.task(taskId)?.finalResultId ?? ""))
    }
    var canSaveTXT: Bool {
        guard current, store.syncOutput, let result, result.available, let id = result.resultId,
              let size = result.txtSize, let hash = result.txtSha256 else { return false }
        return size >= 0 && size <= 800000 && MonitorSecurity.matches(id, "[0-9a-f]{64}")
            && MonitorSecurity.matches(hash, "[0-9a-f]{64}")
    }
    var unresolved: Bool { pending?.unresolved == true }
    var canSend: Bool { current && Date() >= rateLimitUntil && !sending && !refreshingReply && (unresolved || (replyAvailable && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && draft.count <= 8000)) }
    var sendTitle: String { unresolved ? pending?.mayRetry == true ? "重试原回复" : "查询发送结果" : "发送回复" }

    func run() async {
        active = true
        defer { active = false; resultGeneration = UUID() }
        while !Task.isCancelled && current {
            if store.syncOutput {
                let marker = store.task(taskId)?.finalResultId ?? ""
                if TaskResultPolicy.needsRefresh(initial: initialResult, loadedMarker: resultMarker, currentMarker: marker,
                                                  available: result?.available, hasPendingFiles: result?.files?.contains(where: { $0.ready == false }) == true) {
                    await loadResult()
                }
            } else { result = nil; initialResult = false; resultError = "最终结果同步未开启" }
            guard !Task.isCancelled && current else { return }
            await refreshReply()
            do { try await Task.sleep(for: .seconds(retryResultSoon ? 1 : unresolved ? 3 : 8)) } catch { return }
        }
    }
    private func resultPermissionChanged(_ enabled: Bool) {
        // Even an off/on cycle invalidates a response that began under the old permission.
        resultGeneration = UUID()
        initialResult = false
        resultMarker = nil
        resultRetryAt = .distantPast
        retryResultSoon = false
        result = nil
        cachedResultStale = false
        resultError = enabled ? "" : "最终结果同步未开启"
    }
    func loadResult(force: Bool = false) async {
        guard !Task.isCancelled, current, store.syncOutput, !loading, Date() >= rateLimitUntil, force || Date() >= resultRetryAt else { return }
        loading = true
        defer { loading = false }
        let requestedMarker = store.task(taskId)?.finalResultId ?? ""
        let generation = resultGeneration
        retryResultSoon = false
        do {
            let value = try await store.api.json(TaskResult.self, .result(taskId), token: token)
            guard TaskResultPolicy.acceptsResponse(requestGeneration: generation, currentGeneration: resultGeneration,
                        requestedMarker: requestedMarker, currentMarker: store.task(taskId)?.finalResultId ?? "",
                        currentAccount: current, syncOutput: store.syncOutput, cancelled: Task.isCancelled) else {
                if generation == resultGeneration, current, store.syncOutput, !Task.isCancelled {
                    cachedResultStale = result?.available == true
                    initialResult = false
                    resultRetryAt = .distantPast
                    retryResultSoon = true
                    resultError = "结果已更新，正在重新读取"
                }
                return
            }
            guard TaskResultPolicy.bodyMatchesMarker(available: value.available, resultID: value.resultId,
                                                       requestedMarker: requestedMarker) else {
                // A newer workbench marker can precede upload of the matching body.
                initialResult = false
                cachedResultStale = result?.available == true
                resultRetryAt = Date().addingTimeInterval(5)
                resultError = "等待电脑同步最新结果"
                return
            }
            result = value
            resultError = ""
            cachedResultStale = false
            initialResult = true
            resultMarker = requestedMarker
            resultRetryAt = Date().addingTimeInterval(5)
        } catch {
            guard generation == resultGeneration, current, store.syncOutput, !Task.isCancelled else { return }
            resultError = "暂时无法读取最终结果"
            cachedResultStale = result?.available == true
            initialResult = false
            resultRetryAt = Date().addingTimeInterval(max(5, (error as? MonitorError)?.retryAfter ?? 0))
            if let failure = error as? MonitorError, failure.status == 429 { rateLimitUntil = resultRetryAt }
            store.handle(error, token: token, alert: false)
        }
    }
    func refreshReply() async {
        guard !Task.isCancelled, current, !refreshingReply, !sending, Date() >= replyRetryAt, Date() >= rateLimitUntil else { return }
        refreshingReply = true
        defer { refreshingReply = false }
        do {
            let queryId = unresolved ? pending?.id : nil
            let value = try await store.api.json(ReplyState.self, .reply(taskId, queryId), token: token)
            guard !Task.isCancelled, current else { return }
            replyAvailable = value.available ?? false
            if let queryId, value.found == false, value.state == "not_found", pending?.id == queryId {
                try updatePending("not_found", reason: "尚未找到发送记录，可重试原回复")
            } else if let id = value.id, let state = value.state, pending?.id == id {
                try updatePending(state, reason: value.reason)
            } else if let latest = value.latest {
                if pending == nil {
                    pending = PendingReply(id: latest.id, taskId: taskId, text: "", createdAt: Date.distantPast, state: latest.state)
                }
                if pending?.id == latest.id { try updatePending(latest.state, reason: latest.reason) }
            } else if !unresolved { replyMessage = value.reason ?? "" }
        } catch {
            guard !Task.isCancelled, current else { return }
            replyAvailable = false
            replyMessage = "暂时无法确认发送状态"
            store.handle(error, token: token, alert: false)
            if let failure = error as? MonitorError, failure.status == 429 {
                replyRetryAt = Date().addingTimeInterval(failure.retryAfter)
                rateLimitUntil = replyRetryAt
            }
        }
    }
    private func updatePending(_ state: String, reason: String?) throws {
        guard var value = pending else { return }
        let before = value.state
        value.state = state
        if state == "succeeded" {
            if draft.trimmingCharacters(in: .whitespacesAndNewlines) == value.text { draft = "" }
            value.text = ""
            if before != state { initialResult = false }
        }
        if ["failed", "blocked"].contains(state) { value.text = "" }
        try Keychain.write(value, key: ledgerKey)
        pending = value
        let titles = ["queued": "等待电脑接收", "dispatching": "等待电脑确认", "running": "正在回复", "succeeded": "回复完成", "failed": "回复未完成", "blocked": "请在电脑处理批准请求", "uncertain": "发送结果尚未确认，请先查询", "not_found": "尚未找到发送记录"]
        replyMessage = reason?.isEmpty == false ? reason! : (titles[state] ?? "正在确认发送状态")
    }
    func send() async {
        guard current, canSend else { return }
        if unresolved && pending?.mayRetry != true { await refreshReply(); return }
        let record: PendingReply
        if let pending, pending.mayRetry { record = pending }
        else {
            let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
            guard replyAvailable, !text.isEmpty, text.count <= 8000 else { return }
            record = PendingReply(id: UUID().uuidString.lowercased(), taskId: taskId, text: text, createdAt: Date(), state: "uncertain")
        }
        do { try Keychain.write(record, key: ledgerKey) }
        catch { store.notice = error.localizedDescription; return }
        pending = record
        sending = true
        replyMessage = "正在发送…"
        do {
            // This is the only POST site. Refresh/poll/relaunch never resend a reply.
            let response = try await store.api.json(ReplyState.self, .sendReply, method: "POST", body: ["task_id": taskId, "request_id": record.id, "text": record.text], token: token)
            guard current, pending?.id == record.id else { sending = false; return }
            guard response.id == record.id, let state = response.state else { throw MonitorError.invalidResponse }
            try updatePending(state, reason: response.reason)
        } catch {
            if current {
                try? updatePending("uncertain", reason: "发送结果尚未确认，请先查询")
                store.handle(error, token: token, alert: false)
                if let failure = error as? MonitorError, failure.status == 429 { rateLimitUntil = Date().addingTimeInterval(failure.retryAfter) }
            }
        }
        sending = false
        await refreshReply()
    }
    func copyResult() {
        guard current, store.syncOutput, let text = result?.text, result?.available == true else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
    func saveTXT() async {
        guard canSaveTXT, let result, let id = result.resultId, let size = result.txtSize, let hash = result.txtSha256 else { return }
        await download(.resultTXT(taskId, id), name: "最终结果.txt", size: size, hash: hash)
    }
    func saveFile(_ file: ResultFile) async {
        guard current, store.syncOutput, let result, result.available, let id = result.resultId, file.ready != false else { return }
        guard result.files?.contains(where: { $0.id == file.id && $0.sha256 == file.sha256 && $0.size == file.size && $0.ready != false }) == true else {
            store.notice = "文件列表已更新，请重新选择要保存的文件"
            return
        }
        await download(.file(taskId, id, file.id), name: file.name, size: file.size, hash: file.sha256)
    }
    private func download(_ endpoint: Endpoint, name: String, size: Int, hash: String) async {
        guard !Task.isCancelled, current, !downloading, store.syncOutput else { return }
        downloading = true
        defer { downloading = false }
        let generation = resultGeneration
        let panel = NSSavePanel()
        panel.nameFieldStringValue = MonitorSecurity.safeName(name)
        panel.canCreateDirectories = true
        panel.title = "保存文件"
        guard await panel.begin() == .OK, let destination = panel.url,
              TaskResultPolicy.canExport(currentAccount: current, syncOutput: store.syncOutput, cancelled: Task.isCancelled,
                                          requestGeneration: generation, currentGeneration: resultGeneration) else { return }
        do {
            let data = try await store.api.download(endpoint, token: token, size: size, sha256: hash)
            guard TaskResultPolicy.canExport(currentAccount: current, syncOutput: store.syncOutput, cancelled: Task.isCancelled,
                                              requestGeneration: generation, currentGeneration: resultGeneration) else { return }
            do {
                try data.write(to: destination, options: .atomic)
                store.notice = "已保存「\(destination.lastPathComponent)」"
            } catch { store.notice = "文件未保存，请检查目标文件夹权限和磁盘可用空间" }
        } catch {
            guard generation == resultGeneration, current, store.syncOutput, !Task.isCancelled else { return }
            store.handle(error, token: token)
        }
    }
}
