package com.droidlink.domain.model

import kotlinx.serialization.Serializable

enum class Scope(val wire: String, val title: String, val description: String) {
    VIEW("view", "View screen", "See this device's screen live"),
    CONTROL("control", "Control", "Tap, swipe, type and open apps (needs the Accessibility service)"),
    FILES("files", "File transfer", "Send and receive files you pick");

    companion object {
        fun fromWire(value: String): Scope? = entries.firstOrNull { it.wire == value }
        fun parse(values: Collection<String>): Set<Scope> = values.mapNotNull { fromWire(it) }.toSet()
    }
}

enum class DeviceRole { TARGET, CONTROLLER;
    companion object { fun fromWire(v: String) = if (v == "target") TARGET else CONTROLLER }
}

enum class PairingStatus { PENDING, ACTIVE, REJECTED, REVOKED;
    companion object { fun fromWire(v: String) = entries.firstOrNull { it.name.equals(v, true) } ?: REVOKED }
}

enum class SessionStatus { PENDING, ACTIVE, ENDED, REJECTED, EXPIRED;
    companion object { fun fromWire(v: String) = entries.firstOrNull { it.name.equals(v, true) } ?: ENDED }
}

/** A pairing between this device and a peer. [role] is MY role: TARGET = peer may control me. */
data class PairedDevice(
    val pairingId: String,
    val peerId: String,
    val peerName: String,
    val peerModel: String,
    val role: DeviceRole,
    val status: PairingStatus,
    val scopes: Set<Scope>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class RemoteSession(
    val id: String,
    val pairingId: String,
    val role: DeviceRole,
    val status: SessionStatus,
    val scopes: Set<Scope>,
    val peerId: String,
    val peerName: String,
    val peerModel: String,
    val createdAt: Long,
    val expiresAt: Long,
    val endReason: String?,
    /** Remaining lifetime according to the server's clock, so local clock skew cannot cut sessions short. */
    val ttlMs: Long = 0,
)

data class PairingCode(val code: String, val expiresAtMs: Long, val qrPayload: String)

data class ConnectionRecord(
    val id: Long,
    val sessionId: String,
    val peerName: String,
    val role: DeviceRole,
    val scopes: Set<Scope>,
    val startedAt: Long,
    val endedAt: Long?,
    val outcome: String,
)

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, REJECTED }

data class TransferRecord(
    val id: String,
    val sessionId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val detail: String?,
)

data class SecurityEvent(val id: Long, val timestamp: Long, val type: String, val detail: String)

data class Registration(val deviceId: String, val deviceName: String, val serverUrl: String)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val requireReauthForApproval: Boolean = true,
    val appLockEnabled: Boolean = false,
    val defaultFps: Int = 12,
    val defaultQuality: Int = 60,
    val onboardingDone: Boolean = false,
)

/** Only what the dashboard needs; no identifiers (IMEI, serial, accounts) are ever collected. */
@Serializable
data class DeviceInfo(
    val model: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val storageTotal: Long = 0,
    val storageFree: Long = 0,
    val ramTotal: Long = 0,
    val ramFree: Long = 0,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val network: String = "Unknown",
    val appVersion: String = "",
    val accessibilityEnabled: Boolean = false,
)

@Serializable
data class AppEntry(val l: String, val p: String)
