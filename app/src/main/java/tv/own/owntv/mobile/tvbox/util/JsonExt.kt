package tv.own.owntv.mobile.tvbox.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 构造 JsonPrimitive 的小工具（避免各处重复写 JsonPrimitive(...)） */
fun toPrimitive(value: String): JsonPrimitive = JsonPrimitive(value)

fun toPrimitive(value: Int): JsonPrimitive = JsonPrimitive(value)

fun toPrimitive(value: Long): JsonPrimitive = JsonPrimitive(value)

fun toPrimitive(value: Boolean): JsonPrimitive = JsonPrimitive(value)

/**
 * JsonObject → 请求头 Map；非字符串值统一 toString
 *
 * ## 为什么嵌套对象必须丢弃（真机踩过的坑）
 * 源返回的 header 里可能混进**嵌套 JSON 对象**（如 `{"ua":{"name":..,"ver":..}}`）。
 * 旧实现对非 primitive 值走 `v.toString()`，于是产生了
 * **key 是整个 JSON 文本、value 还是 JSON 文本**的假header：
 * ```
 * {"User-Agent":{"Name":"okhttp","Version":"4.12"}, "User-Agent":"xxx", "Referer":"..."}
 * ↓toHeaderMap()
 * key = "{\"User-Agent\":{\"Name\"..." , value = 同 一段 JSON
 * ```
 * 这种垃圾请求头发给服务器，轻则被拒（**实测拿到 HTTP 400**），重则连接被挂。
 * 请求头只该是"扁平的字符串键值对"，遇到对象/数组一律丢掉。
 */
fun JsonObject.toHeaderMap(): Map<String, String> =
    mapNotNull { (k, v) ->
        when (v) {
            is JsonNull -> null
            is JsonPrimitive -> v.content.takeIf { it.isNotBlank() }?.let { k to it }
            // 对象/数组不是合法请求头值，直接丢弃
            else -> null
        }
    }.toMap()

/** Map → JsonObject */
fun Map<String, String>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, v) -> JsonPrimitive(v) })

/**
 * RFC 7230 的 token 字符集（tchar）：请求头名只允许这些字符。
 *
 * 注意 **不含** `{ } " : 空格 ( )` —— 正是畸形头的常见来源。
 */
private val HTTP_TOKEN: Regex = Regex("[!#\$%&'*+\\-.^_`|~0-9A-Za-z]+")

/**
 * 出口闸门：只保留"能真的发出去"的请求头。
 *
 * ## 为什么必须有这一道
 * 畸形 header 名（含 `{` `"` 等）**过得了 OkHttp 的检查** ——
 * OkHttp 只校验字符落在 0x21~0x7E，这些字符全部合法 ——
 * 然后被服务端按 RFC 7230 拒绝，实测 Tomcat 直接回 **HTTP 400**。
 *
 * 与其在每一处解析逻辑里小心翼翼不产出畸形键，不如在出口统一过滤：
 * 少发一个头最坏是防盗链失败（可重试/换线路），发出畸形头是必然 400（无任何补救）。
 */
fun Map<String, String>.legalHeaders(): Map<String, String> =
    entries.mapNotNull { (k, v) ->
        val name = k.trim()
        val value = v.trim()
        if (!HTTP_TOKEN.matches(name)) return@mapNotNull null
        // 值里的 CR/LF 是 header 注入（CRLF injection），同样丢弃
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return@mapNotNull null
        name to value
    }.toMap()

/**
 * HTML 标签清洗：CMS 源的 vod_content 普遍是 HTML 片段，
 * Compose 的 Text 不渲染 HTML，需转纯文本。
 */
object StripHtml {

    fun strip(html: String?): String? {
        if (html.isNullOrBlank()) return html
        var s: String = html
        // script/style 整段剔除
        s = SCRIPT_STYLE.replace(s, " ")
        // 换行类标签转空格，避免句子粘连
        s = BLOCK_TAGS.replace(s, " ")
        s = BR.replace(s, " ")
        s = P_CLOSE.replace(s, " ")
        // 去掉所有剩余标签
        s = TAG.replace(s, "")
        s = TextUtil.decode(s).orEmpty()
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex("\\n{2,}"), "\n")
        s = s.replace(Regex("\\s*\\n\\s*"), "\n")
        return s.trim().takeIf { it.isNotBlank() }
    }

    private val SCRIPT_STYLE = Regex(
        "<(script|style)[^>]*>.*?</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK_TAGS = Regex(
        "</?(div|p|ul|ol|li|tr|td|th|table|h[1-6]|section|article|blockquote)[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("<[^>]+>")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val P_CLOSE = Regex("</p>", RegexOption.IGNORE_CASE)
}
