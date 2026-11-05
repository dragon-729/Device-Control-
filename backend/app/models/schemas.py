"""Pydantic request/response models (strict validation of all inputs)."""
from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RegisterRequest(_Strict):
    name: str = Field(min_length=1, max_length=64)
    model: str = Field(min_length=1, max_length=64)
    registration_token: Optional[str] = Field(default=None, max_length=128)


class RegisterResponse(BaseModel):
    device_id: str
    device_secret: str


class TokenRequest(_Strict):
    device_id: str = Field(min_length=8, max_length=64)
    device_secret: str = Field(min_length=16, max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class ClaimRequest(_Strict):
    code: str = Field(min_length=8, max_length=16)


class ScopesRequest(_Strict):
    scopes: Optional[List[str]] = Field(default=None, max_length=3)


class SessionRequestBody(_Strict):
    pairing_id: str = Field(min_length=8, max_length=64)
    scopes: List[str] = Field(min_length=1, max_length=3)


class PeerInfo(BaseModel):
    id: Optional[str] = None
    name: Optional[str] = None
    model: Optional[str] = None


class PairingView(BaseModel):
    id: str
    role: str
    status: str
    scopes: List[str]
    peer: PeerInfo
    created_at: float
    updated_at: float


class SessionView(BaseModel):
    id: str
    pairing_id: str
    role: str
    status: str
    scopes: List[str]
    peer: PeerInfo
    created_at: float
    expires_at: float
    ended_at: Optional[float] = None
    end_reason: Optional[str] = None
    server_time: float = 0.0


class CodeResponse(BaseModel):
    code: str
    expires_at: float
    expires_in: int
    qr_payload: str


class AuditEntry(BaseModel):
    ts: float
    event: str
    detail: Optional[str] = None
