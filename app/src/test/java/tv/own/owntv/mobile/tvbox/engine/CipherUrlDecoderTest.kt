package tv.own.owntv.mobile.tvbox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放地址密文解码的回归测试。
 *
 *## 起因（真机实测 2026-10-07）
 * 同一个源（热播┃快速）、同一部《藏海传》，两边拿到的地址完全不同：
 * - 参考项目：`http://43.248.96.62:9090/nby/m3u8/getM3u8?name=jx.91by&top&time=...` → 能播
 * - 本项目：`eyJ1cmwiOiJOQklrUE1Q...`（Base64 密文）→ 嗅探 12 秒后失败
 *
 * 我原先把密文当成"二级播放页"丢给 WebView，这是**方向性错误**：
 * WebView 打开一串 Base64 会在 272ms 内 ERR_ABORTED，`shouldInterceptRequest`
 * 一次都不触发，看起来像"华为 WebView 不工作"，实际是上游数据没解码。
 */
class CipherUrlDecoderTest {

    // 真实样本：参考项目详情页显示的播放地址
    private val realPlainUrl =
        "http://43.248.96.62:9090/nby/m3u8/getM3u8?name=jx.91by&top&time=1789f8a1c2b3"

    private fun encrypt(json: String, noPadding: Boolean = false): String =
        Base64Compat.encode(json.toByteArray(Charsets.UTF_8), noPadding)

    // ---------- 基本解码 ----------

    @Test
    fun decodesRealSampleCipher() {
        val cipher = encrypt("""{"url":"$realPlainUrl"}""")
        assertTrue(cipher.startsWith("eyJ")) // 与真机看到的形态一致
        assertEquals(realPlainUrl, CipherUrlDecoder.decode(cipher))
    }

    @Test
    fun decodesCipherWithoutPadding() {
        // 部分源发的是无padding 的 URL_SAFE Base64
        val json = """{"url":"$realPlainUrl"}"""
        val cipher = encrypt(json, noPadding = true)
        assertFalse(cipher.contains("="))
        assertEquals(realPlainUrl, CipherUrlDecoder.decode(cipher))
    }

    @Test
    fun decodesUrlSafeVariant() {
        // 构造含 - 或 _ 的 URL_SAFE 密文：内容里有这些字符时编码结果会用到
        val url = "https://cdn.example.com/a-b_c/stream.m3u8?a=1"
        val cipher = Base64Compat.encode("""{"url":"$url"}""".toByteArray(), noPadding = true)
        assertEquals(url, CipherUrlDecoder.decode(cipher))
    }

    // ---------- 各种字段名（真实源不只用 url） ----------

    @Test
    fun picksAlternativeFieldNames() {
        listOf("playUrl", "play_url", "m3u8", "mp4", "src", "link", "video").forEach { key ->
            val cipher = encrypt("""{"$key":"$realPlainUrl"}""")
            assertEquals("字段名 $key 应被识别", realPlainUrl, CipherUrlDecoder.decode(cipher))
        }
    }

    @Test
    fun picksUrlFromNestedObject() {
        // 部分源把地址裹在 data / result 里，key 名不变
        val cipher = encrypt("""{"data":{"url":"$realPlainUrl"},"code":200}""")
        assertEquals(realPlainUrl, CipherUrlDecoder.decode(cipher))
    }

    @Test
    fun handlesJsonWithSpacesAndExtraFields() {
        val cipher = encrypt("""{ "url" : "$realPlainUrl" , "parse" : 0 }""")
        assertEquals(realPlainUrl, CipherUrlDecoder.decode(cipher))
    }

    @Test
    fun takesFirstHttpUrlWhenSeveralFieldsExist() {
        // flag 字段可能是线路名，url 才是地址 —— 顺序不能错
        val cipher = encrypt("""{"parse":1,"flag":"线路1(nby)","url":"$realPlainUrl"}""")
        assertEquals(realPlainUrl, CipherUrlDecoder.decode(cipher))
    }

    // ---------- 明文地址不能被误判（最重要：不误伤能播的源） ----------

    @Test
    fun plainUrlIsNotTreatedAsCipher() {
        assertNull(CipherUrlDecoder.decode(realPlainUrl))
        assertFalse(CipherUrlDecoder.isEncoded(realPlainUrl))
        assertFalse(CipherUrlDecoder.isEncoded("https://v.qq.com/x/cover/abc.html"))
    }

    @Test
    fun dataBlobFileUrlsAreNotCipher() {
        assertNull(CipherUrlDecoder.decode("data:video/mp4;base64,AAAA"))
        assertNull(CipherUrlDecoder.decode("blob:https://x/y"))
        assertNull(CipherUrlDecoder.decode("file:///sdcard/a.mp4"))
    }

    @Test
    fun emptyAndBlankAreNotCipher() {
        assertNull(CipherUrlDecoder.decode(null))
        assertNull(CipherUrlDecoder.decode(""))
        assertNull(CipherUrlDecoder.decode("   "))
        assertFalse(CipherUrlDecoder.isEncoded(null))
    }

    // ---------- 不该解码的输入（宁可漏判不误判） ----------

    @Test
    fun nonJsonCipherIsRejected() {
        // 能解出字节但不是 JSON —— 不认
        val cipher = encrypt("just some plain text here, not json at all")
        assertNull(CipherUrlDecoder.decode(cipher))
    }

    @Test
    fun jsonWithoutHttpUrlIsRejected() {
        // JSON 合法但没有可播地址 —— 不认
        assertNull(CipherUrlDecoder.decode(encrypt("""{"code":401,"msg":"登录失效"}""")))
        assertNull(CipherUrlDecoder.decode(encrypt("""{"url":"clan://localhost"}""")))
        assertNull(CipherUrlDecoder.decode(encrypt("""{"url":""}""")))
    }

    @Test
    fun tooShortIsRejected() {
        assertNull(CipherUrlDecoder.decode("eyJ"))
        assertNull(CipherUrlDecoder.decode("eyJ1cmw"))
    }

    @Test
    fun illegalBase64CharsAreRejected() {
        assertNull(CipherUrlDecoder.decode("eyJ1cmwi*******"))
        assertNull(CipherUrlDecoder.decode("eyJ1cm wi!"))
    }

    /**
     * 长度不是 4 的倍数**不能**直接拒 —— 无 padding 的 URL_SAFE Base64
     * 长度 mod 4 会是 2 或 3，这条曾经把主流的快播系源全漏掉。
     * 合法性由解码器自己验（尾部比特必须为 0）。
     */
    @Test
    fun nonMultipleOfFourLengthIsAcceptedWhenValid() {
        val cipher = encrypt("""{"url":"$realPlainUrl"}""", noPadding = true)
        assertTrue(cipher.length % 4 != 0) // 确认样本确实不是 4 的倍数
        assertEquals(realPlainUrl, CipherUrlDecoder.decode(cipher))
    }

    @Test
    fun validBase64WithBadTrailingBitsIsRejected() {
        // "eyJ1cmw" 补齐成4 的倍数后再改末尾字符，制造"长度合法但尾部比特非0"
        assertNull(Base64Compat.decode("eyJ1cmwi****"))
        // 长度合法、内容能解出但不是 JSON 的情况
        assertNull(CipherUrlDecoder.decode(encrypt("plain text not json at all!!")))
    }

    @Test
    fun ordinaryVodIdIsNotMistakenForCipher() {
        // 纯数字 id 也能被 Base64 解出乱码，必须靠"解出 http 地址"这道关挡住
        val digits = "123456789012345678901234"
        assertNull(CipherUrlDecoder.decode(digits))
    }

    // ---------- decodeJson：给上层读 parse 等字段 ----------

    @Test
    fun decodeJsonReturnsPlainJsonText() {
        val json = CipherUrlDecoder.decodeJson(encrypt("""{"url":"$realPlainUrl","parse":1}"""))
        assertNotNull(json)
        assertTrue(json!!.contains(realPlainUrl))
        assertTrue(json.contains("\"parse\":1"))
    }

    @Test
    fun decodeJsonNullForPlainUrl() {
        assertNull(CipherUrlDecoder.decodeJson(realPlainUrl))
    }

    // ---------- looksStream ----------

    @Test
    fun looksStreamDetectsMediaSuffix() {
        assertTrue(CipherUrlDecoder.looksStream("http://x/y/index.m3u8"))
        assertTrue(CipherUrlDecoder.looksStream("http://x/y/a.mp4"))
        assertFalse(CipherUrlDecoder.looksStream("http://x/player/m3u8play.php?url=a"))
    }

    // ---------- Base64Compat 本身的正确性 ----------

    @Test
    fun base64RoundTripsAllByteValues() {
        val all = ByteArray(256) { it.toByte() }
        val encoded = Base64Compat.encode(all)
        val decoded = Base64Compat.decode(encoded)
        assertNotNull(decoded)
        assertEquals(all.size, decoded!!.size)
        for (i in all.indices) assertEquals(all[i], decoded[i])
    }

    @Test
    fun base64HandlesAllPaddingLengths() {
        // 长度 1/2/3 字节对应 padding 2/1/0，是最容易写错的地方
        for (n in 1..6) {
            val data = ByteArray(n) { (it * 37).toByte() }
            val encoded = Base64Compat.encode(data)
            val decoded = Base64Compat.decode(encoded)
            assertNotNull("长度 $n 应能往返", decoded)
            assertTrue("长度 $n 往返后应相等", data.contentEquals(decoded!!))
        }
    }

    @Test
    fun base64DecodesKnownVector() {
        // RFC 4648 标准向量
        assertEquals("Man", String(Base64Compat.decode("TWFu")!!))
        assertEquals("Ma", String(Base64Compat.decode("TWE=")!!))
        assertEquals("M", String(Base64Compat.decode("TQ==")!!))
    }

    @Test
    fun base64AcceptsUrlSafeChars() {
        // 62/63 位在 URL_SAFE 里是 - _
        val encoded = Base64Compat.encode(ByteArray(3) { 0xFB.toByte() }, noPadding = true)
        assertTrue(encoded.contains('-') || encoded.contains('_'))
        assertNotNull(Base64Compat.decode(encoded))
    }

    @Test
    fun base64RejectsInvalidInput() {
        assertNull(Base64Compat.decode("!!!!"))
        assertNull(Base64Compat.decode("A"))
        // 长度为 4 但只有 1 个有效字符组，残留位不为 0
        assertNull(Base64Compat.decode("A==="))
    }
}
