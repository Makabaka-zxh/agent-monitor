"""Pure contracts for phone controls; no transport or permission grant.

Catalogs must come from the authenticated owning connector and its live provider
capabilities, never from phone input. This module alone does not authorize a
request or change a session. The caller must authenticate and persist decisions
atomically before sending them to the original request channel.
"""
from __future__ import annotations

import hashlib
import json
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator, model_serializer, model_validator

Provider = Literal["codex", "claude"]
QuickServiceTier = Literal["priority"]
QUICK_SERVICE_TIER: QuickServiceTier = "priority"
CollaborationMode = Literal["plan"]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class ModelOption(Contract):
    # Preserve catalog-supplied context variants such as [1m] as literal argv
    # data. This is only syntax validation, never a guessed available model.
    id: str = Field(min_length=1, max_length=120, pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]*(?:\[[A-Za-z0-9._-]+\])?$")
    label: str = Field(min_length=1, max_length=120)
    efforts: tuple[str, ...] = Field(default=(), max_length=16)
    service_tiers: tuple[QuickServiceTier, ...] = Field(default=(), max_length=1)

    @model_serializer(mode="wrap")
    def serialize(self, handler):
        data = handler(self)
        if not self.service_tiers:
            data.pop("service_tiers", None)
        return data

    @field_validator("label")
    @classmethod
    def public_label(cls, value):
        if not value.strip() or any(ord(ch) < 32 or ch in "\x7f\u202d\u202e" for ch in value):
            raise ValueError("invalid display label")
        return value

    @field_validator("efforts")
    @classmethod
    def explicit_unique_efforts(cls, value):
        # Do not infer per-model support from a global list of familiar levels.
        if len(value) != len(set(value)) or any(not s or len(s) > 32 or not s.isascii()
                or not all(ch.islower() or ch.isdigit() or ch == "_" for ch in s) for s in value):
            raise ValueError("invalid effort options")
        return value


class ModelCatalog(Contract):
    provider: Provider
    device_id: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=1, max_length=200)
    revision: str = Field(min_length=1, max_length=128)
    valid_until: float = Field(allow_inf_nan=False, gt=0)
    models: tuple[ModelOption, ...] = Field(max_length=256)
    collaboration_modes: tuple[CollaborationMode, ...] = Field(default=(), max_length=1)

    @model_validator(mode="after")
    def unique_models(self):
        if len(self.models) != len({item.id for item in self.models}):
            raise ValueError("ambiguous model catalog")
        if self.provider != "codex" and any(model.service_tiers for model in self.models):
            raise ValueError("service tiers are not available for this provider")
        if self.provider != "codex" and self.collaboration_modes:
            raise ValueError("collaboration mode is not available for this provider")
        return self


class ModelSelection(Contract):
    """Apply from a future message, never a permission setting or auto-reset.

    The provider may retain it for later turns in the same session.
    """
    provider: Provider
    catalog_revision: str = Field(min_length=1, max_length=128)
    model: str = Field(min_length=1, max_length=120)
    effort: str | None = Field(default=None, min_length=1, max_length=32)
    apply_at: Literal["next_message"] = "next_message"
    service_tier: QuickServiceTier | None = None
    collaboration_mode: CollaborationMode | None = None

    @model_validator(mode="after")
    def supported_provider(self):
        if self.service_tier is not None and self.provider != "codex":
            raise ValueError("service tier is not available for this provider")
        if self.collaboration_mode is not None and self.provider != "codex":
            raise ValueError("collaboration mode is not available for this provider")
        return self

    @model_serializer(mode="wrap")
    def serialize(self, handler):
        data = handler(self)
        # Preserve existing selection fingerprints and wire shape for omission
        # and explicit null, including commands sent to older connectors.
        if self.service_tier is None:
            data.pop("service_tier", None)
        if self.collaboration_mode is None:
            data.pop("collaboration_mode", None)
        return data


def validate_selection(selection: ModelSelection, catalog: ModelCatalog, *,
                       device_id: str, session_id: str, now: float) -> dict:
    """Return only validated explicit settings; absence means inherit upstream."""
    import math
    if not math.isfinite(now) or now >= catalog.valid_until:
        raise ValueError("model catalog expired")
    if (device_id, session_id, selection.provider, selection.catalog_revision) != (
            catalog.device_id, catalog.session_id, catalog.provider, catalog.revision):
        raise ValueError("model catalog changed or belongs to another session")
    option = next((item for item in catalog.models if item.id == selection.model), None)
    if option is None or (selection.effort is not None and selection.effort not in option.efforts):
        raise ValueError("unsupported model/effort combination")
    value = {"model": option.id}
    if selection.effort is not None:
        value["effort"] = selection.effort
    if selection.service_tier is not None:
        if selection.service_tier not in option.service_tiers:
            raise ValueError("unsupported model/service tier combination")
        value["service_tier"] = selection.service_tier
    if selection.collaboration_mode is not None:
        if selection.collaboration_mode not in catalog.collaboration_modes:
            raise ValueError("unsupported collaboration mode")
        value["collaboration_mode"] = selection.collaboration_mode
    return value


class Choice(Contract):
    id: str = Field(min_length=1, max_length=80)
    label: str = Field(min_length=1, max_length=240)
    description: str = Field(default="", max_length=1000)


class Question(Contract):
    id: str = Field(min_length=1, max_length=80)
    text: str = Field(min_length=1, max_length=2000)
    options: tuple[Choice, ...] = Field(default=(), max_length=32)
    multiple: StrictBool = False
    allows_text: StrictBool = False

    @model_validator(mode="after")
    def usable_choices(self):
        if not self.options and not self.allows_text:
            raise ValueError("question has no answer mechanism")
        if len(self.options) != len({option.id for option in self.options}):
            raise ValueError("ambiguous choices")
        return self


class Answer(Contract):
    question_id: str = Field(min_length=1, max_length=80)
    choices: tuple[str, ...] = Field(default=(), max_length=32)
    text: str | None = Field(default=None, min_length=1, max_length=4000)


def validate_answers(questions: tuple[Question, ...], answers: tuple[Answer, ...]) -> None:
    """Validate explicit answers; never select defaults or answer missing items."""
    ids = {question.id for question in questions}
    if not questions or len(questions) > 16 or len(ids) != len(questions):
        raise ValueError("invalid question set")
    if len(answers) != len(questions) or {answer.question_id for answer in answers} != ids:
        raise ValueError("missing, duplicate or unknown answer")
    indexed = {question.id: question for question in questions}
    for answer in answers:
        question = indexed[answer.question_id]
        if len(set(answer.choices)) != len(answer.choices):
            raise ValueError("duplicate selection")
        if not set(answer.choices) <= {choice.id for choice in question.options}:
            raise ValueError("unknown selection")
        if not question.multiple and len(answer.choices) > 1:
            raise ValueError("multiple choices for single-select question")
        if answer.text is not None and (not question.allows_text or not answer.text.strip()):
            raise ValueError("free text not allowed or empty")
        if answer.text is not None and answer.choices:
            raise ValueError("custom text and listed choices are alternatives")
        if not answer.choices and answer.text is None:
            raise ValueError("no answer selected")


def reply_fingerprint(text: str, selection: ModelSelection | None) -> str:
    """A replay with changed settings must not be mistaken for the same reply."""
    value = {"text": text, "selection": selection.model_dump() if selection is not None else None}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()
