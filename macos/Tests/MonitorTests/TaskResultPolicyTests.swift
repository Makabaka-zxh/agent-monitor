import XCTest
@testable import Monitor

final class TaskResultPolicyTests: XCTestCase {
    func testBodyArrivingAfterWorkbenchMarkerIsStillFetched() {
        let marker = String(repeating: "a", count: 64)
        // Workbench arrives first; the first successful result GET has no body yet.
        XCTAssertTrue(TaskResultPolicy.needsRefresh(initial: true, loadedMarker: marker, currentMarker: marker,
                                                     available: false, hasPendingFiles: false))
        // Once that same version's body arrives, ordinary polling can stop.
        XCTAssertFalse(TaskResultPolicy.needsRefresh(initial: true, loadedMarker: marker, currentMarker: marker,
                                                      available: true, hasPendingFiles: false))
        XCTAssertTrue(TaskResultPolicy.needsRefresh(initial: true, loadedMarker: marker, currentMarker: marker,
                                                     available: true, hasPendingFiles: true))
    }

    func testLateOldVersionCannotReplaceAResultAfterMarkerAdvances() {
        let generation = UUID()
        XCTAssertFalse(TaskResultPolicy.acceptsResponse(requestGeneration: generation, currentGeneration: generation,
                         requestedMarker: "old", currentMarker: "new", currentAccount: true, syncOutput: true, cancelled: false))
        XCTAssertTrue(TaskResultPolicy.needsRefresh(initial: true, loadedMarker: "old", currentMarker: "new",
                                                    available: true, hasPendingFiles: false))
        XCTAssertTrue(TaskResultPolicy.acceptsResponse(requestGeneration: generation, currentGeneration: generation,
                        requestedMarker: "new", currentMarker: "new", currentAccount: true, syncOutput: true, cancelled: false))
    }

    func testOffThenOnDoesNotAuthorizeAnOlderReadOrPendingSavePanel() {
        let beforeDisable = UUID(), afterEnable = UUID()
        XCTAssertFalse(TaskResultPolicy.acceptsResponse(requestGeneration: beforeDisable, currentGeneration: afterEnable,
                        requestedMarker: "same", currentMarker: "same", currentAccount: true, syncOutput: true, cancelled: false))
        XCTAssertFalse(TaskResultPolicy.canExport(currentAccount: true, syncOutput: true, cancelled: false,
                                                   requestGeneration: beforeDisable, currentGeneration: afterEnable))
        XCTAssertTrue(TaskResultPolicy.canExport(currentAccount: true, syncOutput: true, cancelled: false,
                                                  requestGeneration: afterEnable, currentGeneration: afterEnable))
    }

    func testLeavingDetailOrRevokingPermissionRejectsReadAndExport() {
        let generation = UUID()
        for context in [(false, true, false), (true, false, false), (true, true, true)] {
            XCTAssertFalse(TaskResultPolicy.acceptsResponse(requestGeneration: generation, currentGeneration: generation,
                            requestedMarker: "same", currentMarker: "same", currentAccount: context.0,
                            syncOutput: context.1, cancelled: context.2))
            XCTAssertFalse(TaskResultPolicy.canExport(currentAccount: context.0, syncOutput: context.1, cancelled: context.2,
                                                       requestGeneration: generation, currentGeneration: generation))
        }
    }

    func testCachedBodyRefreshFailureRemainsRetryable() {
        // A failed forced refresh resets initial without discarding the last body.
        XCTAssertTrue(TaskResultPolicy.needsRefresh(initial: false, loadedMarker: "same", currentMarker: "same",
                                                     available: true, hasPendingFiles: false))
    }

    func testOlderUploadedBodyDoesNotSatisfyNewWorkbenchMarker() {
        XCTAssertFalse(TaskResultPolicy.bodyMatchesMarker(available: true, resultID: "old", requestedMarker: "new"))
        XCTAssertTrue(TaskResultPolicy.bodyMatchesMarker(available: true, resultID: "new", requestedMarker: "new"))
        XCTAssertTrue(TaskResultPolicy.bodyMatchesMarker(available: false, resultID: "", requestedMarker: "new"))
        // Older collectors may omit the workbench marker; the result endpoint remains authoritative then.
        XCTAssertTrue(TaskResultPolicy.bodyMatchesMarker(available: true, resultID: "result", requestedMarker: ""))
    }
}
