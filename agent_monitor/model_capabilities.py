"""Catalog normalization from provider responses, without model-name guesses."""
from __future__ import annotations

import hashlib
import json
import time

from .session_controls import QUICK_SERVICE_TIER, ModelCatalog, ModelOption, ModelSelection, validate_selection


def normalize_catalog(provider, rows, *, current_model=None, current_effort=None, now=None, collaboration_modes=()):
    if provider not in {"codex", "claude"} or not isinstance(rows, list) or not 0 < len(rows) <= 256:
        raise ValueError("unsupported model catalog")
    models = []
    for row in rows:
        if not isinstance(row, dict): raise ValueError("unsupported model catalog")
        service_tiers = ()
        if provider == "codex":
            if row.get("hidden") is True: continue
            identity, label = row.get("model"), row.get("displayName")
            effort_rows = row.get("supportedReasoningEfforts", [])
            if not isinstance(effort_rows, list): raise ValueError("unsupported effort catalog")
            efforts = [entry.get("reasoningEffort") if isinstance(entry, dict) else None for entry in effort_rows]
            advertised = row.get("serviceTiers")
            if isinstance(advertised, list) and any(isinstance(tier, dict) and tier.get("id") == QUICK_SERVICE_TIER for tier in advertised):
                service_tiers = (QUICK_SERVICE_TIER,)
        else:
            identity, label = row.get("value"), row.get("displayName")
            efforts = row.get("supportedEffortLevels", []) if row.get("supportsEffort") is True else []
            if not isinstance(efforts, list): raise ValueError("unsupported effort catalog")
        models.append(ModelOption(id=identity, label=label, efforts=tuple(efforts), service_tiers=service_tiers))
    models.sort(key=lambda item: item.id)
    payload = [model.model_dump(mode="json") for model in models]
    capability = {"provider": provider, "models": payload}
    if collaboration_modes:
        capability["collaboration_modes"] = list(collaboration_modes)
    revision = hashlib.sha256(json.dumps(capability,
        sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    # Validate duplicates and expiry with the common contract before publishing.
    expiry = float((time.time() if now is None else now) + 120)
    ModelCatalog(provider=provider, device_id="local", session_id="local", revision=revision,
                 valid_until=expiry, models=tuple(models), collaboration_modes=collaboration_modes)
    return {**capability, "catalog_revision": revision, "valid_until": expiry,
            "current_model": current_model, "current_effort": current_effort}


def selected_settings(selection, catalog, *, provider, session_id, now=None):
    selected = ModelSelection.model_validate(selection)
    if selected.provider != provider or not isinstance(catalog, dict):
        raise ValueError("catalog provider mismatch")
    value = ModelCatalog(provider=catalog["provider"], device_id="local", session_id=session_id,
        revision=catalog["catalog_revision"], valid_until=float(catalog["valid_until"]),
        collaboration_modes=tuple(catalog.get("collaboration_modes", ())),
        models=tuple(ModelOption.model_validate_json(json.dumps(item))
                     for item in catalog["models"]))
    return validate_selection(selected, value, device_id="local", session_id=session_id,
                              now=time.time() if now is None else now)
