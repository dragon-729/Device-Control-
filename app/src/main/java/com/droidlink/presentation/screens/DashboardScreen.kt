@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidlink.presentation.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.R
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.LocalActivityActions
import com.droidlink.presentation.common.ScopeSelector
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.SectionTitle
import com.droidlink.presentation.common.StatusBadge
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.statusColor
import com.droidlink.presentation.common.userMessage
import com.droidlink.presentation.theme.Danger
import com.droidlink.security.Reauthenticator
import com.droidlink.services.HostSessionInfo
import com.droidlink.services.HostStartRequest
import com.droidlink.services.HostState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    val pendingPairings = c.presence.pendingPairings
    val pendingSessions = c.presence.pendingSessions
    val presenceConnected = c.presence.connected
    val hostState: StateFlow<HostState> = c.hostController.state
    val registration = c.auth.registration
    val online = c.networkMonitor.online.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.networkMonitor.isOnline())
    val settings = c.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.currentSettings)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch { c.presence.refreshPending() }
    }

    fun accessibilityEnabled(): Boolean = c.inputExecutor.isServiceEnabled()

    fun approvePairing(p: PairedDevice, scopes: Set<Scope>) = viewModelScope.launch {
        try {
            c.approvePairing(p.pairingId, scopes, p.peerName)
            c.presence.dismissPairing(p.pairingId)
            _messages.emit("${p.peerName} can now request access to this device")
        } catch (e: Exception) {
            _messages.emit(e.userMessage())
        }
    }

    fun rejectPairing(p: PairedDevice) = viewModelScope.launch {
        try {
            c.pairingRepository.reject(p.pairingId)
            c.securityLog.log("pairing_rejected", p.peerName)
            c.presence.dismissPairing(p.pairingId)
        } catch (e: Exception) {
            _messages.emit(e.userMessage())
        }
    }

    fun approveSession(s: RemoteSession, scopes: Set<Scope>, resultCode: Int?, data: Intent?) = viewModelScope.launch {
        try {
            val approved = c.approveSession(s.id, scopes, s.peerName)
            c.presence.dismissSession(s.id)
            val ttl = if (approved.ttlMs > 0) approved.ttlMs else 10 * 60_000L
            c.hostController.start(
                HostStartRequest(approved.id, s.peerName, approved.scopes, System.currentTimeMillis() + ttl, resultCode, data),
            )
        } catch (e: Exception) {
            _messages.emit(e.userMessage())
        }
    }

    fun rejectSession(s: RemoteSession) = viewModelScope.launch {
        try {
            c.sessionRepository.reject(s.id)
            c.securityLog.log("session_rejected", s.peerName)
            c.presence.dismissSession(s.id)
        } catch (e: Exception) {
            _messages.emit(e.userMessage())
        }
    }

    fun message(text: String) { _messages.tryEmit(text) }
    fun emergencyDisconnect() = c.hostController.emergencyDisconnect()
    fun pauseControl() = c.hostController.pauseControl()
    fun acknowledgeEnded() = c.hostController.acknowledgeEnded()
}

private data class Tile(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

@Composable
fun DashboardScreen(onNavigate: (String) -> Unit) {
    val vm = appViewModel { DashboardViewModel(it) }
    val actions = LocalActivityActions.current
    val snackbar = remember { SnackbarHostState() }
    val pairings by vm.pendingPairings.collectAsState()
    val sessions by vm.pendingSessions.collectAsState()
    val connected by vm.presenceConnected.collectAsState()
    val online by vm.online.collectAsState()
    val host by vm.hostState.collectAsState()
    val settings by vm.settings.collectAsState()
    val registration by vm.registration.collectAsState()
    var a11y by remember { mutableStateOf(vm.accessibilityEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { a11y = vm.accessibilityEnabled() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(host) {
        (host as? HostState.Ended)?.let {
            snackbar.showSnackbar("Session ended: ${it.reason}")
            vm.acknowledgeEnded()
        }
    }

    var pairingToReview by remember { mutableStateOf<PairedDevice?>(null) }
    var sessionToReview by remember { mutableStateOf<RemoteSession?>(null) }

    fun withReauth(title: String, subtitle: String, proceed: () -> Unit) {
        if (!settings.requireReauthForApproval) return proceed()
        actions.reauthenticate(title, subtitle) { result ->
            when (result) {
                Reauthenticator.Result.SUCCESS, Reauthenticator.Result.UNAVAILABLE -> proceed()
                Reauthenticator.Result.FAILED -> vm.message("Not approved: identity not confirmed")
            }
        }
    }

    pairingToReview?.let { p ->
        PairingRequestDialog(
            request = p,
            onApprove = { scopes ->
                pairingToReview = null
                withReauth("Approve pairing", "Allow ${p.peerName} to request access") { vm.approvePairing(p, scopes) }
            },
            onDecline = { pairingToReview = null; vm.rejectPairing(p) },
            onDismiss = { pairingToReview = null },
        )
    }

    sessionToReview?.let { s ->
        SessionRequestDialog(
            request = s,
            accessibilityEnabled = a11y,
            onOpenAccessibility = actions::openAccessibilitySettings,
            onApprove = { granted ->
                sessionToReview = null
                withReauth("Approve remote session", "${s.peerName} will get: ${granted.joinToString { it.title }}") {
                    if (Scope.VIEW in granted) {
                        // Android shows its own consent dialog; DroidLink cannot skip or pre-answer it.
                        actions.requestScreenCapture { code, data ->
                            if (code == Activity.RESULT_OK && data != null) vm.approveSession(s, granted, code, data)
                            else vm.message("Screen capture was not allowed - session not approved")
                        }
                    } else {
                        vm.approveSession(s, granted, null, null)
                    }
                }
            },
            onDecline = { sessionToReview = null; vm.rejectSession(s) },
            onDismiss = { sessionToReview = null },
        )
    }

    val tiles = listOf(
        Tile("Pair a device", "Code or QR", Icons.Filled.Link, "pairing"),
        Tile("My devices", "Manage access", Icons.Filled.PhoneAndroid, "devices"),
        Tile("File transfer", "Send & receive", Icons.Filled.SwapVert, "transfers"),
        Tile("Device info", "This device", Icons.Filled.Info, "deviceinfo"),
        Tile("History", "Past sessions", Icons.Filled.History, "history"),
        Tile("Security", "Permissions & log", Icons.Filled.Security, "security"),
    )

    ScreenScaffold(
        title = "DroidLink",
        onBack = null,
        snackbar = snackbar,
        actions = { IconButton(onClick = { onNavigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_launcher_foreground_img), null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(registration?.deviceName ?: "This device", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusBadge(if (online) "Online" else "Offline", statusColor(online))
                                StatusBadge(if (connected) "Relay connected" else "Relay not connected", statusColor(connected))
                            }
                        }
                    }
                }
            }
            if (pairings.isNotEmpty() || sessions.isNotEmpty()) {
                item { SectionTitle("Waiting for your approval") }
                items(pairings, key = { "p" + it.pairingId }) { p ->
                    RequestCard("Pairing request", "${p.peerName} (${p.peerModel}) wants to pair with this device", "Review") { pairingToReview = p }
                }
                items(sessions, key = { "s" + it.id }) { s ->
                    RequestCard("Session request", "${s.peerName} requests: ${s.scopes.joinToString { it.title }}", "Review") { sessionToReview = s }
                }
            }
            if (!a11y) {
                item {
                    InfoCard {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(10.dp))
                                Text("Remote control is off on this device", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Other devices can only view this screen. To let an approved device tap and type here, enable DroidLink in Accessibility settings. You stay in control and can turn it off anytime.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = actions::openAccessibilitySettings) { Text("Open Accessibility settings") }
                        }
                    }
                }
            }
            item { SectionTitle("Quick actions") }
            items(tiles.chunked(2)) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t -> TileCard(t, Modifier.weight(1f)) { onNavigate(t.route) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TileCard(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(tile.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(tile.title, style = MaterialTheme.typography.titleMedium)
            Text(tile.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RequestCard(title: String, body: String, action: String, onClick: () -> Unit) {
    InfoCard {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun PairingRequestDialog(request: PairedDevice, onApprove: (Set<Scope>) -> Unit, onDecline: () -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(Scope.entries.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with ${request.peerName}?") },
        text = {
            Column {
                Text(
                    "${request.peerName} (${request.peerModel}) used your pairing code. Choose what it may ask for. You will still approve every session separately.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ScopeSelector(Scope.entries.toSet(), selected, { s -> selected = if (s in selected) selected - s else selected + s })
            }
        },
        confirmButton = { Button(onClick = { onApprove(selected) }, enabled = selected.isNotEmpty()) { Text("Approve") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

@Composable
fun SessionRequestDialog(
    request: RemoteSession,
    accessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onApprove: (Set<Scope>) -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(request.scopes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow ${request.peerName} to connect?") },
        text = {
            Column {
                Text("Untick anything you do not want to allow. A notification will stay visible and you can disconnect at any time.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                ScopeSelector(request.scopes, selected, { s -> selected = if (s in selected) selected - s else selected + s })
                if (Scope.CONTROL in selected && !accessibilityEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text("Control needs DroidLink's Accessibility service, which is currently off.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenAccessibility) { Text("Open Accessibility settings") }
                }
                if (Scope.VIEW in selected) {
                    Spacer(Modifier.height(8.dp))
                    Text("Android will ask you to confirm screen capture next.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = { onApprove(selected) }, enabled = selected.isNotEmpty()) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

/** Persistent in-app indicator, shown above every screen while someone has access to this device. */
@Composable
fun HostSessionBanner(state: HostState, onPauseControl: () -> Unit, onDisconnect: () -> Unit, modifier: Modifier = Modifier) {
    val info: HostSessionInfo? = (state as? HostState.Active)?.info
    val connecting = state as? HostState.Connecting
    if (info == null && connecting == null) return
    val peer = info?.peerName ?: connecting?.peerName.orEmpty()
    Column(modifier.fillMaxWidth().background(Danger).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            if (info == null) "Connecting to $peer..." else "Remote access active: $peer",
            color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium,
        )
        if (info != null) {
            Text(
                info.scopes.joinToString { it.title } + if (info.peerOnline) "  -  viewer connected" else "  -  waiting for viewer",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info != null && Scope.CONTROL in info.scopes) {
                OutlinedButton(onClick = onPauseControl, colors = ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)) { Text("Pause control") }
            }
            Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White, contentColor = Danger)) {
                Text("Disconnect now")
            }
        }
    }
}
