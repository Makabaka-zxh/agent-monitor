import XCTest
@testable import Monitor

final class SessionControlPolicyTests: XCTestCase {
    private let testOrigin = "https://monitor.example.com"
    private let now: Int64 = 1000
    private let validUUID = "00112233-4455-4677-8899-aabbccddeeff"

    private func decodeWire<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(type, from: Data(json.utf8))
    }

    private func sampleCatalog(provider: String = "codex") -> SessionModelCatalog {
        SessionModelCatalog(
            provider: provider,
            catalogRevision: "catalog-v1",
            validUntil: 1100.5,
            models: [
                SessionModelOption(id: "source-model", label: "Source model", efforts: ["low", "high"])
            ]
        )
    }

    private func sampleSelection(provider: String = "codex") -> SessionModelSelection {
        SessionModelSelection(
            provider: provider,
            catalogRevision: "catalog-v1",
            model: "source-model",
            effort: "high",
            applyAt: "next_message"
        )
    }

    private func sampleApproval(provider: String = "claude") -> SessionPendingRequest {
        SessionPendingRequest(
            id: "request-v1",
            revision: "revision-v1",
            turnId: "turn-v1",
            provider: provider,
            kind: "approval",
            title: "Synthetic action",
            detail: "Synthetic impact",
            operation: "create a synthetic file",
            questions: [],
            expiresAt: 1100.5,
            state: "pending"
        )
    }

    private func sampleQuestion() -> SessionPendingRequest {
        SessionPendingRequest(
            id: "request-v1",
            revision: "revision-v1",
            turnId: "turn-v1",
            provider: "antigravity",
            kind: "question",
            title: "Synthetic question",
            detail: "Choose one",
            operation: "",
            questions: [
                SessionQuestion(
                    id: "q1",
                    text: "Synthetic choice",
                    multiple: false,
                    allowsText: true,
                    options: [
                        SessionChoice(id: "a", label: "A", description: "First synthetic option"),
                        SessionChoice(id: "b", label: "B", description: "Second synthetic option")
                    ]
                )
            ],
            expiresAt: 1100.5,
            state: "pending"
        )
    }

    func testControlsEndpointsEnforceOriginAndTaskBounds() throws {
        let identity = "computer:antigravity:/workspace?x=1&decision_id=evil#frag"
        let controlsURL = try Endpoint.controls(identity).url(origin: testOrigin)
        let components = try XCTUnwrap(URLComponents(url: controlsURL, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "monitor.example.com")
        XCTAssertEqual(components.path, "/api/native/tasks/controls")
        XCTAssertEqual(components.queryItems, [URLQueryItem(name: "task_id", value: identity)])
        XCTAssertNil(components.fragment)
        XCTAssertThrowsError(try Endpoint.controls("").url(origin: testOrigin))
        XCTAssertThrowsError(try Endpoint.controls(String(repeating: "x", count: 501)).url(origin: testOrigin))

        let decisionURL = try Endpoint.controlDecision.url(origin: testOrigin)
        XCTAssertEqual(decisionURL.absoluteString, testOrigin + "/api/native/tasks/controls/decision")
        XCTAssertNil(URLComponents(url: decisionURL, resolvingAgainstBaseURL: false)?.query)
    }

    func testCatalogStatesAndDiagnosticLabels() throws {
        XCTAssertEqual(SessionControlPolicy.catalogState(nil, fresh: false, readFailed: false, now: now), .loading)
        XCTAssertEqual(SessionControlPolicy.catalogState(nil, fresh: false, readFailed: true, now: now), .requestFailed)

        var response = TaskControlsResponse(
            catalog: nil,
            capabilities: SessionCapabilities(questions: false, approvals: false, modelSettings: true)
        )
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: true, readFailed: false, now: now), .notProvided)

        response.catalog = sampleCatalog()
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: true, readFailed: false, now: now), .ready)
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: false, readFailed: false, now: now), .rechecking)
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: false, readFailed: true, now: now), .requestFailed)
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: true, readFailed: false, now: 1100), .expired)

        response.capabilities?.modelSettings = false
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: true, readFailed: false, now: now), .notProvided)

        response.capabilities?.modelSettings = true
        response.catalog?.models = []
        XCTAssertEqual(SessionControlPolicy.catalogState(response, fresh: true, readFailed: false, now: now), .invalid)

        let stringDeadline = try decodeWire(
            TaskControlsResponse.self,
            #"{"catalog":{"provider":"codex","catalog_revision":"v1","valid_until":"1100","models":[{"id":"m","label":"M","efforts":[]}]},"capabilities":{"model_settings":true}}"#
        )
        XCTAssertEqual(SessionControlPolicy.catalogState(stringDeadline, fresh: true, readFailed: false, now: now), .invalid)

        for state in SessionControlPolicy.CatalogState.allCases {
            XCTAssertTrue(state == .ready || !state.message.isEmpty)
            XCTAssertFalse(state.message.hasSuffix("。"))
        }

        var distinctMessages = Set<String>()
        for status in ["pending", "unavailable", "unsupported", "offline", "archived"] {
            let statusResponse = TaskControlsResponse(catalogStatus: status)
            let text = SessionControlPolicy.catalogMessage(.notProvided, response: statusResponse)
            XCTAssertTrue(distinctMessages.insert(text).inserted)
            XCTAssertFalse(text.hasSuffix("。"))
            XCTAssertEqual(
                SessionControlPolicy.catalogMessage(.requestFailed, response: statusResponse),
                SessionControlPolicy.CatalogState.requestFailed.message
            )
        }
        XCTAssertEqual(
            SessionControlPolicy.catalogMessage(.notProvided, response: TaskControlsResponse(catalogStatus: "unexpected")),
            SessionControlPolicy.CatalogState.notProvided.message
        )
    }

    func testAntigravityClaudeAndCodexProviderBoundaries() throws {
        XCTAssertTrue(SessionControlPolicy.provider("codex"))
        XCTAssertTrue(SessionControlPolicy.provider("claude"))
        XCTAssertTrue(SessionControlPolicy.provider("antigravity"))
        XCTAssertFalse(SessionControlPolicy.provider("unknown"))

        let agCatalog = sampleCatalog(provider: "antigravity")
        XCTAssertTrue(SessionControlPolicy.validCatalog(agCatalog, now: now))
        XCTAssertFalse(SessionControlPolicy.advertisesPlan(agCatalog))
        XCTAssertNil(SessionControlPolicy.fastModel(agCatalog, selected: nil, corrupt: false, now: now))

        var agSelection = sampleSelection(provider: "antigravity")
        XCTAssertTrue(SessionControlPolicy.validSelection(agSelection, catalog: agCatalog, now: now))
        agSelection.serviceTier = "priority"
        XCTAssertFalse(SessionControlPolicy.selectionSnapshot(agSelection))

        let agApproval = sampleApproval(provider: "antigravity")
        XCTAssertTrue(SessionControlPolicy.validRequest(agApproval))

        let booleanTier = try decodeWire(
            SessionModelSelection.self,
            #"{"provider":"codex","catalog_revision":"catalog-v1","model":"source-model","effort":"high","apply_at":"next_message","service_tier":true}"#
        )
        XCTAssertFalse(SessionControlPolicy.selectionSnapshot(booleanTier))

        let booleanMode = try decodeWire(
            SessionModelSelection.self,
            #"{"provider":"codex","catalog_revision":"catalog-v1","model":"source-model","effort":"high","apply_at":"next_message","collaboration_mode":true}"#
        )
        XCTAssertFalse(SessionControlPolicy.selectionSnapshot(booleanMode))
    }

    func testCodexFastAndPlanOneTurnStagingAndWireShape() throws {
        var base = sampleCatalog()
        base.currentModel = "source-model"
        base.currentEffort = "low"
        XCTAssertNil(SessionControlPolicy.fastModel(base, selected: nil, corrupt: false, now: now))

        base.models?[0].serviceTiers = ["fast"]
        XCTAssertNil(SessionControlPolicy.fastModel(base, selected: nil, corrupt: false, now: now))

        base.models?[0].serviceTiers = ["priority", "priority"]
        XCTAssertNil(SessionControlPolicy.fastModel(base, selected: nil, corrupt: false, now: now))

        base.models?[0].serviceTiers = ["priority"]
        XCTAssertNotNil(SessionControlPolicy.fastModel(base, selected: nil, corrupt: false, now: now))

        let fastChoice = try XCTUnwrap(SessionControlPolicy.stageFast(base, selected: nil, corrupt: false, now: now))
        XCTAssertEqual(fastChoice.model, "source-model")

        base.currentEffort = "unavailable-effort"
        XCTAssertNil(SessionControlPolicy.stageFast(base, selected: nil, corrupt: false, now: now))
        XCTAssertNotNil(SessionControlPolicy.stageFast(base, selected: sampleSelection(), corrupt: false, now: now))

        base.currentEffort = "low"
        XCTAssertNil(SessionControlPolicy.stagePlan(base, selected: nil, corrupt: false, now: now))
        base.collaborationModes = ["plan"]
        let planChoice = try XCTUnwrap(SessionControlPolicy.stagePlan(base, selected: nil, corrupt: false, now: now))

        let selected = sampleSelection()
        let combined = try XCTUnwrap(SessionControlPolicy.oneTurnSettings(
            base, selected: selected, fast: fastChoice, plan: planChoice, now: now
        ))
        XCTAssertTrue(SessionControlPolicy.fastRequested(combined))
        XCTAssertTrue(SessionControlPolicy.planRequested(combined))
        XCTAssertEqual(combined.effort, "high")
        XCTAssertNil(selected.serviceTier)
        XCTAssertNil(selected.collaborationMode)
        XCTAssertTrue(SessionControlPolicy.selectionSnapshot(combined))

        let stalePlan = OneTurnOptionChoice(model: "source-model", catalogRevision: "obsolete")
        XCTAssertNil(SessionControlPolicy.oneTurnSettings(
            base, selected: selected, fast: fastChoice, plan: stalePlan, now: now
        ))

        let defaultEffortWire = SessionModelSelection(
            provider: "antigravity", catalogRevision: "v1", model: "gemini-3.1-pro-high", effort: nil
        ).wireDictionary
        XCTAssertNil(defaultEffortWire["service_tier"])
        XCTAssertNil(defaultEffortWire["collaboration_mode"])
        XCTAssertTrue(defaultEffortWire["effort"] is NSNull)
        XCTAssertTrue(JSONSerialization.isValidJSONObject(defaultEffortWire))
    }

    func testModelNamingAndAmbiguousLabelDisambiguation() {
        XCTAssertEqual(SessionControlPolicy.modelName("claude-sonnet-4-6-20260514"), "Claude Sonnet 4.6")
        XCTAssertEqual(SessionControlPolicy.modelName("claude-3-5-sonnet-latest"), "Claude 3.5 Sonnet")
        XCTAssertEqual(SessionControlPolicy.modelName("gemini-3.8-flash-low"), "Gemini 3.8 Flash (Low)")
        XCTAssertEqual(SessionControlPolicy.modelName("Gemini 3.1 Pro (High)"), "Gemini 3.1 Pro (High)")
        XCTAssertEqual(SessionControlPolicy.modelName("gpt-5.3-codex"), "GPT-5.3-Codex")
        XCTAssertEqual(SessionControlPolicy.modelName("gpt-5.4-mini"), "GPT-5.4 mini")
        XCTAssertEqual(SessionControlPolicy.modelName("gpt-oss-120b-xhigh"), "GPT-OSS 120B (XHigh)")
        XCTAssertEqual(SessionControlPolicy.effortLabel("xhigh"), "极高")
        XCTAssertEqual(SessionControlPolicy.effortLabel("custom"), "custom")

        let ambiguous = [
            SessionModelOption(id: "claude-sonnet-4-6-20260501", label: "claude-sonnet-4-6-20260501", efforts: []),
            SessionModelOption(id: "claude-sonnet-4-6-latest", label: "claude-sonnet-4-6-latest", efforts: []),
            SessionModelOption(id: "custom-inherit-name", label: "继承电脑设置", efforts: [])
        ]
        let labels = SessionControlPolicy.disambiguatedModelLabels(ambiguous)
        XCTAssertEqual(labels[0], "Claude Sonnet 4.6 · claude-sonnet-4-6-20260501")
        XCTAssertEqual(labels[1], "Claude Sonnet 4.6 · claude-sonnet-4-6-latest")
        XCTAssertEqual(labels[2], "继承电脑设置 · custom-inherit-name")
    }

    func testApprovalsQuestionsFingerprintsAndTerminalReceipts() throws {
        var approval = sampleApproval()
        XCTAssertTrue(SessionControlPolicy.validRequest(approval))
        approval.operation = ""
        XCTAssertFalse(SessionControlPolicy.validRequest(approval))
        approval.operation = "safe\u{202E}evil"
        XCTAssertFalse(SessionControlPolicy.validRequest(approval))
        approval.operation = "safe\u{0007}bell"
        XCTAssertFalse(SessionControlPolicy.validRequest(approval))

        let cleanApproval = sampleApproval()
        let caps = SessionCapabilities(questions: true, approvals: true, modelSettings: false)
        XCTAssertTrue(SessionControlPolicy.actionable(cleanApproval, capabilities: caps, now: now))
        XCTAssertFalse(SessionControlPolicy.actionable(cleanApproval, capabilities: SessionCapabilities(), now: now))

        for terminalState in ["forwarded", "acknowledged", "expired", "cancelled"] {
            let receipt = SessionPendingRequest(
                id: "request-v1", revision: "revision-v1", turnId: "turn-v1",
                provider: "antigravity", kind: "approval", title: "请求已结束",
                detail: "", operation: "", questions: [], expiresAt: 1100.5, state: terminalState
            )
            XCTAssertTrue(SessionControlPolicy.validRequest(receipt))
            XCTAssertFalse(SessionControlPolicy.stateLabel(receipt, now: now).contains("不完整"))
            let allowPayload = SessionDecisionPayload(
                taskId: "task-1", requestId: "request-v1", revision: "revision-v1",
                decisionId: validUUID, decision: "allow_once"
            )
            XCTAssertFalse(SessionControlPolicy.validDecision(receipt, payload: allowPayload, taskId: "task-1", now: now))
        }

        let allowOnce = SessionDecisionPayload(
            taskId: "task-1", requestId: "request-v1", revision: "revision-v1",
            decisionId: validUUID, decision: "allow_once"
        )
        let allowAlways = SessionDecisionPayload(
            taskId: "task-1", requestId: "request-v1", revision: "revision-v1",
            decisionId: validUUID, decision: "allow_always"
        )
        XCTAssertTrue(SessionControlPolicy.validDecision(cleanApproval, payload: allowOnce, taskId: "task-1", now: now))
        XCTAssertFalse(SessionControlPolicy.validDecision(cleanApproval, payload: allowAlways, taskId: "task-1", now: now))
        XCTAssertFalse(SessionControlPolicy.validDecision(cleanApproval, payload: allowOnce, taskId: "other-task", now: now))

        let question = sampleQuestion()
        XCTAssertTrue(SessionControlPolicy.validRequest(question))
        XCTAssertFalse(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1")]))
        XCTAssertTrue(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", choices: ["a"])]))
        XCTAssertFalse(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", choices: ["a", "b"])]))
        XCTAssertTrue(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", text: "Custom answer")]))
        XCTAssertFalse(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", choices: ["a"], text: "Both")]))
        XCTAssertFalse(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", text: "   ")]))
        XCTAssertTrue(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", text: String(repeating: "x", count: 4000))]))
        XCTAssertFalse(SessionControlPolicy.validAnswers(question, answers: [SessionAnswerPayload(questionId: "q1", text: String(repeating: "x", count: 4001))]))

        var noTextQuestion = question
        noTextQuestion.questions?[0].allowsText = false
        XCTAssertFalse(SessionControlPolicy.validAnswers(noTextQuestion, answers: [SessionAnswerPayload(questionId: "q1", text: "Custom")]))

        let originalDigest = SessionControlPolicy.fingerprintDigest(cleanApproval)
        var submittedCopy = cleanApproval
        submittedCopy.state = "submitted"
        XCTAssertEqual(originalDigest, SessionControlPolicy.fingerprintDigest(submittedCopy))

        var mutatedOperation = cleanApproval
        mutatedOperation.operation = "rm -rf build"
        XCTAssertNotEqual(originalDigest, SessionControlPolicy.fingerprintDigest(mutatedOperation))
        XCTAssertNotEqual(SessionControlPolicy.fingerprintDigest(question), SessionControlPolicy.fingerprintDigest(noTextQuestion))
    }

    func testPendingReplyAndPersistedLedgerCompatibility() throws {
        let legacyJSON = #"{"id":"00112233-4455-4677-8899-aabbccddeeff","taskId":"task-1","text":"继续","createdAt":0,"state":"not_found"}"#
        var legacy = try JSONDecoder().decode(PendingReply.self, from: Data(legacyJSON.utf8))
        legacy = PendingReply(id: legacy.id, taskId: legacy.taskId, text: legacy.text,
                              createdAt: Date(), state: legacy.state,
                              submittedSelection: legacy.submittedSelection,
                              submittedSelectionInvalid: legacy.submittedSelectionInvalid)
        XCTAssertNil(legacy.submittedSelection)
        XCTAssertFalse(legacy.submittedSelectionInvalid)
        XCTAssertTrue(legacy.mayRetry)

        let withSelection = PendingReply(
            id: validUUID, taskId: "task-1", text: "继续", createdAt: Date(),
            state: "not_found", submittedSelection: sampleSelection(provider: "antigravity")
        )
        let encoded = try JSONEncoder().encode(withSelection)
        let roundTripped = try JSONDecoder().decode(PendingReply.self, from: encoded)
        XCTAssertEqual(roundTripped.submittedSelection, sampleSelection(provider: "antigravity"))
        XCTAssertTrue(roundTripped.mayRetry)

        let corruptJSON = #"{"id":"00112233-4455-4677-8899-aabbccddeeff","taskId":"task-1","text":"继续","createdAt":999999999,"state":"not_found","submittedSelection":{"provider":"antigravity","catalogRevision":"v1","model":"m","applyAt":"next_message","serviceTier":"priority"}}"#
        let corrupt = try JSONDecoder().decode(PendingReply.self, from: Data(corruptJSON.utf8))
        XCTAssertTrue(corrupt.submittedSelectionInvalid)
        XCTAssertFalse(corrupt.mayRetry)

        let validDigest = SessionControlPolicy.fingerprintDigest(sampleApproval())
        let validRecord = StoredDecisionRecord(
            fingerprint: validDigest,
            payload: SessionDecisionPayload(
                taskId: "task-1", requestId: "request-v1", revision: "revision-v1",
                decisionId: validUUID, decision: "deny"
            )
        )
        let checkOK = SessionControlPolicy.validatePersisted(
            PersistedSessionControls(selection: sampleSelection(), decisions: ["request-v1": validRecord]),
            taskId: "task-1"
        )
        XCTAssertEqual(checkOK, .init(selectionCorrupt: false, fastCorrupt: false, planCorrupt: false, recordsCorrupt: false))

        let checkWrongTask = SessionControlPolicy.validatePersisted(
            PersistedSessionControls(decisions: ["request-v1": validRecord]),
            taskId: "other-task"
        )
        XCTAssertTrue(checkWrongTask.recordsCorrupt)
    }
}
