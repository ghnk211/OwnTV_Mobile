package tv.own.owntv.mobile.tvbox.engine.jar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jar 源 header 解析的回归测试。
 *
 * ## 起因（真机实测 2026-10-07，源「热播┃快速」= type=3 jar，站Rbys）
 * 落盘日志显示发给服务器的请求头是：
 * ```
 * {"User-Agent"="Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3...) Safari/604.1"}
 * ```
 * ——**整个嵌套 JSON 被当成了 header 的名字**（Gson `Map.toString()` 的格式）。
 * 服务器收到这种畸形头直接回 **HTTP 400**，于是表现为「源站返回异常状态（HTTP 错误）」。
 *
 * 对照：`curl` 请求同一个地址（不带这个畸形头）返回 **200**。
 * 说明地址本身没问题，**问题在请求头**。
 *
 * ## 真实数据形态
 * 源返回的 header 是双层嵌套：
 * ```json
 * {"User-Agent": {"User-Agent": "Mozilla/5.0 (iPhone...) Safari/604.1"}}
 * ```
 */
class JarHeaderParseTest {

    private fun parseHeader(raw: Any): JsonObject? =
        JarResultParser.parseHeaderForTest(raw)

    @Test
    fun nestedHeaderIsUnwrappedNotStringified() {
        // 核心回归：不能把整个 JSON 变成 header 名
        val h = parseHeader(
            mapOf(
                "User-Agent" to mapOf(
                    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) Safari/604.1"
                )
            )
        )
        assertEquals("Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) Safari/604.1", h?.get("User-Agent")?.jsonPrimitive?.content)
    }

    @Test
    fun noKeyEverLooksLikeJson() {
        val h = parseHeader(
            mapOf("User-Agent" to mapOf("User-Agent" to "UA-string"))
        )
        h?.keys?.forEach { k ->
            assertFalse("header 名不该是 JSON 文本：$k", k.contains("{"))
            assertFalse("header 名不该是 JSON 文本：$k", k.contains("="))
        }
    }

    @Test
    fun plainHeaderStillWorks() {
        val h = parseHeader(
            mapOf("User-Agent" to "okhttp/4.12", "Referer" to "https://a.com/")
        )
        assertEquals(2, h?.size)
        assertEquals("okhttp/4.12", h?.get("User-Agent")?.jsonPrimitive?.content)
        assertEquals("https://a.com/", h?.get("Referer")?.jsonPrimitive?.content)
    }

    @Test
    fun newlineSeparatedStringStillWorks() {
        val h = parseHeader("User-Agent: okhttp/4.12\nReferer: https://a.com/")
        assertEquals(2, h?.size)
        assertEquals("okhttp/4.12", h?.get("User-Agent")?.jsonPrimitive?.content)
    }

    @Test
    fun deeplyNestedValueYieldsNothingFake() {
        // 再下一层还是对象：宁可不发，也不能造出JSON 文本当 header 名
        val h = parseHeader(
            mapOf("X" to mapOf("Y" to mapOf("Z" to "deep")))
        )
        // 不该出现任何以 { 开头的 key
        h?.keys?.forEach { k ->
            assertFalse(k.trimStart().startsWith("{"))
        }
    }

    @Test
    fun numericValueIsKeptAsString() {
        val h = parseHeader(mapOf("X-Version" to 4))
        assertEquals("4", h?.get("X-Version")?.jsonPrimitive?.content)
    }

    @Test
    fun nullValueIsDropped() {
        val h = parseHeader(mapOf("A" to null, "B" to "ok"))
        assertNull(h?.get("A"))
        assertEquals("ok", h?.get("B")?.jsonPrimitive?.content)
    }

    @Test
    fun emptyHeaderYieldsNull() {
        assertNull(parseHeader(emptyMap<String, Any>()))
    }

    // ---------------- header 是「JSON 字符串」形态 ----------------

    /**
     * ★ 真机根因（2026-10-07，源「热播┃快速」站 Rbys，type=3 jar）。
     *
     * jar 把 header 以**字符串里的 JSON** 返回：
     * `"header":"{\"User-Agent\":\"Mozilla/5.0 (iPhone...)\"}"`。
     * 旧实现的 String 分支一律按 `k:v` 切行，于是从第一个冒号处劈开：
     * key = `{"User-Agent"`、value = `"Mozilla/5.0 (...)...Safari/604.1"}`。
     * 这个畸形名被 OkHttp 原样发出 → Tomcat 按 RFC 7230 tchar 拒绝 → **HTTP 400**。
     *
     * 注意：**修 Map 嵌套分支救不了这一条** —— 这批数据走的是 String 分支。
     */
    private val IPHONE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"

    @Test
    fun jsonStringHeaderIsParsedNotSplitByColon() {
        val raw = "{\"User-Agent\":\"$IPHONE_UA\"}"
        val h = parseHeader(raw)
        assertEquals(IPHONE_UA, h?.get("User-Agent")?.jsonPrimitive?.content)
    }

    @Test
    fun jsonStringHeaderNeverProducesFakeName() {
        val h = parseHeader("{\"User-Agent\":\"$IPHONE_UA\"}")
        assertEquals(1, h?.size)
        h?.keys?.forEach { k ->
            assertFalse("header 名不该含 {：$k", k.contains("{"))
            assertFalse("header 名不该含 \"：$k", k.contains("\""))
        }
    }

    @Test
    fun jsonStringHeaderWithMultipleKeys() {
        val h = parseHeader("""{"User-Agent":"ua-x","Referer":"https://a.com/","Cookie":"k=v"}""")
        assertEquals(3, h?.size)
        assertEquals("ua-x", h?.get("User-Agent")?.jsonPrimitive?.content)
        assertEquals("https://a.com/", h?.get("Referer")?.jsonPrimitive?.content)
        assertEquals("k=v", h?.get("Cookie")?.jsonPrimitive?.content)
    }

    @Test
    fun brokenJsonStringYieldsNothingRatherThanFakeHeader() {
        // 解析不出来就宁可不发，绝不退回按冒号切
        val h = parseHeader("{\"User-Agent\":\"ua-x\"")
        assertTrue(h == null || h.isEmpty())
    }

    @Test
    fun colonSeparatedStringStillWorksAfterJsonCheck() {
        // 不像 JSON 的字符串仍走 k:v 分支
        val h = parseHeader("User-Agent: okhttp/4.12\nX-Token: abc:def")
        assertEquals(2, h?.size)
        assertEquals("okhttp/4.12", h?.get("User-Agent")?.jsonPrimitive?.content)
        // 值里的冒号不能被吃掉（只切第一个）
        assertEquals("abc:def", h?.get("X-Token")?.jsonPrimitive?.content)
    }
}
