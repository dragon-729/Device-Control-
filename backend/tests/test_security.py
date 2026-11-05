import time
import unittest

from app.core import security
from app.core.rate_limit import RateLimiter
from tests.helpers import FakeClock

KEY = "k" * 48


class SecurityTests(unittest.TestCase):
    def test_secret_hash_roundtrip_and_mismatch(self):
        s = security.new_device_secret()
        h = security.hash_secret(s, KEY)
        self.assertNotIn(s, h)
        self.assertTrue(security.verify_secret(s, h, KEY))
        self.assertFalse(security.verify_secret(s + "x", h, KEY))
        self.assertFalse(security.verify_secret(s, h, "other-key"))

    def test_token_roundtrip(self):
        tok = security.issue_access_token("dev1", KEY, 60)
        self.assertEqual(security.decode_access_token(tok, KEY)["sub"], "dev1")

    def test_token_expired(self):
        now = time.time()
        tok = security.issue_access_token("dev1", KEY, 60, now=now)
        with self.assertRaises(security.TokenError):
            security.decode_access_token(tok, KEY, now=now + 61)

    def test_token_tampered_and_wrong_key(self):
        tok = security.issue_access_token("dev1", KEY, 60)
        with self.assertRaises(security.TokenError):
            security.decode_access_token(tok[:-2] + "xx", KEY)
        with self.assertRaises(security.TokenError):
            security.decode_access_token(tok, "different-key" * 4)

    def test_pairing_code_format(self):
        for _ in range(50):
            c = security.new_pairing_code()
            self.assertTrue(security.is_valid_code_format(c))
        self.assertEqual(security.normalize_code("ab3d-ef4g"), "AB3DEF4G")
        self.assertFalse(security.is_valid_code_format("OOOO0000"))

    def test_rate_limiter_window(self):
        clock = FakeClock()
        rl = RateLimiter(clock)
        self.assertTrue(all(rl.allow("a", 3, 10) for _ in range(3)))
        self.assertFalse(rl.allow("a", 3, 10))
        self.assertTrue(rl.allow("b", 3, 10))
        clock.advance(11)
        self.assertTrue(rl.allow("a", 3, 10))
