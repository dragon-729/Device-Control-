package com.droidlink.presentation.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidlink.BuildConfig
import com.droidlink.R
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.LocalActivityActions
import com.droidlink.presentation.common.ScreenScaffold

const val TELEGRAM_HANDLE = "@YogeshBhai729"
const val TELEGRAM_URL = "https://t.me/YogeshBhai729"
const val GITHUB_URL = "https://github.com/hgcheat094-max"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val actions = LocalActivityActions.current
    ScreenScaffold("About DroidLink", onBack) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.droidlink_logo), "DroidLink logo", Modifier.size(180.dp).clip(RoundedCornerShape(32.dp)))
            Text("DroidLink", style = MaterialTheme.typography.headlineMedium)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Open-source, consent-based remote access and management for your own Android devices.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Our promises", style = MaterialTheme.typography.titleMedium)
                    Text("- Every pairing and every session needs the device owner's approval.", style = MaterialTheme.typography.bodyMedium)
                    Text("- A notification stays visible, with a Disconnect button, whenever someone has access.", style = MaterialTheme.typography.bodyMedium)
                    Text("- Screen capture uses Android's official consent; Accessibility is switched on only by you.", style = MaterialTheme.typography.bodyMedium)
                    Text("- No stealth mode, no keylogging, no password capture, no hidden persistence.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            InfoCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Contact the developer", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { actions.openUrl(TELEGRAM_URL) }, modifier = Modifier.fillMaxWidth()) { Text("Telegram  $TELEGRAM_HANDLE") }
                    OutlinedButton(onClick = { actions.openUrl(GITHUB_URL) }, modifier = Modifier.fillMaxWidth()) { Text("GitHub  hgcheat094-max") }
                }
            }
            Text("Released under the MIT License.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}
