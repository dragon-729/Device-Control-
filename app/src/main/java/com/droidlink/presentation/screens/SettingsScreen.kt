package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.AppContainer
import com.droidlink.domain.model.AppSettings
import com.droidlink.domain.model.ThemeMode
import com.droidlink.presentation.common.InfoCard
import com.droidlink.presentation.common.ScreenScaffold
import com.droidlink.presentation.common.SectionTitle
import com.droidlink.presentation.common.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.currentSettings)
    fun setTheme(mode: ThemeMode) { viewModelScope.launch { c.settingsRepository.setThemeMode(mode) } }
    fun setStream(fps: Int, quality: Int) { viewModelScope.launch { c.settingsRepository.setStreamDefaults(fps, quality) } }
    fun clearLocalData() {
        viewModelScope.launch {
            c.sessionRepository.clearHistory()
            c.transferRepository.clear()
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit, onSecurity: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsState()
    var fps by remember(settings.defaultFps) { mutableFloatStateOf(settings.defaultFps.toFloat()) }
    var quality by remember(settings.defaultQuality) { mutableFloatStateOf(settings.defaultQuality.toFloat()) }

    ScreenScaffold("Settings", onBack) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Appearance")
            InfoCard {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = settings.themeMode == mode, role = Role.RadioButton, onClick = { vm.setTheme(mode) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.themeMode == mode, onClick = null)
                            Text(
                                when (mode) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" },
                                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
            SectionTitle("Sharing this device's screen")
            InfoCard {
                Column {
                    Text("Starting frame rate: ${fps.toInt()} fps")
                    Slider(value = fps, onValueChange = { fps = it }, valueRange = 2f..30f, onValueChangeFinished = { vm.setStream(fps.toInt(), quality.toInt()) })
                    Text("Starting JPEG quality: ${quality.toInt()}")
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 20f..95f, onValueChangeFinished = { vm.setStream(fps.toInt(), quality.toInt()) })
                    Text(
                        "Quality adapts automatically to the network; the viewer can also change it during a session.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionTitle("Data")
            OutlinedButton(onClick = vm::clearLocalData, modifier = Modifier.fillMaxWidth()) { Text("Clear session & transfer history") }
            OutlinedButton(onClick = onSecurity, modifier = Modifier.fillMaxWidth()) { Text("Security settings") }
            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("About DroidLink") }
        }
    }
}
