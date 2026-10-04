import Foundation

/// Notification Center never receives quota values, computer names or task text.
struct UsageNotificationPayload {
    let title: String
    let body: String
    let account: String
    let tool: String

    init(account: String, tool: String, test: Bool = false) {
        self.account = account
        self.tool = ["codex", "claude"].contains(tool) ? tool : ""
        title = test ? "Monitor · 测试提醒" : tool == "claude" ? "Claude Code" : "Codex"
        body = test ? "点击查看用量" : "有一条额度提醒，打开 Monitor 查看"
    }
    var userInfo: [String: String] {
        ["monitor_route": "usage", "monitor_account": account, "monitor_tool": tool]
    }
    static func route(_ info: [AnyHashable: Any], currentAccount: String?) -> String? {
        guard let currentAccount, currentAccount.count == 64,
              currentAccount.allSatisfy({ "0123456789abcdef".contains($0) }),
              info["monitor_route"] as? String == "usage",
              info["monitor_account"] as? String == currentAccount,
              let tool = info["monitor_tool"] as? String, ["", "codex", "claude"].contains(tool) else { return nil }
        return tool
    }
}
