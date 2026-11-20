package com.ed.edqiu.service

import org.junit.Assert.*
import org.junit.Test

class YtdlpProgressTrackerTest {
    @Test fun videoAndAudioUseFixedTotalFromTheStart() {
        val tracker = YtdlpProgressTracker(2)
        val values = listOf(
            tracker.update(99f, "EDQIU|tweet|video|downloading"),
            tracker.update(100f, "EDQIU|tweet|video|finished"),
            tracker.update(0f, "EDQIU|tweet|audio|downloading"),
            tracker.update(50f, "EDQIU|tweet|audio|downloading"),
            tracker.update(100f, "EDQIU|tweet|audio|finished"),
        )
        assertEquals(listOf(49.5f, 50f, 50f, 75f, 99f), values)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun retryOfSameFormatDoesNotBecomeAnAudioStream() {
        val tracker = YtdlpProgressTracker(2)
        assertEquals(49.5f, tracker.update(99f, "EDQIU|tweet|video|downloading"))
        assertEquals(49.5f, tracker.update(0f, "EDQIU|tweet|video|downloading"))
        assertEquals(49.5f, tracker.update(Float.NaN, "warning"))
    }

    @Test fun logLinesDoNotDuplicateTheLastStreamProgress() {
        val tracker = YtdlpProgressTracker(2)
        assertEquals(50f, tracker.update(99f, "EDQIU|tweet|video|finished|100.0%"))
        assertEquals(50f, tracker.update(99f, "[download] Destination: audio"))
        assertEquals(50f, tracker.update(99f, "EDQIU|tweet|audio|downloading|0.0%"))
        assertEquals(60f, tracker.update(99f, "EDQIU|tweet|audio|downloading|20.0%"))
    }

    @Test fun singleStreamReservesFinalizationAndNeverRecedes() {
        val tracker = YtdlpProgressTracker(1)
        assertEquals(80f, tracker.update(80f, "EDQIU|tweet|combined|downloading"))
        assertEquals(80f, tracker.update(20f, "EDQIU|tweet|combined|downloading"))
        assertEquals(99f, tracker.update(100f, "EDQIU|tweet|combined|finished"))
    }

    @Test fun singleFormatFallbackReachesFullProgressViaInfoLine() {
        // P3 审计修复（2026-11-21）：单格式回退（GIF 无独立音轨）按 info 行动态定分母，
        // 进度不再恒卡 ≤50%
        val tracker = YtdlpProgressTracker(2)
        tracker.update(0f, "[info] tweet: Downloading 1 format(s): 137")
        assertEquals(99f, tracker.update(99f, "EDQIU|tweet|137|downloading"))
        assertEquals(99f, tracker.update(100f, "EDQIU|tweet|137|finished"))
    }

    @Test fun twoFormatInfoLineKeepsDenominatorAtTwo() {
        val tracker = YtdlpProgressTracker(2)
        tracker.update(0f, "[info] tweet: Downloading 1 format(s): 137+140")
        assertEquals(49.5f, tracker.update(99f, "EDQIU|tweet|video|downloading"))
    }
}
