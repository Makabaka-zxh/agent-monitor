using System.Globalization;
using System.Text.Json.Serialization;

namespace MonitorApp.Core;

public enum ToolFilter
{
    All,
    Claude,
    Codex,
    Antigravity
}

public enum TaskScope
{
    Active,
    All,
    Archived
}

public static class ToolFilterExtensions
{
    public static string WireKey(this ToolFilter filter) => filter switch
    {
        ToolFilter.Claude => "claude",
        ToolFilter.Codex => "codex",
        ToolFilter.Antigravity => "antigravity",
        _ => "all"
    };

    public static string Title(this ToolFilter filter) => filter switch
    {
        ToolFilter.Claude => "Claude Code",
        ToolFilter.Codex => "Codex",
        ToolFilter.Antigravity => "Antigravity",
        _ => "全部"
    };

    public static ToolFilter ParseTool(string? raw) => raw switch
    {
        "claude" => ToolFilter.Claude,
        "codex" => ToolFilter.Codex,
        "antigravity" => ToolFilter.Antigravity,
        _ => ToolFilter.All
    };
}

public static class TaskScopeExtensions
{
    public static string WireKey(this TaskScope scope) => scope switch
    {
        TaskScope.All => "all",
        TaskScope.Archived => "archived",
        _ => "active"
    };

    public static string Title(this TaskScope scope) => scope switch
    {
        TaskScope.All => "全部任务",
        TaskScope.Archived => "已归档",
        _ => "进行中"
    };

    public static TaskScope ParseScope(string? raw) => raw switch
    {
        "all" => TaskScope.All,
        "archived" => TaskScope.Archived,
        _ => TaskScope.Active
    };
}

public static class MonitorDate
{
    public static DateTimeOffset? Parse(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var dto))
            return dto;
        return null;
    }
}

public sealed class TaskUsageSummary
{
    [JsonPropertyName("tool")] public string? Tool { get; set; }
    [JsonPropertyName("coverage")] public string? Coverage { get; set; }
    [JsonPropertyName("total_tokens")] public long? TotalTokens { get; set; }
    [JsonPropertyName("input_tokens")] public long? InputTokens { get; set; }
    [JsonPropertyName("output_tokens")] public long? OutputTokens { get; set; }
    [JsonPropertyName("cached_input_tokens")] public long? CachedInputTokens { get; set; }
    [JsonPropertyName("cache_write_tokens")] public long? CacheWriteTokens { get; set; }
    [JsonPropertyName("observed_at")] public string? ObservedAt { get; set; }
}

public sealed class ErrorLocationDto
{
    [JsonPropertyName("file")] public string? File { get; set; }
    [JsonPropertyName("line")] public int? Line { get; set; }
    [JsonPropertyName("column")] public int? Column { get; set; }
    [JsonPropertyName("step")] public string? Step { get; set; }
}

public sealed class ErrorAnalysisDto
{
    [JsonPropertyName("category")] public string? Category { get; set; }
    [JsonPropertyName("summary")] public string? Summary { get; set; }
    [JsonPropertyName("location")] public ErrorLocationDto? Location { get; set; }
    [JsonPropertyName("suggestion")] public string? Suggestion { get; set; }
    [JsonPropertyName("confidence")] public string? Confidence { get; set; }
    [JsonPropertyName("source")] public string? Source { get; set; }
}

public sealed class MonitorTask
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("tool")] public string Tool { get; set; } = "";
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("device_id")] public string DeviceId { get; set; } = "";
    [JsonPropertyName("device_name")] public string? DeviceName { get; set; }
    [JsonPropertyName("project")] public string? Project { get; set; }
    [JsonPropertyName("project_id")] public string? ProjectId { get; set; }
    [JsonPropertyName("preview")] public string? Preview { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
    [JsonPropertyName("final_result_id")] public string? FinalResultId { get; set; }
    [JsonPropertyName("archived")] public bool Archived { get; set; }
    [JsonPropertyName("stale")] public bool? Stale { get; set; }
    [JsonPropertyName("pending_interaction_kind")] public string? PendingInteractionKind { get; set; }
    [JsonPropertyName("continuation_state")] public string? ContinuationState { get; set; }
    [JsonPropertyName("failure_code")] public string? FailureCode { get; set; }
    [JsonPropertyName("error_analysis")] public ErrorAnalysisDto? ErrorAnalysis { get; set; }
    [JsonPropertyName("usage")] public TaskUsageSummary? Usage { get; set; }

    public static string BrandFor(string? tool) => tool switch
    {
        "claude" => "Claude Code",
        "codex" => "Codex",
        "antigravity" => "Antigravity",
        _ => "任务"
    };

    [JsonIgnore] public string Brand => BrandFor(Tool);
    [JsonIgnore] public bool SupportsReply => Tool is "claude" or "codex" or "antigravity";

    [JsonIgnore]
    public string EffectivePendingKind =>
        Archived || Stale == true || !SupportsReply
            ? ""
            : PendingInteractionKind is "question" or "approval" ? PendingInteractionKind : "";

    [JsonIgnore]
    public string EffectiveStatus =>
        !string.IsNullOrEmpty(EffectivePendingKind) ? "waiting" : Status;

    [JsonIgnore]
    public string StatusTitle => EffectivePendingKind switch
    {
        "question" => "等待回答",
        "approval" => "等待批准",
        _ => Status switch
        {
            "running" => "进行中",
            "waiting" => "待处理",
            "completed" => "已完成",
            "idle" => "待命",
            "error" => "需要关注",
            _ => "状态未知"
        }
    };

    public string EffectiveContinuation(bool fresh, bool deviceOnline = true) =>
        Archived || !fresh || !deviceOnline || Stale == true
            ? ""
            : ContinuationState is "continuable" or "computer_in_use" or "view_only"
                ? ContinuationState!
                : "";

    public static string ContinuationTitleFor(string? state) => state switch
    {
        "continuable" => "可在手机继续",
        "computer_in_use" => "电脑正在使用",
        "view_only" => "仅可查看",
        _ => ""
    };

    public bool Matches(ToolFilter filter, TaskScope scope, bool fresh, string? query = null)
    {
        if (filter != ToolFilter.All && Tool != filter.WireKey()) return false;
        if (scope == TaskScope.Archived)
        {
            if (!Archived) return false;
        }
        else
        {
            if (Archived) return false;
            if (scope == TaskScope.Active && !(fresh && Stale != true && EffectiveStatus is "running" or "waiting" or "error"))
                return false;
        }
        if (!string.IsNullOrWhiteSpace(query))
        {
            var bounded = query.Length <= 240 ? query : query[..240];
            var terms = bounded.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (terms.Length > 0)
            {
                var haystack = $"{Title} {Project} {DeviceName} {Brand}";
                for (var i = 0; i < Math.Min(terms.Length, 16); i++)
                {
                    if (!haystack.Contains(terms[i], StringComparison.OrdinalIgnoreCase))
                        return false;
                }
            }
        }
        return true;
    }
}

public sealed class DeviceSourceDto
{
    [JsonPropertyName("tool")] public string Tool { get; set; } = "";
    [JsonPropertyName("available")] public bool Available { get; set; }
    [JsonPropertyName("mode")] public string? Mode { get; set; }
    [JsonPropertyName("detail")] public string? Detail { get; set; }
}

public sealed class ProjectSummaryDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("tools")] public List<string>? Tools { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
}

public sealed class DevPortDto
{
    [JsonPropertyName("port")] public int Port { get; set; }
    [JsonPropertyName("bind_scope")] public string BindScope { get; set; } = "";
    [JsonPropertyName("process")] public string? Process { get; set; }
    [JsonPropertyName("project_id")] public string? ProjectId { get; set; }
}

public sealed class Computer
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("platform")] public string Platform { get; set; } = "";
    [JsonPropertyName("online")] public bool Online { get; set; }
    [JsonPropertyName("local")] public bool Local { get; set; }
    [JsonPropertyName("last_seen")] public string? LastSeen { get; set; }
    [JsonPropertyName("sources")] public List<DeviceSourceDto>? Sources { get; set; }
    [JsonPropertyName("supported_tools")] public List<string>? SupportedTools { get; set; }
    [JsonPropertyName("projects")] public List<ProjectSummaryDto>? Projects { get; set; }
    [JsonPropertyName("dev_ports")] public List<DevPortDto>? DevPorts { get; set; }
    [JsonPropertyName("preview_hosts")] public List<string>? PreviewHosts { get; set; }
}

public sealed class PreferencesDto
{
    [JsonPropertyName("sync_output")] public bool SyncOutput { get; set; }
    [JsonPropertyName("tool_filter")] public string? ToolFilter { get; set; }
}

public sealed class SyncTimingsDto
{
    [JsonPropertyName("computer_completed_at")] public string? ComputerCompletedAt { get; set; }
    [JsonPropertyName("server_received_at")] public string? ServerReceivedAt { get; set; }
    [JsonPropertyName("server_generated_at")] public string? ServerGeneratedAt { get; set; }
}

public sealed class WorkbenchResponse
{
    [JsonPropertyName("devices")] public List<Computer> Devices { get; set; } = [];
    [JsonPropertyName("tasks")] public List<MonitorTask> Tasks { get; set; } = [];
    [JsonPropertyName("preferences")] public PreferencesDto Preferences { get; set; } = new();
    [JsonPropertyName("usage")] public UsageSummaryDto? Usage { get; set; }
    [JsonPropertyName("sync_timings")] public SyncTimingsDto? SyncTimings { get; set; }
    [JsonPropertyName("generated_at")] public string? GeneratedAt { get; set; }
}

public sealed class ProfileDto
{
    [JsonPropertyName("username")] public string Username { get; set; } = "";
    [JsonPropertyName("display_name")] public string? DisplayName { get; set; }
    [JsonPropertyName("avatar")] public string? Avatar { get; set; }
    [JsonIgnore] public string DisplayOrUsername => !string.IsNullOrWhiteSpace(DisplayName) ? DisplayName! : Username;
}

public sealed class NativeConnectionDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("last_seen")] public string? LastSeen { get; set; }
    [JsonPropertyName("current")] public bool? Current { get; set; }
}

public sealed class BrowserSessionDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("last_seen")] public string? LastSeen { get; set; }
    [JsonPropertyName("current")] public bool? Current { get; set; }
    [JsonPropertyName("authorizes_current")] public bool? AuthorizesCurrent { get; set; }
}

public sealed class AccountResponse
{
    [JsonPropertyName("user")] public ProfileDto User { get; set; } = new();
    [JsonPropertyName("preferences")] public PreferencesDto Preferences { get; set; } = new();
    [JsonPropertyName("google_linked")] public bool? GoogleLinked { get; set; }
    [JsonPropertyName("sessions")] public List<BrowserSessionDto>? Sessions { get; set; }
    [JsonPropertyName("native_devices")] public List<NativeConnectionDto>? NativeDevices { get; set; }
    [JsonPropertyName("current_reader_id")] public string? CurrentReaderId { get; set; }
}

public sealed class PairingStartDto
{
    [JsonPropertyName("request_id")] public string RequestId { get; set; } = "";
    [JsonPropertyName("verification_url")] public string VerificationUrl { get; set; } = "";
    [JsonPropertyName("expires_at")] public string ExpiresAt { get; set; } = "";
}

public sealed class PairingPollDto
{
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("reader_token")] public string? ReaderToken { get; set; }
    [JsonPropertyName("mode")] public string? Mode { get; set; }
    [JsonPropertyName("expires_at")] public string? ExpiresAt { get; set; }
}

public sealed class QrPairingStartDto
{
    [JsonPropertyName("request_id")] public string RequestId { get; set; } = "";
    [JsonPropertyName("poll_secret")] public string PollSecret { get; set; } = "";
    [JsonPropertyName("verification_url")] public string VerificationUrl { get; set; } = "";
    [JsonPropertyName("expires_at")] public string ExpiresAt { get; set; } = "";
    [JsonPropertyName("interval")] public int? Interval { get; set; }
    [JsonPropertyName("include_collector")] public bool? IncludeCollector { get; set; }
    [JsonPropertyName("include_reader")] public bool? IncludeReader { get; set; }
}

public sealed class QrPairingPollDto
{
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("reader_token")] public string? ReaderToken { get; set; }
    [JsonPropertyName("reader_expires_at")] public string? ReaderExpiresAt { get; set; }
    [JsonPropertyName("reader_mode")] public string? ReaderMode { get; set; }
    [JsonPropertyName("collector_code")] public string? CollectorCode { get; set; }
    [JsonPropertyName("collector_code_expires_at")] public string? CollectorCodeExpiresAt { get; set; }
    [JsonPropertyName("interval")] public int? Interval { get; set; }
}

public sealed class ComputerPairingCodeDto
{
    [JsonPropertyName("code")] public string Code { get; set; } = "";
    [JsonPropertyName("expires_at")] public string ExpiresAt { get; set; } = "";
}

public sealed class ResultFileDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("size")] public int Size { get; set; }
    [JsonPropertyName("sha256")] public string Sha256 { get; set; } = "";
    [JsonPropertyName("ready")] public bool? Ready { get; set; }
}

public sealed class TaskResultDto
{
    [JsonPropertyName("available")] public bool Available { get; set; }
    [JsonPropertyName("text")] public string? Text { get; set; }
    [JsonPropertyName("result_id")] public string? ResultId { get; set; }
    [JsonPropertyName("completed_at")] public string? CompletedAt { get; set; }
    [JsonPropertyName("truncated")] public bool? Truncated { get; set; }
    [JsonPropertyName("files")] public List<ResultFileDto>? Files { get; set; }
    [JsonPropertyName("txt_size")] public int? TxtSize { get; set; }
    [JsonPropertyName("txt_sha256")] public string? TxtSha256 { get; set; }
    [JsonPropertyName("reason")] public string? Reason { get; set; }
}

public sealed class AppliedSettingsDto
{
    [JsonPropertyName("model")] public string? Model { get; set; }
    [JsonPropertyName("effort")] public string? Effort { get; set; }
    [JsonPropertyName("service_tier")] public string? ServiceTier { get; set; }
    [JsonPropertyName("collaboration_mode")] public string? CollaborationMode { get; set; }
}

public sealed class ReplyCommandDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("state")] public string State { get; set; } = "";
    [JsonPropertyName("reason")] public string? Reason { get; set; }
    [JsonPropertyName("delivery_ms")] public int? DeliveryMs { get; set; }
    [JsonPropertyName("claimed_at")] public string? ClaimedAt { get; set; }
    [JsonPropertyName("created_task_id")] public string? CreatedTaskId { get; set; }
    [JsonPropertyName("requested_selection")] public SessionModelSelectionDto? RequestedSelection { get; set; }
    [JsonPropertyName("applied")] public AppliedSettingsDto? Applied { get; set; }
}

public sealed class ReplyStateDto
{
    [JsonPropertyName("id")] public string? Id { get; set; }
    [JsonPropertyName("state")] public string? State { get; set; }
    [JsonPropertyName("found")] public bool? Found { get; set; }
    [JsonPropertyName("available")] public bool? Available { get; set; }
    [JsonPropertyName("reason")] public string? Reason { get; set; }
    [JsonPropertyName("claimed_at")] public string? ClaimedAt { get; set; }
    [JsonPropertyName("created_task_id")] public string? CreatedTaskId { get; set; }
    [JsonPropertyName("requested_selection")] public SessionModelSelectionDto? RequestedSelection { get; set; }
    [JsonPropertyName("applied")] public AppliedSettingsDto? Applied { get; set; }
    [JsonPropertyName("latest")] public ReplyCommandDto? Latest { get; set; }
}

public sealed class TaskStartPayloadDto
{
    [JsonPropertyName("device_id")] public string DeviceId { get; set; } = "";
    [JsonPropertyName("project_id")] public string ProjectId { get; set; } = "";
    [JsonPropertyName("tool")] public string Tool { get; set; } = "";
    [JsonPropertyName("request_id")] public string RequestId { get; set; } = "";
    [JsonPropertyName("text")] public string Text { get; set; } = "";
    [JsonPropertyName("selection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public SessionModelSelectionDto? Selection { get; set; }
}

public sealed class LoginSecret
{
    public string Token { get; set; } = "";
    public DateTimeOffset ExpiresAt { get; set; }
}

public sealed class PairingSecret
{
    public string Id { get; set; } = "";
    public string Verifier { get; set; } = "";
    public DateTimeOffset ExpiresAt { get; set; }
}

public sealed class QrLoginSession
{
    public required string Id { get; init; }
    public required string PollSecret { get; init; }
    public required string Verifier { get; init; }
    public required string VerificationUrl { get; init; }
    public required DateTimeOffset ExpiresAt { get; init; }
    public required bool IncludeCollector { get; init; }
    public required int IntervalSeconds { get; init; }
}

// --- Usage DTOs ---

public sealed class UsageDayModelDto
{
    [JsonPropertyName("model")] public string Model { get; set; } = "";
    [JsonPropertyName("tokens")] public long? Tokens { get; set; }
}

public sealed class UsageDayDto
{
    [JsonPropertyName("day")] public string Day { get; set; } = "";
    [JsonPropertyName("tokens")] public long? Tokens { get; set; }
    [JsonPropertyName("coverage")] public string? Coverage { get; set; }
    [JsonPropertyName("models")] public List<UsageDayModelDto>? Models { get; set; }
    [JsonPropertyName("unknown_model_tokens")] public long? UnknownModelTokens { get; set; }
}

public sealed class UsageHistoryDto
{
    [JsonPropertyName("start_day")] public string? StartDay { get; set; }
    [JsonPropertyName("end_day")] public string? EndDay { get; set; }
    [JsonPropertyName("timezone")] public string? Timezone { get; set; }
    [JsonPropertyName("days")] public List<UsageDayDto>? Days { get; set; }
}

public sealed class UsageQuotaDto
{
    [JsonPropertyName("key")] public string Key { get; set; } = "";
    [JsonPropertyName("label")] public string? Label { get; set; }
    [JsonPropertyName("bucket_id")] public string? BucketId { get; set; }
    [JsonPropertyName("bucket_label")] public string? BucketLabel { get; set; }
    [JsonPropertyName("model")] public string? Model { get; set; }
    [JsonPropertyName("used_percent")] public double? UsedPercent { get; set; }
    [JsonPropertyName("remaining_percent")] public double? RemainingPercent { get; set; }
    [JsonPropertyName("window_minutes")] public long? WindowMinutes { get; set; }
    [JsonPropertyName("resets_at")] public long? ResetsAt { get; set; }
    [JsonPropertyName("observed_at")] public string? ObservedAt { get; set; }
    [JsonPropertyName("source_device_id")] public string? SourceDeviceId { get; set; }
    [JsonPropertyName("source_name")] public string? SourceName { get; set; }
    [JsonPropertyName("stale")] public bool Stale { get; set; }
    [JsonPropertyName("availability")] public string? Availability { get; set; }

    [JsonIgnore]
    public double? Remaining => RemainingPercent ?? (UsedPercent is { } u ? Math.Clamp(100.0 - u, 0.0, 100.0) : null);

    [JsonIgnore]
    public double? Used => UsedPercent ?? (RemainingPercent is { } r ? Math.Clamp(100.0 - r, 0.0, 100.0) : null);

    [JsonIgnore]
    public DateTimeOffset? ResetDate =>
        ResetsAt is > 946684800 and < 4102444800 ? DateTimeOffset.FromUnixTimeSeconds(ResetsAt.Value) : null;

    public bool IsExpired(DateTimeOffset now) =>
        Availability == "expired" || (ResetDate is { } r && r <= now);
}

public sealed class UsageQuotaCheckDto
{
    [JsonPropertyName("tool")] public string Tool { get; set; } = "";
    [JsonPropertyName("source_device_id")] public string? SourceDeviceId { get; set; }
    [JsonPropertyName("source_name")] public string? SourceName { get; set; }
    [JsonPropertyName("state")] public string? State { get; set; }
    [JsonPropertyName("status")] public string? Status { get; set; }
    [JsonPropertyName("last_attempt_at")] public string? LastAttemptAt { get; set; }
    [JsonPropertyName("retry_after_seconds")] public long? RetryAfterSeconds { get; set; }

    [JsonIgnore] public string EffectiveState => !string.IsNullOrWhiteSpace(State) ? State! : (Status ?? "unavailable");
}

public sealed class UsageProviderDto
{
    [JsonPropertyName("tool")] public string Tool { get; set; } = "";
    [JsonPropertyName("today_tokens")] public long? TodayTokens { get; set; }
    [JsonPropertyName("session_count")] public long? SessionCount { get; set; }
    [JsonPropertyName("observed_at")] public string? ObservedAt { get; set; }
    [JsonPropertyName("coverage")] public string? Coverage { get; set; }
    [JsonPropertyName("quotas")] public List<UsageQuotaDto>? Quotas { get; set; }
    [JsonPropertyName("history")] public UsageHistoryDto? History { get; set; }

    [JsonIgnore] public string Brand => MonitorTask.BrandFor(Tool);
}

public sealed class UsageSummaryDto
{
    [JsonPropertyName("day")] public string? Day { get; set; }
    [JsonPropertyName("timezone")] public string? Timezone { get; set; }
    [JsonPropertyName("loading")] public bool Loading { get; set; }
    [JsonPropertyName("history_included")] public bool HistoryIncluded { get; set; }
    [JsonPropertyName("providers")] public List<UsageProviderDto>? Providers { get; set; }
    [JsonPropertyName("quota_checks")] public List<UsageQuotaCheckDto>? QuotaChecks { get; set; }
}

// --- Session Controls DTOs ---

public sealed class SessionCapabilitiesDto
{
    [JsonPropertyName("questions")] public bool Questions { get; set; }
    [JsonPropertyName("approvals")] public bool Approvals { get; set; }
    [JsonPropertyName("model_settings")] public bool ModelSettings { get; set; }
}

public sealed class SessionModelOptionDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("efforts")] public List<string>? Efforts { get; set; }
    [JsonPropertyName("service_tiers")] public List<string>? ServiceTiers { get; set; }
}

public sealed class SessionModelCatalogDto
{
    [JsonPropertyName("provider")] public string Provider { get; set; } = "";
    [JsonPropertyName("catalog_revision")] public string CatalogRevision { get; set; } = "";
    [JsonPropertyName("valid_until")] public double? ValidUntil { get; set; }
    [JsonPropertyName("models")] public List<SessionModelOptionDto>? Models { get; set; }
    [JsonPropertyName("collaboration_modes")] public List<string>? CollaborationModes { get; set; }
    [JsonPropertyName("current_model")] public string? CurrentModel { get; set; }
    [JsonPropertyName("current_effort")] public string? CurrentEffort { get; set; }
}

public sealed class SessionChoiceDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("description")] public string? Description { get; set; }
}

public sealed class SessionQuestionDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("text")] public string Text { get; set; } = "";
    [JsonPropertyName("multiple")] public bool? Multiple { get; set; }
    [JsonPropertyName("allows_text")] public bool? AllowsText { get; set; }
    [JsonPropertyName("options")] public List<SessionChoiceDto>? Options { get; set; }
}

public sealed class SessionPendingRequestDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("revision")] public string Revision { get; set; } = "";
    [JsonPropertyName("turn_id")] public string TurnId { get; set; } = "";
    [JsonPropertyName("provider")] public string Provider { get; set; } = "";
    [JsonPropertyName("kind")] public string Kind { get; set; } = "";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("detail")] public string? Detail { get; set; }
    [JsonPropertyName("operation")] public string? Operation { get; set; }
    [JsonPropertyName("questions")] public List<SessionQuestionDto>? Questions { get; set; }
    [JsonPropertyName("expires_at")] public double? ExpiresAt { get; set; }
    [JsonPropertyName("state")] public string State { get; set; } = "";
    [JsonPropertyName("decision_id")] public string? DecisionId { get; set; }
    [JsonPropertyName("delivered")] public bool? Delivered { get; set; }
    [JsonPropertyName("decision_kind")] public string? DecisionKind { get; set; }
    [JsonPropertyName("outcome")] public string? Outcome { get; set; }
    [JsonPropertyName("submitted_at")] public string? SubmittedAt { get; set; }
    [JsonPropertyName("delivered_at")] public string? DeliveredAt { get; set; }
    [JsonPropertyName("resolved_at")] public string? ResolvedAt { get; set; }
}

public sealed class TaskControlsResponseDto
{
    [JsonPropertyName("catalog")] public SessionModelCatalogDto? Catalog { get; set; }
    [JsonPropertyName("catalog_status")] public string? CatalogStatus { get; set; }
    [JsonPropertyName("pending")] public List<SessionPendingRequestDto>? Pending { get; set; }
    [JsonPropertyName("capabilities")] public SessionCapabilitiesDto? Capabilities { get; set; }
    [JsonPropertyName("reason")] public string? Reason { get; set; }
}

public sealed class SessionModelSelectionDto
{
    [JsonPropertyName("provider")] public string Provider { get; set; } = "";
    [JsonPropertyName("catalog_revision")] public string CatalogRevision { get; set; } = "";
    [JsonPropertyName("model")] public string Model { get; set; } = "";
    [JsonPropertyName("effort")] public string? Effort { get; set; }
    [JsonPropertyName("apply_at")] public string ApplyAt { get; set; } = "next_message";
    [JsonPropertyName("service_tier")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ServiceTier { get; set; }
    [JsonPropertyName("collaboration_mode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? CollaborationMode { get; set; }
}

public sealed class SessionAnswerPayloadDto
{
    [JsonPropertyName("question_id")] public string QuestionId { get; set; } = "";
    [JsonPropertyName("choices")] public List<string> Choices { get; set; } = [];
    [JsonPropertyName("text")] public string? Text { get; set; }
}

public sealed class SessionDecisionPayloadDto
{
    [JsonPropertyName("task_id")] public string TaskId { get; set; } = "";
    [JsonPropertyName("request_id")] public string RequestId { get; set; } = "";
    [JsonPropertyName("revision")] public string Revision { get; set; } = "";
    [JsonPropertyName("decision_id")] public string DecisionId { get; set; } = "";
    [JsonPropertyName("decision")] public string Decision { get; set; } = "";
    [JsonPropertyName("answers")] public List<SessionAnswerPayloadDto> Answers { get; set; } = [];
}

public sealed class CollectorStatusDto
{
    [JsonPropertyName("installed")] public bool Installed { get; set; }
    [JsonPropertyName("paired")] public bool Paired { get; set; }
    [JsonPropertyName("service")] public string Service { get; set; } = "stopped";
    [JsonPropertyName("mode")] public string Mode { get; set; } = "status-only";
    [JsonPropertyName("mode_schema")] public int? ModeSchema { get; set; }
    [JsonPropertyName("supported_modes")] public List<string>? SupportedModes { get; set; }
    [JsonPropertyName("server_scope_version")] public int? ServerScopeVersion { get; set; }

    [JsonIgnore] public bool SupportsServerBinding => ServerScopeVersion == 1;
}
