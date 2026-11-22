"""Strict adapters for requests received on a Monitor-owned provider pipe.

This module never discovers approvals from transcripts or grants session-wide
permissions. Unsupported requests remain desktop-only. All provider payloads
stay in memory; only bounded, explicit prompts are sent to the paired client.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
import time
import uuid

from .session_controls import Answer, Choice, Question, validate_answers


class UnsupportedControl(ValueError):
    pass


def _text(value, limit, *, optional=False):
    if optional and value is None:
        return ""
    if not isinstance(value, str) or len(value) > limit or (not optional and not value.strip()):
        raise UnsupportedControl("unsupported text")
    if any(ord(c) < 32 and c not in "\n\t" for c in value) or any(c in value for c in "\x7f\u202d\u202e"):
        raise UnsupportedControl("unsupported text")
    return value


def _digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode()).hexdigest()


@dataclass
class ProviderRequest:
    public: dict
    provider: str
    wire_id: str | int
    original: dict
    questions: tuple[Question, ...] = ()
    codex_denial: str = "decline"
    antigravity_allow_id: str = ""
    antigravity_deny_id: str = ""
    form_choice_values: dict[tuple[str, str], str] = field(default_factory=dict)

    def response(self, decision):
        if not isinstance(decision, dict):
            raise UnsupportedControl("missing explicit decision")
        action = decision.get("decision")
        if action == "deny":
            if self.provider == "codex" and self.form_choice_values:
                result = {"action": "decline", "content": None, "_meta": None}
            elif self.provider == "antigravity":
                result = ({"outcome": {"outcome": "selected", "optionId": self.antigravity_deny_id}}
                          if self.public["kind"] == "approval" else {"action": "decline"})
            elif self.provider == "claude":
                result = {"behavior": "deny", "message": "User declined this request", "interrupt": True}
            else:
                result = ({"decision": self.codex_denial} if self.public["kind"] == "approval" else {"answers": {}})
        elif action == "allow_once" and self.public["kind"] == "approval":
            if self.provider == "antigravity":
                result = {"outcome": {"outcome": "selected", "optionId": self.antigravity_allow_id}}
            elif self.provider == "codex":
                result = {"decision": "accept"}
            else:
                result = {"behavior": "allow", "updatedInput": self.original["input"]}
        elif action == "answer" and self.public["kind"] == "question":
            try:
                raw = decision.get("answers")
                if not isinstance(raw, list): raise ValueError()
                answers = tuple(Answer.model_validate({**a, "choices": tuple(a.get("choices", []))}) for a in raw)
                validate_answers(self.questions, answers)
                # A free-text answer is an alternative, not an extra hidden choice.
                if any(a.text is not None and a.choices for a in answers): raise ValueError()
            except (ValueError, TypeError, AttributeError) as exc:
                raise UnsupportedControl("invalid answers") from exc
            if self.provider == "claude":
                result = {"behavior": "allow", "updatedInput": claude_answered_input(self.original["input"], self.questions, answers)}
                return {"type": "control_response", "response": {
                    "subtype": "success", "request_id": self.wire_id, "response": result}}
            indexed = {q.id: q for q in self.questions}
            mapped = {}
            for answer in answers:
                question = indexed[answer.question_id]
                if self.form_choice_values:
                    if answer.text is not None:
                        mapped[f"{question.id}_other"] = answer.text
                    else:
                        consts = [self.form_choice_values[(question.id, c)] for c in answer.choices]
                        mapped[question.id] = consts if question.multiple else consts[0]
                    continue
                labels = {o.id: o.label for o in question.options}
                values = [answer.text] if answer.text is not None else [labels[c] for c in answer.choices]
                mapped[question.id] = {"answers": values}
            if self.form_choice_values:
                result = {"action": "accept", "content": mapped}
                if self.provider == "codex":
                    result["_meta"] = None
            else:
                result = {"answers": mapped}
        else:
            raise UnsupportedControl("unsupported decision")
        if self.provider == "antigravity": return {"jsonrpc": "2.0", "id": self.wire_id, "result": result}
        if self.provider == "codex": return {"id": self.wire_id, "result": result}
        return {"type": "control_response", "response": {
            "subtype": "success", "request_id": self.wire_id, "response": result}}


def _questions(rows, provider):
    if not isinstance(rows, list) or not 1 <= len(rows) <= (3 if provider == "codex" else 4):
        raise UnsupportedControl("unsupported questions")
    result = []
    option_fields = {"label", "description", "preview"} if provider == "claude" else {"label", "description"}
    for i, row in enumerate(rows):
        if not isinstance(row, dict): raise UnsupportedControl("unsupported question")
        allowed = ({"id", "header", "question", "isOther", "isSecret", "options"} if provider == "codex"
                   else {"header", "question", "options", "multiSelect"})
        if set(row) - allowed or row.get("isSecret", False):
            raise UnsupportedControl("private or unsupported question")
        for key in ("isOther", "isSecret", "multiSelect"):
            if key in row and type(row[key]) is not bool: raise UnsupportedControl("unsupported question flags")
        qid = _text(row.get("id"), 80) if provider == "codex" else "q" + str(i)
        options = row.get("options") or []
        if not isinstance(options, list) or len(options) > 32: raise UnsupportedControl("unsupported options")
        choices = []
        for j, option in enumerate(options):
            if not isinstance(option, dict) or set(option) - option_fields:
                raise UnsupportedControl("unsupported option")
            if "preview" in option and not isinstance(option["preview"], str):
                raise UnsupportedControl("unsupported option preview")
            choices.append(Choice(id="o" + str(j), label=_text(option.get("label"), 240),
                                  description=_text(option.get("description"), 1000, optional=True)))
        if len({c.label for c in choices}) != len(choices): raise UnsupportedControl("ambiguous labels")
        result.append(Question(id=qid, text=_text(row.get("question"), 2000), options=tuple(choices),
                               multiple=row.get("multiSelect", False),
                               allows_text=row.get("isOther", False) if provider == "codex" else True))
    if len({q.id for q in result}) != len(result) or len({q.text for q in result}) != len(result):
        raise UnsupportedControl("ambiguous questions")
    return tuple(result)


def claude_question(data):
    if not isinstance(data, dict) or set(data) - {"questions", "metadata", "answers", "annotations"}:
        raise UnsupportedControl("unsupported question input")
    if any(key in data and data[key] != {} for key in ("answers", "annotations")):
        raise UnsupportedControl("question already contains answers or annotations")
    metadata = data.get("metadata", {})
    if not isinstance(metadata, dict) or set(metadata) - {"source"}:
        raise UnsupportedControl("unsupported question metadata")
    if "source" in metadata:
        _text(metadata["source"], 1000, optional=True)
    questions = _questions(data.get("questions"), "claude")
    has_preview = any(option.get("preview") for row in data["questions"] for option in row.get("options") or [])
    return questions, "部分选项附有电脑端预览" if has_preview else ""


def claude_answered_input(data, questions, answers):
    validate_answers(questions, answers)
    indexed = {question.id: question for question in questions}
    mapped = {}
    for answer in answers:
        question = indexed[answer.question_id]
        labels = {choice.id: choice.label for choice in question.options}
        mapped[question.text] = answer.text if answer.text is not None else ", ".join(labels[c] for c in answer.choices)
    return {**data, "answers": mapped}


def _codex_approval_denial(choices, proposal):
    """Select only native once/deny decisions; never return an amendment."""
    if choices is None:
        if proposal is not None:
            raise UnsupportedControl("missing explicit once-only decisions")
        return "decline"
    if not isinstance(choices, list) or len(choices) > 5:
        raise UnsupportedControl("unsupported approval decisions")
    offered = set()
    for choice in choices:
        if isinstance(choice, str) and choice in {"accept", "decline", "cancel", "acceptForSession"}:
            name = choice
        elif (proposal is not None
              and choice == {"acceptWithExecpolicyAmendment": {"execpolicy_amendment": proposal}}):
            name = "acceptWithExecpolicyAmendment"
        else:
            raise UnsupportedControl("unsupported approval decisions")
        if name in offered:
            raise UnsupportedControl("duplicate approval decision")
        offered.add(name)
    if "accept" not in offered or not offered.intersection({"decline", "cancel"}):
        raise UnsupportedControl("once-only decision unavailable")
    return "decline" if "decline" in offered else "cancel"


def _antigravity_permission_options(options):
    """Select only explicit single-turn allow/reject options; never persistent grants."""
    if not isinstance(options, list) or not 1 <= len(options) <= 16:
        raise UnsupportedControl("unsupported approval options")
    seen_ids = set()
    by_kind = {}
    for option in options:
        if not isinstance(option, dict) or set(option) - {"optionId", "name", "kind", "_meta"}:
            raise UnsupportedControl("unsupported approval option")
        option_id = _text(option.get("optionId"), 120)
        if any(ch in option_id for ch in "\r\n\t") or option_id in seen_ids:
            raise UnsupportedControl("ambiguous approval option")
        seen_ids.add(option_id)
        _text(option.get("name"), 120)
        kind = option.get("kind")
        if kind not in {"allow_once", "reject_once", "allow_always", "reject_always"}:
            raise UnsupportedControl("unsupported approval kind")
        if kind in {"allow_once", "reject_once"} and kind in by_kind:
            raise UnsupportedControl("duplicate once-only option")
        by_kind.setdefault(kind, option_id)
    if "allow_once" not in by_kind or "reject_once" not in by_kind:
        raise UnsupportedControl("once-only decision unavailable")
    return by_kind["allow_once"], by_kind["reject_once"]


def _form_questions(schema, *, antigravity=False):
    """Translate enumerated form fields, preserving native property and option values."""
    if not isinstance(schema, dict) or schema.get("type") != "object" or set(schema) - {
            "$schema", "type", "properties", "required", "additionalProperties"}:
        raise UnsupportedControl("unsupported elicitation schema")
    if "$schema" in schema:
        _text(schema["$schema"], 300)
    props = schema.get("properties")
    if not isinstance(props, dict) or not props:
        raise UnsupportedControl("unsupported elicitation properties")
    if not all(isinstance(k, str) for k in props):
        raise UnsupportedControl("unsupported elicitation keys")
    required = schema.get("required", [])
    if not isinstance(required, list) or any(not isinstance(k, str) or k not in props for k in required):
        raise UnsupportedControl("invalid required fields")
    other = {k for k in props if antigravity and k.endswith("_other")}
    primary = [k for k in props if k not in other]
    if not 1 <= len(primary) <= 16:
        raise UnsupportedControl("unsupported elicitation questions")
    primary_set = set(primary)
    for key in other:
        base = key[:-6]
        spec = props[key]
        if (key in required or base not in primary_set or not isinstance(spec, dict) or spec.get("type") != "string"
                or set(spec) - {"type", "title", "description"}):
            raise UnsupportedControl("unsupported elicitation free-text field")
    questions = []
    choice_values = {}
    for qid in primary:
        _text(qid, 64)
        if any(ch.isspace() for ch in qid):
            raise UnsupportedControl("invalid question id")
        spec = props[qid]
        if not isinstance(spec, dict) or set(spec) - {
                "type", "title", "description", "oneOf", "enum", "enumNames", "items", "default"}:
            raise UnsupportedControl("unsupported question schema")
        qtext = _text(spec.get("title") or spec.get("description") or qid, 2000)
        if spec.get("type") == "string" and "items" not in spec:
            multiple = False
            option_schema = spec
        elif (spec.get("type") == "array" and not {"oneOf", "enum", "enumNames"}.intersection(spec)
              and isinstance(spec.get("items"), dict) and spec["items"].get("type", "string") == "string"
              and set(spec["items"]) <= {"type", "anyOf", "oneOf", "enum"}):
            multiple = True
            option_schema = spec["items"]
        else:
            raise UnsupportedControl("unsupported question type")
        encodings = {"oneOf", "anyOf", "enum"}.intersection(option_schema)
        if len(encodings) != 1 or ("enumNames" in option_schema and "enum" not in option_schema):
            raise UnsupportedControl("ambiguous option schema")
        raw_options = option_schema[encodings.pop()]
        if not isinstance(raw_options, list) or not 1 <= len(raw_options) <= 32:
            raise UnsupportedControl("unsupported options")
        if "enum" in option_schema:
            labels = option_schema.get("enumNames", raw_options)
            if not isinstance(labels, list) or len(labels) != len(raw_options):
                raise UnsupportedControl("invalid option labels")
            raw_options = [{"const": value, "title": label} for value, label in zip(raw_options, labels)]
        choices = []
        seen_consts = set()
        for idx, opt in enumerate(raw_options):
            if not isinstance(opt, dict) or set(opt) - {"const", "title", "description"}:
                raise UnsupportedControl("unsupported option")
            const_val = _text(opt.get("const"), 80)
            if any(ch in const_val for ch in "\r\n\t") or const_val in seen_consts:
                raise UnsupportedControl("ambiguous option value")
            seen_consts.add(const_val)
            cid = f"o{idx}"
            choices.append(Choice(id=cid, label=_text(opt.get("title"), 240),
                                  description=_text(opt.get("description"), 1000, optional=True)))
            choice_values[(qid, cid)] = const_val
        if len({c.label for c in choices}) != len(choices):
            raise UnsupportedControl("ambiguous labels")
        questions.append(Question(id=qid, text=qtext, options=tuple(choices),
                                  multiple=multiple, allows_text=(f"{qid}_other" in other and qid not in required)))
    if len({q.id for q in questions}) != len(questions) or len({q.text for q in questions}) != len(questions):
        raise UnsupportedControl("ambiguous questions")
    return tuple(questions), choice_values


def normalize_request(provider, message, *, command_id, source_id, session_id, turn_id, now=None,
                      file_preview=None, codex_local_command=False):
    """Bind a supported wire request to one known, already-started turn."""
    now = time.time() if now is None else now
    if not turn_id or source_id != provider + ":" + session_id:
        raise UnsupportedControl("invalid session binding")
    questions = ()
    codex_denial = "decline"
    antigravity_allow_id = antigravity_deny_id = ""
    form_choice_values = {}
    if provider == "codex":
        params, wire_id, method = message.get("params"), message.get("id"), message.get("method")
        if not isinstance(params, dict) or type(wire_id) not in {str, int} or isinstance(wire_id, bool):
            raise UnsupportedControl("unsupported request")
        if params.get("threadId") != session_id or params.get("turnId") != turn_id:
            raise UnsupportedControl("request belongs to another turn")
        if method in {"item/tool/requestUserInput", "tool/requestUserInput"}:
            if set(params) - {"threadId", "turnId", "itemId", "questions", "autoResolutionMs", "isBlocking"}:
                raise UnsupportedControl("unsupported question fields")
            if "isBlocking" in params and type(params["isBlocking"]) is not bool:
                raise UnsupportedControl("unsupported blocking flag")
            questions = _questions(params.get("questions"), provider)
            kind, title, detail, operation = "question", "Codex 需要你的选择", "", ""
        elif method == "mcpServer/elicitation/request":
            if (params.get("mode") != "form" or set(params) - {
                    "threadId", "turnId", "serverName", "environmentId", "mode", "_meta", "message", "requestedSchema"}):
                raise UnsupportedControl("unsupported MCP elicitation")
            server = _text(params.get("serverName"), 240)
            questions, form_choice_values = _form_questions(params.get("requestedSchema"))
            kind, title, operation = "question", "Codex 工具需要你的选择", ""
            detail = _text(server + "\n" + _text(params.get("message"), 4000), 4000)
        elif method in {"item/commandExecution/requestApproval", "item/fileChange/requestApproval"}:
            known = {"threadId", "turnId", "itemId", "reason", "command", "cwd", "commandActions",
                     "proposedExecpolicyAmendment", "networkApprovalContext", "availableDecisions", "additionalPermissions", "grantRoot",
                     "kind", "startedAtMs", "approvalId", "environmentId", "proposedNetworkPolicyAmendments"}
            if set(params) - known or any(params.get(k) is not None for k in
                    ("additionalPermissions", "networkApprovalContext", "grantRoot", "proposedNetworkPolicyAmendments")):
                raise UnsupportedControl("additional permissions require desktop")
            command_approval = method == "item/commandExecution/requestApproval"
            environment = params.get("environmentId")
            if environment is not None and not (command_approval and codex_local_command and environment == "local"):
                raise UnsupportedControl("unverified execution environment")
            proposal = params.get("proposedExecpolicyAmendment")
            if proposal is not None:
                if not (command_approval and codex_local_command and environment == "local"
                        and isinstance(proposal, list) and 1 <= len(proposal) <= 32):
                    raise UnsupportedControl("unsupported execpolicy proposal")
                for part in proposal:
                    _text(part, 8000)
            if params.get("kind", "command") != "command": raise UnsupportedControl("terminal input requires desktop")
            if "startedAtMs" in params and (type(params["startedAtMs"]) is not int or params["startedAtMs"] < 0):
                raise UnsupportedControl("unsupported start timestamp")
            if params.get("approvalId") is not None:
                try:
                    if str(uuid.UUID(params["approvalId"])) != params["approvalId"].lower(): raise ValueError()
                except (TypeError, ValueError, AttributeError): raise UnsupportedControl("unsupported callback identity")
            codex_denial = _codex_approval_denial(params.get("availableDecisions"), proposal)
            kind, title = "approval", "Codex 请求单次批准"
            detail = _text(params.get("reason"), 4000, optional=True)
            if codex_denial == "cancel":
                detail = _text(("拒绝会停止本轮。" + ("\n" + detail if detail else "")), 4000)
            if command_approval:
                cwd = _text(params.get("cwd"), 2000)
                command = _text(params.get("command"), 8000)
                operation = _text("工作目录：" + cwd + "\n命令：\n" + command, 8000)
            else:
                operation = _text(file_preview, 8000)
        else:
            raise UnsupportedControl("unsupported method")
        original = {key: value for key, value in params.items()
                    if key not in {"proposedExecpolicyAmendment", "availableDecisions"}}
    elif provider == "claude":
        original, wire_id = message.get("request"), message.get("request_id")
        if not isinstance(original, dict) or not isinstance(wire_id, str) or not wire_id or len(wire_id) > 128:
            raise UnsupportedControl("unsupported request")
        if message.get("session_id", session_id) != session_id or original.get("subtype") != "can_use_tool":
            raise UnsupportedControl("unsupported request binding")
        known = {"subtype", "tool_name", "input", "permission_suggestions", "blocked_path", "decision_reason",
                 "tool_use_id", "agent_id", "title", "display_name", "description"}
        if set(original) - known or original.get("agent_id") or not isinstance(original.get("input"), dict):
            raise UnsupportedControl("unsupported tool scope")
        _text(original.get("tool_use_id"), 128)
        name = _text(original.get("tool_name"), 120)
        if name in {"ExitPlanMode", "EnterPlanMode"}:
            raise UnsupportedControl("permission mode changes require desktop")
        if name == "AskUserQuestion":
            questions, detail = claude_question(original["input"])
            kind, title, operation = "question", "Claude 需要你的选择", ""
        else:
            # Never accept permission updates or change the proposed operation.
            kind, title = "approval", "Claude 请求单次批准"
            detail = name
            operation = _text(json.dumps(original["input"], ensure_ascii=False, sort_keys=True), 8000)
    elif provider == "antigravity":
        params, wire_id, method = message.get("params"), message.get("id"), message.get("method")
        if message.get("jsonrpc") not in {None, "2.0"}:
            raise UnsupportedControl("unsupported jsonrpc version")
        if not isinstance(params, dict) or type(wire_id) not in {str, int} or isinstance(wire_id, bool):
            raise UnsupportedControl("unsupported request")
        if isinstance(wire_id, str) and (not wire_id or len(wire_id) > 128 or any(ch in wire_id for ch in "\r\n\t")):
            raise UnsupportedControl("unsupported request id")
        if params.get("sessionId") != session_id:
            raise UnsupportedControl("request belongs to another session")
        if method == "session/request_permission":
            if set(params) - {"sessionId", "toolCall", "options", "_meta"}:
                raise UnsupportedControl("unsupported permission fields")
            antigravity_allow_id, antigravity_deny_id = _antigravity_permission_options(params.get("options"))
            tool_call = params.get("toolCall")
            known_call = {"sessionUpdate", "toolCallId", "name", "args", "status", "kind",
                          "title", "locations", "content", "rawInput", "rawOutput", "_meta"}
            if not isinstance(tool_call, dict) or set(tool_call) - known_call:
                raise UnsupportedControl("unsupported tool call")
            _text(tool_call.get("toolCallId"), 128)
            name = _text(tool_call.get("name") or tool_call.get("title"), 120)
            if name in {"ExitPlanMode", "EnterPlanMode"}:
                raise UnsupportedControl("permission mode changes require desktop")
            raw_input = tool_call.get("rawInput") if tool_call.get("rawInput") is not None else tool_call.get("args")
            if raw_input is not None and not isinstance(raw_input, dict):
                raise UnsupportedControl("unsupported tool input")
            call_title = _text(tool_call.get("title"), 500, optional=True)
            kind, title = "approval", "Antigravity 请求单次批准"
            detail = _text(name + (f" · {call_title}" if call_title and call_title != name else ""), 4000)
            if (isinstance(raw_input, dict) and name == "run_command"
                    and set(raw_input) <= {"CommandLine", "WaitMsBeforeAsync", "toolSummary", "IsDaemon"}
                    and isinstance(raw_input.get("CommandLine"), str) and raw_input["CommandLine"].strip()):
                operation = _text("命令：\n" + raw_input["CommandLine"], 8000)
            elif isinstance(raw_input, dict) and raw_input:
                operation = _text(json.dumps(raw_input, ensure_ascii=False, sort_keys=True), 8000)
            else:
                operation = _text(call_title or name, 8000)
            original = {key: value for key, value in params.items() if key != "options"}
        elif method == "elicitation/create":
            if set(params) - {"sessionId", "toolCallId", "mode", "message", "requestedSchema", "_meta"}:
                raise UnsupportedControl("unsupported elicitation fields")
            if params.get("mode") != "form":
                raise UnsupportedControl("non-form elicitation requires desktop")
            if "toolCallId" in params and params["toolCallId"] is not None:
                _text(params.get("toolCallId"), 128)
            questions, form_choice_values = _form_questions(params.get("requestedSchema"), antigravity=True)
            kind, title = "question", "Antigravity 需要你的选择"
            detail = _text(params.get("message"), 4000, optional=True)
            operation = ""
            original = dict(params)
        else:
            raise UnsupportedControl("unsupported method")
    else:
        raise UnsupportedControl("unknown provider")
    identity = {"command": command_id, "session": source_id, "turn": turn_id, "wire": wire_id}
    public = {"id": _digest(identity), "revision": _digest(message), "provider": provider,
              "command_id": command_id, "source_id": source_id, "session_id": session_id, "turn_id": turn_id,
              "kind": kind, "title": title, "detail": detail, "operation": operation,
              "questions": [q.model_dump(mode="json") for q in questions], "expires_at": float(now + 300)}
    timeout = original.get("autoResolutionMs")
    if timeout is not None:
        if type(timeout) is not int or timeout <= 0: raise UnsupportedControl("unsupported timeout")
        public["expires_at"] = min(public["expires_at"], now + timeout / 1000)
    return ProviderRequest(public, provider, wire_id, original, questions, codex_denial,
                           antigravity_allow_id, antigravity_deny_id, form_choice_values)
