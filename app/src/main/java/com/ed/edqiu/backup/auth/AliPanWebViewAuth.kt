package com.ed.edqiu.backup.auth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 阿里云盘 WebView 登录 + refresh_token 截取（对应架构文档 N17）。
 *
 * 流程：
 * 1. [createWebView] 打开 aliyundrive.com 网页登录（支持账号密码 / 扫码）；
 * 2. 登录成功后网页前端把 token 写入 localStorage（key = "token"，值形如
 *    `{"access_token":"...","refresh_token":"...","expires_in":7200,...}`）；
 * 3. 在 [WebViewClient.onPageFinished] 注入 JS 读取该值并解析出 refresh_token → 回调 [onToken]；
 * 4. 同时拦截携带 `refresh_token` 的跳转 URL（query / fragment）作为补充通道；
 * 5. 自动截取失败时由 UI（[AliAuthDialog]）引导用户手动粘贴 refresh_token 兜底。
 *
 * 安全：token 只在内存回调中传递，不落日志（Log 仅记录事件，不打印 token）。
 */
class AliPanWebViewAuth(
    private val onToken: (String) -> Unit,
    private val onLoadingChange: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {

    /** 防止同一会话重复回调（页面刷新 / 二次 onPageFinished 均只交付一次）。 */
    private var delivered = false

    private fun deliver(token: String) {
        if (delivered) return
        val t = token.trim()
        if (t.isBlank()) return
        delivered = true
        onToken(t)
    }

    /**
     * 创建并加载登录页的 WebView（调用方负责挂载到视图树并最终 destroy）。
     * 所有回调都在主线程触发。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        // localStorage 依赖 DOM Storage；databaseEnabled（Web SQL）已废弃，无需开启
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString = settings.userAgentString + " EdqiuBackup/1.2.0"

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                captureFromUrl(url)?.let { deliver(it) }
                onLoadingChange(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                captureFromUrl(url)?.let { deliver(it); return }
                onLoadingChange(false)
                // localStorage 需在页面加载完成后访问；evaluateJavascript 结果按 JSON 编码返回
                view?.evaluateJavascript(READ_TOKEN_JS) { result ->
                    captureFromStorage(result)?.let { deliver(it) }
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    onLoadingChange(false)
                    onError("网页加载失败（${error?.errorCode ?: "未知错误"}），可尝试手动粘贴 refresh_token")
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                captureFromUrl(url)?.let { deliver(it) }
                return false // 在本 WebView 内继续加载
            }
        }

        loadUrl(LOGIN_URL)
    }

    /** 从跳转 URL（query / fragment）截取 refresh_token；未命中返回 null。 */
    fun captureFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        parsed.getQueryParameter("refresh_token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return Uri.decode(it) }
        return parsed.getFragmentParameter("refresh_token")
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.decode(it) }
    }

    /**
     * 从注入 JS 的返回值截取 refresh_token。
     *
     * evaluateJavascript 的返回值是 JSON 编码的字符串：
     * - token 对象：`"{\"refresh_token\":\"...\"}"`（外层带引号）
     * - 空：`""` 或 `"null"` / `"undefined"`
     */
    fun captureFromStorage(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank()) return null
        val trimmed = rawJsResult.trim()
        if (trimmed == "null" || trimmed == "undefined") return null

        val tokenText = if (trimmed.startsWith("\"")) {
            try {
                json.parseToJsonElement(trimmed).jsonPrimitive.content
            } catch (e: Exception) {
                return null
            }
        } else {
            trimmed
        }
        if (tokenText.isBlank()) return null

        return try {
            val obj = json.parseToJsonElement(tokenText).jsonObject
            obj["refresh_token"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    private fun Uri.getFragmentParameter(key: String): String? {
        val fragment = fragment ?: return null
        fragment.split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0].trim() == key) {
                return Uri.decode(parts[1].trim())
            }
        }
        return null
    }

    companion object {
        /** 阿里云盘网页版登录地址。 */
        const val LOGIN_URL = "https://www.aliyundrive.com/"

        private const val TOKEN_STORAGE_KEY = "token"
        private const val TAG = "AliPanWebViewAuth"

        private val json = Json { ignoreUnknownKeys = true }

        /** 读取 localStorage token 的注入脚本（同源下可访问；返回空串表示未登录 / 未找到）。 */
        private const val READ_TOKEN_JS =
            "(function(){try{var t=window.localStorage.getItem('$TOKEN_STORAGE_KEY');return t||'';}catch(e){return '';}})()"
    }
}
