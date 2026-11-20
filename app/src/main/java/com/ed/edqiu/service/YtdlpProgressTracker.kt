package com.ed.edqiu.service

/** 固定音视频阶段总数；同格式重试不会被误认成新流，未知进度保持上次值。 */
internal class YtdlpProgressTracker(expectedStreams: Int) {
    private val streamCount = expectedStreams.coerceAtLeast(1)
    private val peaks = linkedMapOf<String, Float>()
    private var lastProgress = 0f

    @Synchronized
    fun update(progress: Float, line: String): Float {
        val marker = line.substringAfter("EDQIU|", "").trim().split('|')
        if (marker.size >= 3) {
            peaks.remove("unknown")
            val key = marker[0] + "|" + marker[1]
            val percent = if (marker[2].startsWith("finished")) 100f else
                marker.getOrNull(3)?.trim()?.removeSuffix("%")?.toFloatOrNull() ?: progress
            if (percent.isFinite() && percent >= 0f) {
                peaks[key] = maxOf(peaks[key] ?: 0f, percent.coerceIn(0f, 100f))
            }
        } else if (peaks.keys.none { it != "unknown" } && progress.isFinite() && progress >= 0f) {
            peaks["unknown"] = maxOf(peaks["unknown"] ?: 0f, progress.coerceIn(0f, 100f))
        }
        val total = (peaks.values.sum() / streamCount).coerceIn(0f, 99f)
        lastProgress = maxOf(lastProgress, total)
        return lastProgress
    }

    companion object {
        // 保留库所需的 [download]/百分比/ETA 格式，并附上可识别的媒体与格式身份。
        const val PROGRESS_TEMPLATE = "download:[download] %(progress._percent_str)s of %(progress._total_bytes_str)s at %(progress._speed_str)s ETA %(progress._eta_str)s EDQIU|%(info.id)s|%(info.format_id)s|%(progress.status)s|%(progress._percent_str)s"
    }
}
