import unittest

from app.services.errors import Conflict, Expired, Forbidden, NotFound, ServiceError, Unauthorized
from tests.helpers import Services


class AuthServiceTests(unittest.TestCase):
    def test_register_and_token(self):
        s = Services()
        reg = s.auth.register("My Phone", "Pixel 8", None)
        tok = s.auth.issue_token(reg["device_id"], reg["device_secret"])
        self.assertEqual(s.auth.authenticate(tok["access_token"])["id"], reg["device_id"])
        stored = s.db.one("SELECT secret_hash FROM devices")["secret_hash"]
        self.assertNotEqual(stored, reg["device_secret"])

    def test_bad_credentials_rejected_uniformly(self):
        s = Services()
        reg = s.auth.register("My Phone", "Pixel 8", None)
        with self.assertRaises(Unauthorized):
            s.auth.issue_token(reg["device_id"], "wrong" * 8)
        with self.assertRaises(Unauthorized):
            s.auth.issue_token("nonexistent", "wrong" * 8)

    def test_expired_token(self):
        s = Services()
        _, reg = s.new_device()
        token = s.auth.issue_token(reg["device_id"], reg["device_secret"])["access_token"]
        s.clock.advance(s.settings.access_token_ttl + 1)
        with self.assertRaises(Unauthorized):
            s.auth.authenticate(token)

    def test_revoked_device_cannot_authenticate(self):
        s = Services()
        dev, reg = s.new_device()
        token = s.auth.issue_token(reg["device_id"], reg["device_secret"])["access_token"]
        s.db.run("UPDATE devices SET revoked=1 WHERE id=?", (dev["id"],))
        with self.assertRaises(Unauthorized):
            s.auth.authenticate(token)
        with self.assertRaises(Unauthorized):
            s.auth.issue_token(reg["device_id"], reg["device_secret"])

    def test_registration_token_enforced(self):
        s = Services(registration_token="invite-123")
        with self.assertRaises(Forbidden):
            s.auth.register("Phone", "Model", None)
        with self.assertRaises(Forbidden):
            s.auth.register("Phone", "Model", "nope")
        self.assertIn("device_id", s.auth.register("Phone", "Model", "invite-123"))

    def test_invalid_labels_rejected(self):
        s = Services()
        with self.assertRaises(ServiceError):
            s.auth.register("<script>", "Model", None)


class PairingServiceTests(unittest.TestCase):
    def test_full_pairing_flow_requires_approval(self):
        s = Services()
        target, _ = s.new_device("Target", "A")
        controller, _ = s.new_device("Controller", "B")
        code = s.pairings.create_code(target)["code"]
        view = s.pairings.claim(controller, code)
        self.assertEqual(view["status"], "pending")
        with self.assertRaises(Conflict):  # cannot start a session before approval
            s.sessions.request(controller, view["id"], ["view"])
        approved = s.pairings.approve(target, view["id"], ["view", "files"])
        self.assertEqual(approved["status"], "active")
        self.assertEqual(approved["scopes"], ["files", "view"])

    def test_code_is_single_use(self):
        s = Services()
        target, _ = s.new_device()
        c1, _ = s.new_device()
        c2, _ = s.new_device()
        code = s.pairings.create_code(target)["code"]
        s.pairings.claim(c1, code)
        with self.assertRaises(NotFound):
            s.pairings.claim(c2, code)

    def test_code_expires(self):
        s = Services()
        target, _ = s.new_device()
        ctrl, _ = s.new_device()
        code = s.pairings.create_code(target)["code"]
        s.clock.advance(s.settings.pairing_code_ttl + 1)
        with self.assertRaises(Expired):
            s.pairings.claim(ctrl, code)

    def test_invalid_code_format(self):
        s = Services()
        ctrl, _ = s.new_device()
        with self.assertRaises(ServiceError):
            s.pairings.claim(ctrl, "abc")
        with self.assertRaises(NotFound):
            s.pairings.claim(ctrl, "ABCD2345")

    def test_cannot_pair_with_self(self):
        s = Services()
        dev, _ = s.new_device()
        code = s.pairings.create_code(dev)["code"]
        with self.assertRaises(Conflict):
            s.pairings.claim(dev, code)

    def test_only_target_can_approve(self):
        s = Services()
        target, _ = s.new_device()
        ctrl, _ = s.new_device()
        view = s.pairings.claim(ctrl, s.pairings.create_code(target)["code"])
        with self.assertRaises(Forbidden):
            s.pairings.approve(ctrl, view["id"], None)

    def test_revoke_by_either_side(self):
        s = Services()
        target, ctrl, pid = s.paired()
        self.assertEqual(s.pairings.revoke(ctrl, pid)["status"], "revoked")
        with self.assertRaises(Conflict):
            s.sessions.request(ctrl, pid, ["view"])

    def test_outsider_cannot_revoke(self):
        s = Services()
        _, _, pid = s.paired()
        other, _ = s.new_device()
        with self.assertRaises(Forbidden):
            s.pairings.revoke(other, pid)


class SessionServiceTests(unittest.TestCase):
    def test_session_lifecycle(self):
        s = Services()
        target, ctrl, pid = s.paired()
        sess = s.sessions.request(ctrl, pid, ["view", "control"])
        self.assertEqual(sess["status"], "pending")
        self.assertEqual(len(s.sessions.pending_for_target(target["id"])), 1)
        active = s.sessions.approve(target, sess["id"], ["view"])  # owner narrows the scope
        self.assertEqual(active["scopes"], ["view"])
        ws = s.sessions.active_for_ws(ctrl["id"], sess["id"])
        self.assertEqual(ws["role"], "controller")
        ended = s.sessions.end(target, sess["id"])
        self.assertEqual(ended["status"], "ended")
        with self.assertRaises(Conflict):
            s.sessions.active_for_ws(ctrl["id"], sess["id"])

    def test_scope_cannot_exceed_pairing_grant(self):
        s = Services()
        _, ctrl, pid = s.paired(scopes=["view"])
        with self.assertRaises(Forbidden):
            s.sessions.request(ctrl, pid, ["view", "control"])

    def test_approve_cannot_widen_scope(self):
        s = Services()
        target, ctrl, pid = s.paired()
        sess = s.sessions.request(ctrl, pid, ["view"])
        with self.assertRaises(ServiceError):
            s.sessions.approve(target, sess["id"], ["view", "control"])

    def test_pending_request_expires(self):
        s = Services()
        target, ctrl, pid = s.paired()
        sess = s.sessions.request(ctrl, pid, ["view"])
        s.clock.advance(s.settings.session_request_ttl + 1)
        with self.assertRaises(Expired):
            s.sessions.approve(target, sess["id"], None)

    def test_active_session_expires(self):
        s = Services()
        target, ctrl, pid = s.paired()
        sess = s.sessions.request(ctrl, pid, ["view"])
        s.sessions.approve(target, sess["id"], None)
        s.clock.advance(s.settings.session_max_seconds + 1)
        expired = s.sessions.expire_due()
        self.assertEqual([r["id"] for r in expired], [sess["id"]])
        with self.assertRaises(Conflict):
            s.sessions.active_for_ws(ctrl["id"], sess["id"])

    def test_outsider_cannot_join_or_view(self):
        s = Services()
        target, ctrl, pid = s.paired()
        sess = s.sessions.request(ctrl, pid, ["view"])
        s.sessions.approve(target, sess["id"], None)
        other, _ = s.new_device()
        with self.assertRaises(Forbidden):
            s.sessions.active_for_ws(other["id"], sess["id"])
        with self.assertRaises(Forbidden):
            s.sessions.view(sess["id"], other["id"])

    def test_one_session_at_a_time(self):
        s = Services()
        target, ctrl, pid = s.paired()
        s.sessions.request(ctrl, pid, ["view"])
        with self.assertRaises(Conflict):
            s.sessions.request(ctrl, pid, ["view"])

    def test_revoking_pairing_ends_sessions(self):
        s = Services()
        target, ctrl, pid = s.paired()
        sess = s.sessions.request(ctrl, pid, ["view"])
        s.sessions.approve(target, sess["id"], None)
        s.pairings.revoke(target, pid)
        self.assertEqual(s.sessions.end_for_pairing(pid, "pairing revoked"), [sess["id"]])
        self.assertEqual(s.sessions.view(sess["id"], target["id"])["status"], "ended")

    def test_audit_trail_written(self):
        s = Services()
        target, ctrl, pid = s.paired()
        events = [e["event"] for e in s.audit.for_device(target["id"])]
        self.assertIn("pairing_approved", events)
        self.assertIn("pairing_code_created", events)
