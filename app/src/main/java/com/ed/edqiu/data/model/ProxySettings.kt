package com.ed.edqiu.data.model

/**
 * Proxy settings for yt-dlp HTTP proxy support.
 * Default values match Clash for Android's HTTP proxy port.
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 7890
) {
    /**
     * Convert to yt-dlp --proxy URL format: http://host:port
     * Returns null if proxy is disabled.
     */
    fun toProxyUrl(): String? {
        return if (enabled) "http://$host:$port" else null
    }

    companion object {
        val DEFAULT = ProxySettings()
    }
}

