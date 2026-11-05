package com.droidlink.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidlink.presentation.common.LocalActivityActions
import com.droidlink.security.Reauthenticator

/** Shown on cold start when "App lock" is enabled in Security settings. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val actions = LocalActivityActions.current
    fun prompt() = actions.reauthenticate("Unlock DroidLink", "Confirm it's you") { result ->
        if (result == Reauthenticator.Result.SUCCESS) onUnlocked()
    }
    LaunchedEffect(Unit) { prompt() }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("DroidLink is locked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { prompt() }) { Text("Unlock") }
    }
}
