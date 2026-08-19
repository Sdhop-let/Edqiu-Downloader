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
            port = prefs.getInt("proxy_port", 7890)
        )
    }

    fun saveProxySettings(settings: ProxySettings) {
        prefs.edit()
            .putBoolean("proxy_enabled", settings.enabled)
            .putString("proxy_host", settings.host)
            .putInt("proxy_port", settings.port)
            .apply()
    }

    /** Detect system proxy first, then fall back to reachable Clash/V2Ray-style local ports. */
    fun detectProxySettings(): ProxySettings {
        val current = getProxySettings()
        val detectedHost = listOf(
            System.getProperty("http.proxyHost"),
            System.getProperty("https.proxyHost")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
        val detectedPort = parsePort(System.getProperty("http.proxyPort"))
            ?: parsePort(System.getProperty("https.proxyPort"))
        val localPort = COMMON_PROXY_PORTS.firstOrNull { isLocalPortOpen(it) }

        return current.copy(
            host = detectedHost ?: current.host.ifBlank { LOCAL_PROXY_HOST },
            port = detectedPort ?: localPort ?: current.port.takeIf { it in 1..65535 } ?: DEFAULT_PROXY_PORT
        )
    }

    private fun parsePort(value: String?): Int? =
        value?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun isLocalPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOCAL_PROXY_HOST, port), 120)
            true
        }
    }.getOrDefault(false)

    companion object {
        private const val LOCAL_PROXY_HOST = "127.0.0.1"
        private const val DEFAULT_PROXY_PORT = 7890
        private val COMMON_PROXY_PORTS = listOf(7890, 7897, 10809, 10808, 1080, 8080)
    }
}

