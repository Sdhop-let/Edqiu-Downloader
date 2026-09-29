package com.ed.edqiu.data.model

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Proxy settings for yt-dlp HTTP proxy support.
 * Default values match Clash for Android's HTTP proxy port.
 *
 * 2026-09-15 P0-3 内建代理分流：新增 [type] 字段（http / socks5）。
 * - 内部引擎（HttpURLConnection）：http → [Proxy.Type.HTTP]，socks5 → [Proxy.Type.SOCKS]；
 * - yt-dlp 引擎：--proxy 透传 socks5://（需 PySocks 支持；Clash 混合端口建议仍用 http）。
 * - 全部引擎统一经 [toProxyUrl] / [toJavaProxy] 消费，改这里即全链路生效。
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 7890,
    val type: String = TYPE_HTTP
) {
    /**
     * Convert to yt-dlp --proxy URL format: http://host:port 或 socks5://host:port.
     * Returns null if proxy is disabled.
     */
    fun toProxyUrl(): String? {
        if (!enabled) return null
        return if (type == TYPE_SOCKS5) "socks5://$host:$port" else "http://$host:$port"
    }

    /** 转 [java.net.Proxy]；未启用时返回 null（直连）。 */
    fun toJavaProxy(): Proxy? {
        if (!enabled) return null
        return if (type == TYPE_SOCKS5) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        } else {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }
    }

    /** 代理展示标签（如 "http://127.0.0.1:7897"；未启用返回 "直连"）。 */
    fun describe(): String =
        if (!enabled) "直连" else "${if (type == TYPE_SOCKS5) "socks5" else "http"}://$host:$port"

    companion object {
        const val TYPE_HTTP = "http"
        const val TYPE_SOCKS5 = "socks5"
        val DEFAULT = ProxySettings()
    }
}
