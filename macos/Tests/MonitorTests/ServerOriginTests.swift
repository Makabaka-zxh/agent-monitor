import XCTest
@testable import Monitor

final class ServerOriginTests: XCTestCase {
    func testCanonicalHTTPSRoots() {
        for (input, expected) in [
            ("https://monitor.example.com", "https://monitor.example.com"),
            ("HTTPS://Monitor.Example.com:443/", "https://monitor.example.com"),
            ("https://monitor.example.com:8443/", "https://monitor.example.com:8443"),
            ("https://127.0.0.1:8443", "https://127.0.0.1:8443"),
            ("https://[2001:DB8::1]:443/", "https://[2001:db8::1]")
        ] { XCTAssertEqual(ServerOrigin.normalize(input), expected) }
    }
    func testMissingAmbiguousAndNonRootAddressesFailClosed() {
        for input in ["", " https://monitor.example.com", "https://monitor.example.com\n", "http://monitor.example.com",
                      "https://user:secret@monitor.example.com", "https://monitor.example.com/path", "https://monitor.example.com//",
                      "https://monitor.example.com?", "https://monitor.example.com#", "https://monitor.example.com:0",
                      "https://monitor.example.com:65536", "https://monitor.example.com:", "https://monitor.example.com.",
                      "https://monitor..example.com", "https://-monitor.example.com", "https://monitor_example.com",
                      "https://monitor.example.com\\evil", "https://monitor%2eexample.com", "https://服务器.example.com",
                      "https://[:::]", "https://[::1]suffix", "https://[fe80::1%25en0]", "https://monitor.example.com:999999999999999999999"] {
            XCTAssertNil(ServerOrigin.normalize(input), input)
        }
        XCTAssertNil(ServerOrigin.normalize(nil))
        XCTAssertNil(ServerOrigin.normalize("https://" + String(repeating: "a", count: 64) + ".example.com"))
    }
    func testEnvironmentHasExplicitPrecedenceWithoutFallbackFromInvalidInput() {
        XCTAssertEqual(ServerOrigin.resolve(environment: [:], stored: "https://stored.example.com"), "https://stored.example.com")
        XCTAssertEqual(ServerOrigin.resolve(environment: ["MONITOR_SERVER_URL": "https://environment.example.com"], stored: "https://stored.example.com"), "https://environment.example.com")
        for invalid in ["", "http://environment.example.com", "not a server"] {
            XCTAssertNil(ServerOrigin.resolve(environment: ["MONITOR_SERVER_URL": invalid], stored: "https://stored.example.com"))
        }
        XCTAssertNil(ServerOrigin.resolve(environment: [:], stored: nil))
    }
    func testKeychainNamespacesNeverReuseUnboundCredentials() throws {
        let first = try Keychain.service(for: "https://one.example.com")
        XCTAssertEqual(first, try Keychain.service(for: "HTTPS://ONE.example.com:443/"))
        XCTAssertNotEqual(first, try Keychain.service(for: "https://two.example.com"))
        XCTAssertNotEqual(first, try Keychain.service(for: "https://one.example.com:8443"))
        XCTAssertNotEqual(first, "com.agentmonitor.mac")
        XCTAssertTrue(first.hasPrefix("com.agentmonitor.mac.server.v1."))
        XCTAssertThrowsError(try Keychain.service(for: ""))
    }
    func testEndpointAndCollectorUseTheSameOrigin() throws {
        let origin = "https://monitor.example.com:8443"
        XCTAssertEqual(try Endpoint.usage.url(origin: origin).absoluteString, origin + "/api/native/usage")
        XCTAssertThrowsError(try Endpoint.usage.url(origin: ""))
        XCTAssertThrowsError(try Endpoint.usage.url(origin: "http://monitor.example.com"))
        for command in [["status"], ["pair", "--code-stdin", "--name", "Example Mac"], ["start"], ["stop"], ["mode", "--set", "full"]] {
            XCTAssertEqual(try ServerOrigin.collectorArguments(command, origin: origin), command + ["--server", origin])
        }
        for command in [["pair"], ["start"], ["mode", "--set", "full"], ["status"]] {
            XCTAssertThrowsError(try ServerOrigin.collectorArguments(command, origin: nil))
        }
        XCTAssertEqual(try ServerOrigin.collectorArguments(["stop"], origin: nil), ["stop"])
        XCTAssertThrowsError(try ServerOrigin.collectorArguments(["pair", "--server", "https://other.example.com"], origin: origin))
        XCTAssertThrowsError(try ServerOrigin.collectorArguments(["pair", "--server=https://other.example.com"], origin: origin))
        XCTAssertThrowsError(try ServerOrigin.collectorArguments(["uninstall"], origin: origin))
    }
    func testOldCollectorCannotClaimServerScopedPairing() throws {
        let old = try CollectorStatus.decode(Data(#"{"installed":true,"paired":true,"service":"running","mode":"status-only"}"#.utf8))
        XCTAssertFalse(old.supportsServerBinding)
        for version in [1, 2] {
            let status = try CollectorStatus.decode(Data("{\"installed\":true,\"paired\":false,\"service\":\"stopped\",\"mode\":\"status-only\",\"server_scope_version\":\(version)}".utf8))
            XCTAssertEqual(status.supportsServerBinding, version == 1)
        }
    }
}
