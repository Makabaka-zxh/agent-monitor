import Foundation

struct SessionCapabilities: Codable, Equatable {
    var questions: Bool
    var approvals: Bool
    var modelSettings: Bool

    init(questions: Bool = false, approvals: Bool = false, modelSettings: Bool = false) {
        self.questions = questions
        self.approvals = approvals
        self.modelSettings = modelSettings
    }

    private enum CodingKeys: String, CodingKey { case questions, approvals, modelSettings }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        questions = (try? c.decode(Bool.self, forKey: .questions)) ?? false
        approvals = (try? c.decode(Bool.self, forKey: .approvals)) ?? false
        modelSettings = (try? c.decode(Bool.self, forKey: .modelSettings)) ?? false
    }
}

struct SessionModelOption: Codable, Equatable, Identifiable {
    var id: String
    var label: String
    var efforts: [String]?
    var serviceTiers: [String]?

    init(id: String, label: String, efforts: [String]? = [], serviceTiers: [String]? = []) {
        self.id = id
        self.label = label
        self.efforts = efforts
        self.serviceTiers = serviceTiers
    }

    static let invalidPlaceholder = SessionModelOption(id: "", label: "", efforts: nil, serviceTiers: nil)

    private enum CodingKeys: String, CodingKey { case id, label, efforts, serviceTiers }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(String.self, forKey: .id)) ?? ""
        label = (try? c.decode(String.self, forKey: .label)) ?? ""
        efforts = try? c.decode([String].self, forKey: .efforts)
        if c.contains(.serviceTiers) {
            if (try? c.decodeNil(forKey: .serviceTiers)) == true { serviceTiers = [] }
            else { serviceTiers = try? c.decode([String].self, forKey: .serviceTiers) }
        } else {
            serviceTiers = []
        }
    }
}

private struct LossyModelOption: Decodable {
    let value: SessionModelOption
    init(from decoder: Decoder) throws {
        value = (try? SessionModelOption(from: decoder)) ?? .invalidPlaceholder
    }
}

struct SessionModelCatalog: Codable, Equatable {
    var provider: String
    var catalogRevision: String
    var validUntil: Double?
    var models: [SessionModelOption]?
    var collaborationModes: [String]?
    var currentModel: String?
    var currentEffort: String?

    init(provider: String, catalogRevision: String, validUntil: Double?, models: [SessionModelOption]?,
         collaborationModes: [String]? = [], currentModel: String? = nil, currentEffort: String? = nil) {
        self.provider = provider
        self.catalogRevision = catalogRevision
        self.validUntil = validUntil
        self.models = models
        self.collaborationModes = collaborationModes
        self.currentModel = currentModel
        self.currentEffort = currentEffort
    }

    private enum CodingKeys: String, CodingKey {
        case provider, catalogRevision, validUntil, models, collaborationModes, currentModel, currentEffort
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        catalogRevision = (try? c.decode(String.self, forKey: .catalogRevision)) ?? ""
        let rawValidUntil = try? c.decode(Double.self, forKey: .validUntil)
        validUntil = rawValidUntil
        models = (try? c.decode([LossyModelOption].self, forKey: .models))?.map(\.value)
        if c.contains(.collaborationModes) {
            if (try? c.decodeNil(forKey: .collaborationModes)) == true { collaborationModes = [] }
            else { collaborationModes = try? c.decode([String].self, forKey: .collaborationModes) }
        } else {
            collaborationModes = []
        }
        currentModel = try? c.decode(String.self, forKey: .currentModel)
        currentEffort = try? c.decode(String.self, forKey: .currentEffort)
    }
}

struct SessionChoice: Codable, Equatable, Identifiable {
    var id: String
    var label: String
    var description: String?

    init(id: String, label: String, description: String? = "") {
        self.id = id
        self.label = label
        self.description = description
    }

    static let invalidPlaceholder = SessionChoice(id: "", label: "", description: nil)

    private enum CodingKeys: String, CodingKey { case id, label, description }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(String.self, forKey: .id)) ?? ""
        label = (try? c.decode(String.self, forKey: .label)) ?? ""
        if c.contains(.description) {
            if (try? c.decodeNil(forKey: .description)) == true { description = "" }
            else { description = try? c.decode(String.self, forKey: .description) }
        } else {
            description = ""
        }
    }
}

private struct LossySessionChoice: Decodable {
    let value: SessionChoice
    init(from decoder: Decoder) throws {
        value = (try? SessionChoice(from: decoder)) ?? .invalidPlaceholder
    }
}

struct SessionQuestion: Codable, Equatable, Identifiable {
    var id: String
    var text: String
    var multiple: Bool?
    var allowsText: Bool?
    var options: [SessionChoice]?

    init(id: String, text: String, multiple: Bool? = false, allowsText: Bool? = false, options: [SessionChoice]? = []) {
        self.id = id
        self.text = text
        self.multiple = multiple
        self.allowsText = allowsText
        self.options = options
    }

    static let invalidPlaceholder = SessionQuestion(id: "", text: "", multiple: nil, allowsText: nil, options: nil)

    private enum CodingKeys: String, CodingKey { case id, text, multiple, allowsText, options }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(String.self, forKey: .id)) ?? ""
        text = (try? c.decode(String.self, forKey: .text)) ?? ""
        multiple = try? c.decode(Bool.self, forKey: .multiple)
        allowsText = try? c.decode(Bool.self, forKey: .allowsText)
        options = (try? c.decode([LossySessionChoice].self, forKey: .options))?.map(\.value)
    }
}

private struct LossySessionQuestion: Decodable {
    let value: SessionQuestion
    init(from decoder: Decoder) throws {
        value = (try? SessionQuestion(from: decoder)) ?? .invalidPlaceholder
    }
}

struct SessionPendingRequest: Codable, Equatable, Identifiable {
    var id: String
    var revision: String
    var turnId: String
    var provider: String
    var kind: String
    var title: String
    var detail: String?
    var operation: String?
    var questions: [SessionQuestion]?
    var expiresAt: Double?
    var state: String
    var decisionId: String?

    init(id: String, revision: String, turnId: String, provider: String, kind: String, title: String,
         detail: String? = "", operation: String? = "", questions: [SessionQuestion]? = [],
         expiresAt: Double?, state: String, decisionId: String? = nil) {
        self.id = id
        self.revision = revision
        self.turnId = turnId
        self.provider = provider
        self.kind = kind
        self.title = title
        self.detail = detail
        self.operation = operation
        self.questions = questions
        self.expiresAt = expiresAt
        self.state = state
        self.decisionId = decisionId
    }

    static let invalidPlaceholder = SessionPendingRequest(
        id: "", revision: "", turnId: "", provider: "", kind: "", title: "",
        detail: nil, operation: nil, questions: nil, expiresAt: nil, state: "", decisionId: nil
    )

    private enum CodingKeys: String, CodingKey {
        case id, revision, turnId, provider, kind, title, detail, operation, questions, expiresAt, state, decisionId
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(String.self, forKey: .id)) ?? ""
        revision = (try? c.decode(String.self, forKey: .revision)) ?? ""
        turnId = (try? c.decode(String.self, forKey: .turnId)) ?? ""
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        kind = (try? c.decode(String.self, forKey: .kind)) ?? ""
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        if c.contains(.detail) {
            if (try? c.decodeNil(forKey: .detail)) == true { detail = "" }
            else { detail = try? c.decode(String.self, forKey: .detail) }
        } else { detail = "" }
        if c.contains(.operation) {
            if (try? c.decodeNil(forKey: .operation)) == true { operation = "" }
            else { operation = try? c.decode(String.self, forKey: .operation) }
        } else { operation = "" }
        if c.contains(.questions) {
            if (try? c.decodeNil(forKey: .questions)) == true { questions = [] }
            else { questions = (try? c.decode([LossySessionQuestion].self, forKey: .questions))?.map(\.value) }
        } else { questions = [] }
        expiresAt = try? c.decode(Double.self, forKey: .expiresAt)
        state = (try? c.decode(String.self, forKey: .state)) ?? ""
        decisionId = try? c.decode(String.self, forKey: .decisionId)
    }
}

private struct LossyPendingRequest: Decodable {
    let value: SessionPendingRequest
    init(from decoder: Decoder) throws {
        value = (try? SessionPendingRequest(from: decoder)) ?? .invalidPlaceholder
    }
}

struct TaskControlsResponse: Codable, Equatable {
    var catalog: SessionModelCatalog?
    var catalogMalformed: Bool
    var catalogStatus: String?
    var pending: [SessionPendingRequest]?
    var capabilities: SessionCapabilities?
    var reason: String?

    init(catalog: SessionModelCatalog? = nil, catalogMalformed: Bool = false, catalogStatus: String? = nil,
         pending: [SessionPendingRequest]? = [], capabilities: SessionCapabilities? = nil, reason: String? = nil) {
        self.catalog = catalog
        self.catalogMalformed = catalogMalformed
        self.catalogStatus = catalogStatus
        self.pending = pending
        self.capabilities = capabilities
        self.reason = reason
    }

    private enum CodingKeys: String, CodingKey { case catalog, catalogStatus, pending, capabilities, reason }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if c.contains(.catalog), (try? c.decodeNil(forKey: .catalog)) != true {
            catalog = try? c.decode(SessionModelCatalog.self, forKey: .catalog)
            catalogMalformed = (catalog == nil)
        } else {
            catalog = nil
            catalogMalformed = false
        }
        catalogStatus = try? c.decode(String.self, forKey: .catalogStatus)
        if c.contains(.pending) {
            if (try? c.decodeNil(forKey: .pending)) == true { pending = [] }
            else { pending = (try? c.decode([LossyPendingRequest].self, forKey: .pending))?.map(\.value) }
        } else {
            pending = []
        }
        capabilities = try? c.decode(SessionCapabilities.self, forKey: .capabilities)
        reason = try? c.decode(String.self, forKey: .reason)
    }
}

struct OneTurnOptionChoice: Codable, Equatable {
    var model: String
    var catalogRevision: String
}

struct SessionModelSelection: Codable, Equatable {
    var provider: String
    var catalogRevision: String
    var model: String
    var effort: String?
    var applyAt: String
    var serviceTier: String?
    var collaborationMode: String?
    var hasInvalidFieldTypes: Bool = false

    init(provider: String, catalogRevision: String, model: String, effort: String? = nil,
         applyAt: String = "next_message", serviceTier: String? = nil, collaborationMode: String? = nil,
         hasInvalidFieldTypes: Bool = false) {
        self.provider = provider
        self.catalogRevision = catalogRevision
        self.model = model
        self.effort = effort
        self.applyAt = applyAt
        self.serviceTier = serviceTier
        self.collaborationMode = collaborationMode
        self.hasInvalidFieldTypes = hasInvalidFieldTypes
    }

    private enum CodingKeys: String, CodingKey {
        case provider, catalogRevision, model, effort, applyAt, serviceTier, collaborationMode
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        catalogRevision = (try? c.decode(String.self, forKey: .catalogRevision)) ?? ""
        model = (try? c.decode(String.self, forKey: .model)) ?? ""
        applyAt = (try? c.decode(String.self, forKey: .applyAt)) ?? ""
        var invalid = false
        if c.contains(.effort), (try? c.decodeNil(forKey: .effort)) != true {
            if let value = try? c.decode(String.self, forKey: .effort) { effort = value }
            else { effort = nil; invalid = true }
        } else { effort = nil }
        if c.contains(.serviceTier), (try? c.decodeNil(forKey: .serviceTier)) != true {
            if let value = try? c.decode(String.self, forKey: .serviceTier) { serviceTier = value }
            else { serviceTier = nil; invalid = true }
        } else { serviceTier = nil }
        if c.contains(.collaborationMode), (try? c.decodeNil(forKey: .collaborationMode)) != true {
            if let value = try? c.decode(String.self, forKey: .collaborationMode) { collaborationMode = value }
            else { collaborationMode = nil; invalid = true }
        } else { collaborationMode = nil }
        hasInvalidFieldTypes = invalid
    }

    var wireDictionary: [String: Any] {
        var body: [String: Any] = [
            "provider": provider,
            "catalog_revision": catalogRevision,
            "model": model,
            "effort": effort ?? NSNull(),
            "apply_at": applyAt
        ]
        if let serviceTier { body["service_tier"] = serviceTier }
        if let collaborationMode { body["collaboration_mode"] = collaborationMode }
        return body
    }
}

struct SessionAnswerPayload: Codable, Equatable {
    var questionId: String
    var choices: [String]
    var text: String?
    var hasInvalidFieldTypes: Bool = false

    init(questionId: String, choices: [String] = [], text: String? = nil, hasInvalidFieldTypes: Bool = false) {
        self.questionId = questionId
        self.choices = choices
        self.text = text
        self.hasInvalidFieldTypes = hasInvalidFieldTypes
    }

    private enum CodingKeys: String, CodingKey { case questionId, choices, text }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        questionId = (try? c.decode(String.self, forKey: .questionId)) ?? ""
        var invalid = false
        if let decodedChoices = try? c.decode([String].self, forKey: .choices) {
            choices = decodedChoices
        } else {
            choices = []
            invalid = true
        }
        if c.contains(.text), (try? c.decodeNil(forKey: .text)) != true {
            if let value = try? c.decode(String.self, forKey: .text) { text = value }
            else { text = nil; invalid = true }
        } else {
            text = nil
        }
        hasInvalidFieldTypes = invalid
    }

    var wireDictionary: [String: Any] {
        ["question_id": questionId, "choices": choices, "text": text ?? NSNull()]
    }
}

struct SessionDecisionPayload: Codable, Equatable {
    var taskId: String
    var requestId: String
    var revision: String
    var decisionId: String
    var decision: String
    var answers: [SessionAnswerPayload]

    init(taskId: String, requestId: String, revision: String, decisionId: String,
         decision: String, answers: [SessionAnswerPayload] = []) {
        self.taskId = taskId
        self.requestId = requestId
        self.revision = revision
        self.decisionId = decisionId
        self.decision = decision
        self.answers = answers
    }

    var wireDictionary: [String: Any] {
        [
            "task_id": taskId,
            "request_id": requestId,
            "revision": revision,
            "decision_id": decisionId,
            "decision": decision,
            "answers": answers.map(\.wireDictionary)
        ]
    }
}

struct StoredDecisionRecord: Codable, Equatable {
    var fingerprint: String
    var payload: SessionDecisionPayload
}

struct PersistedSessionControls: Codable, Equatable {
    var selection: SessionModelSelection?
    var fastNext: OneTurnOptionChoice?
    var planNext: OneTurnOptionChoice?
    var decisions: [String: StoredDecisionRecord]

    init(selection: SessionModelSelection? = nil, fastNext: OneTurnOptionChoice? = nil,
         planNext: OneTurnOptionChoice? = nil, decisions: [String: StoredDecisionRecord] = [:]) {
        self.selection = selection
        self.fastNext = fastNext
        self.planNext = planNext
        self.decisions = decisions
    }
}

/// Fail-closed wire validation and selection binding for macOS session controls.
/// No provider options, questions, or approvals are inferred from transcript text.
enum SessionControlPolicy {
    enum CatalogState: Equatable, CaseIterable {
        case loading
        case notProvided
        case requestFailed
        case rechecking
        case expired
        case invalid
        case ready

        var message: String {
            switch self {
            case .loading: "正在获取这项任务可用的模型…"
            case .notProvided: "暂时没有可选模型，可重新获取"
            case .requestFailed: "模型列表读取失败，请稍后重试"
            case .rechecking: "正在重新确认这项任务的模型信息…"
            case .expired: "模型列表已过期，可重新获取"
            case .invalid: "返回的模型列表无法验证，可重新获取"
            case .ready: ""
            }
        }
    }

    struct PersistedValidation: Equatable {
        var selectionCorrupt: Bool
        var fastCorrupt: Bool
        var planCorrupt: Bool
        var recordsCorrupt: Bool
    }

    private static let uuidPattern = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
    private static let sha256Pattern = "[0-9a-f]{64}"

    private static let claudeRegex = try! NSRegularExpression(
        pattern: #"\A(?:anthropic/)?claude-(opus|sonnet|haiku|fable|mythos)(?:-([0-9]{1,2}(?:[.-][0-9]{1,2})?))?(?:-(low|medium|high|xhigh))?(?:-(?:[0-9]{8}|latest))?\z"#,
        options: [.caseInsensitive]
    )
    private static let earlyClaudeRegex = try! NSRegularExpression(
        pattern: #"\A(?:anthropic/)?claude-([0-9]{1,2}(?:[.-][0-9]{1,2})?)-(opus|sonnet|haiku|fable|mythos)(?:-(?:[0-9]{8}|latest))?\z"#,
        options: [.caseInsensitive]
    )
    private static let geminiRegex = try! NSRegularExpression(
        pattern: #"\A(?:google/)?gemini(?:-([0-9]+(?:[.-][0-9]+)?))?((?:-(?:pro|flash|lite|ultra|nano|neon|carbon|hydrogen|argon|orchestrator|tiered|worker|thinking|exp))+)(?:-(low|medium|high|xhigh))?(?:-(?:agy|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|latest))?\z"#,
        options: [.caseInsensitive]
    )
    private static let gptOssRegex = try! NSRegularExpression(
        pattern: #"\A(?:openai/)?gpt-oss-([0-9]+b)(?:-(low|medium|high|xhigh))?\z"#,
        options: [.caseInsensitive]
    )
    private static let gptRegex = try! NSRegularExpression(
        pattern: #"\A(?:openai/)?(gpt-[0-9]+(?:\.[0-9]+)?o?|o[1-9][0-9]*)((?:-(?:codex|spark|pro|mini|nano|max|turbo|astra|sol|luna|terra))*)(?:-[0-9]{4}-[0-9]{2}-[0-9]{2})?\z"#,
        options: [.caseInsensitive]
    )

    static func provider(_ value: String) -> Bool {
        value == "codex" || value == "claude" || value == "antigravity"
    }

    static func validText(_ value: String?, max: Int, required: Bool) -> Bool {
        guard let value else { return !required }
        if value.unicodeScalars.count > max { return false }
        if required && value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
        for scalar in value.unicodeScalars {
            let code = scalar.value
            let isDisallowedControl = (code <= 0x1F && code != 0x09 && code != 0x0A && code != 0x0D) || (0x7F...0x9F).contains(code)
            let isBidiOverride = (0x202A...0x202E).contains(code) || (0x2066...0x2069).contains(code)
            if isDisallowedControl || isBidiOverride { return false }
        }
        return true
    }

    static func validId(_ value: String?, max: Int = 240) -> Bool {
        guard let value, validText(value, max: max, required: true) else { return false }
        return !value.contains("\n") && !value.contains("\r") && !value.contains("\t")
    }

    static func seconds(_ value: Double?) -> Int64 {
        guard let value, value.isFinite, value >= 0, value <= 32_503_680_000 else { return 0 }
        return Int64(value)
    }

    static func validCatalog(_ value: SessionModelCatalog?, now: Int64) -> Bool {
        guard let value, provider(value.provider), validId(value.catalogRevision, max: 128),
              seconds(value.validUntil) > now,
              let models = value.models, !models.isEmpty, models.count <= 256 else { return false }
        var seenModels = Set<String>()
        for item in models {
            guard validId(item.id, max: 120), seenModels.insert(item.id).inserted,
                  validText(item.label, max: 120, required: true),
                  let efforts = item.efforts, efforts.count <= 16 else { return false }
            var seenEfforts = Set<String>()
            for level in efforts {
                guard validId(level, max: 32), seenEfforts.insert(level).inserted else { return false }
            }
        }
        return true
    }

    static func catalogState(_ response: TaskControlsResponse?, fresh: Bool, readFailed: Bool, now: Int64) -> CatalogState {
        if readFailed { return .requestFailed }
        guard let response else { return .loading }
        let expires = seconds(response.catalog?.validUntil)
        if expires > 0 && expires <= now { return .expired }
        if !fresh { return .rechecking }
        guard response.capabilities?.modelSettings == true else { return .notProvided }
        if response.catalogMalformed { return .invalid }
        guard let catalog = response.catalog else { return .notProvided }
        return validCatalog(catalog, now: now) ? .ready : .invalid
    }

    static func catalogMessage(_ state: CatalogState, response: TaskControlsResponse?) -> String {
        guard state == .notProvided else { return state.message }
        switch response?.catalogStatus ?? "" {
        case "archived": return "任务已归档，恢复后可选择模型"
        case "offline": return "电脑离线，连接后可重新获取模型"
        case "unsupported": return "电脑端暂不支持读取模型"
        case "pending": return "正在等待电脑返回可选模型"
        case "unavailable": return "电脑未能取得可选模型，可稍后重试"
        default: return state.message
        }
    }

    static func model(in catalog: SessionModelCatalog?, id: String) -> SessionModelOption? {
        guard !id.isEmpty else { return nil }
        return catalog?.models?.first { $0.id == id }
    }

    /// Inheritance can only be specialized to a model the catalog itself identifies.
    static func effortModel(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?, corrupt: Bool, now: Int64) -> SessionModelOption? {
        guard !corrupt, validCatalog(catalog, now: now), validSelection(selected, catalog: catalog, now: now) else { return nil }
        let target = selected == nil ? (catalog?.currentModel ?? "") : (selected?.model ?? "")
        return model(in: catalog, id: target)
    }

    static func advertisesFast(_ catalog: SessionModelCatalog?, model: SessionModelOption?) -> Bool {
        guard catalog?.provider == "codex", let tiers = model?.serviceTiers else { return false }
        return tiers == ["priority"]
    }

    /// Fast is a capability of the exact Codex model, not a reasoning effort.
    static func fastModel(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?, corrupt: Bool, now: Int64) -> SessionModelOption? {
        guard let resolved = effortModel(catalog, selected: selected, corrupt: corrupt, now: now),
              advertisesFast(catalog, model: resolved) else { return nil }
        return resolved
    }

    static func fastRequested(_ selected: SessionModelSelection?) -> Bool {
        selected?.serviceTier == "priority"
    }

    static func planRequested(_ selected: SessionModelSelection?) -> Bool {
        selected?.collaborationMode == "plan"
    }

    static func advertisesPlan(_ catalog: SessionModelCatalog?) -> Bool {
        guard catalog?.provider == "codex", let modes = catalog?.collaborationModes else { return false }
        return modes == ["plan"]
    }

    static func validOneTurnChoice(_ choice: OneTurnOptionChoice?) -> Bool {
        guard let choice else { return false }
        return validId(choice.model, max: 120) && validId(choice.catalogRevision, max: 128)
    }

    static func stageFast(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?, corrupt: Bool, now: Int64) -> OneTurnOptionChoice? {
        stageOneTurn(catalog, selected: selected, corrupt: corrupt, plan: false, now: now)
    }

    static func stagePlan(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?, corrupt: Bool, now: Int64) -> OneTurnOptionChoice? {
        stageOneTurn(catalog, selected: selected, corrupt: corrupt, plan: true, now: now)
    }

    private static func stageOneTurn(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?,
                                     corrupt: Bool, plan: Bool, now: Int64) -> OneTurnOptionChoice? {
        guard let catalog else { return nil }
        let resolved: SessionModelOption?
        if plan {
            resolved = advertisesPlan(catalog) ? effortModel(catalog, selected: selected, corrupt: corrupt, now: now) : nil
        } else {
            resolved = fastModel(catalog, selected: selected, corrupt: corrupt, now: now)
        }
        guard let resolved else { return nil }
        let choice = OneTurnOptionChoice(model: resolved.id, catalogRevision: catalog.catalogRevision)
        return oneTurnReply(catalog, selected: selected, pending: choice, plan: plan, now: now) == nil ? nil : choice
    }

    static func fastReply(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?,
                          pending: OneTurnOptionChoice?, now: Int64) -> SessionModelSelection? {
        oneTurnReply(catalog, selected: selected, pending: pending, plan: false, now: now)
    }

    static func planReply(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?,
                          pending: OneTurnOptionChoice?, now: Int64) -> SessionModelSelection? {
        oneTurnReply(catalog, selected: selected, pending: pending, plan: true, now: now)
    }

    private static func oneTurnReply(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?,
                                     pending: OneTurnOptionChoice?, plan: Bool, now: Int64) -> SessionModelSelection? {
        guard let catalog, let pending, validOneTurnChoice(pending) else { return nil }
        let resolved: SessionModelOption?
        if plan {
            resolved = advertisesPlan(catalog) ? effortModel(catalog, selected: selected, corrupt: false, now: now) : nil
        } else {
            resolved = fastModel(catalog, selected: selected, corrupt: false, now: now)
        }
        guard let resolved, resolved.id == pending.model, catalog.catalogRevision == pending.catalogRevision else { return nil }
        let rawEffort = selected == nil ? (catalog.currentEffort ?? "") : (selected?.effort ?? "")
        let result = SessionModelSelection(
            provider: "codex",
            catalogRevision: pending.catalogRevision,
            model: pending.model,
            effort: rawEffort.isEmpty ? nil : rawEffort,
            applyAt: "next_message",
            serviceTier: plan ? nil : "priority",
            collaborationMode: plan ? "plan" : nil
        )
        return validSelection(result, catalog: catalog, now: now) ? result : nil
    }

    static func oneTurnSettings(_ catalog: SessionModelCatalog?, selected: SessionModelSelection?,
                                fast: OneTurnOptionChoice?, plan: OneTurnOptionChoice?, now: Int64) -> SessionModelSelection? {
        guard let fast else { return planReply(catalog, selected: selected, pending: plan, now: now) }
        guard var speed = fastReply(catalog, selected: selected, pending: fast, now: now) else { return nil }
        guard let plan else { return speed }
        guard planReply(catalog, selected: selected, pending: plan, now: now) != nil else { return nil }
        speed.collaborationMode = "plan"
        return validSelection(speed, catalog: catalog, now: now) ? speed : nil
    }

    /// Restored retries retain their committed catalog revision; server validates freshness idempotently.
    static func selectionSnapshot(_ selected: SessionModelSelection?) -> Bool {
        guard let selected, !selected.hasInvalidFieldTypes,
              provider(selected.provider),
              validId(selected.catalogRevision, max: 128),
              validId(selected.model, max: 120),
              selected.effort == nil || validId(selected.effort, max: 32),
              selected.serviceTier == nil || (selected.provider == "codex" && fastRequested(selected)),
              selected.collaborationMode == nil || (selected.provider == "codex" && planRequested(selected)),
              selected.applyAt == "next_message" else { return false }
        return true
    }

    static func validSelection(_ selected: SessionModelSelection?, catalog: SessionModelCatalog?, now: Int64) -> Bool {
        guard let selected else { return true }
        guard selectionSnapshot(selected),
              let catalog, validCatalog(catalog, now: now),
              catalog.provider == selected.provider,
              catalog.catalogRevision == selected.catalogRevision,
              let resolved = model(in: catalog, id: selected.model) else { return false }
        if fastRequested(selected) && !advertisesFast(catalog, model: resolved) { return false }
        if planRequested(selected) && !advertisesPlan(catalog) { return false }
        guard let effort = selected.effort else { return true }
        return resolved.efforts?.contains(effort) == true
    }

    static func canSendReply(catalog: SessionModelCatalog?, state: CatalogState,
                             selection: SessionModelSelection?, fastNext: OneTurnOptionChoice?, planNext: OneTurnOptionChoice?,
                             selectionCorrupt: Bool, fastCorrupt: Bool, planCorrupt: Bool, now: Int64) -> Bool {
        guard !selectionCorrupt, !fastCorrupt, !planCorrupt else { return false }
        let ready = (state == .ready)
        guard selection == nil || (ready && validSelection(selection, catalog: catalog, now: now)) else { return false }
        guard (fastNext == nil && planNext == nil)
            || (ready && oneTurnSettings(catalog, selected: selection, fast: fastNext, plan: planNext, now: now) != nil) else { return false }
        return true
    }

    static func selectionForSend(catalog: SessionModelCatalog?, state: CatalogState,
                                 selection: SessionModelSelection?, fastNext: OneTurnOptionChoice?, planNext: OneTurnOptionChoice?,
                                 selectionCorrupt: Bool, fastCorrupt: Bool, planCorrupt: Bool, now: Int64) -> Result<SessionModelSelection?, MonitorError> {
        guard canSendReply(catalog: catalog, state: state, selection: selection, fastNext: fastNext, planNext: planNext,
                           selectionCorrupt: selectionCorrupt, fastCorrupt: fastCorrupt, planCorrupt: planCorrupt, now: now) else {
            return .failure(.service(0, "模型选项已变化，请重新选择或改为继承电脑设置", 0))
        }
        if fastNext == nil && planNext == nil {
            return .success(selection)
        }
        guard let combined = oneTurnSettings(catalog, selected: selection, fast: fastNext, plan: planNext, now: now) else {
            return .failure(.service(0, "下次回复的选项已变化，请重新选择", 0))
        }
        return .success(combined)
    }

    static func effortLabel(_ effort: String) -> String {
        switch effort {
        case "none": "关闭"
        case "minimal": "最低"
        case "low": "低"
        case "medium": "中"
        case "high": "高"
        case "xhigh": "极高"
        case "max": "最高"
        default: effort
        }
    }

    static func modelName(_ value: String) -> String {
        guard !value.isEmpty else { return "" }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        if let match = claudeRegex.firstMatch(in: value, range: range) {
            let family = titleCase(group(match, 1, in: value) ?? "")
            var base = "Claude \(family)"
            if let version = group(match, 2, in: value) {
                base += " \(version.replacingOccurrences(of: "-", with: "."))"
            }
            if let effort = group(match, 3, in: value) {
                return base + " (\(modelEffortSuffix(effort)))"
            }
            return base
        }
        if let match = earlyClaudeRegex.firstMatch(in: value, range: range),
           let version = group(match, 1, in: value), let family = group(match, 2, in: value) {
            return "Claude \(version.replacingOccurrences(of: "-", with: ".")) \(titleCase(family))"
        }
        if let match = geminiRegex.firstMatch(in: value, range: range),
           let rawModifiers = group(match, 2, in: value) {
            var label = "Gemini"
            if let version = group(match, 1, in: value) {
                label += " " + version.replacingOccurrences(of: "-", with: ".")
            }
            for word in rawModifiers.dropFirst().split(separator: "-") {
                label += " " + titleCase(String(word))
            }
            if let effort = group(match, 3, in: value) {
                label += " (\(modelEffortSuffix(effort)))"
            }
            return label
        }
        if let match = gptOssRegex.firstMatch(in: value, range: range),
           let size = group(match, 1, in: value) {
            let base = "GPT-OSS " + size.uppercased()
            if let effort = group(match, 2, in: value) {
                return base + " (\(modelEffortSuffix(effort)))"
            }
            return base
        }
        guard let match = gptRegex.firstMatch(in: value, range: range),
              let rawBase = group(match, 1, in: value) else { return value }
        let base = rawBase.lowercased()
        var label = base.replacingOccurrences(of: "gpt-", with: "GPT-")
        let modifiers = (group(match, 2, in: value) ?? "").lowercased()
        if !modifiers.isEmpty {
            for wordSlice in modifiers.dropFirst().split(separator: "-") {
                let word = String(wordSlice)
                if base.hasPrefix("o") {
                    label += "-" + word
                } else if modifiers.hasPrefix("-codex") || word == "spark" {
                    label += "-" + titleCase(word)
                } else if word == "mini" || word == "nano" || word == "pro" {
                    label += " " + word
                } else {
                    label += " " + titleCase(word)
                }
            }
        }
        return label
    }

    static func displayModelName(_ item: SessionModelOption) -> String {
        modelName(item.label.isEmpty ? item.id : item.label)
    }

    static func disambiguatedModelLabels(_ models: [SessionModelOption]) -> [String] {
        var labels = models.map(displayModelName)
        var seen: Set<String> = ["继承电脑设置"]
        var ambiguous = Set<String>()
        for label in labels {
            if !seen.insert(label).inserted { ambiguous.insert(label) }
        }
        for index in labels.indices where ambiguous.contains(labels[index]) {
            labels[index] += " · " + models[index].id
        }
        return labels
    }

    private static func group(_ match: NSTextCheckingResult, _ index: Int, in source: String) -> String? {
        guard index < match.numberOfRanges else { return nil }
        let nsRange = match.range(at: index)
        guard nsRange.location != NSNotFound, let swiftRange = Range(nsRange, in: source) else { return nil }
        return String(source[swiftRange])
    }

    private static func titleCase(_ token: String) -> String {
        guard let first = token.first else { return "" }
        return String(first).uppercased() + token.dropFirst().lowercased()
    }

    private static func modelEffortSuffix(_ effort: String) -> String {
        effort.lowercased() == "xhigh" ? "XHigh" : titleCase(effort)
    }

    static func terminal(_ value: SessionPendingRequest?) -> Bool {
        guard let state = value?.state else { return false }
        return state == "forwarded" || state == "acknowledged" || state == "expired" || state == "cancelled"
    }

    static func validRequest(_ value: SessionPendingRequest?) -> Bool {
        guard let value,
              validId(value.id, max: 128),
              validId(value.revision, max: 128),
              validId(value.turnId, max: 200),
              provider(value.provider),
              seconds(value.expiresAt) > 0,
              ["pending", "submitted", "forwarded", "acknowledged", "expired", "cancelled"].contains(value.state),
              value.kind == "approval" || value.kind == "question",
              validText(value.title, max: 240, required: true),
              let detail = value.detail, validText(detail, max: 4000, required: false),
              let operation = value.operation, validText(operation, max: 8000, required: false) else { return false }
        // Resolved receipts deliberately omit operation and question payloads.
        if terminal(value) { return true }
        if value.kind == "approval" {
            return validText(value.operation, max: 8000, required: true)
        }
        guard value.kind == "question",
              let questions = value.questions, !questions.isEmpty, questions.count <= 16 else { return false }
        var seenQuestions = Set<String>()
        for question in questions {
            guard validId(question.id, max: 80), seenQuestions.insert(question.id).inserted,
                  validText(question.text, max: 2000, required: true),
                  question.multiple != nil, let allowsText = question.allowsText,
                  let options = question.options, options.count <= 32,
                  !options.isEmpty || allowsText else { return false }
            var seenOptions = Set<String>()
            for option in options {
                guard validId(option.id, max: 80), seenOptions.insert(option.id).inserted,
                      validText(option.label, max: 240, required: true),
                      let desc = option.description, validText(desc, max: 1000, required: false) else { return false }
            }
        }
        return true
    }

    static func pending(_ request: SessionPendingRequest?, now: Int64) -> Bool {
        guard let request, validRequest(request) else { return false }
        return request.state == "pending" && seconds(request.expiresAt) > now
    }

    static func actionable(_ request: SessionPendingRequest?, capabilities: SessionCapabilities?, now: Int64) -> Bool {
        guard let request, pending(request, now: now), let capabilities else { return false }
        return request.kind == "approval" ? capabilities.approvals : capabilities.questions
    }

    static func validAnswers(_ request: SessionPendingRequest?, answers: [SessionAnswerPayload]?) -> Bool {
        guard let request, validRequest(request), request.kind == "question",
              let questions = request.questions, !questions.isEmpty,
              let answers, answers.count == questions.count else { return false }
        for (question, answer) in zip(questions, answers) {
            guard !answer.hasInvalidFieldTypes, question.id == answer.questionId,
                  let multiple = question.multiple, let allowsText = question.allowsText,
                  let options = question.options else { return false }
            if !multiple && answer.choices.count > 1 { return false }
            let allowed = Set(options.map(\.id))
            var seenChoices = Set<String>()
            for choice in answer.choices {
                guard allowed.contains(choice), seenChoices.insert(choice).inserted else { return false }
            }
            let hasText: Bool
            if let text = answer.text {
                guard allowsText, validText(text, max: 4000, required: true), answer.choices.isEmpty else { return false }
                hasText = true
            } else {
                hasText = false
            }
            if answer.choices.isEmpty && !hasText { return false }
        }
        return true
    }

    static func validDecision(_ request: SessionPendingRequest?, payload: SessionDecisionPayload?, taskId: String, now: Int64) -> Bool {
        guard let request, let payload,
              pending(request, now: now),
              payload.taskId == taskId,
              payload.requestId == request.id,
              payload.revision == request.revision,
              MonitorSecurity.matches(payload.decisionId, uuidPattern) else { return false }
        if request.kind == "approval" {
            return (payload.decision == "allow_once" || payload.decision == "deny") && payload.answers.isEmpty
        }
        return payload.decision == "answer" && validAnswers(request, answers: payload.answers)
    }

    /// All displayed operation and choice fields are bound, not only the upstream revision.
    static func fingerprint(_ request: SessionPendingRequest?) -> String {
        guard let request, validRequest(request) else { return "" }
        var out = ""
        func append(_ part: String) {
            out += "\(part.utf8.count):\(part)"
        }
        for field in [
            request.id, request.revision, request.turnId, request.provider,
            request.kind, request.title, request.detail ?? "", request.operation ?? ""
        ] {
            append(field)
        }
        append(String(seconds(request.expiresAt)))
        if request.kind == "question", let questions = request.questions {
            for question in questions {
                append(question.id)
                append(question.text)
                append(String(question.multiple == true))
                append(String(question.allowsText == true))
                for option in question.options ?? [] {
                    append(option.id)
                    append(option.label)
                    append(option.description ?? "")
                }
            }
        }
        return out
    }

    static func fingerprintDigest(_ request: SessionPendingRequest?) -> String {
        let raw = fingerprint(request)
        return raw.isEmpty ? "" : MonitorSecurity.hash(Data(raw.utf8))
    }

    static func stateLabel(_ request: SessionPendingRequest?, now: Int64) -> String {
        guard let request, validRequest(request) else { return "请求信息不完整，请到电脑查看" }
        switch request.state {
        case "acknowledged": return "电脑已收到；执行结果请看任务状态"
        case "forwarded": return "已交给电脑，等待任务结果"
        case "submitted": return "已发送，等待电脑确认"
        case "cancelled": return "请求已取消"
        case "expired": return "请求已过期"
        default:
            if seconds(request.expiresAt) <= now { return "请求已过期" }
            return request.kind == "approval" ? "仅批准这一次操作" : "请选择后发送"
        }
    }

    static func decisionSummary(_ request: SessionPendingRequest, payload: SessionDecisionPayload) -> String {
        switch payload.decision {
        case "allow_once": return "原决定：仅允许这一次"
        case "deny": return "原决定：拒绝"
        default:
            var lines = ["原回答"]
            let questions = request.questions ?? []
            for (question, answer) in zip(questions, payload.answers) {
                let valueText: String
                if let text = answer.text, !text.isEmpty {
                    valueText = text
                } else {
                    let options = question.options ?? []
                    let labels = answer.choices.compactMap { choiceId in
                        options.first(where: { $0.id == choiceId })?.label
                    }
                    valueText = labels.joined(separator: "、")
                }
                lines.append("\(question.text)：\(valueText)")
            }
            return lines.joined(separator: "\n")
        }
    }

    static func validatePersisted(_ value: PersistedSessionControls, taskId: String) -> PersistedValidation {
        let selectionCorrupt = value.selection.map { !selectionSnapshot($0) } ?? false
        let fastCorrupt = value.fastNext.map { !validOneTurnChoice($0) } ?? false
        let planCorrupt = value.planNext.map { !validOneTurnChoice($0) } ?? false
        var recordsCorrupt = value.decisions.count > 64
        if !recordsCorrupt {
            for (id, record) in value.decisions {
                if !MonitorSecurity.matches(record.fingerprint, sha256Pattern)
                    || record.payload.requestId != id
                    || record.payload.taskId != taskId
                    || !MonitorSecurity.matches(record.payload.decisionId, uuidPattern) {
                    recordsCorrupt = true
                    break
                }
            }
        }
        return PersistedValidation(
            selectionCorrupt: selectionCorrupt,
            fastCorrupt: fastCorrupt,
            planCorrupt: planCorrupt,
            recordsCorrupt: recordsCorrupt
        )
    }
}
