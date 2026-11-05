"""Server-side authorization of every message relayed between the two peers of a session.

The relay cannot read file contents or video, but it *does* enforce who may send what:
control input requires the `control` scope, video requires `view`, file frames require `files`.
"""
from __future__ import annotations

import json
from typing import Optional, Set

SCOPE_VIEW = "view"
SCOPE_CONTROL = "control"
SCOPE_FILES = "files"
ALL_SCOPES = (SCOPE_VIEW, SCOPE_CONTROL, SCOPE_FILES)

ROLE_CONTROLLER = "controller"
ROLE_TARGET = "target"

BIN_FRAME = 0x01
BIN_FILE_CHUNK = 0x02

CONTROLLER_CONTROL = {"tap", "dtap", "long", "swipe", "scroll", "nav", "text", "key", "launch", "apps_req"}
CONTROLLER_VIEW = {"quality"}
CONTROLLER_INFO = {"info_req"}
TARGET_CONTROL = {"apps"}
TARGET_INFO = {"info"}
FILE_TYPES = {"file_offer", "file_accept", "file_reject", "file_done", "file_cancel"}
COMMON = {"ack", "error", "terminate", "ping", "pong"}


def check_text(role: str, scopes: Set[str], raw: str) -> Optional[str]:
    """Return None when allowed, otherwise a short reason string."""
    try:
        msg = json.loads(raw)
    except (ValueError, TypeError):
        return "invalid_json"
    if not isinstance(msg, dict) or not isinstance(msg.get("t"), str):
        return "missing_type"
    t = msg["t"]
    if t in COMMON or t in CONTROLLER_INFO and role == ROLE_CONTROLLER or t in TARGET_INFO and role == ROLE_TARGET:
        return None
    if t in FILE_TYPES:
        return None if SCOPE_FILES in scopes else "scope_files_required"
    if role == ROLE_CONTROLLER:
        if t in CONTROLLER_CONTROL:
            return None if SCOPE_CONTROL in scopes else "scope_control_required"
        if t in CONTROLLER_VIEW:
            return None if SCOPE_VIEW in scopes else "scope_view_required"
    else:
        if t in TARGET_CONTROL:
            return None if SCOPE_CONTROL in scopes else "scope_control_required"
    return "type_not_allowed"


def check_binary(role: str, scopes: Set[str], data: bytes) -> Optional[str]:
    if not data:
        return "empty_frame"
    kind = data[0]
    if kind == BIN_FRAME:
        if role != ROLE_TARGET:
            return "type_not_allowed"
        return None if SCOPE_VIEW in scopes else "scope_view_required"
    if kind == BIN_FILE_CHUNK:
        return None if SCOPE_FILES in scopes else "scope_files_required"
    return "type_not_allowed"
