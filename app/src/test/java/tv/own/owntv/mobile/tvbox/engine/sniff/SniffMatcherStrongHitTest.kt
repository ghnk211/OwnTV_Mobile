package tv.own.owntv.mobile.tvbox.engine.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 命中强度 [SniffMatcher.Hit.strong] 单测（P1-2）。
 *
 * 语义：**强 = 路径末段真带媒体后缀 / 响应头是 video**；弱 = 靠无后缀特征、查询串、flag 猜的。
 *
 * 为什么要锁死这个语义：解析器站自己的接口地址（`https://jx.xxx.com/api.php?type=m3u8&url=…`）
 * 会命中弱规则。若把它当强命中直接采用，播放器会去解析 JSON/HTML，报容器错误，
 * 然后整条换线换源链重来（感知上慢 10s+）。弱命中落在解析器域名上时必须降级。
 */
class SniffMatcherStrongHitTest {

    @Test
    fun `真后缀命中是强证据`() {
        val hit = SniffMatcher.match("https://cdn.example.com/hls/index.m3u8")
        assertNotNull(hit)
        assertEquals(true, hit!!.strong)
    }

    @Test
    fun `查询串里的媒体后缀是弱证据`() {
        // m3u8 藏在 query 里：可能是真代理流，也可能只是解析器接口的参数
        val hit = SniffMatcher.match("https://ssl.data.m3u8cache.com/m3u8.php?f=9664.m3u8&expire=1")
        assertNotNull(hit)
        assertEquals(false, hit!!.strong)
    }

    @Test
    fun `无后缀特征命中是弱证据`() {
        val hit = SniffMatcher.match("http://api.example.com/proxy.php?type=m3u8&url=abc")
        assertNotNull(hit)
        assertEquals(false, hit!!.strong)
    }

    @Test
    fun `解析器接口地址本身是弱命中`() {
        // 真实形态：`/player/m3u8play.php?url=`，末段无媒体后缀
        val hit = SniffMatcher.match("https://jx.example.com/player/m3u8play.php?url=http%3A%2F%2Fa.com")
        assertNotNull(hit)
        assertEquals(false, hit!!.strong)
    }

    @Test
    fun `video content-type 是强证据`() {
        val hit = SniffMatcher.match("https://a.com/stream/abc", contentType = "video/mp4")
        assertNotNull(hit)
        assertEquals(true, hit!!.strong)
    }

    @Test
    fun `mpegurl content-type 是强证据`() {
        val hit = SniffMatcher.match("https://a.com/x", contentType = "application/vnd.apple.mpegurl")
        assertNotNull(hit)
        assertEquals(true, hit!!.strong)
    }

    @Test
    fun `flag 关键字兜底是弱证据`() {
        val hit = SniffMatcher.match("https://a.com/noext/path", flag = "云播m3u8线路")
        assertNotNull(hit)
        assertEquals(false, hit!!.strong)
    }

    @Test
    fun `自定义规则命中是弱证据`() {
        val rules = listOf(
            tv.own.owntv.mobile.tvbox.config.model.SniffRule(name = "量子", hosts = "okzybo.com", rule = listOf("hls/")),
        )
        val hit = SniffMatcher.match("https://vip.okzybo.com/hls/xyz", rules = rules)
        assertNotNull(hit)
        assertEquals(false, hit!!.strong)
    }
}
