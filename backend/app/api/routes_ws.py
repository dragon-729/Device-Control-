from __future__ import annotations

import json

from fastapi import APIRouter, WebSocket

from ..services import relay_policy as policy
from ..services.errors import ServiceError
from .deps import parse_bearer

router = APIRouter(prefix="/api/v1/ws", tags=["websocket"])


async def _auth(ws: WebSocket):
    ctx = ws.app.state.ctx
    try:
        return ctx, ctx.auth.authenticate(parse_bearer(ws.headers.get("authorization")))
    except ServiceError:
        await ws.close(code=1008)
        return ctx, None


@router.websocket("/presence")
async def presence(ws: WebSocket):
    """Server-to-device event channel (pairing requests, session requests, status updates)."""
    ctx, device = await _auth(ws)
    if device is None:
        return
    await ws.accept()
    await ctx.manager.register_presence(device["id"], ws)
    try:
        await ws.send_text(json.dumps({"event": "hello", "device_id": device["id"]}))
        while True:
            text = await ws.receive_text()
            if len(text) > 1024:
                await ws.close(code=1009)
                break
            if text == "ping":
                await ws.send_text("pong")
    except Exception:
        pass
    finally:
        ctx.manager.unregister_presence(device["id"], ws)


@router.websocket("/sessions/{sid}")
async def session_relay(ws: WebSocket, sid: str):
    """Authorised relay between the two peers of an approved session."""
    ctx, device = await _auth(ws)
    if device is None:
        return
    try:
        s = ctx.sessions.active_for_ws(device["id"], sid)
    except ServiceError:
        await ws.close(code=1008)
        return
    role, scopes = s["role"], s["scope_set"]
    await ws.accept()
    await ctx.manager.join_session(sid, role, ws)
    peer = ctx.manager.peer(sid, role)
    if peer is not None:
        await peer.send_text(json.dumps({"t": "peer", "state": "online"}))
        await ws.send_text(json.dumps({"t": "peer", "state": "online"}))
    ctx.audit.record(device["id"], "session_socket_open", f"{sid} role={role}")
    s_cfg = ctx.settings
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if s_cfg.clock() >= s["expires_at"]:
                await ws.close(code=4002, reason="session_expired")
                break
            target_ws = ctx.manager.peer(sid, role)
            if msg.get("text") is not None:
                raw = msg["text"]
                if len(raw.encode()) > s_cfg.max_text_message_bytes:
                    await ws.close(code=1009)
                    break
                if not ctx.limiter.allow(f"ws:{sid}:{role}", s_cfg.control_messages_per_second, 1.0):
                    await ws.send_text(json.dumps({"t": "error", "msg": "rate_limited"}))
                    continue
                reason = policy.check_text(role, scopes, raw)
                if reason:
                    ctx.audit.record(device["id"], "relay_denied", f"{sid} {reason}")
                    await ws.send_text(json.dumps({"t": "error", "msg": reason}))
                    continue
                if target_ws is not None:
                    await target_ws.send_text(raw)
            elif msg.get("bytes") is not None:
                data = msg["bytes"]
                if len(data) > s_cfg.max_binary_message_bytes:
                    await ws.close(code=1009)
                    break
                reason = policy.check_binary(role, scopes, data)
                if reason:
                    ctx.audit.record(device["id"], "relay_denied", f"{sid} {reason}")
                    await ws.send_text(json.dumps({"t": "error", "msg": reason}))
                    continue
                if target_ws is not None:
                    await target_ws.send_bytes(data)
    except Exception:
        pass
    finally:
        ctx.manager.leave_session(sid, role, ws)
        other = ctx.manager.peer(sid, role)
        if other is not None:
            try:
                await other.send_text(json.dumps({"t": "peer", "state": "offline"}))
            except Exception:
                pass
        ctx.audit.record(device["id"], "session_socket_closed", sid)
