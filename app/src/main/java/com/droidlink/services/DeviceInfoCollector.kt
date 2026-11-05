package com.droidlink.services

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import com.droidlink.BuildConfig
import com.droidlink.domain.model.DeviceInfo
import com.droidlink.network.NetworkMonitor

/** Collects only what the dashboard shows. No IMEI, serial number, account, contact or location data. */
class DeviceInfoCollector(private val context: Context, private val network: NetworkMonitor) {

    fun collect(): DeviceInfo {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val stat = StatFs(Environment.getDataDirectory().path)
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)

        val size = Point()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealSize(size)

        return DeviceInfo(
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            sdkInt = Build.VERSION.SDK_INT,
            batteryPercent = percent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            storageTotal = stat.totalBytes,
            storageFree = stat.availableBytes,
            ramTotal = mem.totalMem,
            ramFree = mem.availMem,
            screenWidth = size.x,
            screenHeight = size.y,
            network = network.label(),
            appVersion = BuildConfig.VERSION_NAME,
            accessibilityEnabled = DroidLinkAccessibilityService.instance != null,
        )
    }
}
