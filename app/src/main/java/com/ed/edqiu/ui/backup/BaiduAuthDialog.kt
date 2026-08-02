package com.ed.edqiu.ui.backup

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.ed.edqiu.backup.auth.DeviceCodeSession
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier

/**
 * 百度网盘设备码扫码授权弹窗（UI 层，状态由调用方 / VM 驱动）。
 *
 * - WebView 加载 `qrcode_url` 展示二维码（[android.webkit.WebView] 经 [AndroidView] 接入）；
 *   二维码无法显示时展示 `user_code` 验证码 + `verification_url` 文本兜底。
 * - 轮询进度由调用方（VM）驱动，通过 [BaiduAuthUiState] 传入并展示；
 * - 授权成功自动回调 [onSuccess]；取消按钮触发 [onCancelAuth]（调用方取消轮询协程）。
 *
 * @param state       授权 UI 状态（请求中 / 会话 / 轮询 / 成功 / 失败）
 * @param onDismiss   关闭弹窗（返回键 / 点击遮罩）
 * @param onCancelAuth 取消授权（停止轮询并关闭）
 * @param onSuccess   授权成功回调（可选，成功后自动触发）
 */
@Composable
fun BaiduAuthDialog(
    state: BaiduAuthUiState,
    onDismiss: () -> Unit,
    onCancelAuth: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    // 授权成功自动通知调用方（仅触发一次）
    LaunchedEffect(state.success) {
        if (state.success) onSuccess?.invoke()
    }

    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(min = 300.dp, max = 360.dp),
            tier = GlassTier.L3,
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "登录百度网盘",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )

                when {
                    // 1. 正在请求设备码
                    state.requesting -> {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text(
                            text = "正在请求授权…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 2. 授权成功
                    state.success -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "授权成功",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = "授权成功，可以开始备份",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // 3. 授权失败
                    state.errorMessage != null -> {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // 4. 已拿到会话：展示二维码 / 验证码
                    state.session != null -> {
                        QrCodeSection(session = state.session!!)
                        if (state.polling) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = state.hint ?: "等待扫码授权…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                text = "请使用百度网盘 App 扫码，或在浏览器打开链接并输入验证码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    // 5. 兜底：状态异常
                    else -> {
                        Text(
                            text = "授权状态异常，请关闭后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                TextButton(onClick = onCancelAuth) {
                    Text(if (state.success) "完成" else "取消")
                }
            }
        }
    }
}

/** 二维码 / 验证码展示区：优先 WebView 二维码，失败则文本兜底。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun QrCodeSection(session: DeviceCodeSession) {
    val qrUrl = session.qrcodeUrl.takeIf { it.isNotBlank() }

    // 持有 WebView 引用，弹窗销毁时释放原生资源
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
    }

    if (qrUrl != null) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                }.also { webViewRef = it }
            },
            update = { view ->
                if (view.url != qrUrl) view.loadUrl(qrUrl)
            },
            modifier = Modifier.size(240.dp),
        )
    }

    if (session.userCode.isNotBlank()) {
        Text(
            text = "验证码：${session.userCode}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }

    if (session.verificationUrl.isNotBlank()) {
        Text(
            text = "若二维码无法显示，请在浏览器打开：\n${session.verificationUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 百度授权弹窗 UI 状态（由调用方 / VM 依据 [com.ed.edqiu.backup.auth.BaiduAuthProgress] 构建）。
 *
 * @param requesting   正在请求设备码
 * @param session      设备码会话（二维码 / 验证码）
 * @param polling      正在轮询授权结果
 * @param hint         轮询提示文案
 * @param success      授权是否成功
 * @param errorMessage 失败原因（面向用户、中文、脱敏）
 */
data class BaiduAuthUiState(
    val requesting: Boolean = false,
    val session: DeviceCodeSession? = null,
    val polling: Boolean = false,
    val hint: String? = null,
    val success: Boolean = false,
    val errorMessage: String? = null,
)
