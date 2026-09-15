package com.ed.edqiu.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.ui.components.CapsuleFeedbackController
import com.ed.edqiu.ui.components.CapsuleFeedbackHost
import com.ed.edqiu.ui.components.FeedbackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 接收系统分享 / 「处理文本」的保存结果页（透明 overlay）。
 *
 * 体验流程（修复「在 Twitter 点保存后卡顿」）：
 * 1. 链接入库是纯本地操作、立即完成；转圈阶段只等待作者/封面元数据补全（最长 8s）；
 * 2. 元数据成功 → 成功胶囊，约 1.1s 后自动返回原应用；
 * 3. 失败 → 底部小操作卡显示原因 +「重试一次」；仍失败可点「完成」退出
 *   （链接已入库，元数据后续由收件箱刷新自动补全）。
 *
 * UI（2026-09-15 重构）：原居中大结果卡占据屏幕上部 1/3，遮挡原 App 内容视野。
 * 改为主 App 同源的「底部胶囊」反馈语言：加载/成功用玻璃胶囊，失败用小型玻璃操作卡，
 * 全部贴底部、不遮内容。本 Activity 不在主 App 导航栈内，自持一套 CapsuleFeedbackController。
 */
private enum class Phase { LOADING, SUCCESS, FAILED }

class CaptureIntentActivity : ComponentActivity() {

    private var phase by mutableStateOf(Phase.LOADING)
    private var failureReason by mutableStateOf("")
    private var retryUsed = false
    private var savedTweetId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        }
        if (text.isNullOrBlank()) {
            finish()
            return
        }

        val container = (application as EdqiuApplication).container
        val coordinator = container.linkCaptureCoordinator
        val repository = container.savedLinkRepository

        fun succeed() {
            phase = Phase.SUCCESS
            lifecycleScope.launch {
                delay(1_100)
                finish()
            }
        }

        fun fail(reason: String) {
            failureReason = reason
            phase = Phase.FAILED
        }

        fun fetchMeta(tweetId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    withTimeoutOrNull(8_000) { repository.fetchMetadata(tweetId) }
                }.getOrDefault(false) == true
                withContext(Dispatchers.Main) {
                    if (ok) succeed()
                    else fail(
                        if (retryUsed) "重试仍失败：网络或链接不可用。链接已保存，可稍后在收件箱刷新补全。"
                        else "作者信息获取失败（网络或链接不可用）"
                    )
                }
            }
        }

        setContent {
            MaterialTheme {
                CaptureFeedbackLayer(
                    phase = phase,
                    failureReason = failureReason,
                    canRetry = !retryUsed && savedTweetId != null,
                    onRetry = {
                        val tweetId = savedTweetId ?: return@CaptureFeedbackLayer
                        retryUsed = true
                        phase = Phase.LOADING
                        fetchMeta(tweetId)
                    },
                    onFinish = { finish() }
                )
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { coordinator.capture(text) }.getOrNull()
                ?: SavedLinkRepository.CaptureResult.Invalid
            when (result) {
                is SavedLinkRepository.CaptureResult.Added -> {
                    savedTweetId = result.tweetId
                    fetchMeta(result.tweetId)
                }
                SavedLinkRepository.CaptureResult.Duplicate ->
                    withContext(Dispatchers.Main) { fail("这条链接已经在收件箱里了") }
                SavedLinkRepository.CaptureResult.Invalid ->
                    withContext(Dispatchers.Main) { fail("不是有效的 X/Twitter 链接") }
            }
        }
    }

    private fun extractText(sourceIntent: Intent?): String? = when (sourceIntent?.action) {
        Intent.ACTION_SEND -> sourceIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        Intent.ACTION_PROCESS_TEXT -> sourceIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }
}

/**
 * 底部反馈层：
 * - LOADING → 常驻胶囊「正在保存…」（sticky，不自动消失）
 * - SUCCESS → 成功胶囊，停留 1.1s 后随 Activity 一起结束
 * - FAILED → 胶囊收起，底部浮出小型操作卡（原因 + 重试/完成）
 */
@Composable
private fun CaptureFeedbackLayer(
    phase: Phase,
    failureReason: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onFinish: () -> Unit
) {
    val capsule = remember { CapsuleFeedbackController() }

    LaunchedEffect(phase) {
        when (phase) {
            Phase.LOADING -> capsule.show(FeedbackKind.NEUTRAL, "正在保存…", sticky = true)
            Phase.SUCCESS -> capsule.show(FeedbackKind.SUCCESS, "已保存到收件箱")
            Phase.FAILED -> capsule.clear()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CapsuleFeedbackHost(
            controller = capsule,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // 失败操作卡：与胶囊退场自然衔接（胶囊下沉收起、卡片浮入）
        AnimatedVisibility(
            visible = phase == Phase.FAILED,
            enter = slideInVertically(tween(240)) { it / 2 } + fadeIn(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SaveFailureCard(
                reason = failureReason,
                canRetry = canRetry,
                onRetry = onRetry,
                onFinish = onFinish
            )
        }
    }
}

/** 失败小型操作卡：深色玻璃与胶囊同源，居中布局，仅失败时出现。 */
@Composable
private fun SaveFailureCard(
    reason: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onFinish: () -> Unit
) {
    val errorAccent = Color(0xFFFF453A)
    Box(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 32.dp)
            .padding(bottom = 20.dp)
            .widthIn(max = 380.dp)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.28f)
            )
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF01A1D21))
    ) {
        // 顶部镜面高光（与 CapsulePill 同语言）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.03f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 60f
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(errorAccent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = errorAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                "保存遇到问题",
                color = Color(0xFFEDEEF1),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                reason,
                color = Color(0xFFB9BEC7),
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canRetry) {
                    Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                        Text("重试一次")
                    }
                }
                OutlinedButton(onClick = onFinish, shape = RoundedCornerShape(12.dp)) {
                    Text("完成")
                }
            }
        }
    }
}
