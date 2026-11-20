package com.ed.edqiu.downloader

/** 下载前按同一媒体选择完整可播放格式；有声源不得回退为纯视频。 */
internal object VideoSourcePolicy {
    data class Format(
        val id: String,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        val bitrate: Int = 0,
        val hasAudio: Boolean = true,
        val audioId: String? = null,
        val requiresAudio: Boolean = true,
    ) {
        val shortEdge: Int get() = minOf(width, height)
        val pixels: Long get() = width.toLong() * height
        val selector: String get() = audioId?.let { "$id+$it" } ?: id
        val streams: Int get() = if (audioId == null) 1 else 2
        val playable: Boolean get() = hasAudio || audioId != null || !requiresAudio
    }

    fun selectBetter(baseline: Format, formats: List<Format>): Format? = formats
        .filter { safeId(it.id) && (it.audioId == null || safeId(it.audioId)) && it.playable }
        .filter { improves(baseline, it) }
        .maxWithOrNull(compareBy<Format> { it.shortEdge }.thenBy { it.pixels }
            .thenBy { it.fps }.thenBy { it.bitrate })

    private fun safeId(id: String) = id.matches(Regex("[A-Za-z0-9_.-]+"))

    private fun improves(baseline: Format, candidate: Format): Boolean {
        if (baseline.shortEdge > 0 && candidate.shortEdge > 0) {
            if (candidate.shortEdge != baseline.shortEdge) return candidate.shortEdge > baseline.shortEdge
            if (candidate.pixels != baseline.pixels) return candidate.pixels > baseline.pixels
            if (baseline.fps > 0 && candidate.fps > 0 && candidate.fps != baseline.fps) {
                return candidate.fps > baseline.fps
            }
        }
        return candidate.bitrate > 0 && candidate.bitrate > baseline.bitrate * 1.1
    }
}
