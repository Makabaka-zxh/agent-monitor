import XCTest
@testable import Monitor

final class UsageNotificationPayloadTests: XCTestCase {
    private let account = String(repeating: "a", count: 64)
    func testNotificationsHaveNoQuotaOrComputerDetails() {
        for tool in ["codex", "claude"] {
            let payload = UsageNotificationPayload(account: account, tool: tool)
            XCTAssertEqual(payload.title, tool == "claude" ? "Claude Code" : "Codex")
            XCTAssertEqual(payload.body, "有一条额度提醒，打开 Monitor 查看")
            XCTAssertEqual(Set(payload.userInfo.keys), Set(["monitor_route", "monitor_account", "monitor_tool"]))
            XCTAssertEqual(UsageNotificationPayload.route(payload.userInfo, currentAccount: account), tool)
        }
    }
    func testTapRejectsSignedOutOldAccountAndUnknownDestinations() {
        let payload = UsageNotificationPayload(account: account, tool: "claude")
        XCTAssertNil(UsageNotificationPayload.route(payload.userInfo, currentAccount: nil))
        XCTAssertNil(UsageNotificationPayload.route(payload.userInfo, currentAccount: String(repeating: "b", count: 64)))
        var data = payload.userInfo
        data["monitor_route"] = "https://example.invalid"
        XCTAssertNil(UsageNotificationPayload.route(data, currentAccount: account))
        data = payload.userInfo; data["monitor_tool"] = "unexpected"
        XCTAssertNil(UsageNotificationPayload.route(data, currentAccount: account))
        data = payload.userInfo; data["monitor_account"] = ""
        XCTAssertNil(UsageNotificationPayload.route(data, currentAccount: ""))
    }
    func testTestNotificationHasSeparateTextAndSameSafeDestination() {
        let payload = UsageNotificationPayload(account: account, tool: "", test: true)
        XCTAssertEqual(payload.title, "Monitor · 测试提醒")
        XCTAssertEqual(UsageNotificationPayload.route(payload.userInfo, currentAccount: account), "")
    }
}
