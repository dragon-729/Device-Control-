@file:OptIn(ExperimentalMaterial3Api::class)

package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.domain.repository.SessionRepository
import com.droidlink.domain.usecase.RequestSessionUseCase
import kotlinx.coroutines.CoroutineScope
import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SessionStatus
import com.droidlink.presentation.common.EmptyState
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.LoadingDots
import com.droidlink.presentation.common.ScopeSelector
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectUiState(
    val device: PairedDevice? = null,
    val selected: Set<Scope> = emptySet(),
    val waiting: RemoteSession? = null,
    val readySessionId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notFound: Boolean = false,
)

class ConnectViewModel(
    private val pairing: PairingRepository,
    private val requestSession: RequestSessionUseCase,
    private val sessions: SessionRepository,
    private val appScope: CoroutineScope,
    private val pairingId: String,
    private val pollIntervalMs: Long = 1_500,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()
    private var poll: Job? = null

    init {
        viewModelScope.launch {
            val device = pairing.devices.first().firstOrNull { it.pairingId == pairingId && it.status == PairingStatus.ACTIVE }
            if (device == null) _state.update { it.copy(notFound = true) }
            else _state.update { it.copy(device = device, selected = device.scopes) }
        }
    }

    fun toggle(scope: Scope) = _state.update { s -> s.copy(selected = if (scope in s.selected) s.selected - scope else s.selected + scope) }

    fun request() {
        val s = _state.value
        if (s.busy || s.selected.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = requestSession(pairingId, s.selected)
                _state.update { it.copy(busy = false, waiting = session) }
                pollForApproval(session)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    private fun pollForApproval(session: RemoteSession) {
        poll?.cancel()
        poll = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + (if (session.ttlMs > 0) session.ttlMs else 120_000L) + 2_000
            while (System.currentTimeMillis() < deadline) {
                delay(pollIntervalMs)
                try {
                    val current = sessions.get(session.id)
                    when (current.status) {
                        SessionStatus.ACTIVE -> {
                            _state.update { it.copy(waiting = null, readySessionId = current.id) }
                            return@launch
                        }
                        SessionStatus.REJECTED -> return@launch fail("${session.peerName} declined the request.")
                        SessionStatus.EXPIRED, SessionStatus.ENDED -> return@launch fail("The request expired before ${session.peerName} answered.")
                        SessionStatus.PENDING -> Unit
                    }
                } catch (e: Exception) {
                    // keep trying until the request expires
                }
            }
            fail("No answer from ${session.peerName}.")
        }
    }

    private fun fail(message: String) = _state.update { it.copy(waiting = null, error = message) }

    fun cancel() {
        poll?.cancel()
        val waiting = _state.value.waiting ?: return
        _state.update { it.copy(waiting = null) }
        appScope.launch { runCatching { sessions.end(waiting.id) } }
    }

    fun consumeReady() = _state.update { it.copy(readySessionId = null) }

    override fun onCleared() {
        // Leaving the screen while a request is pending withdraws it.
        if (_state.value.waiting != null) cancel()
    }
}

@Composable
fun ConnectScreen(pairingId: String, onBack: () -> Unit, onConnected: (String) -> Unit) {
    val vm = appViewModel(key = "connect-$pairingId") { ConnectViewModel(it.pairingRepository, it.requestSession, it.sessionRepository, it.appScope, pairingId) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.readySessionId) {
        state.readySessionId?.let { vm.consumeReady(); onConnected(it) }
    }
    ScreenScaffold("Connect", onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val device = state.device
            when {
                state.notFound -> EmptyState(Icons.Filled.PhoneAndroid, "Device not available", "This pairing is no longer active. Pair the device again.")
                device == null -> LoadingDots(Modifier.align(Alignment.CenterHorizontally))
                state.waiting != null -> {
                    InfoCard {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Waiting for ${device.peerName}...", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The owner has to approve this request on their phone" +
                                    (if (Scope.VIEW in state.selected) " and confirm Android's screen-capture prompt." else "."),
                                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            LoadingDots()
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = vm::cancel) { Text("Cancel request") }
                        }
                    }
                }
                else -> {
                    Text(device.peerName, style = MaterialTheme.typography.headlineMedium)
                    Text(device.peerModel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoCard {
                        Column {
                            Text("Request access to:", style = MaterialTheme.typography.titleMedium)
                            ScopeSelector(device.scopes, state.selected, vm::toggle)
                        }
                    }
                    Button(onClick = vm::request, enabled = !state.busy && state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Ask ${device.peerName} for access")
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
