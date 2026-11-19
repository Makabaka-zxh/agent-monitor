import Foundation
import Darwin

/** Bind account traffic, Keychain and collector commands to one validated HTTPS server origin. */
enum ServerOrigin {
    static let defaultsKey = "MonitorServerOrigin"
    private static let lock = NSLock()
    private static var runtimeStored: String? = UserDefaults.standard.string(forKey: defaultsKey)

    static var isEnvironmentControlled: Bool {
        ProcessInfo.processInfo.environment["MONITOR_SERVER_URL"] != nil
    }

    static var current: String? {
        lock.lock()
        let stored = runtimeStored
        lock.unlock()
        return resolve(environment: ProcessInfo.processInfo.environment, stored: stored)
    }

    @discardableResult
    static func save(_ rawValue: String, defaults: UserDefaults = .standard) -> String? {
        guard !isEnvironmentControlled,
              let normalized = normalize(rawValue.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return nil
        }
        defaults.set(normalized, forKey: defaultsKey)
        if defaults === UserDefaults.standard {
            lock.lock()
            runtimeStored = normalized
            lock.unlock()
        }
        return normalized
    }

    /// Read-only UI prefill hint from this user's local collector config; never auto-connects.
    static func suggestedCollectorOrigin(home: URL = FileManager.default.homeDirectoryForCurrentUser) -> String? {
        let root = home.resolvingSymlinksInPath()
        let config = root.appendingPathComponent("Library/Application Support/Monitor/collector/agent/config.json")
        guard config.resolvingSymlinksInPath() == config else { return nil }
        var info = stat()
        guard lstat(config.path, &info) == 0,
              (info.st_mode & S_IFMT) == S_IFREG,
              info.st_uid == getuid(),
              info.st_size > 0, info.st_size <= 64 * 1024,
              let data = try? Data(contentsOf: config),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let server = object["server"] as? String else { return nil }
        return normalize(server)
    }

    static func resolve(environment: [String: String], stored: String?) -> String? {
        // An explicitly supplied but invalid environment value must not fall through to another host.
        normalize(environment["MONITOR_SERVER_URL"] ?? stored)
    }

    static func normalize(_ value: String?) -> String? {
        guard let value, !value.isEmpty, value.count <= 512,
              value.unicodeScalars.allSatisfy({ $0.value > 32 && $0.value < 127 }),
              !value.contains("%"), !value.contains("\\"),
              let parts = URLComponents(string: value), parts.scheme?.lowercased() == "https",
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/", var host = parts.host?.lowercased(), !host.isEmpty,
              let authorityStart = value.range(of: "://")?.upperBound else { return nil }
        let authority = value[authorityStart...].split(separator: "/", omittingEmptySubsequences: false)[0]
        guard authority.range(of: "\\A(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?::[0-9]+)?\\z", options: .regularExpression) != nil else { return nil }
        let portRange = authority.range(of: ":[0-9]+\\z", options: .regularExpression)
        let port = portRange.flatMap { Int(authority[$0].dropFirst()) }
        guard portRange == nil || port.map({ (1...65535).contains($0) }) == true else { return nil }
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        if host.contains(":") {
            var address = in6_addr()
            guard host.withCString({ inet_pton(AF_INET6, $0, &address) }) == 1 else { return nil }
            host = "[" + host + "]"
        } else {
            guard host.count <= 253, host.split(separator: ".", omittingEmptySubsequences: false).allSatisfy({
                $0.range(of: "\\A[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\z", options: .regularExpression) != nil
            }) else { return nil }
        }
        return "https://" + host + (port.map { $0 == 443 ? "" : ":\($0)" } ?? "")
    }

    static func collectorArguments(_ arguments: [String], origin: String?) throws -> [String] {
        guard let command = arguments.first, ["status", "pair", "start", "stop", "mode"].contains(command),
              !arguments.contains(where: { $0 == "--server" || $0.hasPrefix("--server=") }) else { throw MonitorError.invalidRoute }
        if let origin = normalize(origin) { return arguments + ["--server", origin] }
        // Stopping an existing local process must remain possible when configuration is missing.
        if command == "stop" { return arguments }
        throw MonitorError.serverConfiguration
    }
}
