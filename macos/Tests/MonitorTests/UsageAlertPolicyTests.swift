import Foundation
import XCTest
@testable import Monitor

final class UsageAlertPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_790_467_200)

    private func timestamp(_ offset: TimeInterval = 0) -> String {
        ISO8601DateFormatter().string(from: now.addingTimeInterval(offset))
    }

    private func quota(_ overrides: [String: Any] = [:], removing: [String] = []) -> [String: Any] {
        var value: [String: Any] = [
            "key": "five_hour", "source_device_id": "mac", "source_name": "Mac mini",
            "window_minutes": 300, "resets_at": Int64(now.timeIntervalSince1970) + 900,
            "observed_at": timestamp(), "remaining_percent": 10, "stale": false,
            "availability": "observed"
        ]
        for (key, replacement) in overrides { value[key] = replacement }
        for key in removing { value.removeValue(forKey: key) }
        return value
    }

    private func summary(_ quotas: [[String: Any]], tool: String = "claude",
                         extraProviders: [[String: Any]] = []) throws -> UsageSummary {
        let body: [String: Any] = [
            "loading": false,
            "providers": [["tool": tool, "today_tokens": 999_999_999, "quotas": quotas]] + extraProviders
        ]
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(UsageSummary.self, from: JSONSerialization.data(withJSONObject: body))
    }

    private func candidates(_ quotas: [[String: Any]], enabled: Bool = true,
                            lowThreshold: Int = 10, resetMinutes: Int = 15) throws -> [UsageAlertCandidate] {
        UsageAlertPolicy.candidates(summary: try summary(quotas), enabled: enabled,
                                    lowThreshold: lowThreshold, resetMinutes: resetMinutes, now: now)
    }

    func testInclusiveThresholdsDoNotRoundAnEarlyWarningIntoExistence() throws {
        let both = try XCTUnwrap(candidates([quota()]).first)
        XCTAssertEqual(both.reasons, Set([.low, .resetSoon]))
        XCTAssertEqual(both.remainingPercent, 10)

        let justAboveLow = try XCTUnwrap(candidates([quota(["remaining_percent": 10.01])]).first)
        XCTAssertEqual(justAboveLow.reasons, Set([.resetSoon]))
        let justOutsideReset = try XCTUnwrap(candidates([quota([
            "resets_at": Int64(now.timeIntervalSince1970) + 901
        ])]).first)
        XCTAssertEqual(justOutsideReset.reasons, Set([.low]))
        XCTAssertTrue(try candidates([quota([
            "remaining_percent": 10.01, "resets_at": Int64(now.timeIntervalSince1970) + 901
        ])]).isEmpty)
    }

    func testDisabledAndZeroPreferencesSuppressOnlyTheirOwnReasons() throws {
        XCTAssertTrue(try candidates([quota()], enabled: false).isEmpty)
        XCTAssertTrue(try candidates([quota()], lowThreshold: 0, resetMinutes: 0).isEmpty)
        XCTAssertEqual(try candidates([quota()], lowThreshold: 0).first?.reasons, Set([.resetSoon]))
        XCTAssertEqual(try candidates([quota()], resetMinutes: 0).first?.reasons, Set([.low]))
        XCTAssertTrue(try candidates([quota(["remaining_percent": 0])], lowThreshold: 0, resetMinutes: 0).isEmpty)
    }

    func testLoadingSnapshotsDoNotSendAlertsFromRetainedBalances() throws {
        var loading = try summary([quota()])
        loading.loading = true
        XCTAssertTrue(UsageAlertPolicy.candidates(summary: loading, enabled: true, now: now).isEmpty)
        XCTAssertTrue(try candidates([quota()], lowThreshold: 101, resetMinutes: -1).isEmpty)
        XCTAssertEqual(try candidates([quota()], lowThreshold: -1).first?.reasons, Set([.resetSoon]))
        XCTAssertEqual(try candidates([quota()], resetMinutes: 366 * 24 * 60 + 1).first?.reasons, Set([.low]))
    }

    func testObservedZeroIsRealButMissingPercentNeverComesFromTokens() throws {
        let zero = try XCTUnwrap(candidates([quota(["remaining_percent": 0])]).first)
        XCTAssertEqual(zero.remainingPercent, 0)
        XCTAssertTrue(zero.reasons.contains(.low))
        XCTAssertTrue(try candidates([quota(removing: ["remaining_percent"])]).isEmpty)
        XCTAssertTrue(try candidates([quota(["remaining_percent": NSNull()])]).isEmpty)
        let derived = try XCTUnwrap(candidates([quota(["used_percent": 100], removing: ["remaining_percent"])]).first)
        XCTAssertEqual(derived.remainingPercent, 0)
        XCTAssertEqual(derived.reasons, Set([.low, .resetSoon]))
    }

    func testContradictoryPercentagesSuppressBothReasonsWithHalfPointTolerance() throws {
        XCTAssertTrue(try candidates([quota(["used_percent": 89.49])]).isEmpty)
        XCTAssertTrue(try candidates([quota(["used_percent": 90.51])]).isEmpty)
        for used in [89.5, 90.0, 90.5] {
            XCTAssertEqual(try candidates([quota(["used_percent": used])]).count, 1, "used=\(used)")
        }
    }

    func testObservationAgeAllowsInclusiveClockSkewAndFreshnessBoundaries() throws {
        for offset in [-900.0, 0, 60] {
            XCTAssertEqual(try candidates([quota(["observed_at": timestamp(offset)])]).count, 1, "offset=\(offset)")
        }
        for offset in [-901.0, 61] {
            XCTAssertTrue(try candidates([quota(["observed_at": timestamp(offset)])]).isEmpty, "offset=\(offset)")
        }
        for invalid in ["", "not-a-date", "1970-01-01T00:00:00Z"] {
            XCTAssertTrue(try candidates([quota(["observed_at": invalid])]).isEmpty, invalid)
        }
        XCTAssertTrue(try candidates([quota(removing: ["observed_at"])]).isEmpty)
    }

    func testStaleExpiredAndUnavailableReadingsCannotSendEitherReason() throws {
        XCTAssertTrue(try candidates([quota(["stale": true])]).isEmpty)
        XCTAssertTrue(try candidates([quota(removing: ["stale"])]).isEmpty)
        for availability in ["expired", "unavailable", "error", ""] {
            XCTAssertTrue(try candidates([quota(["availability": availability])]).isEmpty, availability)
        }
        XCTAssertEqual(try candidates([quota(removing: ["availability"])]).count, 1)
        for seconds in [Int64(0), -1] {
            XCTAssertTrue(try candidates([quota([
                "resets_at": Int64(now.timeIntervalSince1970) + seconds
            ])]).isEmpty)
        }
    }

    func testMissingIdentityWindowResetOrBalanceCannotAuthorizeAnAlert() throws {
        for field in ["source_device_id", "key", "window_minutes", "resets_at", "remaining_percent"] {
            XCTAssertTrue(try candidates([quota(removing: [field])]).isEmpty, field)
        }
        for field in ["source_device_id", "key"] {
            for blank in ["", " \n\t"] {
                XCTAssertTrue(try candidates([quota([field: blank])]).isEmpty, field)
            }
        }
        for field in ["window_minutes", "resets_at"] {
            for invalid in [Int64(0), -1, Int64.max] {
                XCTAssertTrue(try candidates([quota([field: invalid])]).isEmpty, "\(field)=\(invalid)")
            }
        }
    }

    func testResetMustFitTheObservedWindowIncludingOnlySixtySecondsOfSkew() throws {
        let latestAllowed = Int64(now.timeIntervalSince1970) + 300 * 60 + 60
        XCTAssertEqual(try candidates([quota(["resets_at": latestAllowed])]).first?.reasons, Set([.low]))
        XCTAssertTrue(try candidates([quota(["resets_at": latestAllowed + 1])]).isEmpty)
        // The plausibility bound is measured from observation, not the later evaluation time.
        XCTAssertTrue(try candidates([quota([
            "observed_at": timestamp(-900), "resets_at": latestAllowed
        ])]).isEmpty)
        let longest: Int64 = 366 * 24 * 60
        XCTAssertEqual(try candidates([quota([
            "window_minutes": longest, "resets_at": Int64(now.timeIntervalSince1970) + longest * 60
        ])]).count, 1)
        XCTAssertTrue(try candidates([quota(["window_minutes": longest + 1])]).isEmpty)
    }

    func testWindowIdentityIgnoresObservationAndDisplayNameButKeepsAllSourceDimensions() throws {
        let base = try XCTUnwrap(candidates([quota()]).first)
        // Keep the persisted identity stable across launches and future refactors.
        XCTAssertEqual(base.windowID, "monitor.usage.v1.32e390d7598860a8c5082c0eacbf9832c3a00ef8d826d26dc58aa2b852ebaac4")
        let renamed = try XCTUnwrap(candidates([quota([
            "observed_at": timestamp(-30), "source_name": "Renamed computer"
        ])]).first)
        XCTAssertEqual(base.windowID, renamed.windowID)
        XCTAssertEqual(base.id, renamed.id)
        XCTAssertEqual(base.id, base.windowID + ":low+resetSoon")
        XCTAssertEqual(renamed.sourceName, "Renamed computer")
        XCTAssertEqual(base.tool, "claude")
        XCTAssertEqual(base.sourceDeviceID, "mac")
        XCTAssertEqual(base.windowKey, "five_hour")
        XCTAssertEqual(base.windowMinutes, 300)
        XCTAssertEqual(base.resetsAt, Int64(now.timeIntervalSince1970) + 900)
        let variants: [[String: Any]] = [
            ["source_device_id": "pc"], ["key": "model-specific"], ["window_minutes": 301],
            ["resets_at": Int64(now.timeIntervalSince1970) + 899]
        ]
        for variant in variants {
            let different = try XCTUnwrap(candidates([quota(variant)]).first)
            XCTAssertNotEqual(base.windowID, different.windowID)
        }
        let codex = try XCTUnwrap(UsageAlertPolicy.candidates(
            summary: try summary([quota()], tool: "codex"), enabled: true, now: now
        ).first)
        XCTAssertNotEqual(base.windowID, codex.windowID)
        let lowOnly = try XCTUnwrap(candidates([quota()], resetMinutes: 0).first)
        XCTAssertEqual(base.windowID, lowOnly.windowID)
        XCTAssertNotEqual(base.id, lowOnly.id)
    }

    func testSameNamedDevicesAndDifferentProvidersProduceIndependentCandidates() throws {
        let quotas = [quota(), quota(["source_device_id": "pc"])]
        let input = try summary(quotas, extraProviders: [["tool": "codex", "quotas": quotas]])
        let result = UsageAlertPolicy.candidates(summary: input, enabled: true, now: now)
        XCTAssertEqual(result.count, 4)
        XCTAssertEqual(Set(result.map(\.windowID)).count, 4)
        XCTAssertEqual(Set(result.map(\.id)).count, 4)
    }

    func testNewestDuplicateCanSuppressAnOlderLowReadingRegardlessOfArrayOrder() throws {
        let older = quota(["remaining_percent": 1, "observed_at": timestamp(-60)])
        let newer = quota(["remaining_percent": 80])
        for readings in [[older, newer], [newer, older]] {
            XCTAssertTrue(try candidates(readings, resetMinutes: 0).isEmpty)
            let resetOnly = try XCTUnwrap(candidates(readings).first)
            XCTAssertEqual(resetOnly.remainingPercent, 80)
            XCTAssertEqual(resetOnly.reasons, Set([.resetSoon]))
            XCTAssertEqual(try candidates(readings).count, 1)
        }
    }

    func testEqualObservationContradictionsSuppressRatherThanDependOnArrayOrder() throws {
        let low = quota(["remaining_percent": 1])
        let high = quota(["remaining_percent": 80])
        XCTAssertTrue(try candidates([low, high]).isEmpty)
        XCTAssertTrue(try candidates([high, low]).isEmpty)
        XCTAssertEqual(try candidates([low, low]).count, 1)
    }

    func testNewestUnusableDuplicateCannotResurrectAnOlderWarning() throws {
        let older = quota(["observed_at": timestamp(-60)])
        for newer in [quota(["stale": true]), quota(["availability": "expired"]), quota(removing: ["remaining_percent"])] {
            XCTAssertTrue(try candidates([older, newer]).isEmpty)
            XCTAssertTrue(try candidates([newer, older]).isEmpty)
        }
    }

    func testReceiptIsRecordedOnlyAfterDeliveryAndKeepsNewReasonsPending() throws {
        let combined = try XCTUnwrap(candidates([quota()]).first)
        let lowOnly = try XCTUnwrap(candidates([quota()], resetMinutes: 0).first)
        var receipts = UsageAlertReceipts()
        XCTAssertEqual(receipts.pending(combined)?.reasons, Set([.low, .resetSoon]))
        // Asking for an alert does not consume it if notification delivery fails.
        XCTAssertEqual(receipts.pending(combined)?.reasons, Set([.low, .resetSoon]))
        receipts.recordDelivered(lowOnly)
        XCTAssertNil(receipts.pending(lowOnly))
        let pendingReset = try XCTUnwrap(receipts.pending(combined))
        XCTAssertEqual(pendingReset.windowID, combined.windowID)
        XCTAssertEqual(pendingReset.reasons, Set([.resetSoon]))
        XCTAssertNotEqual(pendingReset.id, combined.id)
        XCTAssertNotEqual(pendingReset.id, lowOnly.id)
        receipts.recordDelivered(pendingReset)
        XCTAssertNil(receipts.pending(combined))
    }

    func testReceiptCodableRoundTripPreservesDeliveredReasonsAndWindowIsolation() throws {
        let combined = try XCTUnwrap(candidates([quota()]).first)
        let resetOnly = try XCTUnwrap(candidates([quota()], lowThreshold: 0).first)
        var receipts = UsageAlertReceipts()
        receipts.recordDelivered(resetOnly)
        var restored = try JSONDecoder().decode(UsageAlertReceipts.self, from: JSONEncoder().encode(receipts))
        XCTAssertNil(restored.pending(resetOnly))
        let pendingLow = try XCTUnwrap(restored.pending(combined))
        XCTAssertEqual(pendingLow.reasons, Set([.low]))
        restored.recordDelivered(pendingLow)
        XCTAssertNil(restored.pending(combined))
        for variant in [["source_device_id": "other"] as [String: Any], ["resets_at": Int64(now.timeIntervalSince1970) + 899]] {
            let independent = try XCTUnwrap(candidates([quota(variant)]).first)
            XCTAssertEqual(restored.pending(independent)?.reasons, Set([.low, .resetSoon]))
        }
    }

    func testPendingBatchFiltersDeliveredWindowsBeforeApplyingDeliveryLimit() throws {
        let all = try candidates((0..<12).map { quota(["source_device_id": "computer-\($0)"]) })
        XCTAssertEqual(all.count, 12)
        var receipts = UsageAlertReceipts()
        let first = receipts.pendingBatch(all)
        XCTAssertEqual(first, Array(all.prefix(8)))
        for candidate in first { receipts.recordDelivered(candidate) }

        let second = receipts.pendingBatch(all)
        XCTAssertEqual(second, Array(all.dropFirst(8)))
        XCTAssertEqual(receipts.pendingBatch(all, limit: 2), Array(second.prefix(2)))
        for candidate in second { receipts.recordDelivered(candidate) }
        XCTAssertTrue(receipts.pendingBatch(all).isEmpty)

        let freshReceipts = UsageAlertReceipts()
        XCTAssertTrue(freshReceipts.pendingBatch(all, limit: 0).isEmpty)
        XCTAssertTrue(freshReceipts.pendingBatch(all, limit: -1).isEmpty)
    }

    func testReceiptPruningUsesActualLongWindowResetInsteadOfAWeeklyRetentionLimit() throws {
        let duration: Int64 = 30 * 24 * 60
        let reset = Int64(now.timeIntervalSince1970) + duration * 60
        let longWindow = try XCTUnwrap(candidates([quota([
            "key": "monthly", "window_minutes": duration, "resets_at": reset
        ])]).first)
        var receipts = UsageAlertReceipts()
        receipts.recordDelivered(longWindow)
        receipts.prune(now: now.addingTimeInterval(8 * 24 * 60 * 60))
        XCTAssertNil(receipts.pending(longWindow))
        receipts.prune(now: Date(timeIntervalSince1970: TimeInterval(reset - 1)))
        XCTAssertNil(receipts.pending(longWindow))
        receipts.prune(now: Date(timeIntervalSince1970: TimeInterval(reset + 1)))
        // pending is a ledger lookup; the policy separately rejects this now-expired reading.
        XCTAssertEqual(receipts.pending(longWindow)?.reasons, Set([.low]))
    }
}
