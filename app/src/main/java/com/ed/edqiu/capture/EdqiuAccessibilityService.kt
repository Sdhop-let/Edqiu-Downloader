package com.ed.edqiu.capture

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.ed.edqiu.EdqiuApplication
import com.ed.edqiu.clipboard.ClipboardCapture
import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.domain.TweetIdExtractor
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 在明确的“复制”操作后，尽力读取系统当前剪贴板。
 * 不读取节点树、页面正文或任何输入法私有剪贴板历史。
 */
class EdqiuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packagePolicy by lazy { CapturePackagePolicy(this) }
    private val coordinator by lazy {
        (application as EdqiuApplication).container.linkCaptureCoordinator
    }

    private var lastTriggerAt = 0L
    private var lastCapturedTweetId: String? = null
    private var lastCapturedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!packagePolicy.isAllowed(event.packageName)) return
        if (!isCopyEvent(event)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < TRIGGER_THROTTLE_MS) return
        lastTriggerAt = now

        serviceScope.launch {
            delay(CLIPBOARD_SETTLE_MS)
            if (!captureCurrentClipboard()) {
                delay(CLIPBOARD_RETRY_MS)
                captureCurrentClipboard()
            }
        }
    }

    private suspend fun captureCurrentClipboard(): Boolean {
        val text = ClipboardCapture.readLatest(this) ?: return false
        val canonicalUrl = TweetIdExtractor.canonicalUrlFromText(text) ?: return true
        val tweetId = TweetIdExtractor.fromUrl(canonicalUrl) ?: return true

        val now = SystemClock.elapsedRealtime()
        if (tweetId == lastCapturedTweetId && now - lastCapturedAt < SAME_TWEET_SUPPRESSION_MS) {
            return true
        }
        return when (coordinator.capture(canonicalUrl)) {
            SavedLinkRepository.CaptureResult.Added,
            SavedLinkRepository.CaptureResult.Duplicate -> {
                lastCapturedTweetId = tweetId
                lastCapturedAt = now
                true
            }
            SavedLinkRepository.CaptureResult.Invalid -> false
        }
    }

    private fun isCopyEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType !in SUPPORTED_EVENT_TYPES) return false

        val labels = buildList {
            event.text.forEach { value ->
                value?.toString()?.trim()?.takeIf { it.length <= MAX_EVENT_LABEL_LENGTH }?.let(::add)
            }
            event.contentDescription
                ?.toString()
                ?.trim()
                ?.takeIf { it.length <= MAX_EVENT_LABEL_LENGTH }
                ?.let(::add)
        }
        return labels.any { it.lowercase(Locale.ROOT) in COPY_LABELS }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TRIGGER_THROTTLE_MS = 750L
        const val CLIPBOARD_SETTLE_MS = 150L
        const val CLIPBOARD_RETRY_MS = 200L
        const val SAME_TWEET_SUPPRESSION_MS = 10_000L
        const val MAX_EVENT_LABEL_LENGTH = 32

        val SUPPORTED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        )

        val COPY_LABELS = setOf(
            "复制",
            "复制链接",
            "链接已复制",
            "已复制",
            "copy",
            "copy link",
            "copied",
            "link copied"
        )
    }
}
