@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidlink.presentation.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.domain.model.Registration
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.domain.repository.SecurityLogRepository
import com.droidlink.domain.usecase.ClaimPairingCodeUseCase
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingCode
import com.droidlink.domain.model.PairingStatus
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.LoadingDots
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.rememberContainer
import com.droidlink.presentation.common.userMessage
import com.droidlink.security.InputValidator
import com.droidlink.utils.QrCode
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val code: PairingCode? = null,
    val link: String? = null,
    val generating: Boolean = false,
    val secondsLeft: Int = 0,
    val manualInput: String = "",
    val claiming: Boolean = false,
    val waitingFor: PairedDevice? = null,
    val approved: PairedDevice? = null,
    val error: String? = null,
)

class PairingViewModel(
    private val pairing: PairingRepository,
    private val registration: StateFlow<Registration?>,
    val incomingRequests: StateFlow<List<PairedDevice>>,
    private val claimPairingCode: ClaimPairingCodeUseCase,
    private val securityLog: SecurityLogRepository,
    private val pollIntervalMs: Long = 2_000,
) : ViewModel() {
    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()
    private var countdown: Job? = null
    private var polling: Job? = null

    fun generate() {
        if (_state.value.generating) return
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            try {
                val code = pairing.createCode()
                val server = registration.value?.serverUrl.orEmpty()
                _state.update { it.copy(code = code, link = InputValidator.buildPairingLink(code.code, server), generating = false) }
                startCountdown(code)
            } catch (e: Exception) {
                _state.update { it.copy(generating = false, error = e.userMessage()) }
            }
        }
    }

    private fun startCountdown(code: PairingCode) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                val left = ((code.expiresAtMs - System.currentTimeMillis()) / 1000).toInt()
                if (left <= 0) {
                    _state.update { it.copy(code = null, link = null, secondsLeft = 0) }
                    return@launch
                }
                _state.update { it.copy(secondsLeft = left) }
                delay(1_000)
            }
        }
    }

    fun onManualInput(v: String) = _state.update { it.copy(manualInput = v.take(12), error = null) }

    /** From the in-app QR scanner or a droidlink:// link. Accepts either a link or a bare code. */
    fun onScanned(raw: String) {
        val link = InputValidator.parsePairingLink(raw)
        if (link != null) {
            val mine = registration.value?.serverUrl
            if (link.server != null && mine != null && !InputValidator.sameServer(link.server, mine)) {
                _state.update { it.copy(error = "That code belongs to a different relay server than this device is registered with.") }
                return
            }
            claim(link.code)
        } else {
            claim(raw)
        }
    }

    fun claim(raw: String) {
        if (_state.value.claiming) return
        _state.update { it.copy(claiming = true, error = null) }
        viewModelScope.launch {
            try {
                val pending = claimPairingCode(raw)
                _state.update { it.copy(claiming = false, waitingFor = pending, manualInput = "") }
                pollForApproval(pending)
            } catch (e: Exception) {
                _state.update { it.copy(claiming = false, error = e.userMessage()) }
            }
        }
    }

    private fun pollForApproval(pending: PairedDevice) {
        polling?.cancel()
        polling = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(pollIntervalMs)
                try {
                    pairing.refresh()
                    val current = pairing.devices.first().firstOrNull { it.pairingId == pending.pairingId }
                    when (current?.status) {
                        PairingStatus.ACTIVE -> {
                            securityLog.log("pairing_active", pending.peerName)
                            _state.update { it.copy(waitingFor = null, approved = current) }
                            return@launch
                        }
                        PairingStatus.REJECTED, PairingStatus.REVOKED -> {
                            _state.update { it.copy(waitingFor = null, error = "${pending.peerName} declined the pairing.") }
                            return@launch
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    // transient network problem: keep polling until the deadline
                }
            }
            _state.update { it.copy(waitingFor = null, error = "No answer from ${pending.peerName}. Ask them to check their device.") }
        }
    }

    fun dismissResult() = _state.update { it.copy(approved = null, error = null) }
}

@Composable
fun PairingScreen(onBack: () -> Unit, onOpenDevices: () -> Unit, onOpenDashboard: () -> Unit) {
    val vm = appViewModel { PairingViewModel(it.pairingRepository, it.auth.registration, it.presence.pendingPairings, it.claimPairingCode, it.securityLog) }
    val state by vm.state.collectAsState()
    val incoming by vm.incomingRequests.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::onScanned) }
    val container = rememberContainer()
    val link by container.incomingLink.collectAsState()
    LaunchedEffect(link) {
        link?.let {
            tab = 1
            vm.onScanned(it)
            container.incomingLink.value = null
        }
    }

    LaunchedEffect(state.approved) {
        state.approved?.let {
            snackbar.showSnackbar("Paired with ${it.peerName}")
            vm.dismissResult()
            onOpenDevices()
        }
    }

    ScreenScaffold("Pair a device", onBack, snackbar) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Show my code") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Enter a code") })
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) {
                    InfoCard {
                        Text(
                            "Use this on the device you want to reach. The other device scans the QR or types the code. You will be asked to approve before anything is shared.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val code = state.code
                    if (code == null) {
                        Button(onClick = vm::generate, enabled = !state.generating, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            if (state.generating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Generate pairing code")
                        }
                    } else {
                        InfoCard {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                state.link?.let { link ->
                                    val bitmap = remember(link) { QrCode.render(link) }
                                    Image(
                                        bitmap.asImageBitmap(), "Pairing QR code",
                                        Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    InputValidator.formatPairingCode(code.code),
                                    fontSize = 32.sp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineMedium,
                                )
                                Text("Expires in ${state.secondsLeft}s - single use", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("DroidLink code", code.code))
                                }) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                                    Text("  Copy code")
                                }
                            }
                        }
                        if (incoming.isNotEmpty()) {
                            InfoCard {
                                Column {
                                    Text("${incoming.first().peerName} wants to pair", style = MaterialTheme.typography.titleMedium)
                                    Text("Review and approve it on the dashboard.", style = MaterialTheme.typography.bodyMedium)
                                    Button(onClick = onOpenDashboard, modifier = Modifier.padding(top = 8.dp)) { Text("Review request") }
                                }
                            }
                        }
                    }
                } else {
                    val waiting = state.waitingFor
                    if (waiting != null) {
                        InfoCard {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Waiting for ${waiting.peerName} to approve...", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(12.dp))
                                LoadingDots()
                                Spacer(Modifier.height(8.dp))
                                Text("Ask the owner to open DroidLink and tap Review.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = state.manualInput, onValueChange = vm::onManualInput,
                            label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            supportingText = { Text("8 letters/digits, e.g. K7P4-9TXM") },
                        )
                        Button(
                            onClick = { vm.claim(state.manualInput) },
                            enabled = !state.claiming && state.manualInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { if (state.claiming) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Request pairing") }
                        OutlinedButton(
                            onClick = {
                                scanner.launch(
                                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scan the DroidLink QR code").setBeepEnabled(false).setOrientationLocked(false),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, null)
                            Text("  Scan QR code")
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
