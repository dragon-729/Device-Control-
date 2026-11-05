"""Security / audit event log (persisted and mirrored to the process log)."""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from .db import Database

log = logging.getLogger("droidlink.audit")


class AuditLog:
    def __init__(self, db: Database, clock):
        self._db = db
        self._clock = clock

    def record(self, device_id: Optional[str], event: str, detail: str = "") -> None:
        detail = detail[:500]
        self._db.run(
            "INSERT INTO audit (ts, device_id, event, detail) VALUES (?,?,?,?)",
            (self._clock(), device_id, event, detail),
        )
        log.info("audit device=%s event=%s detail=%s", device_id, event, detail)

    def for_device(self, device_id: str, limit: int = 100) -> List[Dict[str, Any]]:
        limit = max(1, min(limit, 500))
        return self._db.query(
            "SELECT ts, event, detail FROM audit WHERE device_id=? ORDER BY id DESC LIMIT ?",
            (device_id, limit),
        )
