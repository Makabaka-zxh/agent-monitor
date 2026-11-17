"""Bounded, opt-in explanations from final text or fixed runtime error enums.

These rules report what the answer or provider reports. They do not inspect tool
results, prompts, reasoning, files, or the network and cannot establish a root
cause. No unbounded/raw diagnostic text is copied into the analysis payload.
"""
from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


Category = Literal["syntax", "dependency", "permission", "authentication", "rate_limit", "quota_exhausted",
                   "network", "timeout", "test_failure", "unknown", "billing", "account_restricted",
                   "service_unavailable", "context_limit", "invalid_request", "model_unavailable", "output_limit"]


class ErrorLocation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    file: str = Field(max_length=100)
    line: int | None = Field(default=None, ge=1, le=10_000_000)
    column: int | None = Field(default=None, ge=1, le=100_000)
    step: str = Field(max_length=40)


class ErrorAnalysis(BaseModel):
    model_config = ConfigDict(extra="forbid")
    category: Category
    summary: str = Field(max_length=180)
    location: ErrorLocation | None = None
    suggestion: str = Field(max_length=180)
    confidence: Literal["reported", "unknown"]
    source: Literal["final_output", "runtime_event"] = "final_output"


# Labels, explanations and advice are fixed text, not extracted prose. Match
# explicit exception/error reports rather than guessing from ordinary keywords.
RULES = (
    ("quota_exhausted", r"\b(?:insufficient_quota|UsageLimitExceeded|usage_limit_exceeded)\b|\b(?:API|token|usage) quota (?:exceeded|exhausted)\b|(?:使用|用量|本周|本月)?额度(?:已)?(?:用完|耗尽)|(?:模型|API|账户|账号|本周|本月)(?:已)?达到使用上限",
     "最终输出报告了可用额度耗尽；尚未独立核验账户额度。", "查看对应账户的额度与恢复时间，等待额度恢复；不要反复重试或自动使用额外付费额度。", "账户额度"),
    ("syntax", r"\b(?:SyntaxError|IndentationError|TabError)\b|error TS\d{3,5}\b|语法错误|编译失败",
     "最终输出报告了语法或编译错误；尚未独立验证根因。", "检查所报告位置的语法、类型及相邻代码，再运行对应检查。", "编译或解析"),
    ("dependency", r"\b(?:ModuleNotFoundError|ImportError)\b|Cannot find module|No module named|依赖缺失",
     "最终输出报告了依赖或模块加载错误；尚未独立验证根因。", "核对当前环境中的依赖是否安装、版本是否匹配，以及模块路径是否正确。", "依赖加载"),
    ("permission", r"\b(?:PermissionError|EACCES|EPERM)\b|permission denied|拒绝访问|权限不足",
     "最终输出报告了访问权限错误；尚未独立验证根因。", "确认失败操作所需的最小权限和目标访问权限，避免直接扩大权限。", "资源访问"),
    ("authentication", r"\b(?:AuthenticationError|Unauthorized|invalid_api_key)\b|HTTP\s*401\b|身份验证失败|认证失败",
     "最终输出报告了身份验证失败；尚未独立验证根因。", "在对应服务中检查登录状态或凭据有效性，不要把凭据粘贴到任务中。", "身份验证"),
    ("rate_limit", r"\b(?:RateLimitError|rate_limit_exceeded)\b|HTTP\s*429\b|too many requests|请求被限流",
     "最终输出报告了请求限流；尚未独立验证触发原因。", "按服务给出的等待时间恢复请求，避免反复重试。", "服务请求"),
    ("network", r"\b(?:ConnectionError|ECONNREFUSED|ENOTFOUND|SSLError)\b|connection refused|certificate verify failed|连接失败",
     "最终输出报告了连接或证书错误；尚未独立验证根因。", "检查对应服务是否可用及错误发生的请求步骤，不要跳过证书校验。", "网络连接"),
    ("timeout", r"\b(?:TimeoutError|ETIMEDOUT)\b|request timed out|请求超时",
     "最终输出报告了操作超时；尚未独立验证根因。", "检查超时的操作和耗时，再判断是否需要优化工作量或调整合理的等待时间。", "等待操作完成"),
    ("test_failure", r"\bAssertionError\b|(?:^|\n)FAILED\s|测试失败|断言失败",
     "最终输出报告了测试或断言失败；尚未独立验证根因。", "先查看失败断言及预期与实际结果，再单独运行对应测试确认。", "测试验证"),
)

UNKNOWN = ("最终输出未提供可可靠归类的错误证据，具体原因尚未确认。",
           "在电脑上查看本轮失败步骤，确认明确错误后再处理；不要仅凭状态猜测根因。")
EXTENSIONS = r"(?:py|js|mjs|cjs|jsx|ts|tsx|java|kt|kts|go|rs|c|cc|cpp|h|hpp|cs|swift|rb|php|sh|ps1)"
# Paths can be present in already-consented output, but only source-code
# basenames and explicit positive line/column positions may leave this helper.
LOCATION = re.compile(
    rf'(?:File\s+["\'](?P<py>[^"\'\r\n]{{1,512}}\.{EXTENSIONS})["\'],\s*line\s+(?P<pyline>\d{{1,8}})(?!\d)'
    rf'|(?<![A-Za-z0-9_.-])(?P<file>[A-Za-z0-9_.-]{{1,100}}\.{EXTENSIONS}):(?P<line>\d{{1,8}})(?!\d)(?::(?P<column>\d{{1,6}})(?!\d))?(?![:\d]))', re.IGNORECASE)

# A final answer can discuss earlier, ruled-out or hypothetical failures. When
# it does, avoid turning that prose into a confident report about this failure.
CONTEXT_UNCERTAIN = re.compile(
    r"\b(?:no|not|without|never|resolved|fixed|example|hypothetical|would|if)\b|"
    r"未出现|没有|并非|不是|已修复|已解决|例如|假如|如果|可能", re.IGNORECASE)
QUOTA_RESOURCE_CONTEXT = re.compile(r"\b(?:disk|storage|filesystem)\b|磁盘|硬盘|存储|文件系统", re.IGNORECASE)


def analyze_final_text(text: str) -> dict:
    """Return only fixed explanations and a single unambiguous reported location."""
    text = text[:12000]
    evidence_lines = []
    for line in text.splitlines():
        # "No module named" is itself a standard explicit diagnostic.
        context = re.sub(r"\bNo module named\b", "", line, flags=re.IGNORECASE)
        if not CONTEXT_UNCERTAIN.search(context):
            evidence_lines.append(line)
    text = "\n".join(evidence_lines)
    quota_text = "\n".join(line for line in evidence_lines if not QUOTA_RESOURCE_CONTEXT.search(line))
    matches = [(category, summary, suggestion, step) for category, pattern, summary, suggestion, step in RULES
               if re.search(pattern, quota_text if category == "quota_exhausted" else text, re.IGNORECASE)]
    # A quota response may also carry HTTP 429. Only collapse that pair when
    # both markers occur on the same line; separate failures remain ambiguous.
    if {item[0] for item in matches} == {"quota_exhausted", "rate_limit"}:
        quota_pattern = next(item[1] for item in RULES if item[0] == "quota_exhausted")
        rate_pattern = next(item[1] for item in RULES if item[0] == "rate_limit")
        rate_lines = [line for line in evidence_lines if re.search(rate_pattern, line, re.IGNORECASE)]
        if rate_lines and all(not QUOTA_RESOURCE_CONTEXT.search(line) and re.search(quota_pattern, line, re.IGNORECASE) for line in rate_lines):
            matches = [item for item in matches if item[0] == "quota_exhausted"]
    if len(matches) != 1:
        category, summary, suggestion, step = "unknown", *UNKNOWN, ""
    else:
        category, summary, suggestion, step = matches[0]
    locations = set()
    for match in LOCATION.finditer(text):
        basename = (match.group("py") or match.group("file")).replace("\\", "/").rsplit("/", 1)[-1]
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", basename) or basename.startswith("."):
            continue
        line = int(match.group("pyline") or match.group("line"))
        column = int(match.group("column")) if match.group("column") else None
        if 1 <= line <= 10_000_000 and (column is None or 1 <= column <= 100_000):
            locations.add((basename, line, column))
    location = None
    # A stack with several frames does not prove which frame caused the failure.
    if category != "unknown" and len(locations) == 1:
        file, line, column = next(iter(locations))
        location = {"file": file, "line": line, "column": column, "step": step}
    return {"category": category, "summary": summary, "location": location, "suggestion": suggestion,
            "confidence": "reported" if category != "unknown" else "unknown", "source": "final_output"}


def sanitize_task_analysis(task: dict, *, enabled: bool) -> dict | None:
    """Enforce output consent again at ingestion; never trust remote free text.

    A collector supplies the analysis only after proving a current-turn final.
    Recompute it from that task's permitted output so remote extra fields or
    arbitrary summaries/paths cannot become a second diagnostic payload.
    """
    incoming = task.get("error_analysis")
    output = task.get("output")
    if not enabled or task.get("status") != "error":
        return None
    # The normalized enum is the only evidence carried from a runtime failure.
    # Ignore remote prose and reconstruct the exact public explanation locally.
    from .failure_reasons import runtime_analysis
    runtime = runtime_analysis(task.get("failure_code"))
    if runtime is not None:
        return runtime
    if not isinstance(incoming, dict) or not isinstance(output, str) or not output.strip():
        return None
    expected = analyze_final_text(output)
    return expected if incoming == expected else None
