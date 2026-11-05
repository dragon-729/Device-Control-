package com.droidlink.domain.repository

import com.droidlink.domain.model.AppSettings
import com.droidlink.domain.model.ConnectionRecord
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingCode
import com.droidlink.domain.model.Registration
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SecurityEvent
import com.droidlink.domain.model.ThemeMode
import com.droidlink.domain.model.TransferRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val registration: StateFlow<Registration?>
    suspend fun register(serverUrl: String, deviceName: String, inviteCode: String?): Registration
    /** Returns a valid short-lived access token, refreshing it when needed. */
    suspend fun accessToken(forceRefresh: Boolean = false): String
    suspend fun signOut()
}

interface PairingRepository {
    val devices: Flow<List<PairedDevice>>
    suspend fun refresh()
    suspend fun createCode(): PairingCode
    suspend fun claim(code: String): PairedDevice
    suspend fun approve(pairingId: String, scopes: Set<Scope>): PairedDevice
    suspend fun reject(pairingId: String)
    suspend fun updateScopes(pairingId: String, scopes: Set<Scope>): PairedDevice
    suspend fun revoke(pairingId: String)
    /** Pairings waiting for MY approval (I am the target). */
    suspend fun pendingIncoming(): List<PairedDevice>
}

interface SessionRepository {
    suspend fun request(pairingId: String, scopes: Set<Scope>): RemoteSession
    suspend fun get(sessionId: String): RemoteSession
    suspend fun pendingForMe(): List<RemoteSession>
    suspend fun approve(sessionId: String, scopes: Set<Scope>): RemoteSession
    suspend fun reject(sessionId: String)
    suspend fun end(sessionId: String)
    val history: Flow<List<ConnectionRecord>>
    suspend fun recordStart(session: RemoteSession)
    suspend fun recordEnd(sessionId: String, outcome: String)
    suspend fun clearHistory()
}

interface TransferRepository {
    val transfers: Flow<List<TransferRecord>>
    suspend fun upsert(record: TransferRecord)
    suspend fun clear()
}

interface SecurityLogRepository {
    val events: Flow<List<SecurityEvent>>
    suspend fun log(type: String, detail: String = "")
    suspend fun clear()
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setRequireReauth(value: Boolean)
    suspend fun setAppLock(value: Boolean)
    suspend fun setStreamDefaults(fps: Int, quality: Int)
    suspend fun setOnboardingDone()
}
