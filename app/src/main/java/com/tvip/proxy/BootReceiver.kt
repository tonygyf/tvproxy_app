package com.tvip.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore.isAutoStartEnabled(context)) return

        val serviceIntent = Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
        }
        context.startForegroundService(serviceIntent)
    }
}
