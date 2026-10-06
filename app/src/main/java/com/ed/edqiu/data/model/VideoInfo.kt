package com.ed.edqiu.data.model

data class VideoInfo(
    val url: String,
    val title: String,
    val thumbnail: String,
    val duration: Long,
    val uploader: String,
    val formats: List<VideoFormat>,
    val formatMode: FormatMode = FormatMode.QUALITY,
    // 2026-10-02 批次B：主媒体显示宽高（px，FXTwitter media.photos[].width/height 或
    // media.videos[]（外层缺失时 videos[] 变体））。null=来源未提供，靠回填 Worker 兜底。
    val width: Int? = null,
    val height: Int? = null
) {
    val durationFormatted: String
        get() {
            if (duration <= 0) return "--:--"
            val mins = duration / 60
            val secs = duration % 60
            return "%02d:%02d".format(mins, secs)
        }

    val bestFormat: VideoFormat?
        get() = formats.firstOrNull()

    val hasMultipleMediaItems: Boolean
        get() = formatMode == FormatMode.MEDIA_ITEMS && formats.size > 1

    val hasImageItems: Boolean
        get() = formats.any { it.mediaType == MediaType.IMAGE }

    val hasVideoItems: Boolean
        get() = formats.any { it.mediaType == MediaType.VIDEO }

    val mediaItemLabel: String
        get() = when {
            hasImageItems && hasVideoItems -> "媒体"
            hasImageItems -> "图片"
            else -> "视频"
        }

    enum class FormatMode {
        QUALITY,
        MEDIA_ITEMS
    }
}
