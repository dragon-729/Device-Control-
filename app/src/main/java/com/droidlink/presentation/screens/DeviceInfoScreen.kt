package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.domain.model.DeviceInfo
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.LoadingDots
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.appViewModel
import com.droidlink.utils.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceInfoViewModel(private val c: AppContainer) : ViewModel() {
    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(5_000)
            }
        }
    }

    private suspend fun refreshOnce() {
        _info.value = withContext(Dispatchers.IO) { c.deviceInfo.collect() }
    }

    fun refresh() { viewModelScope.launch { refreshOnce() } }
}

@Composable
fun DeviceInfoScreen(onBack: () -> Unit) {
    val vm = appViewModel { DeviceInfoViewModel(it) }
    val info by vm.info.collectAsState()
    ScreenScaffold(
        "Device information", onBack,
        actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") } },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            val i = info
            if (i == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingDots() }
            } else {
                DeviceInfoGrid(i)
                Spacer(Modifier.height(12.dp))
                Text(
                    "DroidLink only reads the values above. It does not collect IMEI, serial number, accounts, contacts or location.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun DeviceInfoGrid(info: DeviceInfo) {
    InfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("Model", info.model)
            InfoRow("Android", "${info.androidVersion} (API ${info.sdkInt})")
            InfoRow("Battery", if (info.batteryPercent < 0) "Unknown" else "${info.batteryPercent}%" + if (info.charging) " - charging" else " - not charging")
            if (info.batteryPercent in 0..100) LinearProgressIndicator(progress = { info.batteryPercent / 100f }, modifier = Modifier.fillMaxWidth())
            InfoRow("Storage", "${Formatters.bytes(info.storageFree)} free of ${Formatters.bytes(info.storageTotal)}")
            if (info.storageTotal > 0) {
                LinearProgressIndicator(progress = { 1f - info.storageFree.toFloat() / info.storageTotal }, modifier = Modifier.fillMaxWidth())
            }
            InfoRow("Memory", if (info.ramTotal > 0) "${Formatters.bytes(info.ramFree)} free of ${Formatters.bytes(info.ramTotal)}" else "Unavailable")
            InfoRow("Screen", "${info.screenWidth} x ${info.screenHeight} px")
            InfoRow("Network", info.network)
            InfoRow("DroidLink", "v${info.appVersion}")
            InfoRow("Remote control service", if (info.accessibilityEnabled) "Enabled" else "Off (view-only)")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
    }
}
