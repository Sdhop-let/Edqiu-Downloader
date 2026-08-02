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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ed.edqiu.backup.auth.AliPanWebViewAuth

/**
 * 阿里云盘授权弹窗（对应架构文档 N23 / 阿里时序图场景 2）。
 *
 * - [AndroidView] 内嵌 WebView 打开 aliyundrive.com 网页登录（支持账号密码 / 扫码）；
 * - 自动截取 refresh_token 成功后回调 [onTokenReceived]；
 * - 自动截取失败时用户可手动粘贴 refresh_token 兜底，点「提交 Token」同样回调 [onTokenReceived]；
 * - 本组件**不自动关闭**：是否关闭由调用方（VM）在调用 [onTokenReceived] 后根据
 *   `target.setRefreshToken()` 结果决定（成功关闭 / 失败保留让用户重试）。
 *
 * @param onDismiss 用户点「取消」或点击弹窗外区域
 * @param onTokenReceived 拿到 refresh_token（自动截取或手动粘贴）时回调
 */
@Composable
fun AliAuthDialog(
    onDismiss: () -> Unit,
    onTokenReceived: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val currentOnTokenReceived by rememberUpdatedState(onTokenReceived)

    fun submit(token: String) {
        val t = token.trim()
        if (t.isNotBlank()) currentOnTokenReceived(t)
    }

    val auth = remember(context) {
        AliPanWebViewAuth(
            onToken = { token -> submit(token) },
            onLoadingChange = { loading = it },
            onError = { message -> error = message; loading = false },
        )
    }

    // 退出组合时释放 WebView，避免内存泄漏
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
        title = { Text("登录阿里云盘") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // WebView 登录区（带加载进度）
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

                HorizontalDivider()

                Text(
                    text = "自动获取失败？可在网页登录后，从阿里云盘开放平台 / 登录工具中复制 refresh_token 粘贴到下方：",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("粘贴 refresh_token") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pasted.isNotBlank(),
                onClick = { submit(pasted) },
            ) { Text("提交 Token") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val WEBVIEW_HEIGHT_DP = 380.dp
