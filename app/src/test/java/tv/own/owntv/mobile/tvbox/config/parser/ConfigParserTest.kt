package tv.own.owntv.mobile.tvbox.config.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析容错测试（方案 14：真实配置样本回归 + 畸形 JSON 容错）。
 */
class ConfigParserTest {

    @Test
    fun `解析标准配置`() {
        val json = """
        {
          "spider": "https://example.com/spider.jar;md5;abc",
          "sites": [
            { "key": "cms_json", "name": "苹果CMS", "type": 0,
              "api": "https://api.x.com/api.php/vod", "searchable": 1 },
            { "key": "cms_xml", "name": "XML源", "type": 1,
              "api": "https://api.y.com/api.php/provide/vod" }
          ],
          "flags": ["youku", "m3u8"],
          "rules": [ { "name": "默认嗅探", "hosts": "*", "rule": [".m3u8", ".mp4"] } ]
        }
        """.trimIndent()

        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val cfg = r.config!!
        assertEquals(2, cfg.sites.size)
        assertEquals(2, cfg.supportedSites().size)
        assertEquals(2, cfg.flags.size)
        assertEquals(1, cfg.rules.size)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `单条 site 非法只丢该条`() {
        val json = """
        {
          "sites": [
            { "key": "ok1", "name": "正常", "type": 0, "api": "https://a.com" },
            { "name": "缺key", "type": 0, "api": "https://b.com" },
            { "key": "ok2", "type": 0, "api": "https://c.com" }
          ]
        }
        """.trimIndent()

        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        assertEquals(2, r.config!!.sites.size)
        assertTrue(r.warnings.any { it.contains("缺少 key/api") })
    }

    @Test
    fun `字段缺失取默认值`() {
        val json = """{ "sites": [ { "key": "k", "api": "https://a.com" } ] }"""
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val site = r.config!!.sites.first()
        assertEquals("k", site.name)   // name 缺省用 key
        assertEquals(0, site.type)      // type 缺省 0
        assertEquals(1, site.searchable)
    }

    @Test
    fun `字段类型混乱时容错`() {
        // 数字写成字符串、布尔写成 0/1
        val json = """
        { "sites": [ { "key": "k", "name": "n", "type": "0",
                       "api": "https://a.com", "searchable": "1", "filterable": true } ] }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val s = r.config!!.sites.first()
        assertEquals(0, s.type)
        assertEquals(1, s.searchable)
        assertEquals(1, s.filterable)
        assertTrue(s.canFilter)
    }

    @Test
    fun `未知字段被忽略`() {
        val json = """
        { "sites": [ { "key": "k", "api": "https://a.com",
                       "someNewField": {"nested": [1,2,3]}, "another": "x" } ],
          "unknownTop": 123 }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
    }

    @Test
    fun `sites 为空返回错误`() {
        val r = ConfigParser.parse("""{ "sites": [] }""")
        assertEquals(false, r.isSuccess)
        assertNotNull(r.error)
    }

    @Test
    fun `非法 JSON 返回错误不崩溃`() {
        val r = ConfigParser.parse("{ this is not json ")
        assertEquals(false, r.isSuccess)
        assertNotNull(r.error)
    }

    @Test
    fun `空内容返回错误`() {
        val r = ConfigParser.parse("")
        assertEquals(false, r.isSuccess)
    }

    @Test
    fun `未知 type 保留但灰显`() {
        val json = """
        { "sites": [
            { "key": "a", "api": "https://a.com", "type": 0 },
            { "key": "b", "api": "csp_B", "type": 3 },
            { "key": "c", "api": "https://c.com", "type": 2 }
        ] }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val cfg = r.config!!
        assertEquals(3, cfg.sites.size)
        // type 0/3 是协议已知；type 2 未知
        assertEquals(2, cfg.knownSites().size)
        assertEquals(1, cfg.unknownSites().size)
        assertTrue(r.warnings.any { it.contains("不在已知路由表内") })
    }

    @Test
    fun `Jar 与 drpy JS 源均可用 但未知 type 不可用`() {
        // type 3 的 csp_* Jar 源与 drpy JS 源分别由 Jar/QuickJS 引擎接管；未知 type 仍单列。
        val json = """
        { "sites": [
            { "key": "jar", "api": "csp_Jar", "type": 3 },
            { "key": "js", "api": "https://x.com/r.js", "type": 3 },
            { "key": "weird", "api": "https://y.com", "type": 9 }
        ] }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val cfg = r.config!!
        assertEquals(2, cfg.supportedSites().size)
        assertTrue(cfg.supportedSites().any { it.key == "jar" })
        assertTrue(cfg.supportedSites().any { it.key == "js" })
        assertEquals(1, cfg.unsupportedSites().size)
        // 只有未知 type 产生路由提示；JS 源已由 QuickJS 接管
        assertTrue(r.warnings.none { it.contains("QuickJS") })
        assertTrue(r.warnings.any { it.contains("不在已知路由表内") })
    }

    @Test
    fun `全部为 type3 时导入仍成功`() {
        // 回归：真机首屏 bug —— 全 Jar 源配置必须能导入；
        // M2 起这些站点还应该真正进入可用列表（引擎已落地）
        val json = """
        { "sites": [
            { "key": "a", "api": "csp_A", "type": 3 },
            { "key": "b", "api": "csp_B", "type": 3 }
        ] }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        assertEquals(2, r.config!!.sites.size)
        assertEquals(2, r.config!!.supportedSites().size)
    }

    @Test
    fun `BOM 与前后空白可容错`() {
        val json = "\uFEFF  \n{ \"sites\": [ { \"key\": \"k\", \"api\": \"https://a.com\" } ] }  \n"
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
    }

    @Test
    fun `重复站点 key 本地唯一化且不丢源`() {
        // 回归：clun.top 的 fun.json 自带重复 key，滚动站点列表时 Compose Lazy 抛
        // "Key \"py_Youavhub\" was already used"。重复项保留源，只把 key 改为 key#2/#3。
        val json = """
        { "sites": [
            { "key": "py_Youavhub", "name": "Youavhub[py]", "type": 3, "api": "https://a.com/a.py" },
            { "key": "py_Youavhub", "name": "Youavhub[py]", "type": 3, "api": "https://a.com/a.py" },
            { "key": "美少女", "name": "少女 | 采集", "type": 0, "api": "https://b.com/1" },
            { "key": "美少女", "name": "美少女", "type": 0, "api": "https://b.com/2" }
        ] }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        assertTrue(r.isSuccess)
        val sites = r.config!!.sites
        assertEquals(4, sites.size)
        assertEquals(listOf("py_Youavhub", "py_Youavhub#2", "美少女", "美少女#2"), sites.map { it.key })
        assertEquals(sites.size, sites.map { it.key }.toSet().size)
        assertTrue(r.warnings.any { it.contains("重复") })
    }
}
