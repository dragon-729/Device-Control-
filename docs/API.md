# Relay API

Live, always up to date documentation: `GET /docs` (Swagger UI), `GET /redoc`, `GET /openapi.json`.

All endpoints except `/health`, `/devices/register` and `/auth/token` need `Authorization: Bearer <access token>`.
Errors are `{"error": "<code>", "message": "<text>"}`; FastAPI validation errors are HTTP 422 with `detail`.

| Status | Meaning |
|--------|---------|
| 400 | Bad request / invalid scope |
| 401 | Missing, expired or invalid token; bad credentials; revoked device |
| 403 | Not allowed for this device (wrong role, scope too wide, bad invite) |
| 404 | Unknown pairing / session / code |
| 409 | State conflict (already paired, session already pending, not active) |
| 410 | Expired code or session request |
| 429 | Rate limited |

## Authentication
| Method & path | Body | Result |
|---------------|------|--------|
| `POST /api/v1/devices/register` | `{name, model, registration_token?}` | `201 {device_id, device_secret}` - the secret is shown once |
| `POST /api/v1/auth/token` | `{device_id, device_secret}` | `{access_token, token_type, expires_in}` |
| `GET /api/v1/devices/me` | - | `{id, name, model}` |
| `DELETE /api/v1/devices/me` | - | `204`; revokes the device, ends its sessions |
| `GET /api/v1/audit?limit=100` | - | security events for the caller |

## Pairing
| Method & path | Who | Notes |
|---------------|-----|-------|
| `POST /api/v1/pairing/codes` | target | `{code, expires_at, expires_in, qr_payload}`; replaces previous codes |
| `POST /api/v1/pairing/claim` `{code}` | controller | creates a **pending** pairing; max 8 attempts / 10 min per device |
| `GET /api/v1/pairings` | both | list with `role`, `status`, `scopes`, `peer` |
| `POST /api/v1/pairings/{id}/approve` `{scopes?}` | target | explicit authorisation |
| `POST /api/v1/pairings/{id}/reject` | target | |
| `PATCH /api/v1/pairings/{id}/scopes` `{scopes}` | target | change permissions |
| `DELETE /api/v1/pairings/{id}` | either | revoke; live sessions end immediately |

Scopes: `view`, `control`, `files`.

## Sessions
| Method & path | Who | Notes |
|---------------|-----|-------|
| `POST /api/v1/sessions` `{pairing_id, scopes}` | controller | `pending`, valid 120 s; scopes must be within the pairing grant |
| `GET /api/v1/sessions/pending` | target | requests waiting for approval |
| `GET /api/v1/sessions/history?limit=` | both | |
| `GET /api/v1/sessions/{id}` | both | includes `server_time` for clock-skew-free countdowns |
| `POST /api/v1/sessions/{id}/approve` `{scopes?}` | target | may only *narrow* scopes; starts the lifetime (default 1 h) |
| `POST /api/v1/sessions/{id}/reject` | target | |
| `POST /api/v1/sessions/{id}/end` | either | closes sockets (emergency disconnect path) |

## WebSockets
Authenticate with the `Authorization` header on the upgrade request (never in the URL).

* `GET /api/v1/ws/presence` - server → device events: `{"event": "pairing_request" | "pairing_update" | "session_request" | "session_update", "pairing"|"session": {...}}`. Client may send `ping`.
* `GET /api/v1/ws/sessions/{id}` - relay between the two session peers. Close codes: `1008` not authorised, `4001` session ended, `4002` expired, `1009` message too large.

### Relay policy (server enforced)
| Message | Direction | Needs scope |
|---------|-----------|-------------|
| `tap` `dtap` `long` `swipe` `scroll` `nav` `text` `key` `launch` `apps_req` | controller → target | `control` |
| `apps` | target → controller | `control` |
| `quality` | controller → target | `view` |
| binary `0x01` screen frame | target → controller | `view` |
| `file_*`, binary `0x02` file chunk | either | `files` |
| `info_req` / `info` | controller / target | none |
| `ack` `error` `terminate` `ping` `pong` | either | none |

Anything else is dropped and answered with `{"t":"error","msg":"<reason>"}`. Limits: 64 KB per text frame, 2 MB per binary frame, 60 control messages / second.
