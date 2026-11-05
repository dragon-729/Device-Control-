package com.droidlink.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val tokenCalls = mutableListOf<Boolean>()

    private fun client() = ApiClient(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
        baseUrl = { server.url("/").toString().trimEnd('/') },
        tokenProvider = { force -> tokenCalls += force; if (force) "fresh-token" else "old-token" },
    )

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val pairingJson = """{"id":"p1","role":"controller","status":"pending","scopes":["view"],"peer":{"id":"d2","name":"Tablet","model":"Pixel"},"created_at":1.5,"updated_at":2.5}"""

    @Test fun sendsBearerTokenAndParsesResponse() = runBlocking {
        server.enqueue(json(201, pairingJson))
        val result = client().claim("ABCD2345")
        assertEquals("p1", result.id)
        assertEquals("Tablet", result.peer.name)
        val req = server.takeRequest()
        assertEquals("Bearer old-token", req.getHeader("Authorization"))
        assertEquals("/api/v1/pairing/claim", req.path)
        assertEquals("""{"code":"ABCD2345"}""", req.body.readUtf8())
    }

    @Test fun unauthenticatedCallsSendNoToken() = runBlocking {
        server.enqueue(json(201, """{"device_id":"d1","device_secret":"s"}"""))
        client().register(RegisterRequest("Phone", "Pixel"))
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertTrue(tokenCalls.isEmpty())
    }

    @Test fun expiredTokenIsRefreshedOnceThenRetried() = runBlocking {
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        server.enqueue(json(200, "[]"))
        assertEquals(0, client().pairings().size)
        assertEquals(listOf(false, true), tokenCalls)
        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun persistent401SurfacesAsUnauthorized() {
        server.enqueue(json(401, "{}"))
        server.enqueue(json(401, "{}"))
        assertThrows(ApiException.Unauthorized::class.java) { runBlocking { client().pairings() } }
        assertEquals(2, server.requestCount)
    }

    @Test fun errorStatusesMapToTypedExceptions() {
        val cases = listOf(
            403 to ApiException.Forbidden::class.java, 404 to ApiException.NotFound::class.java,
            409 to ApiException.Conflict::class.java, 410 to ApiException.Expired::class.java,
            422 to ApiException.Validation::class.java, 429 to ApiException.RateLimited::class.java,
            500 to ApiException.Server::class.java,
        )
        for ((code, type) in cases) {
            server.enqueue(json(code, """{"error":"x","message":"server says no"}"""))
            val ex = assertThrows(ApiException::class.java) { runBlocking { client().session("s1") } }
            assertTrue("HTTP $code -> ${ex::class.java}", type.isInstance(ex))
        }
    }

    @Test fun serverMessageIsPropagated() {
        server.enqueue(json(404, """{"error":"not_found","message":"pairing code not found"}"""))
        val ex = assertThrows(ApiException.NotFound::class.java) { runBlocking { client().claim("ABCD2345") } }
        assertEquals("pairing code not found", ex.message)
    }

    @Test fun fastApiValidationDetailIsUnderstood() {
        server.enqueue(json(422, """{"detail":[{"msg":"String should have at least 8 characters"}]}"""))
        val ex = assertThrows(ApiException.Validation::class.java) { runBlocking { client().claim("A") } }
        assertTrue(ex.message!!.contains("at least 8"))
    }

    @Test fun unreachableServerIsReportedAsOffline() {
        val c = client()
        server.shutdown()
        assertThrows(ApiException.Offline::class.java) { runBlocking { c.pairings() } }
    }

    @Test fun slowOrDroppedConnectionIsOffline() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(ApiException.Offline::class.java) { runBlocking { client().pairings() } }
    }

    @Test fun garbageSuccessBodyIsAServerError() {
        server.enqueue(json(200, "<html>proxy error</html>"))
        assertThrows(ApiException.Server::class.java) { runBlocking { client().pairings() } }
    }
}
