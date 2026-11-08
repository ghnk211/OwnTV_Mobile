package tv.own.owntv.mobile.tvbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `clan://` / `file://` 地址解析的回归测试（对齐 TVBoxOS 生态写法）。
 *
 * ## 为什么要单测
 * 这套前缀是**生态约定**，不是我们自己定的：用户在别处复制来的"本地版配置"
 * 地址形如 `clan://localhost/jihe666/jihe.json`。解析错一个字符，
 * 用户看到的就是"地址填错了"，而实际上地址没错。
 *
 * 外部存储根通过 [LocalSourceResolver.parse] 的 root 参数注入，
 * 保证这是纯 JVM 可测逻辑（真机上才依赖 `Environment`）。
 */
class LocalSourceTest {

    private val root = File("/sdcard")

    /**
     * 路径统一按 POSIX 展示。
     *
     * 单测跑在 Windows JVM 上：`File(File("/sdcard"), "a/b.json").path` 会变成
     * `\sdcard\a\b.json`。断言的是**解析出的逻辑路径**，与平台分隔符无关，
     * 故统一成正斜杠再比。
     */
    private fun posix(f: File): String = f.path.replace('\\', '/')

    // ---------------- clan://localhost ----------------

    @Test
    fun clanLocalhostMapsToExternalRoot() {
        val src = LocalSourceResolver.parse("clan://localhost/jihe666/jihe.json", root)
        assertTrue(src is LocalSource.LocalFile)
        assertEquals("/sdcard/jihe666/jihe.json", posix((src as LocalSource.LocalFile).file))
    }

    @Test
    fun clanWithEmptyHostIsAlsoLocal() {
        // 生态里确实有 clan:///x.json 这种写法，不能当成远端
        val src = LocalSourceResolver.parse("clan:///jihe.json", root)
        assertTrue(src is LocalSource.LocalFile)
        assertEquals("/sdcard/jihe.json", posix((src as LocalSource.LocalFile).file))
    }

    @Test
    fun clanLocalhostIsCaseInsensitive() {
        val src = LocalSourceResolver.parse("CLAN://LocalHost/x/y.json", root)
        assertTrue(src is LocalSource.LocalFile)
        assertEquals("/sdcard/x/y.json", posix((src as LocalSource.LocalFile).file))
    }

    // ---------------- clan://<host> = 另一台设备的服务 ----------------

    /**
     * 生态真实用法：家里一台盒子开着 TVBox 内置服务，手机上填
     * `clan://192.168.1.9/config.json` 直接用盒子上的配置。
     * 只实现 localhost 会让它退化成"地址填错了"。
     */
    @Test
    fun clanRemoteHostBecomesFileRoute() {
        val src = LocalSourceResolver.parse("clan://192.168.1.9/config.json", root)
        assertTrue(src is LocalSource.RemoteClan)
        assertEquals("http://192.168.1.9/file/config.json", (src as LocalSource.RemoteClan).httpUrl)
    }

    @Test
    fun clanRemoteWithNestedPathKeepsPath() {
        val src = LocalSourceResolver.parse("clan://192.168.1.9/box/my.json", root)
        assertEquals("http://192.168.1.9/file/box/my.json", (src as LocalSource.RemoteClan).httpUrl)
    }

    @Test
    fun clanWithoutPathIsRejected() {
        assertNull(LocalSourceResolver.parse("clan://localhost", root))
        assertNull(LocalSourceResolver.parse("clan://localhost/", root))
    }

    // ---------------- file:// / 裸路径 ----------------

    @Test
    fun fileSchemeStripsQueryAndFragment() {
        val src = LocalSourceResolver.parse("file:///sdcard/jihe.json?a=1#x", root)
        assertTrue(src is LocalSource.LocalFile)
        assertEquals("/sdcard/jihe.json", posix((src as LocalSource.LocalFile).file))
    }

    @Test
    fun bareAbsolutePathIsLocal() {
        val src = LocalSourceResolver.parse("/storage/emulated/0/box/m.json", root)
        assertTrue(src is LocalSource.LocalFile)
        assertEquals("/storage/emulated/0/box/m.json", posix((src as LocalSource.LocalFile).file))
    }

    // ---------------- 非本地形态 ----------------

    @Test
    fun httpIsLeftToNetworkLayer() {
        assertNull(LocalSourceResolver.parse("http://a.com/x.json", root))
        assertNull(LocalSourceResolver.parse("https://a.com/x.json", root))
    }

    @Test
    fun blankIsRejected() {
        assertNull(LocalSourceResolver.parse("   ", root))
        assertNull(LocalSourceResolver.parse("", root))
    }

    @Test
    fun isLocalPathCoversAllLocalForms() {
        assertTrue(LocalSourceResolver.isLocalPath("clan://localhost/a.json"))
        assertTrue(LocalSourceResolver.isLocalPath("clan://192.168.1.9/a.json"))
        assertTrue(LocalSourceResolver.isLocalPath("file:///sdcard/a.json"))
        assertTrue(LocalSourceResolver.isLocalPath("/sdcard/a.json"))
        assertTrue(LocalSourceResolver.isLocalPath("content://x/y"))
        // 网络地址不该被当成本地
        assertTrue(!LocalSourceResolver.isLocalPath("http://a.com/x.json"))
        assertTrue(!LocalSourceResolver.isLocalPath(null))
    }

    // ---------------- 相对路径修正 ----------------

    /**
     * 本地配置普遍用 `"./spider.jar"` 引用同目录的 jar。
     * 不修正的话没有任何一层能解析它，表现为"导入成功但所有 csp_ 源都找不到 jar"。
     */
    @Test
    fun fixContentPathRewritesRelativeSpider() {
        val body = """{"spider":"./spider.jar","sites":[]}"""
        val fixed = LocalSourceResolver.fixContentPath(body, "file:///sdcard/jihe666/")
        assertEquals("""{"spider":"file:///sdcard/jihe666/spider.jar","sites":[]}""", fixed)
    }

    @Test
    fun fixContentPathLeavesPlainJsonAlone() {
        // 正文里没有 "./" 就不能动 —— 误伤 URL 里的 ./ 会毁掉整份配置
        val body = """{"spider":"http://a.com/b.jar","sites":[]}"""
        assertEquals(body, LocalSourceResolver.fixContentPath(body, "file:///sdcard/jihe666/"))
    }

    @Test
    fun fixContentPathNoopWithoutPrefix() {
        val body = """{"spider":"./spider.jar"}"""
        assertEquals(body, LocalSourceResolver.fixContentPath(body, null))
    }

    // ---------------- 相对地址使用点解析（docs/直播防护改进计划.md jar 修复） ----------------

    @Test
    fun resolveAgainstPointSlashJoinsConfigDirectory() {
        // top98 情形：配置在主机根目录，./jar/ 与配置同目录
        assertEquals(
            "http://home.jundie.top:81/jar/top98_1.jar",
            LocalSourceResolver.resolveAgainst("./jar/top98_1.jar", "http://home.jundie.top:81/top98.json"),
        )
        // 配置在子目录：./ 相对的是目录而不是主机根
        assertEquals(
            "https://tv.203511.xyz/jar/custom_spider.jar",
            LocalSourceResolver.resolveAgainst("./jar/custom_spider.jar", "https://tv.203511.xyz/0821.json"),
        )
        assertEquals(
            "https://a.com/sub/lib/fty.jar",
            LocalSourceResolver.resolveAgainst("./lib/fty.jar", "https://a.com/sub/catcr.json?x=1#frag"),
        )
    }

    @Test
    fun resolveAgainstBareRelativeAlsoJoinsDirectory() {
        assertEquals(
            "https://a.com/sub/lib/fty.jar",
            LocalSourceResolver.resolveAgainst("lib/fty.jar", "https://a.com/sub/catcr.json"),
        )
    }

    @Test
    fun resolveAgainstKeepsAbsoluteAndLocalForms() {
        val base = "https://a.com/sub/c.json"
        assertEquals("https://cdn.x.com/a.jar", LocalSourceResolver.resolveAgainst("https://cdn.x.com/a.jar", base))
        assertEquals("clan://localhost/jar/a.jar", LocalSourceResolver.resolveAgainst("clan://localhost/jar/a.jar", base))
        assertEquals("file:///sdcard/a.jar", LocalSourceResolver.resolveAgainst("file:///sdcard/a.jar", base))
        // / 开头按 isLocalPath 语义视为本地绝对路径，不做根相对解析（无法与 /sdcard 区分）
        assertEquals("/sdcard/a.jar", LocalSourceResolver.resolveAgainst("/sdcard/a.jar", base))
    }

    @Test
    fun resolveAgainstNoopWithoutUsableBaseOrSegments() {
        // 无 base / base 非 http（本地导入）→ 原样，宁缺勿错拼
        assertEquals("./lib/a.jar", LocalSourceResolver.resolveAgainst("./lib/a.jar", null))
        assertEquals("./lib/a.jar", LocalSourceResolver.resolveAgainst("./lib/a.jar", ""))
        assertEquals("./lib/a.jar", LocalSourceResolver.resolveAgainst("./lib/a.jar", "file:///sdcard/c.json"))
        // 多段形态（url;md5;hash）由 SpiderSpec 拆分后才进来；未拆段的原样返回
        assertEquals("./jar/a.jar;md5;abc", LocalSourceResolver.resolveAgainst("./jar/a.jar;md5;abc", "https://a.com/c.json"))
        // base 无路径（https://host）无目录可推导
        assertEquals("./jar/a.jar", LocalSourceResolver.resolveAgainst("./jar/a.jar", "https://a.com"))
    }

    // ---------------- 目录前缀 ----------------

    @Test
    fun localFileDirPrefixEndsWithSlash() {
        val src = LocalSourceResolver.parse("clan://localhost/jihe666/jihe.json", root)!!
        val dir = src.dirPrefix!!
        assertTrue(dir.startsWith("file://"))
        assertTrue(dir.endsWith("/"))
        assertTrue(dir.contains("jihe666"))
    }

    @Test
    fun remoteClanDirPrefixIsHttp() {
        val src = LocalSourceResolver.parse("clan://192.168.1.9/box/my.json", root)!!
        assertEquals("http://192.168.1.9/file/box/", src.dirPrefix)
    }
}
