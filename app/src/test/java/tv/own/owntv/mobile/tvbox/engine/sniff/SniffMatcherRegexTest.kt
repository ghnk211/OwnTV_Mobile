package tv.own.owntv.mobile.tvbox.engine.sniff

import tv.own.owntv.mobile.tvbox.config.model.SniffRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3：`rules[].regex` 落到嗅探匹配。
 *
 * 真实 0821 配置里两类正则混在一起：
 * - 嗅探用：`item_id=` / `is_play_url=` / `video/tos/cn`；
 * - M3U8 净化用：`"5"` / `"20.123"` / `#EXT-X-DISCONTINUITY`。
 * 后者绝不能拿来匹配请求 URL —— 纯数字会命中几乎所有地址。
 */
class SniffMatcherRegexTest {

    @Test
    fun `非播放列表 regex 参与 URL 嗅探`() {
        val compiled = SniffMatcher.compile(
            listOf(SniffRule(name = "hs", hosts = "huoshan.com", regex = listOf("item_id="))),
        )
        val hit = SniffMatcher.matchCompiled("https://v.huoshan.com/api/item_id=123", compiled)
        assertNotNull("item_id= 应命中嗅探", hit)
        assertTrue("reason 应带规则名", hit!!.reason.contains("hs"))
    }

    @Test
    fun `播放列表数字 regex 不参与 URL 嗅探`() {
        val compiled = SniffMatcher.compile(
            listOf(SniffRule(name = "kk", hosts = "kuaikan", regex = listOf("5", "20.123"))),
        )
        assertTrue("播放列表规则不应生成嗅探条目", compiled.isEmpty)
        assertNull(
            SniffMatcher.matchCompiled("https://v.kuaikan.com/api/5x", compiled),
        )
    }

    @Test
    fun `host 不匹配时不使用该条 regex`() {
        val compiled = SniffMatcher.compile(
            listOf(SniffRule(name = "hs", hosts = "huoshan.com", regex = listOf("item_id="))),
        )
        assertNull(
            SniffMatcher.matchCompiled("https://v.douyin.com/api/item_id=123", compiled),
        )
    }

    @Test
    fun `regex 大小写不敏感`() {
        val compiled = SniffMatcher.compile(
            listOf(SniffRule(name = "dy", hosts = "*", regex = listOf("[?&]VID="))),
        )
        assertNotNull(
            SniffMatcher.matchCompiled("https://a.com/play?vid=abc", compiled),
        )
    }

    @Test
    fun `非法 regex 被忽略且不影响后续规则`() {
        val compiled = SniffMatcher.compile(
            listOf(
                SniffRule(name = "bad", hosts = "*", regex = listOf("([")),
                SniffRule(name = "nm", hosts = "*", regex = listOf("video/tos/cn")),
            ),
        )
        val hit = SniffMatcher.matchCompiled(
            "https://v.toutiaovod.com/video/tos/cn/abc", compiled,
        )
        assertNotNull("坏正则不能拖垮整张规则表", hit)
        assertTrue(hit!!.reason.contains("nm"))
    }
}
