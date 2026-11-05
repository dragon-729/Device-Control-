"""DroidLink relay server entry point.

Run:  uvicorn app.main:app --host 0.0.0.0 --port 8000
Interactive API docs (OpenAPI / Swagger): /docs   (ReDoc: /redoc)
Always terminate TLS in front of this server (reverse proxy or uvicorn --ssl-*).
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .api import routes_auth, routes_pairing, routes_sessions, routes_ws
from .api.deps import client_ip
from .context import AppContext, build_context
from .core.config import Settings
from .services.errors import ServiceError

log = logging.getLogger("droidlink")


async def _expiry_loop(ctx: AppContext) -> None:
    while True:
        await asyncio.sleep(5)
        try:
            for row in ctx.sessions.expire_due():
                await ctx.manager.close_session(row["id"], 4002, "session_expired")
                for dev in (row["target_id"], row["controller_id"]):
                    await ctx.manager.notify(dev, {"event": "session_update", "session": ctx.sessions.view(row["id"], dev)})
        except Exception:  # keep the loop alive
            log.exception("expiry loop error")


def create_app(settings: Optional[Settings] = None) -> FastAPI:
    settings = settings or Settings.from_env()
    ctx = build_context(settings)

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):
        task = asyncio.create_task(_expiry_loop(ctx))
        try:
            yield
        finally:
            task.cancel()
            ctx.db.close()

    app = FastAPI(
        title="DroidLink Relay API",
        version="1.0.0",
        description="Pairing, authorisation and relay service for consent-based Android remote sessions.",
        lifespan=lifespan,
    )
    app.state.ctx = ctx

    @app.exception_handler(ServiceError)
    async def service_error_handler(_: Request, exc: ServiceError):
        return JSONResponse(status_code=exc.status_code, content={"error": exc.code, "message": exc.message})

    @app.middleware("http")
    async def global_rate_limit(request: Request, call_next):
        if not ctx.limiter.allow(f"ip:{client_ip(request)}", settings.global_rate_per_minute, 60):
            return JSONResponse(status_code=429, content={"error": "rate_limited", "message": "too many requests"})
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/health", tags=["meta"])
    async def health():
        return {"status": "ok"}

    app.include_router(routes_auth.router)
    app.include_router(routes_pairing.router)
    app.include_router(routes_sessions.router)
    app.include_router(routes_ws.router)
    return app


def __getattr__(name: str):
    """Lazily build the default ASGI app so `uvicorn app.main:app` works without side effects on import."""
    if name == "app":
        global _default_app
        if _default_app is None:
            _default_app = create_app()
        return _default_app
    raise AttributeError(name)


_default_app: Optional[FastAPI] = None
