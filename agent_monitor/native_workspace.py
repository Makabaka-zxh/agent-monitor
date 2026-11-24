"""Fixed bearer APIs for the Android workbench; no browser-cookie exchange."""
from __future__ import annotations

import re

from fastapi import HTTPException, Request
from pydantic import Field
from .request_diagnostics import mark_request_phase

# Share the browser API's validated inputs and Store operations. Imported only
# once create_app has finished defining these models, avoiding a second policy.
from .server import ArchiveChanges, Preferences, ProfileChanges


class NativeArchiveChanges(ArchiveChanges):
    task_id: str = Field(min_length=1, max_length=500)


class NativePreferences(Preferences):
    model_config = {"extra": "forbid"}


def install_workspace_routes(app, access, store, bearer, reader_gate, same_origin, google_enabled):
    def perform(request, operation, *, write=False):
        mark_request_phase("handler_start")
        try:
            reader_gate(request)
            # A bearer is never inferred from browser cookies. Cross-origin browser
            # submissions also fail, even if their caller knows a native credential.
            same_origin(request)
            return access.workspace(bearer(request), operation, write=write)
        finally:
            mark_request_phase("handler_end")

    from .task_results import install_result_routes
    from .task_replies import install_reply_routes
    from .task_interactions import install_control_routes
    install_result_routes(app, app.state.task_results, perform)
    install_reply_routes(app, store, perform)
    install_control_routes(app, store, perform)

    def identity(value, *, native=False):
        pattern = r"[A-Za-z0-9_-]{32}" if native else r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
        if not re.fullmatch(pattern, value):
            raise HTTPException(404, "对象不存在")
        return value

    def workbench(reader):
        mark_request_phase("snapshot_start")
        snapshot = store.snapshot()
        pending_kinds = app.state.task_replies.controls.pending_kinds(reader["id"])
        continuation = app.state.task_replies.continuation_states(snapshot)
        device_tools = app.state.task_replies.device_supported_tools(snapshot)
        mark_request_phase("snapshot_end")
        mark_request_phase("usage_start")
        snapshot = app.state.usage.enrich(snapshot)
        mark_request_phase("usage_end")
        for device in snapshot["devices"]:
            device["supported_tools"] = device_tools.get(device["id"], [])
        names = {device["id"]: device["name"] for device in snapshot["devices"]}
        for task in snapshot["tasks"]:
            task["device_name"] = names.get(task["device_id"], "电脑")
            task.pop("pending_interaction_kind", None)
            task.pop("continuation_state", None)
            if not task["archived"] and not task["stale"]:
                if task["id"] in pending_kinds:
                    task["pending_interaction_kind"] = pending_kinds[task["id"]]
                if task["id"] in continuation:
                    task["continuation_state"] = continuation[task["id"]]
            # Final answers are fetched on demand by the task panel. Repeating
            # every answer in five-second polling wastes bandwidth on mobile.
            task.pop("output", None)
        online_devs = [d for d in snapshot["devices"] if d.get("online")]
        source_devs = online_devs or snapshot["devices"]
        collected_candidates = [d["collected_at"] for d in source_devs if d.get("collected_at")]
        if not collected_candidates:
            collected_candidates = [
                t.get("final_result_at") or t.get("updated_at")
                for t in snapshot["tasks"]
                if not t.get("stale") and (t.get("final_result_at") or t.get("updated_at"))
            ]
        received_candidates = [d["last_seen"] for d in source_devs if d.get("last_seen")]
        sync_timings = {
            "computer_completed_at": max(collected_candidates) if collected_candidates else None,
            "server_received_at": max(received_candidates) if received_candidates else None,
            "server_generated_at": snapshot["server_time"],
        }
        # Frequent task polling must not repeatedly transmit avatar bytes.
        return {**snapshot, "generated_at": snapshot["server_time"], "sync_timings": sync_timings,
                "preferences": store.preferences(), "mode": "full_app"}

    def account(reader):
        sessions = store.sessions("")
        child = store.db.execute("SELECT id FROM sessions WHERE native_reader_id=?", (reader["id"],)).fetchone()
        for session in sessions:
            session["current"] = bool(child and session["id"] == child["id"])
            session["authorizes_current"] = session["id"] == reader["parent_session_id"]
        devices = access.devices()["devices"]
        for device in devices:
            device["current"] = device["id"] == reader["id"]
        return {"user": store.profile(), "preferences": store.preferences(), "sync_mode": "self_hosted",
                "google_login": google_enabled(),
                "google_linked": bool(store.db.execute("SELECT 1 FROM google_identity WHERE id=1").fetchone()),
                "sessions": sessions, "native_devices": devices, "current_reader_id": reader["id"]}

    @app.get("/api/native/workbench")
    def read_workbench(request: Request):
        return perform(request, workbench)

    @app.get("/api/native/account")
    def read_account(request: Request):
        return perform(request, account)

    @app.get("/api/native/usage")
    def read_usage(request: Request):
        def summary(reader):
            mark_request_phase("usage_start")
            result = app.state.usage.detail_summary()
            mark_request_phase("usage_end")
            return result
        return perform(request, summary)

    @app.get("/api/native/profile")
    def read_profile(request: Request):
        return perform(request, lambda reader: {"user": store.profile()})

    @app.patch("/api/native/profile")
    def update_profile(changes: ProfileChanges, request: Request):
        return perform(request, lambda reader: {"user": store.update_profile(changes.model_dump(exclude_unset=True))}, write=True)

    @app.patch("/api/native/preferences")
    def update_preferences(changes: NativePreferences, request: Request):
        return perform(request, lambda reader: store.update_preferences(changes.model_dump(exclude_none=True)), write=True)

    @app.patch("/api/native/tasks/archive")
    def archive_task(changes: NativeArchiveChanges, request: Request):
        def archive(reader):
            try:
                return store.archive_task(changes.task_id, changes.archived)
            except ValueError as exc:
                raise HTTPException(404, str(exc)) from None
        return perform(request, archive, write=True)

    @app.post("/api/native/computers/pairing")
    def create_pairing(request: Request):
        return perform(request, lambda reader: store.create_pairing(), write=True)

    @app.get("/api/native/computers/pairing/{request_id}")
    def pairing_details(request_id: str, request: Request):
        return perform(request, lambda reader: store.qr_pairing_details(identity(request_id, native=True)))

    @app.post("/api/native/computers/pairing/{request_id}/approve")
    def approve_pairing(request_id: str, request: Request):
        return perform(
            request,
            lambda reader: store.decide_qr_pairing(
                identity(request_id, native=True),
                True,
                parent_session_id=reader["parent_session_id"],
            ),
            write=True,
        )

    @app.post("/api/native/computers/pairing/{request_id}/reject")
    def reject_pairing(request_id: str, request: Request):
        return perform(request, lambda reader: store.decide_qr_pairing(identity(request_id, native=True), False), write=True)

    @app.delete("/api/native/computers/{device_id}")
    def remove_computer(device_id: str, request: Request):
        def remove(reader):
            try:
                store.remove_device(identity(device_id))
                app.state.usage.refresh()
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from None
            return {"ok": True}
        return perform(request, remove, write=True)

    @app.delete("/api/native/sessions/{session_id}")
    def revoke_session(session_id: str, request: Request):
        def revoke(reader):
            store.revoke_session(identity(session_id))
            return {"ok": True}
        return perform(request, revoke, write=True)

    @app.delete("/api/native/connections/{reader_id}")
    def revoke_connection(reader_id: str, request: Request):
        return perform(request, lambda reader: access.revoke(identity(reader_id, native=True)), write=True)
