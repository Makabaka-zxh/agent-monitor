"""Synthetic tests for Antigravity usage accounting and verified deliverables."""
from __future__ import annotations

from contextlib import closing
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sqlite3
from unittest.mock import MagicMock
from urllib.parse import quote
import zipfile

from fastapi import HTTPException
from fastapi.testclient import TestClient
import pytest

from agent_monitor.desktop_controller import present_usage
from agent_monitor.server import create_app
from agent_monitor.task_results import LocalResults
from agent_monitor.usage_ledger import UsageLedger, _iso
from agent_monitor.usage_service import UsageService

SESSION = "52c5d365-f107-45ed-b82e-d2c9d4738b67"
SUBAGENT = "7a1d8290-33aa-44e4-98d4-0f42d4c25e11"
NOW = datetime(2026, 11, 18, 12, 0, tzinfo=timezone.utc)
SCHEMA = """
CREATE TABLE conversation_summaries (
 conversation_id text PRIMARY KEY, title text, last_modified_time text, workspace_uris text,
 status text, source text, parent_conversation_id text, nesting_depth integer,
 not_fully_idle numeric, killed numeric, step_count integer, last_user_input_step_index integer,
 preview text, raw_summary blob
)
"""


def _file_uri(path: Path) -> str:
    posix = path.resolve().as_posix()
    return ("file:///" + quote(posix, safe="/:") if os.name == "nt"
            else "file://" + quote(posix, safe="/"))


def _index_db(base: Path, *, workspace_uris: str, step_count: int = 2, user_step: int = 0,
              updated_at: str = "2026-11-18T11:59:30Z") -> Path:
    db = base / "conversation_summaries.db"
    row = (
        SESSION, "Synthetic Antigravity Task", updated_at, workspace_uris,
        "CASCADE_RUN_STATUS_IDLE", "", "", 0, 0, 0, step_count, user_step,
        "private preview", b"private blob",
    )
    with closing(sqlite3.connect(db)) as conn, conn:
        conn.execute(SCHEMA)
        conn.execute("INSERT INTO conversation_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", row)
    return db


def _write_transcript(base: Path, session_id: str, records: list[dict], *, filename: str = "transcript.jsonl") -> Path:
    path = base / "brain" / session_id / ".system_generated" / "logs" / filename
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"".join((json.dumps(r, ensure_ascii=False) + "\n").encode("utf-8") for r in records))
    return path


def test_antigravity_usage_ledger_scans_only_canonical_transcripts_and_handles_retries(tmp_path):
    ag_home = tmp_path / "antigravity"
    # Main session with retry at step_index=1 + checkpoint step + model attribution + unknown model scrubbing.
    main_steps = [
        {"step_index": 0, "source": "USER_EXPLICIT", "type": "USER_INPUT", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=10)), "content": "SECRET USER PROMPT"},
        {"step_index": 1, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=9)), "model": "gemini-3.8-flash-low",
         "input_tokens": 1000, "cache_read_tokens": 4000, "output_tokens": 200,
         "content": "SECRET PLANNER TEXT", "thinking": "SECRET THINKING"},
        # Retried step_index=1 at a later timestamp must NOT conflict or drop tokens.
        {"step_index": 1, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=8)), "model": "gemini-3.8-flash-low",
         "input_tokens": 900, "cache_read_tokens": 4100, "output_tokens": 250,
         "content": "RETRIED PLANNER TEXT"},
        # Context compaction checkpoint LLM call.
        {"step_index": 2, "source": "SYSTEM", "type": "CHECKPOINT", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=7)),
         "input_tokens": 300, "cache_read_tokens": 5000, "output_tokens": 700},
        # Unrecognized model string is scrubbed to None (unknown_model_tokens), never leaked.
        {"step_index": 3, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=6)), "model": "PRIVATE / CUSTOM / LABEL",
         "input_tokens": 100, "cache_read_tokens": 200, "output_tokens": 50},
        # Non-DONE step must be ignored.
        {"step_index": 4, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "RUNNING",
         "created_at": _iso(NOW - timedelta(minutes=5)),
         "input_tokens": 9999, "cache_read_tokens": 0, "output_tokens": 9999},
    ]
    _write_transcript(ag_home, SESSION, main_steps, filename="transcript.jsonl")
    # Duplicate full transcript, scratch jsonl, and non-UUID directory must be ignored completely.
    _write_transcript(ag_home, SESSION, main_steps, filename="transcript_full.jsonl")
    scratch = ag_home / "brain" / SESSION / "scratch" / "noise.jsonl"
    scratch.parent.mkdir(parents=True, exist_ok=True)
    scratch.write_text(json.dumps(main_steps[1]) + "\n", encoding="utf-8")
    _write_transcript(ag_home, "not-a-uuid", main_steps, filename="transcript.jsonl")

    # Subagent conversation contributes to provider daily total once, while keeping main own_session separate.
    _write_transcript(ag_home, SUBAGENT, [
        {"step_index": 1, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=4)), "model": "gpt-oss-120b-medium",
         "input_tokens": 500, "cache_read_tokens": 500, "output_tokens": 100},
    ])

    ledger = UsageLedger(tmp_path / "usage.sqlite3")
    try:
        report = ledger.scan(antigravity_home=ag_home, now=NOW)
        assert report["files_discovered"] == 2
        # Rescanning does not double count.
        ledger.scan(antigravity_home=ag_home, now=NOW)

        summary = ledger.summary(now=NOW)
        raw_json = json.dumps(summary, ensure_ascii=False)
        for secret in ("SECRET USER PROMPT", "SECRET PLANNER TEXT", "SECRET THINKING", "PRIVATE / CUSTOM / LABEL"):
            assert secret not in raw_json

        provider = next(p for p in summary["providers"] if p["tool"] == "antigravity")
        # Main: (1000+4000+200)=5200 + (900+4100+250)=5250 + (300+5000+700)=6000 + (100+200+50)=350 = 16800
        # Subagent: (500+500+100)=1100 -> Total = 17900 across 2 sessions.
        assert provider["today_tokens"] == 17900
        assert provider["session_count"] == 2
        assert provider["quotas"] == []
        today_entry = provider["history"]["days"][-1]
        assert today_entry["tokens"] == 17900
        assert today_entry["models"] == [
            {"model": "gemini-3.8-flash-low", "tokens": 10450},
            {"model": "gpt-oss-120b-medium", "tokens": 1100},
        ]
        assert today_entry["unknown_model_tokens"] == 6350

        own = ledger.task_usage("antigravity", f"antigravity:{SESSION}", now=NOW)
        assert own["total_tokens"] == 16800
        assert own["input_tokens"] == 2300
        assert own["output_tokens"] == 1200
        assert own["cached_input_tokens"] == 13300
        assert own["cache_write_tokens"] is None
        assert own["coverage"] == "partial"

        cards = present_usage(summary, now=NOW.timestamp())
        ag_card = next(c for c in cards if c["tool"] == "antigravity")
        assert ag_card["title"] == "Antigravity" and ag_card["today_tokens"] == 17900

        # Export v2 wire batch and ingest into a fresh Hub via /api/agent/usage.
        batch = ledger.export_batch()
        assert batch["version"] == 2 and len(batch["events"]) == 5
        app = create_app(tmp_path / "hub", collect_local=False)
        with TestClient(app) as client:
            reg = app.state.store.register(app.state.store.create_pairing()["code"], "Mac mini", "macOS")
            headers = {"Authorization": "Bearer " + reg["token"]}
            resp = client.post("/api/agent/usage", headers=headers, json=batch)
            assert resp.status_code == 200
            assert resp.json() == {"version": 2, "accepted": 5, "rejected": 0}
            hub_ag = next(p for p in app.state.usage.ledger.summary(NOW, [reg["device_id"]])["providers"]
                          if p["tool"] == "antigravity")
            assert hub_ag["today_tokens"] == 17900
    finally:
        ledger.close()


def test_usage_service_enriches_antigravity_tasks_without_error(tmp_path):
    store = MagicMock()
    store.snapshot.return_value = {
        "devices": [{"id": "pc-local", "name": "Local", "local": True}],
        "tasks": [{"id": f"pc-local:antigravity:{SESSION}", "device_id": "pc-local", "tool": "antigravity"}],
    }
    service = UsageService(tmp_path, store, enabled=False)
    try:
        service.refresh()
        enriched = service.enrich(store.snapshot.return_value)
        assert enriched["tasks"][0]["usage"]["tool"] == "antigravity"
        assert enriched["tasks"][0]["usage"]["coverage"] == "unavailable"
    finally:
        service.close()


def test_antigravity_local_deliverables_workspace_and_brain_artifacts_with_strict_boundaries(tmp_path, monkeypatch):
    monkeypatch.setattr("agent_monitor.collector.utc_now", lambda: NOW)
    ag_home = tmp_path / "antigravity"
    workspace = tmp_path / "workspace"
    (workspace / "docs").mkdir(parents=True)

    turn_start = NOW - timedelta(seconds=40)
    turn_end = NOW - timedelta(seconds=10)
    old_time = (NOW - timedelta(hours=2)).timestamp()
    in_turn_time = (NOW - timedelta(seconds=20)).timestamp()

    # 1. Workspace deliverable created during the turn and linked via file:// URI + line fragment.
    ws_report = workspace / "docs" / "audit-report.md"
    ws_report.write_bytes(b"# Workspace Deliverable\n")
    os.utime(ws_report, (in_turn_time, in_turn_time))

    # 2. Pre-existing untouched workspace source file cited in prose -> MUST NOT be attached.
    old_source = workspace / "existing.py"
    old_source.write_text("print('untouched')\n", encoding="utf-8")
    os.utime(old_source, (old_time, old_time))

    # 3. Brain artifact explicitly linked via file:// URI.
    brain_dir = ag_home / "brain" / SESSION
    brain_dir.mkdir(parents=True)
    brain_chart = brain_dir / "architecture.png"
    brain_chart.write_bytes(b"\x89PNG\r\n\x1a\nsynthetic")
    os.utime(brain_chart, (old_time, old_time))  # Created in an earlier turn of same session, linked in final answer.

    # 4. Unlinked brain artifact with userFacing: true created in this turn -> MUST be attached.
    brain_plan = brain_dir / "implementation_plan.md"
    brain_plan.write_bytes(b"# Implementation Plan\n")
    os.utime(brain_plan, (in_turn_time, in_turn_time))
    (brain_dir / "implementation_plan.md.metadata.json").write_text(
        json.dumps({"summary": "Plan", "userFacing": True}), encoding="utf-8"
    )

    # 5. Forbidden / non-deliverable files that MUST NEVER be exposed:
    # - userFacing: false unlinked file
    hidden_art = brain_dir / "internal_notes.md"
    hidden_art.write_text("internal", encoding="utf-8")
    os.utime(hidden_art, (in_turn_time, in_turn_time))
    (brain_dir / "internal_notes.md.metadata.json").write_text(
        json.dumps({"summary": "Internal", "userFacing": False}), encoding="utf-8"
    )
    # - scratch subdirectory file even if linked
    scratch_file = brain_dir / "scratch" / "temp.md"
    scratch_file.parent.mkdir(parents=True)
    scratch_file.write_text("scratch", encoding="utf-8")
    os.utime(scratch_file, (in_turn_time, in_turn_time))
    # - outside workspace file linked via file://
    outside = tmp_path / "outside-secret.md"
    outside.write_text("outside", encoding="utf-8")
    os.utime(outside, (in_turn_time, in_turn_time))
    # - unsafe zip with traversal inside workspace
    bad_zip = workspace / "bad.zip"
    with zipfile.ZipFile(bad_zip, "w") as zf:
        zf.writestr("../escape.txt", "nope")
    os.utime(bad_zip, (in_turn_time, in_turn_time))

    answer_text = "\n".join([
        f"报告已生成：[audit-report.md]({_file_uri(ws_report)}#L1-L5)",
        f"参考旧代码：[existing.py]({_file_uri(old_source)}#L1)",
        f"架构图：[architecture.png]({_file_uri(brain_chart)})",
        f"草稿（不应暴露）：[temp.md]({_file_uri(scratch_file)})",
        f"外部文件（不应暴露）：[outside]({_file_uri(outside)})",
        f"远程共享（不应暴露）：[unc](file://evil-server/share/audit-report.md)",
        f"危险压缩包（不应暴露）：[bad.zip]({_file_uri(bad_zip)})",
    ])

    _index_db(ag_home, workspace_uris=json.dumps([_file_uri(workspace)]), step_count=2, user_step=0,
              updated_at=_iso(turn_end))
    _write_transcript(ag_home, SESSION, [
        {"step_index": 0, "source": "USER_EXPLICIT", "type": "USER_INPUT", "status": "DONE",
         "created_at": _iso(turn_start), "content": "Generate report and plan."},
        {"step_index": 1, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(turn_end), "content": answer_text},
    ])

    results = LocalResults(antigravity_home=ag_home)
    source_id = f"antigravity:{SESSION}"
    final = results.read(source_id)
    assert final is not None
    names = [f["name"] for f in final["files"]]
    assert names == ["audit-report.md", "architecture.png", "implementation_plan.md"]

    # Every exposed file downloads cleanly with exact bytes.
    by_name = {f["name"]: f for f in final["files"]}
    raw_ws, meta_ws = results.file(source_id, final["result_id"], by_name["audit-report.md"]["id"])
    assert raw_ws == b"# Workspace Deliverable\n" and meta_ws["name"] == "audit-report.md"
    raw_plan, _ = results.file(source_id, final["result_id"], by_name["implementation_plan.md"]["id"])
    assert raw_plan == b"# Implementation Plan\n"

    # Mutating a file after final completion triggers HTTP 409 fail-closed rejection on download.
    old_file_id = by_name["audit-report.md"]["id"]
    ws_report.write_text("# Tampered after completion\n", encoding="utf-8")
    os.utime(ws_report, (NOW.timestamp() + 30, NOW.timestamp() + 30))
    with pytest.raises(HTTPException) as exc:
        results.file(source_id, final["result_id"], old_file_id)
    assert exc.value.status_code in (404, 409)


def _pb_encode_varint(val: int) -> bytes:
    out = bytearray()
    while True:
        chunk = val & 0x7F
        val >>= 7
        if val:
            out.append(chunk | 0x80)
        else:
            out.append(chunk)
            return bytes(out)


def _pb_encode_gen_metadata(step_indices: list[int], model: str, *, packed: bool = False) -> bytes:
    model_bytes = model.encode("utf-8")
    chat_model = _pb_encode_varint((19 << 3) | 2) + _pb_encode_varint(len(model_bytes)) + model_bytes
    blob = bytearray()
    blob += _pb_encode_varint((1 << 3) | 2) + _pb_encode_varint(len(chat_model)) + chat_model
    if packed:
        packed_steps = b"".join(_pb_encode_varint(s) for s in step_indices)
        blob += _pb_encode_varint((2 << 3) | 2) + _pb_encode_varint(len(packed_steps)) + packed_steps
    else:
        for s in step_indices:
            blob += _pb_encode_varint((2 << 3) | 0) + _pb_encode_varint(s)
    return bytes(blob)


def test_antigravity_gen_metadata_backfills_desktop_models_without_double_counting(tmp_path):
    from types import SimpleNamespace
    from agent_monitor.antigravity import read_latest_antigravity_model
    from agent_monitor.local_executor import _catalog_observation

    ag_home = tmp_path / "antigravity"
    steps = [
        {"step_index": 0, "source": "USER_EXPLICIT", "type": "USER_INPUT", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=10)), "content": "Desktop prompt"},
        {"step_index": 1, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=9)),
         "input_tokens": 2000, "cache_read_tokens": 8000, "output_tokens": 500},
        {"step_index": 2, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=8)),
         "input_tokens": 3000, "cache_read_tokens": 9000, "output_tokens": 1000},
        {"step_index": 3, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
         "created_at": _iso(NOW - timedelta(minutes=7)),
         "input_tokens": 400, "cache_read_tokens": 500, "output_tokens": 100},
    ]
    _write_transcript(ag_home, SESSION, steps)

    # First scan without conversations/<id>.db simulates v1 ledger where Desktop steps had model=NULL.
    db_path = tmp_path / "usage.sqlite3"
    ledger_v1 = UsageLedger(db_path)
    try:
        ledger_v1.scan(antigravity_home=ag_home, now=NOW)
        s1 = ledger_v1.summary(now=NOW)
        ag1 = next(p for p in s1["providers"] if p["tool"] == "antigravity")
        assert ag1["today_tokens"] == 24500
        assert ag1["history"]["days"][-1]["models"] == []
        assert ag1["history"]["days"][-1]["unknown_model_tokens"] == 24500
        with ledger_v1.db:
            ledger_v1.db.execute("UPDATE usage_meta SET value='1' WHERE key='local_antigravity_parser_version'")
    finally:
        ledger_v1.close()

    # Create Desktop conversation DB with gen_metadata (including packed varint and conflicting step 3).
    conv_dir = ag_home / "conversations"
    conv_dir.mkdir(parents=True, exist_ok=True)
    conv_db = conv_dir / f"{SESSION}.db"
    with closing(sqlite3.connect(conv_db)) as conn, conn:
        conn.execute("CREATE TABLE gen_metadata (idx INTEGER PRIMARY KEY, data BLOB, size INTEGER)")
        b1 = _pb_encode_gen_metadata([1], "gemini-4-carbon")
        b2 = _pb_encode_gen_metadata([2], "claude-opus-5-5-high", packed=True)
        b3a = _pb_encode_gen_metadata([3], "gemini-4-carbon")
        b3b = _pb_encode_gen_metadata([3], "gemini-3.8-flash-low")
        conn.executemany(
            "INSERT INTO gen_metadata(idx, data, size) VALUES (?, ?, ?)",
            [(1, b1, len(b1)), (2, b2, len(b2)), (3, b3a, len(b3a)), (4, b3b, len(b3b))],
        )

    assert read_latest_antigravity_model(conv_db) == "gemini-3.8-flash-low"
    ws = tmp_path / "ws"
    ws.mkdir()
    obs_model, obs_effort = _catalog_observation(
        SimpleNamespace(source_id=f"antigravity:{SESSION}", tool="antigravity", session_id=SESSION, path=conv_db, cwd=ws)
    )
    assert obs_model == "gemini-3.8-flash-low" and obs_effort is None

    # Re-opening UsageLedger triggers v2 parser migration and enriches models without double-counting!
    ledger_v2 = UsageLedger(db_path)
    try:
        ledger_v2.scan(antigravity_home=ag_home, now=NOW)
        s2 = ledger_v2.summary(now=NOW)
        ag2 = next(p for p in s2["providers"] if p["tool"] == "antigravity")
        assert ag2["today_tokens"] == 24500
        day_row = ag2["history"]["days"][-1]
        assert day_row["models"] == [
            {"model": "claude-opus-5-5-high", "tokens": 13000},
            {"model": "gemini-4-carbon", "tokens": 10500},
        ]
        # Conflicting retry on step 3 stays safely in unknown_model_tokens.
        assert day_row["unknown_model_tokens"] == 1000
    finally:
        ledger_v2.close()


def test_antigravity_official_quota_scrubs_pii_handles_proto3_zero_and_matches_acp_catalog(tmp_path):
    from agent_monitor.antigravity_quota import (
        _scrub_cascade_config,
        antigravity_fast_catalog_rows,
        read_antigravity_quota,
    )
    from agent_monitor.model_capabilities import antigravity_catalog_rows, normalize_catalog

    five_h_reset = _iso(NOW + timedelta(hours=3, minutes=20))
    weekly_reset = _iso(NOW + timedelta(days=6))
    raw_rpc = {
        "userStatus": {
            "name": "PRIVATE_USER_NAME",
            "email": "private.user@example.com",
            "profilePictureUrl": "https://example.com/private-avatar.png",
            "cascadeModelConfigData": {
                "defaultOverrideModelConfig": {"modelOrAlias": {"model": "MODEL_PLACEHOLDER_M532"}},
                "clientModelConfigs": [
                    {"label": "Gemini 4 Neon (High)", "modelId": "gemini-4-neon-high",
                     "modelOrAlias": {"model": "MODEL_PLACEHOLDER_M532"},
                     "quotaInfo": {"remainingFraction": 0.88, "resetTime": five_h_reset}},
                    {"label": "Gemini 4 Neon (Low)", "modelId": "gemini-4-neon-low",
                     "modelOrAlias": {"model": "MODEL_PLACEHOLDER_M534"},
                     "quotaInfo": {"remainingFraction": 0.88, "resetTime": five_h_reset}},
                    {"label": "Orchestrator (Carbon)", "modelId": "gemini-pro-orchestrator",
                     "quotaInfo": {"remainingFraction": 0.88, "resetTime": five_h_reset}},
                    # Exhausted weekly Claude + GPT-OSS pool: proto3 JSON omits remainingFraction when 0.0!
                    {"label": "Claude Opus 5.5 (Low)", "modelId": "claude-opus-5-5-low",
                     "quotaInfo": {"resetTime": weekly_reset}},
                    {"label": "Claude Opus 5.5 (High)", "modelId": "claude-opus-5-5-high",
                     "quotaInfo": {"resetTime": weekly_reset}},
                    {"label": "GPT-OSS 120B (Medium)", "modelId": "gpt-oss-120b-medium",
                     "quotaInfo": {"resetTime": weekly_reset}},
                ],
            },
        }
    }

    scrubbed = _scrub_cascade_config(raw_rpc)
    assert scrubbed is not None
    serialized_scrub = json.dumps(scrubbed)
    for secret in ("PRIVATE_USER_NAME", "private.user@example.com", "private-avatar.png"):
        assert secret not in serialized_scrub

    # Fast catalog matches ACP catalog revision bit-for-bit!
    fast_rows, fast_cm, fast_ce = antigravity_fast_catalog_rows(scrubbed)
    assert fast_cm == "gemini-4-neon" and fast_ce == "high"
    fast_cat = normalize_catalog("antigravity", fast_rows, current_model=fast_cm, current_effort=fast_ce, now=100)

    acp_payload = {
        "configOptions": [
            {"id": "model", "category": "model", "currentValue": "gemini-4-neon", "options": [
                {"value": "gemini-4-neon", "name": "Gemini 4 Neon"},
                {"value": "gemini-pro-orchestrator", "name": "Orchestrator (Carbon)"},
                {"value": "claude-opus-5-5", "name": "Claude Opus 5.5"},
                {"value": "gpt-oss-120b-medium", "name": "GPT-OSS 120B (Medium)"},
            ]},
            {"id": "thought_level", "category": "thought_level", "currentValue": "high", "options": [
                {"value": "high", "name": "High"}, {"value": "low", "name": "Low"},
            ]},
        ],
        "models": {"availableModels": [
            {"modelId": "gemini-4-neon-high"}, {"modelId": "gemini-4-neon-low"},
            {"modelId": "gemini-pro-orchestrator"},
            {"modelId": "claude-opus-5-5-low"}, {"modelId": "claude-opus-5-5-high"},
            {"modelId": "gpt-oss-120b-medium"},
        ]},
    }
    acp_rows, acp_cm, acp_ce = antigravity_catalog_rows(acp_payload)
    acp_cat = normalize_catalog("antigravity", acp_rows, current_model=acp_cm, current_effort=acp_ce, now=100)
    assert fast_cat["catalog_revision"] == acp_cat["catalog_revision"]

    # Quota ingestion + weekly window preservation near reset + remote Hub wire sync.
    quota_payload = read_antigravity_quota(now=NOW, status_fetcher=lambda: scrubbed)
    assert quota_payload is not None
    ledger = UsageLedger(tmp_path / "quota.sqlite3")
    try:
        res = ledger.ingest_antigravity_quota(quota_payload, now=NOW)
        assert res == {"accepted": 2, "rejected": 0}
        # Re-ingesting 2 hours before the weekly reset keeps window_minutes == 10080 (never shrinks to 300).
        near_reset = NOW + timedelta(days=5, hours=22)
        near_payload = read_antigravity_quota(now=near_reset, status_fetcher=lambda: scrubbed)
        assert ledger.ingest_antigravity_quota(near_payload, now=near_reset) == {"accepted": 2, "rejected": 0}

        summary = ledger.summary(now=NOW)
        ag = next(p for p in summary["providers"] if p["tool"] == "antigravity")
        by_label = {q["bucket_label"]: q for q in ag["quotas"]}
        assert by_label["Gemini"]["used_percent"] == 12.0
        assert by_label["Gemini"]["remaining_percent"] == 88.0
        assert by_label["Gemini"]["window_minutes"] == 300
        assert by_label["Claude / GPT-OSS"]["used_percent"] == 100.0
        assert by_label["Claude / GPT-OSS"]["remaining_percent"] == 0.0
        assert by_label["Claude / GPT-OSS"]["window_minutes"] == 10080

        cards = present_usage(summary, now=NOW.timestamp())
        ag_card = next(c for c in cards if c["tool"] == "antigravity")
        assert [q["label"] for q in ag_card["quotas"]] == ["Gemini · 5 小时", "Claude / GPT-OSS · 7 天"]

        # Remote connector export -> Hub ingest_batch accepts Antigravity quotas & snapshots.
        q_batch = ledger.export_quota_batch()
        hub_ledger = UsageLedger(tmp_path / "hub-quota.sqlite3")
        try:
            hub_res = hub_ledger.ingest_batch("mac-mini", q_batch, now=near_reset)
            assert hub_res["rejected"] == 0 and hub_res["accepted"] >= 2
            hub_ag = next(p for p in hub_ledger.summary(near_reset, ["mac-mini"])["providers"] if p["tool"] == "antigravity")
            assert len(hub_ag["quotas"]) == 2
        finally:
            hub_ledger.close()
    finally:
        ledger.close()


def test_antigravity_retrieve_user_quota_summary_emits_both_5h_and_weekly_windows_and_handles_disabled_5h(tmp_path):
    from agent_monitor.antigravity_quota import _scrub_quota_summary, read_antigravity_quota

    five_h_reset = _iso(NOW + timedelta(hours=4, minutes=20))
    gemini_weekly_reset = _iso(NOW + timedelta(days=5, hours=7))
    claude_weekly_reset = _iso(NOW + timedelta(days=4, hours=13))

    raw_summary = {
        "userEmail": "never.leak@example.com",
        "response": {
            "groups": [
                {
                    "displayName": "Gemini Models",
                    "description": "Models within this group: Gemini 4 Neon, Gemini 4 Carbon",
                    "buckets": [
                        {
                            "bucketId": "gemini-weekly",
                            "window": "weekly",
                            "remainingFraction": 0.933,
                            "resetTime": gemini_weekly_reset,
                        },
                        {
                            "bucketId": "gemini-5h",
                            "window": "5h",
                            "remainingFraction": 0.977,
                            "resetTime": five_h_reset,
                        },
                    ],
                },
                {
                    "displayName": "Claude and GPT models",
                    "buckets": [
                        {
                            "bucketId": "3p-weekly",
                            "window": "weekly",
                            "remainingFraction": 0,
                            "resetTime": claude_weekly_reset,
                        },
                        {
                            "bucketId": "3p-5h",
                            "window": "5h",
                            "remainingFraction": 1,
                            "disabled": True,
                        },
                    ],
                },
            ]
        },
    }

    scrubbed = _scrub_quota_summary(raw_summary)
    assert scrubbed is not None
    assert "never.leak@example.com" not in json.dumps(scrubbed)

    quota_payload = read_antigravity_quota(
        now=NOW,
        summary_fetcher=lambda: scrubbed,
        status_fetcher=lambda: None,
    )
    assert quota_payload is not None
    assert len(quota_payload["quota_windows"]) == 4

    ledger = UsageLedger(tmp_path / "dual_quota.sqlite3")
    try:
        res = ledger.ingest_antigravity_quota(quota_payload, now=NOW)
        assert res == {"accepted": 4, "rejected": 0}
        summary = ledger.summary(now=NOW)
        ag = next(p for p in summary["providers"] if p["tool"] == "antigravity")
        assert len(ag["quotas"]) == 4

        by_pair = {(q["bucket_label"], q["window_minutes"]): q for q in ag["quotas"]}
        assert by_pair[("Gemini", 300)]["used_percent"] == 2.3
        assert by_pair[("Gemini", 10080)]["used_percent"] == 6.7
        assert by_pair[("Claude / GPT-OSS", 10080)]["used_percent"] == 100.0
        # Disabled 5h bucket reflects weekly cap (100% used, resets with weekly window) while keeping 300m slot!
        assert by_pair[("Claude / GPT-OSS", 300)]["used_percent"] == 100.0
        assert by_pair[("Claude / GPT-OSS", 300)]["resets_at"] == by_pair[("Claude / GPT-OSS", 10080)]["resets_at"]
    finally:
        ledger.close()

