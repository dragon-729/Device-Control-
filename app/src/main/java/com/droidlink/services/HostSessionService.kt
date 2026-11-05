package com.droidlink.services

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.droidlink.DroidLinkApp
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SessionStatus
import com.droidlink.network.BinaryFrames
import com.droidlink.network.MsgType
import com.droidlink.network.RemoteMessage
import com.droidlink.network.SessionSocket
import com.droidlink.security.SessionGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that exists only while the device owner has approved a remote session.
 * It owns the relay socket, the screen capture (if the owner granted VIEW + Android's capture consent),
 * the file transfers and the dispatch of remote input. The persistent notification cannot be dismissed
 * and carries a Disconnect action.
 */
class HostSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ending = AtomicBoolean(false)
    private var running = false

    private var socket: SessionSocket? = null
    private var engine: ScreenCaptureEngine? = null
    private var transfers: FileTransferManager? = null
    private var projection: MediaProjection? = null

    private lateinit var sessionId: String
    private lateinit var peerName: String
    private var scopes: Set<Scope> = emptySet()
    private var startedAt = 0L

    private val container get() = (application as DroidLinkApp).container
    private val guard: SessionGuard get() = container.sessionGuard

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // The system restarted us without a request: there is nothing to resume (sessions are never persisted).
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> if (!running) handleStart(intent) else stopSelfResult(startId)
            ACTION_STOP -> if (running) endSession("Disconnected by the device owner", notifyServer = true) else stopSelf()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        running = true
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "Remote device" }
        scopes = Scope.parse(intent.getStringArrayExtra(EXTRA_SCOPES)?.toList().orEmpty())
        val expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, 0L)
        startedAt = System.currentTimeMillis()
        val wantsView = Scope.VIEW in scopes
        val resultCode = if (intent.hasExtra(EXTRA_MP_RESULT)) intent.getIntExtra(EXTRA_MP_RESULT, 0) else null
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_MP_DATA, Intent::class.java)

        val type = if (wantsView) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(
                this, Notifications.SESSION_NOTIFICATION_ID,
                Notifications.sessionNotification(this, peerName, scopes.joinToString { it.title }),
                type,
            )
        } catch (e: Exception) {
            container.hostController.publish(HostState.Ended("Android did not allow the sharing service to start"))
            running = false
            stopSelf()
            return
        }

        if (sessionId.isEmpty() || scopes.isEmpty() || expiresAt <= System.currentTimeMillis()) {
            endSession("Invalid session request", notifyServer = false)
            return
        }
        if (wantsView) {
            if (resultCode == null || data == null) {
                endSession("Screen capture was not authorised", notifyServer = true)
                return
            }
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projection = manager.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                endSession("Android refused the screen capture request", notifyServer = true)
                return
            }
        }
        container.hostController.publish(HostState.Connecting(peerName))
        serviceScope.launch { connect(expiresAt) }
    }

    private suspend fun connect(expiresAt: Long) {
        val sock = SessionSocket(container.wsClient)
        socket = sock
        try {
            val token = container.auth.accessToken()
            val server = container.auth.registration.value?.serverUrl ?: throw IllegalStateException("not registered")
            if (Scope.FILES in scopes) {
                transfers = FileTransferManager(
                    sessionId = sessionId,
                    channel = sock,
                    io = container.fileIo,
                    scope = serviceScope,
                    isAllowed = { guard.isAllowed(Scope.FILES) },
                    onPersist = { record -> container.appScope.launch { container.transferRepository.upsert(record) } },
                )
                container.hostController.publishFiles(transfers)
            }
            sock.connect(server, sessionId, token)
        } catch (e: Exception) {
            endSession("Could not reach the relay server", notifyServer = false)
            return
        }
        serviceScope.launch {
            val remaining = expiresAt - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            endSession("Session time limit reached", notifyServer = true)
        }
        sock.events.collect { event ->
            when (event) {
                is SessionSocket.Event.Open -> onOpen(expiresAt)
                is SessionSocket.Event.Text -> event.message?.let { route(it) }
                is SessionSocket.Event.Binary ->
                    if (event.bytes.firstOrNull() == BinaryFrames.KIND_FILE_CHUNK) transfers?.onBinary(event.bytes)
                is SessionSocket.Event.Closed -> endSession("Connection closed", notifyServer = true)
                is SessionSocket.Event.Failure -> endSession("Connection lost", notifyServer = true)
            }
        }
    }

    private fun onOpen(expiresAt: Long) {
        guard.activate(SessionGuard.Grant(sessionId, scopes, expiresAt))
        container.hostController.publish(
            HostState.Active(HostSessionInfo(sessionId, peerName, scopes, startedAt, expiresAt)),
        )
        val record = RemoteSession(
            id = sessionId, pairingId = "", role = DeviceRole.TARGET, status = SessionStatus.ACTIVE, scopes = scopes,
            peerId = "", peerName = peerName, peerModel = "", createdAt = startedAt, expiresAt = expiresAt, endReason = null,
        )
        container.appScope.launch {
            container.sessionRepository.recordStart(record)
            container.securityLog.log("session_started", "$peerName: ${scopes.joinToString { it.wire }}")
        }
        val mp = projection
        if (mp != null && guard.isAllowed(Scope.VIEW)) startCapture(mp)
    }

    private fun startCapture(mp: MediaProjection) {
        val settings = container.currentSettings
        val controller = AdaptiveStreamController(settings.defaultFps, settings.defaultQuality)
        val sock = socket ?: return
        engine = ScreenCaptureEngine(
            context = this,
            projection = mp,
            controller = controller,
            backlog = { sock.backlog() },
            onFrame = { jpeg, w, h ->
                if (guard.isAllowed(Scope.VIEW) && sock.sendBinary(BinaryFrames.encodeScreen(w, h, jpeg))) {
                    container.hostController.updateInfo { it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + jpeg.size) }
                }
            },
            onStopped = { endSession("Screen sharing was stopped from Android", notifyServer = true) },
        ).also { it.start() }
    }

    private suspend fun route(msg: RemoteMessage) {
        val sock = socket ?: return
        when (msg.t) {
            MsgType.TAP, MsgType.DOUBLE_TAP, MsgType.LONG_PRESS, MsgType.SWIPE, MsgType.SCROLL,
            MsgType.NAV, MsgType.TEXT, MsgType.KEY, MsgType.LAUNCH,
            -> {
                val outcome = withContext(Dispatchers.Main) { container.inputExecutor.execute(msg) }
                val reason = when (outcome) {
                    RemoteInputExecutor.Outcome.Ok -> null
                    is RemoteInputExecutor.Outcome.Denied -> outcome.reason
                    is RemoteInputExecutor.Outcome.Unsupported -> outcome.reason
                    is RemoteInputExecutor.Outcome.Failed -> outcome.reason
                }
                if (reason != null) sock.send(RemoteMessage(t = MsgType.ERROR, msg = reason))
            }
            MsgType.APPS_REQ -> {
                val apps = container.inputExecutor.listApps()
                if (apps != null) sock.send(RemoteMessage(t = MsgType.APPS, apps = apps))
                else sock.send(RemoteMessage(t = MsgType.ERROR, msg = "control_not_authorised"))
            }
            MsgType.QUALITY -> if (guard.isAllowed(Scope.VIEW)) {
                val s = container.currentSettings
                engine?.applySettings(msg.fps ?: s.defaultFps, msg.q ?: s.defaultQuality, msg.scale)
            }
            MsgType.INFO_REQ -> sock.send(RemoteMessage(t = MsgType.INFO, info = container.deviceInfo.collect()))
            MsgType.FILE_OFFER, MsgType.FILE_ACCEPT, MsgType.FILE_REJECT, MsgType.FILE_DONE,
            MsgType.FILE_CANCEL, MsgType.ACK, MsgType.ERROR,
            -> transfers?.onMessage(msg)
            MsgType.PEER -> {
                val online = msg.state == "online"
                container.hostController.updateInfo { it.copy(peerOnline = online) }
                if (online) engine?.resendLastFrame()
            }
            MsgType.TERMINATE -> endSession("Ended by $peerName", notifyServer = false)
        }
    }

    private fun endSession(reason: String, notifyServer: Boolean) {
        if (!ending.compareAndSet(false, true)) return
        guard.revoke() // first: no further remote action can be executed
        val id = sessionId
        transfers?.abortAll("Session ended")
        engine?.stop()
        projection = null
        socket?.send(RemoteMessage(t = MsgType.TERMINATE, msg = reason))
        socket?.close(1000, "session_ended")
        container.hostController.publishFiles(null)
        container.hostController.publish(HostState.Ended(reason))
        container.appScope.launch {
            if (notifyServer && id.isNotEmpty()) runCatching { container.sessionRepository.end(id) }
            container.sessionRepository.recordEnd(id, reason)
            container.securityLog.log("session_ended", reason)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        if (running && ending.compareAndSet(false, true)) {
            guard.revoke()
            engine?.stop()
            socket?.close(1001, "service_destroyed")
            container.hostController.publishFiles(null)
            container.hostController.publish(HostState.Ended("Sharing service stopped"))
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.droidlink.action.START_SESSION"
        const val ACTION_STOP = "com.droidlink.action.STOP_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_SCOPES = "scopes"
        const val EXTRA_EXPIRES_AT = "expires_at"
        const val EXTRA_MP_RESULT = "mp_result"
        const val EXTRA_MP_DATA = "mp_data"
    }
}
