"""Token, secret and pairing-code primitives."""
from __future__ import annotations

import hashlib
import hmac
import secrets
import time
import uuid
from typing import Any, Dict

import jwt

CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I to avoid transcription errors
CODE_LENGTH = 8
ALGORITHM = "HS256"


class TokenError(Exception):
    """Raised for any invalid, expired or malformed token."""


def new_device_secret() -> str:
    return secrets.token_urlsafe(32)


def hash_secret(secret: str, server_key: str) -> str:
    """Keyed hash of a high-entropy device secret (HMAC-SHA256). Plain secrets are never stored."""
    return hmac.new(server_key.encode(), secret.encode(), hashlib.sha256).hexdigest()


def verify_secret(secret: str, stored_hash: str, server_key: str) -> bool:
    return hmac.compare_digest(hash_secret(secret, server_key), stored_hash)


def new_pairing_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


def normalize_code(raw: str) -> str:
    return "".join(ch for ch in raw.upper() if ch.isalnum())


def is_valid_code_format(code: str) -> bool:
    return len(code) == CODE_LENGTH and all(ch in CODE_ALPHABET for ch in code)


def issue_access_token(device_id: str, key: str, ttl: int, now: float | None = None) -> str:
    issued = int(now if now is not None else time.time())
    payload = {"sub": device_id, "iat": issued, "exp": issued + ttl, "jti": uuid.uuid4().hex, "typ": "access"}
    return jwt.encode(payload, key, algorithm=ALGORITHM)


def decode_access_token(token: str, key: str, now: float | None = None) -> Dict[str, Any]:
    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=[ALGORITHM],
            options={"require": ["sub", "exp", "iat", "typ"], "verify_exp": now is None},
        )
    except jwt.PyJWTError as exc:  # expired, bad signature, malformed...
        raise TokenError(str(exc)) from exc
    if now is not None and claims["exp"] <= now:
        raise TokenError("Signature has expired")
    if claims.get("typ") != "access":
        raise TokenError("wrong token type")
    return claims
