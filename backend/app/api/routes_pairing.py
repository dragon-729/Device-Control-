from __future__ import annotations

from typing import List
from urllib.parse import quote

from fastapi import APIRouter, Depends, Request

from ..models.schemas import ClaimRequest, CodeResponse, PairingView, ScopesRequest
from ..services.errors import TooManyRequests
from .deps import current_device, enforce, get_ctx

router = APIRouter(prefix="/api/v1", tags=["pairing"])


async def _notify_peer(ctx, pairing_id: str, actor_id: str, event: str):
    p = ctx.pairings.raw(pairing_id)
    peer = ctx.pairings.peer_id(p, actor_id)
    await ctx.manager.notify(peer, {"event": event, "pairing": ctx.pairings.view(pairing_id, peer)})


@router.post("/pairing/codes", response_model=CodeResponse, status_code=201)
async def create_code(request: Request, device=Depends(current_device)):
    """The device that wants to be controlled creates a short-lived, single-use pairing code."""
    enforce(request, "code", 10)
    ctx = get_ctx(request)
    res = ctx.pairings.create_code(device)
    base = str(request.base_url).rstrip("/")
    res["qr_payload"] = f"droidlink://pair?code={res['code']}&server={quote(base, safe='')}"
    return res


@router.post("/pairing/claim", response_model=PairingView, status_code=201)
async def claim(body: ClaimRequest, request: Request, device=Depends(current_device)):
    """The controlling device submits the code; the owner must still approve explicitly."""
    enforce(request, "claim", 8)  # brute-force protection on top of single-use + 8 char codes
    ctx = get_ctx(request)
    if not ctx.limiter.allow(f"claim-dev:{device['id']}", 8, 600):
        ctx.audit.record(device["id"], "pairing_claim_throttled")
        raise TooManyRequests("too many pairing attempts")
    view = ctx.pairings.claim(device, body.code)
    await _notify_peer(ctx, view["id"], device["id"], "pairing_request")
    return view


@router.get("/pairings", response_model=List[PairingView])
async def list_pairings(request: Request, device=Depends(current_device)):
    return get_ctx(request).pairings.list_for(device["id"])


@router.post("/pairings/{pairing_id}/approve", response_model=PairingView)
async def approve(pairing_id: str, body: ScopesRequest, request: Request, device=Depends(current_device)):
    ctx = get_ctx(request)
    view = ctx.pairings.approve(device, pairing_id, body.scopes)
    await _notify_peer(ctx, pairing_id, device["id"], "pairing_update")
    return view


@router.post("/pairings/{pairing_id}/reject", response_model=PairingView)
async def reject(pairing_id: str, request: Request, device=Depends(current_device)):
    ctx = get_ctx(request)
    view = ctx.pairings.reject(device, pairing_id)
    await _notify_peer(ctx, pairing_id, device["id"], "pairing_update")
    return view


@router.patch("/pairings/{pairing_id}/scopes", response_model=PairingView)
async def change_scopes(pairing_id: str, body: ScopesRequest, request: Request, device=Depends(current_device)):
    ctx = get_ctx(request)
    view = ctx.pairings.update_scopes(device, pairing_id, body.scopes or [])
    await _notify_peer(ctx, pairing_id, device["id"], "pairing_update")
    return view


@router.delete("/pairings/{pairing_id}", response_model=PairingView)
async def revoke(pairing_id: str, request: Request, device=Depends(current_device)):
    """Revoke a pairing (either side). Any live session on it is terminated immediately."""
    ctx = get_ctx(request)
    view = ctx.pairings.revoke(device, pairing_id)
    for sid in ctx.sessions.end_for_pairing(pairing_id, "pairing revoked"):
        await ctx.manager.close_session(sid, 4001, "pairing_revoked")
    await _notify_peer(ctx, pairing_id, device["id"], "pairing_update")
    return view
