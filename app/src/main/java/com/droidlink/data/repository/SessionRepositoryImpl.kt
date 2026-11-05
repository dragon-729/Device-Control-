package com.droidlink.data.repository

import com.droidlink.data.local.ConnectionDao
import com.droidlink.data.local.ConnectionEntity
import com.droidlink.domain.model.ConnectionRecord
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.repository.SessionRepository
import com.droidlink.network.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SessionRepositoryImpl(
    private val api: ApiClient,
    private val dao: ConnectionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionRepository {

    override val history: Flow<List<ConnectionRecord>> = dao.observe().map { list -> list.map { it.toDomain() } }

    override suspend fun request(pairingId: String, scopes: Set<Scope>): RemoteSession =
        api.requestSession(pairingId, scopes.map { it.wire }).toDomain()

    override suspend fun get(sessionId: String): RemoteSession = api.session(sessionId).toDomain()

    override suspend fun pendingForMe(): List<RemoteSession> = api.pendingSessions().map { it.toDomain() }

    override suspend fun approve(sessionId: String, scopes: Set<Scope>): RemoteSession =
        api.approveSession(sessionId, scopes.map { it.wire }).toDomain()

    override suspend fun reject(sessionId: String) {
        api.rejectSession(sessionId)
    }

    override suspend fun end(sessionId: String) {
        api.endSession(sessionId)
    }

    override suspend fun recordStart(session: RemoteSession) {
        if (dao.countFor(session.id) > 0) return
        dao.insert(
            ConnectionEntity(
                sessionId = session.id,
                peerName = session.peerName,
                role = session.role.name.lowercase(),
                scopes = scopesToCsv(session.scopes),
                startedAt = clock(),
                endedAt = null,
                outcome = "active",
            ),
        )
    }

    override suspend fun recordEnd(sessionId: String, outcome: String) {
        dao.finish(sessionId, clock(), outcome)
    }

    override suspend fun clearHistory() = dao.clear()
}
