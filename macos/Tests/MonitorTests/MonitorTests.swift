import XCTest
@testable import Monitor

final class MonitorTests: XCTestCase {
    private let testOrigin = "https://monitor.example.com"
    func testUsageUsesTheExistingAuthenticatedOriginWithoutQuery() throws {
        let url = try Endpoint.usage.url(origin: testOrigin)
        XCTAssertEqual(url.absoluteString, testOrigin + "/api/native/usage")
        XCTAssertNil(URLComponents(url: url, resolvingAgainstBaseURL: false)?.query)
    }
    private func decode<T: Decodable>(_ type: T.Type, _ text: String) throws -> T {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(type, from: Data(text.utf8))
    }
    func testTaskIdentityCannotChangeOriginOrRoute() throws {
        let identity = "computer:codex:/a?x=1&result_id=evil#任务"
        let url = try Endpoint.result(identity).url(origin: testOrigin)
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "monitor.example.com")
        XCTAssertEqual(components.path, "/api/native/tasks/result")
        XCTAssertEqual(components.queryItems, [URLQueryItem(name: "task_id", value: identity)])
        XCTAssertNil(components.fragment)
        XCTAssertNil(components.user)
        XCTAssertThrowsError(try Endpoint.result("").url(origin: testOrigin))
        XCTAssertThrowsError(try Endpoint.result(String(repeating: "x", count: 501)).url(origin: testOrigin))
    }
    func testOnlyBoundedCanonicalIdentitiesReachPathAndDownloadQueries() throws {
        XCTAssertThrowsError(try Endpoint.pairingPoll("../session").url(origin: testOrigin))
        XCTAssertThrowsError(try Endpoint.pairingPoll(String(repeating: "a", count: 31)).url(origin: testOrigin))
        XCTAssertNoThrow(try Endpoint.pairingPoll(String(repeating: "a", count: 32)).url(origin: testOrigin))
        XCTAssertThrowsError(try Endpoint.resultTXT("task", "../../secret").url(origin: testOrigin))
        let hash = String(repeating: "a", count: 64)
        XCTAssertNoThrow(try Endpoint.file("task:/with:punc", hash, hash).url(origin: testOrigin))
        XCTAssertThrowsError(try Endpoint.file("task", hash, String(repeating: "A", count: 64)).url(origin: testOrigin))
        XCTAssertThrowsError(try Endpoint.reply("task", "bad").url(origin: testOrigin))
        XCTAssertNoThrow(try Endpoint.reply("task", "12345678-1234-1234-1234-123456789abc").url(origin: testOrigin))
    }
    func testServerWorkbenchShapeAndStatusFiltering() throws {
        let fixture = #"{"devices":[{"id":"device","name":"Mac mini","platform":"Darwin","online":true,"local":false,"last_seen":"2026-09-18T12:00:00+00:00","sources":[]}],"tasks":[{"id":"device:codex:abc","title":"任务","tool":"codex","status":"running","device_id":"device","device_name":"Mac mini","updated_at":"2026-09-18T12:00:00+00:00","final_result_id":"abc","archived":false,"stale":false}],"preferences":{"sync_output":false,"tool_filter":"claude"},"generated_at":"2026-09-18T12:00:00Z"}"#
        let value = try decode(Workbench.self, fixture)
        let task = try XCTUnwrap(value.tasks.first)
        XCTAssertEqual(task.deviceId, "device")
        XCTAssertEqual(task.finalResultId, "abc")
        XCTAssertEqual(value.preferences.toolFilter, "claude")
        XCTAssertTrue(task.matches(tool: .codex, scope: .active, fresh: true))
        XCTAssertFalse(task.matches(tool: .claude, scope: .all, fresh: true))
        XCTAssertFalse(task.matches(tool: .codex, scope: .active, fresh: false))
        XCTAssertTrue(task.matches(tool: .codex, scope: .all, fresh: false))
        var archived = task
        archived.archived = true
        XCTAssertFalse(archived.matches(tool: .all, scope: .all, fresh: true))
        XCTAssertTrue(archived.matches(tool: .codex, scope: .archived, fresh: false))
    }
    func testAntigravityTaskSupportsFilteringAndReply() throws {
        let task = try decode(MonitorTask.self, #"{"id":"device:antigravity:1","title":"Build","tool":"antigravity","status":"completed","device_id":"device","archived":false,"stale":false}"#)
        XCTAssertEqual(task.brand, "Antigravity")
        XCTAssertTrue(task.matches(tool: .antigravity, scope: .all, fresh: true))
        XCTAssertFalse(task.matches(tool: .codex, scope: .all, fresh: true))
        XCTAssertTrue(task.supportsReply)
        let unknown = try decode(MonitorTask.self, #"{"id":"device:other:1","title":"Build","tool":"other","status":"completed","device_id":"device","archived":false,"stale":false}"#)
        XCTAssertFalse(unknown.supportsReply)
    }
    func testResultAndReplyProtocolNames() throws {
        let result = try decode(TaskResult.self, #"{"available":true,"text":"完成","result_id":"abc","txt_size":6,"txt_sha256":"hash","files":[{"id":"f","name":"报告.txt","size":6,"sha256":"hash","ready":false}]}"#)
        XCTAssertEqual(result.txtSize, 6)
        XCTAssertEqual(result.txtSha256, "hash")
        XCTAssertEqual(result.files?.first?.ready, false)
        let state = try decode(ReplyState.self, #"{"available":false,"reason":"忙碌","latest":{"id":"123","state":"running","reason":"正在回复","delivery_ms":700}}"#)
        XCTAssertEqual(state.latest?.deliveryMs, 700)
        let notFound = try decode(ReplyState.self, #"{"found":false,"state":"not_found"}"#)
        XCTAssertEqual(notFound.found, false)
        XCTAssertNil(notFound.id)
    }
    func testRepliesNeverBecomeAutomaticallyResendable() {
        let now = Date()
        var record = PendingReply(id: "id", taskId: "task", text: "继续", createdAt: now, state: "uncertain")
        XCTAssertTrue(record.unresolved)
        XCTAssertFalse(record.mayRetry)
        for state in ["queued", "dispatching", "running"] { record.state = state; XCTAssertFalse(record.mayRetry) }
        record.state = "not_found"
        XCTAssertTrue(record.mayRetry)
        let expired = PendingReply(id: "id", taskId: "task", text: "继续", createdAt: now.addingTimeInterval(-90000), state: "not_found")
        XCTAssertFalse(expired.mayRetry)
        for state in ["succeeded", "failed", "blocked"] { record.state = state; XCTAssertFalse(record.unresolved); XCTAssertFalse(record.mayRetry) }
        record.state = "uncertain"
        XCTAssertTrue(record.unresolved)
    }
    func testPKCEAndSafeDownloads() throws {
        XCTAssertEqual(MonitorSecurity.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        XCTAssertEqual(MonitorSecurity.hash(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(MonitorSecurity.safeName("../../secret\n.txt"), "_.._secret_.txt")
        XCTAssertEqual(MonitorSecurity.safeName("..."), "附件")
        XCTAssertNotNil(MonitorDate.parse("2026-09-18T12:34:56.123456+00:00"))
        XCTAssertNotNil(MonitorDate.parse("2026-09-18T12:34:56Z"))
        let verifier = try MonitorSecurity.verifier()
        XCTAssertTrue(MonitorSecurity.matches(verifier, "[A-Za-z0-9_-]{43}"))
    }
    func testUnifiedQRPairingEndpointsAndRendering() throws {
        let startURL = try Endpoint.qrPairingStart.url(origin: testOrigin)
        XCTAssertEqual(startURL.absoluteString, testOrigin + "/api/agent/pairing/start")
        let validId = String(repeating: "b", count: 32)
        let pollURL = try Endpoint.qrPairingPoll(validId).url(origin: testOrigin)
        XCTAssertEqual(pollURL.absoluteString, testOrigin + "/api/agent/pairing/\(validId)/poll")
        XCTAssertThrowsError(try Endpoint.qrPairingPoll("../evil").url(origin: testOrigin))
        let start = try decode(QRPairingStartResponse.self, #"{"request_id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","poll_secret":"cccccccccccccccccccccccccccccccc","verification_url":"https://monitor.example.com/#/pairing-confirm/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","expires_at":"2026-09-18T12:00:00Z","interval":3,"include_collector":true,"include_reader":true}"#)
        XCTAssertEqual(start.includeCollector, true)
        XCTAssertEqual(start.includeReader, true)
        let poll = try decode(QRPairingPollResponse.self, #"{"status":"approved","reader_token":"nrd_ddddddddddddddddddddddddddddddddddddddddddd","reader_expires_at":"2026-10-18T12:00:00Z","reader_mode":"full_app","collector_code":"AABBCCDDEEFF"}"#)
        XCTAssertEqual(poll.readerMode, "full_app")
        XCTAssertEqual(poll.collectorCode, "AABBCCDDEEFF")
        XCTAssertNotNil(QRCodeRenderer.image(for: start.verificationUrl))
        XCTAssertNil(QRCodeRenderer.image(for: ""))
    }
    func testPendingInteractionKindSurfacesQuestionAndApprovalAcrossRunningOrIdleTasks() throws {
        let questionTask = try decode(MonitorTask.self, #"{"id":"device:claude:q1","title":"确认迁移策略","tool":"claude","status":"running","pending_interaction_kind":"question","device_id":"device","archived":false,"stale":false}"#)
        XCTAssertEqual(questionTask.effectivePendingKind, "question")
        XCTAssertEqual(questionTask.effectiveStatus, "waiting")
        XCTAssertEqual(questionTask.statusTitle, "等待回答")
        XCTAssertTrue(questionTask.matches(tool: .all, scope: .active, fresh: true))

        let approvalTask = try decode(MonitorTask.self, #"{"id":"device:codex:a1","title":"执行脚本","tool":"codex","status":"idle","pending_interaction_kind":"approval","device_id":"device","archived":false,"stale":false}"#)
        XCTAssertEqual(approvalTask.effectivePendingKind, "approval")
        XCTAssertEqual(approvalTask.effectiveStatus, "waiting")
        XCTAssertEqual(approvalTask.statusTitle, "等待批准")
        XCTAssertTrue(approvalTask.matches(tool: .all, scope: .active, fresh: true))
    }

    func testNewTaskEndpointAndDevPreviewPolicyValidation() throws {
        let startTaskURL = try Endpoint.startTask.url(origin: testOrigin)
        XCTAssertEqual(startTaskURL.absoluteString, testOrigin + "/api/native/tasks/start")

        let replyWithCreated = try decode(ReplyState.self, #"{"id":"req-1","state":"running","created_task_id":"dev-1:codex:11111111-2222-3333-4444-555555555555"}"#)
        XCTAssertEqual(replyWithCreated.createdTaskId, "dev-1:codex:11111111-2222-3333-4444-555555555555")

        XCTAssertEqual(DevPreviewPolicy.validNetworkHost("DevBox.Example-Tailnet.ts.net"), "devbox.example-tailnet.ts.net")
        XCTAssertEqual(DevPreviewPolicy.validNetworkHost("100.88.12.34"), "100.88.12.34")
        XCTAssertEqual(DevPreviewPolicy.validNetworkHost("192.168.1.25"), "192.168.1.25")
        XCTAssertNil(DevPreviewPolicy.validNetworkHost("8.8.8.8"))
        XCTAssertNil(DevPreviewPolicy.validNetworkHost("127.0.0.1"))
        XCTAssertNil(DevPreviewPolicy.validNetworkHost("evil.com"))

        XCTAssertNotNil(DevPreviewPolicy.validPreviewURL("http://127.0.0.1:5173/", allowLoopback: true))
        XCTAssertNil(DevPreviewPolicy.validPreviewURL("http://127.0.0.1:5173/", allowLoopback: false))
        XCTAssertNotNil(DevPreviewPolicy.validPreviewURL("http://100.90.1.2:3000/", allowLoopback: false))
        XCTAssertNil(DevPreviewPolicy.validPreviewURL("https://evil.com:3000/", allowLoopback: true))

        let projId = "0123456789abcdef01234567"
        let bench = try decode(Workbench.self, """
        {"devices":[{"id":"11111111-2222-3333-4444-555555555555","name":"Remote PC","platform":"Windows","online":true,"local":false,
          "supported_tools":["claude","codex"],
          "projects":[{"id":"\(projId)","name":"web-app","tools":["codex"]},{"id":"invalid","name":"bad"}],
          "preview_hosts":["100.101.2.3","8.8.8.8"],
          "dev_ports":[{"port":5173,"bind_scope":"network","process":"node","project_id":"\(projId)"},
                       {"port":3000,"bind_scope":"loopback","process":"bun","project_id":""}]}],
         "tasks":[{"id":"t1","title":"Web","tool":"codex","status":"running","device_id":"11111111-2222-3333-4444-555555555555","project_id":"\(projId)","archived":false,"stale":false}],
         "preferences":{"sync_output":true}}
        """)
        let computer = try XCTUnwrap(bench.devices.first)
        let task = try XCTUnwrap(bench.tasks.first)
        XCTAssertEqual(DevPreviewPolicy.supportedTools(for: computer), ["codex", "claude"])
        XCTAssertEqual(DevPreviewPolicy.verifiedProjects(for: computer).map(\.id), [projId])

        let matched = DevPreviewPolicy.items(for: task, computer: computer, fresh: true)
        XCTAssertEqual(matched.count, 1)
        XCTAssertTrue(matched[0].matchedProject)
        XCTAssertEqual(matched[0].openURL, "http://100.101.2.3:5173/")

        let allItems = DevPreviewPolicy.items(for: nil, computer: computer, fresh: true)
        XCTAssertEqual(allItems.count, 2)
        XCTAssertNil(allItems[1].openURL)
        XCTAssertTrue(allItems[1].loopbackFixPrompt?.contains("0.0.0.0:3000") == true)
    }

    func testDiffSummaryAndQuickSuggestions() throws {
        let errChips = QuickReplySuggestionPolicy.suggestions(status: "error", hasError: true, diffFiles: 0, planMode: false, loopbackPort: 5173, loopbackPrompt: "bind 0.0.0.0")
        XCTAssertEqual(errChips.count, 3)
        XCTAssertTrue(errChips[0].label.contains(":5173"))
        XCTAssertTrue(errChips[1].label.contains("定位报错"))

        let diffChips = QuickReplySuggestionPolicy.suggestions(status: "completed", hasError: false, diffFiles: 2, planMode: false)
        XCTAssertEqual(diffChips.count, 3)
        XCTAssertTrue(diffChips[0].label.contains("运行测试验证改动"))

        let res = try decode(TaskResult.self, """
        {"available":true,"text":"ok","diff":{"source":"working_tree","commit_title":null,"total_files":1,"additions":3,"deletions":1,"truncated":false,
          "files":[{"path":"src/main.swift","status":"M","additions":3,"deletions":1,"binary":false,"truncated":false,
            "hunks":[{"header":"@@ -1,2 +1,4 @@","lines":[{"kind":"add","new_line":2,"text":"let ok = true"}]}]}]}}
        """)
        XCTAssertEqual(res.diff?.source, "working_tree")
        XCTAssertEqual(res.diff?.additions, 3)
        XCTAssertEqual(res.diff?.files?.first?.path, "src/main.swift")
        XCTAssertEqual(res.diff?.files?.first?.hunks?.first?.lines?.first?.newLine, 2)

        let errTask = try decode(MonitorTask.self, #"{"id":"device:codex:e1","title":"Build","tool":"codex","status":"error","error_analysis":{"summary":"Exit code 1"},"device_id":"device","archived":false,"stale":false}"#)
        XCTAssertEqual(errTask.errorAnalysis?.summary, "Exit code 1")
    }

    func testAntigravityDualQuotaHorizonsAndWeeklyCapPace() throws {
        let now = Date(timeIntervalSince1970: 1_795_800_000)
        let nowIso = ISO8601DateFormatter().string(from: now.addingTimeInterval(-10))
        let r5h = Int64(now.addingTimeInterval(4 * 3600).timeIntervalSince1970)
        let r7d = Int64(now.addingTimeInterval(5 * 86400).timeIntervalSince1970)
        let raw = """
        [
          {"key":"k1","bucket_label":"Claude / GPT-OSS","used_percent":100,"remaining_percent":0,"window_minutes":10080,"resets_at":\(r7d),"observed_at":"\(nowIso)","stale":false},
          {"key":"k2","bucket_label":"Gemini","used_percent":3,"remaining_percent":97,"window_minutes":300,"resets_at":\(r5h),"observed_at":"\(nowIso)","stale":false},
          {"key":"k3","bucket_label":"Gemini","used_percent":8,"remaining_percent":92,"window_minutes":10080,"resets_at":\(r7d),"observed_at":"\(nowIso)","stale":false},
          {"key":"k4","bucket_label":"Claude / GPT-OSS","used_percent":100,"remaining_percent":0,"window_minutes":300,"resets_at":\(r7d),"observed_at":"\(nowIso)","stale":false}
        ]
        """
        let quotas = try decode([UsageQuota].self, raw)
        let ordered = UsageSource.orderAntigravityQuotas(quotas)
        XCTAssertTrue(UsageSource.hasDualHorizons(ordered))
        let fiveHour = UsageSource.filterByHorizon(ordered, horizon: "5h")
        XCTAssertEqual(fiveHour.count, 2)
        XCTAssertEqual(fiveHour[0].bucketLabel, "Gemini")
        XCTAssertEqual(fiveHour[1].bucketLabel, "Claude / GPT-OSS")
        let cappedPace = fiveHour[1].pace(now: now, failed: false)
        XCTAssertEqual(cappedPace?.label, "受周额度限制")
        XCTAssertTrue((cappedPace?.idealRemainingPercent ?? 0) < 0)
        let weekly = UsageSource.filterByHorizon(ordered, horizon: "weekly")
        XCTAssertEqual(weekly.count, 2)
        XCTAssertTrue(weekly.allSatisfy { $0.windowMinutes == 10080 })
    }
}
