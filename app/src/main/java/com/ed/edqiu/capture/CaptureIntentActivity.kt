package com.ed.edqiu.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.data.repository.SavedLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 接收系统分享 / 「处理文本」的保存结果页。
 *
 * 体验流程（修复「在 Twitter 点保存后卡顿」）：
 * 1. 链接入库是纯本地操作、立即完成；转圈阶段只等待作者/封面元数据补全（最长 8s）；
 * 2. 元数据成功 → 绿色对勾 +「已保存」，约 1s 后自动返回原应用；
 * 3. 失败 → 显示原因 +「重试一次」；仍失败可点「完成」退出
 *   （链接已入库，元数据后续由收件箱刷新自动补全）。
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
                SaveResultCard(
                    phase = phase,
                    failureReason = failureReason,
                    canRetry = !retryUsed && savedTweetId != null,
                    onRetry = {
                        val tweetId = savedTweetId ?: return@SaveResultCard
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

/** 居中玻璃结果卡：转圈 / 对勾 / 失败原因 + 重试。 */
@Composable
private fun SaveResultCard(
    phase: Phase,
    failureReason: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onFinish: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (phase) {
                    Phase.LOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 4.dp
                        )
                        Text("正在保存…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("正在补全作者与封面信息", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Phase.SUCCESS -> {
                        SuccessCheck()
                        Text("已保存到收件箱", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Phase.FAILED -> {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Text("保存遇到问题", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            failureReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (canRetry) {
                                Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) {
                                    Text("重试一次")
                                }
                            }
                            OutlinedButton(onClick = onFinish, shape = RoundedCornerShape(14.dp)) {
                                Text("完成")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 完成态：绿色圆底 + 缩放弹入的对勾。 */
@Composable
private fun SuccessCheck() {
    var shown by mutableStateOf(false)
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.3f,
        animationSpec = tween(260),
        label = "check_scale"
    )
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    Box(
        modifier = Modifier
            .size(46.dp)
            .alpha(scale)
            .clip(CircleShape)
            .background(Color(0xFF22C55E)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
