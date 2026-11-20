package com.ed.edqiu.downloader

import org.junit.Assert.*
import org.junit.Test

class VideoSourcePolicyTest {
    private val baseline = VideoSourcePolicy.Format("mp4", 1920, 1080, 30.0, 2_000_000)

    @Test fun equivalentSourceDoesNotRouteToAnotherDownload() {
        assertNull(VideoSourcePolicy.selectBetter(baseline, listOf(
            baseline.copy(id = "hls"), baseline.copy(id = "noise", bitrate = 2_100_000))))
    }

    @Test fun higherResolutionWinsEvenWithLowerBitrate() {
        // video-only 候选必须配套音频流才可入选（有声源不得回退为纯视频）。
        val better = VideoSourcePolicy.Format("2160", 3840, 2160, 30.0, 1_800_000, false, "bestaudio")
        assertEquals(better, VideoSourcePolicy.selectBetter(baseline, listOf(better)))
        assertEquals("2160+bestaudio", better.selector)
        assertEquals(2, better.streams)
    }

    @Test fun videoOnlyWithoutAudioMateIsRejected() {
        // requiresAudio 任务的纯视频流（无 audioId）不可播，选源必须拒绝，交回直连源。
        val mute = VideoSourcePolicy.Format("2160", 3840, 2160, 30.0, 1_800_000, false)
        assertNull(VideoSourcePolicy.selectBetter(baseline, listOf(mute)))
    }

    @Test fun higherFrameRateWinsAndLowerResolutionDoesNot() {
        val fps = baseline.copy(id = "60fps", fps = 60.0, bitrate = 1_800_000)
        val lower = baseline.copy(id = "720", width = 1280, height = 720, bitrate = 8_000_000)
        assertEquals(fps, VideoSourcePolicy.selectBetter(baseline, listOf(lower, fps)))
        assertNull(VideoSourcePolicy.selectBetter(baseline, listOf(lower)))
    }

    @Test fun unavailableOrUnsafeFormatsKeepExistingSource() {
        assertNull(VideoSourcePolicy.selectBetter(baseline, emptyList()))
        assertNull(VideoSourcePolicy.selectBetter(baseline, listOf(
            baseline.copy(id = "best/bad", bitrate = 9_000_000))))
    }

    @Test fun combinedFormatNeedsOneStream() {
        val selected = baseline.copy(id = "1080", bitrate = 3_000_000)
        assertEquals(selected, VideoSourcePolicy.selectBetter(baseline, listOf(selected)))
        assertEquals("1080", selected.selector)
        assertEquals(1, selected.streams)
    }
}
