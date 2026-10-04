import AppKit
import SwiftUI

/// Full-history responses feed this page; source selection is deliberately quota-only.
@MainActor
struct UsageView: View {
    @ObservedObject var store: MonitorStore
    @State private var showingAlerts = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    Text("用量").font(.custom("MiSans-Regular", size: 24)).fontWeight(.semibold)
                    Spacer()
                    Button { showingAlerts = true } label: { Label("提醒", systemImage: "bell") }
                    if store.usageRefreshing { ProgressView().controlSize(.small) }
                    Button { Task { await store.refreshUsage() } } label: {
                        Label("刷新", systemImage: "arrow.clockwise")
                    }
                    .disabled(store.usageRefreshing)
                }
                if !store.usageError.isEmpty {
                    Label(store.usage == nil ? "暂时无法读取用量" : "连接暂不可用，显示上次记录", systemImage: "wifi.exclamationmark")
                        .foregroundStyle(.secondary)
                } else if store.usage == nil {
                    Text(store.usageRefreshing ? "正在读取用量…" : "等待用量记录").foregroundStyle(.secondary)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 330), spacing: 18, alignment: .top)], alignment: .leading, spacing: 18) {
                    ForEach(["codex", "claude"], id: \.self) { tool in
                        UsageProviderCard(
                            tool: tool,
                            provider: store.usage?.provider(tool: tool),
                            reportDay: store.usage?.day,
                            checks: store.usage?.quotaChecks ?? [],
                            failed: !store.usageError.isEmpty,
                            loading: store.usageRefreshing || store.usage?.loading == true
                        )
                        .id(store.tokenFingerprint + ":" + tool)
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: 1080)
            .frame(maxWidth: .infinity, alignment: .top)
        }
        .font(.custom("MiSans-Regular", size: 14))
        .navigationTitle("用量")
        .sheet(isPresented: $showingAlerts) {
            UsageAlertSettingsView(alerts: store.usageAlerts)
                .onAppear { AcceptanceProbe.shared.remindersDisplayed() }
        }
        .onAppear { AcceptanceProbe.shared.usageDisplayed(true) }
        .onDisappear { AcceptanceProbe.shared.usageDisplayed(false) }
        .onReceive(NotificationCenter.default.publisher(for: AcceptanceProbe.reminders)) { _ in
            guard AcceptanceProbe.shared.active else { return }
            showingAlerts = true
        }
        .task(id: store.tokenFingerprint) {
            while !Task.isCancelled && store.connected {
                // A previous page's cancelled request may still be unwinding.
                // Wait for its gate instead of skipping this page's first load.
                while store.usageRefreshing && !Task.isCancelled && store.connected {
                    do { try await Task.sleep(for: .milliseconds(200)) }
                    catch { return }
                }
                guard !Task.isCancelled && store.connected else { return }
                await store.refreshUsage()
                guard !Task.isCancelled else { return }
                do { try await Task.sleep(for: .seconds(30)) }
                catch { return }
            }
        }
    }
}

private struct UsageProviderCard: View {
    let tool: String
    let provider: UsageProvider?
    let reportDay: String?
    let checks: [UsageQuotaCheck]
    let failed: Bool
    let loading: Bool
    @State private var selectedWeek: Date?
    @State private var selectedDay: Date?
    @State private var showingHelp = false

    private var brand: String { tool == "claude" ? "Claude Code" : "Codex" }
    private var today: Date { UsageCalendar.today(now: Date()) }
    private var historyValid: Bool {
        guard provider?.history?.isValid == true,
              let start = UsageCalendar.date(provider?.history?.startDay) else { return false }
        return start <= today
    }
    private var firstDay: Date {
        guard historyValid, let start = UsageCalendar.date(provider?.history?.startDay) else { return today }
        return max(start, UsageCalendar.addingDays(-89, to: today))
    }
    private var week: Date { UsageCalendar.clampWeek(selected: selectedWeek, start: firstDay, today: today) }
    private var day: Date { UsageCalendar.selection(selected: selectedDay, week: week, start: firstDay, end: today) }
    private var weekDays: [Date] { (0..<7).map { UsageCalendar.addingDays($0, to: week) } }
    private var maximum: Int64 { weekDays.compactMap { tokens(on: $0) }.max() ?? 0 }
    private var caption: String {
        if day == today { return failed ? "今日上次记录" : "今日已记录" }
        return UsageCalendar.shortDay(day) + " 已记录"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(spacing: 10) {
                UsageBrand(tool: tool)
                Text(brand).font(.custom("MiSans-Regular", size: 17)).fontWeight(.semibold)
                Spacer()
                Button { showingHelp = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain).foregroundStyle(.secondary)
                    .help("\(brand) 用量说明")
                    .accessibilityLabel("查看 \(brand) 用量说明")
            }
            VStack(alignment: .leading, spacing: 5) {
                Text(caption).font(.custom("MiSans-Regular", size: 12)).foregroundStyle(.secondary)
                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    Text(tokens(on: day).map { UsageFormat.exact($0) } ?? "—")
                        .font(.custom("MiSans-Regular", size: 27)).fontWeight(.medium)
                        .monospacedDigit().lineLimit(1).minimumScaleFactor(0.65)
                        .textSelection(.enabled)
                    Text("Token").font(.custom("MiSans-Regular", size: 12)).foregroundStyle(.secondary)
                }
            }
            VStack(spacing: 8) {
                HStack {
                    Button { moveWeek(-1) } label: { Image(systemName: "chevron.left").frame(width: 26, height: 26) }
                        .disabled(week <= UsageCalendar.monday(firstDay))
                        .help("上一周").accessibilityLabel("上一周")
                    Spacer(minLength: 2)
                    Button {
                        selectedWeek = UsageCalendar.monday(today)
                        selectedDay = today
                    } label: {
                        Text((week == UsageCalendar.monday(today) ? "本周 · " : "") + UsageCalendar.range(week))
                            .font(.custom("MiSans-Regular", size: 12)).lineLimit(1)
                    }
                    .help("返回本周")
                    Spacer(minLength: 2)
                    Button { moveWeek(1) } label: { Image(systemName: "chevron.right").frame(width: 26, height: 26) }
                        .disabled(week >= UsageCalendar.monday(today))
                        .help("下一周").accessibilityLabel("下一周")
                }
                .buttonStyle(.plain)
                HStack(spacing: 3) {
                    ForEach(Array(weekDays.enumerated()), id: \.offset) { index, date in
                        UsageChartBar(
                            day: UsageCalendar.day(date),
                            weekday: ["一", "二", "三", "四", "五", "六", "日"][index],
                            shortDate: UsageCalendar.shortDay(date),
                            amount: tokens(on: date).map { UsageFormat.exact($0) } ?? "—",
                            tokens: tokens(on: date), maximum: maximum,
                            available: date >= firstDay && date <= today,
                            selected: date == day,
                            choose: { selectedDay = date }
                        )
                    }
                }
            }
            if !historyValid && loading {
                Text("正在读取历史…").font(.custom("MiSans-Regular", size: 12)).foregroundStyle(.secondary)
            }
            Divider()
            UsageQuotaSection(tool: tool, provider: provider, checks: checks, failed: failed)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.quaternary, lineWidth: 0.7) }
        .sheet(isPresented: $showingHelp) { UsageHelpSheet(title: brand + " 用量", text: providerHelp) }
    }

    private func tokens(on date: Date) -> Int64? {
        if historyValid, date >= firstDay, date <= today,
           let end = UsageCalendar.date(provider?.history?.endDay), date <= end,
           let value = provider?.history?.tokens(on: date) { return value }
        // Startup/compact responses may carry today's numeric record without history.
        // A previous day's cached total must never be relabelled as today's usage.
        if date == today, UsageCalendar.date(reportDay) == today { return provider?.todayTokens }
        return nil
    }
    private func moveWeek(_ delta: Int) {
        let next = UsageCalendar.clampWeek(selected: UsageCalendar.addingDays(delta * 7, to: week), start: firstDay, today: today)
        selectedWeek = next
        selectedDay = UsageCalendar.selection(selected: nil, week: next, start: firstDay, end: today)
    }
    private var providerHelp: String {
        var lines = ["柱状图汇总所有已授权电脑的已记录 Token，切换额度来源不会改变图表。"]
        if let count = provider?.sessionCount { lines.append("已记录 \(count) 个对话。") }
        if let observed = provider?.observedAt, let date = MonitorDate.parse(observed) {
            lines.append("最近记录：" + UsageViewText.observed(date))
        }
        lines.append("按北京时间统计，周一至周日为一周，最多查看最近 90 天。点选柱状图可查看该日准确数字；— 表示未提供，0 表示已记录为零。历史可能有缺口，并非完整账单。")
        lines.append("套餐周额度是服务方提供的滚动窗口，与图表的日历周不同。百分比只使用真实额度读数，不用 Token 推算。不同来源电脑可能登录不同账号，额度不合并。")
        lines.append("旧数据会标为上次记录。到达重置时间后等待新额度确认，不会自动显示为已恢复。缓存读取已包含在总量中，不重复相加。")
        return lines.joined(separator: "\n\n")
    }
}

private struct UsageQuotaSection: View {
    let tool: String
    let provider: UsageProvider?
    let checks: [UsageQuotaCheck]
    let failed: Bool
    @State private var selectedSource: String?
    @State private var showingDetails = false

    private var sources: [UsageSource] { provider?.sources(now: Date()) ?? [] }
    private var source: UsageSource? {
        sources.first(where: { $0.id == selectedSource }) ?? sources.first
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack(spacing: 10) {
                if sources.count > 1 {
                    Picker("额度来源", selection: Binding(
                        get: { source?.id ?? "" },
                        set: { selectedSource = $0 }
                    )) {
                        ForEach(sources, id: \.id) { item in Text(item.name).tag(item.id) }
                    }
                    .pickerStyle(.menu)
                    .help("仅切换套餐额度来源；图表仍汇总全部已授权电脑")
                } else {
                    Text(source.map { "额度来源 · " + $0.name } ?? "套餐额度")
                        .font(.custom("MiSans-Regular", size: 12)).foregroundStyle(.secondary)
                        .lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 0)
                Button { showingDetails = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain).foregroundStyle(.secondary)
                    .help("查看全部额度窗口与读取状态")
                    .accessibilityLabel("查看额度详情")
            }
            // Only quota rows tick. Chart identity and source-picker presentation stay stable.
            TimelineView(.periodic(from: .now, by: 1)) { context in
                VStack(spacing: 17) {
                    UsageQuotaRow(title: "周额度", quota: source?.preferredQuota(minutes: 10080, now: context.date), weekly: true, failed: failed, now: context.date)
                    UsageQuotaRow(title: "5 小时额度", quota: source?.preferredQuota(minutes: 300, now: context.date), weekly: false, failed: failed, now: context.date)
                }
            }
        }
        .onAppear { retainSource(sources.map(\.id)) }
        .onChange(of: sources.map(\.id)) { _, ids in retainSource(ids) }
        .sheet(isPresented: $showingDetails) {
            UsageHelpSheet(title: source.map { "额度 · " + $0.name } ?? "套餐额度", text: details)
        }
    }

    private func retainSource(_ ids: [String]) {
        if let selectedSource, ids.contains(selectedSource) { return }
        selectedSource = ids.first
    }

    private var details: String {
        let now = Date()
        var parts = ["额度来自所选电脑登录的账号，不同来源不能合并。这里的来源选择只影响套餐额度，不影响每日 Token 图表。"]
        if let source {
            for quota in source.quotas {
                var text = quota.windowTitle + "\n" + quota.remainingText(now: now, failed: failed) + "\n" + quota.resetText(now: now, failed: failed)
                if let observed = quota.observedDate { text += "\n记录于 " + UsageViewText.observed(observed) }
                parts.append(text)
            }
        }
        let relevant = checks.filter { $0.matches(tool: tool, sourceId: source?.id) }
        for check in relevant {
            var text = "额度读取"
            if let name = check.sourceName, !name.isEmpty { text += " · " + name }
            text += "\n" + UsageFormat.quotaCheck(check.state)
            if let raw = check.lastAttemptAt, let date = MonitorDate.parse(raw) { text += "\n尝试于 " + UsageViewText.observed(date) }
            if ["no_live_data", "unavailable"].contains(check.state), let interval = check.retryAfterSeconds {
                text += "\n后续按 \((interval + 59) / 60) 分钟间隔重试"
            }
            parts.append(text)
        }
        if source == nil && relevant.isEmpty { parts.append("这台电脑暂未提供套餐额度。未提供不代表没有使用。") }
        parts.append("每个窗口独立计量。已过期窗口等待新的真实读数，不能自动视为满额。读取尝试时间与额度记录时间分别显示，重试不会使旧数据变新。")
        return parts.joined(separator: "\n\n")
    }
}

private struct UsageQuotaRow: View {
    let title: String
    let quota: UsageQuota?
    let weekly: Bool
    let failed: Bool
    let now: Date
    private var old: Bool { failed || quota?.isStale(now: now) != false }
    private var expired: Bool { quota?.isExpired(now: now) == true }
    private var remaining: Double? { expired ? nil : quota?.remaining }
    private var heading: String {
        guard weekly, !expired, let used = quota?.usedPercent else { return title }
        return title + (old ? " · 上次已用 " : "已用 ") + UsageFormat.percentage(used)
    }
    var remainingText: String {
        quota?.remainingText(now: now, failed: failed) ?? "未提供"
    }
    private var tone: Color { old || expired ? .secondary : (remaining ?? 100) <= 10 ? .orange : .primary }
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(heading).fontWeight(.medium).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Text(remainingText).foregroundStyle(tone).fixedSize(horizontal: true, vertical: false)
            }
            .font(.custom("MiSans-Regular", size: 12))
            UsageRemainingBar(value: remaining, stale: old)
            Text(quota?.resetText(now: now, failed: failed) ?? "等待电脑提供额度")
                .font(.custom("MiSans-Regular", size: 11)).foregroundStyle(.secondary)
                .monospacedDigit().fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

private enum UsageViewText {
    static func observed(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter.string(from: date) + "（北京时间）"
    }
}

private struct UsageBrand: View {
    let tool: String
    var body: some View {
        Group {
            if let image = MonitorResources.image(tool == "claude" ? "Claude" : "Codex") {
                Image(nsImage: image).resizable().scaledToFit()
            } else {
                Image(systemName: "terminal").resizable().scaledToFit()
            }
        }
        .frame(width: 26, height: 26)
        .accessibilityHidden(true)
    }
}

private struct UsageChartBar: View {
    let day: String
    let weekday: String
    let shortDate: String
    let amount: String
    let tokens: Int64?
    let maximum: Int64
    let available: Bool
    let selected: Bool
    let choose: () -> Void

    private var height: CGFloat {
        guard let tokens, tokens > 0, maximum > 0 else { return 0 }
        return max(4, 102 * CGFloat(UsageFormat.ratio(tokens, maximum: maximum)))
    }
    var body: some View {
        Button(action: choose) {
            VStack(spacing: 7) {
                Text(weekday).font(.custom("MiSans-Regular", size: 11)).foregroundStyle(.secondary)
                ZStack(alignment: .bottom) {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color.primary.opacity(0.045))
                        .frame(width: 21, height: 102)
                    if let tokens, available {
                        if tokens > 0 {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(selected ? Color.accentColor : Color.accentColor.opacity(0.34))
                                .frame(width: 21, height: min(102, height))
                        } else {
                            Text("0").font(.custom("MiSans-Regular", size: 11)).foregroundStyle(.secondary).padding(.bottom, 3)
                        }
                    } else {
                        Text("—").font(.custom("MiSans-Regular", size: 11)).foregroundStyle(.tertiary).padding(.bottom, 3)
                    }
                }
                Text(shortDate)
                    .font(.custom("MiSans-Regular", size: 11))
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .lineLimit(1)
                Circle().fill(selected ? Color.accentColor : Color.clear).frame(width: 4, height: 4)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 7)
            .background(selected ? Color.accentColor.opacity(0.06) : Color.clear, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!available)
        .opacity(available ? 1 : 0.4)
        .help(available ? "\(day) · \(amount) Token" : "该日期不在记录范围内")
        .accessibilityLabel("\(day)，\(tokens == nil ? "未提供记录" : amount + " Token")")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

private struct UsageRemainingBar: View {
    let value: Double?
    let stale: Bool
    private var tint: Color { stale ? .secondary : (value ?? 100) <= 10 ? .orange : .accentColor }
    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.075))
                if let value, value.isFinite, value > 0 {
                    Capsule().fill(tint.opacity(stale ? 0.42 : 0.85))
                        .frame(width: geometry.size.width * min(100, max(0, value)) / 100)
                }
            }
        }
        .frame(height: 7)
        .accessibilityHidden(true)
    }
}

private struct UsageHelpSheet: View {
    let title: String
    let text: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(title).font(.custom("MiSans-Regular", size: 22)).fontWeight(.semibold)
            ScrollView {
                Text(text).font(.custom("MiSans-Regular", size: 14)).lineSpacing(6)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button("知道了") { dismiss() }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(26)
        .frame(width: 430, height: 460)
    }
}
