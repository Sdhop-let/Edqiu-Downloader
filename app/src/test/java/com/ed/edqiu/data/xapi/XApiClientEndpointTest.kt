package com.ed.edqiu.data.xapi

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class XApiClientEndpointTest {

    @Test fun validFxHostsAcceptsHttpsHostList() {
        val raw = JSONArray().put("https://api.fxtwitter.com").put("https://mirror.example.com:8443")
        assertEquals(
            listOf("https://api.fxtwitter.com", "https://mirror.example.com:8443"),
            XApiClient.validFxHosts(raw)
        )
    }

    @Test fun validFxHostsRejectsGarbageAndOversized() {
        // 形状白名单：非 https / 带路径 / 超过 5 条 / 空数组——一律整体拒绝（保留原值）
        assertNull(XApiClient.validFxHosts(JSONArray().put("http://api.fxtwitter.com")))
        assertNull(XApiClient.validFxHosts(JSONArray().put("https://api.fxtwitter.com/Twitter/status/")))
        assertNull(XApiClient.validFxHosts(JSONArray().put("not a url")))
        assertNull(XApiClient.validFxHosts(JSONArray()))
        assertNull(
            XApiClient.validFxHosts(
                JSONArray().apply {
                    repeat(6) { put("https://h$it.example.com") }
                }
            )
        )
    }

    @Test fun validBskyEndpointAcceptsOnlyHttpsHost() {
        assertEquals("https://public.api.bsky.app", XApiClient.validBskyEndpoint("https://public.api.bsky.app"))
        assertNull(XApiClient.validBskyEndpoint("http://public.api.bsky.app"))
        assertNull(XApiClient.validBskyEndpoint("https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed"))
        assertNull(XApiClient.validBskyEndpoint(""))
        assertNull(XApiClient.validBskyEndpoint(null))
    }

    @Test fun manifestWithFxAndBskyFieldsIsValidated() {
        // 与仓库 x_api_manifest.json 同构：fxHosts + bskyEndpoint 可选字段全部校验通过
        val body = """
            {
              "schema": 1,
              "bearer": "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA",
              "queryIds": {"userByScreenName": "sLVLhk0bGj3MVFEKTdax1w", "userTweets": "E3opETHurmVJflFsUBVuUQ"},
              "fxHosts": ["https://api.fxtwitter.com", "https://api-mirror.example.com"],
              "bskyEndpoint": "https://public.api.bsky.app"
            }
        """.trimIndent()
        val json = org.json.JSONObject(body)
        val hosts = XApiClient.validFxHosts(json.optJSONArray("fxHosts"))
        assertEquals(listOf("https://api.fxtwitter.com", "https://api-mirror.example.com"), hosts)
        assertEquals("https://public.api.bsky.app", XApiClient.validBskyEndpoint(json.optString("bskyEndpoint")))
    }

    @Test fun validWebdavPresetsAcceptsWellFormedOverrides() {
        val raw = org.json.JSONArray(
            """
            [
              {"id": "123pan", "serverUrlTemplate": "https://webdav-{账号ID}.pd1.123pan.cn/webdav"},
              {"id": "jianguoyun", "name": "坚果云", "description": "服务商口径更新后的说明文案"}
            ]
            """.trimIndent()
        )
        val out = checkNotNull(XApiClient.validWebdavPresets(raw)) { "合法覆盖集应通过" }
        assertEquals(2, out.size)
        assertEquals("123pan", out[0].id)
        assertEquals("https://webdav-{账号ID}.pd1.123pan.cn/webdav", out[0].serverUrlTemplate)
        assertNull(out[0].description)
        assertEquals("坚果云", out[1].name)
        assertNull(out[1].serverUrlTemplate)
    }

    @Test fun validWebdavPresetsRejectsMalformed() {
        val cases = listOf(
            """[{"id": "UPPER", "serverUrlTemplate": "https://x.com"}]""",   // id 大写
            """[{"id": "123pan", "serverUrlTemplate": "ftp://x.com"}]""",    // 非 http(s)
            """[{"id": "123pan", "serverUrlTemplate": "https://x.com  空格"}]""", // 含空白
            """[{"id": "123pan", "name": ""}]""",                            // name 显式空串
            """[{"no_id": true}]""",
            """[]""",
            """[{"id": "a"}, {"id": "b"}, {"id": "c"}, {"id": "d"}, {"id": "e"}, {"id": "f"}]""" // 超过 20 条的一半不对——此例 6 条合法
        )
        // 逐例校验（最后一个 6 条是合法的，排除在外）
        cases.dropLast(1).forEach { body ->
            try {
                XApiClient.validWebdavPresets(org.json.JSONArray(body))
                error("应拒绝: $body")
            } catch (expected: Exception) {
                assertTrue(expected is IllegalArgumentException || expected is IllegalStateException)
            }
        }
        // 6 条合法覆盖应通过
        assertEquals(
            6,
            XApiClient.validWebdavPresets(org.json.JSONArray(cases.last()))?.size
        )
    }

    @Test fun validAnnouncementsAcceptsWellFormed() {
        val raw = org.json.JSONArray(
            """
            [
              {"id": "e012-signature-note", "severity": "warn", "text": "v1.9.10 以下须卸载重装", "url": "https://github.com/Sdhop-let/Edqiu-Downloader/releases", "maxApp": 101},
              {"id": "maintenance-1", "text": "FXTwitter 官方实例故障中，已自动切换镜像", "once": false}
            ]
            """.trimIndent()
        )
        val out = checkNotNull(XApiClient.validAnnouncements(raw)) { "合法公告应通过" }
        assertEquals(2, out.size)
        assertEquals("warn", out[0].severity)
        assertEquals(101, out[0].maxApp)
        assertEquals(false, out[1].once)
        assertNull(out[1].url)
    }

    @Test fun validAnnouncementsRejectsMalformed() {
        val cases = listOf(
            """[{"text": "缺 id"}]""",
            """[{"id": "a", "text": ""}]""",                                     // 空 text
            """[{"id": "a", "text": "x", "severity": "critical"}]""",            // severity 白名单外
            """[{"id": "a", "text": "x", "url": "http://insecure.example.com"}]""", // 非 https
            """[{"id": "a", "text": "x", "minApp": -1}]""",                      // 负数版本
            """[]""",
            """[{"id": "a", "text": "x"}, {"id": "b", "text": "y"}]"""           // 2 条合法
        )
        cases.dropLast(1).forEach { body ->
            try {
                XApiClient.validAnnouncements(org.json.JSONArray(body))
                error("应拒绝: $body")
            } catch (expected: Exception) {
                assertTrue(expected is IllegalArgumentException || expected is IllegalStateException)
            }
        }
        assertEquals(2, XApiClient.validAnnouncements(org.json.JSONArray(cases.last()))?.size)
    }
}
