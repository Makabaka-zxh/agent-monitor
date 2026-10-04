import Foundation
import Security
import CryptoKit

enum MonitorError: LocalizedError {
    case invalidResponse, sizeLimit, digestMismatch, keychain, invalidRoute, serverConfiguration
    case service(Int, String, TimeInterval)
    var errorDescription: String? {
        switch self {
        case .invalidResponse: "服务返回的内容无效，请重试"
        case .sizeLimit: "内容超过下载上限"
        case .digestMismatch: "文件校验未通过，请刷新结果后重试"
        case .keychain: "无法保存到钥匙串，请检查系统授权"
        case .invalidRoute: "连接地址无效"
        case .serverConfiguration: "请先配置 HTTPS 服务器地址，重新打开 Monitor 后登录"
        case let .service(_, message, _): message
        }
    }
    var status: Int { if case let .service(code, _, _) = self { return code }; return 0 }
    var retryAfter: TimeInterval { if case let .service(_, _, seconds) = self { return seconds }; return 0 }
}

enum MonitorSecurity {
    static func matches(_ text: String, _ regex: String) -> Bool {
        text.range(of: "\\A(?:" + regex + ")\\z", options: .regularExpression) != nil
    }
    static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func verifier() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw MonitorError.keychain }
        return base64URL(Data(bytes))
    }
    static func challenge(_ verifier: String) -> String { base64URL(Data(SHA256.hash(data: Data(verifier.utf8)))) }
    static func safeName(_ value: String) -> String {
        let cleaned = value.unicodeScalars.map { scalar -> String in
            if CharacterSet.controlCharacters.contains(scalar) || CharacterSet(charactersIn: "/\\:").contains(scalar) || scalar.properties.generalCategory == .format { return "_" }
            return String(scalar)
        }.joined().trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        return cleaned.isEmpty ? "附件" : String(cleaned.prefix(180))
    }
}

enum Keychain {
    static func service(for origin: String) throws -> String {
        guard let normalized = ServerOrigin.normalize(origin) else { throw MonitorError.serverConfiguration }
        return "com.agentmonitor.mac.server.v1." + MonitorSecurity.hash(Data(normalized.utf8))
    }
    // Never read/migrate the former unbound service. A new origin requires a new login.
    private static func currentService() throws -> String { try service(for: MonitorAPI.origin) }
    static func read<T: Decodable>(_ type: T.Type, key: String) throws -> T? {
        let service = try currentService()
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                  kSecAttrAccount as String: key, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw MonitorError.keychain }
        do { return try JSONDecoder().decode(type, from: data) } catch { throw MonitorError.keychain }
    }
    static func write<T: Encodable>(_ value: T, key: String) throws {
        let service = try currentService()
        let data = try JSONEncoder().encode(value)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw MonitorError.keychain }
        } else if status != errSecSuccess { throw MonitorError.keychain }
    }
    static func remove(_ key: String) throws {
        let service = try currentService()
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw MonitorError.keychain }
    }
}
