"""Catalog normalization from provider responses, without model-name guesses."""
from __future__ import annotations

import hashlib
import json
import time

from .session_controls import QUICK_SERVICE_TIER, ModelCatalog, ModelOption, ModelSelection, validate_selection


def antigravity_catalog_rows(payload):
    """Extract explicit model and per-model thinking levels from ACP session metadata."""
    if not isinstance(payload, dict):
        raise ValueError("unsupported model catalog")
    config_options = payload.get("configOptions")
    if not isinstance(config_options, list):
        raise ValueError("unsupported model catalog")
    model_opt = next((o for o in config_options if isinstance(o, dict)
                      and (o.get("id") == "model" or o.get("category") == "model")), None)
    if not isinstance(model_opt, dict) or not isinstance(model_opt.get("options"), list) or not model_opt["options"]:
        raise ValueError("unsupported model catalog")
    thought_opt = next((o for o in config_options if isinstance(o, dict)
                        and (o.get("id") == "thought_level" or o.get("category") == "thought_level")), None)
    current_model = model_opt.get("currentValue") if isinstance(model_opt.get("currentValue"), str) else None
    current_effort = (thought_opt.get("currentValue")
                      if isinstance(thought_opt, dict) and isinstance(thought_opt.get("currentValue"), str) else None)
    active_efforts = []
    if isinstance(thought_opt, dict) and isinstance(thought_opt.get("options"), list):
        for item in thought_opt["options"]:
            if not isinstance(item, dict) or not isinstance(item.get("value"), str):
                raise ValueError("unsupported effort catalog")
            active_efforts.append(item["value"])
    models_block = payload.get("models")
    available = models_block.get("availableModels") if isinstance(models_block, dict) else None
    base_ids = set()
    for opt in model_opt["options"]:
        if not isinstance(opt, dict) or not isinstance(opt.get("value"), str) or not isinstance(opt.get("name"), str):
            raise ValueError("unsupported model catalog")
        base_ids.add(opt["value"])
    rows = []
    if isinstance(available, list) and available:
        advertised_ids = []
        for item in available:
            if not isinstance(item, dict) or not isinstance(item.get("modelId"), str):
                raise ValueError("unsupported model catalog")
            advertised_ids.append(item["modelId"])
        for opt in model_opt["options"]:
            base_id = opt["value"]
            efforts = []
            for mid in advertised_ids:
                if mid not in base_ids and mid.startswith(base_id + "-"):
                    suffix = mid[len(base_id) + 1:]
                    if suffix and suffix not in efforts:
                        efforts.append(suffix)
            rows.append({"value": base_id, "displayName": opt["name"],
                         "supportsEffort": bool(efforts), "supportedEffortLevels": efforts})
    else:
        for opt in model_opt["options"]:
            base_id = opt["value"]
            efforts = list(active_efforts) if base_id == current_model and active_efforts else []
            rows.append({"value": base_id, "displayName": opt["name"],
                         "supportsEffort": bool(efforts), "supportedEffortLevels": efforts})
    return rows, current_model, current_effort


def normalize_catalog(provider, rows, *, current_model=None, current_effort=None, now=None, collaboration_modes=()):
    if provider not in {"codex", "claude", "antigravity"} or not isinstance(rows, list) or not 0 < len(rows) <= 256:
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
    if provider == "antigravity" and isinstance(current_model, str) and all(m.id != current_model for m in models):
        matches = [(m.id, eff) for m in models for eff in m.efforts if current_model == f"{m.id}-{eff}"]
        if len(matches) == 1:
            current_model = matches[0][0]
            if current_effort is None:
                current_effort = matches[0][1]
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
