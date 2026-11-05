from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from ..models.schemas import AuditEntry, RegisterRequest, RegisterResponse, TokenRequest, TokenResponse
from .deps import current_device, enforce, get_ctx

router = APIRouter(prefix="/api/v1", tags=["auth"])


@router.post("/devices/register", response_model=RegisterResponse, status_code=201)
async def register(body: RegisterRequest, request: Request):
    """Register this device; returns a long-lived device secret (store it in the Android Keystore)."""
    enforce(request, "register", 10)
    return get_ctx(request).auth.register(body.name, body.model, body.registration_token)


@router.post("/auth/token", response_model=TokenResponse)
async def token(body: TokenRequest, request: Request):
    """Exchange device credentials for a short-lived access token."""
    enforce(request, "token", 30)
    return get_ctx(request).auth.issue_token(body.device_id, body.device_secret)


@router.get("/devices/me")
async def me(device=Depends(current_device)):
    return {"id": device["id"], "name": device["name"], "model": device["model"]}


@router.delete("/devices/me", status_code=204)
async def unregister(request: Request, device=Depends(current_device)):
    """Revoke this device: ends all of its pairings and sessions."""
    ctx = get_ctx(request)
    ctx.db.run("UPDATE devices SET revoked=1 WHERE id=?", (device["id"],))
    rows = ctx.db.query(
        "SELECT id, target_id, controller_id FROM pairings WHERE (target_id=? OR controller_id=?) "
        "AND status IN ('pending','active')", (device["id"], device["id"]))
    for p in rows:
        ctx.db.run("UPDATE pairings SET status='revoked', updated_at=? WHERE id=?", (ctx.settings.clock(), p["id"]))
        for sid in ctx.sessions.end_for_pairing(p["id"], "device unregistered"):
            await ctx.manager.close_session(sid)
    ws = ctx.manager.presence.pop(device["id"], None)
    if ws is not None:
        try:
            await ws.close(code=4003)
        except Exception:
            pass
    ctx.audit.record(device["id"], "device_unregistered")


@router.get("/audit", response_model=list[AuditEntry])
async def audit(request: Request, limit: int = 100, device=Depends(current_device)):
    """Security events for the calling device."""
    return get_ctx(request).audit.for_device(device["id"], limit)
