package com.droidlink.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidlink.domain.model.TransferDirection
import com.droidlink.domain.model.TransferRecord
import com.droidlink.domain.model.TransferStatus
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.StatusBadge
import com.droidlink.presentation.common.statusColor
import com.droidlink.presentation.theme.Danger
import com.droidlink.services.FileTransferManager
import com.droidlink.utils.Formatters

/**
 * Send / receive UI bound to a live [FileTransferManager].
 * Files are chosen with the system document picker (Storage Access Framework): DroidLink never
 * gets blanket storage access, only the single files or locations the user picks.
 */
@Composable
fun FileTransferPanel(manager: FileTransferManager, peerName: String, modifier: Modifier = Modifier) {
    val offers by manager.offers.collectAsState()
    val live by manager.live.collectAsState()
    var acceptingId by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) manager.send(uri.toString())
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = acceptingId
        acceptingId = null
        if (id != null) {
            if (uri != null) manager.accept(id, uri.toString()) else manager.reject(id)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Upload, null)
            Text("  Send a file to $peerName")
        }
        offers.forEach { offer ->
            InfoCard {
                Column {
                    Text("$peerName wants to send you a file", style = MaterialTheme.typography.titleMedium)
                    Text("${offer.fileName} (${Formatters.bytes(offer.size)})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { acceptingId = offer.id; saver.launch(offer.fileName) }) {
                            Icon(Icons.Filled.Download, null)
                            Text("  Choose where to save")
                        }
                        OutlinedButton(onClick = { manager.reject(offer.id) }) { Text("Decline") }
                    }
                }
            }
        }
        live.values.sortedByDescending { it.startedAt }.forEach { TransferRow(it, onCancel = { manager.cancel(it.id) }) }
    }
}

@Composable
fun TransferRow(record: TransferRecord, onCancel: (() -> Unit)?) {
    val active = record.status == TransferStatus.IN_PROGRESS || record.status == TransferStatus.PENDING
    InfoCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (record.direction == TransferDirection.UPLOAD) Icons.Filled.Upload else Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(record.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "${Formatters.bytes(record.transferredBytes)} of ${Formatters.bytes(record.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val (label, good) = when (record.status) {
                    TransferStatus.COMPLETED -> "Done" to true
                    TransferStatus.IN_PROGRESS -> "Sending" to null
                    TransferStatus.PENDING -> "Waiting" to null
                    TransferStatus.FAILED -> "Failed" to false
                    TransferStatus.CANCELLED -> "Cancelled" to false
                    TransferStatus.REJECTED -> "Declined" to false
                }
                StatusBadge(if (record.status == TransferStatus.IN_PROGRESS && record.direction == TransferDirection.DOWNLOAD) "Receiving" else label, statusColor(good))
            }
            if (active) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { Formatters.percent(record.transferredBytes, record.totalBytes) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel", color = Danger) }
            }
            record.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
