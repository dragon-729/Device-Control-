package com.droidlink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val pairingId: String,
    val peerId: String,
    val peerName: String,
    val peerModel: String,
    val role: String,
    val status: String,
    val scopes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "connection_history")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val peerName: String,
    val role: String,
    val scopes: String,
    val startedAt: Long,
    val endedAt: Long?,
    val outcome: String,
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val direction: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val detail: String?,
)

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val detail: String,
)
