package com.droidlink.data.repository

import com.droidlink.data.local.ConnectionEntity
import com.droidlink.data.local.PairedDeviceEntity
import com.droidlink.data.local.SecurityEventEntity
import com.droidlink.data.local.TransferEntity
import com.droidlink.domain.model.ConnectionRecord
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SecurityEvent
import com.droidlink.domain.model.SessionStatus
import com.droidlink.domain.model.TransferDirection
import com.droidlink.domain.model.TransferRecord
import com.droidlink.domain.model.TransferStatus
import com.droidlink.network.PairingDto
import com.droidlink.network.SessionDto

private fun Double.toMillis(): Long = (this * 1000).toLong()

internal fun scopesToCsv(scopes: Set<Scope>): String = scopes.joinToString(",") { it.wire }
internal fun csvToScopes(csv: String): Set<Scope> = Scope.parse(csv.split(',').filter { it.isNotBlank() })

fun PairingDto.toEntity() = PairedDeviceEntity(
    pairingId = id,
    peerId = peer.id.orEmpty(),
    peerName = peer.name ?: "Unknown device",
    peerModel = peer.model.orEmpty(),
    role = role,
    status = status,
    scopes = scopes.joinToString(","),
    createdAt = createdAt.toMillis(),
    updatedAt = updatedAt.toMillis(),
)

fun PairedDeviceEntity.toDomain() = PairedDevice(
    pairingId = pairingId,
    peerId = peerId,
    peerName = peerName,
    peerModel = peerModel,
    role = DeviceRole.fromWire(role),
    status = PairingStatus.fromWire(status),
    scopes = csvToScopes(scopes),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PairingDto.toDomain() = toEntity().toDomain()

fun SessionDto.toDomain() = RemoteSession(
    id = id,
    pairingId = pairingId,
    role = DeviceRole.fromWire(role),
    status = SessionStatus.fromWire(status),
    scopes = Scope.parse(scopes),
    peerId = peer.id.orEmpty(),
    peerName = peer.name ?: "Unknown device",
    peerModel = peer.model.orEmpty(),
    createdAt = createdAt.toMillis(),
    expiresAt = expiresAt.toMillis(),
    endReason = endReason,
    ttlMs = if (serverTime > 0) ((expiresAt - serverTime) * 1000).toLong().coerceAtLeast(0) else 0,
)

fun ConnectionEntity.toDomain() = ConnectionRecord(
    id = id,
    sessionId = sessionId,
    peerName = peerName,
    role = DeviceRole.fromWire(role),
    scopes = csvToScopes(scopes),
    startedAt = startedAt,
    endedAt = endedAt,
    outcome = outcome,
)

fun TransferEntity.toDomain() = TransferRecord(
    id = id,
    sessionId = sessionId,
    fileName = fileName,
    totalBytes = totalBytes,
    transferredBytes = transferredBytes,
    direction = TransferDirection.valueOf(direction),
    status = TransferStatus.valueOf(status),
    startedAt = startedAt,
    finishedAt = finishedAt,
    detail = detail,
)

fun TransferRecord.toEntity() = TransferEntity(
    id = id,
    sessionId = sessionId,
    fileName = fileName,
    totalBytes = totalBytes,
    transferredBytes = transferredBytes,
    direction = direction.name,
    status = status.name,
    startedAt = startedAt,
    finishedAt = finishedAt,
    detail = detail,
)

fun SecurityEventEntity.toDomain() = SecurityEvent(id, timestamp, type, detail)
