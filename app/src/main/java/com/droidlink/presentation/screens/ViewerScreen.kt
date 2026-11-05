@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidlink.presentation.screens

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.domain.model.AppEntry
import com.droidlink.domain.model.DeviceInfo
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SessionStatus
import com.droidlink.network.BinaryFrames
import com.droidlink.network.KeyAction
import com.droidlink.network.MsgType
import com.droidlink.network.NavAction
import com.droidlink.network.RemoteMessage
import com.droidlink.network.SessionSocket
import com.droidlink.presentation.common.EmptyState
import com.droidlink.presentation.common.LoadingDots
import com.droidlink.presentation.common.StatusBadge
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.remoteErrorText
import com.droidlink.presentation.common.statusColor
import com.droidlink.presentation.common.userMessage
import com.droidlink.presentation.theme.Danger
import com.droidlink.services.FileTransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class RemoteFrame(val image: ImageBitmap, val width: Int, val height: Int, val seq: Long)

data class ViewerUiState(
    val status: Status = Status.CONNECTING,
    val session: RemoteSession? = null,
    val peerOnline: Boolean = false,
    val endReason: String? = null,
    val notice: String? = null,
    val apps: List<AppEntry>? = null,
    val remoteInfo: DeviceInfo? = null,
    val fps: Int = 12,
    val quality: Int = 60,
    val scale: Int = 60,
    val framesPerSecond: Int = 0,
) {
    enum class Status { CONNECTING, LIVE, ENDED }
    val canView get() = session?.scopes?.contains(Scope.VIEW) == true
    val canControl get() = session?.scopes?.contains(Scope.CONTROL) == true
    val canTransfer get() = session?.scopes?.contains(Scope.FILES) == true
}

class ViewerViewModel(private val c: AppContainer, private val sessionId: String) : ViewModel() {
    private val _state = MutableStateFlow(ViewerUiState(fps = c.currentSettings.defaultFps, quality = c.currentSettings.defaultQuality))
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()
    private val _frame = MutableStateFlow<RemoteFrame?>(null)
    val frame: StateFlow<RemoteFrame?> = _frame.asStateFlow()

    private val socket = SessionSocket(c.wsClient)
    var transfers: FileTransferManager? = null
        private set
    private val decoding = AtomicBoolean(false)
    @Volatile private var pendingFrame: ByteArray? = null
    private var frameSeq = 0L
    private var framesInWindow = 0
    private var windowStart = SystemClock.elapsedRealtime()
    private val ended = AtomicBoolean(false)

    init {
        viewModelScope.launch { connect() }
    }

    private suspend fun connect() {
        try {
            val session = c.sessionRepository.get(sessionId)
            if (session.status != SessionStatus.ACTIVE || session.role != DeviceRole.CONTROLLER) {
                return finish("This session is no longer active.")
            }
            _state.update { it.copy(session = session) }
            if (Scope.FILES in session.scopes) {
                transfers = FileTransferManager(
                    sessionId = sessionId, channel = socket, io = c.fileIo, scope = viewModelScope,
                    isAllowed = { _state.value.status != ViewerUiState.Status.ENDED },
                    onPersist = { r -> c.appScope.launch { c.transferRepository.upsert(r) } },
                )
            }
            val token = c.auth.accessToken()
            val server = c.auth.registration.value?.serverUrl ?: return finish("This device is not registered.")
            socket.connect(server, sessionId, token)
            socket.events.collect { handle(it) }
        } catch (e: Exception) {
            finish(e.userMessage())
        }
    }

    private suspend fun handle(event: SessionSocket.Event) {
        when (event) {
            is SessionSocket.Event.Open -> {
                _state.update { it.copy(status = ViewerUiState.Status.LIVE) }
                _state.value.session?.let { c.sessionRepository.recordStart(it) }
                c.securityLog.log("session_connected", _state.value.session?.peerName.orEmpty())
                socket.send(RemoteMessage(t = MsgType.INFO_REQ))
            }
            is SessionSocket.Event.Binary -> when (event.bytes.firstOrNull()) {
                BinaryFrames.KIND_SCREEN -> onFrameBytes(event.bytes)
                BinaryFrames.KIND_FILE_CHUNK -> transfers?.onBinary(event.bytes)
            }
            is SessionSocket.Event.Text -> event.message?.let { onMessage(it) }
            is SessionSocket.Event.Closed -> finish(if (_state.value.endReason == null) "The session was closed." else null)
            is SessionSocket.Event.Failure -> finish("Connection lost.")
        }
    }

    private fun onMessage(m: RemoteMessage) {
        when (m.t) {
            MsgType.PEER -> _state.update { it.copy(peerOnline = m.state == "online") }
            MsgType.ERROR -> if (m.id == null) _state.update { it.copy(notice = remoteErrorText(m.msg.orEmpty())) } else transfers?.onMessage(m)
            MsgType.APPS -> _state.update { it.copy(apps = m.apps.orEmpty()) }
            MsgType.INFO -> _state.update { it.copy(remoteInfo = m.info) }
            MsgType.TERMINATE -> finish(m.msg ?: "The other device ended the session.")
            MsgType.FILE_OFFER, MsgType.FILE_ACCEPT, MsgType.FILE_REJECT, MsgType.FILE_DONE, MsgType.FILE_CANCEL, MsgType.ACK ->
                transfers?.onMessage(m)
        }
    }

    // Only the newest frame is decoded; older ones are dropped so the view never lags behind.
    private fun onFrameBytes(bytes: ByteArray) {
        pendingFrame = bytes
        if (!decoding.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val next = pendingFrame
                if (next == null) {
                    decoding.set(false)
                    if (pendingFrame != null && decoding.compareAndSet(false, true)) continue
                    break
                }
                pendingFrame = null
                val parsed = BinaryFrames.decodeScreen(next) ?: continue
                val bmp = BitmapFactory.decodeByteArray(parsed.jpeg, 0, parsed.jpeg.size) ?: continue
                _frame.value = RemoteFrame(bmp.asImageBitmap(), parsed.width, parsed.height, ++frameSeq)
                framesInWindow++
                val now = SystemClock.elapsedRealtime()
                if (now - windowStart >= 1000) {
                    val fps = framesInWindow
                    framesInWindow = 0
                    windowStart = now
                    _state.update { it.copy(framesPerSecond = fps) }
                }
            }
        }
    }

    // ---- commands -------------------------------------------------------------------------------
    private fun send(msg: RemoteMessage) {
        if (_state.value.status == ViewerUiState.Status.LIVE) socket.send(msg)
    }

    private fun sendControl(msg: RemoteMessage) {
        if (_state.value.canControl) send(msg)
    }

    fun tap(x: Float, y: Float) = sendControl(RemoteMessage(t = MsgType.TAP, x = x, y = y))
    fun doubleTap(x: Float, y: Float) = sendControl(RemoteMessage(t = MsgType.DOUBLE_TAP, x = x, y = y))
    fun longPress(x: Float, y: Float) = sendControl(RemoteMessage(t = MsgType.LONG_PRESS, x = x, y = y))
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        sendControl(RemoteMessage(t = MsgType.SWIPE, x = x1, y = y1, x2 = x2, y2 = y2, d = durationMs))
    fun scroll(direction: String) = sendControl(RemoteMessage(t = MsgType.SCROLL, a = direction))
    fun nav(action: String) = sendControl(RemoteMessage(t = MsgType.NAV, a = action))
    fun typeText(text: String) = sendControl(RemoteMessage(t = MsgType.TEXT, s = text))
    fun key(action: String) = sendControl(RemoteMessage(t = MsgType.KEY, k = action))
    fun launch(pkg: String) = sendControl(RemoteMessage(t = MsgType.LAUNCH, pkg = pkg))
    fun requestApps() = sendControl(RemoteMessage(t = MsgType.APPS_REQ))
    fun requestInfo() = send(RemoteMessage(t = MsgType.INFO_REQ))

    fun setQuality(fps: Int, quality: Int, scale: Int) {
        _state.update { it.copy(fps = fps, quality = quality, scale = scale) }
        send(RemoteMessage(t = MsgType.QUALITY, fps = fps, q = quality, scale = scale))
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun disconnect() = finish("You disconnected.", notifyPeer = true)

    private fun finish(reason: String?, notifyPeer: Boolean = false) {
        if (!ended.compareAndSet(false, true)) return
        val text = reason ?: "The session ended."
        _state.update { it.copy(status = ViewerUiState.Status.ENDED, endReason = text) }
        if (notifyPeer) socket.send(RemoteMessage(t = MsgType.TERMINATE, msg = "Controller disconnected"))
        transfers?.abortAll("Session ended")
        socket.close(1000, "bye")
        c.appScope.launch {
            runCatching { c.sessionRepository.end(sessionId) }
            c.sessionRepository.recordEnd(sessionId, text)
            c.securityLog.log("session_ended", text)
        }
    }

    override fun onCleared() {
        finish("Viewer closed.", notifyPeer = true)
    }
}

@Composable
fun ViewerScreen(sessionId: String, onExit: () -> Unit) {
    val vm = appViewModel(key = "viewer-$sessionId") { ViewerViewModel(it, sessionId) }
    val state by vm.state.collectAsState()
    val frame by vm.frame.collectAsState()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    BackHandler { vm.disconnect(); onExit() }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(3500)
            vm.clearNotice()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.session?.peerName ?: "Connecting...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(
                        when (state.status) {
                            ViewerUiState.Status.CONNECTING -> "Connecting"
                            ViewerUiState.Status.LIVE -> if (state.peerOnline) "Live - ${state.framesPerSecond} fps" else "Waiting for device"
                            ViewerUiState.Status.ENDED -> "Ended"
                        },
                        statusColor(if (state.status == ViewerUiState.Status.LIVE && state.peerOnline) true else null),
                    )
                }
            }
            Button(
                onClick = { vm.disconnect(); onExit() },
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
            ) { Text("Disconnect") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.status == ViewerUiState.Status.ENDED -> EmptyState(
                    Icons.Filled.Close, "Session ended", state.endReason.orEmpty(),
                    action = { Button(onClick = onExit) { Text("Back") } },
                )
                !state.canView -> EmptyState(Icons.Filled.ViewStream, "Screen viewing not granted", "This session allows: ${state.session?.scopes?.joinToString { it.title }.orEmpty()}")
                frame == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingDots(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("Waiting for the first frame...", color = Color.White.copy(alpha = 0.8f))
                }
                else -> RemoteScreen(frame!!, state.canControl, vm)
            }
            state.notice?.let {
                Text(
                    it, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC000000)).padding(10.dp),
                )
            }
        }

        // control bar
        if (state.status != ViewerUiState.Status.ENDED) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF111827)).navigationBarsPadding().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val control = state.canControl
                BarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", control) { vm.nav(NavAction.BACK) }
                BarButton(Icons.Filled.Home, "Home", control) { vm.nav(NavAction.HOME) }
                BarButton(Icons.Filled.ViewStream, "Recents", control) { vm.nav(NavAction.RECENTS) }
                BarButton(Icons.Filled.Keyboard, "Keyboard", control) { sheet = Sheet.KEYBOARD }
                BarButton(Icons.Filled.Apps, "Apps", control) { vm.requestApps(); sheet = Sheet.APPS }
                BarButton(Icons.Filled.SwapVert, "Files", state.canTransfer) { sheet = Sheet.FILES }
                BarButton(Icons.Filled.Tune, "More", true) { sheet = Sheet.MORE }
            }
        }
    }

    sheet?.let { current ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                when (current) {
                    Sheet.KEYBOARD -> KeyboardSheet(vm)
                    Sheet.APPS -> AppsSheet(state.apps, onLaunch = { vm.launch(it); sheet = null })
                    Sheet.FILES -> vm.transfers?.let { FileTransferPanel(it, state.session?.peerName ?: "device") }
                    Sheet.MORE -> MoreSheet(state, vm)
                }
            }
        }
    }
}

private enum class Sheet { KEYBOARD, APPS, FILES, MORE }

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
    }
}

/** Draws the remote frame and translates touches into normalised remote gestures. */
@Composable
private fun RemoteScreen(frame: RemoteFrame, control: Boolean, vm: ViewerViewModel) {
    var box by remember { mutableStateOf(IntSize.Zero) }

    // Rect of the displayed frame inside the box (aspect-fit)
    fun layout(): Triple<Float, Float, Float> {
        val scale = min(box.width / frame.width.toFloat(), box.height / frame.height.toFloat())
        val w = frame.width * scale
        val h = frame.height * scale
        return Triple((box.width - w) / 2f, (box.height - h) / 2f, scale)
    }

    fun norm(p: Offset): Offset? {
        val (ox, oy, scale) = layout()
        val nx = (p.x - ox) / (frame.width * scale)
        val ny = (p.y - oy) / (frame.height * scale)
        return if (nx in 0f..1f && ny in 0f..1f) Offset(nx, ny) else null
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(frame.width, frame.height, control) {
                if (control) detectTapGestures(
                    onTap = { norm(it)?.let { n -> vm.tap(n.x, n.y) } },
                    onDoubleTap = { norm(it)?.let { n -> vm.doubleTap(n.x, n.y) } },
                    onLongPress = { norm(it)?.let { n -> vm.longPress(n.x, n.y) } },
                )
            }
            .pointerInput(frame.width, frame.height, control) {
                if (control) {
                    var start = Offset.Zero
                    var last = Offset.Zero
                    var t0 = 0L
                    detectDragGestures(
                        onDragStart = { start = it; last = it; t0 = SystemClock.elapsedRealtime() },
                        onDrag = { change, _ -> last = change.position },
                        onDragEnd = {
                            val a = norm(start)
                            val b = norm(last)
                            if (a != null && b != null) vm.swipe(a.x, a.y, b.x, b.y, SystemClock.elapsedRealtime() - t0)
                        },
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width / frame.width, size.height / frame.height)
            val w = (frame.width * scale).toInt()
            val h = (frame.height * scale).toInt()
            drawImage(
                frame.image,
                dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

@Composable
private fun KeyboardSheet(vm: ViewerViewModel) {
    var text by remember { mutableStateOf("") }
    Text("Type on the other device", style = MaterialTheme.typography.titleMedium)
    Text(
        "Text goes into the field that is focused there. Password fields are blocked, and nothing you type is stored.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(value = text, onValueChange = { text = it.take(2000) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Text to send") })
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (text.isNotEmpty()) { vm.typeText(text); text = "" } }, enabled = text.isNotEmpty()) { Text("Send text") }
        OutlinedButton(onClick = { vm.key(KeyAction.ENTER) }) { Text("Enter") }
        OutlinedButton(onClick = { vm.key(KeyAction.BACKSPACE) }) { Text("Backspace") }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { vm.key(KeyAction.SELECT_ALL) }) { Text("Select all") }
        TextButton(onClick = { vm.key(KeyAction.COPY) }) { Text("Copy") }
        TextButton(onClick = { vm.key(KeyAction.CUT) }) { Text("Cut") }
        TextButton(onClick = { vm.key(KeyAction.PASTE) }) { Text("Paste") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { vm.key(KeyAction.PREV_FIELD) }) { Text("Previous field") }
        TextButton(onClick = { vm.key(KeyAction.NEXT_FIELD) }) { Text("Next field") }
    }
}

@Composable
private fun AppsSheet(apps: List<AppEntry>?, onLaunch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Text("Open an app", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    when {
        apps == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingDots() }
        apps.isEmpty() -> Text("No launchable apps were reported.")
        else -> LazyColumn(Modifier.height(320.dp)) {
            items(apps.filter { query.isBlank() || it.l.contains(query, ignoreCase = true) }, key = { it.p }) { app ->
                TextButton(onClick = { onLaunch(app.p) }, modifier = Modifier.fillMaxWidth()) {
                    Text(app.l, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
            }
        }
    }
}

@Composable
private fun MoreSheet(state: ViewerUiState, vm: ViewerViewModel) {
    var fps by remember { mutableFloatStateOf(state.fps.toFloat()) }
    var quality by remember { mutableFloatStateOf(state.quality.toFloat()) }
    var scale by remember { mutableFloatStateOf(state.scale.toFloat()) }
    Text("Stream quality", style = MaterialTheme.typography.titleMedium)
    Text("Lower values use less data. The device also lowers quality automatically when the network is slow.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Frame rate: ${fps.toInt()} fps")
    Slider(value = fps, onValueChange = { fps = it }, valueRange = 2f..30f, onValueChangeFinished = { vm.setQuality(fps.toInt(), quality.toInt(), scale.toInt()) })
    Text("JPEG quality: ${quality.toInt()}")
    Slider(value = quality, onValueChange = { quality = it }, valueRange = 20f..95f, onValueChangeFinished = { vm.setQuality(fps.toInt(), quality.toInt(), scale.toInt()) })
    Text("Resolution: ${scale.toInt()}%")
    Slider(value = scale, onValueChange = { scale = it }, valueRange = 25f..100f, onValueChangeFinished = { vm.setQuality(fps.toInt(), quality.toInt(), scale.toInt()) })
    if (state.canControl) {
        Text("Scroll", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.scroll("up") }) { Icon(Icons.Filled.KeyboardArrowUp, "Scroll up") }
            OutlinedButton(onClick = { vm.scroll("down") }) { Icon(Icons.Filled.KeyboardArrowDown, "Scroll down") }
            OutlinedButton(onClick = { vm.scroll("left") }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Scroll left") }
            OutlinedButton(onClick = { vm.nav(NavAction.NOTIFICATIONS) }) { Icon(Icons.Filled.Notifications, "Notifications") }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Info, null)
        Text("  Remote device", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = vm::requestInfo) { Text("Refresh") }
    }
    state.remoteInfo?.let { DeviceInfoGrid(it) } ?: Text("Waiting for device information...")
}
