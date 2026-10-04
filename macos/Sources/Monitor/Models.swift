import Foundation

enum ToolFilter: String, CaseIterable, Identifiable {
    case all, claude, codex
    var id: String { rawValue }
    var title: String { switch self { case .all: "全部"; case .claude: "Claude Code"; case .codex: "Codex" } }
}
enum TaskScope: String, CaseIterable, Identifiable {
    case active, all, archived
    var id: String { rawValue }
    var title: String { switch self { case .active: "进行中"; case .all: "全部任务"; case .archived: "已归档" } }
}

struct MonitorTask: Decodable, Identifiable, Hashable {
    let id: String
    let title: String
    let tool: String
    let status: String
    let deviceId: String
    let deviceName: String?
    let project: String?
    let preview: String?
    let updatedAt: String?
    let finalResultId: String?
    var archived: Bool
    let stale: Bool?
    var brand: String { tool == "claude" ? "Claude Code" : tool == "codex" ? "Codex" : "任务" }
    var statusTitle: String {
        switch status {
        case "running": "进行中"
        case "waiting": "等待批准"
        case "completed": "已完成"
        case "idle": "待命"
        case "error": "需要关注"
        default: "状态未知"
        }
    }
    func matches(tool filter: ToolFilter, scope: TaskScope, fresh: Bool) -> Bool {
        guard filter == .all || tool == filter.rawValue else { return false }
        if scope == .archived { return archived }
        guard !archived else { return false }
        return scope == .all || (fresh && stale != true && ["running", "waiting", "error"].contains(status))
    }
}

struct Computer: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
    let platform: String
    let online: Bool
    let local: Bool
    let lastSeen: String?
    var symbol: String { platform.lowercased().contains("darwin") || platform.lowercased().contains("mac") ? "macmini" : "desktopcomputer" }
}
struct Preferences: Decodable {
    let syncOutput: Bool
    let toolFilter: String?
}
struct Workbench: Decodable {
    let devices: [Computer]
    let tasks: [MonitorTask]
    let preferences: Preferences
    let usage: UsageSummary?
}
struct Profile: Decodable {
    let username: String
    let displayName: String?
    let avatar: String?
    var name: String { displayName?.isEmpty == false ? displayName! : username }
}
struct Account: Decodable {
    let user: Profile
    let preferences: Preferences
    let googleLinked: Bool?
    let nativeDevices: [NativeConnection]?
}
struct NativeConnection: Decodable, Identifiable {
    let id: String
    let name: String
    let current: Bool?
}
struct PairingStart: Decodable {
    let requestId: String
    let verificationUrl: String
    let expiresAt: String
}
struct PairingPoll: Decodable {
    let status: String
    let readerToken: String?
    let mode: String?
    let expiresAt: String?
}
struct ComputerPairingCode: Decodable {
    let code: String
    let expiresAt: String
}
struct TaskResult: Decodable {
    let available: Bool
    let text: String?
    let resultId: String?
    let completedAt: String?
    let truncated: Bool?
    let files: [ResultFile]?
    let txtSize: Int?
    let txtSha256: String?
    let reason: String?
}
struct ResultFile: Decodable, Identifiable {
    let id: String
    let name: String
    let size: Int
    let sha256: String
    let ready: Bool?
}
struct ReplyState: Decodable {
    let id: String?
    let state: String?
    let found: Bool?
    let available: Bool?
    let reason: String?
    let latest: ReplyCommand?
    let deliveryMs: Int?
}
struct ReplyCommand: Decodable {
    let id: String
    let state: String
    let reason: String?
    let deliveryMs: Int?
}
struct PendingReply: Codable {
    let id: String
    let taskId: String
    var text: String
    let createdAt: Date
    var state: String
    var unresolved: Bool { !["succeeded", "failed", "blocked"].contains(state) }
    var mayRetry: Bool {
        let age = Date().timeIntervalSince(createdAt)
        return state == "not_found" && !text.isEmpty && age >= 0 && age <= 86400
    }
}
struct PairingSecret: Codable {
    let id: String
    let verifier: String
    let expiresAt: Date
}
struct LoginSecret: Codable {
    let token: String
    let expiresAt: Date
}

enum MonitorDate {
    static func parse(_ text: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let result = formatter.date(from: text) { return result }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: text)
    }
}
