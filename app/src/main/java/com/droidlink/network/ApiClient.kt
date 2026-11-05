package com.droidlink.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thin REST client for the relay API.
 * [tokenProvider] returns a valid access token; with forceRefresh=true it must fetch a new one.
 * A 401 on an authenticated call triggers exactly one refresh + retry, then surfaces [ApiException.Unauthorized].
 */
class ApiClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ---- public API ----------------------------------------------------------------------------
    suspend fun register(body: RegisterRequest): RegisterResponse =
        call("POST", "/api/v1/devices/register", RegisterRequest.serializer(), body, RegisterResponse.serializer(), auth = false)

    suspend fun token(body: TokenRequest): TokenResponse =
        call("POST", "/api/v1/auth/token", TokenRequest.serializer(), body, TokenResponse.serializer(), auth = false)

    suspend fun unregister() = callUnit("DELETE", "/api/v1/devices/me")

    suspend fun createPairingCode(): CodeResponse =
        call<Unit, CodeResponse>("POST", "/api/v1/pairing/codes", null, null, CodeResponse.serializer())

    suspend fun claim(code: String): PairingDto =
        call("POST", "/api/v1/pairing/claim", ClaimRequest.serializer(), ClaimRequest(code), PairingDto.serializer())

    suspend fun pairings(): List<PairingDto> =
        call<Unit, List<PairingDto>>("GET", "/api/v1/pairings", null, null, ListSerializer(PairingDto.serializer()))

    suspend fun approvePairing(id: String, scopes: List<String>): PairingDto =
        call("POST", "/api/v1/pairings/$id/approve", ScopesBody.serializer(), ScopesBody(scopes), PairingDto.serializer())

    suspend fun rejectPairing(id: String): PairingDto =
        call<Unit, PairingDto>("POST", "/api/v1/pairings/$id/reject", null, null, PairingDto.serializer())

    suspend fun updatePairingScopes(id: String, scopes: List<String>): PairingDto =
        call("PATCH", "/api/v1/pairings/$id/scopes", ScopesBody.serializer(), ScopesBody(scopes), PairingDto.serializer())

    suspend fun revokePairing(id: String): PairingDto =
        call<Unit, PairingDto>("DELETE", "/api/v1/pairings/$id", null, null, PairingDto.serializer())

    suspend fun requestSession(pairingId: String, scopes: List<String>): SessionDto =
        call("POST", "/api/v1/sessions", SessionRequestBody.serializer(), SessionRequestBody(pairingId, scopes), SessionDto.serializer())

    suspend fun session(id: String): SessionDto =
        call<Unit, SessionDto>("GET", "/api/v1/sessions/$id", null, null, SessionDto.serializer())

    suspend fun pendingSessions(): List<SessionDto> =
        call<Unit, List<SessionDto>>("GET", "/api/v1/sessions/pending", null, null, ListSerializer(SessionDto.serializer()))

    suspend fun approveSession(id: String, scopes: List<String>): SessionDto =
        call("POST", "/api/v1/sessions/$id/approve", ScopesBody.serializer(), ScopesBody(scopes), SessionDto.serializer())

    suspend fun rejectSession(id: String): SessionDto =
        call<Unit, SessionDto>("POST", "/api/v1/sessions/$id/reject", null, null, SessionDto.serializer())

    suspend fun endSession(id: String): SessionDto =
        call<Unit, SessionDto>("POST", "/api/v1/sessions/$id/end", null, null, SessionDto.serializer())

    // ---- plumbing --------------------------------------------------------------------------------
    private suspend fun <B, R> call(
        method: String,
        path: String,
        bodySerializer: KSerializer<B>?,
        body: B?,
        responseSerializer: KSerializer<R>,
        auth: Boolean = true,
    ): R {
        val payload = if (bodySerializer != null && body != null) ProtocolJson.encodeToString(bodySerializer, body) else null
        val text = execute(method, path, payload, auth)
        return try {
            ProtocolJson.decodeFromString(responseSerializer, text)
        } catch (e: Exception) {
            throw ApiException.Server(200, "Unexpected response from the server.")
        }
    }

    private suspend fun callUnit(method: String, path: String) {
        execute(method, path, null, auth = true)
    }

    private suspend fun execute(method: String, path: String, jsonBody: String?, auth: Boolean): String {
        var forceRefresh = false
        repeat(2) { attempt ->
            val token = if (auth) tokenProvider(forceRefresh) else null
            val (code, text) = send(method, path, jsonBody, token)
            if (code in 200..299) return text
            if (code == 401 && auth && attempt == 0) {
                forceRefresh = true
                return@repeat
            }
            throw mapError(code, text)
        }
        throw ApiException.Unauthorized()
    }

    private suspend fun send(method: String, path: String, jsonBody: String?, token: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val body = when {
                jsonBody != null -> jsonBody.toRequestBody(jsonType)
                method == "POST" || method == "PATCH" -> "{}".toRequestBody(jsonType)
                else -> null
            }
            val builder = Request.Builder().url(baseUrl().trimEnd('/') + path).method(method, body)
            if (token != null) builder.header("Authorization", "Bearer $token")
            try {
                http.newCall(builder.build()).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: IOException) {
                throw ApiException.Offline(e)
            } catch (e: IllegalArgumentException) {
                throw ApiException.Validation("The server address is not valid.")
            }
        }

    internal fun mapError(code: Int, body: String): ApiException {
        val message = extractMessage(body)
        return when (code) {
            400, 422 -> ApiException.Validation(message ?: "The request was rejected as invalid.")
            401 -> ApiException.Unauthorized()
            403 -> ApiException.Forbidden(message ?: "You do not have permission to do that.")
            404 -> ApiException.NotFound(message ?: "Not found.")
            409 -> ApiException.Conflict(message ?: "That conflicts with the current state.")
            410 -> ApiException.Expired(message ?: "That request has expired.")
            429 -> ApiException.RateLimited()
            else -> ApiException.Server(code)
        }
    }

    private fun extractMessage(body: String): String? = try {
        val root: JsonElement = ProtocolJson.parseToJsonElement(body)
        val obj: JsonObject = root.jsonObject
        (obj["message"] as? JsonPrimitive)?.content
            ?: (obj["detail"] as? JsonPrimitive)?.content
            ?: obj["detail"]?.jsonArray?.firstOrNull()?.jsonObject?.get("msg")?.let { (it as? JsonPrimitive)?.content }
    } catch (e: Exception) {
        null
    }
}
