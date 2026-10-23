package dev.teslanavi.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.teslanavi.TeslaNaviApp

class AutoConnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as TeslaNaviApp
        if (!app.autoConnect.enabled.value) return
        if (!AutoConnectSettings.ready(context)) {
            app.autoConnect.report("자동 연결 권한을 확인해 주세요")
            return
        }
        try { AutoConnectService.start(context) }
        catch (_: Exception) { app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요") }
    }
}
