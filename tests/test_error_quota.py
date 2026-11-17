"""Synthetic failure reports only; never query a real account or spend quota."""
import pytest

from agent_monitor.error_analysis import analyze_final_text, sanitize_task_analysis
from agent_monitor.server import Task


@pytest.mark.parametrize("text", [
    "HTTP 429: insufficient_quota", "UsageLimitExceeded", "usage_limit_exceeded",
    "API quota exhausted", "本周额度已用完", "额度耗尽", "账户已达到使用上限",
])
def test_explicit_quota_reports_are_distinct_from_throttling(text):
    analysis = analyze_final_text(text)
    assert analysis["category"] == "quota_exhausted"
    assert analysis["confidence"] == "reported"
    assert "尚未独立核验" in analysis["summary"]
    assert "额外付费" in analysis["suggestion"]
    task = {"id": "codex:synthetic", "tool": "codex", "title": "Synthetic",
            "status": "error", "updated_at": "2026-10-31T00:00:00Z",
            "output": text, "error_analysis": analysis}
    assert Task.model_validate(task).error_analysis.category == "quota_exhausted"
    assert sanitize_task_analysis(task, enabled=True) == analysis
    assert sanitize_task_analysis(task, enabled=False) is None
    task["status"] = "completed"
    assert sanitize_task_analysis(task, enabled=True) is None


@pytest.mark.parametrize("text,expected", [
    ("HTTP 429: too many requests", "rate_limit"),
    ("RateLimitError", "rate_limit"),
    ("Disk quota exceeded", "unknown"),
    ("Storage quota exceeded", "unknown"),
    ("User quota exceeded", "unknown"),
    ("Quota exhausted", "unknown"),
    ("磁盘额度耗尽", "unknown"),
    ("存储额度已用完", "unknown"),
    ("已达到使用上限", "unknown"),
    ("文件系统账户已达到使用上限", "unknown"),
    ("If quota exhausted, wait", "unknown"),
    ("没有额度耗尽", "unknown"),
    ("已解决额度用完", "unknown"),
    ("HTTP 429\n额度耗尽", "unknown"),
    ("HTTP 429: insufficient_quota\nPermissionError", "unknown"),
    ("Insufficient_quota\nHTTP 429: too many requests", "unknown"),
])
def test_unknown_negated_and_conflicting_reports_are_not_guessed(text, expected):
    assert analyze_final_text(text)["category"] == expected
