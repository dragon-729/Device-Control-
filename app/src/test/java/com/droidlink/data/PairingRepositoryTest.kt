package com.droidlink.data

import com.droidlink.data.repository.PairingRepositoryImpl
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.Scope
import com.droidlink.network.ApiClient
import com.droidlink.network.ApiException
import com.droidlink.testutil.FakePairedDeviceDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PairingRepositoryTest {
    private lateinit var server: MockWebServer
    private val dao = FakePairedDeviceDao()
    private lateinit var repo: PairingRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val api = ApiClient(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "t" })
        repo = PairingRepositoryImpl(api, dao, clock = { 10_000L })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun pairing(id: String, role: String, status: String, scopes: String = "\"view\",\"control\"") =
        """{"id":"$id","role":"$role","status":"$status","scopes":[$scopes],"peer":{"id":"peer-$id","name":"Peer $id","model":"M"},"created_at":1.0,"updated_at":2.0}"""

    @Test fun refreshMirrorsServerStateIntoTheLocalCache() = runBlocking {
        server.enqueue(json(200, "[${pairing("a", "controller", "active")},${pairing("b", "target", "pending")}]"))
        repo.refresh()
        val devices = repo.devices.first()
        assertEquals(setOf("a", "b"), devices.map { it.pairingId }.toSet())
        assertEquals(setOf(Scope.VIEW, Scope.CONTROL), devices.first { it.pairingId == "a" }.scopes)
        // a pairing revoked on another device disappears after the next refresh
        server.enqueue(json(200, "[${pairing("a", "controller", "active")}]"))
        repo.refresh()
        assertEquals(listOf("a"), repo.devices.first().map { it.pairingId })
    }

    @Test fun failedRefreshKeepsCachedDevicesForOfflineUse() = runBlocking {
        server.enqueue(json(200, "[${pairing("a", "controller", "active")}]"))
        repo.refresh()
        server.shutdown()
        assertThrows(ApiException.Offline::class.java) { runBlocking { repo.refresh() } }
        assertEquals(1, repo.devices.first().size)
    }

    @Test fun claimStoresPendingPairing() = runBlocking {
        server.enqueue(json(201, pairing("n", "controller", "pending", "\"view\"")))
        val p = repo.claim("ABCD2345")
        assertEquals(PairingStatus.PENDING, p.status)
        assertEquals(DeviceRole.CONTROLLER, p.role)
        assertEquals(1, repo.devices.first().size)
    }

    @Test fun approveSendsGrantedScopes() = runBlocking {
        server.enqueue(json(200, pairing("n", "target", "active", "\"files\"")))
        val p = repo.approve("n", setOf(Scope.FILES))
        assertEquals(setOf(Scope.FILES), p.scopes)
        val req = server.takeRequest()
        assertEquals("/api/v1/pairings/n/approve", req.path)
        assertEquals("""{"scopes":["files"]}""", req.body.readUtf8())
    }

    @Test fun pendingIncomingFiltersToRequestsForThisDevice() = runBlocking {
        server.enqueue(json(200, "[${pairing("a", "target", "pending")},${pairing("b", "controller", "pending")},${pairing("c", "target", "active")}]"))
        assertEquals(listOf("a"), repo.pendingIncoming().map { it.pairingId })
    }

    @Test fun invalidCodeErrorsAreTyped() {
        server.enqueue(json(404, """{"error":"not_found","message":"pairing code not found"}"""))
        assertThrows(ApiException.NotFound::class.java) { runBlocking { repo.claim("ABCD2345") } }
        server.enqueue(json(410, """{"error":"expired","message":"pairing code expired"}"""))
        assertThrows(ApiException.Expired::class.java) { runBlocking { repo.claim("ABCD2345") } }
    }
}
