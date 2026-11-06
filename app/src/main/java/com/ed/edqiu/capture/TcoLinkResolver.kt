package com.ed.edqiu.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * t.co 短链展开兜底（2026-11-04）。
 *
 * 触发条件严格：文本里找不到任何严格 status 链接、却存在 t.co 短链时才走网络
 * （GET 跟随跳转取最终 URL），避免给正常捕获路径引入任何网络等待。
 * 逐条限时 2.5s、最多 3 条，失败静默回退原文——捕获离线语义不被破坏。
 */
object TcoLinkResolver {

    private const val MAX_LINKS = 3
    private const val TIMEOUT_MS = 2500

    // t.co 码位实际 6-7 位，取 4 位下限；前置断言防误命中其他域名内的 "t.co" 字样
    private val SHORT_LINK = Regex(
        """(?i)(?<![A-Za-z0-9.])(?:https?://)?(?:www\.)?t\.co/[A-Za-z0-9_-]{4,}"""
    )

    /** 提取文本中的 t.co 短链（纯离线，供 UI 层快速判定是否存在可捕获对象）。 */
    fun findShortLinks(text: String): List<String> =
        SHORT_LINK.findAll(text.take(8_192))
            .map { it.value }
            .distinct()
            .take(MAX_LINKS)
            .toList()

    /** 兜底展开：原文 + 逐条解析出的最终 URL 追加返回（解析失败保持原文）。 */
    suspend fun expand(text: String): String {
        val shorts = findShortLinks(text)
        if (shorts.isEmpty()) return text
        val resolved = shorts.mapNotNull { short ->
            runCatching { resolveOne(short) }.getOrNull()
        }
        return if (resolved.isEmpty()) text
        else text + "\n" + resolved.joinToString("\n")
    }

    private suspend fun resolveOne(short: String): String? = withContext(Dispatchers.IO) {
        val target = if (short.startsWith("http", ignoreCase = true)) short else "https://$short"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Edqiu")
            }
            // 触发请求即可（跳转信息在响应头），只读 1 字节省流量
            runCatching { conn.inputStream.use { it.read() } }
            val final = conn.url.toString()
            // 只认真正落到 status 页的跳转，避免把 t.co 原样带回污染文本
            if (final != target && final.contains("/status/", ignoreCase = true)) final else null
        } finally {
            conn?.disconnect()
        }
    }
}
