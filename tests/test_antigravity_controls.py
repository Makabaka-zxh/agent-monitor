"""Synthetic unit tests for Antigravity ACP controls, permissions, and execution."""
from __future__ import annotations

from contextlib import closing
import json
import os
from pathlib import Path
import queue
import sqlite3
import threading
import time
from urllib.parse import quote
import uuid

import pytest

from agent_monitor import local_executor as le
from agent_monitor.antigravity import read_antigravity_result, resolve_antigravity_session
from agent_monitor.failure_reasons import failure_code_for_antigravity
from agent_monitor.model_capabilities import antigravity_catalog_rows, normalize_catalog, selected_settings
from agent_monitor.provider_controls import UnsupportedControl, normalize_request
from agent_monitor.task_replies import AgentPoll
from agent_monitor.task_results import resolve_local_session

SESSION = "c08a368f-0fff-4fd5-8792-2af18c4d8205"
SCHEMA = """
CREATE TABLE conversation_summaries (
 conversation_id text PRIMARY KEY, title text, last_modified_time text, workspace_uris text,
 status text, source text, parent_conversation_id text, nesting_depth integer,
 not_fully_idle numeric, killed numeric, step_count integer, last_user_input_step_index integer,
 preview text, raw_summary blob
)
"""

ACP_RESUME_SAMPLE = {
    "sessionId": SESSION,
    "models": {
        "availableModels": [
            {"modelId": "gemini-4-carbon-low", "name": "Gemini 4 Carbon (Low)"},
            {"modelId": "gemini-4-carbon-medium", "name": "Gemini 4 Carbon (Medium)"},
            {"modelId": "gemini-4-carbon-high", "name": "Gemini 4 Carbon (High)"},
            {"modelId": "gemini-4-hydrogen-low", "name": "Gemini 4 Hydrogen (Low)"},
            {"modelId": "gemini-4-hydrogen-xhigh", "name": "Gemini 4 Hydrogen (XHigh)"},
            {"modelId": "gemini-pro-orchestrator", "name": "Orchestrator (Carbon)"},
            {"modelId": "gemini-pro-orchestrator-argon", "name": "Orchestrator (Argon)"},
            {"modelId": "gemini-3.8-flash-low", "name": "Gemini 3.8 Flash (Low)"},
            {"modelId": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (High)"},
            {"modelId": "gpt-oss-120b-medium", "name": "GPT-OSS 120B (Medium)"},
        ],
        "currentModelId": "gemini-4-carbon-medium",
    },
    "modes": {
        "currentModeId": "default",
        "availableModes": [
            {"id": "default", "name": "Default"},
            {"id": "accept-edits", "name": "Accept edits"},
            {"id": "auto", "name": "Auto"},
            {"id": "plan", "name": "Plan"},
        ],
    },
    "configOptions": [
        {
            "id": "model",
            "name": "Model",
            "category": "model",
            "type": "select",
            "currentValue": "gemini-4-carbon",
            "options": [
                {"value": "gemini-4-carbon", "name": "Gemini 4 Carbon"},
                {"value": "gemini-4-hydrogen", "name": "Gemini 4 Hydrogen"},
                {"value": "gemini-pro-orchestrator", "name": "Orchestrator (Carbon)"},
                {"value": "gemini-pro-orchestrator-argon", "name": "Orchestrator (Argon)"},
                {"value": "gemini-3.8-flash", "name": "Gemini 3.8 Flash"},
                {"value": "gpt-oss-120b-medium", "name": "GPT-OSS 120B (Medium)"},
            ],
        },
        {
            "id": "thought_level",
            "name": "Thinking Level",
            "category": "thought_level",
            "type": "select",
            "currentValue": "medium",
            "options": [
                {"value": "low", "name": "Low"},
                {"value": "medium", "name": "Medium"},
                {"value": "high", "name": "High"},
            ],
        },
        {
            "id": "mode",
            "name": "Mode",
            "category": "mode",
            "type": "select",
            "currentValue": "default",
            "options": [{"value": "default", "name": "Default"}, {"value": "plan", "name": "Plan"}],
        },
    ],
}


def workspace_uri(path: Path) -> str:
    posix = path.resolve().as_posix()
    if os.name == "nt":
        drive, rest = posix.split(":", 1)
        return f"file:///{drive.lower()}%3A{quote(rest, safe='/')}"
    return f"file://{quote(posix, safe='/')}"


def make_antigravity_fixture(tmp_path: Path, *, session_id: str = SESSION, status: str = "CASCADE_RUN_STATUS_IDLE",
                             source: str = "", killed: int = 0, parent: str = "", ws_override: str | None = None,
                             model_field: str | None = "gemini-3.8-flash-low"):
    ag = tmp_path / ".gemini" / "antigravity"
    ws = tmp_path / "workspace"
    ws.mkdir(parents=True, exist_ok=True)
    (ag / "conversations").mkdir(parents=True, exist_ok=True)
    (ag / "presence").mkdir(parents=True, exist_ok=True)
    logs = ag / "brain" / session_id / ".system_generated" / "logs"
    logs.mkdir(parents=True, exist_ok=True)

    conv_db = ag / "conversations" / f"{session_id}.db"
    conv_db.write_bytes(b"SQLite format 3\x00" + b"\x00" * 84)

    now_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    uri_json = json.dumps([workspace_uri(ws)]) if ws_override is None else ws_override
    with closing(sqlite3.connect(ag / "conversation_summaries.db")) as dbh, dbh:
        dbh.execute(SCHEMA)
        dbh.execute(
            "INSERT INTO conversation_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (session_id, "Synthetic Antigravity", now_iso, uri_json, status, source, parent, 0, 0, killed, 2, 0, "", b""),
        )

    final_step = {
        "step_index": 1, "source": "MODEL", "type": "PLANNER_RESPONSE", "status": "DONE",
        "created_at": now_iso, "content": "ACP model-tagged answer.",
    }
    if model_field is not None:
        final_step["model"] = model_field
    records = [{"step_index": 0, "source": "USER_EXPLICIT", "type": "USER_INPUT", "status": "DONE",
                "created_at": now_iso, "content": "Prompt"}, final_step]
    (logs / "transcript.jsonl").write_bytes(b"".join((json.dumps(r) + "\n").encode() for r in records))
    return ag, ws, conv_db


def test_session_resolution_and_model_tagged_transcript(tmp_path):
    ag, ws, conv_db = make_antigravity_fixture(tmp_path)
    resolved = resolve_local_session("antigravity:" + SESSION, antigravity_home=ag)
    assert resolved is not None
    assert resolved.path == conv_db and resolved.cwd == ws.resolve() and resolved.tool == "antigravity"

    result = read_antigravity_result(SESSION, base=ag)
    assert result is not None and result["text"] == "ACP model-tagged answer."

    # Non-string model field in transcript fails closed.
    bad_sid = str(uuid.UUID(int=2))
    ag2, _, _ = make_antigravity_fixture(tmp_path / "bad_model", session_id=bad_sid, model_field=None)
    tr = ag2 / "brain" / bad_sid / ".system_generated" / "logs" / "transcript.jsonl"
    lines = [json.loads(x) for x in tr.read_text().splitlines()]
    lines[-1]["model"] = 123
    tr.write_bytes(b"".join((json.dumps(r) + "\n").encode() for r in lines))
    assert read_antigravity_result(bad_sid, base=ag2) is None

    # Projectless, remote URI, SDK source, killed, or subagent sessions fail closed.
    for idx, kwargs in enumerate([
        {"ws_override": ""},
        {"ws_override": '["file://remote-host/share"]'},
        {"source": "TRAJECTORY_SOURCE_SDK"},
        {"killed": 1},
        {"parent": str(uuid.UUID(int=99))},
    ], start=10):
        sid = str(uuid.UUID(int=idx))
        sub_ag, _, _ = make_antigravity_fixture(tmp_path / f"case_{idx}", session_id=sid, **kwargs)
        assert resolve_antigravity_session(sid, base=sub_ag) is None
        assert resolve_local_session("antigravity:" + sid, antigravity_home=sub_ag) is None


def test_catalog_derives_per_model_efforts_and_splits_observed_variant():
    rows, cur_model, cur_effort = antigravity_catalog_rows(ACP_RESUME_SAMPLE)
    catalog = normalize_catalog("antigravity", rows, current_model="gemini-3.8-flash-low", now=100)
    by_id = {m["id"]: m for m in catalog["models"]}
    assert by_id["gemini-4-carbon"]["efforts"] == ["low", "medium", "high"]
    assert by_id["gemini-4-hydrogen"]["efforts"] == ["low", "xhigh"]
    # Orchestrator (Argon) is its own model option, never misread as an effort of Orchestrator (Carbon).
    assert by_id["gemini-pro-orchestrator"]["efforts"] == []
    assert by_id["gemini-pro-orchestrator-argon"]["efforts"] == []
    assert by_id["gpt-oss-120b-medium"]["efforts"] == []
    assert catalog["current_model"] == "gemini-3.8-flash"
    assert catalog["current_effort"] == "low"

    chosen = {"provider": "antigravity", "catalog_revision": catalog["catalog_revision"],
              "model": "gemini-4-hydrogen", "effort": "xhigh", "apply_at": "next_message"}
    assert selected_settings(chosen, catalog, provider="antigravity", session_id=SESSION, now=101) == {
        "model": "gemini-4-hydrogen", "effort": "xhigh"}
    with pytest.raises(ValueError):
        selected_settings({**chosen, "service_tier": "priority"}, catalog, provider="antigravity", session_id=SESSION, now=101)
    with pytest.raises(ValueError):
        selected_settings({**chosen, "collaboration_mode": "plan"}, catalog, provider="antigravity", session_id=SESSION, now=101)


def test_permission_and_elicitation_wire_contracts():
    perm_msg = {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "session/request_permission",
        "params": {
            "sessionId": SESSION,
            "toolCall": {
                "sessionUpdate": "tool_call",
                "toolCallId": "call_1",
                "name": "run_command",
                "title": "echo synthetic",
                "rawInput": {"CommandLine": "echo synthetic", "WaitMsBeforeAsync": 5000},
            },
            "options": [
                {"optionId": "always", "name": "Always Allow", "kind": "allow_always"},
                {"optionId": "once-ok", "name": "Allow", "kind": "allow_once"},
                {"optionId": "once-no", "name": "Deny", "kind": "reject_once"},
            ],
        },
    }
    req = normalize_request("antigravity", perm_msg, command_id="cmd-1",
                            source_id="antigravity:" + SESSION, session_id=SESSION, turn_id="cmd-1", now=100)
    assert req.public["kind"] == "approval"
    assert "echo synthetic" in req.public["operation"]
    allow_wire = req.response({"decision": "allow_once"})
    assert allow_wire == {"jsonrpc": "2.0", "id": "req-1",
                          "result": {"outcome": {"outcome": "selected", "optionId": "once-ok"}}}
    deny_wire = req.response({"decision": "deny"})
    assert deny_wire == {"jsonrpc": "2.0", "id": "req-1",
                         "result": {"outcome": {"outcome": "selected", "optionId": "once-no"}}}
    assert "always" not in json.dumps(allow_wire)

    # Missing allow_once or foreign session must fail closed.
    bad_perm = json.loads(json.dumps(perm_msg))
    bad_perm["params"]["options"] = [{"optionId": "always", "name": "Always", "kind": "allow_always"}]
    with pytest.raises(UnsupportedControl):
        normalize_request("antigravity", bad_perm, command_id="cmd-1",
                          source_id="antigravity:" + SESSION, session_id=SESSION, turn_id="cmd-1", now=100)

    elicit_msg = {
        "jsonrpc": "2.0",
        "id": 7,
        "method": "elicitation/create",
        "params": {
            "sessionId": SESSION,
            "toolCallId": "call_q1",
            "mode": "form",
            "message": "The agent has 2 questions.",
            "requestedSchema": {
                "type": "object",
                "properties": {
                    "q1": {"type": "string", "title": "Env",
                           "oneOf": [{"const": "1", "title": "Staging"}, {"const": "2", "title": "Prod"}]},
                    "q1_other": {"type": "string", "title": "Other"},
                    "q2": {"type": "array", "title": "Flags",
                           "items": {"anyOf": [{"const": "1", "title": "A"}, {"const": "2", "title": "B"}]}},
                },
            },
        },
    }
    qreq = normalize_request("antigravity", elicit_msg, command_id="cmd-1",
                             source_id="antigravity:" + SESSION, session_id=SESSION, turn_id="cmd-1", now=100)
    assert qreq.public["kind"] == "question" and len(qreq.questions) == 2
    assert qreq.questions[0].allows_text is True and qreq.questions[1].multiple is True

    ans_wire = qreq.response({"decision": "answer", "answers": [
        {"question_id": "q1", "choices": ["o1"]},
        {"question_id": "q2", "choices": ["o0", "o1"]},
    ]})
    assert ans_wire == {"jsonrpc": "2.0", "id": 7,
                        "result": {"action": "accept", "content": {"q1": "2", "q2": ["1", "2"]}}}

    free_wire = qreq.response({"decision": "answer", "answers": [
        {"question_id": "q1", "choices": [], "text": "CustomEnv"},
        {"question_id": "q2", "choices": ["o0"]},
    ]})
    assert free_wire == {"jsonrpc": "2.0", "id": 7,
                         "result": {"action": "accept", "content": {"q1_other": "CustomEnv", "q2": ["1"]}}}

    assert qreq.response({"decision": "deny"}) == {"jsonrpc": "2.0", "id": 7, "result": {"action": "decline"}}

    # URL mode elicitation (OAuth/external link) must stay desktop-only.
    url_elicit = json.loads(json.dumps(elicit_msg))
    url_elicit["params"]["mode"] = "url"
    with pytest.raises(UnsupportedControl):
        normalize_request("antigravity", url_elicit, command_id="cmd-1",
                          source_id="antigravity:" + SESSION, session_id=SESSION, turn_id="cmd-1", now=100)


def test_untitled_enum_multiselect_returns_exact_values_and_ignores_defaults():
    fields = {"color": {"type": "string", "enum": ["green", "blue"], "enumNames": ["Green", "Blue"]},
              "tags": {"type": "array", "items": {"type": "string", "enum": ["frontend", "qa", "docs"]},
                       "default": ["qa"]}}
    message = {"id": "form1", "method": "elicitation/create", "params": {
        "sessionId": SESSION, "mode": "form", "requestedSchema": {"type": "object", "properties": fields}}}

    def parse():
        return normalize_request("antigravity", message, command_id="cmd1", source_id="antigravity:" + SESSION,
                                 session_id=SESSION, turn_id="cmd1")

    request = parse()
    color, tags = request.questions
    assert color.text == "color" and [choice.label for choice in color.options] == ["Green", "Blue"]
    assert tags.multiple and [choice.label for choice in tags.options] == ["frontend", "qa", "docs"]
    wire = request.response({"decision": "answer", "answers": [
        {"question_id": "color", "choices": ["o1"]}, {"question_id": "tags", "choices": ["o2", "o0"]}]})
    assert wire["result"] == {"action": "accept", "content": {"color": "blue", "tags": ["docs", "frontend"]}}
    fields["color"]["enumNames"] = ["one label missing"]
    with pytest.raises(UnsupportedControl):
        parse()
    del fields["color"]["enumNames"]
    fields["color"]["oneOf"] = [{"const": "green", "title": "Green"}]
    with pytest.raises(UnsupportedControl):
        parse()


class FakePipe:
    def __init__(self, on_line=None):
        self.q: queue.Queue = queue.Queue()
        self.on_line = on_line
        self.buf = bytearray()
        self.closed = False

    def write(self, data):
        chunk = bytes(data)
        self.buf.extend(chunk)
        while b"\n" in self.buf:
            line, _, rest = self.buf.partition(b"\n")
            self.buf[:] = rest
            if self.on_line:
                self.on_line(json.loads(line))
        return len(chunk)

    def flush(self):
        pass

    def readline(self, limit=-1):
        item = self.q.get()
        return b"" if item is None else (json.dumps(item) + "\n").encode()

    def close(self):
        self.closed = True
        self.q.put(None)


class FakeAcpProcess:
    def __init__(self, argv, cwd, *, mode="default", scenario="perm_allow"):
        self.argv, self.cwd = argv, cwd
        self.mode, self.scenario = mode, scenario
        self.sent = []
        self.closed = False
        stdin = FakePipe(self._on_client_msg)
        stdout = FakePipe()
        stderr = FakePipe()
        stderr.q.put(None)
        self.stdout = stdout
        self.proc = type("P", (), {"stdin": stdin, "stdout": stdout, "stderr": stderr, "poll": lambda self: 0})()

    def _on_client_msg(self, msg):
        self.sent.append(msg)
        method, rid = msg.get("method"), msg.get("id")
        if method == "initialize":
            if self.scenario == "auth_error":
                self.stdout.q.put({"jsonrpc": "2.0", "id": rid,
                                   "error": {"code": -32000, "message": "Authentication required: PRIVATE TOKEN"}})
                return
            self.stdout.q.put({"jsonrpc": "2.0", "id": rid, "result": {"protocolVersion": 1}})
        elif method in {"session/resume", "session/new"}:
            payload = json.loads(json.dumps(ACP_RESUME_SAMPLE))
            if method == "session/new":
                payload["sessionId"] = "99999999-9999-4999-8999-999999999999"
            payload["modes"]["currentModeId"] = self.mode
            self.stdout.q.put({"jsonrpc": "2.0", "id": rid, "result": payload})
        elif method == "session/set_config_option":
            params = msg["params"]
            opts = json.loads(json.dumps(ACP_RESUME_SAMPLE["configOptions"]))
            for o in opts:
                if o["id"] == params["configId"]:
                    o["currentValue"] = params["value"]
            self.stdout.q.put({"jsonrpc": "2.0", "id": rid, "result": {"configOptions": opts}})
        elif method == "session/prompt":
            self._prompt_id = rid
            if self.scenario == "plain":
                self.stdout.q.put({"jsonrpc": "2.0", "method": "session/update",
                                   "params": {"sessionId": SESSION, "update": {"sessionUpdate": "agent_message_chunk"}}})
                self.stdout.q.put({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "end_turn"}})
            elif self.scenario in {"perm_allow", "perm_deny_cancel"}:
                self.stdout.q.put({
                    "jsonrpc": "2.0", "id": "srv-perm-1", "method": "session/request_permission",
                    "params": {
                        "sessionId": SESSION,
                        "toolCall": {"toolCallId": "tc1", "name": "run_command", "title": "echo ok",
                                     "rawInput": {"CommandLine": "echo ok"}},
                        "options": [
                            {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
                            {"optionId": "deny", "name": "Deny", "kind": "reject_once"},
                        ],
                    },
                })
        elif method == "session/close":
            self.stdout.q.put({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif msg.get("id") == "srv-perm-1":
            selected = ((msg.get("result") or {}).get("outcome") or {}).get("optionId")
            stop = "cancelled" if (self.scenario == "perm_deny_cancel" and selected == "deny") else "end_turn"
            self.stdout.q.put({"jsonrpc": "2.0", "id": self._prompt_id, "result": {"stopReason": stop}})

    def close(self, *, graceful=False):
        self.closed = True
        self.stdout.close()


def test_executor_catalog_lifecycle_locks_and_mode_guards(tmp_path):
    ag, ws, conv_db = make_antigravity_fixture(tmp_path)
    source_id = "antigravity:" + SESSION
    resolver = lambda sid: resolve_local_session(sid, antigravity_home=ag)
    finder = lambda tool: "agy.exe" if tool == "antigravity" else None

    # 1. Catalog discovery uses isolated session/new + session/close without opening the live desktop session.
    spawned = []
    def factory(argv, cwd, *, mode="default", scenario="perm_allow"):
        p = FakeAcpProcess(argv, cwd, mode=mode, scenario=scenario)
        spawned.append(p)
        return p

    cat = le.discover_reply_catalog(source_id, resolver=resolver, executable_finder=finder,
                                    process_factory=lambda a, c: factory(a, c, scenario="plain"))
    assert cat is not None and cat["provider"] == "antigravity"
    assert cat["current_model"] == "gemini-3.8-flash" and cat["current_effort"] == "low"
    assert "--dangerously-skip-permissions" not in spawned[0].argv
    assert "--app-data-dir" not in spawned[0].argv and "antigravity-cli" in spawned[0].argv
    assert [m.get("method") for m in spawned[0].sent if "method" in m] == ["initialize", "session/new", "session/close"]

    # 2. Controlled turn with model+effort selection and allow_once permission!
    events, seen_requests = [], []
    def handler(req, cancelled):
        seen_requests.append(req)
        return {"decision": "allow_once"}

    selection = {"provider": "antigravity", "catalog_revision": cat["catalog_revision"],
                 "model": "gemini-4-carbon", "effort": "high", "apply_at": "next_message"}
    ex = le.LocalReplyExecutor(events.append, resolver=resolver, executable_finder=finder,
                               process_factory=lambda a, c: factory(a, c, scenario="perm_allow"),
                               interaction_handler=handler, catalog_provider=lambda _: cat)
    started = ex.start("cmd-allow-1", source_id, "Run check", selection=selection)
    assert started["state"] == "running"
    ex.runs["cmd-allow-1"].thread.join(timeout=5)
    assert events[-1]["state"] == "succeeded"
    assert len(seen_requests) == 1 and seen_requests[0]["provider"] == "antigravity"
    methods = [m.get("method") for m in spawned[-1].sent if "method" in m]
    assert methods[:4] == ["initialize", "session/resume", "session/set_config_option", "session/set_config_option"]

    # 3. Auto mode is rejected fail-closed before sending user prompt!
    events.clear()
    ex_auto = le.LocalReplyExecutor(events.append, resolver=resolver, executable_finder=finder,
                                    process_factory=lambda a, c: factory(a, c, mode="auto", scenario="plain"),
                                    interaction_handler=handler)
    ex_auto.start("cmd-auto-1", source_id, "Do not run")
    ex_auto.runs["cmd-auto-1"].thread.join(timeout=5)
    assert events[-1]["state"] == "blocked" and events[-1]["error_code"] == "permissions"
    assert not any(m.get("method") == "session/prompt" for m in spawned[-1].sent)

    # 4. ACP -32000 maps to authentication_failed without leaking private message text!
    events.clear()
    ex_auth = le.LocalReplyExecutor(events.append, resolver=resolver, executable_finder=finder,
                                    process_factory=lambda a, c: factory(a, c, scenario="auth_error"),
                                    interaction_handler=handler)
    ex_auth.start("cmd-auth-1", source_id, "Check auth")
    ex_auth.runs["cmd-auth-1"].thread.join(timeout=5)
    assert events[-1]["state"] == "failed" and events[-1]["error_code"] == "authentication_failed"
    assert "PRIVATE TOKEN" not in json.dumps(events[-1])
    assert failure_code_for_antigravity({"code": -32000}) == "authentication_failed"

    # 5. Inert leftover -wal does not block an idle session, while an active open handle or rollback journal blocks as busy!
    wal = conv_db.with_name(conv_db.name + "-wal")
    wal.write_bytes(b"inert-leftover")
    try:
        assert le._antigravity_locked(ag, SESSION, conv_db) is False
        with conv_db.open("r+b") as active_handle:
            if le.os.name != "nt":
                import fcntl
                fcntl.flock(active_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            res_busy = ex.start("cmd-busy-open", source_id, "Blocked while active")
            assert res_busy["state"] == "blocked" and res_busy["error_code"] == "busy"
    finally:
        wal.unlink()
    journal = conv_db.with_name(conv_db.name + "-journal")
    journal.write_bytes(b"active-rollback")
    try:
        res_journal = ex.start("cmd-busy-journal", source_id, "Blocked during rollback")
        assert res_journal["state"] == "blocked" and res_journal["error_code"] == "busy"
    finally:
        journal.unlink()

    # 5b. Selection without catalog_provider validates against session/resume in a single ACP spawn.
    events.clear()
    before_spawns = len(spawned)
    ex_nocat = le.LocalReplyExecutor(events.append, resolver=resolver, executable_finder=finder,
                                     process_factory=lambda a, c: factory(a, c, scenario="plain"),
                                     interaction_handler=handler)
    ex_nocat.start("cmd-nocat-sel", source_id, "Single spawn", selection=selection)
    ex_nocat.runs["cmd-nocat-sel"].thread.join(timeout=5)
    assert events[-1]["state"] == "succeeded"
    assert len(spawned) - before_spawns == 1

    # 5c. Phone denial returning end_turn maps to declined when transcript halted on permission ERROR,
    #     or succeeded when the model wrote a post-denial PLANNER_RESPONSE text reply.
    transcript_path = ag / "brain" / SESSION / ".system_generated" / "logs" / "transcript.jsonl"
    orig_transcript = transcript_path.read_text(encoding="utf-8")
    def deny_with_error(req, cancelled):
        transcript_path.write_text(
            orig_transcript + json.dumps({"step_index": 9, "source": "MODEL", "type": "GENERIC", "status": "ERROR", "content": "denied"}) + "\n",
            encoding="utf-8",
        )
        return {"decision": "deny"}

    events.clear()
    ex_deny = le.LocalReplyExecutor(
        events.append, resolver=resolver, executable_finder=finder,
        process_factory=lambda a, c: factory(a, c, scenario="perm_allow"),
        interaction_handler=deny_with_error,
    )
    ex_deny.start("cmd-deny-endturn", source_id, "Deny tool")
    ex_deny.runs["cmd-deny-endturn"].thread.join(timeout=5)
    assert events[-1]["state"] == "failed" and events[-1]["error_code"] == "declined"
    transcript_path.write_text(orig_transcript, encoding="utf-8")

    events.clear()
    ex_deny_reply = le.LocalReplyExecutor(
        events.append, resolver=resolver, executable_finder=finder,
        process_factory=lambda a, c: factory(a, c, scenario="perm_allow"),
        interaction_handler=lambda req, cancelled: {"decision": "deny"},
    )
    ex_deny_reply.start("cmd-deny-with-text", source_id, "Deny tool with answer")
    ex_deny_reply.runs["cmd-deny-with-text"].thread.join(timeout=5)
    assert events[-1]["state"] == "succeeded"

    # 5d. Expired interaction (handler returns None) sends session/cancel + session/close so index returns to IDLE.
    events.clear()
    ex_expired = le.LocalReplyExecutor(
        events.append, resolver=resolver, executable_finder=finder,
        process_factory=lambda a, c: factory(a, c, scenario="perm_allow"),
        interaction_handler=lambda req, cancelled: None,
    )
    ex_expired.start("cmd-expired-perm", source_id, "Expired approval")
    ex_expired.runs["cmd-expired-perm"].thread.join(timeout=5)
    assert events[-1]["state"] == "blocked" and events[-1]["error_code"] == "approval"
    abort_methods = [m.get("method") for m in spawned[-1].sent if "method" in m]
    assert "session/cancel" in abort_methods and abort_methods[-1] == "session/close"

    # 6. AgentPoll accepts all 3 supported providers simultaneously.
    poll = AgentPoll.model_validate({"supported_tools": ["codex", "claude", "antigravity"]})
    assert len(poll.supported_tools) == 3
