from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends, Request

from ..models.schemas import ScopesRequest, SessionRequestBody, SessionView
from .deps import current_device, get_ctx

router = APIRouter(prefix="/api/v1", tags=["sessions"])


async def _notify_peer(ctx, sid: str, actor_id: str, event: str):
    s = ctx.sessions.db.one("SELECT target_id, controller_id FROM sessions WHERE id=?", (sid,))
    peer = s["controller_id"] if s["target_id"] == actor_id else s["target_id"]
    await ctx.manager.notify(peer, {"event": event, "session": ctx.sessions.view(sid, peer)})


@router.post("/sessions", response_model=SessionView, status_code=201)
async def request_session(body: SessionRequestBody, request: Request, device=Depends(current_device)):
    """Controller asks the device owner to start a remote session with the given scopes."""
    ctx = get_ctx(request)
    view = ctx.sessions.request(device, body.pairing_id, body.scopes)
    await _notify_peer(ctx, view["id"], device["id"], "session_request")
    return view


@router.get("/sessions/pending", response_model=List[SessionView])
async def pending(request: Request, device=Depends(current_device)):
    return get_ctx(request).sessions.pending_for_target(device["id"])


@router.get("/sessions/history", response_model=List[SessionView])
async def history(request: Request, limit: int = 100, device=Depends(current_device)):
    return get_ctx(request).sessions.history(device["id"], limit)


@router.get("/sessions/{sid}", response_model=SessionView)
async def get_session(sid: str, request: Request, device=Depends(current_device)):
    return get_ctx(request).sessions.view(sid, device["id"])


@router.post("/sessions/{sid}/approve", response_model=SessionView)
async def approve(sid: str, body: ScopesRequest, request: Request, device=Depends(current_device)):
    ctx = get_ctx(request)
    view = ctx.sessions.approve(device, sid, body.scopes)
    await _notify_peer(ctx, sid, device["id"], "session_update")
    return view


@router.post("/sessions/{sid}/reject", response_model=SessionView)
async def reject(sid: str, request: Request, device=Depends(current_device)):
    ctx = get_ctx(request)
    view = ctx.sessions.reject(device, sid)
    await _notify_peer(ctx, sid, device["id"], "session_update")
    return view


@router.post("/sessions/{sid}/end", response_model=SessionView)
async def end(sid: str, request: Request, device=Depends(current_device)):
    """Either side can end a session at any time (this is also the emergency-disconnect path)."""
    ctx = get_ctx(request)
    view = ctx.sessions.end(device, sid)
    await ctx.manager.close_session(sid, 4001, "session_ended")
    await _notify_peer(ctx, sid, device["id"], "session_update")
    return view
