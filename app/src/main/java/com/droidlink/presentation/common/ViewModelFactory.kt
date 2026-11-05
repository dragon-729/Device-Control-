package com.droidlink.presentation.common

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.droidlink.AppContainer
import com.droidlink.DroidLinkApp
import com.droidlink.security.Reauthenticator

@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as DroidLinkApp).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = rememberContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Things only an Activity can do (system dialogs, permission flows). Implemented by MainActivity. */
interface ActivityActions {
    /** Shows Android's official screen-capture consent dialog. */
    fun requestScreenCapture(onResult: (resultCode: Int, data: Intent?) -> Unit)
    fun reauthenticate(title: String, subtitle: String, onResult: (Reauthenticator.Result) -> Unit)
    fun openAccessibilitySettings()
    fun openUrl(url: String)
    fun context(): Context
}

val LocalActivityActions = staticCompositionLocalOf<ActivityActions> { error("ActivityActions not provided") }
