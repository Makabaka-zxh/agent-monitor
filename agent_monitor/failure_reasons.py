"""Fixed classifications from explicit provider error codes, never raw messages.

These functions do not inspect prompts, tool output, error_details, credentials,
or account state. HTTP 429 alone is throttling, not proof of exhausted quota.
Sources: official Codex app-server Errors; Claude Code StopFailure input.
"""
from __future__ import annotations

from typing import Literal

FailureCode = Literal["quota_exhausted", "rate_limit", "authentication_failed", "billing_error",
    "account_restricted", "service_unavailable", "network_error", "context_limit",
    "invalid_request", "model_unavailable", "output_limit", "permission_denied"]

# All public descriptions are fixed and independent of an error's free text.
FAILURES = {
    "quota_exhausted": ("quota_exhausted", "服务报告本轮可用额度已达到上限。", "查看对应账户给出的额度恢复时间；不要反复重试或自动使用额外付费额度。"),
    "rate_limit": ("rate_limit", "服务报告请求被限流，尚不能据此判断账户额度是否耗尽。", "按服务提供的等待时间恢复请求，避免连续重试。"),
    "authentication_failed": ("authentication", "服务报告登录或凭据验证失败。", "在电脑端检查对应服务的登录状态；不要把凭据粘贴到任务中。"),
    "billing_error": ("billing", "服务报告账户计费状态异常。", "在对应服务中检查计费状态；此提示不会自动充值或开启额外用量。"),
    "account_restricted": ("account_restricted", "服务报告账户或组织访问受限。", "在对应服务中核对账户状态或联系组织管理员。"),
    "service_unavailable": ("service_unavailable", "服务报告暂时繁忙或内部异常。", "稍后再确认服务状态；不要把它当作本地代码已经出错。"),
    "network_error": ("network", "服务报告连接失败或响应流中断。", "确认连接与服务状态后再继续，避免重复提交可能已执行的操作。"),
    "context_limit": ("context_limit", "服务报告本轮上下文超过可用长度。", "缩减输入或在确认保留必要上下文后开始新一轮；不要持续重试相同输入。"),
    "invalid_request": ("invalid_request", "服务报告请求参数不受支持或无效。", "检查当前模型支持的参数及客户端版本。"),
    "model_unavailable": ("model_unavailable", "服务报告所选模型不可用。", "刷新该账户的可用模型列表后重新选择。"),
    "output_limit": ("output_limit", "服务报告本轮输出达到长度限制。", "检查已生成的结果，再决定是否分段继续。"),
    "permission_denied": ("permission", "执行环境报告权限限制。", "核对具体操作所需的最小权限；不要自动扩大权限或跳过确认。"),
}
FAILURE_LABELS = {code: description[1] for code, description in FAILURES.items()}

_CODEX = {
    "UsageLimitExceeded": "quota_exhausted", "usageLimitExceeded": "quota_exhausted", "usage_limit_exceeded": "quota_exhausted",
    "ContextWindowExceeded": "context_limit", "contextWindowExceeded": "context_limit", "context_window_exceeded": "context_limit",
    "Unauthorized": "authentication_failed", "unauthorized": "authentication_failed",
    "SandboxError": "permission_denied", "sandboxError": "permission_denied", "sandbox_error": "permission_denied",
    "BadRequest": "invalid_request", "badRequest": "invalid_request", "bad_request": "invalid_request",
    "InternalServerError": "service_unavailable", "internalServerError": "service_unavailable", "internal_server_error": "service_unavailable",
    "ResponseStreamConnectionFailed": "network_error", "responseStreamConnectionFailed": "network_error", "response_stream_connection_failed": "network_error",
    "ResponseStreamDisconnected": "network_error", "responseStreamDisconnected": "network_error", "response_stream_disconnected": "network_error",
}
_HTTP = {"HttpConnectionFailed", "httpConnectionFailed", "http_connection_failed"}
_CLAUDE = {
    "rate_limit": "rate_limit", "overloaded": "service_unavailable",
    "authentication_failed": "authentication_failed", "oauth_org_not_allowed": "account_restricted",
    "account_on_hold": "account_restricted", "billing_error": "billing_error",
    "invalid_request": "invalid_request", "model_not_found": "model_unavailable",
    "server_error": "service_unavailable", "max_output_tokens": "output_limit",
    "cloud_credential_error": "authentication_failed",
}


def normalize_failure_code(value) -> str:
    return value if isinstance(value, str) and value in FAILURES else ""


def failure_code_for_claude(error_type) -> str:
    return _CLAUDE.get(error_type, "") if isinstance(error_type, str) else ""


def _http_code(details) -> str:
    if not isinstance(details, dict):
        return "network_error"
    status = details.get("httpStatusCode", details.get("http_status_code"))
    if type(status) is not int:
        return "network_error"
    if status == 429:
        return "rate_limit"
    if status == 401:
        return "authentication_failed"
    if status == 403:
        return "permission_denied"
    if 500 <= status <= 599:
        return "service_unavailable"
    return "network_error"


def has_codex_error_info(error_obj) -> bool:
    """Distinguish absent diagnostics from explicit unknown/conflicting data."""
    if not isinstance(error_obj, dict):
        return False
    return any(field in obj for obj in (error_obj, error_obj.get("error"))
               if isinstance(obj, dict) for field in ("codexErrorInfo", "codex_error_info"))


def failure_code_for_codex(error_obj) -> str:
    """Accept only a documented structured error-info field at known depths."""
    if not isinstance(error_obj, dict):
        return ""
    objects = [error_obj]
    if isinstance(error_obj.get("error"), dict):
        objects.append(error_obj["error"])
    seen = []
    for obj in objects:
        for field in ("codexErrorInfo", "codex_error_info"):
            if field not in obj or obj[field] is None:
                continue
            info = obj[field]
            if isinstance(info, str):
                code = _http_code(obj) if info in _HTTP else _CODEX.get(info, "")
            elif isinstance(info, dict) and len(info) == 1:
                kind, details = next(iter(info.items()))
                code = _http_code(details) if kind in _HTTP else _CODEX.get(kind, "")
            else:
                code = ""
            seen.append(code)
    # A conflicting/unknown second field must not silently disappear.
    return seen[0] if seen and seen[0] and len(set(seen)) == 1 else ""


def runtime_analysis(code) -> dict | None:
    code = normalize_failure_code(code)
    if not code:
        return None
    category, summary, suggestion = FAILURES[code]
    return {"category": category, "summary": summary, "suggestion": suggestion,
            "location": None, "confidence": "reported", "source": "runtime_event"}
