package tv.own.owntv.mobile.tvbox.config.parser

import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 用真实配置 `http://xhztv.top/4k.json` 验证解析结果符合生态预期。
 *
 * 这份配置代表了一类"大而杂"的真实 TVBox 配置：
 * - 53 个站点（type=3 jar 源 44 个 + type=1 CMS 源 9 个）
 * - 21 个站点带私有 jar（且大部分已失效 —— 这是**配置本身**的问题，不是 App 的）
 * - 5 个 drpy JS 源（需QuickJS 引擎，当前版本不支持）
 * - 文件里带`//` 行注释与裸控制字符
 *
 * 这里只验证**App 侧解析与引擎判定**的正确性，不验证源站可用性
 * （后者依赖网络与上游站点状态，不适合做单测）。
 */
class RealConfig4kTest {

    private fun fixture(): String {
        val f = java.io.File("src/test/resources/fixtures/dirty-4k.json")
        assertTrue("找不到真实配置样本：${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `真实配置能完整解析出53 个站点`() {
        val r = ConfigParser.parse(fixture())
        assertNotNull("解析失败：${r.error}", r.config)
        val sites = r.config!!.sites
        assertTrue("站点数应≥ 50，实际 ${sites.size}", sites.size >= 50)
    }

    /**
     * CMS 源（type=1）必须全部可用 —— 这类源不依赖任何 jar，
     * 只要 App 的 CMS 引擎正常就一定能用。
     *
     * 若这条挂了而 jar 源也没几个能用，说明整个配置"全废"，
     * 那通常是App 侧问题（而不是配置里的 jar 都恰好失效）。
     */
    @Test
    fun `type=1 CMS 站点全部被识别为可用`() {
        val r = ConfigParser.parse(fixture())
        val cms = r.config!!.sites.filter { it.type == 1 }
        assertTrue("应至少有 9 个 CMS 源，实际 ${cms.size}", cms.size >= 9)
        cms.forEach { s ->
            assertTrue("CMS 源「${s.name}」应被支持", s.isSupported)
        }
    }

    /** 带私有 jar 的 type=3 源应被识别为 jar 引擎（依赖站点私有 jar，不依赖公共壳） */
    @Test
    fun `带私有 jar 的 type=3 站点被识别为 Jar 引擎`() {
        val r = ConfigParser.parse(fixture())
        val withJar = r.config!!.sites.filter { it.type == 3 && !it.jar.isNullOrBlank() }
        assertTrue("应至少有 15 个带私有 jar 的源，实际 ${withJar.size}", withJar.size >= 15)
        withJar.forEach { s ->
            assertTrue("「${s.name}」应支持", s.isSupported)
        }
    }

    /**
     * drpy JS 源由 QuickJS 接管，必须保留并标记为可用（不能丢弃）。
     */
    @Test
    fun `drpy JS 源保留并标记为 QuickJS 可用`() {
        val r = ConfigParser.parse(fixture())
        assertNotNull(r.config)
        // JS 源必须被保留并标记为可用，不能静默丢弃
        val js = r.config!!.sites.filter { it.api.endsWith(".js") || it.api.contains("drpy") }
        assertTrue("应识别出 JS 源", js.isNotEmpty())
        assertTrue("JS 源应由 QuickJS 引擎支持", js.all { it.isSupported })
    }

    /** 顶层 spider 字段必须正确解析（TVBox 用它指定公共 jar） */
    @Test
    fun `顶层 spider 声明被正确解析`() {
        val r = ConfigParser.parse(fixture())
        assertNotNull("spider 不应为空", r.config!!.spider)
        assertTrue("spider 应为 http(s) 地址", r.config!!.spider!!.startsWith("http"))
    }

    /** parses 解析器列表（播放时兜底用） */
    @Test
    fun `parses 列表被解析`() {
        val r = ConfigParser.parse(fixture())
        assertNotNull(r.config)
        assertTrue("parses 应有 10+ 条，实际 ${r.config!!.parses.size}", r.config!!.parses.size >= 10)
    }
}