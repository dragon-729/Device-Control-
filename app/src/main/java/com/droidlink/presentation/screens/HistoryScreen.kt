package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.domain.model.ConnectionRecord
import com.droidlink.domain.model.DeviceRole
import com.droidlink.presentation.common.EmptyState
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.StatusBadge
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.statusColor
import com.droidlink.utils.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val records: StateFlow<List<ConnectionRecord>?> =
        c.sessionRepository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun clear() { viewModelScope.launch { c.sessionRepository.clearHistory() } }
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val vm = appViewModel { HistoryViewModel(it) }
    val records by vm.records.collectAsState()
    ScreenScaffold(
        "Connection history", onBack,
        actions = { IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, "Clear history") } },
    ) { padding ->
        val list = records
        if (list != null && list.isEmpty()) {
            Column(Modifier.padding(padding)) { EmptyState(Icons.Filled.History, "No sessions yet", "Every remote session - who, when and what was allowed - is listed here.") }
        } else {
            LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list.orEmpty(), key = { it.id }) { r -> RecordCard(r) }
            }
        }
    }
}

@Composable
private fun RecordCard(r: ConnectionRecord) {
    InfoCard {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (r.role == DeviceRole.TARGET) "${r.peerName} accessed this device" else "You connected to ${r.peerName}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                StatusBadge(if (r.endedAt == null) "Active" else "Ended", statusColor(r.endedAt != null))
            }
            Text(Formatters.dateTime(r.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Allowed: " + r.scopes.joinToString { it.title }, style = MaterialTheme.typography.bodyMedium)
            r.endedAt?.let {
                Text("Duration ${Formatters.duration(it - r.startedAt)} - ${r.outcome}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
