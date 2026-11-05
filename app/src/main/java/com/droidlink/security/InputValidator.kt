package com.droidlink.security

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Central input validation for everything that comes from the user, a QR code or a remote peer. */
object InputValidator {
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 8
    private val NAME_REGEX = Regex("^[\\p{L}\\p{N}_ .,'()\\-]{1,64}$")
    private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val LOCAL_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** Returns the canonical 8-char code or null when the input cannot be a valid pairing code. */
    fun normalizePairingCode(raw: String): String? {
        val code = raw.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return if (code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }) code else null
    }

    fun formatPairingCode(code: String): String = code.chunked(4).joinToString("-")

    sealed interface UrlResult {
        data class Valid(val url: String) : UrlResult
        data class Invalid(val reason: String) : UrlResult
    }

    fun validateServerUrl(raw: String, allowLocalCleartext: Boolean): UrlResult {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return UrlResult.Invalid("Enter the relay server address")
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return UrlResult.Invalid("That is not a valid address")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty() || scheme == null) return UrlResult.Invalid("That is not a valid address")
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return UrlResult.Invalid("Address must not contain credentials, query or fragment")
        }
        return when (scheme) {
            "https" -> UrlResult.Valid(trimmed)
            "http" ->
                if (allowLocalCleartext && host in LOCAL_HOSTS) UrlResult.Valid(trimmed)
                else UrlResult.Invalid("Use https:// - unencrypted connections are not allowed")
            else -> UrlResult.Invalid("Use an https:// address")
        }
    }

    /** Trimmed name or null when invalid. */
    fun validateDeviceName(raw: String): String? = raw.trim().takeIf { NAME_REGEX.matches(it) }

    fun isValidPackageName(pkg: String): Boolean = pkg.length <= 255 && PACKAGE_REGEX.matches(pkg)

    /** Strips path components and control characters from a file name supplied by a remote peer. */
    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val forbidden = setOf(':', '*', '?', '"', '<', '>', '|')
        val cleaned = base.filter { it.code >= 32 && it !in forbidden }.trim().trimStart('.')
        return cleaned.take(128).ifEmpty { "file" }
    }

    data class PairingLink(val code: String, val server: String?)

    /** Parses droidlink://pair?code=ABCD2345&server=https%3A%2F%2Frelay.example.com */
    fun parsePairingLink(link: String): PairingLink? {
        val uri = try {
            URI(link.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.scheme != "droidlink" || uri.host != "pair") return null
        val params = (uri.rawQuery ?: return null).split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val code = normalizePairingCode(params["code"] ?: return null) ?: return null
        return PairingLink(code, params["server"])
    }

    fun buildPairingLink(code: String, server: String): String =
        "droidlink://pair?code=$code&server=${URLEncoder.encode(server, "UTF-8")}"

    fun sameServer(a: String, b: String): Boolean = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
}
