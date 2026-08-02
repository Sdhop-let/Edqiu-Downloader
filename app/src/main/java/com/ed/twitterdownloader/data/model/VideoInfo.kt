package com.ed.twitterdownloader.data.model

data class VideoInfo(
    val url: String,
    val title: String,
    val thumbnail: String,
    val duration: Long,
    val uploader: String,
    val formats: List<VideoFormat>,
    val formatMode: FormatMode = FormatMode.QUALITY
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
            hasImageItems && hasVideoItems -> "濯掍綋"
            hasImageItems -> "鍥剧墖"
            else -> "瑙嗛"
        }

    enum class FormatMode {
        QUALITY,
        MEDIA_ITEMS
    }
}
