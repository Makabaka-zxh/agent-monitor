import XCTest
@testable import Monitor

final class CollectorModeTests: XCTestCase {
    func testOldInstallRemainsReadableWithoutOfferingUnsupportedChanges() throws {
        let old = try CollectorStatus.decode(Data(#"{"installed":true,"paired":true,"service":"running","mode":"status-only"}"#.utf8))
        XCTAssertEqual(old.selectedMode, .statusOnly)
        XCTAssertFalse(old.supportsModeSelection)
    }

    func testAllModesDecodeWithVersionedCapabilities() throws {
        for mode in CollectorMode.allCases {
            let json = """
            {"installed":true,"paired":true,"service":"stopped","mode":"\(mode.rawValue)","mode_schema":1,"supported_modes":["full","results","status-only"]}
            """
            let status = try CollectorStatus.decode(Data(json.utf8))
            XCTAssertEqual(status.selectedMode, mode)
            XCTAssertTrue(status.supportsModeSelection)
            XCTAssertEqual(status.service, "stopped")
        }
    }

    func testUnknownModesAndSchemasCannotBeAppliedAsDefaults() throws {
        for pair in [("other", 1), ("full", 2)] {
            let json = """
            {"installed":true,"paired":true,"service":"running","mode":"\(pair.0)","mode_schema":\(pair.1),"supported_modes":["status-only","results","full"]}
            """
            XCTAssertFalse(try CollectorStatus.decode(Data(json.utf8)).supportsModeSelection)
        }
        let incomplete = try CollectorStatus.decode(Data(#"{"installed":true,"paired":true,"service":"running","mode":"results","mode_schema":1,"supported_modes":["status-only","results"]}"#.utf8))
        XCTAssertFalse(incomplete.supportsModeSelection)
    }
}
