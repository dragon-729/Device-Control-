package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SwapVert
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
import com.droidlink.domain.model.TransferRecord
import com.droidlink.presentation.common.EmptyState
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.SectionTitle
import com.droidlink.presentation.common.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransferViewModel(private val c: AppContainer) : ViewModel() {
    val history: StateFlow<List<TransferRecord>?> =
        c.transferRepository.transfers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val liveManager = c.hostController.files
    val hostState = c.hostController.state
    fun clear() { viewModelScope.launch { c.transferRepository.clear() } }
}

@Composable
fun TransferScreen(onBack: () -> Unit) {
    val vm = appViewModel { TransferViewModel(it) }
    val history by vm.history.collectAsState()
    val manager by vm.liveManager.collectAsState()
    ScreenScaffold(
        "File transfer", onBack,
        actions = { IconButton(onClick = vm::clear) { Icon(Icons.Filled.DeleteSweep, "Clear history") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val m = manager
                if (m != null) {
                    Spacer(Modifier.height(4.dp))
                    FileTransferPanel(m, "the connected device")
                } else {
                    Text(
                        "To send or receive files, start a session with the File transfer permission. While you are viewing another device, use the Files button in the viewer; while someone is connected to this device, transfers appear here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                SectionTitle("History")
            }
            val h = history
            if (h != null && h.isEmpty()) {
                item { Column(Modifier.height(260.dp)) { EmptyState(Icons.Filled.SwapVert, "No transfers yet", "Files you send or receive will be listed here.") } }
            } else {
                items(h.orEmpty(), key = { it.id }) { TransferRow(it, onCancel = null) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
