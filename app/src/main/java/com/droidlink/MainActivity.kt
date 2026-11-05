package com.droidlink

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.droidlink.navigation.AppNavHost
import com.droidlink.presentation.common.ActivityActions
import com.droidlink.presentation.common.LocalActivityActions
import com.droidlink.presentation.theme.DroidLinkTheme
import com.droidlink.security.Reauthenticator
import android.media.projection.MediaProjectionManager
import android.widget.Toast

/** FragmentActivity (not just ComponentActivity) because BiometricPrompt requires it. */
class MainActivity : FragmentActivity(), ActivityActions {

    private val container get() = (application as DroidLinkApp).container
    private var captureCallback: ((Int, Intent?) -> Unit)? = null

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        captureCallback?.invoke(result.resultCode, result.data)
        captureCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hide DroidLink's own screens (pairing codes, approvals) from screenshots and from remote screen sharing.
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        handleLink(intent)
        setContent {
            val settings by container.settingsRepository.settings.collectAsState(initial = container.currentSettings)
            DroidLinkTheme(settings.themeMode) {
                CompositionLocalProvider(LocalActivityActions provides this) { AppNavHost() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("droidlink://")) container.incomingLink.value = data
    }

    override fun onStart() {
        super.onStart()
        if (container.auth.registration.value != null) container.presence.start()
    }

    override fun onStop() {
        // Presence is only needed while the app is visible; an active session has its own socket.
        container.presence.stop()
        super.onStop()
    }

    // ---- ActivityActions ----------------------------------------------------------------------------
    override fun requestScreenCapture(onResult: (resultCode: Int, data: Intent?) -> Unit) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureCallback = onResult
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun reauthenticate(title: String, subtitle: String, onResult: (Reauthenticator.Result) -> Unit) {
        container.reauthenticator.authenticate(this, title, subtitle, onResult)
    }

    override fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings > Accessibility manually", Toast.LENGTH_LONG).show()
        }
    }

    override fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun context(): Context = this
}
