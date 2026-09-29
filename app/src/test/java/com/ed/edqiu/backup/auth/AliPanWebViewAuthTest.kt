package com.ed.edqiu.backup.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AliPanWebViewAuth.captureFromStorage] 纯逻辑单测（不依赖 Android 运行时）。
 *
 * 覆盖 evaluateJavascript 各种返回值形态：
 * - JSON 编码的带引号对象字符串（WebView 实际返回形态）
 * - 裸 JSON 对象字符串
 * - null / undefined / 空串 / 非 JSON
 * - 缺少 refresh_token 的 token 对象
 */
class AliPanWebViewAuthTest {

    private val auth = AliPanWebViewAuth(onToken = {})

    @Test
    fun `blank or null raw result returns null`() {
        assertNull(auth.captureFromStorage(null))
        assertNull(auth.captureFromStorage(""))
        assertNull(auth.captureFromStorage("   "))
        assertNull(auth.captureFromStorage("null"))
        assertNull(auth.captureFromStorage("undefined"))
    }

    @Test
    fun `webview json encoded object with refresh_token returns token`() {
        // evaluateJavascript 对返回的 JSON 字符串再做一次 JSON 编码（外层引号 + 转义）
        val raw = "\"{\\\"access_token\\\":\\\"at\\\",\\\"refresh_token\\\":\\\"rt-123\\\",\\\"expires_in\\\":7200}\""
        assertEquals("rt-123", auth.captureFromStorage(raw))
    }

    @Test
    fun `bare json object with refresh_token returns token`() {
        val raw = """{"access_token":"at","refresh_token":"rt-456","expires_in":7200}"""
        assertEquals("rt-456", auth.captureFromStorage(raw))
    }

    @Test
    fun `token object without refresh_token returns null`() {
        val raw = """{"access_token":"at","expires_in":7200}"""
        assertNull(auth.captureFromStorage(raw))
    }

    @Test
    fun `non json garbage returns null`() {
        assertNull(auth.captureFromStorage("hello world"))
        assertNull(auth.captureFromStorage("12345"))
        assertNull(auth.captureFromStorage("\"just a string\""))
    }
}
