package com.droidlink.data.repository

import com.droidlink.domain.model.Registration
import com.droidlink.domain.repository.AuthRepository
import com.droidlink.network.ApiClient
import com.droidlink.network.ApiException
import com.droidlink.network.RegisterRequest
import com.droidlink.network.TokenRequest
import com.droidlink.security.SecureKeyValue
import com.droidlink.security.SecureKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Device-credential authentication:
 *  - registration yields a device id + long-lived secret, stored only encrypted (Android Keystore);
 *  - the secret is exchanged for 15-minute access tokens, which are cached in memory only.
 */
class AuthRepositoryImpl(
    private val secure: SecureKeyValue,
    private val apiProvider: () -> ApiClient,
    private val deviceModel: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthRepository {

    private val _registration = MutableStateFlow(loadRegistration())
    override val registration: StateFlow<Registration?> = _registration.asStateFlow()

    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var cachedExpiry: Long = 0
    @Volatile private var pendingServerUrl: String? = null

    /** Base URL for the API client: registered server, or the one being registered against. */
    fun currentServerUrl(): String = pendingServerUrl ?: _registration.value?.serverUrl.orEmpty()

    private fun loadRegistration(): Registration? = try {
        val id = secure.get(SecureKeys.DEVICE_ID)
        val url = secure.get(SecureKeys.SERVER_URL)
        val name = secure.get(SecureKeys.DEVICE_NAME)
        if (id != null && url != null && secure.get(SecureKeys.DEVICE_SECRET) != null) Registration(id, name ?: deviceModel, url) else null
    } catch (e: Exception) {
        null
    }

    override suspend fun register(serverUrl: String, deviceName: String, inviteCode: String?): Registration {
        pendingServerUrl = serverUrl
        try {
            val response = apiProvider().register(RegisterRequest(deviceName, deviceModel, inviteCode?.trim()?.takeIf { it.isNotEmpty() }))
            secure.put(SecureKeys.DEVICE_ID, response.deviceId)
            secure.put(SecureKeys.DEVICE_SECRET, response.deviceSecret)
            secure.put(SecureKeys.SERVER_URL, serverUrl)
            secure.put(SecureKeys.DEVICE_NAME, deviceName)
            val reg = Registration(response.deviceId, deviceName, serverUrl)
            mutex.withLock { cachedToken = null }
            _registration.value = reg
            return reg
        } finally {
            pendingServerUrl = null
        }
    }

    override suspend fun accessToken(forceRefresh: Boolean): String = mutex.withLock {
        val now = clock()
        val cached = cachedToken
        if (!forceRefresh && cached != null && now < cachedExpiry - 30_000) return cached
        val id = secure.get(SecureKeys.DEVICE_ID)
        val secret = secure.get(SecureKeys.DEVICE_SECRET)
        if (id == null || secret == null) throw ApiException.Unauthorized("This device is not registered.")
        try {
            val response = apiProvider().token(TokenRequest(id, secret))
            cachedToken = response.accessToken
            cachedExpiry = now + response.expiresIn * 1000L
            response.accessToken
        } catch (e: ApiException.Unauthorized) {
            // Credentials rejected (revoked / server reset): forget them so the user can sign in again.
            wipe()
            throw e
        }
    }

    override suspend fun signOut() {
        try {
            apiProvider().unregister()
        } catch (e: Exception) {
            // Best effort: local credentials are removed regardless.
        }
        mutex.withLock { wipe() }
    }

    private fun wipe() {
        secure.clear()
        cachedToken = null
        cachedExpiry = 0
        _registration.value = null
    }
}
