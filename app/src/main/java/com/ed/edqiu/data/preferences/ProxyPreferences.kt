package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.ed.edqiu.data.model.ProxySettings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Persistent storage for proxy settings using SharedPreferences.
 */
class ProxyPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)

    fun getProxySettings(): ProxySettings {
        return ProxySettings(
            enabled = prefs.getBoolean("proxy_enabled", false),
            host = prefs.getString("proxy_host", "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt("proxy_port", 7890),
            type = prefs.getString("proxy_type", ProxySettings.TYPE_HTTP) ?: ProxySettings.TYPE_HTTP
        )
    }

    fun saveProxySettings(settings: ProxySettings) {
        prefs.edit()
            .putBoolean("proxy_enabled", settings.enabled)
            .putString("proxy_host", settings.host)
            .putInt("proxy_port", settings.port)
            .putString("proxy_type", settings.type)
            .apply()
    }
}

