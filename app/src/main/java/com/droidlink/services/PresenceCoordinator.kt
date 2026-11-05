package com.droidlink.services

import com.droidlink.data.repository.toDomain
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.SessionStatus
import com.droidlink.domain.repository.AuthRepository
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.domain.repository.SessionRepository
import com.droidlink.network.ProtocolJson
import com.droidlink.network.ServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Keeps a presence WebSocket open while the app is in the foreground so that pairing and session
 * requests arrive instantly. Pending items are also re-fetched over REST after every (re)connect,
 * so nothing is lost if the socket was down. Reconnects with exponential back-off.
 */
class PresenceCoordinator(
    private val wsClient: OkHttpClient,
    private val auth: AuthRepository,
    private val pairing: PairingRepository,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _pendingPairings = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pendingPairings: StateFlow<List<PairedDevice>> = _pendingPairings.asStateFlow()

    private val _pendingSessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val pendingSessions: StateFlow<List<RemoteSession>> = _pendingSessions.asStateFlow()

    private val _sessionUpdates = MutableSharedFlow<RemoteSession>(extraBufferCapacity = 16)
    val sessionUpdates: SharedFlow<RemoteSession> = _sessionUpdates.asSharedFlow()

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    fun dismissPairing(id: String) = _pendingPairings.update { l -> l.filterNot { it.pairingId == id } }
    fun dismissSession(id: String) = _pendingSessions.update { l -> l.filterNot { it.id == id } }

    suspend fun refreshPending() {
        try {
            _pendingPairings.value = pairing.pendingIncoming()
            _pendingSessions.value = sessions.pendingForMe()
        } catch (e: Exception) {
            // Offline or signed out: keep what we have; the next reconnect refreshes again.
        }
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (scope.isActive) {
            val server = auth.registration.value?.serverUrl
            if (server == null) {
                delay(2_000)
                continue
            }
            try {
                val token = auth.accessToken()
                val closed = CompletableDeferred<Unit>()
                val request = Request.Builder()
                    .url(server.trimEnd('/') + "/api/v1/ws/presence")
                    .header("Authorization", "Bearer $token")
                    .build()
                val ws = wsClient.newWebSocket(request, listener(closed))
                try {
                    closed.await()
                } finally {
                    ws.cancel()
                    _connected.value = false
                }
                backoff = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // network or auth problem: back off and retry
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun listener(closed: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.value = true
            scope.launch { refreshPending() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "pong") return
            val event = try {
                ProtocolJson.decodeFromString(ServerEvent.serializer(), text)
            } catch (e: Exception) {
                return
            }
            scope.launch { handle(event) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(Unit)
        }
    }

    private suspend fun handle(event: ServerEvent) {
        when (event.event) {
            "pairing_request", "pairing_update" -> {
                runCatching { pairing.refresh() }
                val p = event.pairing?.toDomain() ?: return
                if (p.role == DeviceRole.TARGET && p.status == PairingStatus.PENDING) {
                    _pendingPairings.update { l -> l.filterNot { it.pairingId == p.pairingId } + p }
                } else {
                    dismissPairing(p.pairingId)
                }
            }
            "session_request", "session_update" -> {
                val s = event.session?.toDomain() ?: return
                if (s.role == DeviceRole.TARGET && s.status == SessionStatus.PENDING) {
                    _pendingSessions.update { l -> l.filterNot { it.id == s.id } + s }
                } else {
                    dismissSession(s.id)
                }
                _sessionUpdates.tryEmit(s)
            }
        }
    }
}
