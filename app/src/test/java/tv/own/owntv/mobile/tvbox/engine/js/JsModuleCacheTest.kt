package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 模块缓存：7 天 TTL、源码与字节码同生命周期。 */
class JsModuleCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `write then read source within ttl`() {
        val cache = JsModuleCache(tmp.newFolder())
        val url = "https://a.com/lib/drpy-core-lite.min.js"
        cache.writeSource(url, "export default {}")
        assertEquals("export default {}", cache.readSource(url))
    }

    @Test
    fun `bytecode invalid together with source`() {
        val dir = tmp.newFolder()
        val cache = JsModuleCache(dir)
        val url = "https://a.com/lib/x.js"
        cache.writeSource(url, "export default 1")
        cache.writeBytecode(url, byteArrayOf(1, 2, 3))
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), cache.readBytecode(url))

        val srcFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".js") }!!
        srcFile.setLastModified(System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L)
        assertNull(cache.readSource(url))
        assertNull(cache.readBytecode(url))
    }

    @Test
    fun `different modules do not collide`() {
        val cache = JsModuleCache(tmp.newFolder())
        cache.writeSource("https://a.com/x.js", "A")
        cache.writeSource("https://a.com/y.js", "B")
        assertEquals("A", cache.readSource("https://a.com/x.js"))
        assertEquals("B", cache.readSource("https://a.com/y.js"))
    }
}