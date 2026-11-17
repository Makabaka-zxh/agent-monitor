"""Synthetic contracts only: no provider calls, phone access, or real decisions."""
import pytest
from pydantic import ValidationError

from agent_monitor.session_controls import (
    Answer, Choice, ModelCatalog, ModelOption, ModelSelection, Question,
    reply_fingerprint, validate_answers, validate_selection,
)


def catalog(**changes):
    data = dict(provider="codex", device_id="synthetic-device", session_id="synthetic-session",
                revision="synthetic-revision", valid_until=100.0,
                models=(ModelOption(id="synthetic-model", label="Synthetic", efforts=("low", "high")),))
    return ModelCatalog(**(data | changes))


def selection(**changes):
    return ModelSelection(**(dict(provider="codex", catalog_revision="synthetic-revision",
                                 model="synthetic-model", effort="low") | changes))


def verify(pick, source=None, **changes):
    return validate_selection(pick, source or catalog(), **(dict(device_id="synthetic-device",
        session_id="synthetic-session", now=10.0) | changes))


def test_explicit_model_and_effort_come_from_catalog_not_fixed_presets():
    assert verify(selection()) == {"model": "synthetic-model", "effort": "low"}
    assert verify(selection(effort=None)) == {"model": "synthetic-model"}
    assert verify(selection(provider="claude"), catalog(provider="claude"))["effort"] == "low"


@pytest.mark.parametrize("change", [dict(provider="claude"), dict(catalog_revision="old"),
    dict(model="guessed-model"), dict(effort="ultra"), dict(effort="bypassPermissions")])
def test_wrong_provider_revision_or_unknown_combination_is_refused(change):
    with pytest.raises(ValueError):
        verify(selection(**change))


@pytest.mark.parametrize("change", [dict(device_id="another"), dict(session_id="another"),
    dict(now=100.0), dict(now=101.0), dict(now=float("nan")), dict(now=float("inf"))])
def test_expiry_and_ownership_binding(change):
    with pytest.raises(ValueError):
        verify(selection(), **change)


def test_missing_or_ambiguous_catalog_never_invents_support():
    with pytest.raises(ValueError): verify(selection(), catalog(models=()))
    with pytest.raises(ValidationError): catalog(models=catalog().models * 2)
    with pytest.raises(ValidationError): ModelOption(id="x", label="X", efforts=("low", "low"))
    with pytest.raises(ValidationError): ModelOption(id="x", label="\u202eX")
    with pytest.raises(ValidationError): selection(permissionMode="bypassPermissions")
    with pytest.raises(ValidationError): selection(apply_at="current_running_turn")


def test_retry_identity_binds_settings_and_text():
    original = reply_fingerprint("synthetic", selection())
    assert original == reply_fingerprint("synthetic", selection())
    assert original != reply_fingerprint("synthetic", selection(effort="high"))
    assert original != reply_fingerprint("synthetic", None)
    assert original != reply_fingerprint("different", selection())


def test_catalog_context_variant_is_literal_and_still_requires_membership():
    variant = ModelOption(id="synthetic-model[1m]", label="Synthetic long context")
    source = catalog(models=(variant,))
    assert verify(selection(model=variant.id, effort=None), source) == {"model": variant.id}
    with pytest.raises(ValueError): verify(selection(model=variant.id, effort=None))
    for invalid in ("--model", "model;echo", "model$(x)", "model[1m]extra", "model[", "model\n"):
        with pytest.raises(ValidationError): ModelOption(id=invalid, label="Synthetic")


def question(**changes):
    return Question(**(dict(id="q", text="Synthetic choice?", options=(
        Choice(id="one", label="One"), Choice(id="two", label="Two"))) | changes))


def test_question_preserves_single_multiple_and_explicit_text():
    validate_answers((question(),), (Answer(question_id="q", choices=("one",)),))
    validate_answers((question(multiple=True),), (Answer(question_id="q", choices=("one", "two")),))
    validate_answers((question(options=(), allows_text=True),), (Answer(question_id="q", text="Synthetic"),))


@pytest.mark.parametrize("answers", [(), (Answer(question_id="q"),),
    (Answer(question_id="other", choices=("one",)),),
    (Answer(question_id="q", choices=("unknown",)),),
    (Answer(question_id="q", choices=("one", "one")),),
    (Answer(question_id="q", choices=("one", "two")),),
    (Answer(question_id="q", text="not allowed"),),
    (Answer(question_id="q", choices=("one",)),) * 2])
def test_no_defaults_missing_or_invalid_answers(answers):
    with pytest.raises(ValueError): validate_answers((question(),), answers)


def test_ambiguous_questions_and_blank_text_refused():
    with pytest.raises(ValidationError): question(options=(Choice(id="one", label="A"),) * 2)
    with pytest.raises(ValidationError): question(options=())
    with pytest.raises(ValueError): validate_answers((question(),) * 2, ())
    with pytest.raises(ValueError):
        validate_answers((question(allows_text=True),), (Answer(question_id="q", text="   "),))
    with pytest.raises(ValueError):
        validate_answers((question(allows_text=True),), (Answer(question_id="q", choices=("one",), text="Synthetic"),))
