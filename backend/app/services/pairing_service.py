from __future__ import annotations

import json
import uuid
from typing import Any, Dict, List, Optional

from ..core import security
from ..core.audit import AuditLog
from ..core.config import Settings
from ..core.db import Database
from .errors import Conflict, Expired, Forbidden, NotFound, ServiceError
from .relay_policy import ALL_SCOPES


def normalize_scopes(scopes: Optional[List[str]], default_all: bool = True) -> List[str]:
    if scopes is None:
        return list(ALL_SCOPES) if default_all else []
    clean = sorted(set(scopes))
    if any(s not in ALL_SCOPES for s in clean):
        raise ServiceError("unknown scope")
    return clean


class PairingService:
    def __init__(self, db: Database, settings: Settings, audit: AuditLog):
        self.db, self.settings, self.audit = db, settings, audit

    # ---- codes ---------------------------------------------------------------
    def create_code(self, device: Dict[str, Any]) -> Dict[str, Any]:
        now = self.settings.clock()
        self.db.run("DELETE FROM pairing_codes WHERE device_id=? OR expires_at<?", (device["id"], now))
        code = security.new_pairing_code()
        expires = now + self.settings.pairing_code_ttl
        self.db.run("INSERT INTO pairing_codes (code, device_id, expires_at) VALUES (?,?,?)", (code, device["id"], expires))
        self.audit.record(device["id"], "pairing_code_created")
        return {"code": code, "expires_at": expires, "expires_in": self.settings.pairing_code_ttl}

    def claim(self, controller: Dict[str, Any], raw_code: str) -> Dict[str, Any]:
        code = security.normalize_code(raw_code)
        if not security.is_valid_code_format(code):
            raise ServiceError("invalid pairing code")
        row = self.db.one("SELECT * FROM pairing_codes WHERE code=?", (code,))
        now = self.settings.clock()
        if row is None or row["used"]:
            self.audit.record(controller["id"], "pairing_claim_failed", "unknown or used code")
            raise NotFound("pairing code not found")
        if row["expires_at"] <= now:
            self.db.run("DELETE FROM pairing_codes WHERE code=?", (code,))
            raise Expired("pairing code expired")
        if row["device_id"] == controller["id"]:
            raise Conflict("cannot pair a device with itself")
        target = self.db.one("SELECT * FROM devices WHERE id=? AND revoked=0", (row["device_id"],))
        if target is None:
            raise NotFound("pairing code not found")
        existing = self.db.one(
            "SELECT id FROM pairings WHERE target_id=? AND controller_id=? AND status IN ('pending','active')",
            (target["id"], controller["id"]),
        )
        if existing:
            raise Conflict("already paired or pending")
        self.db.run("UPDATE pairing_codes SET used=1 WHERE code=?", (code,))  # single use
        pid = uuid.uuid4().hex
        self.db.run(
            "INSERT INTO pairings (id, target_id, controller_id, status, scopes, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
            (pid, target["id"], controller["id"], "pending", json.dumps(list(ALL_SCOPES)), now, now),
        )
        self.audit.record(controller["id"], "pairing_requested", f"target={target['id']}")
        self.audit.record(target["id"], "pairing_request_received", f"controller={controller['id']}")
        return self.view(pid, controller["id"])

    # ---- lifecycle -----------------------------------------------------------
    def _get(self, pairing_id: str) -> Dict[str, Any]:
        p = self.db.one("SELECT * FROM pairings WHERE id=?", (pairing_id,))
        if p is None:
            raise NotFound("pairing not found")
        return p

    def approve(self, target: Dict[str, Any], pairing_id: str, scopes: Optional[List[str]]) -> Dict[str, Any]:
        p = self._get(pairing_id)
        if p["target_id"] != target["id"]:
            raise Forbidden("only the target device can approve")
        if p["status"] != "pending":
            raise Conflict("pairing is not pending")
        granted = normalize_scopes(scopes)
        self.db.run("UPDATE pairings SET status='active', scopes=?, updated_at=? WHERE id=?",
                    (json.dumps(granted), self.settings.clock(), pairing_id))
        self.audit.record(target["id"], "pairing_approved", f"{pairing_id} scopes={granted}")
        return self.view(pairing_id, target["id"])

    def reject(self, target: Dict[str, Any], pairing_id: str) -> Dict[str, Any]:
        p = self._get(pairing_id)
        if p["target_id"] != target["id"]:
            raise Forbidden("only the target device can reject")
        if p["status"] != "pending":
            raise Conflict("pairing is not pending")
        self.db.run("UPDATE pairings SET status='rejected', updated_at=? WHERE id=?", (self.settings.clock(), pairing_id))
        self.audit.record(target["id"], "pairing_rejected", pairing_id)
        return self.view(pairing_id, target["id"])

    def update_scopes(self, target: Dict[str, Any], pairing_id: str, scopes: List[str]) -> Dict[str, Any]:
        p = self._get(pairing_id)
        if p["target_id"] != target["id"]:
            raise Forbidden("only the target device can change permissions")
        if p["status"] != "active":
            raise Conflict("pairing is not active")
        granted = normalize_scopes(scopes)
        self.db.run("UPDATE pairings SET scopes=?, updated_at=? WHERE id=?",
                    (json.dumps(granted), self.settings.clock(), pairing_id))
        self.audit.record(target["id"], "pairing_scopes_changed", f"{pairing_id} scopes={granted}")
        return self.view(pairing_id, target["id"])

    def revoke(self, device: Dict[str, Any], pairing_id: str) -> Dict[str, Any]:
        p = self._get(pairing_id)
        if device["id"] not in (p["target_id"], p["controller_id"]):
            raise Forbidden("not a participant")
        self.db.run("UPDATE pairings SET status='revoked', updated_at=? WHERE id=?", (self.settings.clock(), pairing_id))
        self.audit.record(device["id"], "pairing_revoked", pairing_id)
        return self.view(pairing_id, device["id"])

    def peer_id(self, pairing: Dict[str, Any], me: str) -> str:
        return pairing["controller_id"] if pairing["target_id"] == me else pairing["target_id"]

    # ---- queries -------------------------------------------------------------
    def view(self, pairing_id: str, me: str) -> Dict[str, Any]:
        p = self._get(pairing_id)
        if me not in (p["target_id"], p["controller_id"]):
            raise Forbidden("not a participant")
        role = "target" if p["target_id"] == me else "controller"
        peer = self.db.one("SELECT id, name, model FROM devices WHERE id=?", (self.peer_id(p, me),)) or {}
        return {
            "id": p["id"], "role": role, "status": p["status"], "scopes": json.loads(p["scopes"]),
            "peer": {"id": peer.get("id"), "name": peer.get("name"), "model": peer.get("model")},
            "created_at": p["created_at"], "updated_at": p["updated_at"],
        }

    def list_for(self, me: str) -> List[Dict[str, Any]]:
        rows = self.db.query(
            "SELECT id FROM pairings WHERE (target_id=? OR controller_id=?) ORDER BY updated_at DESC LIMIT 200", (me, me)
        )
        return [self.view(r["id"], me) for r in rows]

    def raw(self, pairing_id: str) -> Dict[str, Any]:
        return self._get(pairing_id)
