package com.github.catvod.net

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 桩：远程过程调用（部分 jar 用它请求其他源的接口）。
 *
 * M2 只提供最小可用形态：把请求交给宿主共享 OkHttp，
 * 保证与主链路同一套 UA/超时/冷却策略。
 */
object RPC {

    @JvmStatic
    @JvmOverloads
    fun get(url: String, header: String? = null): OkResult = OkHttp.get(url, header)

    @JvmStatic
    @JvmOverloads
    fun post(url: String, body: String?, header: String? = null): OkResult =
        OkHttp.post(url, body, header)

    /**
     * 解析 JSON 文本为对象。jar 常把 RPC 返回值强转成 bean，
     * 这里提供与生态一致的 Gson 解析路径。
     */
    @JvmStatic
    @JvmOverloads
    fun json(body: String?, clazz: Class<*>): Any? {
        if (body.isNullOrBlank()) return null
        return runCatching { Gson().fromJson(body, clazz) }.getOrNull()
    }

    @JvmStatic
    fun jsonObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            JsonParser.parseString(body)?.takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
    }
}
