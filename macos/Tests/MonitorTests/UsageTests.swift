import Foundation
import XCTest
@testable import Monitor

final class UsageTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ text: String) throws -> T {
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(type, from: Data(text.utf8))
    }
    private func date(_ value: String) throws -> Date { try XCTUnwrap(MonitorDate.parse(value)) }
    private let full = #"""
    {"day":"2026-09-27","timezone":"Asia/Shanghai","loading":false,"history_included":true,
     "providers":[
       {"tool":"codex","today_tokens":0,"session_count":1,"observed_at":"2026-09-27T00:00:00Z","coverage":"partial",
        "history":{"start_day":"2026-09-26","end_day":"2026-09-27","timezone":"Asia/Shanghai","days":[
          {"day":"2026-09-26","tokens":null,"coverage":"unavailable"},{"day":"2026-09-27","tokens":0,"coverage":"partial"}]},
        "quotas":[{"key":"weekly","label":"7 天","source_device_id":"mac","source_name":"Mac mini","window_minutes":10080,"used_percent":88,"remaining_percent":12,"resets_at":1790899200,"observed_at":"2026-09-27T00:00:00Z","stale":false}]},
       {"tool":"claude","today_tokens":41,"session_count":2,"coverage":"partial",
        "history":{"start_day":"2026-09-26","end_day":"2026-09-27","timezone":"Asia/Shanghai","days":[
          {"day":"2026-09-26","tokens":40,"coverage":"partial"},{"day":"2026-09-27","tokens":41,"coverage":"partial"}]},
        "quotas":[{"key":"five_hour","source_device_id":"pc","source_name":"Makabaka","window_minutes":300,"used_percent":100,"remaining_percent":0,"resets_at":1790460000,"observed_at":"2026-09-27T00:00:00Z","stale":false}]}],
     "quota_checks":[{"tool":"claude","source_device_id":"pc","source_name":"Makabaka","state":"no_live_data","last_attempt_at":"2026-09-27T00:05:00Z","retry_after_seconds":600}]}
    """#

    func testServerShapePreservesObservedZeroAndSeparatesAttempts() throws {
        let value = try decode(UsageSummary.self, full)
        XCTAssertTrue(value.hasCompleteHistory)
        let codex = try XCTUnwrap(value.provider(tool: "codex"))
        XCTAssertEqual(codex.todayTokens, 0)
        XCTAssertEqual(codex.history?.days.count, 2)
        XCTAssertNil(codex.history?.days.first?.tokens)
        XCTAssertEqual(codex.history?.days.last?.tokens, 0)
        let quota = try XCTUnwrap(codex.quotas.first)
        XCTAssertEqual(quota.sourceDeviceId, "mac")
        XCTAssertEqual(quota.remaining, 12)
        XCTAssertEqual(quota.windowMinutes, 10080)
        XCTAssertEqual(quota.windowTitle, "每周额度")
        let check = try XCTUnwrap(value.quotaChecks.first)
        XCTAssertEqual(check.lastAttemptAt, "2026-09-27T00:05:00Z")
        XCTAssertEqual(value.provider(tool: "claude")?.quotas.first?.observedAt, "2026-09-27T00:00:00Z")
        XCTAssertEqual(check.retryAfterSeconds, 600)
        XCTAssertTrue(check.matches(tool: "claude", sourceId: "device:pc"))
        XCTAssertFalse(check.matches(tool: "claude", sourceId: "device:mac"))
        XCTAssertFalse(check.matches(tool: "codex", sourceId: nil))
    }

    func testInvalidNumericReadingsRemainUnknownRatherThanZero() throws {
        for raw in ["null", "-1", "1.5", "true", #""3""#, "9223372036854775808"] {
            let day = try decode(UsageDay.self, "{\"day\":\"2026-09-27\",\"coverage\":\"partial\",\"tokens\":\(raw)}")
            XCTAssertNil(day.tokens, raw)
        }
        XCTAssertEqual(try decode(UsageDay.self, #"{"day":"2026-09-27","tokens":0}"#).tokens, 0)
        XCTAssertNil(try decode(UsageDay.self, #"{"day":"2026-09-27","tokens":0,"coverage":"unavailable"}"#).tokens)
        let invalid = try decode(UsageQuota.self, #"{"key":"x","used_percent":101,"remaining_percent":-1,"window_minutes":-5,"resets_at":1790460000000,"stale":"false"}"#)
        XCTAssertNil(invalid.remaining)
        XCTAssertNil(invalid.windowMinutes)
        XCTAssertNil(invalid.resetDate)
        XCTAssertTrue(invalid.stale)
        let fallback = try decode(UsageQuota.self, #"{"key":"x","used_percent":0,"remaining_percent":"80"}"#)
        XCTAssertEqual(fallback.remaining, 100)
        XCTAssertNil(UsageFormat.percent(.nan))
        XCTAssertNil(UsageFormat.percent(.infinity))
        XCTAssertEqual(UsageFormat.percentage(0), "0%")
    }

    func testCompactResponseKeepsChartButCannotResurrectProviderOrQuotaSource() throws {
        let previous = try decode(UsageSummary.self, full)
        let compact = try decode(UsageSummary.self, #"""
        {"day":"2026-09-28","timezone":"Asia/Shanghai","history_included":false,"loading":false,
         "providers":[{"tool":"codex","today_tokens":7,"coverage":"partial","quotas":[
            {"key":"weekly","source_device_id":"replacement","source_name":"New Mac","window_minutes":10080,"remaining_percent":20,"stale":false}]}]}
        """#)
        let merged = compact.merging(previous: previous)
        XCTAssertEqual(merged.day, "2026-09-28")
        XCTAssertNil(merged.provider(tool: "claude"))
        let codex = try XCTUnwrap(merged.provider(tool: "codex"))
        XCTAssertEqual(codex.todayTokens, 7)
        XCTAssertEqual(codex.history?.endDay, "2026-09-27")
        XCTAssertEqual(codex.quotas.map(\.sourceDeviceId), ["replacement"])
        XCTAssertEqual(codex.history?.days.last?.tokens, 0)
        XCTAssertNil(codex.history?.tokens(on: try date("2026-09-28T00:00:00Z"), now: try date("2026-09-28T01:00:00Z")))
        XCTAssertTrue(merged.quotaChecks.isEmpty)
        XCTAssertFalse(merged.hasCompleteHistory)
    }

    func testLoadingPlaceholderRetainsHistoryWithoutClaimingNewHistory() throws {
        let previous = try decode(UsageSummary.self, full)
        let loading = try decode(UsageSummary.self, #"{"day":"2026-09-28","timezone":"Asia/Shanghai","loading":true,"history_included":false,"providers":[],"quota_checks":[]}"#)
        let merged = loading.merging(previous: previous)
        XCTAssertTrue(merged.loading)
        XCTAssertFalse(merged.hasCompleteHistory)
        XCTAssertEqual(merged.day, "2026-09-27")
        XCTAssertNotEqual(merged.day, loading.day)
        XCTAssertEqual(merged.provider(tool: "claude")?.history?.days.last?.tokens, 41)
        XCTAssertEqual(merged.provider(tool: "claude")?.history?.endDay, "2026-09-27")
        XCTAssertTrue(merged.quotaChecks.isEmpty)
        XCTAssertTrue(loading.merging(previous: nil).providers.isEmpty)
        let removed = try decode(UsageSummary.self, #"{"day":"2026-09-28","timezone":"Asia/Shanghai","loading":false,"history_included":false,"providers":[]}"#)
        XCTAssertTrue(removed.merging(previous: previous).providers.isEmpty)
    }

    func testFullHistoryReplacesCachedHistoryAndExplicitEmptyChecksClear() throws {
        let previous = try decode(UsageSummary.self, full)
        let next = try decode(UsageSummary.self, #"""
        {"day":"2026-09-28","timezone":"Asia/Shanghai","history_included":true,"loading":false,"quota_checks":[],
         "providers":[{"tool":"codex","today_tokens":9,"quotas":[],"history":{"start_day":"2026-09-28","end_day":"2026-09-28","timezone":"Asia/Shanghai","days":[{"day":"2026-09-28","tokens":9,"coverage":"partial"}]}}]}
        """#)
        let merged = next.merging(previous: previous)
        XCTAssertTrue(merged.hasCompleteHistory)
        XCTAssertEqual(merged.provider(tool: "codex")?.history?.days.count, 1)
        XCTAssertEqual(merged.provider(tool: "codex")?.history?.days.first?.tokens, 9)
        XCTAssertNil(merged.provider(tool: "claude"))
        XCTAssertTrue(merged.quotaChecks.isEmpty)
        XCTAssertTrue(merged.provider(tool: "codex")?.quotas.isEmpty == true)
    }

    func testSourceGroupingDoesNotMergeAnonymousOrSameNamedComputers() throws {
        let provider = try decode(UsageProvider.self, #"""
        {"tool":"codex","quotas":[
          {"key":"weekly","source_name":"Same","window_minutes":10080,"remaining_percent":10},
          {"key":"weekly","source_name":"Same","window_minutes":10080,"remaining_percent":20},
          {"key":"weekly","source_device_id":"a","source_name":"Same","window_minutes":10080,"remaining_percent":30},
          {"key":"five_hour","source_device_id":"a","source_name":"Same","window_minutes":300,"remaining_percent":40},
          {"key":"weekly","source_device_id":"b","source_name":"Same","window_minutes":10080,"remaining_percent":50}]}
        """#)
        let sources = provider.sources()
        XCTAssertEqual(sources.count, 4)
        XCTAssertEqual(Set(sources.map(\.id)).count, 4)
        let a = try XCTUnwrap(sources.first { $0.id == "device:a" })
        XCTAssertEqual(a.quotas.count, 2)
        XCTAssertEqual(a.preferredQuota(minutes: 10080)?.remaining, 30)
        XCTAssertEqual(a.preferredQuota(minutes: 300)?.remaining, 40)
        XCTAssertNil(a.preferredQuota(minutes: 60))
    }

    func testQuotaFreshnessExpiresLocallyAndNeverAssumesResetRestoresBalance() throws {
        let now = try date("2026-09-27T00:00:00Z")
        let reset = Int64(now.timeIntervalSince1970 + 3600)
        let quota = try decode(UsageQuota.self, "{\"key\":\"five_hour\",\"remaining_percent\":0,\"window_minutes\":300,\"resets_at\":\(reset),\"observed_at\":\"2026-09-27T00:00:00Z\",\"stale\":false}")
        XCTAssertFalse(quota.isStale(now: now.addingTimeInterval(900)))
        XCTAssertTrue(quota.isStale(now: now.addingTimeInterval(900.001)))
        XCTAssertEqual(quota.remainingText(now: now), "剩余 0%")
        XCTAssertEqual(quota.remainingText(now: now.addingTimeInterval(901)), "上次剩余 0%")
        XCTAssertTrue(quota.resetText(now: now.addingTimeInterval(901)).contains("预计"))
        XCTAssertEqual(quota.remainingText(now: now.addingTimeInterval(3600)), "待更新")
        XCTAssertEqual(quota.remaining, 0)
        XCTAssertEqual(quota.resetText(now: now.addingTimeInterval(3600)), "已到重置时间，等待新额度")
        XCTAssertTrue(quota.isStale(now: now.addingTimeInterval(-61)))
        XCTAssertFalse(quota.isStale(now: now.addingTimeInterval(-60)))
        XCTAssertEqual(quota.remainingText(now: now, failed: true), "上次剩余 0%")
        let unavailable = try decode(UsageQuota.self, #"{"key":"missing"}"#)
        XCTAssertTrue(unavailable.isStale(now: now))
        XCTAssertEqual(unavailable.remainingText(now: now), "未提供")
    }

    func testPreferredWindowUsesFreshnessBeforeRecencyAndExactWindowLength() throws {
        let now = try date("2026-09-27T00:00:00Z")
        let provider = try decode(UsageProvider.self, #"""
        {"tool":"claude","quotas":[
          {"key":"older-fresh","source_device_id":"a","window_minutes":300,"remaining_percent":20,"observed_at":"2026-09-26T23:59:00Z","stale":false},
          {"key":"newer-stale","source_device_id":"a","window_minutes":300,"remaining_percent":70,"observed_at":"2026-09-27T00:00:00Z","stale":true},
          {"key":"model-specific","source_device_id":"a","window_minutes":60,"remaining_percent":95,"observed_at":"2026-09-27T00:00:00Z","stale":false}]}
        """#)
        let source = try XCTUnwrap(provider.sources(now: now).first)
        XCTAssertEqual(source.preferredQuota(minutes: 300, now: now)?.key, "older-fresh")
        XCTAssertNil(source.preferredQuota(minutes: 10080, now: now))
    }

    func testShanghaiDateWeekAndSelectionBoundaries() throws {
        let utcBeforeMidnight = try date("2026-09-26T15:59:59Z")
        let utcAfterMidnight = try date("2026-09-26T16:00:00Z")
        XCTAssertEqual(UsageCalendar.day(utcBeforeMidnight), "2026-09-26")
        XCTAssertEqual(UsageCalendar.day(utcAfterMidnight), "2026-09-27")
        let sunday = try XCTUnwrap(UsageCalendar.date("2026-09-27"))
        let monday = UsageCalendar.monday(sunday)
        XCTAssertEqual(UsageCalendar.day(monday), "2026-09-21")
        XCTAssertEqual(UsageCalendar.range(monday), "9.21 – 9.27")
        for invalid in ["2026-02-30", "2026-13-01", "2026-9-27", "0000-01-01", "2026-09-27extra"] { XCTAssertNil(UsageCalendar.date(invalid)) }
        let start = try XCTUnwrap(UsageCalendar.date("2026-09-24"))
        XCTAssertEqual(UsageCalendar.clampWeek(selected: UsageCalendar.date("2020-01-01"), start: start, today: sunday), monday)
        XCTAssertEqual(UsageCalendar.clampWeek(selected: UsageCalendar.date("2030-01-01"), start: start, today: sunday), monday)
        XCTAssertEqual(UsageCalendar.selection(selected: start, week: monday, start: start, end: sunday), start)
        XCTAssertEqual(UsageCalendar.selection(selected: monday, week: monday, start: start, end: sunday), sunday)
    }

    func testMalformedHistoryAndFutureDaysDoNotBecomeMeasuredValues() throws {
        let history = try decode(UsageHistory.self, #"""
        {"start_day":"2026-09-26","end_day":"2026-09-28","timezone":"Asia/Shanghai","days":[
          {"day":"2026-09-26","tokens":7},{"day":"2026-09-26","tokens":8},
          {"day":"2026-09-27","tokens":0},{"day":"2026-09-28","tokens":99},
          {"day":"2026-09-99","tokens":5},{"day":"2026-09-25","tokens":5}]}
        """#)
        let now = try date("2026-09-27T00:00:00Z")
        XCTAssertTrue(history.isValid)
        XCTAssertEqual(history.days.map(\.day), ["2026-09-27", "2026-09-28"])
        XCTAssertNil(history.tokens(on: try date("2026-09-26T00:00:00Z"), now: now))
        XCTAssertEqual(history.tokens(on: now, now: now), 0)
        XCTAssertNil(history.tokens(on: try date("2026-09-28T00:00:00Z"), now: now))
        let wrongZone = try decode(UsageHistory.self, #"{"start_day":"2026-09-27","end_day":"2026-09-27","timezone":"UTC","days":[{"day":"2026-09-27","tokens":1}]}"#)
        XCTAssertFalse(wrongZone.isValid)
        XCTAssertTrue(wrongZone.days.isEmpty)
        let tooLong = try decode(UsageHistory.self, #"{"start_day":"2025-09-27","end_day":"2026-09-27","timezone":"Asia/Shanghai","days":[]}"#)
        XCTAssertFalse(tooLong.isValid)
    }

    func testFormattingDoesNotRoundTokensUpOrInventQuotaPercent() {
        XCTAssertEqual(UsageFormat.compact(nil), "—")
        XCTAssertEqual(UsageFormat.compact(0), "0")
        XCTAssertEqual(UsageFormat.compact(19999), "1.9 万")
        XCTAssertEqual(UsageFormat.compact(100000000), "1 亿")
        XCTAssertEqual(UsageFormat.exact(1234567), "1,234,567")
        XCTAssertEqual(UsageFormat.percentage(nil), "未提供")
        XCTAssertEqual(UsageFormat.percentage(12.34), "12.3%")
        XCTAssertEqual(UsageFormat.ratio(nil, maximum: 100), 0)
        XCTAssertEqual(UsageFormat.ratio(100, maximum: 200), 0.5)
        XCTAssertEqual(UsageFormat.ratio(300, maximum: 200), 1)
        XCTAssertEqual(UsageFormat.quotaCheck("raw diagnostic or path"), "暂时无法读取新额度")
    }
}
