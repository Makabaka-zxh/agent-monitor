import AppKit
import Combine
import Foundation

private struct PersistedModelControls: Codable, Equatable {
    var selection: SessionModelSelection?
    var fastNext: OneTurnOptionChoice?
    var planNext: OneTurnOptionChoice?
}

struct QuestionDraftState: Equatable {
    var choices: [String: [String]] = [:]
    var text: [String: String] = [:]
}

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

    @Published private(set) var controls: TaskControlsResponse?
    @Published private(set) var controlsRefreshing = false
    @Published private(set) var decisionSending = false
    @Published private(set) var controlsReadFailed = false
    @Published private(set) var controlsFeedback = ""
    @Published private(set) var controlsObservedAt: Date?
    @Published private(set) var selection: SessionModelSelection?
    @Published private(set) var fastNext: OneTurnOptionChoice?
    @Published private(set) var planNext: OneTurnOptionChoice?
    @Published private(set) var decisionRecords: [String: StoredDecisionRecord] = [:]
    @Published private(set) var selectionCorrupt = false
    @Published private(set) var fastCorrupt = false
    @Published private(set) var planCorrupt = false
    @Published private(set) var recordsCorrupt = false
    @Published private(set) var questionDrafts: [String: QuestionDraftState] = [:]

    let taskId: String
    private let store: MonitorStore
    private let token: String
    private let ledgerKey: String
    private let controlsKey: String
    private let decisionsKey: String
    private let supportsReply: Bool
    private var refreshingReply = false
    private var resultMarker: String?
    private var initialResult = false
    private var resultRetryAt = Date.distantPast
    private var replyRetryAt = Date.distantPast
    private var controlsRetryAt = Date.distantPast
    private var controlsNextAt = Date.distantPast
    private var active = true
    private var resultGeneration = UUID()
    private var retryResultSoon = false
    private var observedPendingKind: String
    private var subscriptions = Set<AnyCancellable>()
    @Published private var rateLimitUntil = Date.distantPast

    init(task: MonitorTask, store: MonitorStore) {
        let taskId = task.id
        self.taskId = taskId
        supportsReply = task.supportsReply
        observedPendingKind = task.effectivePendingKind
        self.store = store
        token = store.credential?.token ?? ""
        let scopedHash = MonitorSecurity.hash(Data((store.tokenFingerprint + ":" + taskId).utf8))
        ledgerKey = "reply:" + scopedHash
        controlsKey = "controls:" + scopedHash
        decisionsKey = "decisions:" + scopedHash
        if supportsReply {
            do {
                pending = try Keychain.read(PendingReply.self, key: ledgerKey)
                if let pending, pending.unresolved { draft = pending.text }
            } catch { store.notice = error.localizedDescription }
            do {
                if let savedModel = try Keychain.read(PersistedModelControls.self, key: controlsKey) {
                    let check = SessionControlPolicy.validatePersisted(
                        PersistedSessionControls(selection: savedModel.selection, fastNext: savedModel.fastNext,
                                                 planNext: savedModel.planNext, decisions: [:]),
                        taskId: taskId
                    )
                    selection = check.selectionCorrupt ? nil : savedModel.selection
                    fastNext = check.fastCorrupt ? nil : savedModel.fastNext
                    planNext = check.planCorrupt ? nil : savedModel.planNext
                    selectionCorrupt = check.selectionCorrupt
                    fastCorrupt = check.fastCorrupt
                    planCorrupt = check.planCorrupt
                }
            } catch {
                selectionCorrupt = true
                fastCorrupt = true
                planCorrupt = true
                store.notice = error.localizedDescription
            }
            do {
                if let savedDecisions = try Keychain.read([String: StoredDecisionRecord].self, key: decisionsKey) {
                    let check = SessionControlPolicy.validatePersisted(
                        PersistedSessionControls(decisions: savedDecisions),
                        taskId: taskId
                    )
                    decisionRecords = check.recordsCorrupt ? [:] : savedDecisions
                    recordsCorrupt = check.recordsCorrupt
                }
            } catch {
                recordsCorrupt = true
                store.notice = error.localizedDescription
            }
        }
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
    var replyLocked: Bool { sending || unresolved }
    var nowSeconds: Int64 { Int64(Date().timeIntervalSince1970) }
    var hasOpenInteraction: Bool {
        controls?.pending?.contains(where: { $0.state == "pending" || $0.state == "submitted" }) == true
    }
    var hasAnyModelOverride: Bool {
        selection != nil || selectionCorrupt || fastNext != nil || fastCorrupt || planNext != nil || planCorrupt
    }
    func controlsFresh(now: Date = Date()) -> Bool {
        guard controls != nil, let controlsObservedAt else { return false }
        let age = now.timeIntervalSince(controlsObservedAt)
        return age >= -1 && age <= 15
    }
    func catalogState(now: Date = Date()) -> SessionControlPolicy.CatalogState {
        SessionControlPolicy.catalogState(controls, fresh: controlsFresh(now: now),
                                          readFailed: controlsReadFailed, now: Int64(now.timeIntervalSince1970))
    }
    var canSendReplySelection: Bool {
        SessionControlPolicy.canSendReply(
            catalog: controls?.catalog,
            state: catalogState(),
            selection: selection,
            fastNext: fastNext,
            planNext: planNext,
            selectionCorrupt: selectionCorrupt,
            fastCorrupt: fastCorrupt,
            planCorrupt: planCorrupt,
            now: nowSeconds
        )
    }
    var replySettingsSummary: String {
        if !canSendReplySelection { return "模型设置待重新确认" }
        if !hasAnyModelOverride { return "继承电脑设置" }
        guard catalogState() == .ready, let catalog = controls?.catalog else { return "继承电脑设置" }
        let now = nowSeconds
        var parts: [String] = []
        if let sel = selection, let item = SessionControlPolicy.model(in: catalog, id: sel.model) {
            parts.append(SessionControlPolicy.displayModelName(item))
            if let eff = sel.effort, !eff.isEmpty { parts.append(SessionControlPolicy.effortLabel(eff)) }
        } else {
            parts.append("继承模型")
        }
        if SessionControlPolicy.fastReply(catalog, selected: selection, pending: fastNext, now: now) != nil {
            parts.append("快速")
        }
        if SessionControlPolicy.planReply(catalog, selected: selection, pending: planNext, now: now) != nil {
            parts.append("计划")
        }
        return parts.joined(separator: " · ")
    }
    var recentUsedModelSummary: String? {
        guard catalogState() == .ready, let catalog = controls?.catalog,
              let currentModel = catalog.currentModel, !currentModel.isEmpty else { return nil }
        let item = SessionControlPolicy.model(in: catalog, id: currentModel)
        let name = item.map(SessionControlPolicy.displayModelName) ?? SessionControlPolicy.modelName(currentModel)
        if let effort = catalog.currentEffort, !effort.isEmpty {
            return "\(name) · \(SessionControlPolicy.effortLabel(effort))"
        }
        return name
    }
    var canSend: Bool {
        supportsReply && current && Date() >= rateLimitUntil && !sending && !refreshingReply
            && (unresolved || (replyAvailable && canSendReplySelection
                && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && draft.count <= 8000))
    }
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
            if supportsReply {
                await refreshReply()
                guard !Task.isCancelled && current else { return }
                await refreshControls()
            }
            let waitingTask = store.task(taskId)?.effectiveStatus == "waiting"
            let catalogPending = controls?.catalogStatus == "pending"
            let interval = retryResultSoon ? 1 : catalogPending ? 2 : (unresolved || waitingTask || hasOpenInteraction) ? 3 : 8
            do { try await Task.sleep(for: .seconds(interval)) } catch { return }
        }
    }

    func observePendingKind(_ nextKind: String) {
        guard nextKind != observedPendingKind else { return }
        observedPendingKind = nextKind
        if !nextKind.isEmpty && supportsReply && current {
            controlsNextAt = .distantPast
            Task { await refreshControls(force: true) }
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

    func refreshControls(force: Bool = false) async {
        guard supportsReply, !Task.isCancelled, current, !controlsRefreshing, !decisionSending,
              Date() >= rateLimitUntil, Date() >= controlsRetryAt,
              force || Date() >= controlsNextAt else { return }
        controlsRefreshing = true
        defer { controlsRefreshing = false }
        do {
            let value = try await store.api.json(TaskControlsResponse.self, .controls(taskId), token: token)
            guard !Task.isCancelled, current else { return }
            controls = value
            controlsReadFailed = false
            controlsObservedAt = Date()
            controlsFeedback = ""
            let catalogPending = value.catalogStatus == "pending"
            let fastPoll = unresolved || store.task(taskId)?.effectiveStatus == "waiting" || hasOpenInteraction
            controlsNextAt = Date().addingTimeInterval(catalogPending ? 1.5 : fastPoll ? 3 : 5)
            pruneFinishedDecisions()
        } catch {
            guard !Task.isCancelled, current else { return }
            controlsReadFailed = true
            controlsObservedAt = nil
            controlsFeedback = "暂时无法确认请求状态，请刷新后再操作"
            let backoff = max(5, (error as? MonitorError)?.retryAfter ?? 0)
            controlsRetryAt = Date().addingTimeInterval(backoff)
            controlsNextAt = controlsRetryAt
            if let failure = error as? MonitorError, failure.status == 429 { rateLimitUntil = controlsRetryAt }
            store.handle(error, token: token, alert: false)
        }
    }

    private func writeModelControls(selection nextSelection: SessionModelSelection?,
                                    fastNext nextFast: OneTurnOptionChoice?,
                                    planNext nextPlan: OneTurnOptionChoice?) throws {
        if nextSelection == nil && nextFast == nil && nextPlan == nil {
            try Keychain.remove(controlsKey)
        } else {
            try Keychain.write(PersistedModelControls(selection: nextSelection, fastNext: nextFast, planNext: nextPlan),
                               key: controlsKey)
        }
    }

    private func writeDecisionRecords(_ next: [String: StoredDecisionRecord]) throws {
        if next.isEmpty {
            try Keychain.remove(decisionsKey)
        } else {
            try Keychain.write(next, key: decisionsKey)
        }
    }

    private func pruneFinishedDecisions() {
        guard !recordsCorrupt, let items = controls?.pending else { return }
        var next = decisionRecords
        var changed = false
        for item in items where SessionControlPolicy.validRequest(item) && SessionControlPolicy.terminal(item) {
            if let removed = next.removeValue(forKey: item.id) {
                questionDrafts.removeValue(forKey: removed.fingerprint)
                changed = true
            }
        }
        guard changed else { return }
        if (try? writeDecisionRecords(next)) != nil {
            decisionRecords = next
        }
    }

    func resetModelSettings() {
        guard current, !replyLocked else { return }
        do {
            try writeModelControls(selection: nil, fastNext: nil, planNext: nil)
            selection = nil
            selectionCorrupt = false
            fastNext = nil
            fastCorrupt = false
            planNext = nil
            planCorrupt = false
        } catch {
            store.notice = error.localizedDescription
        }
    }

    func selectModel(_ modelId: String?) {
        guard current, !replyLocked else { return }
        guard let modelId, !modelId.isEmpty else {
            resetModelSettings()
            return
        }
        let now = nowSeconds
        guard catalogState() == .ready, let catalog = controls?.catalog,
              SessionControlPolicy.model(in: catalog, id: modelId) != nil else {
            store.notice = "模型选项已变化，请重新打开选择"
            Task { await refreshControls(force: true) }
            return
        }
        if let existing = selection, !selectionCorrupt, existing.model == modelId,
           SessionControlPolicy.validSelection(existing, catalog: catalog, now: now) {
            return
        }
        let next = SessionModelSelection(provider: catalog.provider, catalogRevision: catalog.catalogRevision,
                                         model: modelId, effort: nil, applyAt: "next_message")
        guard SessionControlPolicy.validSelection(next, catalog: catalog, now: now) else {
            store.notice = "无法保存模型选择"
            return
        }
        do {
            try writeModelControls(selection: next, fastNext: nil, planNext: nil)
            selection = next
            selectionCorrupt = false
            fastNext = nil
            fastCorrupt = false
            planNext = nil
            planCorrupt = false
        } catch {
            store.notice = error.localizedDescription
        }
    }

    func selectEffort(_ effort: String?) {
        guard current, !replyLocked else { return }
        let now = nowSeconds
        guard catalogState() == .ready, let catalog = controls?.catalog,
              let resolved = SessionControlPolicy.effortModel(catalog, selected: selection,
                                                              corrupt: selectionCorrupt, now: now) else {
            store.notice = "模型选项已变化，请重新选择"
            return
        }
        let normalized = (effort?.isEmpty == true) ? nil : effort
        if selection == nil && normalized == nil { return }
        let next = SessionModelSelection(provider: catalog.provider, catalogRevision: catalog.catalogRevision,
                                         model: resolved.id, effort: normalized, applyAt: "next_message")
        guard SessionControlPolicy.validSelection(next, catalog: catalog, now: now) else {
            store.notice = "模型选项已变化，请重新选择"
            return
        }
        do {
            let preservedFast = fastCorrupt ? nil : fastNext
            let preservedPlan = planCorrupt ? nil : planNext
            try writeModelControls(selection: next, fastNext: preservedFast, planNext: preservedPlan)
            selection = next
            selectionCorrupt = false
        } catch {
            store.notice = error.localizedDescription
        }
    }

    func setOneTurnOption(plan: Bool, enabled: Bool) {
        guard current, !replyLocked else { return }
        let label = plan ? "回复模式" : "速度选项"
        let now = nowSeconds
        guard catalogState() == .ready, let catalog = controls?.catalog else {
            store.notice = "正在重新确认\(label)，请稍后重试"
            Task { await refreshControls(force: true) }
            return
        }
        let chosen: OneTurnOptionChoice?
        if enabled {
            let staged = plan
                ? SessionControlPolicy.stagePlan(catalog, selected: selection, corrupt: selectionCorrupt, now: now)
                : SessionControlPolicy.stageFast(catalog, selected: selection, corrupt: selectionCorrupt, now: now)
            guard let staged else {
                store.notice = "\(label)已变化，请重新选择"
                return
            }
            chosen = staged
        } else {
            chosen = nil
        }
        let nextFast = plan ? (fastCorrupt ? nil : fastNext) : chosen
        let nextPlan = plan ? chosen : (planCorrupt ? nil : planNext)
        do {
            try writeModelControls(selection: selectionCorrupt ? nil : selection, fastNext: nextFast, planNext: nextPlan)
            if plan {
                planNext = chosen
                planCorrupt = false
            } else {
                fastNext = chosen
                fastCorrupt = false
            }
        } catch {
            store.notice = error.localizedDescription
        }
    }

    func uniquePendingRequest(id: String) -> SessionPendingRequest? {
        guard let items = controls?.pending else { return nil }
        let matches = items.filter { $0.id == id }
        return matches.count == 1 ? matches[0] : nil
    }

    func canAct(on request: SessionPendingRequest, now: Date = Date()) -> Bool {
        current && controlsFresh(now: now) && !decisionSending && !recordsCorrupt
            && Date() >= rateLimitUntil
            && SessionControlPolicy.actionable(request, capabilities: controls?.capabilities,
                                               now: Int64(now.timeIntervalSince1970))
    }

    func selectedChoices(fingerprint: String, questionId: String) -> [String] {
        questionDrafts[fingerprint]?.choices[questionId] ?? []
    }

    func customAnswerText(fingerprint: String, questionId: String) -> String {
        questionDrafts[fingerprint]?.text[questionId] ?? ""
    }

    func toggleChoice(request: SessionPendingRequest, question: SessionQuestion, optionId: String) {
        let fp = SessionControlPolicy.fingerprintDigest(request)
        guard !fp.isEmpty else { return }
        var state = questionDrafts[fp] ?? QuestionDraftState()
        state.text[question.id] = nil
        var selected = state.choices[question.id] ?? []
        if question.multiple == true {
            if let index = selected.firstIndex(of: optionId) { selected.remove(at: index) }
            else { selected.append(optionId) }
        } else {
            selected = [optionId]
        }
        state.choices[question.id] = selected
        questionDrafts[fp] = state
    }

    func updateCustomText(request: SessionPendingRequest, question: SessionQuestion, value: String) {
        let fp = SessionControlPolicy.fingerprintDigest(request)
        guard !fp.isEmpty, question.allowsText == true else { return }
        let bounded = value.unicodeScalars.count > 4000
            ? String(String.UnicodeScalarView(value.unicodeScalars.prefix(4000)))
            : value
        var state = questionDrafts[fp] ?? QuestionDraftState()
        state.text[question.id] = bounded
        if !bounded.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            state.choices[question.id] = []
        }
        questionDrafts[fp] = state
    }

    func submitApproval(_ request: SessionPendingRequest, fingerprint: String, allowOnce: Bool) async {
        let payload = SessionDecisionPayload(
            taskId: taskId,
            requestId: request.id,
            revision: request.revision,
            decisionId: UUID().uuidString.lowercased(),
            decision: allowOnce ? "allow_once" : "deny",
            answers: []
        )
        await sendDecision(request: request, payload: payload, fingerprint: fingerprint, retry: false)
    }

    func submitAnswers(_ request: SessionPendingRequest, fingerprint: String) async {
        guard let questions = request.questions else {
            store.notice = "回答未能确认，请检查后重试"
            return
        }
        let draftState = questionDrafts[fingerprint] ?? QuestionDraftState()
        let answers: [SessionAnswerPayload] = questions.map { question in
            let choices = draftState.choices[question.id] ?? []
            let rawText = draftState.text[question.id]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return SessionAnswerPayload(questionId: question.id, choices: choices, text: rawText.isEmpty ? nil : rawText)
        }
        guard SessionControlPolicy.validAnswers(request, answers: answers) else {
            store.notice = "请为每个问题选择选项或填写允许的文字回答"
            return
        }
        let payload = SessionDecisionPayload(
            taskId: taskId,
            requestId: request.id,
            revision: request.revision,
            decisionId: UUID().uuidString.lowercased(),
            decision: "answer",
            answers: answers
        )
        await sendDecision(request: request, payload: payload, fingerprint: fingerprint, retry: false)
    }

    func retryDecision(_ request: SessionPendingRequest, fingerprint: String) async {
        guard let record = decisionRecords[request.id] else {
            store.notice = "未找到原发送记录，请刷新后确认"
            return
        }
        await sendDecision(request: request, payload: record.payload, fingerprint: fingerprint, retry: true)
    }

    private func sendDecision(request: SessionPendingRequest, payload: SessionDecisionPayload,
                              fingerprint: String, retry: Bool) async {
        let now = Date()
        let nowSec = Int64(now.timeIntervalSince1970)
        guard let latest = uniquePendingRequest(id: request.id),
              canAct(on: latest, now: now),
              SessionControlPolicy.fingerprintDigest(latest) == fingerprint else {
            store.notice = "请求状态已变化，请刷新后确认"
            await refreshControls(force: true)
            return
        }
        guard SessionControlPolicy.validDecision(latest, payload: payload, taskId: taskId, now: nowSec) else {
            store.notice = "决定内容与当前请求不匹配，请刷新后确认"
            return
        }
        let existing = decisionRecords[latest.id]
        if retry {
            guard let existing, existing.fingerprint == fingerprint, existing.payload == payload else {
                store.notice = "已有发送记录，请先确认原决定的状态"
                return
            }
        } else {
            guard existing == nil else {
                store.notice = "已有发送记录，请先确认原决定的状态"
                return
            }
            guard decisionRecords.count < 64 else {
                store.notice = "待确认的发送记录过多，请先在电脑确认结果"
                return
            }
            var next = decisionRecords
            next[latest.id] = StoredDecisionRecord(fingerprint: fingerprint, payload: payload)
            do {
                // Persist before transport so an interrupted response never permits switching choices.
                try writeDecisionRecords(next)
                decisionRecords = next
            } catch {
                store.notice = "无法保存决定，尚未发送"
                return
            }
        }
        decisionSending = true
        var succeeded = false
        do {
            _ = try await store.api.json(SessionPendingRequest.self, .controlDecision,
                                         method: "POST", body: payload.wireDictionary, token: token)
            succeeded = true
        } catch {
            if current {
                store.handle(error, token: token, alert: false)
                if let failure = error as? MonitorError, failure.status == 429 {
                    rateLimitUntil = Date().addingTimeInterval(failure.retryAfter)
                    controlsRetryAt = rateLimitUntil
                }
            }
        }
        decisionSending = false
        guard current else { return }
        controlsObservedAt = nil
        controlsNextAt = .distantPast
        controlsFeedback = succeeded ? "决定已发送，正在确认电脑是否收到" : "发送结果尚未确认；先刷新，重试会使用原决定"
        await refreshControls(force: true)
    }

    func refreshReply() async {
        guard supportsReply, !Task.isCancelled, current, !refreshingReply, !sending, Date() >= replyRetryAt, Date() >= rateLimitUntil else { return }
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
            value.submittedSelection = nil
            if before != state { initialResult = false }
        }
        if ["failed", "blocked"].contains(state) {
            value.text = ""
            value.submittedSelection = nil
        }
        try Keychain.write(value, key: ledgerKey)
        pending = value
        let titles = ["queued": "等待电脑接收", "dispatching": "等待电脑确认", "running": "正在回复", "succeeded": "回复完成", "failed": "回复未完成", "blocked": "请在电脑处理批准请求", "uncertain": "发送结果尚未确认，请先查询", "not_found": "尚未找到发送记录"]
        replyMessage = reason?.isEmpty == false ? reason! : (titles[state] ?? "正在确认发送状态")
    }

    func send() async {
        guard current, canSend else { return }
        if unresolved && pending?.mayRetry != true { await refreshReply(); return }
        let record: PendingReply
        let consumeFast: Bool
        let consumePlan: Bool
        if let pending, pending.mayRetry {
            record = pending
            consumeFast = false
            consumePlan = false
        } else {
            let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
            guard replyAvailable, !text.isEmpty, text.count <= 8000 else { return }
            let resolvedSelection: SessionModelSelection?
            switch SessionControlPolicy.selectionForSend(
                catalog: controls?.catalog,
                state: catalogState(),
                selection: selection,
                fastNext: fastNext,
                planNext: planNext,
                selectionCorrupt: selectionCorrupt,
                fastCorrupt: fastCorrupt,
                planCorrupt: planCorrupt,
                now: nowSeconds
            ) {
            case let .success(chosen):
                resolvedSelection = chosen
            case let .failure(problem):
                store.notice = problem.localizedDescription
                return
            }
            record = PendingReply(
                id: UUID().uuidString.lowercased(),
                taskId: taskId,
                text: text,
                createdAt: Date(),
                state: "uncertain",
                submittedSelection: resolvedSelection,
                submittedSelectionInvalid: false
            )
            consumeFast = SessionControlPolicy.fastRequested(resolvedSelection)
            consumePlan = SessionControlPolicy.planRequested(resolvedSelection)
        }
        let previousPending = pending
        do {
            try Keychain.write(record, key: ledgerKey)
            if consumeFast || consumePlan {
                let remainingFast = consumeFast ? nil : (fastCorrupt ? nil : fastNext)
                let remainingPlan = consumePlan ? nil : (planCorrupt ? nil : planNext)
                do {
                    try writeModelControls(selection: selectionCorrupt ? nil : selection,
                                           fastNext: remainingFast, planNext: remainingPlan)
                } catch {
                    if let previousPending { try? Keychain.write(previousPending, key: ledgerKey) }
                    else { try? Keychain.remove(ledgerKey) }
                    throw error
                }
                if consumeFast { fastNext = nil; fastCorrupt = false }
                if consumePlan { planNext = nil; planCorrupt = false }
            }
        } catch {
            store.notice = error.localizedDescription
            return
        }
        pending = record
        sending = true
        replyMessage = "正在发送…"
        var body: [String: Any] = ["task_id": taskId, "request_id": record.id, "text": record.text]
        if let submitted = record.submittedSelection {
            body["selection"] = submitted.wireDictionary
        }
        do {
            // This is the only POST site. Refresh/poll/relaunch never resend a reply.
            let response = try await store.api.json(ReplyState.self, .sendReply, method: "POST", body: body, token: token)
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
        controlsNextAt = .distantPast
        await refreshControls(force: true)
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
