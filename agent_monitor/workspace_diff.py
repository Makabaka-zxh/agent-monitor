"""Bounded, read-only Git diff summaries for a verified local session workspace.

Never accepts client-supplied paths, executes Git hooks, or exposes secret files.
"""
from __future__ import annotations

from datetime import datetime
import os
from pathlib import Path
import re
import stat
import subprocess
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool

from . import collector

MAX_RAW_DIFF_BYTES = 256 * 1024
MAX_DIFF_FILES = 12
MAX_HUNKS_PER_FILE = 4
MAX_LINES_PER_FILE = 36
MAX_LINE_CHARS = 160
MAX_UNTRACKED_FILES = 6
MAX_UNTRACKED_FILE_BYTES = 32 * 1024

SENSITIVE_NAMES = frozenset({
    ".git", ".codex", ".claude", ".ssh", ".gnupg", ".aws", ".azure", ".config", ".state",
    "state", "secrets", "credentials", "credentials.json", "auth.json", "config.json",
    "id_rsa", "id_ed25519", "known_hosts", "authorized_keys", "cookies", "cookies.txt",
    "keychain", "login data", ".npmrc", ".pypirc", ".netrc",
})
SENSITIVE_SUFFIXES = frozenset({".pem", ".key", ".pfx", ".p12", ".keystore", ".jks", ".sqlite", ".sqlite3", ".db"})
HUNK_RE = re.compile(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(?: (.*))?$")


class DiffLineModel(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["ctx", "add", "del"]
    old_no: int | None = Field(default=None, ge=1, le=9_999_999)
    new_no: int | None = Field(default=None, ge=1, le=9_999_999)
    text: str = Field(max_length=MAX_LINE_CHARS)


class DiffHunkModel(BaseModel):
    model_config = ConfigDict(extra="forbid")
    header: str = Field(min_length=1, max_length=200)
    lines: list[DiffLineModel] = Field(default_factory=list, max_length=MAX_LINES_PER_FILE)


class DiffFileModel(BaseModel):
    model_config = ConfigDict(extra="forbid")
    path: str = Field(min_length=1, max_length=240)
    status: Literal["modified", "added", "deleted", "renamed"] = "modified"
    additions: int = Field(ge=0, le=999_999)
    deletions: int = Field(ge=0, le=999_999)
    truncated: StrictBool = False
    hunks: list[DiffHunkModel] = Field(default_factory=list, max_length=MAX_HUNKS_PER_FILE)


class DiffSummaryModel(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scope: Literal["working_tree", "last_commit"]
    files_changed: int = Field(ge=1, le=99_999)
    additions: int = Field(ge=0, le=9_999_999)
    deletions: int = Field(ge=0, le=9_999_999)
    truncated: StrictBool = False
    files: list[DiffFileModel] = Field(default_factory=list, max_length=MAX_DIFF_FILES)


def _unlinked(path: Path) -> bool:
    try:
        absolute = Path(os.path.abspath(path))
        for component in (*reversed(absolute.parents), absolute):
            info = component.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                return False
        return True
    except (OSError, ValueError):
        return False


def _is_sensitive_relpath(rel_path: str) -> bool:
    if not rel_path or "\x00" in rel_path or rel_path.startswith(("/", "\\")):
        return True
    norm = rel_path.replace("\\", "/").strip("/")
    if not norm:
        return True
    parts = norm.split("/")
    for part in parts:
        lower = part.lower()
        if part in {".", ".."} or lower in SENSITIVE_NAMES:
            return True
        if lower == ".env" or (lower.startswith(".env.") and lower != ".env.example"):
            return True
    leaf_lower = parts[-1].lower()
    if any(leaf_lower.endswith(ext) for ext in SENSITIVE_SUFFIXES):
        return True
    return False


def _clean_line(raw: str) -> str:
    cleaned = "".join(ch for ch in raw if (ord(ch) >= 32 or ch == "\t") and ch not in "\x7f\u202d\u202e")
    return cleaned[:MAX_LINE_CHARS]


def _git_env() -> dict[str, str]:
    allowed = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "HOME", "USERPROFILE", "TMP", "TEMP"}
    env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GIT_OPTIONAL_LOCKS"] = "0"
    env["LC_ALL"] = "C.UTF-8"
    return env


def _run_git(cwd: Path, args: list[str], *, timeout: float = 3.0) -> bytes | None:
    cmd = ["git", "-c", "core.fsmonitor=false", "-c", "core.quotepath=false", "--no-optional-locks", *args]
    kwargs: dict = {
        "cwd": str(cwd),
        "env": _git_env(),
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.DEVNULL,
        "shell": False,
    }
    if os.name == "nt":
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
    try:
        proc = subprocess.Popen(cmd, **kwargs)
    except OSError:
        return None
    try:
        out, _ = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        try:
            proc.communicate(timeout=1.0)
        except Exception:
            pass
        return None
    if proc.returncode != 0:
        return None
    return out


def parse_unified_diff(raw_text: str, *, scope: Literal["working_tree", "last_commit"]) -> dict | None:
    files_raw: list[dict] = []
    current: dict | None = None
    current_hunk: dict | None = None
    old_line = 0
    new_line = 0

    for line in raw_text.splitlines():
        if line.startswith("diff --git "):
            current_hunk = None
            m = re.match(r"^diff --git a/(.+?) b/(.+)$", line)
            rel_path = m.group(2) if m else line[len("diff --git "):].strip()
            current = {
                "path": rel_path[:240],
                "status": "modified",
                "additions": 0,
                "deletions": 0,
                "hunks": [],
                "line_count": 0,
                "truncated": False,
                "skip": _is_sensitive_relpath(rel_path),
            }
            files_raw.append(current)
            continue
        if current is None or current["skip"]:
            continue
        if line.startswith("new file mode "):
            current["status"] = "added"
            continue
        if line.startswith("deleted file mode "):
            current["status"] = "deleted"
            continue
        if line.startswith("rename from ") or line.startswith("rename to "):
            current["status"] = "renamed"
            if line.startswith("rename to "):
                target_path = line[len("rename to "):].strip()
                current["path"] = target_path[:240]
                if _is_sensitive_relpath(target_path):
                    current["skip"] = True
            continue
        if line.startswith(("index ", "--- ", "+++ ", "Binary files ")):
            continue
        if line.startswith("@@ "):
            hm = HUNK_RE.match(line)
            if not hm:
                current_hunk = None
                continue
            old_line = int(hm.group(1))
            new_line = int(hm.group(2))
            if len(current["hunks"]) >= MAX_HUNKS_PER_FILE or current["line_count"] >= MAX_LINES_PER_FILE:
                current["truncated"] = True
                current_hunk = None
                continue
            header = _clean_line(line)[:200]
            current_hunk = {"header": header or "@@", "lines": []}
            current["hunks"].append(current_hunk)
            continue
        if not line:
            prefix, body = " ", ""
        else:
            prefix, body = line[0], line[1:]
        if prefix == "\\":
            continue
        if prefix == "+":
            current["additions"] += 1
            kind: Literal["ctx", "add", "del"] = "add"
            o_no, n_no = None, new_line
            new_line += 1
        elif prefix == "-":
            current["deletions"] += 1
            kind = "del"
            o_no, n_no = old_line, None
            old_line += 1
        elif prefix == " ":
            kind = "ctx"
            o_no, n_no = old_line, new_line
            old_line += 1
            new_line += 1
        else:
            continue
        if current_hunk is not None:
            if current["line_count"] < MAX_LINES_PER_FILE:
                current_hunk["lines"].append({
                    "kind": kind,
                    "old_no": o_no,
                    "new_no": n_no,
                    "text": _clean_line(body),
                })
                current["line_count"] += 1
            else:
                current["truncated"] = True

    visible = [f for f in files_raw if not f["skip"] and (f["additions"] > 0 or f["deletions"] > 0 or f["status"] != "modified")]
    if not visible:
        return None

    total_files = len(visible)
    total_add = sum(f["additions"] for f in visible)
    total_del = sum(f["deletions"] for f in visible)
    summary_truncated = total_files > MAX_DIFF_FILES or any(f["truncated"] for f in visible[:MAX_DIFF_FILES])

    out_files = []
    for f in visible[:MAX_DIFF_FILES]:
        hunks = [h for h in f["hunks"] if h["lines"]]
        out_files.append({
            "path": f["path"],
            "status": f["status"],
            "additions": f["additions"],
            "deletions": f["deletions"],
            "truncated": bool(f["truncated"]),
            "hunks": hunks,
        })

    model = DiffSummaryModel(
        scope=scope,
        files_changed=total_files,
        additions=total_add,
        deletions=total_del,
        truncated=summary_truncated,
        files=[DiffFileModel.model_validate(item) for item in out_files],
    )
    return model.model_dump(mode="json")


def _synthetic_untracked_diff(cwd: Path, rel_paths: list[str]) -> str:
    chunks: list[str] = []
    for rel in rel_paths[:MAX_UNTRACKED_FILES]:
        if _is_sensitive_relpath(rel):
            continue
        target = cwd / rel
        if not _unlinked(target) or not target.is_file():
            continue
        try:
            size = target.stat().st_size
            if size <= 0 or size > MAX_UNTRACKED_FILE_BYTES:
                continue
            raw = target.read_bytes()
            if b"\x00" in raw[:4096]:
                continue
            text = raw.decode("utf-8", errors="replace")
        except OSError:
            continue
        lines = text.splitlines()
        if not lines:
            continue
        preview_lines = lines[:MAX_LINES_PER_FILE]
        chunks.append(f"diff --git a/{rel} b/{rel}")
        chunks.append("new file mode 100644")
        chunks.append("--- /dev/null")
        chunks.append(f"+++ b/{rel}")
        chunks.append(f"@@ -0,0 +1,{len(lines)} @@")
        for line in preview_lines:
            chunks.append("+" + line[:MAX_LINE_CHARS])
        for _ in range(len(lines) - len(preview_lines)):
            chunks.append("+")
    return "\n".join(chunks)


def _iso_to_epoch(value: str) -> float | None:
    if not value:
        return None
    dt: datetime | None = collector._timestamp(value)
    return dt.timestamp() if dt is not None else None


def collect_workspace_diff(cwd: Path, *, started_at: str = "", completed_at: str = "") -> dict | None:
    """Return a bounded structured diff summary for a verified workspace, or None."""
    if not isinstance(cwd, Path) or not _unlinked(cwd) or not cwd.is_dir():
        return None
    git_marker = cwd / ".git"
    if not git_marker.exists() or not _unlinked(git_marker):
        return None

    # 1. Working tree changes (staged + unstaged against HEAD, plus small untracked files)
    wt_bytes = _run_git(cwd, ["diff", "HEAD", "--unified=2", "--no-color", "--no-ext-diff"])
    if wt_bytes is None:
        # Unborn HEAD fallback: staged/unstaged index diff
        wt_bytes = _run_git(cwd, ["diff", "--unified=2", "--no-color", "--no-ext-diff"]) or b""
    wt_truncated = len(wt_bytes) > MAX_RAW_DIFF_BYTES
    wt_text = wt_bytes[:MAX_RAW_DIFF_BYTES].decode("utf-8", errors="replace")

    untracked_out = _run_git(cwd, ["ls-files", "--others", "--exclude-standard", "-z"])
    if untracked_out and len(wt_text.encode("utf-8")) < MAX_RAW_DIFF_BYTES:
        rels = [item.decode("utf-8", errors="replace") for item in untracked_out.split(b"\x00") if item]
        extra = _synthetic_untracked_diff(cwd, rels)
        if extra:
            wt_text = (wt_text + "\n" + extra).strip()

    if wt_text.strip():
        parsed = parse_unified_diff(wt_text, scope="working_tree")
        if parsed is not None:
            if wt_truncated:
                parsed["truncated"] = True
            return parsed

    # 2. Fallback: if working tree is clean, check whether HEAD commit belongs to this session turn
    end_ts = _iso_to_epoch(completed_at)
    start_ts = _iso_to_epoch(started_at)
    if end_ts is None and start_ts is None:
        return None
    head_meta = _run_git(cwd, ["log", "-1", "--format=%ct", "HEAD"])
    if not head_meta:
        return None
    try:
        commit_ts = float(head_meta.decode("ascii", errors="ignore").strip())
    except ValueError:
        return None

    lower_bound = (start_ts - 60.0) if start_ts is not None else (end_ts - 1800.0)  # type: ignore[operator]
    upper_bound = (end_ts + 300.0) if end_ts is not None else (start_ts + 7200.0)  # type: ignore[operator]
    if not (lower_bound <= commit_ts <= upper_bound):
        return None

    commit_bytes = _run_git(cwd, ["show", "--format=", "--unified=2", "--no-color", "--no-ext-diff", "HEAD"])
    if not commit_bytes:
        return None
    commit_truncated = len(commit_bytes) > MAX_RAW_DIFF_BYTES
    commit_text = commit_bytes[:MAX_RAW_DIFF_BYTES].decode("utf-8", errors="replace")
    parsed_commit = parse_unified_diff(commit_text, scope="last_commit")
    if parsed_commit is not None and commit_truncated:
        parsed_commit["truncated"] = True
    return parsed_commit
