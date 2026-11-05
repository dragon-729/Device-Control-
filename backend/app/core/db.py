"""SQLite persistence (thread-safe wrapper around a single connection)."""
from __future__ import annotations

import sqlite3
import threading
from typing import Any, Dict, Iterable, List, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    model TEXT NOT NULL,
    secret_hash TEXT NOT NULL,
    created_at REAL NOT NULL,
    last_seen REAL,
    revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pairing_codes (
    code TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    expires_at REAL NOT NULL,
    used INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pairings (
    id TEXT PRIMARY KEY,
    target_id TEXT NOT NULL,
    controller_id TEXT NOT NULL,
    status TEXT NOT NULL,
    scopes TEXT NOT NULL,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    pairing_id TEXT NOT NULL,
    target_id TEXT NOT NULL,
    controller_id TEXT NOT NULL,
    scopes TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at REAL NOT NULL,
    expires_at REAL NOT NULL,
    ended_at REAL,
    end_reason TEXT
);
CREATE TABLE IF NOT EXISTS audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts REAL NOT NULL,
    device_id TEXT,
    event TEXT NOT NULL,
    detail TEXT
);
CREATE INDEX IF NOT EXISTS idx_pairings_target ON pairings(target_id);
CREATE INDEX IF NOT EXISTS idx_pairings_controller ON pairings(controller_id);
CREATE INDEX IF NOT EXISTS idx_sessions_target ON sessions(target_id);
CREATE INDEX IF NOT EXISTS idx_sessions_controller ON sessions(controller_id);
CREATE INDEX IF NOT EXISTS idx_audit_device ON audit(device_id);
"""


class Database:
    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        with self._lock:
            self._conn.executescript(SCHEMA)
            self._conn.commit()

    def query(self, sql: str, params: Iterable[Any] = ()) -> List[Dict[str, Any]]:
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, tuple(params)).fetchall()]

    def one(self, sql: str, params: Iterable[Any] = ()) -> Optional[Dict[str, Any]]:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def run(self, sql: str, params: Iterable[Any] = ()) -> int:
        with self._lock:
            cur = self._conn.execute(sql, tuple(params))
            self._conn.commit()
            return cur.rowcount

    def close(self) -> None:
        with self._lock:
            self._conn.close()
