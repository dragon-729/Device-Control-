from __future__ import annotations

from typing import Any, Dict, Optional

from fastapi import Request

from ..context import AppContext
from ..services.errors import TooManyRequests, Unauthorized


def get_ctx(request: Request) -> AppContext:
    return request.app.state.ctx


def parse_bearer(header: Optional[str]) -> str:
    if not header:
        raise Unauthorized("missing Authorization header")
    parts = header.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].strip():
        raise Unauthorized("malformed Authorization header")
    return parts[1].strip()


def current_device(request: Request) -> Dict[str, Any]:
    ctx = get_ctx(request)
    device = ctx.auth.authenticate(parse_bearer(request.headers.get("authorization")))
    if not ctx.limiter.allow(f"dev:{device['id']}", ctx.settings.global_rate_per_minute, 60):
        raise TooManyRequests("too many requests")
    return device


def client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def enforce(request: Request, bucket: str, limit: int, window: float = 60.0) -> None:
    ctx = get_ctx(request)
    if not ctx.limiter.allow(f"{bucket}:{client_ip(request)}", limit, window):
        ctx.audit.record(None, "rate_limited", f"{bucket} from {client_ip(request)}")
        raise TooManyRequests("too many requests, slow down")
