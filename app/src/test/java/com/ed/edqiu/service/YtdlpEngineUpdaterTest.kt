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
            {"name": "yt-dlp.exe", "browser_download_url": "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp.exe", "size": 17000000},
            {"name": "yt-dlp", "browser_download_url": "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp", "size": 3073072},
            {"name": "yt-dlp.zip", "browser_download_url": "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.20/yt-dlp.zip", "size": 3100000}
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
        // GitHub 元数据 size 随资产带出（下载后长度精确一致性校验的依据）
        assertEquals(3073072L, info.assetSize)
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

    @Test fun parseManifestReadsLastKnownGood() {
        // 仓库静态 LKG 清单（engine/ytdlp-manifest.json 同构）：version/assetUrl/size
        val manifest = """
            {
              "_doc": "说明字段应被忽略",
              "lastKnownGood": {
                "version": "v2025.11.12",
                "assetUrl": "https://github.com/yt-dlp/yt-dlp/releases/download/2025.11.12/yt-dlp",
                "size": 3170726,
                "verifiedAt": "2025-11-12",
                "notes": "随 E-0.1.2 APK 内置分发"
              }
            }
        """.trimIndent()
        val lkg = YtdlpEngineUpdater.parseManifest(manifest)
        assertEquals("2025.11.12", lkg.version)
        assertEquals(
            "https://github.com/yt-dlp/yt-dlp/releases/download/2025.11.12/yt-dlp",
            lkg.downloadUrl
        )
        assertEquals(
            "https://gh-proxy.com/https://github.com/yt-dlp/yt-dlp/releases/download/2025.11.12/yt-dlp",
            lkg.downloadMirrorUrl
        )
        assertEquals(3170726L, lkg.assetSize)
    }

    @Test fun parseManifestRejectsMalformed() {
        val cases = listOf(
            """{"_doc":"没有 lastKnownGood"}""",
            """{"lastKnownGood":{"assetUrl":"https://github.com/x/yt-dlp"}}""",
            """{"lastKnownGood":{"version":"2025.11.12"}}"""
        )
        cases.forEach { body ->
            try {
                YtdlpEngineUpdater.parseManifest(body)
                fail("应拒绝畸形清单: $body")
            } catch (expected: Exception) {
                assertTrue(expected is IllegalArgumentException || expected is IllegalStateException)
            }
        }
    }

    @Test fun validateBinaryAcceptsZipappShebangForm() {
        // 2026-11-21 机器直测：GitHub `yt-dlp` 资产是 shebang + 内嵌 zip 的 zipapp
        //（3,073,072 字节，头两字节 `#!`）——旧实现只认 "PK" 会把正常产物拒掉
        val zipapp = java.io.File.createTempFile("ytdlp-test", ".bin")
        try {
            val content = ("#!/usr/bin/env python3\n").toByteArray() +
                "PK".toByteArray() + ByteArray(2 * 1024 * 1024)
            zipapp.writeBytes(content)
            YtdlpEngineUpdater.validateBinary(zipapp, expectedSize = content.size.toLong())
            // size 不一致必须拒绝
            try {
                YtdlpEngineUpdater.validateBinary(zipapp, expectedSize = content.size.toLong() + 1)
                fail("size 不一致应被拒绝")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("长度"))
            }
        } finally {
            zipapp.delete()
        }
    }

    @Test fun validateBinaryRejectsWrongHeaderAndSmallFiles() {
        val small = java.io.File.createTempFile("ytdlp-small", ".bin")
        val wrong = java.io.File.createTempFile("ytdlp-wrong", ".bin")
        try {
            small.writeBytes(ByteArray(1024)) // 远小于 1MB 下限
            wrong.writeBytes("not-a-zipapp-body".toByteArray() + ByteArray(2 * 1024 * 1024))
            try {
                YtdlpEngineUpdater.validateBinary(small)
                fail("过小文件应被拒绝")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("过小"))
            }
            try {
                YtdlpEngineUpdater.validateBinary(wrong)
                fail("非法头应被拒绝")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("zipapp"))
            }
        } finally {
            small.delete()
            wrong.delete()
        }
    }
}
