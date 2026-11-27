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

struct TaskErrorAnalysis: Decodable, Hashable {
    let summary: String?
}

struct MonitorTask: Decodable, Identifiable, Hashable {
    let id: String
    let title: String
    let tool: String
    let status: String
    let deviceId: String
    let deviceName: String?
    let project: String?
    var projectId: String? = nil
    let preview: String?
    let updatedAt: String?
    let finalResultId: String?
    var archived: Bool
    let stale: Bool?
    var usage: TaskUsageSummary? = nil
    var pendingInteractionKind: String? = nil
    var errorAnalysis: TaskErrorAnalysis? = nil
    static func brand(for tool: String) -> String {
        guard let source = ToolFilter(rawValue: tool), source != .all else { return "任务" }
        return source.title
    }
    var brand: String { Self.brand(for: tool) }
    var supportsReply: Bool { tool == "claude" || tool == "codex" || tool == "antigravity" }
    var effectivePendingKind: String {
        switch pendingInteractionKind {
        case "question": return "question"
        case "approval": return "approval"
        default: return ""
        }
    }
    var effectiveStatus: String {
        !effectivePendingKind.isEmpty ? "waiting" : status
    }
    var statusTitle: String {
        if effectivePendingKind == "question" { return "等待回答" }
        if effectivePendingKind == "approval" { return "等待批准" }
        switch status {
        case "running": return "进行中"
        case "waiting": return "待处理"
        case "completed": return "已完成"
        case "idle": return "待命"
        case "error": return "需要关注"
        default: return "状态未知"
        }
    }
    func matches(tool filter: ToolFilter, scope: TaskScope, fresh: Bool) -> Bool {
        guard filter == .all || tool == filter.rawValue else { return false }
        if scope == .archived { return archived }
        guard !archived else { return false }
        return scope == .all || (fresh && stale != true && ["running", "waiting", "error"].contains(effectiveStatus))
    }
}

struct ProjectSummary: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
    let tools: [String]?
    let updatedAt: String?
}

struct DevPort: Decodable, Hashable {
    let port: Int
    let bindScope: String
    let process: String?
    let projectId: String?
}

struct Computer: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
    let platform: String
    let online: Bool
    let local: Bool
    let lastSeen: String?
    var supportedTools: [String]? = nil
    var projects: [ProjectSummary]? = nil
    var devPorts: [DevPort]? = nil
    var previewHosts: [String]? = nil
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
struct DiffLine: Decodable {
    let kind: String
    let oldLine: Int?
    let newLine: Int?
    let text: String
}
struct DiffHunk: Decodable {
    let header: String
    let lines: [DiffLine]?
}
struct DiffFile: Decodable, Identifiable {
    var id: String { path }
    let path: String
    let status: String
    let additions: Int
    let deletions: Int
    let binary: Bool?
    let truncated: Bool?
    let hunks: [DiffHunk]?
}
struct DiffSummary: Decodable {
    let source: String
    let commitTitle: String?
    let totalFiles: Int
    let additions: Int
    let deletions: Int
    let truncated: Bool?
    let files: [DiffFile]?
}
struct DraftImageAttachment: Identifiable, Equatable {
    let id = UUID()
    let mime: String
    let data: String
    let label: String
    var wireDictionary: [String: String] { ["mime": mime, "data": data] }
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
    let diff: DiffSummary?
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
    let createdTaskId: String?
    let latest: ReplyCommand?
    let deliveryMs: Int?
}
struct ReplyCommand: Decodable {
    let id: String
    let state: String
    let reason: String?
    let createdTaskId: String?
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

struct DevPreviewItem: Identifiable, Equatable {
    var id: Int { port }
    let port: Int
    let process: String
    let bindScope: String
    let matchedProject: Bool
    let localMachine: Bool
    let openURL: String?
    let shareURL: String?
    let buttonLabel: String
    let subtitle: String
    let loopbackFixPrompt: String?
}

enum DevPreviewPolicy {
    private static let canonicalTools = ["codex", "claude", "antigravity"]

    static func supportedTools(for computer: Computer?) -> [String] {
        guard let raw = computer?.supportedTools, !raw.isEmpty else { return [] }
        return canonicalTools.filter { raw.contains($0) }
    }

    static func verifiedProjects(for computer: Computer?) -> [ProjectSummary] {
        guard let raw = computer?.projects, !raw.isEmpty else { return [] }
        var seen = Set<String>()
        var out: [ProjectSummary] = []
        for item in raw {
            let trimmed = item.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, MonitorSecurity.matches(item.id, "[0-9a-f]{24}") else { continue }
            if seen.insert(item.id).inserted { out.append(item) }
        }
        return out
    }

    static func validNetworkHost(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let host = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !host.isEmpty, host.count <= 253 else { return nil }
        for scalar in host.unicodeScalars {
            if scalar.value <= 0x20 || "/\\:@?#".unicodeScalars.contains(scalar) {
                return nil
            }
        }
        if host.hasSuffix(".ts.net") {
            let labels = host.split(separator: ".", omittingEmptySubsequences: false)
            guard labels.count >= 3 else { return nil }
            for label in labels {
                guard !label.isEmpty, label.count <= 63, label.first != "-", label.last != "-" else { return nil }
                for ch in label.unicodeScalars {
                    let ok = (ch.value >= 97 && ch.value <= 122) || (ch.value >= 48 && ch.value <= 57) || ch == "-"
                    if !ok { return nil }
                }
            }
            return host
        }
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return nil }
        var nums: [Int] = []
        for part in octets {
            guard !part.isEmpty, part.count <= 3, !(part.count > 1 && part.first == "0"),
                  part.allSatisfy({ $0 >= "0" && $0 <= "9" }),
                  let value = Int(part), (0...255).contains(value) else { return nil }
            nums.append(value)
        }
        let a = nums[0], b = nums[1]
        let allowed = a == 10
            || (a == 172 && (16...31).contains(b))
            || (a == 192 && b == 168)
            || (a == 100 && (64...127).contains(b))
        return allowed ? host : nil
    }

    static func validPreviewURL(_ raw: String?, allowLoopback: Bool) -> URL? {
        guard let raw, !raw.isEmpty,
              let components = URLComponents(string: raw),
              components.scheme == "http",
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              components.path == "/",
              let port = components.port, (1024...65535).contains(port),
              let host = components.host else { return nil }
        if allowLoopback && host == "127.0.0.1" { return components.url }
        guard validNetworkHost(host) != nil else { return nil }
        return components.url
    }

    static func items(for task: MonitorTask?, computer: Computer?, fresh: Bool) -> [DevPreviewItem] {
        guard fresh, let computer, computer.online, let rawPorts = computer.devPorts, !rawPorts.isEmpty else {
            return []
        }
        var validHosts: [String] = []
        for candidate in computer.previewHosts ?? [] {
            if let clean = validNetworkHost(candidate), !validHosts.contains(clean) {
                validHosts.append(clean)
            }
        }
        let taskProj = (task?.projectId).flatMap { MonitorSecurity.matches($0, "[0-9a-f]{24}") ? $0 : nil } ?? ""
        var seenPorts = Set<Int>()
        var matched: [DevPort] = []
        var all: [DevPort] = []
        for dp in rawPorts {
            guard (1024...65535).contains(dp.port),
                  dp.bindScope == "network" || dp.bindScope == "loopback",
                  seenPorts.insert(dp.port).inserted else { continue }
            all.append(dp)
            if !taskProj.isEmpty && dp.projectId == taskProj {
                matched.append(dp)
            }
        }
        guard !all.isEmpty else { return [] }
        let chosen = matched.isEmpty ? all : matched
        let matchedFlag = !matched.isEmpty
        let primaryHost = validHosts.first

        return chosen.prefix(4).map { dp in
            let procTrimmed = (dp.process ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let proc = procTrimmed.isEmpty ? "dev" : procTrimmed
            let fixPrompt = "请把当前监听 127.0.0.1:\(dp.port) 的本地开发服务改为绑定 0.0.0.0:\(dp.port)（例如加上 --host 0.0.0.0），方便手机或其他设备直接真机预览。"
            if computer.local {
                let localURL = "http://127.0.0.1:\(dp.port)/"
                let shareURL = (dp.bindScope == "network" && primaryHost != nil) ? "http://\(primaryHost!):\(dp.port)/" : nil
                let sub = shareURL != nil
                    ? "\(proc) · 本机与手机均可访问 (\(primaryHost!):\(dp.port))"
                    : "\(proc) · 本机可直连 (仅 127.0.0.1，手机预览需绑定 0.0.0.0)"
                return DevPreviewItem(
                    port: dp.port, process: proc, bindScope: dp.bindScope,
                    matchedProject: matchedFlag, localMachine: true,
                    openURL: localURL, shareURL: shareURL,
                    buttonLabel: "打开预览 (:\(dp.port))", subtitle: sub,
                    loopbackFixPrompt: dp.bindScope == "loopback" ? fixPrompt : nil
                )
            } else if dp.bindScope == "network", let host = primaryHost {
                let remoteURL = "http://\(host):\(dp.port)/"
                return DevPreviewItem(
                    port: dp.port, process: proc, bindScope: dp.bindScope,
                    matchedProject: matchedFlag, localMachine: false,
                    openURL: remoteURL, shareURL: remoteURL,
                    buttonLabel: "打开远程预览 (:\(dp.port))", subtitle: "\(proc) · \(host):\(dp.port)",
                    loopbackFixPrompt: nil
                )
            } else if dp.bindScope == "loopback" {
                return DevPreviewItem(
                    port: dp.port, process: proc, bindScope: dp.bindScope,
                    matchedProject: matchedFlag, localMachine: false,
                    openURL: nil, shareURL: nil,
                    buttonLabel: ":\(dp.port) 仅限该电脑本机",
                    subtitle: "\(proc) · 仅监听 127.0.0.1，需绑定 0.0.0.0 才可跨设备预览",
                    loopbackFixPrompt: fixPrompt
                )
            } else {
                return DevPreviewItem(
                    port: dp.port, process: proc, bindScope: dp.bindScope,
                    matchedProject: matchedFlag, localMachine: false,
                    openURL: nil, shareURL: nil,
                    buttonLabel: ":\(dp.port)",
                    subtitle: "\(proc) · 未检测到可用的 Tailscale 或局域网地址",
                    loopbackFixPrompt: nil
                )
            }
        }
    }
}

struct QuickSuggestionItem: Identifiable, Equatable {
    var id: String { label + "|" + prompt }
    let label: String
    let prompt: String
}

enum QuickReplySuggestionPolicy {
    static let maxAttachments = 3

    static func suggestions(
        status: String,
        hasError: Bool,
        diffFiles: Int,
        planMode: Bool,
        loopbackPort: Int = 0,
        loopbackPrompt: String? = nil
    ) -> [QuickSuggestionItem] {
        var out: [QuickSuggestionItem] = []
        if loopbackPort > 0, let lp = loopbackPrompt?.trimmingCharacters(in: .whitespacesAndNewlines), !lp.isEmpty {
            out.append(QuickSuggestionItem(label: "开放 :\(loopbackPort) 手机预览", prompt: lp))
        }
        if status == "failed" || status == "error" || hasError {
            out.append(QuickSuggestionItem(label: "定位报错并修复", prompt: "请定位上述报错或失败的根本原因，直接修复并运行验证确认恢复正常。"))
            out.append(QuickSuggestionItem(label: "最小复现并跑测试", prompt: "请先用最小范围复现该问题，修复后运行相关测试汇报结果。"))
        } else if planMode {
            out.append(QuickSuggestionItem(label: "按此方案执行", prompt: "方案确认无误，请按此方案逐步实施修改并运行测试验证。"))
            out.append(QuickSuggestionItem(label: "只保留核心改动", prompt: "请精简上述方案，只做最核心、改动最小的部分并执行。"))
        } else if diffFiles > 0 {
            out.append(QuickSuggestionItem(label: "运行测试验证改动", prompt: "请针对刚才修改的代码运行相关测试与构建检查，确认没有引入回归。"))
            out.append(QuickSuggestionItem(label: "自查边界与安全", prompt: "请审视本轮代码变更，检查是否有遗漏的边界情况、异常处理或权限安全问题。"))
            out.append(QuickSuggestionItem(label: "总结变更与提交说明", prompt: "请简要总结本轮修改的文件与核心逻辑，并草拟一段清晰的 Git 提交说明。"))
        } else {
            out.append(QuickSuggestionItem(label: "继续推进下一步", prompt: "请继续推进下一步，完成后汇报验证结果。"))
            out.append(QuickSuggestionItem(label: "运行测试检查状态", prompt: "请运行当前项目的测试套件，汇报通过情况与待处理项。"))
            out.append(QuickSuggestionItem(label: "总结进展与待办", prompt: "请简要总结目前已完成的内容，以及接下来还需要处理的事项。"))
        }
        return Array(out.prefix(4))
    }
}
