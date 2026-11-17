import hashlib
import json
import os
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from uuid import UUID

import pytest

from agent_monitor.antigravity import antigravity_available, read_antigravity_result, scan_antigravity


SESSION = "e7abf4ca-82f2-4ada-abb3-8f9bba3861bc"
NOW = datetime(2026, 1, 11, 10, 2, tzinfo=timezone.utc)
SCHEMA = """
CREATE TABLE conversation_summaries (
 conversation_id text PRIMARY KEY, title text, last_modified_time text, workspace_uris text,
 status text, source text, parent_conversation_id text, nesting_depth integer,
 not_fully_idle numeric, killed numeric, step_count integer, last_user_input_step_index integer,
 preview text, raw_summary blob
)
"""


def row(session=SESSION, **overrides):
    values = {
        "conversation_id": session, "title": "界面\u202E \t试验", "last_modified_time": "2026-01-11T10:01:30Z",
        "workspace_uris": '["file:///D:/private%20parent/Monitor%20fixture"]',
        "status": "CASCADE_RUN_STATUS_IDLE", "source": "", "parent_conversation_id": "",
        "nesting_depth": 0, "not_fully_idle": 0, "killed": 0, "step_count": 3, "last_user_input_step_index": 0,
        "preview": "personal prompt must never surface", "raw_summary": b"private opaque provider content",
    }
    values.update(overrides)
    return tuple(values.values())


def fixture(base, *rows):
    db = base / "conversation_summaries.db"
    with closing(sqlite3.connect(db)) as dbh, dbh:
        dbh.execute(SCHEMA)
        dbh.executemany("INSERT INTO conversation_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)
    return db


def write_transcript(base, records, session=SESSION):
    path = base / "brain" / session / ".system_generated" / "logs" / "transcript.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"".join((json.dumps(rec) + "\n").encode() for rec in records))
    return path


def transcript(base, session=SESSION, **replace):
    answer = {"step_index": 2, "type": "PLANNER_RESPONSE", "source": "MODEL", "status": "DONE",
              "created_at": "2026-01-11T10:01:29Z", "content": "Only the final answer.",
              "thinking": "private model reasoning"}
    answer.update(replace)
    records = [{"step_index": 0, "type": "USER_INPUT", "content": "private user text"},
               {"step_index": 1, "type": "GENERIC", "content": "private tool output"}, answer]
    return write_transcript(base, records, session)


def test_index_scans_display_metadata_only_and_rejects_ambiguous_states(tmp_path):
    siblings = [str(UUID(int=i)) for i in range(10, 19)]
    fixture(tmp_path, row(), row(siblings[0], status="CASCADE_RUN_STATUS_RUNNING"),
            row(siblings[1], status="CASCADE_RUN_STATUS_RUNNING", last_modified_time="2026-01-11T09:56:50Z"),
            row(siblings[2], not_fully_idle=1),
            row(siblings[3], status="CASCADE_RUN_STATUS_BUSY", workspace_uris='["file://remote/secret"]'),
            row(siblings[4], parent_conversation_id=SESSION),
            row(siblings[5], source="TRAJECTORY_SOURCE_SDK"),
            row(siblings[6], nesting_depth=1), row(siblings[7], last_modified_time="2026-01-12T10:00:00Z"),
            row(siblings[8], workspace_uris='["file:///D:/do%2fnot/label"]'),
            row("a" * 36))
    observed = {r["id"]: r for r in scan_antigravity(base=tmp_path, now=NOW)}
    assert len(observed) == 6
    assert all(r["tool"] == "antigravity" and r["output"] == "" and r["status_source"] == "local_log" for r in observed.values())
    assert observed["antigravity:" + SESSION]["title"] == "界面 试验"
    assert observed["antigravity:" + SESSION]["project"] == "Monitor fixture"
    assert [observed["antigravity:" + sid]["status"] for sid in [SESSION, *siblings[:4]]] == [
        "idle", "running", "unknown", "unknown", "unknown"]
    assert observed["antigravity:" + siblings[3]]["project"] == observed["antigravity:" + siblings[8]]["project"] == ""
    assert "personal prompt" not in str(observed) and "private" not in str(observed)
    assert len(scan_antigravity(base=tmp_path, limit=1, now=NOW)) == 1


def test_wal_visibility_and_closed_index_never_create_files(tmp_path):
    p = fixture(tmp_path)
    with closing(sqlite3.connect(p)) as writer:
        assert writer.execute("PRAGMA journal_mode=wal").fetchone() == ("wal",)
        writer.execute("INSERT INTO conversation_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", row())
        writer.commit()
        assert p.with_name(p.name + "-wal").is_file() and p.with_name(p.name + "-shm").is_file()
        assert len(scan_antigravity(base=tmp_path, now=NOW)) == 1
    before = sorted(t.name for t in tmp_path.iterdir())
    assert before == [p.name]
    assert antigravity_available(base=tmp_path) and len(scan_antigravity(base=tmp_path, now=NOW)) == 1
    assert sorted(t.name for t in tmp_path.iterdir()) == before
    p.with_name(p.name + "-wal").write_bytes(b"unpaired")
    assert scan_antigravity(base=tmp_path, now=NOW) is None
    assert not p.with_name(p.name + "-shm").exists()


def test_unavailable_distinct_from_empty_and_rejects_alternate_index(tmp_path):
    assert scan_antigravity(base=tmp_path) is None and not antigravity_available(base=tmp_path)
    p = fixture(tmp_path)
    assert scan_antigravity(base=tmp_path) == []
    os.link(p, tmp_path / "alternate-index.db")
    assert scan_antigravity(base=tmp_path) is None


def test_only_the_confirmed_final_model_text_is_read(tmp_path):
    fixture(tmp_path, row())
    transcript(tmp_path)
    result = read_antigravity_result(SESSION, base=tmp_path)
    assert result == {
        "text": "Only the final answer.", "completed_at": "2026-01-11T10:01:29.000Z",
        "result_id": hashlib.sha256(b"antigravity\n2026-01-11T10:01:29.000Z\nOnly the final answer.").hexdigest(),
        "started_at": "", "truncated": False, "files": [],
    }
    assert read_antigravity_result("../" + SESSION, base=tmp_path) is None
    transcript(tmp_path, truncated_fields=["content"])
    assert read_antigravity_result(SESSION, base=tmp_path)["truncated"]


def test_no_project_first_turn_recovers_user_step_from_transcript(tmp_path):
    # The Windows desktop app can leave the index at -1 even when the
    # No Project transcript contains the completed first user turn.
    fixture(tmp_path, row(workspace_uris="[]", step_count=2, last_user_input_step_index=-1))
    write_transcript(tmp_path, (
        {"step_index": 0, "type": "USER_INPUT", "source": "USER_EXPLICIT", "status": "DONE",
         "created_at": "2026-01-11T10:01:03.123456789Z", "content": "Private user request."},
        {"step_index": 1, "type": "PLANNER_RESPONSE", "source": "MODEL", "status": "DONE",
         "created_at": "2026-01-11T10:01:29.432198765Z", "content": "Public final answer."},
    ))
    result = read_antigravity_result(SESSION, base=tmp_path)
    assert result == {
        "text": "Public final answer.", "completed_at": "2026-01-11T10:01:29.432Z",
        "result_id": hashlib.sha256(b"antigravity\n2026-01-11T10:01:29.432Z\nPublic final answer.").hexdigest(),
        "started_at": "", "truncated": False, "files": [],
    }


@pytest.mark.parametrize("user_override,order", [
    ({}, (1,)),
    ({"source": "MODEL"}, (0, 1)),
    ({"type": "GENERIC"}, (0, 1)),
    ({"status": "RUNNING"}, (0, 1)),
    ({"step_index": 1}, (0, 1)),
    ({}, (1, 0)),
    ({"created_at": "2026-01-11T10:01:30Z"}, (0, 1)),
], ids=["no-user-row", "model-impersonation", "unrelated-user-step", "partial-user-step",
        "duplicated-index", "reversed-row-order", "reversed-timestamps"])
def test_no_project_first_turn_rejects_unverified_user_step(tmp_path, user_override, order):
    fixture(tmp_path, row(workspace_uris="[]", step_count=2, last_user_input_step_index=-1))
    steps = (
        {"step_index": 0, "type": "USER_INPUT", "source": "USER_EXPLICIT", "status": "DONE",
         "created_at": "2026-01-11T10:01:03Z", "content": "Private user request.", **user_override},
        {"step_index": 1, "type": "PLANNER_RESPONSE", "source": "MODEL", "status": "DONE",
         "created_at": "2026-01-11T10:01:29Z", "content": "Unverified model text."},
    )
    write_transcript(tmp_path, (steps[index] for index in order))
    assert read_antigravity_result(SESSION, base=tmp_path) is None


@pytest.mark.parametrize("index,tail", [
    ({"status": "CASCADE_RUN_STATUS_RUNNING"}, {}),
    ({"not_fully_idle": 1}, {}),
    ({"killed": 1}, {}),
    ({"step_count": 4}, {}),
    ({"last_user_input_step_index": 2}, {}),
    ({}, {"tool_calls": [{"name": "run_command"}]}),
    ({}, {"type": "GENERIC"}),
    ({}, {"source": "USER_EXPLICIT"}),
    ({}, {"status": "RUNNING"}),
    ({}, {"created_at": "2026-01-11T10:01:31Z"}),
    ({}, {"created_at": "2026-01-11T10:01:29"}),
    ({}, {"attachments": []}),
])
def test_unverified_candidates_are_never_published(tmp_path, index, tail):
    fixture(tmp_path, row(**index))
    transcript(tmp_path, **tail)
    assert read_antigravity_result(SESSION, base=tmp_path) is None


def test_does_not_return_earlier_text_after_later_steps_or_partial_writes(tmp_path):
    fixture(tmp_path, row())
    path = transcript(tmp_path)
    path.write_bytes(path.read_bytes() + b'{"step_index":3,"type":"USER_INPUT"')
    assert read_antigravity_result(SESSION, base=tmp_path) is None
    path = transcript(tmp_path)
    path.write_bytes(path.read_bytes() + b'{"step_index":3,"type":"USER_INPUT"}\n')
    assert read_antigravity_result(SESSION, base=tmp_path) is None


def test_long_result_expands_bounded_tail_and_marks_truncation(tmp_path):
    fixture(tmp_path, row())
    transcript(tmp_path, content="x" * 200_013)
    result = read_antigravity_result(SESSION, base=tmp_path)
    assert result["truncated"] and result["text"] == "x" * 200_000
    transcript(tmp_path, content="x" * (2 * 1024 * 1024))
    assert read_antigravity_result(SESSION, base=tmp_path) is None


def test_collect_and_read_result_follow_output_opt_in(tmp_path, monkeypatch):
    from agent_monitor.collector import collect_snapshot
    from agent_monitor.task_results import LocalResults

    monkeypatch.setenv("AGENT_MONITOR_STATE_DIR", str(tmp_path / "monitor"))
    base = tmp_path / "antigravity"
    base.mkdir()
    fixture(base, row())
    transcript(base, content="Only the final answer. potential-report.txt")
    opts = {"codex_home": tmp_path / "codex", "claude_home": tmp_path / "claude",
            "antigravity_home": base}
    hidden = collect_snapshot(**opts)
    exposed = collect_snapshot(**opts, include_output=True)
    assert [item["tool"] for item in hidden["sources"] if item.get("available")] == ["antigravity"]
    assert len(hidden["tasks"]) == len(exposed["tasks"]) == 1
    assert "final_result_id" not in hidden["tasks"][0]
    for preview in (hidden["tasks"][0], exposed["tasks"][0]):
        assert preview["output"] == "" and "Only the final answer" not in str(preview)
    result = LocalResults(**opts).read("antigravity:" + SESSION)
    assert result["result_id"] == exposed["tasks"][0]["final_result_id"]
    assert result["completed_at"] == exposed["tasks"][0]["final_result_at"]
    assert result["text"] == "Only the final answer. potential-report.txt"
    assert result["files"] == []
