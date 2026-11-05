package com.droidlink

import android.app.Application
import com.droidlink.services.Notifications

class DroidLinkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
    }
}
