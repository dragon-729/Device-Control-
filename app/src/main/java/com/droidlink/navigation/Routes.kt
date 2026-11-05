package com.droidlink.navigation

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val LOCK = "lock"
    const val DASHBOARD = "dashboard"
    const val PAIRING = "pairing"
    const val DEVICES = "devices"
    const val CONNECT = "connect/{pairingId}"
    const val VIEWER = "viewer/{sessionId}"
    const val TRANSFERS = "transfers"
    const val DEVICE_INFO = "deviceinfo"
    const val HISTORY = "history"
    const val SECURITY = "security"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun connect(pairingId: String) = "connect/$pairingId"
    fun viewer(sessionId: String) = "viewer/$sessionId"
}
