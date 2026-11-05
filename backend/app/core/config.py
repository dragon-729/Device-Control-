"""Runtime configuration, read from environment variables (never hard-coded secrets)."""
from __future__ import annotations

import logging
import os
import secrets
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

log = logging.getLogger("droidlink.config")


@dataclass
class Settings:
    secret_key: str = ""
    db_path: str = "droidlink.db"
    access_token_ttl: int = 900            # seconds; short-lived access tokens
    pairing_code_ttl: int = 120            # seconds
    session_request_ttl: int = 120         # seconds a pending session request stays valid
    session_max_seconds: int = 3600        # hard cap for one remote session
    registration_token: Optional[str] = None   # optional invite code required to register
    global_rate_per_minute: int = 240
    max_devices: int = 10_000
    max_text_message_bytes: int = 64 * 1024
    max_binary_message_bytes: int = 2 * 1024 * 1024
    control_messages_per_second: int = 60
    clock: Callable[[], float] = field(default=time.time)

    @classmethod
    def from_env(cls) -> "Settings":
        secret = os.environ.get("DROIDLINK_SECRET_KEY", "")
        if not secret:
            secret = secrets.token_urlsafe(48)
            log.warning(
                "DROIDLINK_SECRET_KEY is not set: generated an ephemeral key. "
                "All access tokens become invalid when the server restarts."
            )
        return cls(
            secret_key=secret,
            db_path=os.environ.get("DROIDLINK_DB_PATH", "droidlink.db"),
            access_token_ttl=int(os.environ.get("DROIDLINK_ACCESS_TTL", "900")),
            session_max_seconds=int(os.environ.get("DROIDLINK_SESSION_MAX_SECONDS", "3600")),
            registration_token=os.environ.get("DROIDLINK_REGISTRATION_TOKEN") or None,
            global_rate_per_minute=int(os.environ.get("DROIDLINK_RATE_LIMIT_PER_MIN", "240")),
        )
