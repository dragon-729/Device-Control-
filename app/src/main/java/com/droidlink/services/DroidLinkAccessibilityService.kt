package com.droidlink.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Executes remote input. It does nothing by itself: it never listens to what is on screen and
 * only acts when [RemoteInputExecutor] (which checks the active, owner-approved session) calls it.
 * The device owner must enable it manually in Settings > Accessibility; DroidLink cannot do that for them.
 */
class DroidLinkAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: DroidLinkAccessibilityService? = null
            private set
    }
}
