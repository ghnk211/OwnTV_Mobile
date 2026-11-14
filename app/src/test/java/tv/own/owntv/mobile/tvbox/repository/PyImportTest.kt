package tv.own.owntv.mobile.tvbox.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.config.model.TvBoxConfig
import tv.own.owntv.mobile.tvbox.config.parser.ConfigParser

/**
 * .py 本地导入的纯函数与缓存 round-trip（docs/TVbox-PY源迁移方案.md §6 JVM 单测）。
 *
 * 不依赖 Chaquopy，放在常规 test 源集，WITH_PY=false 的精简包也能跑。
 */
class PyImportTest {

    @Test
    fun `py file name is recognized case-insensitively`() {
        assertTrue(TvboxConfigRepository.isPyFileName("北京.py"))
        assertTrue(TvboxConfigRepository.isPyFileName("X.PY"))
        assertFalse(TvboxConfigRepository.isPyFileName("config.json"))
        assertFalse(TvboxConfigRepository.isPyFileName(null))
    }

    @Test
    fun `content sniffing accepts python but rejects json and landing pages`() {
        val script = "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n"
        assertTrue(TvboxConfigRepository.looksLikePyScript(script))
        // 裸类形态也要认（fun.json 28 个 py 源的实际写法）
        assertTrue(TvboxConfigRepository.looksLikePyScript("class Spider:\n    def homeContent(self, f): return None\n"))
        // JSON 配置绝不能被当成脚本
        assertFalse(TvboxConfigRepository.looksLikePyScript("""{"sites":[]}"""))
        // HTML 落地页没有模块级 class Spider
        assertFalse(TvboxConfigRepository.looksLikePyScript("<!DOCTYPE html><html><body>no</body></html>"))
    }

    @Test
    fun `py site key is content hash prefixed and stable`() {
        val md5 = "0123456789abcdef0123456789abcdef"
        assertEquals("py_0123456789ab", TvboxConfigRepository.pySiteKey(md5))
        assertEquals(TvboxConfigRepository.pySiteKey(md5), TvboxConfigRepository.pySiteKey(md5))
    }

    @Test
    fun `display name prefers readable file name and rejects opaque ids`() {
        assertEquals("北京", TvboxConfigRepository.pyDisplayName("北京.py", "abc123"))
        // 无扩展名的可读文件名直接用
        assertEquals("北京", TvboxConfigRepository.pyDisplayName("北京", "abc123"))
        // SAF 不透明 id 与纯数字 id 必须走哈希兜底
        assertTrue(TvboxConfigRepository.pyDisplayName("msf:1234", "abcdef999").startsWith("Python 源"))
        assertTrue(TvboxConfigRepository.pyDisplayName("123456", "abcdef999").startsWith("Python 源"))
    }

    @Test
    fun `config cache round trip keeps a synthetic py site`() {
        val pyApi = "file:///data/user/0/tv.own.owntv.mobile/files/tvbox/py/local/abc.py"
        val config = TvBoxConfig(
            sites = listOf(
                SiteConfig(
                    key = "py_0123456789ab",
                    name = "北京",
                    type = 3,
                    api = pyApi,
                    searchable = 1,
                ),
            ),
        )

        val encoded = TvboxConfigCacheCodec.encode(config)
        val parsed = ConfigParser.parse(encoded)
        assertTrue("缓存正文应能重新解析", parsed.isSuccess)
        val site = parsed.config!!.sites.single()
        assertEquals("py_0123456789ab", site.key)
        assertEquals("北京", site.name)
        assertEquals(3, site.type)
        assertEquals(pyApi, site.api)
        assertTrue(site.isPySite)
        // WITH_PY=true 的 JVM 单测变体：合成站点可见
        assertTrue(site.isSupported)
        assertNull(site.unsupportedReason)
    }
}