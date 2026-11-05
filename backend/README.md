# DroidLink relay (FastAPI)

Authentication, pairing, session authorisation and a scope-enforcing WebSocket relay for DroidLink.

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt            # runtime
pip install -r requirements-dev.txt        # + pytest, httpx
export DROIDLINK_SECRET_KEY="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
uvicorn app.main:app --reload              # http://localhost:8000/docs
pytest -q
```

Layout: `app/api` (routers), `app/core` (config, tokens, SQLite, rate limiter, audit), `app/models` (Pydantic schemas),
`app/services` (business rules, framework independent), `app/main.py` (app factory).

Configuration and TLS: see [../docs/INSTALLATION.md](../docs/INSTALLATION.md). API reference: [../docs/API.md](../docs/API.md).

Production notes: set `DROIDLINK_SECRET_KEY`, terminate TLS in front, run **one** process (in-memory rate limiter and
WebSocket registry), back up the SQLite file, and consider `DROIDLINK_REGISTRATION_TOKEN`.
