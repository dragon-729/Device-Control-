# Security model

## Principles
1. **Consent first** - pairing and every session need the device owner's explicit approval; nothing is silent.
2. **Visible always** - persistent notification (cannot be swiped away) and red in-app banner during any session.
3. **Instantly revocable** - *Disconnect now* (banner / notification action), pause control, revoke pairing, unregister device.
4. **Defence in depth** - scopes are checked by the relay, by `SessionGuard` and by the executors.
5. **Collect nothing extra** - no IMEI/serial, contacts, location, clipboard reads, keystroke logging or stored screen content.

## Threats and mitigations

| Threat | Mitigation |
|--------|-----------|
| Someone guesses a pairing code | 8 chars from a 32-symbol alphabet, 2-minute life, single use, ≤ 8 claims / 10 min per device, 8 / min per IP, **and** the owner must still approve |
| Stolen / replayed access token | 15-minute JWT, in memory only, device revocation checked on every request |
| Stolen device secret | Stored only AES-256-GCM encrypted by a non-exportable Keystore key; server stores an HMAC-SHA256 hash, never the secret |
| Controller escalates permissions | Session scopes must be ⊆ pairing grant; owner can only narrow when approving; relay drops out-of-scope messages |
| Controller approves its own request through remote taps | Control is refused while DroidLink is in the foreground; approvals require biometric / screen-lock; `FLAG_SECURE` hides DroidLink windows in release builds |
| Passwords captured | Typing into password fields is blocked; typed text is never logged or stored; screen content is never stored |
| Malicious file name / oversize / tampering | Name sanitised, 2 GB cap, SAF-only destinations chosen by the receiver, SHA-256 verified, partial files deleted |
| Rogue pairing QR for another server | App refuses a QR whose server differs from the registered one |
| Brute force / abuse of the API | Global and per-route rate limits, strict Pydantic validation (`extra=forbid`), audit log |
| Session left open | ≤ 1 h lifetime, relay expiry task, ends when the socket drops |
| Cleartext traffic | `network_security_config` forbids it (debug builds allow only localhost / emulator host) |
| Backups leaking credentials | `allowBackup=false`, data-extraction rules exclude everything |

## Honest limitations
* **No end-to-end encryption yet.** The relay terminates TLS and could read session traffic. Run your own relay. E2E is on the roadmap.
* The in-memory rate limiter protects a single process only.
* A device with a compromised OS (root malware) can defeat any app-level control.
* Accessibility permission is powerful by nature. It is enabled only by the owner, and DroidLink uses it only during an approved session, but the service remains *enabled* in Android settings until the owner turns it off.
* Server registration is open unless you set `DROIDLINK_REGISTRATION_TOKEN`.

## What DroidLink will never do
Stealth/hidden mode, silent permission grants, auto-enabling accessibility, hidden recording or keylogging, persistence after the owner removes it, remote operation of Android's own permission or security dialogs, bypassing `FLAG_SECURE` or MediaProjection consent.

## Reporting a vulnerability
See [../SECURITY.md](../SECURITY.md).
