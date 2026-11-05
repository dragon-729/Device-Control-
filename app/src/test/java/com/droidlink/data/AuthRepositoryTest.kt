package com.droidlink.data

import com.droidlink.data.repository.AuthRepositoryImpl
import com.droidlink.network.ApiClient
import com.droidlink.network.ApiException
import com.droidlink.security.SecureKeys
import com.droidlink.testutil.InMemorySecureKeyValue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private val store = InMemorySecureKeyValue()
    private var now = 1_000_000L
    private lateinit var repo: AuthRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        lateinit var api: ApiClient
        repo = AuthRepositoryImpl(store, { api }, "Pixel 8", clock = { now })
        api = ApiClient(OkHttpClient(), { repo.currentServerUrl() }, { force -> repo.accessToken(force) })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun url() = server.url("/").toString().trimEnd('/')
    private fun enqueueRegister() = server.enqueue(json(201, """{"device_id":"dev-1","device_secret":"secret-secret-secret"}"""))
    private fun enqueueToken(token: String, ttl: Int = 900) = server.enqueue(json(200, """{"access_token":"$token","token_type":"bearer","expires_in":$ttl}"""))

    @Test fun registrationStoresCredentialsAndExposesState() = runBlocking {
        enqueueRegister()
        val reg = repo.register(url(), "My phone", "  invite-1 ")
        assertEquals("dev-1", reg.deviceId)
        assertEquals(reg, repo.registration.value)
        assertEquals("dev-1", store.get(SecureKeys.DEVICE_ID))
        assertEquals("secret-secret-secret", store.get(SecureKeys.DEVICE_SECRET))
        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"name":"My phone","model":"Pixel 8","registration_token":"invite-1"}""", body)
    }

    @Test fun failedRegistrationStoresNothing() {
        server.enqueue(json(403, """{"error":"forbidden","message":"invalid registration token"}"""))
        assertThrows(ApiException.Forbidden::class.java) { runBlocking { repo.register(url(), "My phone", "bad") } }
        assertNull(repo.registration.value)
        assertNull(store.get(SecureKeys.DEVICE_SECRET))
    }

    @Test fun accessTokenIsCachedUntilNearExpiry() = runBlocking {
        enqueueRegister(); enqueueToken("t1", 900); enqueueToken("t2", 900)
        repo.register(url(), "My phone", null)
        assertEquals("t1", repo.accessToken())
        now += 800_000
        assertEquals("cached within lifetime", "t1", repo.accessToken())
        now += 80_000 // inside the 30s safety margin of expiry
        assertEquals("t2", repo.accessToken())
        assertEquals(3, server.requestCount)
    }

    @Test fun forceRefreshAlwaysHitsTheServer() = runBlocking {
        enqueueRegister(); enqueueToken("t1"); enqueueToken("t2")
        repo.register(url(), "My phone", null)
        repo.accessToken()
        assertEquals("t2", repo.accessToken(forceRefresh = true))
    }

    @Test fun rejectedCredentialsWipeLocalStateSoUserCanSignInAgain() {
        runBlocking { enqueueRegister(); repo.register(url(), "My phone", null) }
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        assertThrows(ApiException.Unauthorized::class.java) { runBlocking { repo.accessToken() } }
        assertNull(repo.registration.value)
        assertNull(store.get(SecureKeys.DEVICE_SECRET))
    }

    @Test fun tokenWithoutRegistrationFailsWithoutNetworkCall() {
        assertThrows(ApiException.Unauthorized::class.java) { runBlocking { repo.accessToken() } }
        assertEquals(0, server.requestCount)
    }

    @Test fun signOutClearsCredentialsEvenWhenOffline() = runBlocking {
        enqueueRegister(); enqueueToken("t1")
        repo.register(url(), "My phone", null)
        server.shutdown()
        repo.signOut()
        assertNull(repo.registration.value)
        assertNull(store.get(SecureKeys.DEVICE_ID))
        assertNotNull(server)
    }

    @Test fun existingRegistrationIsRestoredFromSecureStore() {
        store.put(SecureKeys.DEVICE_ID, "dev-9"); store.put(SecureKeys.DEVICE_SECRET, "s"); store.put(SecureKeys.SERVER_URL, "https://r.example.com")
        val restored = AuthRepositoryImpl(store, { error("unused") }, "Pixel")
        assertEquals("dev-9", restored.registration.value?.deviceId)
        assertEquals("https://r.example.com", restored.currentServerUrl())
    }
}
