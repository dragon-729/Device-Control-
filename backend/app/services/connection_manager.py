"""Tracks live WebSocket connections: presence channels and per-session relay pairs."""
from __future__ import annotations

import json
from typing import Any, Dict, Optional

from starlette.websockets import WebSocket


class ConnectionManager:
    def __init__(self) -> None:
        self.presence: Dict[str, WebSocket] = {}
        self.sessions: Dict[str, Dict[str, WebSocket]] = {}

    # ---- presence (server -> device events) --------------------------------
    async def register_presence(self, device_id: str, ws: WebSocket) -> None:
        old = self.presence.get(device_id)
        self.presence[device_id] = ws
        if old is not None and old is not ws:
            await self._close_quietly(old, 4000)

    def unregister_presence(self, device_id: str, ws: WebSocket) -> None:
        if self.presence.get(device_id) is ws:
            del self.presence[device_id]

    async def notify(self, device_id: str, event: Dict[str, Any]) -> bool:
        ws = self.presence.get(device_id)
        if ws is None:
            return False
        try:
            await ws.send_text(json.dumps(event))
            return True
        except Exception:
            self.presence.pop(device_id, None)
            return False

    # ---- session relay ------------------------------------------------------
    async def join_session(self, session_id: str, role: str, ws: WebSocket) -> None:
        peers = self.sessions.setdefault(session_id, {})
        old = peers.get(role)
        peers[role] = ws
        if old is not None and old is not ws:
            await self._close_quietly(old, 4000)

    def leave_session(self, session_id: str, role: str, ws: WebSocket) -> None:
        peers = self.sessions.get(session_id)
        if peers and peers.get(role) is ws:
            del peers[role]
            if not peers:
                del self.sessions[session_id]

    def peer(self, session_id: str, role: str) -> Optional[WebSocket]:
        other = "target" if role == "controller" else "controller"
        return self.sessions.get(session_id, {}).get(other)

    async def close_session(self, session_id: str, code: int = 4001, reason: str = "session_ended") -> None:
        peers = self.sessions.pop(session_id, {})
        for ws in list(peers.values()):
            await self._close_quietly(ws, code, reason)

    @staticmethod
    async def _close_quietly(ws: WebSocket, code: int, reason: str = "") -> None:
        try:
            await ws.close(code=code, reason=reason)
        except Exception:
            pass
