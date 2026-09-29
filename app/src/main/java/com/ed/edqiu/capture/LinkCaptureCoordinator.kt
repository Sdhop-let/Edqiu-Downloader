package com.ed.edqiu.capture

import com.ed.edqiu.data.repository.SavedLinkRepository
import com.ed.edqiu.domain.TweetIdExtractor

/**
 * 所有外部入口共用的链接捕获管线。
 * 只保留严格匹配的 Twitter/X status URL，并统一规范化后交给仓库原子去重。
 */
class LinkCaptureCoordinator(
    private val repository: SavedLinkRepository
) {

    suspend fun capture(text: CharSequence?): SavedLinkRepository.CaptureResult {
        val canonicalUrl = TweetIdExtractor.canonicalUrlFromText(text?.toString())
            ?: return SavedLinkRepository.CaptureResult.Invalid
        return repository.capture(canonicalUrl)
    }
}
