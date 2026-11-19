import AppKit
import SwiftUI

/// Full-history responses feed this page; source selection is deliberately quota-only.
@MainActor
struct UsageView: View {
    @ObservedObject var store: MonitorStore
    @State private var showingAlerts = false

    private var displayedTools: [String] {
        var tools = ["codex", "claude"]
        if store.usage?.provider(tool: "antigravity") != nil { tools.append("antigravity") }
        return tools
    }

    private var officialTools: [String] {
        guard let usage = store.usage else { return [] }
        return displayedTools.filter { tool in
            let provider = usage.provider(tool: tool)
            let hasVisible = !(provider?.visibleSources(now: Date()).isEmpty ?? true)
            let hasCheck = usage.quotaChecks.contains(where: { $0.tool == tool })
            let hasHistory = provider?.hasRecordedHistory == true
            return hasVisible || hasCheck || (hasHistory && (tool == "claude" || tool == "antigravity"))
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
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

                if !officialTools.isEmpty {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("官方额度")
                            .font(.custom("MiSans-Regular", size: 20))
                            .fontWeight(.semibold)
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 330), spacing: 18, alignment: .top)], alignment: .leading, spacing: 18) {
                            ForEach(officialTools, id: \.self) { tool in
                                UsageOfficialQuotaCard(
                                    tool: tool,
                                    provider: store.usage?.provider(tool: tool),
                                    checks: store.usage?.quotaChecks ?? [],
                                    failed: !store.usageError.isEmpty
                                )
                                .id(store.tokenFingerprint + ":quota:" + tool)
                            }
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 14) {
                    Text("本地 Token")
                        .font(.custom("MiSans-Regular", size: 20))
                        .fontWeight(.semibold)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 330), spacing: 18, alignment: .top)], alignment: .leading, spacing: 18) {
                        ForEach(displayedTools, id: \.self) { tool in
                            UsageProviderCard(
                                tool: tool,
                                provider: store.usage?.provider(tool: tool),
                                reportDay: store.usage?.day,
                                failed: !store.usageError.isEmpty,
                                loading: store.usageRefreshing || store.usage?.loading == true
                            )
                            .id(store.tokenFingerprint + ":local:" + tool)
                        }
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

@MainActor
private struct UsageOfficialQuotaCard: View {
    let tool: String
    let provider: UsageProvider?
    let checks: [UsageQuotaCheck]
    let failed: Bool
    @State private var selectedSource: String?
    @State private var showingDetails = false

    private var brand: String { tool == "claude" ? "Claude Code" : tool == "antigravity" ? "Antigravity" : "Codex" }
    private var sources: [UsageSource] { provider?.visibleSources(now: Date()) ?? [] }
    private var source: UsageSource? {
        sources.first(where: { $0.id == selectedSource }) ?? sources.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                UsageBrand(tool: tool)
                Text(brand).font(.custom("MiSans-Regular", size: 17)).fontWeight(.semibold)
                Spacer()
                Button { showingDetails = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain).foregroundStyle(.secondary)
                    .help("查看 \(brand) 官方额度说明")
                    .accessibilityLabel("查看 \(brand) 官方额度说明")
            }

            if let source {
                HStack(spacing: 10) {
                    if sources.count > 1 {
                        Picker("额度来源", selection: Binding(
                            get: { source.id },
                            set: { selectedSource = $0 }
                        )) {
                            ForEach(sources, id: \.id) { item in Text(item.name).tag(item.id) }
                        }
                        .pickerStyle(.menu)
                        .help("仅切换官方额度来源；本地 Token 图表仍汇总全部已授权电脑")
                    } else {
                        Text("来源 · " + source.name)
                            .font(.custom("MiSans-Regular", size: 12)).foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.middle)
                    }
                    Spacer(minLength: 0)
                }

                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let windows = source.visibleQuotas(now: context.date)
                    VStack(alignment: .leading, spacing: 17) {
                        ForEach(windows) { quota in
                            UsageQuotaRow(quota: quota, failed: failed, now: context.date)
                        }
                    }
                }
            } else {
                Text(emptyQuotaNote)
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.secondary)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.quaternary, lineWidth: 0.7) }
        .onAppear { retainSource(sources.map(\.id)) }
        .onChange(of: sources.map(\.id)) { _, ids in retainSource(ids) }
        .sheet(isPresented: $showingDetails) {
            UsageHelpSheet(title: brand + " 官方额度", text: detailsText)
        }
    }

    private func retainSource(_ ids: [String]) {
        if let selectedSource, ids.contains(selectedSource) { return }
        selectedSource = ids.first
    }

    private var emptyQuotaNote: String {
        if let check = checks.first(where: { $0.tool == tool }) {
            let prefix = (check.sourceName?.isEmpty == false) ? "来源 · \(check.sourceName!)\n" : ""
            return prefix + UsageFormat.quotaCheck(check.state)
        }
        if tool == "claude" {
            return "暂未收到官方额度读数 · 在电脑端登录 Claude Code 或开启状态栏同步后显示"
        }
        if tool == "antigravity" {
            return "暂未收到官方额度读数 · 打开电脑端 Antigravity 后自动同步"
        }
        return "等待电脑提供官方额度"
    }

    private var detailsText: String {
        let now = Date()
        var parts = ["官方额度显示服务方实际返回的窗口及额度组，百分比只使用真实读数，不用 Token 推算。不同来源电脑可能登录不同账号，额度不合并。"]
        if let source {
            for quota in source.visibleQuotas(now: now) {
                var text = quota.displayTitle + "\n" + quota.usedText(now: now, failed: failed) + "（" + quota.remainingText(now: now, failed: failed) + "）\n" + quota.resetText(now: now, failed: failed)
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
        if source == nil && relevant.isEmpty { parts.append(emptyQuotaNote) }
        parts.append("每个窗口独立计量。已到重置时间的窗口等待新的真实读数确认，不会自动视为满额。")
        return parts.joined(separator: "\n\n")
    }
}

@MainActor
private struct UsageProviderCard: View {
    let tool: String
    let provider: UsageProvider?
    let reportDay: String?
    let failed: Bool
    let loading: Bool
    @State private var selectedWeek: Date?
    @State private var selectedDay: Date?
    @State private var showingHelp = false

    private var brand: String { tool == "claude" ? "Claude Code" : tool == "antigravity" ? "Antigravity" : "Codex" }
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
    private var selectedDayShares: [UsageModelShare] {
        guard historyValid, day >= firstDay, day <= today,
              let end = UsageCalendar.date(provider?.history?.endDay), day <= end,
              let entry = provider?.history?.dayEntry(on: day) else { return [] }
        return entry.modelShares()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 17) {
            HStack(spacing: 10) {
                UsageBrand(tool: tool)
                Text(brand).font(.custom("MiSans-Regular", size: 17)).fontWeight(.semibold)
                Spacer()
                Button { showingHelp = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.plain).foregroundStyle(.secondary)
                    .help("\(brand) 本地使用说明")
                    .accessibilityLabel("查看 \(brand) 本地使用说明")
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
            let shares = selectedDayShares
            if !shares.isEmpty {
                Divider()
                VStack(alignment: .leading, spacing: 12) {
                    Text("模型 Token 占比")
                        .font(.custom("MiSans-Regular", size: 14))
                        .fontWeight(.semibold)
                    ForEach(shares) { share in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(share.displayName)
                                    .font(.custom("MiSans-Regular", size: 13))
                                    .lineLimit(2)
                                Spacer(minLength: 6)
                                Text(UsageFormat.percentage(share.percent))
                                    .font(.custom("MiSans-Regular", size: 14))
                                    .fontWeight(.medium)
                                    .monospacedDigit()
                            }
                            Text(UsageFormat.exact(share.tokens) + " Token" + (share.variantNote.map { " · " + $0 } ?? ""))
                                .font(.custom("MiSans-Regular", size: 11.5))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                    }
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.quaternary, lineWidth: 0.7) }
        .sheet(isPresented: $showingHelp) { UsageHelpSheet(title: brand + " 本地用量", text: providerHelp) }
    }

    private func tokens(on date: Date) -> Int64? {
        if historyValid, date >= firstDay, date <= today,
           let end = UsageCalendar.date(provider?.history?.endDay), date <= end,
           let value = provider?.history?.tokens(on: date) { return value }
        if date == today, UsageCalendar.date(reportDay) == today { return provider?.todayTokens }
        return nil
    }
    private func moveWeek(_ delta: Int) {
        let next = UsageCalendar.clampWeek(selected: UsageCalendar.addingDays(delta * 7, to: week), start: firstDay, today: today)
        selectedWeek = next
        selectedDay = UsageCalendar.selection(selected: nil, week: next, start: firstDay, end: today)
    }
    private var providerHelp: String {
        var lines = ["柱状图汇总所有已授权电脑采集到的本地对话记录，可能有历史缺口，并非完整账单。"]
        if let count = provider?.sessionCount { lines.append("今日已记录 \(count) 个对话。") }
        if let observed = provider?.observedAt, let date = MonitorDate.parse(observed) {
            lines.append("最近记录：" + UsageViewText.observed(date))
        }
        lines.append("按北京时间统计，周一至周日为一周，最多查看最近 90 天。点选柱状图可查看该日准确数字与模型 Token 占比；— 表示未提供，0 表示已记录为零。")
        lines.append("模型构成是所选日期各模型占已采集 Token 总量的比例，其他模型也计入分母，不是官方额度消耗比例。缓存读取已包含在总量中，不重复相加。")
        return lines.joined(separator: "\n\n")
    }
}

private struct UsageQuotaRow: View {
    let quota: UsageQuota
    let failed: Bool
    let now: Date
    private var old: Bool { failed || quota.isStale(now: now) }
    private var expired: Bool { quota.isExpired(now: now) }
    private var remaining: Double? { expired ? nil : quota.remaining }
    private var tone: Color { old || expired ? .secondary : (remaining ?? 100) <= 10 ? .orange : .primary }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(quota.displayTitle).fontWeight(.medium).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Text(quota.remainingText(now: now, failed: failed))
                    .foregroundStyle(tone)
                    .monospacedDigit()
                    .fixedSize(horizontal: true, vertical: false)
            }
            .font(.custom("MiSans-Regular", size: 12.5))
            UsageRemainingBar(value: remaining, stale: old)
            HStack(spacing: 6) {
                Text(quota.usedText(now: now, failed: failed))
                Text("·")
                Text(quota.resetText(now: now, failed: failed))
            }
            .font(.custom("MiSans-Regular", size: 11))
            .foregroundStyle(.secondary)
            .monospacedDigit()
            .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

enum UsageViewText {
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
            if let image = MonitorResources.image(tool == "claude" ? "Claude" : tool == "antigravity" ? "Antigravity" : "Codex") {
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

struct UsageHelpSheet: View {
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
