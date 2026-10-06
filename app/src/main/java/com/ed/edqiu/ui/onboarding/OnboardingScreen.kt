package com.ed.edqiu.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import com.ed.edqiu.data.model.ProxySettings
import com.ed.edqiu.data.preferences.CookiePreferences
import com.ed.edqiu.data.preferences.ProxyPreferences
import com.ed.edqiu.data.preferences.SettingsRepository
import com.ed.edqiu.data.proxy.ProxyDetector
import com.ed.edqiu.data.proxy.ProxyTester
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次安装引导（2026-10 UX 短板补齐：开箱即用感）。
 *
 * 三步横滑：
 *   1. 欢迎 + 核心能力说明；
 *   2. X Cookie（可选，可直接填写保存——解锁登录态高画质/受限内容解析）；
 *   3. 代理（可选，可直接填写/自动检测/连通测试——国内网络访问 X 需要）。
 *
 * 每步都可「跳过」，右上角可跳过全部；完成或跳过都会落库 onboarding_completed，
 * 之后不再出现。所有配置写入与设置页同一套 Preferences，配置完即全局生效。
 */
@Composable
fun OnboardingScreen(
    settingsRepository: SettingsRepository,
    onFinished: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    val complete: () -> Unit = {
        scope.launch {
            settingsRepository.setOnboardingCompleted(true)
            onFinished()
        }
    }
    val nextPage: () -> Unit = {
        scope.launch {
            if (pagerState.currentPage < 2) pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = complete) { Text("跳过全部") }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> WelcomeStep(isCurrent = pagerState.currentPage == page)
                1 -> CookieStep(
                    isCurrent = pagerState.currentPage == page,
                    onNext = nextPage,
                    onSkipToProxy = { scope.launch { pagerState.animateScrollToPage(2) } }
                )
                else -> ProxyStep(
                    isCurrent = pagerState.currentPage == page,
                    onFinish = complete
                )
            }
        }

        // 页面指示器（2026-10-07 引导动画）：圆点宽度/颜色随翻页位置连续插值，
        // 跟手渐变而非瞬变；当前页圆点在拖拽中即开始伸长
        val indicatorPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(3) { index ->
                val dist = (indicatorPos - index).let { if (it < 0f) -it else it }.coerceIn(0f, 1f)
                val dotWidth: Dp = lerpDp(22.dp, 8.dp, dist)
                val dotColor = lerpColor(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.surfaceVariant,
                    dist
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 5.dp)
                        .size(width = dotWidth, height = 8.dp)
                        .background(
                            color = dotColor,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun StepShell(
    icon: ImageVector,
    title: String,
    subtitle: String,
    // 2026-10-07 引导动画：本步成为当前页时重放错峰入场（图标→标题→副标题→内容
    // 依次淡入+上移，480ms FastOutSlowIn）；滑走归零，来回滑动可重放
    isCurrent: Boolean,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(0f)
        }
    }
    // 错峰取值：start 起步、0.4 窗口内完成（四段起步 0/0.12/0.24/0.36）
    fun stage(start: Float): Float = ((progress.value - start) / 0.4f).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    val s = stage(0f)
                    alpha = s
                    translationY = (1f - s) * 28.dp.toPx()
                }
        )
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val s = stage(0.12f)
                alpha = s
                translationY = (1f - s) * 24.dp.toPx()
            }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val s = stage(0.24f)
                alpha = s
                translationY = (1f - s) * 20.dp.toPx()
            }
        )
        Spacer(Modifier.height(26.dp))
        Box(
            modifier = Modifier.graphicsLayer {
                val s = stage(0.36f)
                alpha = s
                translationY = (1f - s) * 16.dp.toPx()
            }
        ) {
            content()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeStep(isCurrent: Boolean) {
    StepShell(
        isCurrent = isCurrent,
        icon = Icons.Outlined.Bolt,
        title = "欢迎使用 Edqiu",
        subtitle = "把 X / Twitter 的视频与图片保存到本机，并随时备份到你的网盘"
    ) {
        FeatureRow("分享保存", "在 X 里点分享选 Edqiu，或粘贴推文链接到收件箱")
        FeatureRow("一键下载", "自动选最高画质；支持批量与作者订阅")
        FeatureRow("历史备份", "下载历史本地留存，可直连备份到百度/阿里/123/WebDAV")
        Spacer(Modifier.height(8.dp))
        Text(
            "后两步是可选配置，跳过也能正常使用",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FeatureRow(title: String, description: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CookieStep(
    isCurrent: Boolean,
    onNext: () -> Unit,
    onSkipToProxy: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cookiePreferences = remember { CookiePreferences(context) }
    val alreadyConfigured = remember { cookiePreferences.hasCookies() }
    var authToken by remember { mutableStateOf("") }
    var ct0 by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    StepShell(
        isCurrent = isCurrent,
        icon = Icons.Outlined.CloudUpload,
        title = "配置 X Cookie（可选）",
        subtitle = "浏览器登录 x.com 后复制 auth_token 与 ct0 两个 Cookie 值。配置后可解析登录态高画质与受限内容；不配置也能使用公开解析。"
    ) {
        if (alreadyConfigured) {
            Text(
                "✓ 已配置过 Cookie，可直接下一步；重新填写将覆盖",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = authToken,
            onValueChange = { authToken = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("auth_token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = ct0,
            onValueChange = { ct0 = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ct0") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSkipToProxy,
                modifier = Modifier.weight(1f)
            ) { Text("稍后再配") }
            Button(
                onClick = {
                    when {
                        authToken.isBlank() || ct0.isBlank() -> message = "两个 Cookie 值都不能为空"
                        else -> {
                            saving = true
                            cookiePreferences.saveCookies(authToken, ct0)
                            saving = false
                            message = "已保存并加密存储 ✓"
                            onNext()
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f)
            ) { Text(if (saving) "保存中…" else "保存并继续") }
        }
    }
}

@Composable
private fun ProxyStep(
    isCurrent: Boolean,
    onFinish: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val proxyPreferences = remember { ProxyPreferences(context) }
    val existing = remember { proxyPreferences.getProxySettings() }
    var enabled by remember { mutableStateOf(existing.enabled) }
    var host by remember { mutableStateOf(existing.host) }
    var port by remember { mutableStateOf(existing.port.toString()) }
    var type by remember { mutableStateOf(existing.type) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    StepShell(
        isCurrent = isCurrent,
        icon = Icons.Outlined.VpnLock,
        title = "配置代理（可选）",
        subtitle = "国内网络直连访问 X 通常需要代理（Clash / V2Ray 等）。填本机代理端口即可；也可以直接完成，稍后在「设置 → 网络与认证」里配置。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用代理", fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("主机") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) port = value },
                    modifier = Modifier.width(110.dp),
                    label = { Text("端口") },
                    singleLine = true
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ProxySettings.TYPE_HTTP to "HTTP",
                    ProxySettings.TYPE_SOCKS5 to "SOCKS5"
                ).forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { type = value },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = if (type == value) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val detection = withContext(Dispatchers.IO) { ProxyDetector.detect(context) }
                            if (detection.kind == "PROXY" && detection.port != null) {
                                host = detection.host
                                port = detection.port.toString()
                                message = "检测到代理：${detection.description}"
                            } else {
                                message = detection.description
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(if (busy) "检测中…" else "自动检测") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val outcome = ProxyTester.test(
                                ProxySettings(
                                    enabled = true,
                                    host = host.ifBlank { "127.0.0.1" },
                                    port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7890,
                                    type = type
                                )
                            )
                            message = outcome.fold(
                                onSuccess = { "连通正常：$it" },
                                onFailure = { it.message ?: "测试失败" }
                            )
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(if (busy) "测试中…" else "测试连通") }
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier.weight(1f)
            ) { Text("直接完成") }
            Button(
                onClick = {
                    if (!enabled) {
                        onFinish()
                        return@Button
                    }
                    proxyPreferences.saveProxySettings(
                        ProxySettings(
                            enabled = true,
                            host = host.ifBlank { "127.0.0.1" },
                            port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7890,
                            type = type
                        )
                    )
                    onFinish()
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (enabled) "保存并完成" else "完成") }
        }
    }
}
