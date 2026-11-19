import Combine
import Foundation
import SwiftUI

private struct PendingApprovalConfirmation: Identifiable {
    let id: String
    let request: SessionPendingRequest
    let fingerprint: String
}

private enum DisplayedRequestItem: Identifiable {
    case valid(index: Int, request: SessionPendingRequest)
    case invalid(index: Int)

    var id: String {
        switch self {
        case let .valid(index, request): "valid:\(index):\(request.id):\(request.revision)"
        case let .invalid(index): "invalid:\(index)"
        }
    }
}

@MainActor
struct TaskAttentionSection: View {
    @ObservedObject var model: TaskDetailModel
    let waiting: Bool
    @State private var tickNow = Date()
    @State private var confirmingApproval: PendingApprovalConfirmation?

    private var nowSeconds: Int64 { Int64(tickNow.timeIntervalSince1970) }

    private var pendingItems: [SessionPendingRequest]? {
        model.controls?.pending
    }

    private var hasPendingItems: Bool {
        guard let pendingItems else { return false }
        return !pendingItems.isEmpty
    }

    private var shouldShow: Bool {
        model.controls?.pending == nil && waiting
            || model.controls?.pending != nil && (hasPendingItems || waiting)
            || (model.controls != nil && model.controls?.pending == nil)
            || !model.controlsFeedback.isEmpty
            || model.recordsCorrupt
    }

    private var needsAction: Bool {
        guard let pendingItems else { return waiting }
        return pendingItems.prefix(20).contains { SessionControlPolicy.pending($0, now: nowSeconds) }
    }

    private var displayedItems: [DisplayedRequestItem] {
        guard let pendingItems else { return [] }
        var seen = Set<String>()
        return pendingItems.enumerated().map { index, item in
            if SessionControlPolicy.validRequest(item), seen.insert(item.id).inserted {
                return .valid(index: index, request: item)
            }
            return .invalid(index: index)
        }
    }

    var body: some View {
        Group {
            if shouldShow {
                GlassCard {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack(spacing: 10) {
                            Label(needsAction ? "待你处理" : "交互记录",
                                  systemImage: needsAction ? "exclamationmark.bubble" : "clock.arrow.circlepath")
                                .font(.custom("MiSans-Regular", size: 18))
                                .fontWeight(.semibold)
                            Spacer()
                            if model.controlsRefreshing || model.decisionSending {
                                ProgressView().controlSize(.small)
                            }
                            Button {
                                Task { await model.refreshControls(force: true) }
                            } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                            .buttonStyle(.plain)
                            .help("刷新提问和批准状态")
                            .disabled(model.controlsRefreshing || model.decisionSending)
                        }
                        if !model.controlsFeedback.isEmpty {
                            Text(model.controlsFeedback)
                                .font(.custom("MiSans-Regular", size: 13))
                                .foregroundStyle(.secondary)
                        }
                        if model.recordsCorrupt {
                            Label("原发送记录无法确认，此客户端已停用操作；请到电脑核对", systemImage: "exclamationmark.triangle")
                                .font(.custom("MiSans-Regular", size: 13))
                                .foregroundStyle(.orange)
                        }
                        if model.controls != nil && pendingItems == nil {
                            Text("返回的请求列表格式无效，请到电脑查看")
                                .font(.custom("MiSans-Regular", size: 13))
                                .foregroundStyle(.secondary)
                        } else if !hasPendingItems {
                            let reason = model.controls == nil
                                ? "正在确认任务的交互能力…"
                                : (model.controls?.reason ?? "")
                            Text(reason.isEmpty ? "尚未收到可在此处理的请求，可先到电脑查看" : reason)
                                .font(.custom("MiSans-Regular", size: 13))
                                .foregroundStyle(.secondary)
                        } else if (pendingItems?.count ?? 0) > 20 {
                            Text("请求数量超出显示范围，请到电脑查看")
                                .font(.custom("MiSans-Regular", size: 13))
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(displayedItems) { entry in
                                switch entry {
                                case .invalid:
                                    Text("请求信息不完整或重复，已停用操作，请到电脑查看")
                                        .font(.custom("MiSans-Regular", size: 13))
                                        .foregroundStyle(.orange)
                                        .padding(14)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .background(Color.orange.opacity(0.08),
                                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                case let .valid(_, request):
                                    SessionRequestCard(
                                        model: model,
                                        request: request,
                                        now: tickNow,
                                        onConfirmApproval: { item in confirmingApproval = item }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        .onReceive(Timer.publish(every: 3, on: .main, in: .common).autoconnect()) { value in
            tickNow = value
        }
        .sheet(item: $confirmingApproval) { item in
            ApprovalConfirmationSheet(
                request: item.request,
                onConfirm: {
                    confirmingApproval = nil
                    Task { await model.submitApproval(item.request, fingerprint: item.fingerprint, allowOnce: true) }
                },
                onCancel: { confirmingApproval = nil }
            )
        }
    }
}

@MainActor
private struct SessionRequestCard: View {
    @ObservedObject var model: TaskDetailModel
    let request: SessionPendingRequest
    let now: Date
    let onConfirmApproval: (PendingApprovalConfirmation) -> Void

    private var nowSeconds: Int64 { Int64(now.timeIntervalSince1970) }
    private var fingerprint: String { SessionControlPolicy.fingerprintDigest(request) }
    private var isTerminal: Bool { SessionControlPolicy.terminal(request) }
    private var enabled: Bool { model.canAct(on: request, now: now) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                BrandIcon(tool: request.provider, size: 18)
                Text(MonitorTask.brand(for: request.provider))
                    .font(.custom("MiSans-Regular", size: 12))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(SessionControlPolicy.stateLabel(request, now: nowSeconds))
                    .font(.custom("MiSans-Regular", size: 12))
                    .foregroundStyle(SessionControlPolicy.pending(request, now: nowSeconds) ? Color.orange : Color.secondary)
            }
            Text(request.title)
                .font(.custom("MiSans-Regular", size: 16))
                .fontWeight(.semibold)
                .textSelection(.enabled)
            if let detail = request.detail, !detail.isEmpty {
                Text(detail)
                    .font(.custom("MiSans-Regular", size: 14))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            if !isTerminal {
                if request.kind == "approval" {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("本次操作")
                            .font(.custom("MiSans-Regular", size: 12))
                            .fontWeight(.medium)
                            .foregroundStyle(.secondary)
                        Text(request.operation ?? "")
                            .font(.system(size: 13, design: .monospaced))
                            .textSelection(.enabled)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.primary.opacity(0.05),
                                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        Text("允许后，电脑可执行上面列出的操作；此批准不适用于其他请求")
                            .font(.custom("MiSans-Regular", size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                if let saved = model.decisionRecords[request.id] {
                    let unchanged = (saved.fingerprint == fingerprint)
                    Text(unchanged
                         ? "已保存本次发送记录；重复发送会沿用同一决定，不会改换选项"
                         : "请求内容已变化，原决定已停用；请在电脑确认新请求")
                        .font(.custom("MiSans-Regular", size: 13))
                        .foregroundStyle(unchanged ? Color.secondary : Color.orange)
                    if unchanged {
                        Text(SessionControlPolicy.decisionSummary(request, payload: saved.payload))
                            .font(.custom("MiSans-Regular", size: 13))
                            .textSelection(.enabled)
                    }
                    let canRetry = enabled && unchanged
                        && SessionControlPolicy.validDecision(request, payload: saved.payload,
                                                              taskId: model.taskId, now: nowSeconds)
                    Button("重试原决定") {
                        Task { await model.retryDecision(request, fingerprint: fingerprint) }
                    }
                    .controlSize(.regular)
                    .disabled(!canRetry)
                } else if !enabled {
                    if request.state == "pending" {
                        Text(model.controlsFresh(now: now)
                             ? "当前客户端暂不能处理这个请求，请到电脑查看"
                             : "状态尚未确认，请先刷新")
                            .font(.custom("MiSans-Regular", size: 13))
                            .foregroundStyle(.secondary)
                    }
                } else if request.kind == "approval" {
                    HStack(spacing: 10) {
                        Button("仅允许这一次") {
                            guard model.canAct(on: request, now: Date()) else {
                                Task { await model.refreshControls(force: true) }
                                return
                            }
                            onConfirmApproval(PendingApprovalConfirmation(
                                id: request.id, request: request, fingerprint: fingerprint
                            ))
                        }
                        .buttonStyle(.borderedProminent)
                        Button("拒绝") {
                            Task { await model.submitApproval(request, fingerprint: fingerprint, allowOnce: false) }
                        }
                        .buttonStyle(.bordered)
                    }
                    .controlSize(.large)
                } else {
                    SessionQuestionsForm(model: model, request: request, fingerprint: fingerprint)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(.quaternary, lineWidth: 0.7)
        }
    }
}

@MainActor
private struct SessionQuestionsForm: View {
    @ObservedObject var model: TaskDetailModel
    let request: SessionPendingRequest
    let fingerprint: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(request.questions ?? []) { question in
                VStack(alignment: .leading, spacing: 8) {
                    Text(question.text)
                        .font(.custom("MiSans-Regular", size: 14))
                        .fontWeight(.medium)
                        .textSelection(.enabled)
                    let selected = model.selectedChoices(fingerprint: fingerprint, questionId: question.id)
                    ForEach(question.options ?? []) { option in
                        let isSelected = selected.contains(option.id)
                        Button {
                            model.toggleChoice(request: request, question: question, optionId: option.id)
                        } label: {
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: question.multiple == true
                                      ? (isSelected ? "checkmark.square.fill" : "square")
                                      : (isSelected ? "largecircle.fill.circle" : "circle"))
                                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                                    .padding(.top, 2)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(option.label)
                                        .font(.custom("MiSans-Regular", size: 14))
                                        .foregroundStyle(.primary)
                                    if let desc = option.description, !desc.isEmpty {
                                        Text(desc)
                                            .font(.custom("MiSans-Regular", size: 12))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(isSelected ? Color.accentColor.opacity(0.12) : Color.primary.opacity(0.03),
                                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if question.allowsText == true {
                        TextField(
                            "自定义回答（代替选项）",
                            text: Binding(
                                get: { model.customAnswerText(fingerprint: fingerprint, questionId: question.id) },
                                set: { model.updateCustomText(request: request, question: question, value: $0) }
                            ),
                            axis: .vertical
                        )
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(2...5)
                    }
                }
            }
            HStack {
                Spacer()
                Button("发送回答") {
                    Task { await model.submitAnswers(request, fingerprint: fingerprint) }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(model.decisionSending)
            }
        }
    }
}

@MainActor
private struct ApprovalConfirmationSheet: View {
    let request: SessionPendingRequest
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("确认本次批准")
                .font(.custom("MiSans-Regular", size: 22))
                .fontWeight(.semibold)
            Text(request.title)
                .font(.custom("MiSans-Regular", size: 14))
                .foregroundStyle(.secondary)
            ScrollView {
                Text(request.operation ?? "")
                    .font(.system(size: 13, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .frame(minHeight: 120, maxHeight: 260)
            .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text("允许后，电脑可执行以上操作，仅限这一次请求。")
                .font(.custom("MiSans-Regular", size: 13))
                .foregroundStyle(.secondary)
            HStack {
                Button("取消", role: .cancel, action: onCancel)
                Spacer()
                Button("仅允许这一次", action: onConfirm)
                    .buttonStyle(.borderedProminent)
            }
            .controlSize(.large)
        }
        .padding(28)
        .frame(width: 500)
    }
}

@MainActor
struct SessionModelSettingsSheet: View {
    @ObservedObject var model: TaskDetailModel
    @Environment(\.dismiss) private var dismiss
    @State private var tickNow = Date()

    private var nowSeconds: Int64 { Int64(tickNow.timeIntervalSince1970) }
    private var state: SessionControlPolicy.CatalogState { model.catalogState(now: tickNow) }
    private var catalog: SessionModelCatalog? { model.controls?.catalog }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("模型设置")
                        .font(.custom("MiSans-Regular", size: 22))
                        .fontWeight(.semibold)
                    Text("此任务的回复设置")
                        .font(.custom("MiSans-Regular", size: 13))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if model.controlsRefreshing {
                    ProgressView().controlSize(.small)
                }
                Button {
                    Task { await model.refreshControls(force: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help("重新读取可用模型")
                .disabled(model.controlsRefreshing || model.decisionSending)
            }

            Divider()

            if state == .ready, let catalog, let models = catalog.models {
                readyContent(catalog: catalog, models: models)
            } else {
                unavailableContent
            }

            if model.fastCorrupt || (model.fastNext != nil && (state != .ready || SessionControlPolicy.fastReply(catalog, selected: model.selection, pending: model.fastNext, now: nowSeconds) == nil)) {
                Text("上次的快速模式选择需要重新确认")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.orange)
            }
            if model.planCorrupt || (model.planNext != nil && (state != .ready || SessionControlPolicy.planReply(catalog, selected: model.selection, pending: model.planNext, now: nowSeconds) == nil)) {
                Text("上次的计划模式选择需要重新确认")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.orange)
            }
            if model.hasAnyModelOverride {
                Button("恢复为继承电脑设置") {
                    model.resetModelSettings()
                }
                .disabled(model.replyLocked)
            }
            if model.replyLocked {
                Text("等待已发送的回复确认后即可修改")
                    .font(.custom("MiSans-Regular", size: 12))
                    .foregroundStyle(.secondary)
            } else if state == .ready {
                Text(SessionControlPolicy.fastModel(catalog, selected: model.selection, corrupt: model.selectionCorrupt, now: nowSeconds) == nil
                     ? "选择保存在这台 Mac 上，随下次回复提交，此会话后续可能继续沿用"
                     : "模型和思考强度保存在这台 Mac 上，随回复提交。快速模式只用于下一次回复")
                    .font(.custom("MiSans-Regular", size: 12))
                    .foregroundStyle(.secondary)
            }

            HStack {
                Spacer()
                Button("完成") { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
        }
        .padding(28)
        .frame(width: 460)
        .task { await model.refreshControls(force: false) }
        .onReceive(Timer.publish(every: 2, on: .main, in: .common).autoconnect()) { value in
            tickNow = value
            if model.controls?.catalogStatus == "pending" || state == .loading {
                Task { await model.refreshControls(force: false) }
            }
        }
    }

    @ViewBuilder
    private func readyContent(catalog: SessionModelCatalog, models: [SessionModelOption]) -> some View {
        let labels = SessionControlPolicy.disambiguatedModelLabels(models)
        let resolvedModel = SessionControlPolicy.effortModel(
            catalog, selected: model.selection, corrupt: model.selectionCorrupt, now: nowSeconds
        )
        let invalidModelSelection = model.selectionCorrupt || (model.selection != nil && resolvedModel == nil)
        let modelBinding = Binding<String>(
            get: {
                if invalidModelSelection { return "__invalid_model__" }
                return model.selection?.model ?? ""
            },
            set: { chosen in
                guard chosen != "__invalid_model__" else { return }
                model.selectModel(chosen.isEmpty ? nil : chosen)
            }
        )

        VStack(alignment: .leading, spacing: 14) {
            if let recent = model.recentUsedModelSummary {
                Text("最近使用：\(recent)")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.secondary)
            }

            Picker("下次回复模型", selection: modelBinding) {
                Text("继承电脑设置").tag("")
                if invalidModelSelection {
                    Text("重新选择").tag("__invalid_model__")
                }
                ForEach(Array(models.enumerated()), id: \.element.id) { index, option in
                    Text(labels[index]).tag(option.id)
                }
            }
            .disabled(model.replyLocked)

            if let resolvedModel, let efforts = resolvedModel.efforts, !efforts.isEmpty {
                let rawEffort = model.selection?.effort ?? ""
                let invalidEffort = !rawEffort.isEmpty && !efforts.contains(rawEffort)
                let effortBinding = Binding<String>(
                    get: { invalidEffort ? "__invalid_effort__" : rawEffort },
                    set: { chosen in
                        guard chosen != "__invalid_effort__" else { return }
                        model.selectEffort(chosen.isEmpty ? nil : chosen)
                    }
                )
                Picker("思考强度", selection: effortBinding) {
                    Text(model.selection == nil ? "继承电脑设置" : "使用模型默认值").tag("")
                    if invalidEffort {
                        Text("重新选择").tag("__invalid_effort__")
                    }
                    ForEach(efforts, id: \.self) { level in
                        Text(SessionControlPolicy.effortLabel(level)).tag(level)
                    }
                }
                .disabled(model.replyLocked)
            } else {
                Text(resolvedModel == nil ? "请先选择模型，再查看可用的思考强度" : "此模型未提供可选的思考强度")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.secondary)
            }

            if SessionControlPolicy.stageFast(catalog, selected: model.selection, corrupt: model.selectionCorrupt, now: nowSeconds) != nil {
                let fastEnabled = SessionControlPolicy.fastReply(
                    catalog, selected: model.selection, pending: model.fastNext, now: nowSeconds
                ) != nil
                Picker("下次回复速度", selection: Binding(
                    get: { fastEnabled },
                    set: { model.setOneTurnOption(plan: false, enabled: $0) }
                )) {
                    Text("继承电脑设置").tag(false)
                    Text("快速").tag(true)
                }
                .pickerStyle(.segmented)
                .disabled(model.replyLocked)
            } else if SessionControlPolicy.fastModel(catalog, selected: model.selection, corrupt: model.selectionCorrupt, now: nowSeconds) != nil {
                Text("当前思考强度无法用于快速模式，请先选择可用的思考强度")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.secondary)
            }

            if SessionControlPolicy.stagePlan(catalog, selected: model.selection, corrupt: model.selectionCorrupt, now: nowSeconds) != nil {
                let planEnabled = SessionControlPolicy.planReply(
                    catalog, selected: model.selection, pending: model.planNext, now: nowSeconds
                ) != nil
                Picker("下次回复模式", selection: Binding(
                    get: { planEnabled },
                    set: { model.setOneTurnOption(plan: true, enabled: $0) }
                )) {
                    Text("继承电脑设置").tag(false)
                    Text("计划").tag(true)
                }
                .pickerStyle(.segmented)
                .disabled(model.replyLocked)
                Text("计划只用于下一次回复；Codex 提出的选项会出现在任务详情")
                    .font(.custom("MiSans-Regular", size: 12))
                    .foregroundStyle(.secondary)
            } else if SessionControlPolicy.advertisesPlan(catalog) {
                Text("请先选择可用的模型和思考强度，再使用计划模式")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var unavailableContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(SessionControlPolicy.catalogMessage(state, response: model.controls))
                .font(.custom("MiSans-Regular", size: 14))
            if let savedModel = model.selection?.model, !savedModel.isEmpty {
                Text("上次选择：\(SessionControlPolicy.modelName(savedModel))")
                    .font(.custom("MiSans-Regular", size: 13))
                    .foregroundStyle(.secondary)
            }
            if state != .loading {
                Button("重新获取") {
                    Task { await model.refreshControls(force: true) }
                }
                .disabled(model.controlsRefreshing || model.decisionSending)
            }
        }
    }
}
