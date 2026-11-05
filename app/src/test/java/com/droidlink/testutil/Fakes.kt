package com.droidlink.testutil

import com.droidlink.data.local.PairedDeviceDao
import com.droidlink.data.local.PairedDeviceEntity
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingCode
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.Registration
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.ConnectionRecord
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SecurityEvent
import com.droidlink.domain.model.SessionStatus
import com.droidlink.domain.repository.AuthRepository
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.domain.repository.SecurityLogRepository
import com.droidlink.domain.repository.SessionRepository
import com.droidlink.security.SecureKeyValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class InMemorySecureKeyValue : SecureKeyValue {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() = map.clear()
}

class FakeSecurityLog : SecurityLogRepository {
    val entries = mutableListOf<Pair<String, String>>()
    override val events: Flow<List<SecurityEvent>> = MutableStateFlow(emptyList())
    override suspend fun log(type: String, detail: String) { entries += type to detail }
    override suspend fun clear() = entries.clear()
}

class FakeAuth(initial: Registration? = null) : AuthRepository {
    private val reg = MutableStateFlow(initial)
    override val registration: StateFlow<Registration?> = reg.asStateFlow()
    var failWith: Exception? = null
    var registered: Triple<String, String, String?>? = null
    override suspend fun register(serverUrl: String, deviceName: String, inviteCode: String?): Registration {
        failWith?.let { throw it }
        registered = Triple(serverUrl, deviceName, inviteCode)
        return Registration("dev-1", deviceName, serverUrl).also { reg.value = it }
    }
    override suspend fun accessToken(forceRefresh: Boolean) = "token"
    override suspend fun signOut() { reg.value = null }
}

fun pairedDevice(
    id: String = "p1", status: PairingStatus = PairingStatus.ACTIVE, role: DeviceRole = DeviceRole.CONTROLLER,
    scopes: Set<Scope> = Scope.entries.toSet(), name: String = "Living-room tablet",
) = PairedDevice(id, "peer-$id", name, "Pixel Tablet", role, status, scopes, 1L, 2L)

class FakePairingRepository : PairingRepository {
    val list = MutableStateFlow<List<PairedDevice>>(emptyList())
    override val devices: Flow<List<PairedDevice>> = list
    var claimResult: PairedDevice = pairedDevice(status = PairingStatus.PENDING)
    var claimError: Exception? = null
    var claimedCode: String? = null
    var refreshCount = 0
    override suspend fun refresh() { refreshCount++ }
    override suspend fun createCode() = PairingCode("ABCD2345", System.currentTimeMillis() + 120_000, "droidlink://pair?code=ABCD2345")
    override suspend fun claim(code: String): PairedDevice {
        claimError?.let { throw it }
        claimedCode = code
        list.value = list.value.filterNot { it.pairingId == claimResult.pairingId } + claimResult
        return claimResult
    }
    override suspend fun approve(pairingId: String, scopes: Set<Scope>) = pairedDevice(pairingId)
    override suspend fun reject(pairingId: String) = Unit
    override suspend fun updateScopes(pairingId: String, scopes: Set<Scope>) = pairedDevice(pairingId, scopes = scopes)
    override suspend fun revoke(pairingId: String) = Unit
    override suspend fun pendingIncoming(): List<PairedDevice> = emptyList()
}

fun remoteSession(status: SessionStatus, scopes: Set<Scope> = setOf(Scope.VIEW), id: String = "s1") = RemoteSession(
    id, "p1", DeviceRole.CONTROLLER, status, scopes, "peer", "Tablet", "Pixel", 0L, 0L, null, ttlMs = 120_000,
)

class FakeSessionRepository : SessionRepository {
    var current: RemoteSession = remoteSession(SessionStatus.PENDING)
    var requestError: Exception? = null
    var ended = mutableListOf<String>()
    override suspend fun request(pairingId: String, scopes: Set<Scope>): RemoteSession {
        requestError?.let { throw it }
        return current.copy(scopes = scopes)
    }
    override suspend fun get(sessionId: String) = current
    override suspend fun pendingForMe() = emptyList<RemoteSession>()
    override suspend fun approve(sessionId: String, scopes: Set<Scope>) = current.copy(status = SessionStatus.ACTIVE, scopes = scopes)
    override suspend fun reject(sessionId: String) = Unit
    override suspend fun end(sessionId: String) { ended += sessionId }
    override val history: Flow<List<ConnectionRecord>> = MutableStateFlow(emptyList())
    override suspend fun recordStart(session: RemoteSession) = Unit
    override suspend fun recordEnd(sessionId: String, outcome: String) = Unit
    override suspend fun clearHistory() = Unit
}

class FakePairedDeviceDao : PairedDeviceDao() {
    val rows = MutableStateFlow<List<PairedDeviceEntity>>(emptyList())
    override fun observeAll(): Flow<List<PairedDeviceEntity>> = rows.map { it.sortedByDescending { e -> e.updatedAt } }
    override suspend fun upsert(entity: PairedDeviceEntity) { rows.value = rows.value.filterNot { it.pairingId == entity.pairingId } + entity }
    override suspend fun upsertAll(entities: List<PairedDeviceEntity>) { entities.forEach { upsert(it) } }
    override suspend fun clear() { rows.value = emptyList() }
    override suspend fun replaceAll(entities: List<PairedDeviceEntity>) { clear(); upsertAll(entities) }
}
