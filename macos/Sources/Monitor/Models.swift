import Foundation

enum ToolFilter: String, CaseIterable, Identifiable {
    case all, claude, codex, antigravity
    var id: String { rawValue }
    var title: String { switch self { case .all: "全部"; case .claude: "Claude Code"; case .codex: "Codex"; case .antigravity: "Antigravity" } }
}
enum TaskScope: String, CaseIterable, Identifiable {
    case active, all, archived
    var id: String { rawValue }
    var title: String { switch self { case .active: "进行中"; case .all: "全部任务"; case .archived: "已归档" } }
}

struct TaskUsageSummary: Decodable, Hashable {
    let tool: String?
    let coverage: String?
    let totalTokens: Int64?
    let inputTokens: Int64?
    let outputTokens: Int64?
    let cachedInputTokens: Int64?
    let cacheWriteTokens: Int64?
    let observedAt: String?

    private enum CodingKeys: String, CodingKey {
        case tool, coverage, totalTokens, inputTokens, outputTokens, cachedInputTokens, cacheWriteTokens, observedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tool = try? c.decode(String.self, forKey: .tool)
        coverage = try? c.decode(String.self, forKey: .coverage)
        totalTokens = UsageFormat.count(try? c.decode(Int64.self, forKey: .totalTokens))
        inputTokens = UsageFormat.count(try? c.decode(Int64.self, forKey: .inputTokens))
        outputTokens = UsageFormat.count(try? c.decode(Int64.self, forKey: .outputTokens))
        cachedInputTokens = UsageFormat.count(try? c.decode(Int64.self, forKey: .cachedInputTokens))
        cacheWriteTokens = UsageFormat.count(try? c.decode(Int64.self, forKey: .cacheWriteTokens))
        observedAt = try? c.decode(String.self, forKey: .observedAt)
    }
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
    var usage: TaskUsageSummary? = nil
    static func brand(for tool: String) -> String {
        guard let source = ToolFilter(rawValue: tool), source != .all else { return "任务" }
        return source.title
    }
    var brand: String { Self.brand(for: tool) }
    var supportsReply: Bool { tool == "claude" || tool == "codex" || tool == "antigravity" }
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
struct QRPairingStartResponse: Decodable {
    let requestId: String
    let pollSecret: String
    let verificationUrl: String
    let expiresAt: String
    let interval: Int?
    let includeCollector: Bool?
    let includeReader: Bool?
}
struct QRPairingPollResponse: Decodable {
    let status: String
    let readerToken: String?
    let readerExpiresAt: String?
    let readerMode: String?
    let collectorCode: String?
    let collectorCodeExpiresAt: String?
    let interval: Int?
}
struct QRLoginSession: Equatable {
    let id: String
    let pollSecret: String
    let verifier: String
    let verificationURL: String
    let expiresAt: Date
    let includeCollector: Bool
    let intervalSeconds: Int
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
    var submittedSelection: SessionModelSelection?
    var submittedSelectionInvalid: Bool

    init(id: String, taskId: String, text: String, createdAt: Date, state: String,
         submittedSelection: SessionModelSelection? = nil, submittedSelectionInvalid: Bool = false) {
        self.id = id
        self.taskId = taskId
        self.text = text
        self.createdAt = createdAt
        self.state = state
        self.submittedSelection = submittedSelection
        self.submittedSelectionInvalid = submittedSelectionInvalid
            || (submittedSelection.map { !SessionControlPolicy.selectionSnapshot($0) } ?? false)
    }

    private enum CodingKeys: String, CodingKey {
        case id, taskId, text, createdAt, state, submittedSelection, submittedSelectionInvalid
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        taskId = try c.decode(String.self, forKey: .taskId)
        text = try c.decode(String.self, forKey: .text)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        state = try c.decode(String.self, forKey: .state)
        var invalid = (try? c.decodeIfPresent(Bool.self, forKey: .submittedSelectionInvalid)) ?? false
        if c.contains(.submittedSelection), (try? c.decodeNil(forKey: .submittedSelection)) != true {
            if let decoded = try? c.decode(SessionModelSelection.self, forKey: .submittedSelection),
               SessionControlPolicy.selectionSnapshot(decoded) {
                submittedSelection = decoded
            } else {
                submittedSelection = nil
                invalid = true
            }
        } else {
            submittedSelection = nil
        }
        submittedSelectionInvalid = invalid
    }

    var unresolved: Bool { !["succeeded", "failed", "blocked"].contains(state) }
    var mayRetry: Bool {
        let age = Date().timeIntervalSince(createdAt)
        return !submittedSelectionInvalid && state == "not_found" && !text.isEmpty && age >= 0 && age <= 86400
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
