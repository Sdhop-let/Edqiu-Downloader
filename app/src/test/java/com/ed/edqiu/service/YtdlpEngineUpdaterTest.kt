package com.ed.edqiu.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YtdlpEngineUpdaterTest {
    private val releaseJson = """
        {
          "tag_name": "2026.11.20",
          "name": "yt-dlp 2026.11.20",
          "assets": [
            {"name": "yt-dlp.exe", "browser_download_url": "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp.exe"},
            {"name": "yt-dlp", "browser_download_url": "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp"},
            {"name": "yt-dlp.zip", "browser_download_url": "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp.zip"}
          ]
        }
    """.trimIndent()

    @Test fun parseReleasePicksZipappAsset() {
        // 资产筛选必须命中无扩展名的 "yt-dlp"（Python zipapp）——
        // 不误中 yt-dlp.exe / yt-dlp.zip 等宿主机产物（库内 updater 同款语义）
        val info = YtdlpEngineUpdater.parseRelease(releaseJson)
        assertEquals("2026.11.20", info.version)
        assertEquals("yt-dlp 2026.11.20", info.versionName)
        assertEquals(
            "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp",
            info.downloadUrl
        )
        assertEquals(
            "https://gh-proxy.com/https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp",
            info.downloadMirrorUrl
        )
    }

    @Test fun parseReleaseRejectsMalformedResponses() {
        val cases = listOf(
            """{"assets":[{"name":"yt-dlp.exe","browser_download_url":"x"}]}""",
            """{"tag_name":"2026.11.20","assets":[{"name":"yt-dlp.exe","browser_download_url":"x"}]}""",
            """{"tag_name":"2026.11.20","assets":[]}"""
        )
        cases.forEach { body ->
            try {
                YtdlpEngineUpdater.parseRelease(body)
                fail("应拒绝畸形响应: $body")
            } catch (expected: Exception) {
                assertTrue(expected is IllegalArgumentException || expected is IllegalStateException)
            }
        }
    }

    @Test fun versionComparisonIsSemanticNotLexicographic() {
        // 逐段数字比较——字符串比较会把 "2026.9.1" 判小于 "2025.12.31"
        assertTrue(YtdlpEngineUpdater.isRemoteNewer("2025.11.12", "2026.11.20"))
        assertTrue(YtdlpEngineUpdater.isRemoteNewer("2026.9.1", "2026.10.1"))
        assertTrue(YtdlpEngineUpdater.isRemoteNewer("2026.11.20", "2026.11.20.1"))
        assertFalse(YtdlpEngineUpdater.isRemoteNewer("2026.11.20", "2026.11.20"))
        assertFalse(YtdlpEngineUpdater.isRemoteNewer("2026.11.21", "2026.11.20"))
        assertFalse(YtdlpEngineUpdater.isRemoteNewer("2026.11.20", "2025.1.1"))
        // nightly tag 形态带 v 前缀同样可比
        assertTrue(YtdlpEngineUpdater.isRemoteNewer("2025.11.12", "v2026.11.20"))
    }
}
