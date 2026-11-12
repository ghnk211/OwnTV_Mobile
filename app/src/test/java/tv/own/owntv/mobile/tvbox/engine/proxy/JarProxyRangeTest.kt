package tv.own.owntv.mobile.tvbox.engine.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Range 切片：media3 拖进度条会发 `Range: bytes=`。 */
class JarProxyRangeTest {

    @Test
    fun `no header covers whole body`() {
        assertEquals(0 to 9, JarProxyServer.parseRange(null, 10))
        assertEquals(0 to 9, JarProxyServer.parseRange("", 10))
        assertEquals(0 to 9, JarProxyServer.parseRange("items=0-1", 10))
    }

    @Test
    fun `open ended range from start`() {
        assertEquals(5 to 9, JarProxyServer.parseRange("bytes=5-", 10))
    }

    @Test
    fun `closed range clamps to body`() {
        assertEquals(2 to 7, JarProxyServer.parseRange("bytes=2-7", 10))
        assertEquals(2 to 9, JarProxyServer.parseRange("bytes=2-99", 10))
    }

    @Test
    fun `suffix range counts from end`() {
        assertEquals(7 to 9, JarProxyServer.parseRange("bytes=-3", 10))
        assertEquals(0 to 9, JarProxyServer.parseRange("bytes=-99", 10))
    }

    @Test
    fun `empty body stays empty`() {
        assertEquals(0 to 0, JarProxyServer.parseRange("bytes=0-5", 0))
    }

    @Test
    fun `out of range start clamps back to the whole body`() {
        assertEquals(0 to 9, JarProxyServer.parseRange("bytes=20-30", 10))
        assertEquals(0 to 9, JarProxyServer.parseRange("bytes=abc-def", 10))
    }
}