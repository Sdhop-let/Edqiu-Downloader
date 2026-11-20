package com.ed.edqiu.data.xapi

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
}
