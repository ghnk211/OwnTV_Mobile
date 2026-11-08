package tv.own.owntv.mobile.tvbox.config.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3 真机缺陷修复包：配置 `doh` 与 `rules[].regex` 的解析。
 *
 * 这两项此前被解析器整个丢掉，表现为设置页 DoH 永远 off、诊断里
 * `excluded=0/adBlockedCount=0`。这里固化"结构解析 + 分类"的口径：
 * - `doh` 只接受 http(s) 端点，坏条目跳过但不阻断导入；
 * - `regex` 原样保留给嗅探，**播放列表类正则同时并入 ads** 供 M3U8 净化。
 */
class ConfigParserDohRegexTest {

    private val minimalSite = """{ "sites": [ { "key": "k", "api": "https://a.com" } ] }"""

    @Test
    fun `解析 doh 对象与纯 URL 字符串`() {
        val json = """
        {
          "sites": [ { "key": "k", "api": "https://a.com" } ],
          "doh": [
            { "name": "Google", "url": "https://dns.google/dns-query",
              "ips": ["8.8.8.8", "8.8.4.4"] },
            "https://doh.pub/dns-query",
            { "name": "没有地址" },
            { "name": "非 http", "url": "ftp://dns.example.com/query" }
          ]
        }
        """.trimIndent()

        val cfg = ConfigParser.parse(json).config!!
        assertEquals("只保留 2 个合法节点", 2, cfg.doh.size)
        assertEquals("Google", cfg.doh[0].displayName)
        assertEquals("https://dns.google/dns-query", cfg.doh[0].url)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), cfg.doh[0].ips)
        assertEquals("纯 URL 形态没有 name，回落到 url", "https://doh.pub/dns-query", cfg.doh[1].displayName)
    }

    @Test
    fun `regex 原样保留且播放列表规则并入 ads`() {
        val json = """
        {
          "sites": [ { "key": "k", "api": "https://a.com" } ],
          "rules": [
            { "name": "hs", "hosts": ["huoshan.com"], "regex": ["item_id="] },
            { "name": "kk", "hosts": ["kuaikan"],
              "regex": ["5", "20.123", "#EXT-X-DISCONTINUITY"],
              "ads": ["kuaikan-extra"] }
          ]
        }
        """.trimIndent()

        val cfg = ConfigParser.parse(json).config!!
        val hs = cfg.rules.first { it.name == "hs" }
        val kk = cfg.rules.first { it.name == "kk" }

        assertEquals("嗅探正则必须原样进入 SniffRule", listOf("item_id="), hs.regex)
        assertTrue("URL 片段不是播放列表规则，不能进 ads", hs.ads.isEmpty())

        assertEquals(3, kk.regex.size)
        assertEquals("数字与 #EXT-X 规则 + 原有 ads 都要保留", 4, kk.ads.size)
        assertTrue(kk.ads.contains("kuaikan-extra"))
        assertTrue(kk.ads.contains("5"))
        assertTrue(kk.ads.contains("#EXT-X-DISCONTINUITY"))
        assertTrue("20.123 是分片时长规则", kk.ads.contains("20.123"))
    }

    @Test
    fun `没有规则的配置仍可正常导入`() {
        val r = ConfigParser.parse(minimalSite)
        assertTrue(r.isSuccess)
        assertTrue(r.config!!.doh.isEmpty())
        assertTrue(r.config!!.rules.isEmpty())
    }
}
