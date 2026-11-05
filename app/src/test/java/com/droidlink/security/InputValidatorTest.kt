package com.droidlink.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidatorTest {
    @Test fun pairingCodeIsNormalised() {
        assertEquals("K7P49TXM", InputValidator.normalizePairingCode("k7p4-9txm"))
        assertEquals("K7P4-9TXM", InputValidator.formatPairingCode("K7P49TXM"))
    }

    @Test fun invalidPairingCodesAreRejected() {
        assertNull(InputValidator.normalizePairingCode(""))
        assertNull(InputValidator.normalizePairingCode("ABC"))
        assertNull(InputValidator.normalizePairingCode("ABCD2345X"))
        assertNull(InputValidator.normalizePairingCode("OOOO0000")) // ambiguous characters are not in the alphabet
        assertNull(InputValidator.normalizePairingCode("ABCD-23<>"))
    }

    @Test fun serverUrlMustBeHttps() {
        assertTrue(InputValidator.validateServerUrl("https://relay.example.com/", false) is InputValidator.UrlResult.Valid)
        assertEquals("https://relay.example.com", (InputValidator.validateServerUrl(" https://relay.example.com/ ", false) as InputValidator.UrlResult.Valid).url)
        assertTrue(InputValidator.validateServerUrl("http://relay.example.com", false) is InputValidator.UrlResult.Invalid)
        assertTrue(InputValidator.validateServerUrl("ftp://relay.example.com", false) is InputValidator.UrlResult.Invalid)
        assertTrue(InputValidator.validateServerUrl("", false) is InputValidator.UrlResult.Invalid)
        assertTrue(InputValidator.validateServerUrl("https://user:pw@relay.example.com", false) is InputValidator.UrlResult.Invalid)
        assertTrue(InputValidator.validateServerUrl("https://relay.example.com?x=1", false) is InputValidator.UrlResult.Invalid)
    }

    @Test fun cleartextOnlyForLocalHostsInDebug() {
        assertTrue(InputValidator.validateServerUrl("http://10.0.2.2:8000", true) is InputValidator.UrlResult.Valid)
        assertTrue(InputValidator.validateServerUrl("http://10.0.2.2:8000", false) is InputValidator.UrlResult.Invalid)
        assertTrue(InputValidator.validateServerUrl("http://evil.example.com", true) is InputValidator.UrlResult.Invalid)
    }

    @Test fun deviceNames() {
        assertEquals("Pixel 8 (work)", InputValidator.validateDeviceName("  Pixel 8 (work) "))
        assertNull(InputValidator.validateDeviceName(""))
        assertNull(InputValidator.validateDeviceName("<script>"))
        assertNull(InputValidator.validateDeviceName("x".repeat(65)))
    }

    @Test fun packageNames() {
        assertTrue(InputValidator.isValidPackageName("com.android.chrome"))
        assertFalse(InputValidator.isValidPackageName("chrome"))
        assertFalse(InputValidator.isValidPackageName("com.evil; rm -rf"))
        assertFalse(InputValidator.isValidPackageName("../../etc"))
    }

    @Test fun fileNamesFromPeersAreSanitised() {
        assertEquals("passwd", InputValidator.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.exe", InputValidator.sanitizeFileName("C:\\Users\\x\\evil.exe"))
        assertEquals("hidden", InputValidator.sanitizeFileName(".hidden"))
        assertEquals("file", InputValidator.sanitizeFileName("///"))
        assertEquals("ab.txt", InputValidator.sanitizeFileName("a\u0000b?.txt".replace("?", "")))
        assertEquals(128, InputValidator.sanitizeFileName("a".repeat(500)).length)
    }

    @Test fun pairingLinks() {
        val link = InputValidator.buildPairingLink("K7P49TXM", "https://relay.example.com")
        val parsed = InputValidator.parsePairingLink(link)
        assertNotNull(parsed)
        assertEquals("K7P49TXM", parsed!!.code)
        assertEquals("https://relay.example.com", parsed.server)
        assertNull(InputValidator.parsePairingLink("https://example.com/pair?code=K7P49TXM"))
        assertNull(InputValidator.parsePairingLink("droidlink://pair?code=bad"))
        assertNull(InputValidator.parsePairingLink("droidlink://other?code=K7P49TXM"))
        assertTrue(InputValidator.sameServer("https://a.com/", "https://A.com"))
    }
}
