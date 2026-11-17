"""Strict adapters for requests received on a Monitor-owned provider pipe.

This module never discovers approvals from transcripts or grants session-wide
permissions. Unsupported requests remain desktop-only. All provider payloads
stay in memory; only bounded, explicit prompts are sent to the paired client.
"""
from __future__ import annotations

from dataclasses import dataclass
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

    def response(self, decision):
        if not isinstance(decision, dict):
            raise UnsupportedControl("missing explicit decision")
        action = decision.get("decision")
        if action == "deny":
            result = ({"decision": self.codex_denial} if self.public["kind"] == "approval" else {"answers": {}})
            if self.provider == "claude":
                result = {"behavior": "deny", "message": "User declined this request", "interrupt": True}
        elif action == "allow_once" and self.public["kind"] == "approval":
            result = {"decision": "accept"} if self.provider == "codex" else {
                "behavior": "allow", "updatedInput": self.original["input"]}
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
            indexed = {q.id: q for q in self.questions}
            mapped = {}
            for answer in answers:
                question = indexed[answer.question_id]
                labels = {o.id: o.label for o in question.options}
                values = [answer.text] if answer.text is not None else [labels[c] for c in answer.choices]
                if self.provider == "codex": mapped[question.id] = {"answers": values}
                else: mapped[question.text] = values if question.multiple else values[0]
            result = {"answers": mapped} if self.provider == "codex" else {
                "behavior": "allow", "updatedInput": {**self.original["input"], "answers": mapped}}
        else:
            raise UnsupportedControl("unsupported decision")
        if self.provider == "codex": return {"id": self.wire_id, "result": result}
        return {"type": "control_response", "response": {
            "subtype": "success", "request_id": self.wire_id, "response": result}}


def _questions(rows, provider):
    if not isinstance(rows, list) or not 1 <= len(rows) <= (3 if provider == "codex" else 4):
        raise UnsupportedControl("unsupported questions")
    result = []
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
            if not isinstance(option, dict) or set(option) - {"label", "description"}:
                raise UnsupportedControl("unsupported option")
            choices.append(Choice(id="o" + str(j), label=_text(option.get("label"), 240),
                                  description=_text(option.get("description"), 1000, optional=True)))
        if len({c.label for c in choices}) != len(choices): raise UnsupportedControl("ambiguous labels")
        result.append(Question(id=qid, text=_text(row.get("question"), 2000), options=tuple(choices),
                               multiple=row.get("multiSelect", False),
                               allows_text=row.get("isOther", False) if provider == "codex" else True))
    if len({q.id for q in result}) != len(result) or len({q.text for q in result}) != len(result):
        raise UnsupportedControl("ambiguous questions")
    return tuple(result)


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


def normalize_request(provider, message, *, command_id, source_id, session_id, turn_id, now=None,
                      file_preview=None, codex_local_command=False):
    """Bind a supported wire request to one known, already-started turn."""
    now = time.time() if now is None else now
    if not turn_id or source_id != provider + ":" + session_id:
        raise UnsupportedControl("invalid session binding")
    questions = ()
    codex_denial = "decline"
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
            if set(original["input"]) - {"questions"}: raise UnsupportedControl("unsupported question input")
            questions = _questions(original["input"].get("questions"), provider)
            kind, title, detail, operation = "question", "Claude 需要你的选择", "", ""
        else:
            # Never accept permission updates or change the proposed operation.
            kind, title = "approval", "Claude 请求单次批准"
            detail = name
            operation = _text(json.dumps(original["input"], ensure_ascii=False, sort_keys=True), 8000)
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
    return ProviderRequest(public, provider, wire_id, original, questions, codex_denial)
