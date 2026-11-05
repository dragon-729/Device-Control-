# Architecture

## Goals
1. The device owner is always in control (approval, visibility, instant disconnect).
2. Authorisation is enforced in more than one place (phone *and* relay).
3. Small, testable pieces: pure logic is separated from Android classes.

## App layers (`app/src/main/java/com/droidlink`)

```
presentation/  Compose screens + ViewModels (MVVM), theme, shared components
domain/        model/ (pure data), repository/ (interfaces), usecase/
data/          local/ (Room entities, DAOs, database), repository/ (implementations, mappers)
network/       ApiClient (REST), SessionSocket (WebSocket), Protocol (wire formats), NetworkMonitor
security/      KeystoreManager, SecureStorage, InputValidator, SessionGuard, Reauthenticator
services/      HostSessionService, ScreenCaptureEngine, RemoteInputExecutor, AccessibilityService,
               FileTransferManager, PresenceCoordinator, DeviceInfoCollector
navigation/    Routes + NavHost
```

Dependency rule: `presentation → domain ← data`; `domain` knows nothing about Android UI. Dependency injection is a hand-written `AppContainer` (no annotation processing besides Room), which keeps the build simple.

## Roles
Every pairing has two roles: the **target** (the device that is viewed / controlled) and the **controller**. The same phone can be a target for one pairing and a controller for another.

## Flows

### Registration and authentication
```
App ── POST /devices/register {name, model} ──► relay     device_id + device_secret (shown once)
App stores both AES-GCM-encrypted with a Keystore key
App ── POST /auth/token {device_id, device_secret} ──► JWT (15 min), cached in memory only
401 on any call → refresh token once → retry; credentials rejected → local wipe → back to sign-in
```

### Pairing
```
Target ── POST /pairing/codes ──► 8-char code (2 min, single use) + QR  droidlink://pair?code=…&server=…
Controller ── POST /pairing/claim {code} ──► pairing = pending      (presence push to target)
Target owner reviews, picks permissions, confirms identity ── POST /pairings/{id}/approve ──► active
```

### Session
```
Controller ── POST /sessions {pairing_id, scopes} ──► pending   (scopes ⊆ pairing scopes)
Target: dialog → (biometric) → untick scopes → (Android screen-capture consent) → POST /sessions/{id}/approve
Target starts HostSessionService (foreground + notification), both sides open WSS /ws/sessions/{id}
Session ends: either side, owner emergency stop, expiry (≤ 1 h), pairing revoked, socket lost
```

### Wire protocol (inside the session WebSocket)
* **Text frames** - JSON `RemoteMessage` (`t` = type). Types are listed in `network/Protocol.kt` and enforced by `backend/app/services/relay_policy.py`.
* **Binary frames** - first byte selects the kind: `0x01` screen frame `[w:int32][h:int32][JPEG]` (target → controller), `0x02` file chunk `[idLen:u8][id][bytes]` (either direction).

## Screen streaming
`ScreenCaptureEngine` creates one `VirtualDisplay` from the consented `MediaProjection`, reads RGBA frames from an `ImageReader`, JPEG-encodes them and pushes them to the socket. `AdaptiveStreamController` watches `WebSocket.queueSize()`: a growing queue lowers JPEG quality and frame rate, a long calm period raises them again, and a very deep queue drops frames. Rotation is handled by `DisplayListener` → `VirtualDisplay.resize()` + new `Surface` (Android 14 forbids creating a second display). The viewer always decodes only the newest frame.

## Remote input
`RemoteInputExecutor` is the only code that touches the `AccessibilityService`. Every command first passes `SessionGuard.isAllowed(CONTROL)` (active, unexpired, owner-granted). It refuses to operate DroidLink's own windows (so a controller cannot press *Approve* or *Disconnect*), refuses text input into password fields, and never logs typed text. Coordinates are normalised 0..1 and clamped on the target.

## File transfer
`FileTransferManager` is symmetric. Offer → owner accepts and picks the destination with `CreateDocument` (SAF) → 48 KB chunks with back-pressure → `file_done` with SHA-256 → receiver verifies and acknowledges. Mismatch, oversize, cancel or connection loss deletes the partial file and marks the record failed. Names from peers are sanitised.

## Backend (`backend/app`)
```
api/        FastAPI routers (auth, pairing, sessions, websocket) + dependencies (auth, rate limits)
core/       config, security primitives (JWT, hashing, codes), SQLite wrapper, rate limiter, audit log
models/     Pydantic request / response schemas (extra fields forbidden)
services/   framework-independent business rules (auth, pairing, sessions, relay policy, connection manager)
main.py     app factory, global rate limit middleware, expiry task
```
Services contain no FastAPI code, so most rules are unit-tested without an HTTP stack.

## Data stored
| Where | What |
|-------|------|
| Phone, Keystore-encrypted prefs | device id, device secret, server URL, device name |
| Phone, Room | pairing cache, connection history, transfer history, security log (all clearable) |
| Phone, DataStore | theme, toggles, stream defaults |
| Relay SQLite | devices (hashed secrets), pairings, sessions, audit events. **No screen content, files or keystrokes are stored** |

## Known limits of the design
* The relay forwards session traffic in the clear (inside TLS). A malicious relay operator could read it. Self-host, or wait for the end-to-end roadmap item.
* Presence works only while the app is visible (no push service yet).
* One session per pairing at a time; a dropped connection ends the session.
* In-memory rate limiter → run a single relay process, or replace with Redis.
