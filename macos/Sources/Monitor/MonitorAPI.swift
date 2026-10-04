import Foundation

enum Endpoint {
    case workbench, usage, account, profile, preferences, session, pairingStart, pairingPoll(String), pairingCode
    case archive, result(String), resultTXT(String, String), file(String, String, String), reply(String, String?), sendReply

    var path: String {
        switch self {
        case .workbench: "/api/native/workbench"
        case .usage: "/api/native/usage"
        case .account: "/api/native/account"
        case .profile: "/api/native/profile"
        case .preferences: "/api/native/preferences"
        case .session: "/api/native/session"
        case .pairingStart: "/api/native/pairing/start"
        case let .pairingPoll(id): "/api/native/pairing/\(id)/poll"
        case .pairingCode: "/api/native/computers/pairing"
        case .archive: "/api/native/tasks/archive"
        case .result: "/api/native/tasks/result"
        case .resultTXT: "/api/native/tasks/result.txt"
        case .file: "/api/native/tasks/result/file"
        case .reply, .sendReply: "/api/native/tasks/reply"
        }
    }
    func url(origin: String = MonitorAPI.origin) throws -> URL {
        var query: [URLQueryItem] = []
        switch self {
        case let .pairingPoll(id):
            guard MonitorSecurity.matches(id, "[A-Za-z0-9_-]{32}") else { throw MonitorError.invalidRoute }
        case let .result(task): query = [URLQueryItem(name: "task_id", value: task)]
        case let .resultTXT(task, result):
            guard MonitorSecurity.matches(result, "[0-9a-f]{64}") else { throw MonitorError.invalidRoute }
            query = [.init(name: "task_id", value: task), .init(name: "result_id", value: result)]
        case let .file(task, result, file):
            guard MonitorSecurity.matches(result, "[0-9a-f]{64}"), MonitorSecurity.matches(file, "[0-9a-f]{64}") else { throw MonitorError.invalidRoute }
            query = [.init(name: "task_id", value: task), .init(name: "result_id", value: result), .init(name: "file_id", value: file)]
        case let .reply(task, request):
            query = [.init(name: "task_id", value: task)]
            if let request {
                guard MonitorSecurity.matches(request, "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") else { throw MonitorError.invalidRoute }
                query.append(.init(name: "request_id", value: request))
            }
        default: break
        }
        if let task = query.first?.value, task.isEmpty || task.count > 500 { throw MonitorError.invalidRoute }
        guard let origin = ServerOrigin.normalize(origin), var components = URLComponents(string: origin) else { throw MonitorError.serverConfiguration }
        components.path = path
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw MonitorError.invalidRoute }
        return url
    }
}

private final class RejectRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

final class MonitorAPI {
    static let origin = ServerOrigin.current ?? ""
    private let redirectDelegate: RejectRedirects
    private let session: URLSession
    init() {
        let delegate = RejectRedirects()
        redirectDelegate = delegate
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 45
        config.waitsForConnectivity = false
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    func json<T: Decodable>(_ type: T.Type, _ endpoint: Endpoint, method: String = "GET", body: [String: Any]? = nil, token: String? = nil) async throws -> T {
        let limit = endpoint.path == "/api/native/workbench" ? 8 * 1024 * 1024 : 1024 * 1024
        let data = try await bytes(endpoint, method: method, body: body, token: token, maximum: limit)
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        do { return try decoder.decode(type, from: data) } catch { throw MonitorError.invalidResponse }
    }
    func mutate(_ endpoint: Endpoint, method: String, body: [String: Any]? = nil, token: String) async throws {
        _ = try await bytes(endpoint, method: method, body: body, token: token, maximum: 1024 * 1024)
    }
    func download(_ endpoint: Endpoint, token: String, size: Int, sha256: String) async throws -> Data {
        guard size >= 0, size <= 32 * 1024 * 1024, MonitorSecurity.matches(sha256, "[0-9a-f]{64}") else { throw MonitorError.invalidResponse }
        let data = try await bytes(endpoint, token: token, maximum: size)
        guard data.count == size, MonitorSecurity.hash(data) == sha256 else { throw MonitorError.digestMismatch }
        return data
    }
    private func bytes(_ endpoint: Endpoint, method: String = "GET", body: [String: Any]? = nil, token: String?, maximum: Int) async throws -> Data {
        var request = URLRequest(url: try endpoint.url())
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(MonitorAPI.origin, forHTTPHeaderField: "Origin")
        if let token {
            guard MonitorSecurity.matches(token, "nrd_[A-Za-z0-9_-]{43}") else { throw MonitorError.invalidResponse }
            request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (stream, rawResponse) = try await session.bytes(for: request)
        guard let response = rawResponse as? HTTPURLResponse, response.url == request.url else { throw MonitorError.invalidResponse }
        let succeeded = (200..<300).contains(response.statusCode)
        let limit = succeeded ? maximum : 16 * 1024
        guard response.expectedContentLength < 0 || response.expectedContentLength <= Int64(limit) else { throw MonitorError.sizeLimit }
        var data = Data()
        data.reserveCapacity(min(limit, 64 * 1024))
        for try await byte in stream {
            guard data.count < limit else { throw MonitorError.sizeLimit }
            data.append(byte)
        }
        guard succeeded else {
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            let detail = object?["detail"] as? String
            let fallback = response.statusCode == 401 || response.statusCode == 403 ? "连接已失效，请重新登录" : "请求未完成，请稍后重试"
            let retry = min(300, max(1, TimeInterval(response.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 5))
            throw MonitorError.service(response.statusCode, String((detail ?? fallback).prefix(300)), retry)
        }
        return data
    }
}
