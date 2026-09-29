package com.ed.edqiu.data.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 主流代理客户端检测器。
 *
 * 网络认证设置「自动检测代理」使用：识别手机当前是否开启了 Clash / FlClash /
 * Clash Verge / v2rayNG / Shadowsocks 等主流代理通路，并返回可直接套用的代理配置。
 *
 * 检测顺序：
 * 1. 系统 VPN 隧道（Clash/FlClash 等 VPN 模式 / 机场 app）→ 直连即可，无需填代理；
 * 2. 已知主流代理客户端本地端口（Clash 混合口 7890、Clash Verge 7897、
 *    v2rayNG HTTP 10809 / SOCKS 10808、Shadowsocks 1080、通用 8080）。
 */
object ProxyDetector {

    /** 一次检测的结果。 */
    data class Detection(
        /** 是否检测到可用通路。 */
        val found: Boolean,
        /** 通路类型：VPN / PROXY / NONE。 */
        val kind: String,
        /** 客户端展示名（如 "Clash / FlClash"）。 */
        val clientName: String,
        val host: String,
        val port: Int?,
        /** 面向用户的中文描述。 */
        val description: String,
    )

    /** 主流代理客户端端口表：端口 → 客户端名。 */
    private val KNOWN_CLIENTS: List<Pair<Int, String>> = listOf(
        7890 to "Clash / FlClash",
        7897 to "Clash Verge",
        10809 to "v2rayNG (HTTP)",
        10808 to "v2rayNG (SOCKS)",
        1080 to "Shadowsocks",
        8080 to "通用 HTTP 代理",
    )

    /**
     * 检测当前代理通路。网络认证设置页调用（IO 线程）。
     * 命中 VPN 隧道返回直连建议；命中代理端口返回可直接应用的 host/port。
     */
    fun detect(context: Context): Detection {
        if (isSystemVpnActive(context)) {
            return Detection(
                found = true,
                kind = "VPN",
                clientName = "系统 VPN 隧道",
                host = "0.0.0.0",
                port = null,
                description = "已检测到系统 VPN 隧道（Clash/FlClash 等 VPN 模式），直连即可访问",
            )
        }
        for ((port, name) in KNOWN_CLIENTS) {
            if (isPortOpen("127.0.0.1", port)) {
                return Detection(
                    found = true,
                    kind = "PROXY",
                    clientName = name,
                    host = "127.0.0.1",
                    port = port,
                    description = "已识别 $name：127.0.0.1:$port",
                )
            }
        }
        return Detection(
            found = false,
            kind = "NONE",
            clientName = "",
            host = "127.0.0.1",
            port = 7890,
            description = "未检测到主流代理（Clash / FlClash / v2rayNG 等），请确认代理客户端已开启",
        )
    }

    /** 系统 VPN 隧道检测（TRANSPORT_VPN 优先，NetworkInterface 兜底）。 */
    private fun isSystemVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val byTransport = cm?.allNetworks?.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: false
        if (byTransport) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .any { iface ->
                    iface.name.startsWith("tun") ||
                        iface.name.startsWith("ppp") ||
                        iface.name.startsWith("wg") ||
                        iface.name.startsWith("utun")
                }
        }.getOrDefault(false)
    }

    private fun isPortOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 250)
            true
        }
    }.getOrDefault(false)
}
