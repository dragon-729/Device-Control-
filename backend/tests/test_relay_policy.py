import json
import unittest

from app.services import relay_policy as p

ALL = {"view", "control", "files"}


def msg(**kw):
    return json.dumps(kw)


class RelayPolicyTests(unittest.TestCase):
    def test_controller_input_requires_control_scope(self):
        self.assertIsNone(p.check_text("controller", ALL, msg(t="tap", x=0.5, y=0.5)))
        self.assertEqual(p.check_text("controller", {"view"}, msg(t="tap", x=0.5, y=0.5)), "scope_control_required")
        self.assertEqual(p.check_text("controller", {"view"}, msg(t="text", s="hi")), "scope_control_required")

    def test_target_cannot_send_control_messages(self):
        self.assertEqual(p.check_text("target", ALL, msg(t="tap", x=0, y=0)), "type_not_allowed")

    def test_controller_cannot_forge_target_only_messages(self):
        self.assertEqual(p.check_text("controller", ALL, msg(t="info")), "type_not_allowed")
        self.assertEqual(p.check_text("controller", ALL, msg(t="apps")), "type_not_allowed")

    def test_file_messages_require_files_scope(self):
        self.assertIsNone(p.check_text("target", ALL, msg(t="file_offer", id="1")))
        self.assertEqual(p.check_text("target", {"view"}, msg(t="file_offer", id="1")), "scope_files_required")

    def test_invalid_input_rejected(self):
        self.assertEqual(p.check_text("controller", ALL, "not json"), "invalid_json")
        self.assertEqual(p.check_text("controller", ALL, "[1,2]"), "missing_type")
        self.assertEqual(p.check_text("controller", ALL, msg(t="rm_rf")), "type_not_allowed")

    def test_binary_rules(self):
        frame = bytes([p.BIN_FRAME]) + b"jpeg"
        chunk = bytes([p.BIN_FILE_CHUNK]) + b"data"
        self.assertIsNone(p.check_binary("target", ALL, frame))
        self.assertEqual(p.check_binary("controller", ALL, frame), "type_not_allowed")
        self.assertEqual(p.check_binary("target", {"control"}, frame), "scope_view_required")
        self.assertIsNone(p.check_binary("controller", ALL, chunk))
        self.assertEqual(p.check_binary("controller", {"view"}, chunk), "scope_files_required")
        self.assertEqual(p.check_binary("target", ALL, b"\x09xx"), "type_not_allowed")
        self.assertEqual(p.check_binary("target", ALL, b""), "empty_frame")
