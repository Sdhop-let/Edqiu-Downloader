package com.ed.edqiu.ui.backup

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ed.edqiu.backup.auth.Pan123WebViewAuth

/**
 * 123 网盘授权弹窗（OAuth 授权码流程）。
 *
 * - [AndroidView] 内嵌 WebView 打开授权页（支持扫码 / 账号密码登录并授权）；
 * - 授权成功后自动拦截回调 URL 中的 code，回调 [onCodeReceived]；
 * - 本组件不自动关闭：由调用方（VM）在 `target.setAuthCode()` 成功后关闭，失败保留重试。
 *
 * @param authorizeUrl 完整授权页 URL（由 [com.ed.edqiu.backup.provider.Pan123OpenTarget.buildAuthorizeUrl] 生成）
 */
@Composable
fun Pan123AuthDialog(
    authorizeUrl: String,
    onDismiss: () -> Unit,
    onCodeReceived: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val currentOnCodeReceived by rememberUpdatedState(onCodeReceived)

    val auth = remember(authorizeUrl) {
        Pan123WebViewAuth(
            authorizeUrl = authorizeUrl,
            onCode = { code -> currentOnCodeReceived(code) },
            onLoadingChange = { loading = it },
            onError = { message -> error = message; loading = false },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val wv = webViewRef.value
            wv?.stopLoading()
            (wv?.parent as? ViewGroup)?.removeView(wv)
            wv?.destroy()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("登录 123 网盘") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "在下方登录 123 网盘并授权，完成后自动返回。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WEBVIEW_HEIGHT_DP)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val wv = auth.createWebView(ctx)
                            webViewRef.value = wv
                            wv
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val WEBVIEW_HEIGHT_DP = 400.dp
