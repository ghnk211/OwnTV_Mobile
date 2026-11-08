package tv.own.owntv.mobile.tvbox.config.parser

import tv.own.owntv.mobile.tvbox.util.LocalSourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fun.json 形态（TVBox 生态"魔改"配置）回归测试。
 *
 * 覆盖四类真实配置差异（样本结构来自真实 fun.json，站点名已脱敏）：
 * - P0-1 相对路径 `./` 需按配置基址补全（http 形态同样要能推导目录前缀）
 * - P0-2 lives 订阅地址字段是 `url`/`ua`（而非 `api`/`userAgent`）
 * - P0-3 rules 的 `hosts` 可能是数组（而非字符串）
 * - P0-4 可用性判定必须与引擎路由逐条对齐（.js→QuickJS 引擎、.py→Python 引擎）
 */
class ConfigParserFunJsonTest {

    /**
     * 脱敏结构样本：只保留 fun.json 的字段形态差异，站点名统一占位。
     * - site_json：type 1 采集站（可用）
     * - site_jar：type 3 csp_ 前缀（可用）
     * - site_private：type 3 裸类名 + 私有 jar（可用，兼容写法）
     * - site_py：type 3 .py 脚本（Python 引擎支持，见 docs/py采集源接入方案.md）
     * - site_js：type 3 远端 .js（QuickJS 引擎支持）
     * - lives 用 `url`/`ua` 字段名（fun.json 的实际写法）
     * - rules 的 hosts 是数组
     */
    private fun sample(): String = """
        {
          "spider": "./pg.jar",
          "sites": [
            { "key": "s1", "name": "站点1", "type": 1, "api": "https://api.x.com/api.php/provide/vod/" },
            { "key": "s2", "name": "站点2", "type": 3, "api": "csp_Impl", "jar": "./jar/a.jar" },
            { "key": "s3", "name": "站点3", "type": 3, "api": "BareClass", "jar": "./jar/b.jar" },
            { "key": "s4", "name": "站点4", "type": 3, "api": "./js/ss/x.py" },
            { "key": "s5", "name": "站点5", "type": 3, "api": "https://raw.x.com/y/dr.js" }
          ],
          "lives": [
            { "name": "直播A", "type": 0, "url": "./lib/a.m3u", "ua": "okhttp/3.15", "epg": "http://epg/?ch={name}" },
            { "name": "直播B", "type": 0, "url": "https://x.com/b.txt", "logo": "https://x/{name}.png" }
          ],
          "rules": [
            { "name": "r1", "hosts": ["a.com", "b.com"], "rule": [".m3u8"] },
            { "host": "*", "rule": ["vid_"] }
          ],
          "parses": [ { "name": "p1", "type": 1, "url": "https://jx.com/?url=" } ],
          "flags": ["youku"]
        }
    """.trimIndent()

    // ---------------- P0-1 相对路径补全 ----------------

    @Test
    fun `http 配置地址可推导目录前缀`() {
        assertEquals("https://clun.top/", LocalSourceResolver.httpDirPrefix("https://clun.top/fun.json"))
        assertEquals("https://a.com/x/y/", LocalSourceResolver.httpDirPrefix("https://a.com/x/y/c.json?k=1"))
        assertEquals("http://h/p/", LocalSourceResolver.httpDirPrefix("http://h/p/c.json#frag"))
    }

    @Test
    fun `非 http 或无路径的地址推导不出前缀`() {
        assertEquals(null, LocalSourceResolver.httpDirPrefix("clan://localhost/a/c.json"))
        assertEquals(null, LocalSourceResolver.httpDirPrefix("https://host"))
    }

    @Test
    fun `fixContentPath 只替换引号开头的相对路径`() {
        val body = """{"spider":"./pg.jar","api":"https://a.com/./b"}"""
        val fixed = LocalSourceResolver.fixContentPath(body, "https://clun.top/")
        // 值开头的 ./ 被补全；URL 中间的 ./ 不被误伤
        assertEquals("""{"spider":"https://clun.top/pg.jar","api":"https://a.com/./b"}""", fixed)
    }

    // ---------------- P0-2 lives 字段兼容 ----------------

    @Test
    fun `lives 的 url 与 ua 字段被归一到 api 与 userAgent`() {
        val r = ConfigParser.parse(sample())
        assertTrue(r.isSuccess)
        val lives = r.config!!.lives
        assertEquals(2, lives.size)
        assertEquals("./lib/a.m3u", lives[0].api)
        assertEquals("okhttp/3.15", lives[0].userAgent)
        assertEquals("https://x.com/b.txt", lives[1].api)
        assertEquals("https://x/{name}.png", lives[1].logo)
    }

    // ---------------- P0-3 rules hosts 数组兼容 ----------------

    @Test
    fun `rules 的 hosts 数组被归一为逗号分隔字符串`() {
        val r = ConfigParser.parse(sample())
        val rules = r.config!!.rules
        assertEquals(2, rules.size)
        assertEquals("a.com,b.com", rules[0].hosts)
        assertTrue(rules[0].hostList().contains("a.com"))
        // 单数 host 写法 + 缺 hosts → 全匹配
        assertEquals("*", rules[1].hosts)
    }

    // ---------------- P0-4 可用性判定对齐引擎路由 ----------------

    @Test
    fun `py 脚本源由 Python 引擎支持而 js 脚本源由 QuickJS 支持`() {
        val r = ConfigParser.parse(sample())
        val byKey = r.config!!.sites.associateBy { it.key }
        assertTrue("csp_ 源应可用", byKey["s2"]!!.isSupported)
        assertTrue("裸类名 + 私有 jar 源应可用", byKey["s3"]!!.isSupported)
        assertTrue("CMS 源应可用", byKey["s1"]!!.isSupported)
        // py 源按 TVbox 迁移方案排除：isSupported 为 false（方案 §2.2 附录 B）
        assertFalse(".py 源不应支持（本移植无 Python 引擎）", byKey["s4"]!!.isSupported)
        assertTrue(".js 源应由 QuickJS 支持", byKey["s5"]!!.isSupported)
        assertEquals(null, byKey["s5"]!!.unsupportedReason)
        // 不可用源仍被保留（不丢弃）
        assertEquals(5, r.config!!.sites.size)
    }

    @Test
    fun `supportedSites 包含全部脚本源`() {
        val r = ConfigParser.parse(sample())
        // py 源剔除后 4 个有引擎路由（CMS/Jar/JS 模型层）；JS 引擎为 P5，由 SourceRepository 层再过滤
        assertEquals(4, r.config!!.supportedSites().size)
    }

    @Test
    fun `js 后缀查询参数和导入后的绝对地址均可识别`() {
        val json = """
            {"sites":[
              {"key":"query", "type":3, "api":"https://a.example/drpy.js?token=1#v"},
              {"key":"runtime", "type":3, "api":"https://a.example/lib/drpy2.min.js", "ext":"https://a.example/js/demo.js"}
            ]}
        """
        val sites = ConfigParser.parse(json).config!!.sites
        assertTrue(sites.all { it.isJsSite && it.isSupported })
    }

    // ---------------- ext 对象形态 ----------------

    @Test
    fun `ext 对象被序列化为 JSON 文本保留`() {
        val json = """
        { "sites": [ { "key": "k", "type": 3, "api": "csp_X",
          "ext": { "userScript": "./js/a.user.js", "debug": false } } ] }
        """.trimIndent()
        val r = ConfigParser.parse(json)
        val ext = r.config!!.sites[0].ext!!
        assertTrue("ext 对象应序列化为 JSON 文本", ext.contains("userScript"))
        assertTrue(ext.contains("./js/a.user.js"))
    }
}
