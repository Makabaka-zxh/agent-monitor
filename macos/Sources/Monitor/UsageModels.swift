import Foundation

// Daily observed tokens and a provider's rolling quota are separate measurements.
struct UsageSummary: Codable {
    var day: String?
    var timezone: String?
    var loading: Bool
    var historyIncluded: Bool
    var providers: [UsageProvider]
    var quotaChecks: [UsageQuotaCheck]
    var quotaChecksIncluded: Bool

    private enum CodingKeys: String, CodingKey { case day, timezone, loading, historyIncluded, providers, quotaChecks }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        day = try? c.decode(String.self, forKey: .day)
        timezone = try? c.decode(String.self, forKey: .timezone)
        loading = (try? c.decode(Bool.self, forKey: .loading)) ?? false
        historyIncluded = (try? c.decode(Bool.self, forKey: .historyIncluded)) ?? false
        let decoded = (try? c.decode([UsageProvider].self, forKey: .providers)) ?? []
        let counts = Dictionary(grouping: decoded, by: \.tool).mapValues(\.count)
        providers = decoded.filter { ["codex", "claude"].contains($0.tool) && counts[$0.tool] == 1 }
        quotaChecksIncluded = c.contains(.quotaChecks)
        quotaChecks = (try? c.decode([UsageQuotaCheck].self, forKey: .quotaChecks)) ?? []
    }
    var hasCompleteHistory: Bool {
        !loading && historyIncluded && timezone == UsageCalendar.timezone.identifier
            && UsageCalendar.date(day) != nil && providers.allSatisfy { $0.history?.isValid == true }
    }
    func provider(tool: String) -> UsageProvider? { providers.first { $0.tool == tool } }

    /// Account and request-generation checks belong to the caller. Never combine different logins.
    func merging(previous: UsageSummary?) -> UsageSummary {
        guard let previous else { return self }
        if loading {
            // The startup placeholder has no authority to erase a previously received chart.
            // Retain the snapshot day as well as each history's endDay: after midnight,
            // yesterday's cached todayTokens must not masquerade as today's measurement.
            var retained = previous
            retained.loading = true
            if quotaChecksIncluded { retained.quotaChecks = quotaChecks; retained.quotaChecksIncluded = true }
            return retained
        }
        if hasCompleteHistory { return self }
        var merged = self
        // Only providers present in the new authorized response survive. Quotas are never copied.
        merged.providers = providers.map { provider in
            var value = provider
            value.history = previous.provider(tool: provider.tool)?.history
            return value
        }
        if !quotaChecksIncluded {
            merged.quotaChecks = previous.quotaChecks.filter { check in
                guard let source = check.sourceDeviceId, !source.isEmpty else { return false }
                return providers.first { $0.tool == check.tool }?.quotas.contains { $0.sourceDeviceId == source } == true
            }
        }
        return merged
    }
}

struct UsageProvider: Codable, Identifiable {
    let tool: String
    let todayTokens: Int64?
    let sessionCount: Int64?
    let observedAt: String?
    let coverage: String?
    let quotas: [UsageQuota]
    var history: UsageHistory?
    var id: String { tool }
    var brand: String { tool == "claude" ? "Claude Code" : "Codex" }

    private enum CodingKeys: String, CodingKey { case tool, todayTokens, sessionCount, observedAt, coverage, quotas, history }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tool = (try? c.decode(String.self, forKey: .tool)) ?? ""
        coverage = try? c.decode(String.self, forKey: .coverage)
        todayTokens = coverage == "unavailable" ? nil : UsageFormat.count(try? c.decode(Int64.self, forKey: .todayTokens))
        sessionCount = UsageFormat.count(try? c.decode(Int64.self, forKey: .sessionCount))
        observedAt = try? c.decode(String.self, forKey: .observedAt)
        quotas = Array(((try? c.decode([UsageQuota].self, forKey: .quotas)) ?? []).prefix(200))
        history = try? c.decode(UsageHistory.self, forKey: .history)
    }
    func sources(now: Date = Date()) -> [UsageSource] {
        var ordered: [UsageSource] = []
        for (index, quota) in quotas.enumerated() {
            let source = quota.sourceDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            // Missing identity cannot authorize combining records by a shared display name.
            let id = source.isEmpty ? "unknown:\(quota.key):\(index)" : "device:\(source)"
            if let at = ordered.firstIndex(where: { $0.id == id }) {
                if !ordered[at].quotas.contains(where: { $0.id == quota.id }) { ordered[at].quotas.append(quota) }
            } else {
                let name = quota.sourceName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                ordered.append(UsageSource(id: id, name: name.isEmpty ? "来源未命名" : name, quotas: [quota]))
            }
        }
        return ordered.sorted {
            let left = $0.bestQuota(now: now), right = $1.bestQuota(now: now)
            if let left, let right {
                if left.rank(now: now) != right.rank(now: now) { return left.rank(now: now) > right.rank(now: now) }
                if left.observedDate != right.observedDate { return (left.observedDate ?? .distantPast) > (right.observedDate ?? .distantPast) }
            }
            return $0.id < $1.id
        }
    }
}

struct UsageHistory: Codable {
    let startDay: String?
    let endDay: String?
    let timezone: String?
    let days: [UsageDay]
    var isValid: Bool {
        guard timezone == UsageCalendar.timezone.identifier, let start = UsageCalendar.date(startDay), let end = UsageCalendar.date(endDay) else { return false }
        let span = UsageCalendar.calendar.dateComponents([.day], from: start, to: end).day ?? -1
        return (0..<90).contains(span)
    }
    private enum CodingKeys: String, CodingKey { case startDay, endDay, timezone, days }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        startDay = try? c.decode(String.self, forKey: .startDay)
        endDay = try? c.decode(String.self, forKey: .endDay)
        timezone = try? c.decode(String.self, forKey: .timezone)
        let decoded = (try? c.decode([UsageDay].self, forKey: .days)) ?? []
        let counts = Dictionary(grouping: decoded, by: \.day).mapValues(\.count)
        if timezone == UsageCalendar.timezone.identifier, let start = UsageCalendar.date(startDay), let end = UsageCalendar.date(endDay), start <= end {
            // Contradictory duplicate days stay unknown instead of silently double-counting.
            days = Array(decoded.filter {
                guard let date = UsageCalendar.date($0.day) else { return false }
                return date >= start && date <= end && counts[$0.day] == 1
            }.sorted { $0.day < $1.day }.suffix(90))
        } else { days = [] }
    }
    func tokens(on date: Date, now: Date = Date()) -> Int64? {
        guard isValid, UsageCalendar.today(now: date) <= UsageCalendar.today(now: now) else { return nil }
        return days.first { $0.day == UsageCalendar.day(date) }?.tokens
    }
}

struct UsageDay: Codable, Identifiable {
    let day: String
    let tokens: Int64?
    let coverage: String?
    var id: String { day }
    private enum CodingKeys: String, CodingKey { case day, tokens, coverage }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        day = (try? c.decode(String.self, forKey: .day)) ?? ""
        coverage = try? c.decode(String.self, forKey: .coverage)
        tokens = coverage == "unavailable" ? nil : UsageFormat.count(try? c.decode(Int64.self, forKey: .tokens))
    }
}

struct UsageQuota: Codable, Identifiable {
    let key: String
    let label: String?
    let usedPercent: Double?
    let remainingPercent: Double?
    let windowMinutes: Int64?
    let resetsAt: Int64?
    let observedAt: String?
    let sourceDeviceId: String?
    let sourceName: String?
    let stale: Bool
    let availability: String?
    var id: String { [sourceDeviceId ?? "", key, String(resetsAt ?? 0), observedAt ?? ""].map { "\($0.utf8.count):\($0)" }.joined() }
    var remaining: Double? { remainingPercent ?? usedPercent.map { 100 - $0 } }
    var resetDate: Date? { UsageFormat.epochDate(resetsAt) }
    var observedDate: Date? { observedAt.flatMap(MonitorDate.parse) }
    var windowTitle: String { UsageFormat.window(windowMinutes, fallback: label) }
    private enum CodingKeys: String, CodingKey { case key, label, usedPercent, remainingPercent, windowMinutes, resetsAt, observedAt, sourceDeviceId, sourceName, stale, availability }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = (try? c.decode(String.self, forKey: .key)) ?? ""
        label = try? c.decode(String.self, forKey: .label)
        usedPercent = UsageFormat.percent(try? c.decode(Double.self, forKey: .usedPercent))
        remainingPercent = UsageFormat.percent(try? c.decode(Double.self, forKey: .remainingPercent))
        windowMinutes = UsageFormat.positive(try? c.decode(Int64.self, forKey: .windowMinutes))
        let reset = UsageFormat.positive(try? c.decode(Int64.self, forKey: .resetsAt))
        resetsAt = UsageFormat.epochDate(reset) == nil ? nil : reset
        observedAt = try? c.decode(String.self, forKey: .observedAt)
        sourceDeviceId = try? c.decode(String.self, forKey: .sourceDeviceId)
        sourceName = try? c.decode(String.self, forKey: .sourceName)
        stale = (try? c.decode(Bool.self, forKey: .stale)) ?? true
        availability = try? c.decode(String.self, forKey: .availability)
    }
    func isExpired(now: Date = Date()) -> Bool { availability == "expired" || resetDate.map { $0 <= now } == true }
    func isStale(now: Date = Date()) -> Bool {
        stale || isExpired(now: now) || !UsageFormat.observationFresh(observedDate, now: now)
    }
    func rank(now: Date) -> Int {
        guard remaining != nil else { return 0 }
        return isExpired(now: now) ? 1 : isStale(now: now) ? 2 : 3
    }
    func remainingText(now: Date = Date(), failed: Bool = false) -> String {
        if isExpired(now: now) { return "待更新" }
        guard let remaining else { return "未提供" }
        return (failed || isStale(now: now) ? "上次剩余 " : "剩余 ") + UsageFormat.percentage(remaining)
    }
    func resetText(now: Date = Date(), failed: Bool = false) -> String {
        if isExpired(now: now) { return "已到重置时间，等待新额度" }
        return UsageFormat.reset(resetDate, now: now, stale: failed || isStale(now: now))
    }
}

struct UsageSource: Identifiable {
    let id: String
    let name: String
    var quotas: [UsageQuota]
    func preferredQuota(minutes: Int64, now: Date = Date()) -> UsageQuota? {
        best(quotas.filter { $0.windowMinutes == minutes }, now: now)
    }
    func bestQuota(now: Date = Date()) -> UsageQuota? { best(quotas, now: now) }
    private func best(_ items: [UsageQuota], now: Date) -> UsageQuota? {
        items.max {
            if $0.rank(now: now) != $1.rank(now: now) { return $0.rank(now: now) < $1.rank(now: now) }
            return ($0.observedDate ?? .distantPast) < ($1.observedDate ?? .distantPast)
        }
    }
}

struct UsageQuotaCheck: Codable {
    let tool: String
    let sourceDeviceId: String?
    let sourceName: String?
    let state: String
    let lastAttemptAt: String?
    let retryAfterSeconds: Int64?
    private enum CodingKeys: String, CodingKey { case tool, sourceDeviceId, sourceName, state, lastAttemptAt, retryAfterSeconds }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tool = (try? c.decode(String.self, forKey: .tool)) ?? ""
        sourceDeviceId = try? c.decode(String.self, forKey: .sourceDeviceId)
        sourceName = try? c.decode(String.self, forKey: .sourceName)
        state = (try? c.decode(String.self, forKey: .state)) ?? "unavailable"
        lastAttemptAt = try? c.decode(String.self, forKey: .lastAttemptAt)
        let interval = try? c.decode(Int64.self, forKey: .retryAfterSeconds)
        retryAfterSeconds = interval.flatMap { (300...3600).contains($0) ? $0 : nil }
    }
    func matches(tool: String, sourceId: String?) -> Bool {
        guard self.tool == tool, let sourceDeviceId, !sourceDeviceId.isEmpty else { return false }
        return sourceId == nil || sourceId == "device:" + sourceDeviceId
    }
    var title: String { UsageFormat.quotaCheck(state) }
}

enum UsageCalendar {
    static let timezone = TimeZone(identifier: "Asia/Shanghai")!
    static var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = timezone; result.locale = Locale(identifier: "en_US_POSIX"); result.firstWeekday = 2
        return result
    }
    static func date(_ text: String?) -> Date? {
        guard let text, text.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil else { return nil }
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, parts[0] >= 1,
              let value = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])), day(value) == text else { return nil }
        return value
    }
    static func day(_ date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
    static func today(now: Date = Date()) -> Date { calendar.startOfDay(for: now) }
    static func addingDays(_ days: Int, to date: Date) -> Date { calendar.date(byAdding: .day, value: days, to: date) ?? date }
    static func monday(_ date: Date) -> Date { addingDays(-((calendar.component(.weekday, from: date) + 5) % 7), to: today(now: date)) }
    static func clampWeek(selected: Date?, start: Date, today: Date) -> Date {
        min(max(monday(selected ?? today), monday(start)), monday(today))
    }
    static func selection(selected: Date?, week: Date, start: Date, end: Date) -> Date {
        let lower = max(week, start), upper = min(addingDays(6, to: week), end)
        if let selected, selected >= lower && selected <= upper { return selected }
        return upper
    }
    static func shortDay(_ date: Date) -> String { "\(calendar.component(.month, from: date)).\(calendar.component(.day, from: date))" }
    static func range(_ week: Date) -> String { "\(shortDay(week)) – \(shortDay(addingDays(6, to: week)))" }
}

enum UsageFormat {
    static func count(_ value: Int64?) -> Int64? { value.flatMap { $0 >= 0 ? $0 : nil } }
    static func positive(_ value: Int64?) -> Int64? { value.flatMap { $0 > 0 ? $0 : nil } }
    static func percent(_ value: Double?) -> Double? { value.flatMap { $0.isFinite && (0...100).contains($0) ? $0 : nil } }
    static func epochDate(_ seconds: Int64?) -> Date? {
        guard let seconds, (1...253402300799).contains(seconds) else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }
    static func observationFresh(_ observed: Date?, now: Date) -> Bool {
        guard let observed, observed.timeIntervalSince1970 > 0 else { return false }
        let age = now.timeIntervalSince(observed)
        return age >= -60 && age <= 900
    }
    static func compact(_ tokens: Int64?) -> String {
        guard let tokens = count(tokens) else { return "—" }
        if tokens < 10000 { return exact(tokens) }
        let divisor: Int64 = tokens < 100000000 ? 10000 : 100000000
        let fraction = (tokens % divisor) / (divisor / 10)
        return "\(tokens / divisor)" + (fraction == 0 ? "" : ".\(fraction)") + (divisor == 10000 ? " 万" : " 亿")
    }
    static func exact(_ tokens: Int64?) -> String {
        guard let tokens = count(tokens) else { return "—" }
        let formatter = NumberFormatter(); formatter.locale = Locale(identifier: "zh_CN"); formatter.numberStyle = .decimal; formatter.maximumFractionDigits = 0
        return formatter.string(from: NSNumber(value: tokens)) ?? String(tokens)
    }
    static func percentage(_ value: Double?) -> String {
        guard let value = percent(value) else { return "未提供" }
        let rounded = (value * 10).rounded() / 10
        return (rounded.rounded() == rounded ? String(format: "%.0f", rounded) : String(format: "%.1f", rounded)) + "%"
    }
    static func ratio(_ value: Int64?, maximum: Int64) -> Double {
        guard let value = count(value), maximum > 0 else { return 0 }
        return min(1, max(0, Double(value) / Double(maximum)))
    }
    static func window(_ minutes: Int64?, fallback: String?) -> String {
        guard let minutes = positive(minutes) else { return fallback?.isEmpty == false ? fallback! : "套餐额度" }
        if minutes % 10080 == 0 { return minutes == 10080 ? "每周额度" : "\(minutes / 10080) 周额度" }
        if minutes % 1440 == 0 { return "\(minutes / 1440) 天额度" }
        if minutes % 60 == 0 { return "\(minutes / 60) 小时额度" }
        return "\(minutes) 分钟额度"
    }
    static func reset(_ reset: Date?, now: Date, stale: Bool) -> String {
        guard let reset else { return stale ? "额度信息待更新" : "未提供重置时间" }
        if reset <= now { return "已到重置时间，等待新额度" }
        let minutes = max(1, Int(ceil(reset.timeIntervalSince(now) / 60)))
        let duration = minutes >= 1440 ? "\(minutes / 1440) 天 \(minutes % 1440 / 60) 小时"
            : minutes >= 60 ? "\(minutes / 60) 小时 \(minutes % 60) 分钟" : "\(minutes) 分钟"
        return (stale ? "预计 " : "") + duration + "后重置" + (stale ? " · 待更新" : "")
    }
    static func quotaCheck(_ state: String) -> String {
        switch state {
        case "waiting": "等待后台读取"
        case "updated": "最近一次读取成功"
        case "no_live_data": "服务方暂未返回新额度"
        case "sign_in_required": "需要在来源电脑登录 Claude Code"
        case "update_required": "来源电脑的额度读取组件需要更新"
        default: "暂时无法读取新额度"
        }
    }
}
