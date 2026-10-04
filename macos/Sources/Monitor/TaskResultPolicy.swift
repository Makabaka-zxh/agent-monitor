import Foundation

/// Decisions shared by the detail lifecycle and its race-condition regression tests.
enum TaskResultPolicy {
    static func needsRefresh(initial: Bool, loadedMarker: String?, currentMarker: String,
                             available: Bool?, hasPendingFiles: Bool) -> Bool {
        !initial || loadedMarker != currentMarker || available != true || hasPendingFiles
    }

    static func acceptsResponse(requestGeneration: UUID, currentGeneration: UUID,
                                requestedMarker: String, currentMarker: String,
                                currentAccount: Bool, syncOutput: Bool, cancelled: Bool) -> Bool {
        requestGeneration == currentGeneration && requestedMarker == currentMarker
            && currentAccount && syncOutput && !cancelled
    }

    static func canExport(currentAccount: Bool, syncOutput: Bool, cancelled: Bool,
                          requestGeneration: UUID, currentGeneration: UUID) -> Bool {
        currentAccount && syncOutput && !cancelled && requestGeneration == currentGeneration
    }

    static func bodyMatchesMarker(available: Bool, resultID: String?, requestedMarker: String) -> Bool {
        !available || requestedMarker.isEmpty || resultID == requestedMarker
    }
}
