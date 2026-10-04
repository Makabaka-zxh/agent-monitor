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
}
