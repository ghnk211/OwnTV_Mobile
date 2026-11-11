package tv.own.owntv.mobile.tvbox.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 方案 §11：extKey 的 flag/episodeId/index JSON 往返一致；空值安全。 */
class TvboxExtKeyTest {

    @Test
    fun `round trip keeps flag episodeId and index`() {
        val json = TvboxExtKey.encode("线路A", "https://vod.example/play/12345-1-2.html", 7)
        assertEquals(TvboxExtKey.Identity("线路A", "https://vod.example/play/12345-1-2.html", 7), TvboxExtKey.decode(json))
    }

    @Test
    fun `round trip survives special characters in episodeId`() {
        // episodeId 是源给的待解析标识：引号、反斜杠、换行、中文、$、# 都可能出现，
        // 且会被备份再套一层 JSON —— 编码必须靠 JSON 转义而不是手拼分隔符。
        val episodeId = "magnet:?xt=urn:btih:\"ABC\\DEF\"\n第02集\$token#frag 中文"
        val json = TvboxExtKey.encode("flag\"\\\n", episodeId, 0)
        assertEquals(TvboxExtKey.Identity("flag\"\\\n", episodeId, 0), TvboxExtKey.decode(json))
    }

    @Test
    fun `index zero is valid`() {
        assertEquals(0, TvboxExtKey.decode(TvboxExtKey.encode("f", "e", 0))?.index)
    }

    @Test
    fun `null blank and malformed decode to null`() {
        assertNull(TvboxExtKey.decode(null))
        assertNull(TvboxExtKey.decode(""))
        assertNull(TvboxExtKey.decode("   "))
        assertNull(TvboxExtKey.decode("{not json"))
        assertNull(TvboxExtKey.decode("[1,2,3]"))
        assertNull(TvboxExtKey.decode("42"))
    }

    @Test
    fun `missing or negative index decodes to null`() {
        assertNull(TvboxExtKey.decode("""{"flag":"f","episodeId":"e"}"""))
        assertNull(TvboxExtKey.decode("""{"flag":"f","episodeId":"e","index":-1}"""))
        assertNull(TvboxExtKey.decode("""{"index":-5}"""))
    }

    @Test
    fun `missing flag and episodeId fall back to empty strings`() {
        val id = TvboxExtKey.decode("""{"index":3}""")
        assertEquals(TvboxExtKey.Identity("", "", 3), id)
    }

    @Test
    fun `unknown keys are ignored`() {
        val id = TvboxExtKey.decode("""{"flag":"f","episodeId":"e","index":2,"future":"x"}""")
        assertEquals(TvboxExtKey.Identity("f", "e", 2), id)
    }
}