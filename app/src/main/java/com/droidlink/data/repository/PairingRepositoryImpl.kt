package com.droidlink.data.repository

import com.droidlink.data.local.PairedDeviceDao
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingCode
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.Scope
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.network.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PairingRepositoryImpl(
    private val api: ApiClient,
    private val dao: PairedDeviceDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PairingRepository {

    override val devices: Flow<List<PairedDevice>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun refresh() {
        val remote = api.pairings()
        dao.replaceAll(remote.map { it.toEntity() })
    }

    override suspend fun createCode(): PairingCode {
        val r = api.createPairingCode()
        return PairingCode(r.code, clock() + r.expiresIn * 1000L, r.qrPayload)
    }

    override suspend fun claim(code: String): PairedDevice {
        val dto = api.claim(code)
        dao.upsert(dto.toEntity())
        return dto.toDomain()
    }

    override suspend fun approve(pairingId: String, scopes: Set<Scope>): PairedDevice {
        val dto = api.approvePairing(pairingId, scopes.map { it.wire })
        dao.upsert(dto.toEntity())
        return dto.toDomain()
    }

    override suspend fun reject(pairingId: String) {
        dao.upsert(api.rejectPairing(pairingId).toEntity())
    }

    override suspend fun updateScopes(pairingId: String, scopes: Set<Scope>): PairedDevice {
        val dto = api.updatePairingScopes(pairingId, scopes.map { it.wire })
        dao.upsert(dto.toEntity())
        return dto.toDomain()
    }

    override suspend fun revoke(pairingId: String) {
        dao.upsert(api.revokePairing(pairingId).toEntity())
    }

    override suspend fun pendingIncoming(): List<PairedDevice> {
        val remote = api.pairings()
        dao.replaceAll(remote.map { it.toEntity() })
        return remote.map { it.toDomain() }.filter { it.role == DeviceRole.TARGET && it.status == PairingStatus.PENDING }
    }
}
