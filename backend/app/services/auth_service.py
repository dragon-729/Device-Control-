from __future__ import annotations

import hmac
import re
import uuid
from typing import Any, Dict

from ..core import security
from ..core.audit import AuditLog
from ..core.config import Settings
from ..core.db import Database
from .errors import Forbidden, ServiceError, Unauthorized

_NAME_RE = re.compile(r"^[\w .,'\-()]{1,64}$", re.UNICODE)


def sanitize_label(value: str, field: str) -> str:
    value = (value or "").strip()
    if not _NAME_RE.match(value):
        raise ServiceError(f"invalid {field}")
    return value


class AuthService:
    def __init__(self, db: Database, settings: Settings, audit: AuditLog):
        self.db, self.settings, self.audit = db, settings, audit

    def register(self, name: str, model: str, registration_token: str | None) -> Dict[str, Any]:
        if self.settings.registration_token:
            if not registration_token or not hmac.compare_digest(registration_token, self.settings.registration_token):
                self.audit.record(None, "register_denied", "bad registration token")
                raise Forbidden("invalid registration token")
        name, model = sanitize_label(name, "name"), sanitize_label(model, "model")
        count = self.db.one("SELECT COUNT(*) AS c FROM devices")["c"]
        if count >= self.settings.max_devices:
            raise Forbidden("device limit reached")
        device_id = uuid.uuid4().hex
        secret = security.new_device_secret()
        now = self.settings.clock()
        self.db.run(
            "INSERT INTO devices (id, name, model, secret_hash, created_at, last_seen) VALUES (?,?,?,?,?,?)",
            (device_id, name, model, security.hash_secret(secret, self.settings.secret_key), now, now),
        )
        self.audit.record(device_id, "device_registered", f"{name} / {model}")
        return {"device_id": device_id, "device_secret": secret}

    def issue_token(self, device_id: str, secret: str) -> Dict[str, Any]:
        dev = self.db.one("SELECT * FROM devices WHERE id=?", (device_id,))
        # identical error for unknown device / bad secret / revoked: no account enumeration
        if dev is None or dev["revoked"] or not security.verify_secret(secret, dev["secret_hash"], self.settings.secret_key):
            self.audit.record(device_id if dev else None, "auth_failed", "invalid credentials")
            raise Unauthorized("invalid credentials")
        now = self.settings.clock()
        self.db.run("UPDATE devices SET last_seen=? WHERE id=?", (now, device_id))
        token = security.issue_access_token(device_id, self.settings.secret_key, self.settings.access_token_ttl, now)
        self.audit.record(device_id, "token_issued")
        return {"access_token": token, "token_type": "bearer", "expires_in": self.settings.access_token_ttl}

    def authenticate(self, token: str) -> Dict[str, Any]:
        try:
            claims = security.decode_access_token(token, self.settings.secret_key, self.settings.clock())
        except security.TokenError as exc:
            raise Unauthorized("invalid or expired token") from exc
        dev = self.db.one("SELECT * FROM devices WHERE id=?", (claims["sub"],))
        if dev is None or dev["revoked"]:
            raise Unauthorized("device revoked")
        return dev
