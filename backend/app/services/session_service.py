from __future__ import annotations

import json
import uuid
from typing import Any, Dict, List, Optional

from ..core.audit import AuditLog
from ..core.config import Settings
from ..core.db import Database
from .errors import Conflict, Expired, Forbidden, NotFound, ServiceError
from .pairing_service import PairingService, normalize_scopes


class SessionService:
    def __init__(self, db: Database, settings: Settings, audit: AuditLog, pairings: PairingService):
        self.db, self.settings, self.audit, self.pairings = db, settings, audit, pairings

    def request(self, controller: Dict[str, Any], pairing_id: str, scopes: Optional[List[str]]) -> Dict[str, Any]:
        p = self.pairings.raw(pairing_id)
        if p["controller_id"] != controller["id"]:
            raise Forbidden("only the controller can request a session")
        if p["status"] != "active":
            raise Conflict("pairing is not active")
        allowed = set(json.loads(p["scopes"]))
        wanted = normalize_scopes(scopes)
        if not wanted:
            raise ServiceError("at least one scope is required")
        if not set(wanted) <= allowed:
            raise Forbidden("requested scope exceeds what the device owner granted")
        busy = self.db.one(
            "SELECT id FROM sessions WHERE pairing_id=? AND status IN ('pending','active') AND expires_at>?",
            (pairing_id, self.settings.clock()),
        )
        if busy:
            raise Conflict("a session is already pending or active")
        now = self.settings.clock()
        sid = uuid.uuid4().hex
        self.db.run(
            "INSERT INTO sessions (id, pairing_id, target_id, controller_id, scopes, status, created_at, expires_at) "
            "VALUES (?,?,?,?,?,?,?,?)",
            (sid, pairing_id, p["target_id"], controller["id"], json.dumps(wanted), "pending", now,
             now + self.settings.session_request_ttl),
        )
        self.audit.record(controller["id"], "session_requested", f"{sid} scopes={wanted}")
        return self.view(sid, controller["id"])

    def _get(self, sid: str) -> Dict[str, Any]:
        s = self.db.one("SELECT * FROM sessions WHERE id=?", (sid,))
        if s is None:
            raise NotFound("session not found")
        return s

    def approve(self, target: Dict[str, Any], sid: str, scopes: Optional[List[str]]) -> Dict[str, Any]:
        s = self._get(sid)
        if s["target_id"] != target["id"]:
            raise Forbidden("only the target device can approve")
        if s["status"] != "pending":
            raise Conflict("session is not pending")
        now = self.settings.clock()
        if s["expires_at"] <= now:
            self._finish(sid, "expired", "request expired")
            raise Expired("session request expired")
        requested = set(json.loads(s["scopes"]))
        granted = normalize_scopes(scopes if scopes is not None else sorted(requested))
        if not set(granted) <= requested or not granted:
            raise ServiceError("granted scopes must be a non-empty subset of the request")
        self.db.run(
            "UPDATE sessions SET status='active', scopes=?, expires_at=? WHERE id=?",
            (json.dumps(granted), now + self.settings.session_max_seconds, sid),
        )
        self.audit.record(target["id"], "session_approved", f"{sid} scopes={granted}")
        return self.view(sid, target["id"])

    def reject(self, target: Dict[str, Any], sid: str) -> Dict[str, Any]:
        s = self._get(sid)
        if s["target_id"] != target["id"]:
            raise Forbidden("only the target device can reject")
        if s["status"] != "pending":
            raise Conflict("session is not pending")
        self._finish(sid, "rejected", "rejected by owner")
        self.audit.record(target["id"], "session_rejected", sid)
        return self.view(sid, target["id"])

    def end(self, device: Dict[str, Any], sid: str, reason: str = "ended") -> Dict[str, Any]:
        s = self._get(sid)
        if device["id"] not in (s["target_id"], s["controller_id"]):
            raise Forbidden("not a participant")
        if s["status"] in ("pending", "active"):
            self._finish(sid, "ended", f"{reason} by {'target' if device['id'] == s['target_id'] else 'controller'}")
            self.audit.record(device["id"], "session_ended", sid)
        return self.view(sid, device["id"])

    def _finish(self, sid: str, status: str, reason: str) -> None:
        self.db.run("UPDATE sessions SET status=?, ended_at=?, end_reason=? WHERE id=?",
                    (status, self.settings.clock(), reason[:200], sid))

    def end_for_pairing(self, pairing_id: str, reason: str) -> List[str]:
        rows = self.db.query("SELECT id FROM sessions WHERE pairing_id=? AND status IN ('pending','active')", (pairing_id,))
        for r in rows:
            self._finish(r["id"], "ended", reason)
        return [r["id"] for r in rows]

    def expire_due(self) -> List[Dict[str, Any]]:
        now = self.settings.clock()
        rows = self.db.query("SELECT * FROM sessions WHERE status IN ('pending','active') AND expires_at<=?", (now,))
        for r in rows:
            self._finish(r["id"], "expired", "session expired")
            self.audit.record(r["target_id"], "session_expired", r["id"])
        return rows

    def active_for_ws(self, device_id: str, sid: str) -> Dict[str, Any]:
        """Validate that `device_id` may attach to session `sid` right now; returns row + role."""
        s = self._get(sid)
        if device_id not in (s["target_id"], s["controller_id"]):
            raise Forbidden("not a participant")
        if s["status"] != "active":
            raise Conflict("session is not active")
        if s["expires_at"] <= self.settings.clock():
            self._finish(sid, "expired", "session expired")
            raise Expired("session expired")
        s["role"] = "target" if device_id == s["target_id"] else "controller"
        s["scope_set"] = set(json.loads(s["scopes"]))
        return s

    def view(self, sid: str, me: str) -> Dict[str, Any]:
        s = self._get(sid)
        if me not in (s["target_id"], s["controller_id"]):
            raise Forbidden("not a participant")
        peer_id = s["controller_id"] if me == s["target_id"] else s["target_id"]
        peer = self.db.one("SELECT id, name, model FROM devices WHERE id=?", (peer_id,)) or {}
        return {
            "id": s["id"], "pairing_id": s["pairing_id"], "role": "target" if me == s["target_id"] else "controller",
            "status": s["status"], "scopes": json.loads(s["scopes"]),
            "peer": {"id": peer.get("id"), "name": peer.get("name"), "model": peer.get("model")},
            "created_at": s["created_at"], "expires_at": s["expires_at"], "ended_at": s["ended_at"],
            "end_reason": s["end_reason"],
            # lets clients compute remaining time without trusting that their clock matches the server's
            "server_time": self.settings.clock(),
        }

    def pending_for_target(self, target_id: str) -> List[Dict[str, Any]]:
        rows = self.db.query(
            "SELECT id FROM sessions WHERE target_id=? AND status='pending' AND expires_at>?",
            (target_id, self.settings.clock()),
        )
        return [self.view(r["id"], target_id) for r in rows]

    def history(self, me: str, limit: int = 100) -> List[Dict[str, Any]]:
        limit = max(1, min(limit, 500))
        rows = self.db.query(
            "SELECT id FROM sessions WHERE target_id=? OR controller_id=? ORDER BY created_at DESC LIMIT ?", (me, me, limit)
        )
        return [self.view(r["id"], me) for r in rows]
