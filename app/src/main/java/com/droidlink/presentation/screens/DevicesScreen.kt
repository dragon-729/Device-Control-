@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.domain.model.DeviceRole
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.Scope
import com.droidlink.presentation.common.EmptyState
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.ScopeSelector
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.SectionTitle
import com.droidlink.presentation.common.StatusBadge
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.statusColor
import com.droidlink.presentation.common.userMessage
import com.droidlink.presentation.theme.Danger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DevicesViewModel(private val c: AppContainer) : ViewModel() {
    val devices: StateFlow<List<PairedDevice>?> =
        c.pairingRepository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                c.pairingRepository.refresh()
            } catch (e: Exception) {
                _messages.emit("Showing saved devices - ${e.userMessage()}")
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun revoke(d: PairedDevice) = viewModelScope.launch {
        try {
            c.revokePairing(d)
            _messages.emit("Access for ${d.peerName} revoked")
        } catch (e: Exception) {
            _messages.emit(e.userMessage())
        }
    }

    fun updateScopes(d: PairedDevice, scopes: Set<Scope>) = viewModelScope.launch {
        try {
            c.pairingRepository.updateScopes(d.pairingId, scopes)
            c.securityLog.log("pairing_scopes_changed", "${d.peerName}: ${scopes.joinToString { it.wire }}")
            _messages.emit("Permissions updated")
        } catch (e: Exception) {
            _messages.emit(e.userMessage())
        }
    }
}

@Composable
fun DevicesScreen(onBack: () -> Unit, onConnect: (String) -> Unit, onPair: () -> Unit) {
    val vm = appViewModel { DevicesViewModel(it) }
    val devices by vm.devices.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var toRevoke by remember { mutableStateOf<PairedDevice?>(null) }
    var toEdit by remember { mutableStateOf<PairedDevice?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    toRevoke?.let { d ->
        AlertDialog(
            onDismissRequest = { toRevoke = null },
            title = { Text("Revoke access?") },
            text = { Text("${d.peerName} will be disconnected immediately and will need a new pairing to connect again.") },
            confirmButton = { Button(onClick = { vm.revoke(d); toRevoke = null }) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { toRevoke = null }) { Text("Cancel") } },
        )
    }
    toEdit?.let { d ->
        var selected by remember(d.pairingId) { mutableStateOf(d.scopes) }
        AlertDialog(
            onDismissRequest = { toEdit = null },
            title = { Text("What can ${d.peerName} do?") },
            text = { ScopeSelector(Scope.entries.toSet(), selected, { s -> selected = if (s in selected) selected - s else selected + s }) },
            confirmButton = { Button(enabled = selected.isNotEmpty(), onClick = { vm.updateScopes(d, selected); toEdit = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { toEdit = null }) { Text("Cancel") } },
        )
    }

    ScreenScaffold(
        "My devices", onBack, snackbar,
        actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") } },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            val list = devices?.filter { it.status == PairingStatus.ACTIVE || it.status == PairingStatus.PENDING }.orEmpty()
            if (devices != null && list.isEmpty()) {
                EmptyState(
                    Icons.Filled.PhoneAndroid, "No paired devices",
                    "Pair your first device to view or control it remotely.",
                    action = { Button(onClick = onPair) { Icon(Icons.Filled.Link, null); Text("  Pair a device") } },
                )
            } else {
                val controlled = list.filter { it.role == DeviceRole.CONTROLLER }
                val controllers = list.filter { it.role == DeviceRole.TARGET }
                LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (controlled.isNotEmpty()) {
                        item { SectionTitle("Devices you can connect to") }
                        items(controlled, key = { it.pairingId }) { d ->
                            DeviceCard(d, primary = if (d.status == PairingStatus.ACTIVE) "Connect" else null, onPrimary = { onConnect(d.pairingId) },
                                onRevoke = { toRevoke = d }, onEdit = null)
                        }
                    }
                    if (controllers.isNotEmpty()) {
                        item { SectionTitle("Devices allowed to reach this phone") }
                        items(controllers, key = { it.pairingId }) { d ->
                            DeviceCard(d, primary = null, onPrimary = {}, onRevoke = { toRevoke = d },
                                onEdit = if (d.status == PairingStatus.ACTIVE) ({ toEdit = d }) else null)
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: PairedDevice, primary: String?, onPrimary: () -> Unit, onRevoke: () -> Unit, onEdit: (() -> Unit)?) {
    InfoCard {
        Column {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(d.peerName, style = MaterialTheme.typography.titleMedium)
                    Text(d.peerModel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(if (d.status == PairingStatus.ACTIVE) "Paired" else "Pending", statusColor(d.status == PairingStatus.ACTIVE))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Allowed: " + d.scopes.joinToString { it.title }.ifEmpty { "nothing yet" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (primary != null) Button(onClick = onPrimary) { Text(primary) }
                if (onEdit != null) OutlinedButton(onClick = onEdit) { Text("Permissions") }
                TextButton(onClick = onRevoke) { Text("Revoke", color = Danger) }
            }
        }
    }
}
