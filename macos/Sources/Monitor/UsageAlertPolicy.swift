import CryptoKit
import Foundation

enum UsageAlertReason: String, Codable, Hashable, Sendable {
    case low
    case resetSoon
}

struct UsageAlertCandidate: Identifiable, Equatable, Sendable {
    let windowID: String
    let tool: String
    let sourceDeviceID: String
    let sourceName: String?
    let windowKey: String
    let windowMinutes: Int64
    let resetsAt: Int64
    let remainingPercent: Double
    var reasons: Set<UsageAlertReason>

    var id: String {
        windowID + ":" + reasons.map(\.rawValue).sorted().joined(separator: "+")
    }

    fileprivate func receiptID(for reason: UsageAlertReason) -> String {
        windowID + ":" + reason.rawValue
    }
}

/// Store this ledger inside the caller's account-fingerprint scope. It contains
/// no tokens or notification body. Generating candidates never consumes a receipt.
struct UsageAlertReceipts: Codable, Equatable, Sendable {
    private(set) var delivered: [String: Int64] = [:]

    init() {}

    func pending(_ candidate: UsageAlertCandidate) -> UsageAlertCandidate? {
        var result = candidate
        result.reasons = Set(candidate.reasons.filter { delivered[candidate.receiptID(for: $0)] == nil })
        return result.reasons.isEmpty ? nil : result
    }

    func pendingBatch(_ candidates: [UsageAlertCandidate], limit: Int = 8) -> [UsageAlertCandidate] {
        guard limit > 0 else { return [] }
        // Delivered windows must not occupy the cap and starve later sources.
        return Array(candidates.compactMap { pending($0) }.prefix(limit))
    }

    /// Call only after the notification was successfully submitted, and after
    /// rechecking that the account and enabled preferences are still current.
    mutating func recordDelivered(_ candidate: UsageAlertCandidate) {
        for reason in candidate.reasons {
            delivered[candidate.receiptID(for: reason)] = candidate.resetsAt
        }
    }

    mutating func prune(now: Date = Date()) {
        guard now.timeIntervalSince1970.isFinite else { return }
        // A fixed retention period would incorrectly repeat alerts in long windows.
        delivered = delivered.filter { Double($0.value) > now.timeIntervalSince1970 }
    }
}

/// Pure decisions over actual quota readings, independent of notification APIs.
/// Both reasons require a fresh balance; an elapsed reset never implies recovery.
enum UsageAlertPolicy {
    private static let maximumWindowMinutes: Int64 = 366 * 24 * 60

    static func candidates(
        summary: UsageSummary,
        enabled: Bool,
        lowThreshold: Int = 10,
        resetMinutes: Int = 15,
        now: Date = Date()
    ) -> [UsageAlertCandidate] {
        guard enabled, !summary.loading, now.timeIntervalSince1970.isFinite else { return [] }
        let lowEnabled = (1...100).contains(lowThreshold)
        let resetEnabled = resetMinutes > 0 && resetMinutes <= Int(maximumWindowMinutes)
        guard lowEnabled || resetEnabled else { return [] }

        var windows: [Window: [UsageQuota]] = [:]
        for provider in summary.providers where ["codex", "claude"].contains(provider.tool) {
            for quota in provider.quotas {
                guard let window = Window(tool: provider.tool, quota: quota) else { continue }
                windows[window, default: []].append(quota)
            }
        }

        var results: [UsageAlertCandidate] = []
        for (window, readings) in windows {
            // Never let an older low reading override a newer balance. Missing
            // observation times or conflicting equally recent rows are ambiguous.
            guard readings.allSatisfy({ $0.observedDate != nil }),
                  let latest = readings.compactMap(\.observedDate).max() else { continue }
            let newest = readings.filter { $0.observedDate == latest }
            guard let quota = newest.first,
                  let remaining = eligibleRemaining(quota, window: window, now: now),
                  newest.allSatisfy({ eligibleRemaining($0, window: window, now: now) == remaining }) else { continue }

            var reasons: Set<UsageAlertReason> = []
            if lowEnabled && remaining <= Double(lowThreshold) { reasons.insert(.low) }
            let untilReset = Double(window.resetsAt) - now.timeIntervalSince1970
            if resetEnabled && untilReset > 0 && untilReset <= Double(resetMinutes) * 60 {
                reasons.insert(.resetSoon)
            }
            guard !reasons.isEmpty else { continue }
            results.append(UsageAlertCandidate(
                windowID: window.id,
                tool: window.tool,
                sourceDeviceID: window.source,
                sourceName: quota.sourceName,
                windowKey: window.key,
                windowMinutes: window.minutes,
                resetsAt: window.resetsAt,
                remainingPercent: remaining,
                reasons: reasons
            ))
        }
        return results.sorted { $0.windowID < $1.windowID }
    }

    private static func eligibleRemaining(_ quota: UsageQuota, window: Window, now: Date) -> Double? {
        guard quota.availability == nil || quota.availability == "observed",
              !quota.isStale(now: now), !quota.isExpired(now: now),
              let observed = quota.observedDate,
              let remaining = UsageFormat.percent(quota.remaining),
              let reset = quota.resetDate, reset > now,
              reset.timeIntervalSince(observed) <= Double(window.minutes) * 60 + 60 else { return nil }
        // Both supplied percentages must describe the same reading. Small display
        // rounding differences are harmless; contradictory balances are not usable.
        if let used = quota.usedPercent, let remaining = quota.remainingPercent,
           abs(used + remaining - 100) > 0.5 { return nil }
        return remaining
    }

    private struct Window: Hashable {
        let tool: String
        let source: String
        let key: String
        let minutes: Int64
        let resetsAt: Int64

        init?(tool: String, quota: UsageQuota) {
            guard let source = quota.sourceDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !source.isEmpty, source.utf8.count <= 256,
                  let minutes = quota.windowMinutes, (1...UsageAlertPolicy.maximumWindowMinutes).contains(minutes),
                  let resetsAt = quota.resetsAt else { return nil }
            let key = quota.key.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, key.utf8.count <= 256 else { return nil }
            self.tool = tool
            self.source = source
            self.key = key
            self.minutes = minutes
            self.resetsAt = resetsAt
        }

        var id: String {
            // UTF-8 length framing avoids separator collisions. SHA-256 is stable
            // across processes; Swift's randomized hashValue is never persisted.
            let fields = [tool, source, key, String(minutes), String(resetsAt)]
            let identity = fields.map { "\($0.utf8.count):\($0)" }.joined()
            let digest = SHA256.hash(data: Data(identity.utf8))
            return "monitor.usage.v1." + digest.map { String(format: "%02x", $0) }.joined()
        }
    }
}
