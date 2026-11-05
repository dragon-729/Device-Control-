package com.droidlink.presentation.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidlink.domain.repository.AuthRepository
import com.droidlink.domain.repository.SecurityLogRepository
import com.droidlink.BuildConfig
import com.droidlink.R
import com.droidlink.presentation.common.appViewModel
import com.droidlink.presentation.common.userMessage
import com.droidlink.security.InputValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val deviceName: String = "",
    val inviteCode: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class LoginViewModel(
    private val auth: AuthRepository,
    private val securityLog: SecurityLogRepository,
    defaultDeviceName: String,
    private val allowLocalCleartext: Boolean = BuildConfig.ALLOW_LOCAL_CLEARTEXT,
    defaultServerUrl: String = BuildConfig.DEFAULT_SERVER_URL,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState(serverUrl = defaultServerUrl, deviceName = defaultDeviceName))
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onServer(v: String) = _state.update { it.copy(serverUrl = v, error = null) }
    fun onName(v: String) = _state.update { it.copy(deviceName = v, error = null) }
    fun onInvite(v: String) = _state.update { it.copy(inviteCode = v, error = null) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        val url = when (val r = InputValidator.validateServerUrl(s.serverUrl, allowLocalCleartext)) {
            is InputValidator.UrlResult.Valid -> r.url
            is InputValidator.UrlResult.Invalid -> return fail(r.reason)
        }
        val name = InputValidator.validateDeviceName(s.deviceName)
            ?: return fail("Device name may use letters, numbers and . , ' - ( ) and must be 1-64 characters")
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.register(url, name, s.inviteCode)
                securityLog.log("device_registered", name)
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val vm = appViewModel { LoginViewModel(it.auth, it.securityLog, InputValidator.validateDeviceName(Build.MODEL) ?: "My Android") }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onSignedIn() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.droidlink_logo), null, Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)))
        Spacer(Modifier.height(16.dp))
        Text("Connect this device", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Register this device with your DroidLink relay server. A private key is stored encrypted in the Android Keystore - no password is needed or saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.serverUrl, onValueChange = vm::onServer, label = { Text("Relay server address") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text("Must be https:// (plain http is only allowed for local debug builds)") },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = state.deviceName, onValueChange = vm::onName, label = { Text("This device's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.inviteCode, onValueChange = vm::onInvite, label = { Text("Invite code (only if your server requires one)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = vm::submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Register and continue")
        }
    }
}
