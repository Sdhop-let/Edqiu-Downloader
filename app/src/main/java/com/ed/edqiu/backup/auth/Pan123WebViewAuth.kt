package com.ed.edqiu.backup.auth

import com.ed.edqiu.BuildConfig
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 123 网盘 WebView 授权（OAuth 授权码流程）。
 *
 * 流程：
 * 1. [createWebView] 打开授权页 [authorizeUrl]（用户扫码 / 账号密码登录并授权）；
 * 2. 授权成功后 123 网盘 302 重定向到 redirect_uri 并携带 `?code=...`；
 * 3. 在 [WebViewClient.shouldOverrideUrlLoading] / [onPageStarted] 拦截含 `code` 的 URL，
 *    解析出授权码回调 [onCode]（拦截后返回 true，不再加载目标地址）；
 * 4. 授权码由 [com.ed.edqiu.backup.provider.Pan123OpenTarget.setAuthCode] 换取 token。
 *
 * 安全：授权码只在内存回调中传递，不落日志。
 */
class Pan123WebViewAuth(
    private val authorizeUrl: String,
    private val onCode: (String) -> Unit,
    private val onLoadingChange: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {

    /** 防止同一会话重复回调。 */
    private var delivered = false

    private fun deliver(code: String) {
        if (delivered) return
        val c = code.trim()
        if (c.isBlank()) return
        delivered = true
        onCode(c)
    }

    /**
     * 创建并加载授权页的 WebView（调用方负责挂载视图并最终 destroy）。
     * 所有回调都在主线程触发。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString = settings.userAgentString + " EdqiuBackup/" + BuildConfig.VERSION_NAME

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                captureCodeFromUrl(url)?.let { deliver(it); return }
                onLoadingChange(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                captureCodeFromUrl(url)?.let { deliver(it); return }
                onLoadingChange(false)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                captureCodeFromUrl(url)?.let {
                    deliver(it)
                    return true // 已拿到授权码，拦截跳转
                }
                return false // 其余 URL 继续在 WebView 内加载
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 自定义 scheme（如 edqiu123://）被系统当作"加载失败"是预期——授权码已在
                // shouldOverrideUrlLoading 中被拦截，此处仅对主框架真实失败提示。
                if (request?.isForMainFrame == true && request.url.toString().contains("://") &&
                    !request.url.toString().startsWith("http")
                ) {
                    return
                }
                if (request?.isForMainFrame == true) {
                    onLoadingChange(false)
                    onError("网页加载失败（${error?.errorCode ?: "未知错误"}），请检查网络后重试")
                }
            }
        }

        loadUrl(authorizeUrl)
    }

    /** 从跳转 URL 的 query 截取授权码；未命中返回 null。 */
    fun captureCodeFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        return parsed.getQueryParameter("code")
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.decode(it) }
    }
}
