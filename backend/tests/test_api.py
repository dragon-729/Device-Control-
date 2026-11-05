"""HTTP + WebSocket tests (require fastapi and httpx: pip install -r requirements-dev.txt)."""
import unittest

from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import create_app


def make_client(**kw):
    settings = Settings(secret_key="t" * 48, db_path=":memory:", **kw)
    return TestClient(create_app(settings))


def register(client, name="Phone"):
    r = client.post("/api/v1/devices/register", json={"name": name, "model": "Model X"})
    assert r.status_code == 201, r.text
    reg = r.json()
    t = client.post("/api/v1/auth/token", json={"device_id": reg["device_id"], "device_secret": reg["device_secret"]})
    assert t.status_code == 200, t.text
    return reg, {"Authorization": f"Bearer {t.json()['access_token']}"}


class ApiTests(unittest.TestCase):
    def test_health_and_openapi(self):
        with make_client() as c:
            self.assertEqual(c.get("/health").json(), {"status": "ok"})
            self.assertIn("/api/v1/sessions", c.get("/openapi.json").json()["paths"])

    def test_unauthorized_requests_rejected(self):
        with make_client() as c:
            self.assertEqual(c.get("/api/v1/pairings").status_code, 401)
            self.assertEqual(c.get("/api/v1/pairings", headers={"Authorization": "Bearer junk"}).status_code, 401)
            self.assertEqual(c.get("/api/v1/pairings", headers={"Authorization": "Basic abc"}).status_code, 401)

    def test_invalid_credentials(self):
        with make_client() as c:
            r = c.post("/api/v1/auth/token", json={"device_id": "x" * 32, "device_secret": "y" * 32})
            self.assertEqual(r.status_code, 401)

    def test_input_validation(self):
        with make_client() as c:
            self.assertEqual(c.post("/api/v1/devices/register", json={"name": "", "model": "m"}).status_code, 422)
            self.assertEqual(c.post("/api/v1/devices/register", json={"name": "a", "model": "m", "x": 1}).status_code, 422)

    def test_pairing_and_session_flow_over_http(self):
        with make_client() as c:
            _, target = register(c, "Target")
            _, ctrl = register(c, "Controller")
            code = c.post("/api/v1/pairing/codes", headers=target).json()["code"]
            claim = c.post("/api/v1/pairing/claim", headers=ctrl, json={"code": code})
            self.assertEqual(claim.status_code, 201)
            pid = claim.json()["id"]
            # controller cannot approve itself
            self.assertEqual(c.post(f"/api/v1/pairings/{pid}/approve", headers=ctrl, json={}).status_code, 403)
            self.assertEqual(c.post(f"/api/v1/pairings/{pid}/approve", headers=target, json={}).status_code, 200)
            s = c.post("/api/v1/sessions", headers=ctrl, json={"pairing_id": pid, "scopes": ["view", "control"]})
            self.assertEqual(s.status_code, 201)
            sid = s.json()["id"]
            self.assertEqual(c.post(f"/api/v1/sessions/{sid}/approve", headers=target, json={}).status_code, 200)
            self.assertEqual(c.post(f"/api/v1/sessions/{sid}/end", headers=target).json()["status"], "ended")

    def test_claim_rate_limited(self):
        with make_client() as c:
            _, ctrl = register(c)
            codes = [c.post("/api/v1/pairing/claim", headers=ctrl, json={"code": "ABCD2345"}).status_code for _ in range(10)]
            self.assertIn(429, codes)

    def test_websocket_relay_enforces_scopes(self):
        with make_client() as c:
            _, target = register(c, "Target")
            _, ctrl = register(c, "Controller")
            code = c.post("/api/v1/pairing/codes", headers=target).json()["code"]
            pid = c.post("/api/v1/pairing/claim", headers=ctrl, json={"code": code}).json()["id"]
            c.post(f"/api/v1/pairings/{pid}/approve", headers=target, json={})
            sid = c.post("/api/v1/sessions", headers=ctrl, json={"pairing_id": pid, "scopes": ["view"]}).json()["id"]
            c.post(f"/api/v1/sessions/{sid}/approve", headers=target, json={})
            with c.websocket_connect(f"/api/v1/ws/sessions/{sid}", headers=target) as wt, \
                    c.websocket_connect(f"/api/v1/ws/sessions/{sid}", headers=ctrl) as wc:
                wt.receive_json()  # peer online
                wc.receive_json()
                wc.send_text('{"t":"tap","x":0.1,"y":0.1}')  # no control scope -> denied
                self.assertEqual(wc.receive_json()["msg"], "scope_control_required")
                wt.send_bytes(b"\x01frame")  # target video -> controller
                self.assertEqual(wc.receive_bytes(), b"\x01frame")

    def test_websocket_requires_auth(self):
        from starlette.websockets import WebSocketDisconnect
        with make_client() as c:
            with self.assertRaises(WebSocketDisconnect):
                with c.websocket_connect("/api/v1/ws/presence"):
                    pass
