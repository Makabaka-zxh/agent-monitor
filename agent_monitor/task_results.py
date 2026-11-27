"""Final answers and explicitly referenced deliverables, never execution logs.

Native callers supply task/result/file identities only. Local filesystem paths
are obtained from a verified transcript and never accepted through the API.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import io
import json
import mimetypes
import os
from pathlib import Path
import re
import stat
import threading
import time
from typing import Any
from urllib.parse import quote, unquote, urlsplit
import zipfile

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator

from . import collector
from .workspace_diff import DiffSummaryModel, collect_workspace_diff

MAX_RESULT_CHARS = 200_000
MAX_RESULT_SCAN_BYTES = 4 * 1024 * 1024
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_CACHE_BYTES = 128 * 1024 * 1024
MAX_TEXT_CACHE_BYTES = 64 * 1024 * 1024
MAX_FILES = 12
CHUNK_BYTES = 512 * 1024
HEX_ID = re.compile(r"^[0-9a-f]{64}$")
_CODEX_UUID = r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
_CODEX_ROLLOUT = re.compile(r"rollout-[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}-(" +
                            _CODEX_UUID + r")\.jsonl")
DELIVERABLE_TYPES = frozenset({
    ".txt", ".md", ".csv", ".tsv", ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
    ".odt", ".ods", ".odp", ".rtf", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
    ".mp3", ".wav", ".m4a", ".mp4", ".mov", ".webm", ".zip", ".html", ".htm",
    ".py", ".js", ".ts", ".tsx", ".jsx", ".css", ".java", ".kt", ".swift", ".sql",
    ".json", ".yaml", ".yml", ".xml", ".ipynb", ".ics", ".apk", ".aab",
})
SENSITIVE_PARTS = frozenset({
    ".git", ".codex", ".claude", ".ssh", ".gnupg", ".aws", ".azure", ".config", ".state",
    "state", "secrets", "credentials", "credentials.json", "auth.json", "config.json", "id_rsa",
    "id_ed25519", "known_hosts", "authorized_keys", "cookies", "cookies.txt", "keychain", "login data",
})


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def final_text(content: Any) -> str:
    if isinstance(content, str):
        return content.replace("\x00", "")
    if not isinstance(content, list):
        return ""
    return "\n".join(block["text"].replace("\x00", "") for block in content
                     if isinstance(block, dict) and block.get("type") in {"text", "output_text"}
                     and isinstance(block.get("text"), str))


def extract_final(records: list[dict], tool: str) -> dict | None:
    """Only explicitly final assistant messages qualify; no prose inference.

    Codex legacy task_complete.last_agent_message is an explicit final field.
    Claude end_turn selects final *text*, not a claim about current run status.
    A later prompt or commentary retains the last completed answer.
    """
    result = None
    started = None
    for record in records:
        if record.get("isSidechain") or record.get("isMeta") or record.get("isCompactSummary"):
            continue
        stamp = collector._timestamp(record.get("timestamp"))
        text = ""
        if tool == "codex":
            payload = record.get("payload")
            if not isinstance(payload, dict):
                continue
            kind = payload.get("type")
            if record.get("type") == "event_msg" and kind in {"task_started", "turn_started", "user_message"}:
                started = stamp
            if (record.get("type") == "response_item" and kind == "message"
                    and payload.get("role") == "assistant" and payload.get("phase") == "final"):
                text = final_text(payload.get("content"))
            elif record.get("type") == "event_msg" and kind in {"task_complete", "turn_complete"}:
                value = payload.get("last_agent_message")
                # Do not replace a detailed final record with its duplicate.
                if isinstance(value, str) and value and (not result or value != result["text"]):
                    text = final_text(value)
        elif tool == "claude":
            message = record.get("message")
            if not isinstance(message, dict):
                continue
            if record.get("type") == "user" and not record.get("toolUseResult"):
                blocks = message.get("content")
                if isinstance(blocks, str) or (isinstance(blocks, list) and any(
                        isinstance(block, dict) and block.get("type") in {"text", "image"} for block in blocks)
                        and not any(isinstance(block, dict) and block.get("type") == "tool_result" for block in blocks)):
                    started = stamp
            if record.get("type") == "assistant" and message.get("stop_reason") == "end_turn":
                text = final_text(message.get("content"))
        if text.strip() and stamp:
            truncated = len(text) > MAX_RESULT_CHARS
            text = text[:MAX_RESULT_CHARS]
            completed_at = collector._iso(stamp)
            result = {"text": text, "completed_at": completed_at, "truncated": truncated,
                      "result_id": sha256((tool + "\n" + completed_at + "\n" + text).encode()),
                      "started_at": collector._iso(started) if started else ""}
    return result


def _unlinked(path: Path) -> bool:
    """Reject every symlink, Windows junction and other reparse component."""
    try:
        absolute = Path(os.path.abspath(path))
        for component in (*reversed(absolute.parents), absolute):
            info = component.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                return False
        return True
    except (OSError, ValueError):
        return False


@dataclass(frozen=True)
class LocalSession:
    path: Path
    cwd: Path
    session_id: str
    tool: str
    source_id: str


def _exact_codex_backup(paths, wanted):
    matches = [path for path in paths if (name := _CODEX_ROLLOUT.fullmatch(path.name)) and name[1] == wanted]
    return matches[0] if len(matches) == 1 else None


def _read_extra_codex_head(path, base):
    identity = collector._plain_codex_identity(path, base)
    if identity is None or identity.st_nlink != 1:
        return []
    with path.open("rb") as handle:
        info = os.fstat(handle.fileno())
        if not stat.S_ISREG(info.st_mode) or not os.path.samestat(info, identity):
            return []
        return collector._json_lines(handle.read(collector.HEAD_BYTES))


def resolve_local_session(source_id: str, codex_home=None, claude_home=None, *,
                          antigravity_home=None, require_workspace=True) -> LocalSession | None:
    if not isinstance(source_id, str) or len(source_id) > 200:
        return None
    tool, _, wanted = source_id.partition(":")
    if tool not in {"codex", "claude", "antigravity"} or not wanted or any(c in wanted for c in "/\\\x00"):
        return None
    if tool == "antigravity":
        from .antigravity import resolve_antigravity_session
        resolved = resolve_antigravity_session(wanted, base=antigravity_home)
        if resolved is None:
            return None
        path, root = resolved
        if not _unlinked(path) or (require_workspace and (not root.is_dir() or not _unlinked(root))):
            return None
        return LocalSession(path, root, wanted, tool, source_id)
    home = (Path(codex_home) if codex_home is not None else Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex")))) if tool == "codex" else (
        Path(claude_home) if claude_home is not None else Path(os.environ.get("CLAUDE_CONFIG_DIR", str(Path.home() / ".claude"))))
    base = home / ("sessions" if tool == "codex" else "projects")
    if not _unlinked(base):
        return None
    # Callers authorize a task before submitting its source id. Rank the same
    # bounded metadata lookahead, but never sample its other transcript heads.
    backup = []
    discovery = {"_codex_backup": backup} if tool == "codex" and re.fullmatch(_CODEX_UUID, wanted) else {}
    files, _, _ = collector._recent_files(base, collector.MAX_SESSION_FILES, claude=tool == "claude", **discovery)
    # Session filenames normally contain the exact id. Try that bounded subset
    # first; retain metadata verification and a fallback for legacy filenames.
    files.sort(key=lambda path: wanted not in path.name)
    extra_path = _exact_codex_backup(backup, wanted)
    for path in files if extra_path is None else (*files, extra_path):
        if not _unlinked(path):
            continue
        try:
            # Metadata must be in the complete head, never inferred from name.
            extra = path == extra_path
            head = _read_extra_codex_head(path, base) if extra else collector._read_head(path)
            if tool == "codex":
                metadata = next((row.get("payload") for row in head if row.get("type") == "session_meta"
                                 and isinstance(row.get("payload"), dict)), None)
                if not metadata:
                    continue
                if collector._codex_is_subagent(metadata):
                    continue
                session = metadata.get("id") or metadata.get("session_id")
                cwd = metadata.get("cwd")
            else:
                metadata = next((row for row in head if row.get("sessionId") == wanted
                                 and isinstance(row.get("cwd"), str) and not row.get("isSidechain")), None)
                if not metadata or any(row.get("isSidechain") for row in head):
                    continue
                session, cwd = metadata.get("sessionId"), metadata.get("cwd")
            if session != wanted or not isinstance(cwd, str) or not Path(cwd).is_absolute():
                continue
            root = Path(cwd)
            if (require_workspace or extra) and (not root.is_dir() or not _unlinked(root)):
                continue
            return LocalSession(path, root, wanted, tool, source_id)
        except (OSError, ValueError):
            continue
    return None


def resolve_local_project(project_id: str, *, codex_home=None, claude_home=None,
                          antigravity_home=None) -> Path | None:
    """Resolve an opaque 24-hex project ID to a verified local workspace directory."""
    if not isinstance(project_id, str) or not re.fullmatch(r"[0-9a-f]{24}", project_id):
        return None
    use_antigravity = antigravity_home is not None or (codex_home is None and claude_home is None)
    c_home = Path(codex_home) if codex_home is not None else Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex")))
    l_home = Path(claude_home) if claude_home is not None else Path(os.environ.get("CLAUDE_CONFIG_DIR", str(Path.home() / ".claude")))
    for tool, home in (("codex", c_home), ("claude", l_home)):
        base = home / ("sessions" if tool == "codex" else "projects")
        if not _unlinked(base):
            continue
        files, _, _ = collector._recent_files(base, collector.MAX_SESSION_FILES, claude=tool == "claude")
        for path in files:
            if not _unlinked(path):
                continue
            try:
                head = collector._read_head(path)
                if tool == "codex":
                    metadata = next((row.get("payload") for row in head if row.get("type") == "session_meta"
                                     and isinstance(row.get("payload"), dict)), None)
                    if not metadata or collector._codex_is_subagent(metadata):
                        continue
                    cwd = metadata.get("cwd")
                else:
                    if any(row.get("isSidechain") for row in head):
                        continue
                    cwd = next((row.get("cwd") for row in head if isinstance(row.get("cwd"), str) and row.get("cwd")), None)
                if not isinstance(cwd, str) or collector.project_id_for_cwd(cwd) != project_id:
                    continue
                root = Path(os.path.abspath(cwd))
                if root.parent != root and root.is_dir() and _unlinked(root):
                    return root
            except (OSError, ValueError):
                continue
    if use_antigravity:
        try:
            from .antigravity import _LOCAL_SOURCES, _read, resolve_antigravity_workspace
            rows = _read(antigravity_home, 150)
            for row in rows:
                if len(row) < 8 or row[5] not in _LOCAL_SOURCES or row[7] != 0:
                    continue
                candidate = resolve_antigravity_workspace(row[3])
                if candidate is None or collector.project_id_for_cwd(candidate) != project_id:
                    continue
                root = Path(os.path.abspath(str(candidate)))
                if root.parent != root and root.is_dir() and _unlinked(root):
                    return root
        except Exception:
            pass
    return None


def _safe_name(name: str) -> bool:
    lowered = name.lower()
    return bool(name and name not in {".", ".."} and len(name) <= 180 and
                not any(ord(c) < 32 or ord(c) == 127 or c in "/\\:" for c in name) and
                lowered not in SENSITIVE_PARTS and not lowered.startswith((".env", ".", "credentials", "secret")) and
                not lowered.endswith((".pem", ".key", ".p12", ".pfx", ".sqlite", ".sqlite3", ".db")))


def _fingerprint(info) -> tuple:
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _normalize_candidate_path(raw: str, *, allow_file_uri: bool = False) -> str | None:
    value = raw.strip().strip("<>")
    if not value or "\x00" in value:
        return None
    if allow_file_uri and value.lower().startswith("file://"):
        parts = urlsplit(value)
        if parts.scheme.lower() != "file" or parts.netloc.lower() not in ("", "localhost") or parts.query:
            return None
        decoded = unquote(parts.path)
        if not decoded or "\x00" in decoded:
            return None
        if os.name == "nt":
            if not re.match(r"^/[a-zA-Z]:/.+", decoded):
                return None
            value = decoded[1:]
        else:
            if not decoded.startswith("/") or decoded.startswith("//") or re.match(r"^/[a-zA-Z]:(?:/|$)", decoded):
                return None
            value = decoded
        value = re.sub(r":\d+(?::\d+)?$", "", value)
    else:
        value = unquote(value)
        # Linux and Windows local paths; never file://, network shares or URLs.
        if not value or value.startswith(("//", "\\\\")) or "://" in value or "\x00" in value:
            return None
        value = re.sub(r":\d+(?::\d+)?$", "", value)
        value = value.split("#", 1)[0]
    if any(part == ".." for part in re.split(r"[/\\]", value)):
        return None
    return value


def referenced_paths(text: str, root: Path, *, allow_file_uri: bool = False) -> list[Path]:
    candidates = [match.group(1) for match in re.finditer(r"\[[^\]\n]*\]\((<[^>\n]+>|[^)\n]+)\)", text)]
    candidates += [match.group(1) for match in re.finditer(r"`([^`\n]+)`", text)]
    paths = []
    abs_root = Path(os.path.abspath(root))
    for raw in candidates[:100]:
        value = _normalize_candidate_path(raw, allow_file_uri=allow_file_uri)
        if not value:
            continue
        candidate = Path(value)
        if not candidate.is_absolute():
            candidate = abs_root / candidate
        candidate = Path(os.path.abspath(candidate))
        try:
            relative = candidate.relative_to(abs_root)
        except ValueError:
            continue
        if candidate.suffix.lower() not in DELIVERABLE_TYPES or not all(_safe_name(part) for part in relative.parts):
            continue
        if candidate not in paths:
            paths.append(candidate)
    return paths[:MAX_FILES]


def _safe_archive(data: bytes, suffix: str) -> bool:
    if suffix not in {".zip", ".docx", ".xlsx", ".pptx", ".odt", ".ods", ".odp", ".apk", ".aab"}:
        return True
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if len(entries) > 5000 or sum(entry.file_size for entry in entries) > MAX_CACHE_BYTES:
                return False
            for entry in entries:
                parts = re.split(r"[/\\]", entry.filename.strip("/"))
                if (entry.filename.startswith(("/", "\\")) or any(p in {"..", "."} or ":" in p for p in parts)
                        or any(p.lower() in SENSITIVE_PARTS or p.lower().startswith((".env", "secret", "credentials")) for p in parts)
                        or stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1):
                    return False
            return True
    except (OSError, ValueError, zipfile.BadZipFile):
        return False


def _read_verified_bytes(path: Path, *, min_mtime: float, max_mtime: float, expected_sha: str | None = None) -> bytes:
    if not _unlinked(path):
        raise ValueError()
    info = path.stat()
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > MAX_FILE_BYTES
            or info.st_mtime < min_mtime or info.st_mtime > max_mtime):
        raise ValueError()
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as handle:
        if _fingerprint(os.fstat(handle.fileno())) != _fingerprint(info):
            raise ValueError()
        data = handle.read(MAX_FILE_BYTES + 1)
        if _fingerprint(os.fstat(handle.fileno())) != _fingerprint(info):
            raise ValueError()
    if len(data) != info.st_size or not _unlinked(path) or _fingerprint(path.stat()) != _fingerprint(info):
        raise ValueError()
    if expected_sha and sha256(data) != expected_sha or not _safe_archive(data, path.suffix.lower()):
        raise ValueError()
    return data


def _read_small_json(path: Path, limit: int = 16384) -> dict | None:
    try:
        if not _unlinked(path):
            return None
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
            return None
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb") as handle:
            if _fingerprint(os.fstat(handle.fileno())) != _fingerprint(info):
                return None
            raw = handle.read(limit + 1)
        if len(raw) != info.st_size or not _unlinked(path):
            return None
        parsed = json.loads(raw.decode("utf-8"))
        return parsed if isinstance(parsed, dict) else None
    except (OSError, ValueError, UnicodeError, RecursionError):
        return None


def _antigravity_candidates(final: dict, brain_dir: Path | None,
                            workspace_dir: Path | None) -> list[tuple[Path, float, float]]:
    ended = collector._timestamp(final.get("completed_at"))
    if not ended:
        return []
    started = collector._timestamp(final.get("started_at"))
    seen: set[Path] = set()
    candidates: list[tuple[Path, float, float]] = []

    def add(path: Path, lo: float, hi: float) -> None:
        if path not in seen and len(candidates) < MAX_FILES:
            seen.add(path)
            candidates.append((path, lo, hi))

    if workspace_dir is not None and started is not None and workspace_dir.is_dir() and _unlinked(workspace_dir):
        for path in referenced_paths(final["text"], workspace_dir, allow_file_uri=True):
            add(path, started.timestamp() - 2, ended.timestamp() + 5)

    if brain_dir is not None and brain_dir.is_dir() and _unlinked(brain_dir):
        abs_brain = Path(os.path.abspath(brain_dir))
        for path in referenced_paths(final["text"], abs_brain, allow_file_uri=True):
            if path.parent == abs_brain and not path.name.lower().endswith(".metadata.json"):
                add(path, 946684800.0, ended.timestamp() + 5)
        if started is not None:
            try:
                with os.scandir(abs_brain) as it:
                    entries = sorted(
                        (e for _, e in zip(range(128), it)
                         if e.name.lower().endswith(".metadata.json") and not e.is_symlink()
                         and e.is_file(follow_symlinks=False)),
                        key=lambda item: item.name,
                    )
            except OSError:
                entries = []
            for entry in entries[:64]:
                meta = _read_small_json(Path(entry.path))
                if not meta or meta.get("userFacing") is not True:
                    continue
                artifact_name = entry.name[:-len(".metadata.json")]
                if (not _safe_name(artifact_name) or artifact_name.lower().endswith(".metadata.json")
                        or Path(artifact_name).suffix.lower() not in DELIVERABLE_TYPES):
                    continue
                candidate = Path(os.path.abspath(abs_brain / artifact_name))
                if candidate.parent == abs_brain:
                    add(candidate, started.timestamp() - 2, ended.timestamp() + 5)
    return candidates


def read_deliverable(path: Path, session: LocalSession, final: dict, expected_sha: str | None = None) -> bytes:
    try:
        if path not in referenced_paths(final["text"], session.cwd) or not _unlinked(path):
            raise ValueError()
        started = collector._timestamp(final.get("started_at"))
        ended = collector._timestamp(final.get("completed_at"))
        if not started or not ended:
            raise ValueError()
        return _read_verified_bytes(path, min_mtime=started.timestamp() - 2,
                                    max_mtime=ended.timestamp() + 5, expected_sha=expected_sha)
    except (OSError, ValueError):
        raise HTTPException(409, "文件已变化或不适合下载，请在电脑确认后重新生成") from None


class LocalResults:
    def __init__(self, *, codex_home=None, claude_home=None, antigravity_home=None):
        self.codex_home, self.claude_home = codex_home, claude_home
        self.antigravity_home = antigravity_home
        self.cache: dict[str, tuple] = {}
        self.lock = threading.RLock()

    def resolve(self, source_id):
        # Final text remains readable after a workspace was moved or removed;
        # file opening still enforces a real, unlinked, contained workspace.
        return resolve_local_session(source_id, self.codex_home, self.claude_home,
                                     antigravity_home=self.antigravity_home, require_workspace=False)

    def _read_antigravity(self, wanted: str) -> dict | None:
        from .antigravity import read_antigravity_result, resolve_antigravity_deliverable_roots

        final = read_antigravity_result(wanted, base=self.antigravity_home, include_turn_start=True)
        if not final:
            return None
        final = dict(final, files=[], _paths={}, _antigravity_id=wanted, diff=None)
        roots = resolve_antigravity_deliverable_roots(wanted, base=self.antigravity_home)
        if roots is not None:
            workspace_dir = roots[1]
            if workspace_dir is not None:
                final["diff"] = collect_workspace_diff(
                    workspace_dir,
                    started_at=final.get("started_at", ""),
                    completed_at=final.get("completed_at", ""),
                )
            for path, lo, hi in _antigravity_candidates(final, *roots):
                try:
                    content = _read_verified_bytes(path, min_mtime=lo, max_mtime=hi)
                except (OSError, ValueError):
                    continue
                digest = sha256(content)
                identity = sha256((final["result_id"] + "\n" + str(path) + "\n" + digest).encode())
                final["files"].append({"id": identity, "name": path.name, "size": len(content),
                                       "mime": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                                       "sha256": digest, "ready": True})
                final["_paths"][identity] = path
        return final

    def read(self, source_id: str) -> dict | None:
        if source_id.startswith("antigravity:"):
            return self._read_antigravity(source_id.removeprefix("antigravity:"))
        session = self.resolve(source_id)
        if not session:
            return None
        try:
            fingerprint = _fingerprint(session.path.stat())
            with self.lock:
                cached = self.cache.get(source_id)
                if cached and cached[0] == fingerprint:
                    hit = dict(cached[1])
                    hit["diff"] = collect_workspace_diff(
                        session.cwd,
                        started_at=hit.get("started_at", ""),
                        completed_at=hit.get("completed_at", ""),
                    )
                    return hit
            _, tail, _ = collector._read_window(session.path, max_bytes=MAX_RESULT_SCAN_BYTES)
            final = extract_final(tail, session.tool)
            if not final:
                # A new turn's large tool payload may push the previous final
                # beyond the bounded tail. Keep the already verified answer
                # only for the same, growing transcript (never replacement or
                # truncation), which is still the most recent completed result.
                # Recompression can grow the same file without appending any
                # transcript. Only plain append-only logs can use this rule.
                if (not session.path.name.endswith(".jsonl.zst") and cached
                        and fingerprint[:2] == cached[0][:2] and fingerprint[2] > cached[0][2]
                        and cached[1]["_session"] == session):
                    with self.lock:
                        self.cache[source_id] = (fingerprint, cached[1])
                    hit = dict(cached[1])
                    hit["diff"] = collect_workspace_diff(
                        session.cwd,
                        started_at=hit.get("started_at", ""),
                        completed_at=hit.get("completed_at", ""),
                    )
                    return hit
                return None
            final = dict(
                final,
                files=[],
                _session=session,
                _paths={},
                diff=collect_workspace_diff(
                    session.cwd,
                    started_at=final.get("started_at", ""),
                    completed_at=final.get("completed_at", ""),
                ),
            )
            for path in referenced_paths(final["text"], session.cwd):
                try:
                    content = read_deliverable(path, session, final)
                except HTTPException:
                    continue
                digest = sha256(content)
                identity = sha256((final["result_id"] + "\n" + str(path) + "\n" + digest).encode())
                final["files"].append({"id": identity, "name": path.name, "size": len(content),
                                       "mime": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                                       "sha256": digest, "ready": True})
                final["_paths"][identity] = path
            with self.lock:
                if len(self.cache) >= collector.MAX_SESSION_FILES:
                    self.cache.pop(next(iter(self.cache)))
                self.cache[source_id] = (fingerprint, final)
            return final
        except (OSError, ValueError):
            return None

    def file(self, source_id: str, result_id: str, file_id: str) -> tuple[bytes, dict]:
        final = self.read(source_id)
        if not final or final["result_id"] != result_id:
            raise HTTPException(409, "结果已更新，请重新打开")
        metadata = next((file for file in final["files"] if file["id"] == file_id), None)
        if not metadata:
            raise HTTPException(404, "文件不存在")
        target = final["_paths"][file_id]
        if source_id.startswith("antigravity:"):
            from .antigravity import resolve_antigravity_deliverable_roots
            roots = resolve_antigravity_deliverable_roots(final["_antigravity_id"], base=self.antigravity_home)
            allowed = {p: (lo, hi) for p, lo, hi in (_antigravity_candidates(final, *roots) if roots else ())}
            if target not in allowed:
                raise HTTPException(409, "文件已变化或不适合下载，请在电脑确认后重新生成")
            lo, hi = allowed[target]
            try:
                content = _read_verified_bytes(target, min_mtime=lo, max_mtime=hi, expected_sha=metadata["sha256"])
            except (OSError, ValueError):
                raise HTTPException(409, "文件已变化或不适合下载，请在电脑确认后重新生成") from None
            return content, metadata
        content = read_deliverable(target, final["_session"], final, metadata["sha256"])
        return content, metadata


class FinalFile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[0-9a-f]{64}$")
    name: str = Field(min_length=1, max_length=180)
    size: int = Field(ge=0, le=MAX_FILE_BYTES, strict=True)
    mime: str = Field(max_length=120)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")

    @field_validator("name")
    @classmethod
    def valid_name(cls, value):
        if not _safe_name(value) or Path(value).suffix.lower() not in DELIVERABLE_TYPES:
            raise ValueError("文件名无效")
        return value


class FinalUpload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    tool: str = Field(pattern=r"^(codex|claude|antigravity)$")
    result_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    text: str = Field(min_length=1, max_length=MAX_RESULT_CHARS)
    completed_at: str = Field(max_length=40)
    truncated: StrictBool = False
    files: list[FinalFile] = Field(default_factory=list, max_length=MAX_FILES)
    diff: DiffSummaryModel | None = None

    @field_validator("completed_at")
    @classmethod
    def valid_stamp(cls, value):
        stamp = collector._timestamp(value)
        if not stamp or stamp.timestamp() > time.time() + 60:
            raise ValueError("时间无效")
        return collector._iso(stamp)


class FileChunk(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    result_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    file_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    offset: int = Field(ge=0, le=MAX_FILE_BYTES, strict=True)
    data: str = Field(max_length=((CHUNK_BYTES + 2) // 3) * 4)


class KnownResult(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    result_id: str = Field(pattern=r"^[0-9a-f]{64}$")


class ResultStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")
    results: list[KnownResult] = Field(max_length=100)


def download_response(data: bytes, name: str, mime: str) -> Response:
    return Response(data, media_type="application/octet-stream", headers={
        "Content-Disposition": "attachment; filename=\"download\"; filename*=UTF-8''" + quote(name, safe=""),
        "X-Content-SHA256": sha256(data), "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
    })


class TaskResults:
    def __init__(self, store, local: LocalResults | None = None):
        self.store, self.local = store, local or LocalResults()
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS final_results (
                    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                    source_id TEXT NOT NULL, result_id TEXT NOT NULL, payload TEXT NOT NULL, updated REAL NOT NULL,
                    PRIMARY KEY(device_id,source_id));
                CREATE TABLE IF NOT EXISTS final_files (
                    device_id TEXT NOT NULL, source_id TEXT NOT NULL, file_id TEXT NOT NULL,
                    payload TEXT NOT NULL, content BLOB NOT NULL DEFAULT X'', ready INTEGER NOT NULL DEFAULT 0,
                    wanted INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(device_id,source_id,file_id),
                    FOREIGN KEY(device_id,source_id) REFERENCES final_results(device_id,source_id) ON DELETE CASCADE);
            """)
            columns = {row[1] for row in store.db.execute("PRAGMA table_info(final_files)")}
            if "wanted" not in columns:
                store.db.execute("ALTER TABLE final_files ADD COLUMN wanted INTEGER NOT NULL DEFAULT 1")

    def _task(self, task_id: str):
        device, separator, source = task_id.partition(":")
        if not separator or len(task_id) > 500:
            raise HTTPException(404, "任务不存在或已不再同步")
        with self.store.lock:
            row = self.store.db.execute("""SELECT t.payload,d.local FROM tasks t JOIN devices d ON d.id=t.device_id
                WHERE t.device_id=? AND t.source_id=?""", (device, source)).fetchone()
        if not row:
            raise HTTPException(404, "任务不存在或已不再同步")
        return device, source, dict(row)

    def _enabled(self):
        if not self.store.preferences()["sync_output"]:
            raise HTTPException(409, "请先在“我的”开启最终结果同步")

    def authorize(self, task_id: str):
        self._task(task_id)
        self._enabled()

    def _final(self, task_id: str):
        with self.store.lock:
            device, source, task = self._task(task_id)
            self._enabled()
            if not task["local"]:
                row = self.store.db.execute("SELECT result_id,payload FROM final_results WHERE device_id=? AND source_id=?",
                                            (device, source)).fetchone()
                # A heartbeat can announce the next final before its upload arrives.
                expected = json.loads(task["payload"]).get("final_result_id")
                if not row or (expected and row["result_id"] != expected):
                    return None, False
                result = json.loads(row["payload"])
                files = self.store.db.execute("SELECT file_id,ready FROM final_files WHERE device_id=? AND source_id=?",
                                              (device, source)).fetchall()
                ready = {file["file_id"]: bool(file["ready"]) for file in files}
                for file in result["files"]:
                    file["ready"] = ready.get(file["id"], False)
                return result, False
        return self.local.read(source), True

    def result(self, task_id: str):
        final, local = self._final(task_id)
        if not final:
            live_diff = None
            if local:
                _, source, _ = self._task(task_id)
                if source.startswith("antigravity:"):
                    from .antigravity import resolve_antigravity_deliverable_roots

                    roots = resolve_antigravity_deliverable_roots(
                        source.removeprefix("antigravity:"),
                        base=self.local.antigravity_home,
                    )
                    if roots is not None and roots[1] is not None:
                        live_diff = collect_workspace_diff(roots[1])
                else:
                    session = self.local.resolve(source)
                    if session is not None and session.cwd is not None:
                        live_diff = collect_workspace_diff(session.cwd)
            return {"task_id": task_id, "available": False, "text": "", "result_id": "", "completed_at": "",
                    "truncated": False, "files": [], "diff": live_diff, "txt_size": 0, "txt_sha256": "",
                    "capability": "local" if local else "remote",
                    "reason": "还没有可读取的最终结果" if local else "等待电脑同步最终结果；旧版连接器需要更新"}
        encoded = final["text"].encode("utf-8")
        if not local and any(not file["ready"] for file in final["files"]):
            device, source, _ = self._task(task_id)
            with self.store.lock, self.store.db:
                self.store.db.execute("UPDATE final_files SET wanted=1 WHERE device_id=? AND source_id=? AND ready=0", (device, source))
        return {"task_id": task_id, "available": True, **{key: final[key] for key in ("text", "result_id", "completed_at", "truncated", "files")},
                "diff": final.get("diff"), "txt_size": len(encoded), "txt_sha256": sha256(encoded),
                "capability": "local" if local else "remote", "reason": ""}

    def text(self, task_id: str, result_id: str):
        final, _ = self._final(task_id)
        if not final or not HEX_ID.fullmatch(result_id) or final["result_id"] != result_id:
            raise HTTPException(409, "结果已更新，请重新打开")
        return download_response(final["text"].encode("utf-8"), "最终结果.txt", "text/plain")

    def file(self, task_id: str, result_id: str, file_id: str):
        if not HEX_ID.fullmatch(result_id) or not HEX_ID.fullmatch(file_id):
            raise HTTPException(404, "文件不存在")
        final, local = self._final(task_id)
        if not final or final["result_id"] != result_id:
            raise HTTPException(409, "结果已更新，请重新打开")
        device, source, _ = self._task(task_id)
        if local:
            content, metadata = self.local.file(source, result_id, file_id)
        else:
            with self.store.lock:
                row = self.store.db.execute("SELECT * FROM final_files WHERE device_id=? AND source_id=? AND file_id=?", (device, source, file_id)).fetchone()
            if not row:
                raise HTTPException(404, "文件不存在")
            if not row["ready"]:
                raise HTTPException(409, "配套文件正在同步，请稍后重试")
            content, metadata = bytes(row["content"]), json.loads(row["payload"])
        return download_response(content, metadata["name"], metadata["mime"])

    def upload(self, device: str, payload: FinalUpload):
        self._enabled()
        _, source, row = self._task(device + ":" + payload.source_id)
        if row["local"] or json.loads(row["payload"])["tool"] != payload.tool:
            raise HTTPException(403, "任务不属于这台电脑")
        expected = sha256((payload.tool + "\n" + payload.completed_at + "\n" + payload.text).encode())
        if expected != payload.result_id or len({file.id for file in payload.files}) != len(payload.files):
            raise HTTPException(422, "结果校验失败")
        if sum(file.size for file in payload.files) > MAX_CACHE_BYTES // 2:
            raise HTTPException(413, "配套文件总量过大")
        existing = self.store.db.execute("SELECT result_id,payload FROM final_results WHERE device_id=? AND source_id=?", (device, source)).fetchone()
        value = payload.model_dump(exclude={"source_id", "tool"})
        if value.get("diff") is None:
            value.pop("diff", None)
        if existing and existing["result_id"] == payload.result_id:
            prev = json.loads(existing["payload"])
            prev_core = {k: v for k, v in prev.items() if k != "diff"}
            next_core = {k: v for k, v in value.items() if k != "diff"}
            if prev_core != next_core:
                raise HTTPException(409, "结果版本不一致")
            if prev.get("diff") != value.get("diff") and "diff" in value:
                self.store.db.execute(
                    "UPDATE final_results SET payload=?,updated=? WHERE device_id=? AND source_id=?",
                    (json.dumps(value, ensure_ascii=False), time.time(), device, source),
                )
        else:
            if existing and json.loads(existing["payload"])["completed_at"] > payload.completed_at:
                raise HTTPException(409, "已有更新的最终结果")
            self.store.db.execute("DELETE FROM final_results WHERE device_id=? AND source_id=?", (device, source))
            self.store.db.execute("INSERT INTO final_results VALUES (?,?,?,?,?)", (device, source, payload.result_id, json.dumps(value, ensure_ascii=False), time.time()))
            for file in payload.files:
                self.store.db.execute("INSERT INTO final_files(device_id,source_id,file_id,payload) VALUES (?,?,?,?)", (device, source, file.id, file.model_dump_json()))
            # Task discovery is bounded but connectors can rotate identifiers.
            # Delete unsynchronized and oldest text records so that metadata
            # churn cannot grow the account database without bound.
            self.store.db.execute("""DELETE FROM final_results WHERE NOT EXISTS
                (SELECT 1 FROM tasks t WHERE t.device_id=final_results.device_id AND t.source_id=final_results.source_id)""")
            total = self.store.db.execute("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM final_results").fetchone()[0]
            count = self.store.db.execute("SELECT COUNT(*) FROM final_results").fetchone()[0]
            for old in self.store.db.execute("SELECT device_id,source_id FROM final_results WHERE NOT(device_id=? AND source_id=?) ORDER BY updated", (device, source)).fetchall():
                if total <= MAX_TEXT_CACHE_BYTES and count <= 500:
                    break
                self.store.db.execute("DELETE FROM final_results WHERE device_id=? AND source_id=?", tuple(old))
                total = self.store.db.execute("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM final_results").fetchone()[0]
                count -= 1
        requests = []
        for row in self.store.db.execute("SELECT file_id,length(content) AS received,ready FROM final_files WHERE device_id=? AND source_id=? AND wanted=1", (device, source)):
            if not row["ready"]:
                requests.append({"id": row["file_id"], "offset": row["received"]})
        return {"ok": True, "needed_files": requests}

    def status(self, device: str, payload: ResultStatus):
        self._enabled()
        needed = []
        for known in payload.results:
            # Scope every lookup to the verified connector, never a supplied
            # device id. A removed task cannot be requested or registered.
            self._task(device + ":" + known.source_id)
            row = self.store.db.execute("SELECT result_id FROM final_results WHERE device_id=? AND source_id=?", (device, known.source_id)).fetchone()
            incomplete = self.store.db.execute("SELECT 1 FROM final_files WHERE device_id=? AND source_id=? AND ready=0 AND wanted=1 LIMIT 1", (device, known.source_id)).fetchone()
            if not row or row["result_id"] != known.result_id or incomplete:
                needed.append(known.source_id)
        return {"needed_results": needed}

    def upload_chunk(self, device: str, chunk: FileChunk):
        self._enabled()
        self._task(device + ":" + chunk.source_id)
        final = self.store.db.execute("SELECT result_id FROM final_results WHERE device_id=? AND source_id=?", (device, chunk.source_id)).fetchone()
        if not final or final["result_id"] != chunk.result_id:
            raise HTTPException(409, "结果已更新")
        row = self.store.db.execute("SELECT * FROM final_files WHERE device_id=? AND source_id=? AND file_id=?", (device, chunk.source_id, chunk.file_id)).fetchone()
        if not row:
            raise HTTPException(404, "文件不存在")
        try:
            data = base64.b64decode(chunk.data, validate=True)
        except (ValueError, TypeError):
            raise HTTPException(422, "文件内容格式无效") from None
        metadata = json.loads(row["payload"])
        before = bytes(row["content"])
        if len(data) > CHUNK_BYTES or chunk.offset > len(before) or chunk.offset + len(data) > metadata["size"]:
            raise HTTPException(409, "文件分段不一致")
        if chunk.offset < len(before):
            if before[chunk.offset:chunk.offset + len(data)] != data:
                raise HTTPException(409, "文件分段不一致")
            return {"ok": True, "offset": len(before), "ready": bool(row["ready"])}
        if not data and metadata["size"] != 0:
            raise HTTPException(422, "文件分段为空")
        occupied = self.store.db.execute("SELECT COALESCE(SUM(length(content)),0) FROM final_files").fetchone()[0]
        if occupied + len(data) > MAX_CACHE_BYTES:
            # Keep final text. Evicted files are requested again only when the
            # owner opens that result, avoiding a perpetual upload/evict loop.
            candidates = self.store.db.execute("SELECT device_id,source_id FROM final_results WHERE NOT(device_id=? AND source_id=?) ORDER BY updated", (device, chunk.source_id)).fetchall()
            for old in candidates:
                self.store.db.execute("UPDATE final_files SET content=X'',ready=0,wanted=0 WHERE device_id=? AND source_id=?", tuple(old))
                occupied = self.store.db.execute("SELECT COALESCE(SUM(length(content)),0) FROM final_files").fetchone()[0]
                if occupied + len(data) <= MAX_CACHE_BYTES:
                    break
            if occupied + len(data) > MAX_CACHE_BYTES:
                raise HTTPException(413, "文件缓存已满，请稍后重试")
        content = before + data
        ready = len(content) == metadata["size"]
        if ready and (sha256(content) != metadata["sha256"] or not _safe_archive(content, Path(metadata["name"]).suffix.lower())):
            self.store.db.execute("UPDATE final_files SET content=X'',ready=0 WHERE device_id=? AND source_id=? AND file_id=?", (device, chunk.source_id, chunk.file_id))
            # Return an error response after resetting the partial bytes, so
            # the surrounding transaction commits the reset for a clean retry.
            return JSONResponse({"detail": "文件校验失败，请重新同步"}, status_code=409)
        self.store.db.execute("UPDATE final_files SET content=?,ready=? WHERE device_id=? AND source_id=? AND file_id=?", (content, int(ready), device, chunk.source_id, chunk.file_id))
        return {"ok": True, "offset": len(content), "ready": ready}


def install_result_routes(app, service: TaskResults, perform):
    def read(request, task_id, operation):
        # Log scans and file hashes must not hold the workbench/revocation lock.
        # Authorize before I/O, then serialize a second check against revocation
        # before disclosing the buffered answer. Neither check loads file bytes.
        perform(request, lambda reader: service.authorize(task_id))
        response = operation()
        return perform(request, lambda reader: (service.authorize(task_id), response)[1])

    @app.get("/api/native/tasks/result")
    def result(task_id: str, request: Request):
        return read(request, task_id, lambda: service.result(task_id))

    @app.get("/api/native/tasks/result.txt")
    def text(task_id: str, result_id: str, request: Request):
        return read(request, task_id, lambda: service.text(task_id, result_id))

    @app.get("/api/native/tasks/result/file")
    def file(task_id: str, result_id: str, file_id: str, request: Request):
        return read(request, task_id, lambda: service.file(task_id, result_id, file_id))


def install_agent_result_routes(app, service: TaskResults, agent_operation):
    @app.post("/api/agent/results/status")
    def status(payload: ResultStatus, request: Request):
        return agent_operation(request, lambda device: service.status(device, payload))

    @app.post("/api/agent/results")
    def upload(payload: FinalUpload, request: Request):
        return agent_operation(request, lambda device: service.upload(device, payload))

    @app.post("/api/agent/results/file")
    def upload_file(payload: FileChunk, request: Request):
        return agent_operation(request, lambda device: service.upload_chunk(device, payload))
