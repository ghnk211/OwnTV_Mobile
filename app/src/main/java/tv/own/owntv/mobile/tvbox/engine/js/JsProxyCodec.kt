package tv.own.owntv.mobile.tvbox.engine.js

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** `/proxy` 一次代理的结果（对齐参考项目 `JsSpider.proxyLocal` 的经典 drpy 返回） */
data class JsProxyResult(
    val code: Int,
    val contentType: String,
    val headers: Map<String, String>,
    val bytes: ByteArray? = null,
    val text: String? = null,
) {
    fun bodyBytes(): ByteArray = bytes
        ?: text?.toByteArray()
        ?: ByteArray(0)
}

/**
 * JS `proxy()` 返回值 → [JsProxyResult] 的解析。
 *
 * 两代形态都要认：
 * - 经典 drpy：`[code, contentType, content, headers, base64Flag]`
 * - cat 形态：`{code, contentType, buffer|content, headers}`
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsProxyCodec.kt`。
 */
internal object JsProxyCodec {

    @OptIn(ExperimentalEncodingApi::class)
    fun toResult(raw: Any?): JsProxyResult? {
        if (raw is Map<*, *>) return fromMap(raw)
        val list = raw as? List<*> ?: return null
        val code = (list.getOrNull(0) as? Number)?.toInt() ?: 200
        val contentType = list.getOrNull(1)?.toString() ?: "application/octet-stream"
        val body = list.getOrNull(2)
        val headers = headerMap(list.getOrNull(3))
        val base64 = list.getOrNull(4) == true || list.getOrNull(4)?.toString() == "true"
        val bytes = when {
            base64 && body is String -> runCatching { Base64.Default.decode(body) }.getOrNull()
            body is String -> body.toByteArray()
            else -> null
        }
        return JsProxyResult(code, contentType, headers, bytes, body as? String)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun fromMap(map: Map<*, *>): JsProxyResult? {
        val code = (map["code"] as? Number)?.toInt() ?: 200
        val contentType = map["contentType"]?.toString() ?: "application/octet-stream"
        val headers = headerMap(map["headers"])
        val content = map["content"]?.toString()
        val buffer = map["buffer"]?.toString()
        val bytes = buffer?.takeIf { it.isNotEmpty() && content == null }?.let {
            runCatching { Base64.Default.decode(it) }.getOrNull()
        }
        return JsProxyResult(code, contentType, headers, bytes, content)
    }

    private fun headerMap(raw: Any?): Map<String, String> =
        (raw as? Map<*, *>)?.mapNotNull { (k, v) ->
            if (k == null) null else k.toString() to v?.toString().orEmpty()
        }?.toMap() ?: emptyMap()
}