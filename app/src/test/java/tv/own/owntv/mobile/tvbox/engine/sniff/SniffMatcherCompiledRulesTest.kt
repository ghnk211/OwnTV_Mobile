package tv.own.owntv.mobile.tvbox.engine.sniff

import tv.own.owntv.mobile.tvbox.config.model.SniffRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SniffMatcher.CompiledRules] 单测（P0-6）。
 *
 * 预编译只是把"每条请求重算一次"搬成"每次嗅探算一次"，
 * 所以核心断言是**语义不能变**：同一个 URL，编译前/编译后必须给出同样的结论与同样的 reason。
 * 真机上调 WebView 拦截回调极其困难，这里把规则侧的行为固化住。
 */
class SniffMatcherCompiledRulesTest {

    @Test
    fun `空规则编译后为空`() {
        assertTrue(SniffMatcher.compile(emptyList()).isEmpty)
    }

    @Test
    fun `全空白关键字条目被丢弃`() {
        val rules = listOf(SniffRule(hosts = "*", rule = listOf("", "   ")))
        assertTrue(SniffMatcher.compile(rules).isEmpty)
        // 语义与旧实现一致：这种规则不会命中任何东西
        assertNull(SniffMatcher.matchCompiled("https://a.com/noext", SniffMatcher.compile(rules)))
    }

    @Test
    fun `编译前后结论与 reason 完全一致`() {
        val rules = listOf(SniffRule(name = "量子", hosts = "okzybo.com", rule = listOf("hls/")))
        val urls = listOf(
            "https://vip.okzybo.com/hls/xyz",
            "https://other.com/hls/xyz",
            "https://vip.okzybo.com/1.m3u8",
            "https://a.com/static/player.js",
            "https://a.com/play/1.html",
        )
        val compiled = SniffMatcher.compile(rules)
        urls.forEach { url ->
            val old = SniffMatcher.match(url, rules = rules)
            val new = SniffMatcher.matchCompiled(url, compiled)
            assertEquals("URL=$url", old?.url, new?.url)
            assertEquals("URL=$url", old?.reason, new?.reason)
        }
    }

    @Test
    fun `hosts 匹配大小写不敏感`() {
        val compiled = SniffMatcher.compile(
            listOf(SniffRule(hosts = "OKZYBO.com", rule = listOf("HLS/"))),
        )
        assertNotNull(
            SniffMatcher.matchCompiled("https://vip.okzybo.com/hls/x", compiled),
        )
    }

    @Test
    fun `hosts 星号对全站生效`() {
        val compiled = SniffMatcher.compile(
            listOf(SniffRule(hosts = "*", rule = listOf("myvideo"))),
        )
        assertNotNull(
            SniffMatcher.matchCompiled("https://any.com/myvideo/1", compiled),
        )
    }

    @Test
    fun `空 hosts 视为全匹配`() {
        val compiled = SniffMatcher.compile(listOf(SniffRule(hosts = "", rule = listOf("/playurl/"))))
        assertNotNull(
            SniffMatcher.matchCompiled("https://a.com/playurl/9f8e7d", compiled),
        )
    }

    /**
     * 预编译的规则表必须能一次命中多条规则里的第一条 —— 顺序语义与旧实现一致
     * （配置里规则的先后是用户意图，不能被 map/集合打乱）。
     */
    @Test
    fun `多条规则按声明顺序命中`() {
        val rules = listOf(
            SniffRule(name = "第一条", hosts = "*", rule = listOf("hls")),
            SniffRule(name = "第二条", hosts = "*", rule = listOf("hls", "m3u8")),
        )
        val compiled = SniffMatcher.compile(rules)
        val hit = SniffMatcher.matchCompiled("https://a.com/hls/x", compiled)
        assertNotNull(hit)
        assertTrue(hit!!.reason.contains("第一条"))
    }

    @Test
    fun `无后缀直链仍由内置特征命中`() {
        // 编译后的规则表为空，走不到自定义分支，但内置分支必须照旧
        val compiled = SniffMatcher.compile(emptyList())
        assertNotNull(
            SniffMatcher.matchCompiled("https://a.com/player/m3u8play.php?url=abc", compiled),
        )
    }
}
