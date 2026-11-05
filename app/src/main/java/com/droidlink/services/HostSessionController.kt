package com.droidlink.services

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.droidlink.domain.model.Scope
import com.droidlink.security.SessionGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HostSessionInfo(
    val sessionId: String,
    val peerName: String,
    val scopes: Set<Scope>,
    val startedAt: Long,
    val expiresAt: Long,
    val peerOnline: Boolean = false,
    val framesSent: Long = 0,
    val bytesSent: Long = 0,
)

sealed interface HostState {
    data object Idle : HostState
    data class Connecting(val peerName: String) : HostState
    data class Active(val info: HostSessionInfo) : HostState
    data class Ended(val reason: String) : HostState
}

class HostStartRequest(
    val sessionId: String,
    val peerName: String,
    val scopes: Set<Scope>,
    val expiresAtMs: Long,
    val projectionResultCode: Int? = null,
    val projectionData: Intent? = null,
)

/** UI-facing handle to the (single) foreground host session. */
class HostSessionController(private val appContext: Context, private val guard: SessionGuard) {
    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _files = MutableStateFlow<FileTransferManager?>(null)
    /** File manager of the running session (null when no session or files not granted). */
    val files: StateFlow<FileTransferManager?> = _files.asStateFlow()

    fun isRunning(): Boolean = _state.value is HostState.Connecting || _state.value is HostState.Active

    fun start(request: HostStartRequest) {
        val intent = Intent(appContext, HostSessionService::class.java).setAction(HostSessionService.ACTION_START)
            .putExtra(HostSessionService.EXTRA_SESSION_ID, request.sessionId)
            .putExtra(HostSessionService.EXTRA_PEER_NAME, request.peerName)
            .putExtra(HostSessionService.EXTRA_SCOPES, request.scopes.map { it.wire }.toTypedArray())
            .putExtra(HostSessionService.EXTRA_EXPIRES_AT, request.expiresAtMs)
        if (request.projectionResultCode != null && request.projectionData != null) {
            intent.putExtra(HostSessionService.EXTRA_MP_RESULT, request.projectionResultCode)
            intent.putExtra(HostSessionService.EXTRA_MP_DATA, request.projectionData)
        }
        _state.value = HostState.Connecting(request.peerName)
        ContextCompat.startForegroundService(appContext, intent)
    }

    /** Emergency disconnect: ends the session on both devices immediately. */
    fun emergencyDisconnect() {
        guard.revoke()
        appContext.startService(Intent(appContext, HostSessionService::class.java).setAction(HostSessionService.ACTION_STOP))
    }

    /** Keeps the screen share running but immediately stops all remote input. */
    fun pauseControl() = guard.downgrade(Scope.CONTROL)

    internal fun publish(state: HostState) {
        _state.value = state
    }

    internal fun publishFiles(manager: FileTransferManager?) {
        _files.value = manager
    }

    internal fun updateInfo(transform: (HostSessionInfo) -> HostSessionInfo) {
        _state.update { s -> if (s is HostState.Active) HostState.Active(transform(s.info)) else s }
    }

    fun acknowledgeEnded() {
        if (_state.value is HostState.Ended) _state.value = HostState.Idle
    }
}
