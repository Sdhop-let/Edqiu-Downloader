package com.ed.twitterdownloader.data.model

import java.util.Locale

data class VideoFormat(
    val formatId: String,
    val quality: String,
    val ext: String,
    val filesize: Long,
    val vcodec: String = "",
    val acodec: String = "",
    val fps: Int = 0,
    /** Direct download URL from fxtwitter API. If non-null, bypass yt-dlp and download directly. */
    val directUrl: String? = null,
    /** Per-media thumbnail URL when one tweet contains multiple media items. */
    val thumbnail: String? = null,
    /** 1-based media index when this item represents one media item in a tweet/playlist. */
    val mediaIndex: Int? = null,
    /** True when this item is a media entry, not a quality variant. */
    val isMediaItem: Boolean = false,
    /** Whether this downloadable item is a video or image. */
    val mediaType: MediaType = MediaType.VIDEO
) {
    val isImage: Boolean
        get() = mediaType == MediaType.IMAGE

    val displayText: String
        get() = buildString {
            append(quality.ifBlank { MediaFileTypes.displayLabel(mediaType) })
            if (mediaType == MediaType.VIDEO && fps > 0) append(" 路 ${fps}fps")
            if (filesize > 0) append(" 路 ${formatFileSize()}")
            if (mediaType == MediaType.IMAGE && ext.isNotBlank() && !quality.uppercase(Locale.ROOT).contains(ext.uppercase(Locale.ROOT))) {
                append(" 路 ${ext.uppercase(Locale.ROOT)}")
            }
        }

    private fun formatFileSize(): String {
        val mb = filesize / (1024.0 * 1024.0)
        return if (mb >= 1) "${"%.1f".format(mb)}MB" else "${filesize / 1024}KB"
    }
}
