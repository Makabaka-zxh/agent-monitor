using System.Net.Http;

namespace MonitorApp.Core;

public sealed class PersistedPendingReply
{
    public string Id { get; set; } = "";
    public string TaskId { get; set; } = "";
    public string Text { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; }
    public string State { get; set; } = "";
    public SessionModelSelectionDto? SubmittedSelection { get; set; }

    public bool Unresolved => State is not ("succeeded" or "failed" or "blocked");
    public bool MayRetry =>
        State == "not_found"
        && !string.IsNullOrEmpty(Text)
        && (DateTimeOffset.UtcNow - CreatedAt).TotalHours <= 24;
}

public sealed class PersistedModelOverrides
{
    public SessionModelSelectionDto? Selection { get; set; }
    public bool FastNext { get; set; }
    public bool PlanNext { get; set; }
}

public sealed class StoredDecisionEntry
{
    public string Fingerprint { get; set; } = "";
    public SessionDecisionPayloadDto Payload { get; set; } = new();
}

public sealed class TaskResultCache
{
    private (string? Marker, string? Status, string? UpdatedAt)? _revision;
    public long Generation { get; private set; }
    public TaskResultDto? Result { get; private set; }
    public bool NeedsRefresh => _revision.HasValue &&
        (Result?.Available != true || Result.Files?.Any(file => file.Ready == false) == true);

    public bool Observe(MonitorTask? task, bool enabled)
    {
        (string? Marker, string? Status, string? UpdatedAt)? next = null;
        if (enabled && task is not null)
        {
            // Older collectors do not advertise a result ID. Recheck on task changes.
            next = string.IsNullOrEmpty(task.FinalResultId)
                ? (null, task.Status, task.UpdatedAt) : (task.FinalResultId, null, null);
        }
        if (next == _revision) return false;
        _revision = next;
        Generation++;
        Result = null;
        return true;
    }

    public void Apply(long generation, TaskResultDto response)
    {
        if (generation != Generation || _revision is not { } current) return;
        Result = response.Available && current.Marker is { } marker && response.ResultId != marker
            ? new TaskResultDto { Reason = "等待电脑同步最新结果" } : response;
    }
}

public sealed class TaskDetailController : IDisposable
{
    public event Action? Changed;

    public string TaskId { get; }
    public bool SupportsReply { get; }
    private readonly MonitorController _hub;
    private readonly string _token;
    private readonly string _replyVaultKey;
    private readonly string _modelVaultKey;
    private readonly string _decisionVaultKey;
    private readonly CancellationTokenSource _cts = new();

    private readonly TaskResultCache _resultCache = new();
    public TaskResultDto? Result => _resultCache.Result;
    public bool ResultLoading { get; private set; }
    public bool Downloading { get; private set; }
    public string ResultError { get; private set; } = "";

    public TaskControlsResponseDto? Controls { get; private set; }
    public bool ControlsLoading { get; private set; }
    public bool DecisionSending { get; private set; }
    public string ControlsFeedback { get; set; } = "";
    private string _observedPendingKind = "";
    private string _observedContinuationState = "";
    public string DecisionSendingId { get; private set; } = "";
    public Dictionary<string, (string Title, string Summary)> SessionDecisionMemos { get; } = new(StringComparer.Ordinal);

    public PersistedPendingReply? PendingReply { get; private set; }
    public PersistedModelOverrides ModelOverrides { get; private set; } = new();
    public Dictionary<string, StoredDecisionEntry> Decisions { get; private set; } = new();

    public bool ReplyAvailable { get; private set; }
    public bool ReplySending { get; private set; }
    public bool ReplyDelivered { get; private set; }
    public string ReplyReceiptTrail { get; private set; } = "";
    public string SettingsReceiptText { get; private set; } = "";
    public string ReplyMessage { get; private set; } = "";
    public string DraftText { get; set; } = "";

    public TaskDetailController(MonitorTask task, MonitorController hub)
    {
        TaskId = task.Id;
        SupportsReply = task.SupportsReply;
        _hub = hub;
        _token = hub.ActiveToken ?? "";
        ObserveWorkbench();

        var scopeHash = MonitorSecurity.Sha256Hex($"{hub.TokenFingerprint}:{TaskId}");
        _replyVaultKey = "reply:" + scopeHash;
        _modelVaultKey = "controls:" + scopeHash;
        _decisionVaultKey = "decisions:" + scopeHash;

        if (SupportsReply && !string.IsNullOrEmpty(hub.Origin))
        {
            PendingReply = DpapiVault.Read<PersistedPendingReply>(hub.Origin, _replyVaultKey);
            if (PendingReply is { Unresolved: true })
                DraftText = PendingReply.Text;
            ModelOverrides = DpapiVault.Read<PersistedModelOverrides>(hub.Origin, _modelVaultKey) ?? new PersistedModelOverrides();
            Decisions = DpapiVault.Read<Dictionary<string, StoredDecisionEntry>>(hub.Origin, _decisionVaultKey) ?? new();
        }
    }

    private bool IsActive => !_cts.IsCancellationRequested && _hub.ActiveToken == _token && !string.IsNullOrEmpty(_token);

    public string ModelSettingsSummary
    {
        get
        {
            if (ModelOverrides.Selection is not { } sel || string.IsNullOrWhiteSpace(sel.Model))
                return "继承电脑当前模型";
            var label = Controls?.Catalog?.Models?.FirstOrDefault(m => m.Id == sel.Model)?.Label
                        ?? UsageFormatting.FormatModelName(sel.Model);
            var extras = new List<string> { label };
            if (!string.IsNullOrWhiteSpace(sel.Effort)) extras.Add($"思考强度 {SessionControlPolicy.LocalizedEffort(sel.Effort)}");
            if (ModelOverrides.FastNext) extras.Add("快速模式(下轮)");
            if (ModelOverrides.PlanNext) extras.Add("计划模式(下轮)");
            return string.Join(" · ", extras);
        }
    }

    public bool CanSaveTxt =>
        IsActive && _hub.SyncOutput && Result is { Available: true, ResultId: { } id, TxtSize: >= 0 and <= 800000, TxtSha256: { } sha }
        && MonitorSecurity.Matches(id, "[0-9a-f]{64}") && MonitorSecurity.Matches(sha, "[0-9a-f]{64}");

    public void StartPolling()
    {
        _ = PollLoopAsync();
    }

    private async Task PollLoopAsync()
    {
        try
        {
            while (IsActive)
            {
                await LoadResultAsync();
                if (SupportsReply)
                {
                    await RefreshReplyStateAsync();
                    await RefreshControlsAsync();
                }
                var hasOpenPending = Controls?.Pending?.Any(p => p.State is "pending" or "submitted") == true;
                var fastPoll = SupportsReply && (Controls?.CatalogStatus == "pending"
                                                 || PendingReply?.Unresolved == true
                                                 || hasOpenPending
                                                 || !string.IsNullOrEmpty(_observedPendingKind));
                await Task.Delay(fastPoll ? 2000 : 5000, _cts.Token);
            }
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
    }

    public void ObserveWorkbench()
    {
        var task = _hub.Tasks.FirstOrDefault(item => item.Id == TaskId);
        if (_resultCache.Observe(task, IsActive && _hub.SyncOutput)) ResultError = "";
        if (!_hub.SyncOutput) ResultError = "尚未开启账号结果同步（可在「电脑 -> 同步设置」中开启）";

        var nextKind = task?.EffectivePendingKind ?? "";
        if (nextKind != _observedPendingKind)
        {
            _observedPendingKind = nextKind;
            if (!string.IsNullOrEmpty(nextKind) && IsActive)
            {
                _ = RefreshControlsAsync();
            }
        }

        var nextCont = task?.ContinuationState ?? "";
        if (nextCont != _observedContinuationState)
        {
            var releasedToContinuable = nextCont == "continuable";
            _observedContinuationState = nextCont;
            if (releasedToContinuable && SupportsReply && IsActive)
            {
                _ = RefreshReplyStateAsync();
            }
        }
    }

    public async Task LoadResultAsync(bool force = false)
    {
        ObserveWorkbench();
        if (!IsActive || !_hub.SyncOutput || _hub.RateLimited || ResultLoading || (!force && !_resultCache.NeedsRefresh)) return;
        var generation = _resultCache.Generation;
        ResultLoading = true;
        Notify();
        try
        {
            var res = await _hub.Api.JsonAsync<TaskResultDto>(
                _hub.Origin, "/api/native/tasks/result", HttpMethod.Get,
                token: _token, query: [("task_id", TaskId)], ct: _cts.Token);
            if (!IsActive) return;
            ObserveWorkbench();
            if (generation != _resultCache.Generation) return;
            _resultCache.Apply(generation, res);
            ResultError = "";
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
        catch (Exception ex)
        {
            if (!IsActive) return;
            ObserveWorkbench();
            if (generation != _resultCache.Generation) return;
            ResultError = ex is MonitorApiException apiEx ? apiEx.Message : "暂时无法读取最终结果";
            _hub.HandleApiError(ex, _token, showAlert: false);
        }
        finally
        {
            ResultLoading = false;
            Notify();
        }
    }

    public async Task<byte[]?> DownloadTxtBytesAsync()
    {
        ObserveWorkbench();
        if (!CanSaveTxt || Downloading || Result is not { } result) return null;
        var generation = _resultCache.Generation;
        Downloading = true;
        Notify();
        try
        {
            var bytes = await _hub.Api.DownloadVerifiedAsync(
                _hub.Origin, "/api/native/tasks/result.txt", _token,
                result.TxtSize!.Value, result.TxtSha256!,
                [("task_id", TaskId), ("result_id", result.ResultId!)], _cts.Token);
            ObserveWorkbench();
            return IsActive && generation == _resultCache.Generation ? bytes : null;
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { return null; }
        catch (Exception ex)
        {
            _hub.Notice = ex is MonitorApiException apiEx ? apiEx.Message : "下载 TXT 校验未通过，请刷新后重试";
            return null;
        }
        finally
        {
            Downloading = false;
            Notify();
        }
    }

    public async Task<byte[]?> DownloadDeliverableAsync(ResultFileDto file)
    {
        ObserveWorkbench();
        if (!IsActive || !_hub.SyncOutput || Downloading || Result?.ResultId is not { } resultId ||
            Result.Files?.Any(item => item.Id == file.Id && item.Sha256 == file.Sha256 && item.Ready != false) != true) return null;
        var generation = _resultCache.Generation;
        Downloading = true;
        Notify();
        try
        {
            var bytes = await _hub.Api.DownloadVerifiedAsync(
                _hub.Origin, "/api/native/tasks/result/file", _token,
                file.Size, file.Sha256,
                [("task_id", TaskId), ("result_id", resultId), ("file_id", file.Id)], _cts.Token);
            ObserveWorkbench();
            return IsActive && generation == _resultCache.Generation ? bytes : null;
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { return null; }
        catch (Exception ex)
        {
            _hub.Notice = ex is MonitorApiException apiEx ? apiEx.Message : "文件下载或 SHA-256 校验失败";
            return null;
        }
        finally
        {
            Downloading = false;
            Notify();
        }
    }

    public async Task RefreshControlsAsync()
    {
        if (!SupportsReply || !IsActive || ControlsLoading) return;
        ControlsLoading = true;
        try
        {
            var res = await _hub.Api.JsonAsync<TaskControlsResponseDto>(
                _hub.Origin, "/api/native/tasks/controls", HttpMethod.Get,
                token: _token, query: [("task_id", TaskId)], ct: _cts.Token);
            if (!IsActive) return;
            Controls = res;
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
        catch (Exception ex)
        {
            if (IsActive) _hub.HandleApiError(ex, _token, showAlert: false);
        }
        finally
        {
            ControlsLoading = false;
            Notify();
        }
    }

    public void SaveModelOverrides(SessionModelSelectionDto? selection, bool fastNext, bool planNext)
    {
        ModelOverrides = new PersistedModelOverrides
        {
            Selection = selection,
            FastNext = fastNext,
            PlanNext = planNext
        };
        if (!string.IsNullOrEmpty(_hub.Origin))
            DpapiVault.Write(_hub.Origin, _modelVaultKey, ModelOverrides);
        Notify();
    }

    public async Task SubmitDecisionAsync(
        SessionPendingRequestDto request,
        string decision,
        List<SessionAnswerPayloadDto>? answers = null)
    {
        if (!IsActive || DecisionSending) return;
        var fingerprint = SessionControlPolicy.AttentionFingerprint(request);
        SessionDecisionPayloadDto payload;
        if (Decisions.TryGetValue(request.Id, out var existing))
        {
            if (existing.Fingerprint != fingerprint)
            {
                _hub.Notice = "请求内容已更新，请刷新后重新确认";
                await RefreshControlsAsync();
                return;
            }
            payload = existing.Payload;
        }
        else
        {
            payload = new SessionDecisionPayloadDto
            {
                TaskId = TaskId,
                RequestId = request.Id,
                Revision = request.Revision,
                DecisionId = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                Decision = decision,
                Answers = answers ?? []
            };
            Decisions[request.Id] = new StoredDecisionEntry { Fingerprint = fingerprint, Payload = payload };
            DpapiVault.Write(_hub.Origin, _decisionVaultKey, Decisions);
        }

        SessionDecisionMemos[request.Id] = (request.Title, DescribeDecision(request, payload));
        DecisionSending = true;
        DecisionSendingId = request.Id;
        ControlsFeedback = "";
        Notify();
        try
        {
            _ = await _hub.Api.JsonAsync<SessionPendingRequestDto>(
                _hub.Origin, "/api/native/tasks/controls/decision", HttpMethod.Post,
                body: payload, token: _token, ct: _cts.Token);
            ControlsFeedback = "";
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            if (ex is MonitorApiException { Status: 400 or 404 or 409 or 410 or 422 } && Decisions.Remove(request.Id) && !string.IsNullOrEmpty(_hub.Origin))
            {
                DpapiVault.Write(_hub.Origin, _decisionVaultKey, Decisions);
            }
            ControlsFeedback = ex is MonitorApiException apiEx ? apiEx.Message : "发送尚未确认，再次点击将使用同一决定幂等重试";
            _hub.HandleApiError(ex, _token, showAlert: false);
        }
        finally
        {
            DecisionSending = false;
            DecisionSendingId = "";
            Notify();
        }
        await RefreshControlsAsync();
    }

    private static string DescribeDecision(SessionPendingRequestDto req, SessionDecisionPayloadDto payload)
    {
        if (payload.Decision == "allow_once") return "已选：仅允许这一次";
        if (payload.Decision == "deny") return "已选：拒绝";
        if (req.Questions is not { Count: > 0 } qs) return "已提交回答";
        var lines = new List<string>();
        foreach (var q in qs)
        {
            var ans = payload.Answers.FirstOrDefault(a => a.QuestionId == q.Id);
            if (ans is null) continue;
            if (!string.IsNullOrWhiteSpace(ans.Text))
            {
                lines.Add($"{q.Text}：{ans.Text}");
            }
            else if (ans.Choices.Count > 0 && q.Options is { } opts)
            {
                var labels = ans.Choices
                    .Select(cid => opts.FirstOrDefault(o => o.Id == cid)?.Label ?? cid)
                    .ToList();
                lines.Add($"{q.Text}：{string.Join("、", labels)}");
            }
        }
        return lines.Count > 0 ? string.Join("\n", lines) : "已提交选项回答";
    }

    public async Task RefreshReplyStateAsync()
    {
        if (!SupportsReply || !IsActive || ReplySending) return;
        try
        {
            var query = new List<(string, string)> { ("task_id", TaskId) };
            if (PendingReply is { Unresolved: true })
                query.Add(("request_id", PendingReply.Id));
            var state = await _hub.Api.JsonAsync<ReplyStateDto>(
                _hub.Origin, "/api/native/tasks/reply", HttpMethod.Get,
                token: _token, query: query, ct: _cts.Token);
            if (!IsActive) return;
            ReplyAvailable = state.Available ?? false;

            if (PendingReply is { Unresolved: true } cur)
            {
                if (state.Found == false && state.State == "not_found")
                {
                    UpdatePendingState("not_found", "尚未找到发送记录，可点击重试发送原消息");
                }
                else if (state.Id == cur.Id && !string.IsNullOrEmpty(state.State))
                {
                    ReplyDelivered = !string.IsNullOrEmpty(state.ClaimedAt) || state.State is "running" or "succeeded";
                    ReplyReceiptTrail = SessionControlPolicy.ReplyReceiptTrail(false, state.State, ReplyDelivered);
                    var receiptSettings = SessionControlPolicy.FormatSettingsReceipt(state.Applied, state.RequestedSelection ?? cur.SubmittedSelection);
                    if (!string.IsNullOrEmpty(receiptSettings)) SettingsReceiptText = receiptSettings;
                    UpdatePendingState(state.State, state.Reason);
                }
            }
            else if (state.Latest is { } latest)
            {
                ReplyDelivered = !string.IsNullOrEmpty(latest.ClaimedAt) || latest.State is "running" or "succeeded";
                ReplyReceiptTrail = SessionControlPolicy.ReplyReceiptTrail(false, latest.State, ReplyDelivered);
                var receiptSettings = SessionControlPolicy.FormatSettingsReceipt(latest.Applied, latest.RequestedSelection);
                if (!string.IsNullOrEmpty(receiptSettings)) SettingsReceiptText = receiptSettings;
                ReplyMessage = !string.IsNullOrWhiteSpace(latest.Reason) ? latest.Reason! : StateLabel(latest.State);
            }
            else
            {
                ReplyReceiptTrail = "";
                ReplyMessage = state.Reason ?? "";
            }
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
        catch (Exception ex)
        {
            if (IsActive)
            {
                ReplyAvailable = false;
                _hub.HandleApiError(ex, _token, showAlert: false);
            }
        }
        finally
        {
            Notify();
        }
    }

    private void UpdatePendingState(string nextState, string? reason)
    {
        if (PendingReply is null) return;
        PendingReply.State = nextState;
        if (nextState == "succeeded")
        {
            if (DraftText.Trim() == PendingReply.Text) DraftText = "";
            PendingReply.Text = "";
            PendingReply.SubmittedSelection = null;
        }
        else if (nextState is "failed" or "blocked")
        {
            PendingReply.Text = "";
            PendingReply.SubmittedSelection = null;
        }
        DpapiVault.Write(_hub.Origin, _replyVaultKey, PendingReply);
        ReplyMessage = !string.IsNullOrWhiteSpace(reason) ? reason! : StateLabel(nextState);
    }

    private static string StateLabel(string state) => state switch
    {
        "queued" => "等待电脑接收",
        "dispatching" => "等待电脑确认",
        "running" => "电脑端正在回复",
        "succeeded" => "回复已完成",
        "failed" => "回复未完成",
        "blocked" => "请在电脑端处理批准请求",
        "uncertain" => "发送结果尚未确认，正在查询",
        "not_found" => "尚未找到发送记录，可重试原消息",
        _ => "正在确认发送状态"
    };

    public async Task SendReplyAsync(string rawText)
    {
        if (!SupportsReply || !IsActive || ReplySending) return;
        PersistedPendingReply record;
        if (PendingReply is { MayRetry: true } retryRecord)
        {
            record = retryRecord;
        }
        else
        {
            var text = rawText.Trim();
            if (!ReplyAvailable || string.IsNullOrEmpty(text) || text.Length > 8000) return;

            SessionModelSelectionDto? submittedSelection = null;
            if (ModelOverrides.Selection is { } sel && !string.IsNullOrWhiteSpace(sel.Model))
            {
                var catalog = Controls?.Catalog;
                if (!SessionControlPolicy.IsCatalogUsable(catalog, DateTimeOffset.UtcNow)
                    || catalog!.CatalogRevision != sel.CatalogRevision)
                {
                    _hub.Notice = "模型列表已更新或过期，请先打开「模型设置」确认或恢复继承电脑设置";
                    Notify();
                    return;
                }
                submittedSelection = new SessionModelSelectionDto
                {
                    Provider = sel.Provider,
                    CatalogRevision = sel.CatalogRevision,
                    Model = sel.Model,
                    Effort = sel.Effort,
                    ApplyAt = "next_message",
                    ServiceTier = ModelOverrides.FastNext ? "priority" : null,
                    CollaborationMode = ModelOverrides.PlanNext ? "plan" : null
                };
            }

            record = new PersistedPendingReply
            {
                Id = Guid.NewGuid().ToString("D").ToLowerInvariant(),
                TaskId = TaskId,
                Text = text,
                CreatedAt = DateTimeOffset.UtcNow,
                State = "uncertain",
                SubmittedSelection = submittedSelection
            };
            DpapiVault.Write(_hub.Origin, _replyVaultKey, record);
            if (ModelOverrides.FastNext || ModelOverrides.PlanNext)
            {
                SaveModelOverrides(ModelOverrides.Selection, false, false);
            }
        }

        PendingReply = record;
        ReplySending = true;
        ReplyDelivered = false;
        ReplyReceiptTrail = SessionControlPolicy.ReplyReceiptTrail(true, "", false);
        SettingsReceiptText = SessionControlPolicy.FormatSettingsReceipt(null, record.SubmittedSelection);
        ReplyMessage = "正在发送…";
        Notify();

        var body = new Dictionary<string, object?>
        {
            ["task_id"] = TaskId,
            ["request_id"] = record.Id,
            ["text"] = record.Text
        };
        if (record.SubmittedSelection is not null)
            body["selection"] = record.SubmittedSelection;

        try
        {
            var res = await _hub.Api.JsonAsync<ReplyStateDto>(_hub.Origin, "/api/native/tasks/reply", HttpMethod.Post, body, _token, ct: _cts.Token);
            if (IsActive && res.Id == record.Id && !string.IsNullOrEmpty(res.State))
                UpdatePendingState(res.State, res.Reason);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            if (IsActive)
            {
                UpdatePendingState("uncertain", "发送结果尚未确认，正在查询");
                _hub.HandleApiError(ex, _token, showAlert: false);
            }
        }
        finally
        {
            ReplySending = false;
            Notify();
        }
        await RefreshReplyStateAsync();
    }

    private void Notify() => Changed?.Invoke();

    public void Dispose() => _cts.Cancel();
}
