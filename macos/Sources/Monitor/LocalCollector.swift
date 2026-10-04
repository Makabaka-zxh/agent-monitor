import Combine
import Foundation

enum CollectorMode: String, CaseIterable, Identifiable {
    case statusOnly = "status-only", results, full
    var id: String { rawValue }
    var title: String {
        switch self {
        case .statusOnly: "仅任务状态"
        case .results: "结果与文件"
        case .full: "结果、文件与回复"
        }
    }
    var explanation: String {
        switch self {
        case .statusOnly: "手机可以查看这台 Mac 的任务状态。"
        case .results: "同步最终结果及其引用的配套文件，供已登录的设备查看和下载。"
        case .full: "同步结果和文件，并允许已登录的设备在原会话发送回复。需要批准的操作仍在电脑处理。"
        }
    }
}

struct CollectorStatus: Decodable {
    let installed: Bool
    let paired: Bool
    let service: String
    let mode: String
    let modeSchema: Int?
    let supportedModes: [String]?
    let serverScopeVersion: Int?
    var supportsServerBinding: Bool { serverScopeVersion == 1 }
    var selectedMode: CollectorMode? { CollectorMode(rawValue: mode) }
    var supportsModeSelection: Bool {
        modeSchema == 1 && selectedMode != nil &&
            Set(supportedModes ?? []) == Set(CollectorMode.allCases.map(\.rawValue))
    }
    static func decode(_ data: Data) throws -> CollectorStatus {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Self.self, from: data)
    }
}

@MainActor
final class LocalCollector: ObservableObject {
    @Published private(set) var status: CollectorStatus?
    @Published private(set) var busy = false
    @Published private(set) var message = ""
    private static var executable: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Monitor/collector/bin/monitor-collector")
    }
    func refresh() async {
        guard !busy else { return }
        guard FileManager.default.isExecutableFile(atPath: Self.executable.path) else { status = nil; return }
        busy = true
        defer { busy = false }
        do {
            let data = try await Self.run(["status"])
            let result = try CollectorStatus.decode(data)
            guard result.supportsServerBinding else {
                status = nil; message = "请先更新本机连接器，再连接当前服务器。"; return
            }
            status = result
            message = ""
        } catch { status = nil; message = "暂时无法读取本机连接状态" }
    }
    func pair(code: String) async {
        guard !busy, MonitorSecurity.matches(code, "[A-F0-9]{12}") else { return }
        guard status?.supportsServerBinding == true else { message = "请先更新本机连接器。"; return }
        busy = true
        var operationMessage = ""
        do {
            _ = try await Self.run(["pair", "--code-stdin", "--name", String((Host.current().localizedName ?? "Mac").prefix(60))], input: code + "\n")
            operationMessage = "这台 Mac 已连接"
        } catch { operationMessage = "连接未完成，请重试" }
        busy = false
        await refresh()
        message = operationMessage
    }
    func setRunning(_ running: Bool) async {
        guard !busy else { return }
        guard !running || status?.supportsServerBinding == true else { message = "请先更新本机连接器。"; return }
        busy = true
        var operationFailed = false
        do { _ = try await Self.run([running ? "start" : "stop"]) }
        catch { operationFailed = true }
        busy = false
        await refresh()
        if operationFailed { message = "操作未完成，请重试" }
    }
    func setMode(_ mode: CollectorMode) async -> Bool {
        guard !busy, status?.supportsModeSelection == true, status?.supportsServerBinding == true else { return false }
        busy = true
        var succeeded = false
        do {
            let value = try CollectorStatus.decode(await Self.run(["mode", "--set", mode.rawValue], timeoutSeconds: 90))
            guard value.supportsModeSelection, value.selectedMode == mode else { throw MonitorError.invalidResponse }
            status = value
            succeeded = true
        } catch { message = "设置未完成，请检查本机连接状态后重试" }
        busy = false
        if succeeded { message = "同步设置已保存" }
        else {
            await refresh()
            message = "设置未完成，请检查本机连接状态后重试"
        }
        return succeeded
    }
    private static func run(_ arguments: [String], input: String? = nil, timeoutSeconds: Double = 45) async throws -> Data {
        let path = executable
        let scopedArguments = try ServerOrigin.collectorArguments(arguments, origin: ServerOrigin.current)
        return try await Task.detached(priority: .utility) {
            // Owned entry point and the same validated startup origin as the App; no shell or override.
            guard path.resolvingSymlinksInPath() == path, FileManager.default.isExecutableFile(atPath: path.path) else { throw MonitorError.invalidRoute }
            let process = Process()
            process.executableURL = path
            process.arguments = scopedArguments
            let output = Pipe()
            let supplied = Pipe()
            process.standardOutput = output
            process.standardError = FileHandle.nullDevice
            process.standardInput = supplied
            try process.run()
            if let input { try supplied.fileHandleForWriting.write(contentsOf: Data(input.utf8)) }
            try supplied.fileHandleForWriting.close()
            let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeoutSeconds, execute: timeout)
            defer { timeout.cancel() }
            let data = output.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            guard process.terminationStatus == 0, data.count <= 16 * 1024 else { throw MonitorError.invalidResponse }
            return data
        }.value
    }
}
