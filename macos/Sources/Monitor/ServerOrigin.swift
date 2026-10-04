import Foundation
import Darwin

/** One startup snapshot binds account traffic, Keychain and collector commands to one server. */
enum ServerOrigin {
    static let defaultsKey = "MonitorServerOrigin"
    static let current = resolve(environment: ProcessInfo.processInfo.environment,
                                 stored: UserDefaults.standard.string(forKey: defaultsKey))

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
