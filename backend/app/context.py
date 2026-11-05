"""Application context: wires services together (also used by tests)."""
from __future__ import annotations

from dataclasses import dataclass

from .core.audit import AuditLog
from .core.config import Settings
from .core.db import Database
from .core.rate_limit import RateLimiter
from .services.auth_service import AuthService
from .services.connection_manager import ConnectionManager
from .services.pairing_service import PairingService
from .services.session_service import SessionService


@dataclass
class AppContext:
    settings: Settings
    db: Database
    audit: AuditLog
    limiter: RateLimiter
    auth: AuthService
    pairings: PairingService
    sessions: SessionService
    manager: ConnectionManager


def build_context(settings: Settings) -> AppContext:
    db = Database(settings.db_path)
    audit = AuditLog(db, settings.clock)
    pairings = PairingService(db, settings, audit)
    return AppContext(
        settings=settings,
        db=db,
        audit=audit,
        limiter=RateLimiter(settings.clock),
        auth=AuthService(db, settings, audit),
        pairings=pairings,
        sessions=SessionService(db, settings, audit, pairings),
        manager=ConnectionManager(),
    )
