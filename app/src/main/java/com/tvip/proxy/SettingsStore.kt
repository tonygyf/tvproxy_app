package com.tvip.proxy

import android.content.Context

object SettingsStore {
    private const val PREFS = "tvproxy_settings"
    private const val KEY_SUB_URL = "subscription_url"
    private const val KEY_NODE_NAME = "selected_node"
    private const val KEY_AUTO_START = "auto_start_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSubscriptionUrl(context: Context): String? =
        prefs(context).getString(KEY_SUB_URL, null)

    fun setSubscriptionUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SUB_URL, url).apply()
    }

    fun getSelectedNode(context: Context): String? =
        prefs(context).getString(KEY_NODE_NAME, null)

    fun setSelectedNode(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NODE_NAME, name).apply()
    }

    // 开机是否自动重新连接上次的订阅+节点
    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}
