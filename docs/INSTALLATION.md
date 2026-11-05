# Installation

## Requirements

| Component | Requirement |
|-----------|-------------|
| Android app build | JDK 17, Android Studio Ladybug (2024.2) or newer, Android SDK Platform 35 |
| Android devices | Android 8.0 (API 26) or newer |
| Relay server | Python 3.11+, a host reachable by both phones, a TLS certificate (Let's Encrypt is fine) |

## 1. Android app

### Android Studio
1. `git clone https://github.com/hgcheat094-max/DroidLink.git`
2. *File → Open…* and choose the folder. Studio reads `gradle/wrapper/gradle-wrapper.properties` and downloads Gradle 8.9.
3. If Studio asks to install SDK 35 or build tools, accept.
4. Select the `app` run configuration and a device (emulator API 26+ works for everything except camera QR scanning).

### Command line
The binary `gradle-wrapper.jar` is not part of the source drop. Generate it once:

```bash
./scripts/bootstrap-wrapper.sh      # needs a local Gradle >= 8.9
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Or call a system Gradle directly: `gradle :app:assembleDebug`.

### Release build and signing
`assembleRelease` produces an **unsigned** APK unless these environment variables are set:

```
DROIDLINK_KEYSTORE_PATH  DROIDLINK_KEYSTORE_PASSWORD  DROIDLINK_KEY_ALIAS  DROIDLINK_KEY_PASSWORD
```

Never commit keystores or passwords. In GitHub, store the keystore as a base64 secret `DROIDLINK_KEYSTORE_BASE64` plus the three secrets above; `release.yml` uses them.

## 2. Relay server

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
export DROIDLINK_SECRET_KEY="<long random string>"       # required in production
export DROIDLINK_DB_PATH=/var/lib/droidlink/droidlink.db
export DROIDLINK_REGISTRATION_TOKEN="<invite code>"      # optional but recommended on a public server
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### TLS (required for release builds)
Example Caddy reverse proxy (automatic certificates):

```
relay.example.com {
    reverse_proxy 127.0.0.1:8000
}
```

Caddy upgrades WebSockets automatically. For nginx add `proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";` and a long `proxy_read_timeout`.

### Docker
```bash
cd backend && docker build -t droidlink-relay .
docker run -d -p 8000:8000 -e DROIDLINK_SECRET_KEY=... -v droidlink-data:/data droidlink-relay
```

### Environment variables

| Variable | Default | Meaning |
|----------|---------|---------|
| `DROIDLINK_SECRET_KEY` | random per start (warning logged) | Signs access tokens and keys the device-secret hash. Set it, or tokens die on restart |
| `DROIDLINK_DB_PATH` | `droidlink.db` | SQLite file |
| `DROIDLINK_ACCESS_TTL` | `900` | Access token lifetime (seconds) |
| `DROIDLINK_SESSION_MAX_SECONDS` | `3600` | Hard cap for a single remote session |
| `DROIDLINK_REGISTRATION_TOKEN` | unset | If set, devices must present this invite code to register |
| `DROIDLINK_RATE_LIMIT_PER_MIN` | `240` | Global per-IP and per-device request limit |

## 3. Pointing the app at your server
Enter the HTTPS address on the *Connect this device* screen. For emulator development against a local server over HTTP, use a **debug** build and `http://10.0.2.2:8000`.

## 4. Permissions the app asks for

| Permission / setting | Why | When |
|----------------------|-----|------|
| Notifications (Android 13+) | Persistent "remote session active" indicator | Onboarding |
| Camera | Scan a pairing QR (optional, enter the code manually instead) | When you tap *Scan QR* |
| Screen capture consent | Let an approved device see this screen | Every approved session that includes *View* |
| Accessibility service | Let an approved device tap / type | Enabled by you in Android settings; only active during a session |
| Biometric / screen lock | Confirm it is really you approving | Approvals, optional app lock |

DroidLink does **not** request storage permissions (files are chosen with the system picker), location, contacts, microphone, SMS or `QUERY_ALL_PACKAGES`.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| "No connection to the server" | Check the URL, TLS certificate and that the relay is reachable from the phone's network |
| Controller sees "accessibility service disabled" | On the target: *Settings → Accessibility → DroidLink Remote Control → On* |
| Black screen in the viewer | The foreground app uses `FLAG_SECURE` (e.g. banking apps) or DroidLink itself is in front |
| Session request never arrives | The target must have DroidLink open (Android limits background networking); the request expires after 2 minutes |
| Gradle sync fails on `libs.versions.toml` | Use Android Studio Ladybug+ / Gradle 8.9 |
