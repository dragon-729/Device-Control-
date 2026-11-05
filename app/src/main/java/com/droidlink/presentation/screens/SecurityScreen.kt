package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.domain.model.AppSettings
import com.droidlink.domain.model.SecurityEvent
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.LocalActivityActions
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.SectionTitle
import com.droidlink.presentation.common.StatusBadge
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.statusColor
import com.droidlink.presentation.theme.Danger
import com.droidlink.security.Reauthenticator
import com.droidlink.utils.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SecurityViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.currentSettings)
    val events: StateFlow<List<SecurityEvent>> = c.securityLog.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val registration = c.auth.registration
    fun accessibilityEnabled() = c.inputExecutor.isServiceEnabled()
    fun setReauth(v: Boolean) { viewModelScope.launch { c.settingsRepository.setRequireReauth(v); c.securityLog.log("setting_changed", "require_reauth=$v") } }
    fun setAppLock(v: Boolean) { viewModelScope.launch { c.settingsRepository.setAppLock(v); c.securityLog.log("setting_changed", "app_lock=$v") } }
    fun clearLog() { viewModelScope.launch { c.securityLog.clear() } }
    fun signOut(onDone: () -> Unit) {
        if (c.hostController.isRunning()) c.hostController.emergencyDisconnect()
        c.presence.stop()
        c.appScope.launch {
            c.auth.signOut()
            c.securityLog.clear()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }
}

@Composable
fun SecurityScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val vm = appViewModel { SecurityViewModel(it) }
    val actions = LocalActivityActions.current
    val settings by vm.settings.collectAsState()
    val events by vm.events.collectAsState()
    val registration by vm.registration.collectAsState()
    var a11y by remember { mutableStateOf(vm.accessibilityEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { a11y = vm.accessibilityEnabled() }
    var confirmSignOut by remember { mutableStateOf(false) }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Unregister this device?") },
            text = { Text("All pairings are revoked, any active session is ended and the stored credentials are deleted. You will need to register and pair again.") },
            confirmButton = { Button(onClick = { confirmSignOut = false; vm.signOut(onSignedOut) }) { Text("Unregister") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    ScreenScaffold("Security settings", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionTitle("Permissions")
                InfoCard {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Accessibility service (remote control)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            StatusBadge(if (a11y) "On" else "Off", statusColor(a11y))
                        }
                        Text(
                            "Required for taps, swipes and typing from an approved device. Only you can turn it on or off, in Android settings. It is only used while a session you approved is running, never reads passwords and cannot operate DroidLink's own screens.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = actions::openAccessibilitySettings, modifier = Modifier.padding(top = 8.dp)) { Text("Open Accessibility settings") }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Screen sharing always shows Android's own consent dialog and a status indicator; DroidLink cannot skip it.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionTitle("Protection")
                InfoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow("Confirm my identity to approve", "Ask for fingerprint / face / screen lock before approving a pairing or session.", settings.requireReauthForApproval, vm::setReauth)
                        SwitchRow("Lock the app", "Require fingerprint / face / screen lock when DroidLink opens.", settings.appLockEnabled) { enable ->
                            if (!enable) vm.setAppLock(false)
                            else actions.reauthenticate("Turn on app lock", "Confirm it's you") { r ->
                                if (r == Reauthenticator.Result.SUCCESS) vm.setAppLock(true)
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("This device")
                InfoCard {
                    Column {
                        Text(registration?.deviceName ?: "Not registered", style = MaterialTheme.typography.titleMedium)
                        Text("Server: ${registration?.serverUrl ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                        Text("ID: ${registration?.deviceId?.take(8) ?: "-"}...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Credentials are encrypted with a hardware-backed Android Keystore key. Access tokens live for 15 minutes and are kept in memory only.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Button(
                            onClick = { confirmSignOut = true },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Danger),
                        ) { Text("Unregister this device") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Security log")
                    TextButton(onClick = vm::clearLog) { Text("Clear") }
                }
            }
            if (events.isEmpty()) item { Text("No events recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events, key = { it.id }) { e ->
                InfoCard {
                    Column {
                        Text(e.type.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                        if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodyMedium)
                        Text(Formatters.dateTime(e.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
