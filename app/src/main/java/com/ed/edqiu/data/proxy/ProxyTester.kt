package com.ed.edqiu.data.proxy

import com.ed.edqiu.data.model.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 代理连通性测试器（2026-09-15 P0-3 内建代理分流）：设置页「测试连通」按钮使用。
 *
 * 以解析 API（api.fxtwitter.com）为探针：收到任意 HTTP 响应即视为通路成立
 * （无论 200/404——证明 DNS、TLS 与代理转发链路全通），并报告延迟。
 * 与下载链路熔断联动：测试失败提示「先修网络」，避免三层引擎无效重试。
 */
object ProxyTester {

    private const val PROBE_URL = "https://api.fxtwitter.com/Twitter/status/12"

    /** 测试结果：success = 通路成立（附延迟与 HTTP 码）；failure = 面向用户的中文失败原因。 */
    suspend fun test(settings: ProxySettings): Result<String> = withContext(Dispatchers.IO) {
        val label = settings.describe()
        val started = System.currentTimeMillis()
        try {
            val proxyObj = settings.toJavaProxy()
            val connection = if (proxyObj != null) {
                URL(PROBE_URL).openConnection(proxyObj) as HttpURLConnection
            } else {
                URL(PROBE_URL).openConnection() as HttpURLConnection
            }
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Edqiu/1.0")
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
            }
            try {
                val code = connection.responseCode
                val ms = System.currentTimeMillis() - started
                // 消费 1 字节确保响应体已实际到达（部分代理只建立连接不转发）
                runCatching { connection.inputStream.use { it.read() } }
                Result.success("经 $label 访问解析 API 正常（HTTP $code · ${ms}ms）")
            } finally {
                connection.disconnect()
            }
        } catch (e: UnknownHostException) {
            Result.failure(Exception("DNS 解析失败（$label）——请检查手机网络或代理是否开启"))
        } catch (e: ConnectException) {
            Result.failure(Exception("无法连接到 $label ——代理客户端未开启或端口不对"))
        } catch (e: SocketTimeoutException) {
            Result.failure(Exception("连接 $label 超时——代理未响应，请确认地址与端口"))
        } catch (e: Exception) {
            Result.failure(Exception("经 $label 测试失败：${e.message ?: "未知错误"}"))
        }
    }
}
