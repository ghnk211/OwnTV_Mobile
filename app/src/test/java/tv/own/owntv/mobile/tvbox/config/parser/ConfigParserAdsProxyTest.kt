package tv.own.owntv.mobile.tvbox.config.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserAdsProxyTest {

    @Test
    fun `解析顶层 ads 与 rules 广告规则`() {
        val json = """
        {
          "sites": [ { "key": "k", "api": "https://a.com" } ],
          "ads": ["doubleclick.net", "https://cdn.x.com/ads/a.js"],
          "rules": [
            {
              "name": "r",
              "hosts": "*",
              "rule": [".m3u8"],
              "ads": ["ads.example.com"],
              "regex": ["#EXT-X-DISCONTINUITY", ".m3u8"]
            }
          ]
        }
        """.trimIndent()

        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val cfg = r.config!!
        assertEquals(2, cfg.ads.size)
        assertEquals("doubleclick.net", cfg.ads[0])

        val rule = cfg.rules.single()
        assertEquals(listOf(".m3u8"), rule.rule)
        assertTrue(rule.ads.contains("ads.example.com"))
        // 只有 m3u8 播放列表规则进入 ads；普通嗅探 regex 不改写 rule
        assertTrue(rule.ads.contains("#EXT-X-DISCONTINUITY"))
        assertEquals(listOf(".m3u8"), rule.rule)
    }

    @Test
    fun `解析 proxy 的字符串与数组写法`() {
        val json = """
        {
          "sites": [ { "key": "k", "api": "https://a.com" } ],
          "proxy": [
            { "name": "p1", "hosts": "api.a.com", "urls": "http://user:pass@proxy.local:8080" },
            { "name": "p2", "hosts": ["x.com", "y.com"],
              "urls": ["socks5://proxy2.local:1080", "http://proxy3.local:3128"] }
          ]
        }
        """.trimIndent()

        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val proxy = r.config!!.proxy
        assertEquals(2, proxy.size)
        assertEquals(listOf("api.a.com"), proxy[0].hosts)
        assertEquals(listOf("http://user:pass@proxy.local:8080"), proxy[0].urls)
        assertEquals(listOf("x.com", "y.com"), proxy[1].hosts)
        assertEquals(2, proxy[1].urls.size)
    }

    @Test
    fun `非法 proxy 只丢弃该条并记 warning`() {
        val json = """
        {
          "sites": [ { "key": "k", "api": "https://a.com" } ],
          "proxy": [
            { "name": "missing-url", "hosts": "a.com" },
            { "name": "ok", "hosts": "b.com", "urls": "http://p:8080" }
          ]
        }
        """.trimIndent()

        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        assertEquals(1, r.config!!.proxy.size)
        assertTrue(r.warnings.any { it.contains("proxy") })
    }

    @Test
    fun `缺少 ads 与 proxy 时保持默认空列表`() {
        val r = ConfigParser.parse("""{ "sites": [ { "key": "k", "api": "https://a.com" } ] }""")
        assertTrue(r.isSuccess)
        assertNotNull(r.config)
        assertTrue(r.config!!.ads.isEmpty())
        assertTrue(r.config!!.proxy.isEmpty())
    }
}
