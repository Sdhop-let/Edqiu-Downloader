package com.ed.edqiu.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * 第三方解析 API 配置（2026-09-15 P0-1 解析链三层冗余第三层）。
 *
 * endpoint 留空 = 关闭；填入合法 http(s) 端点即启用（Key 视服务商可留空）。
 * 安全约定：接入任何付费 API 前必须人工甄别无广告 SDK / 跟踪器注入（项目铁律）。
 */
class ThirdPartyApiPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("third_party_api", Context.MODE_PRIVATE)

    fun getEndpoint(): String = prefs.getString(KEY_ENDPOINT, "") ?: ""

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    /** 是否已配置可用端点（须以 http(s) 开头；{id} 占位可缺省）。 */
    val isConfigured: Boolean
        get() = getEndpoint().replace("{id}", "1").startsWith("http")

    fun save(endpoint: String, apiKey: String) {
        prefs.edit()
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    companion object {
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_API_KEY = "api_key"
    }
}
