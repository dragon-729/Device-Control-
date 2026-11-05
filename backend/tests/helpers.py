from __future__ import annotations

from app.core.audit import AuditLog
from app.core.config import Settings
from app.core.db import Database
from app.core.rate_limit import RateLimiter
from app.services.auth_service import AuthService
from app.services.pairing_service import PairingService
from app.services.session_service import SessionService


class FakeClock:
    def __init__(self, start: float = 1_700_000_000.0):
        self.t = start

    def __call__(self) -> float:
        return self.t

    def advance(self, seconds: float) -> None:
        self.t += seconds


class Services:
    def __init__(self, **overrides):
        self.clock = FakeClock()
        self.settings = Settings(secret_key="test-secret-key-0123456789abcdef0123456789", db_path=":memory:",
                                 clock=self.clock, **overrides)
        self.db = Database(":memory:")
        self.audit = AuditLog(self.db, self.clock)
        self.limiter = RateLimiter(self.clock)
        self.auth = AuthService(self.db, self.settings, self.audit)
        self.pairings = PairingService(self.db, self.settings, self.audit)
        self.sessions = SessionService(self.db, self.settings, self.audit, self.pairings)

    def new_device(self, name: str = "Pixel 8", model: str = "Pixel 8"):
        reg = self.auth.register(name, model, None)
        return self.auth.authenticate(self.auth.issue_token(reg["device_id"], reg["device_secret"])["access_token"]), reg

    def paired(self, scopes=None):
        target, _ = self.new_device("Target Phone", "Galaxy S23")
        controller, _ = self.new_device("Controller Phone", "Pixel 8")
        code = self.pairings.create_code(target)["code"]
        view = self.pairings.claim(controller, code)
        self.pairings.approve(target, view["id"], scopes)
        return target, controller, view["id"]
