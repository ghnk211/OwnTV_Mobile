package tv.own.owntv.mobile.tvbox.engine.cms

import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.net.NetApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * CMS URL 拼接与故障语义回归测试。
 *
 * 全部样本取自真实配置 `http://xhztv.top/4k.json`，不是构造的假数据 ——
 * 这类"配置写法不规范"的问题只有真实配置才会暴露。
 */
class CmsSpiderUrlTest {

    private fun spiderFor(api: String, ext: String? = null): CmsSpider {
        val site = SiteConfig(
            key = "t",
            name = "测试源",
            type = 1,
            api = api,
            ext = ext,
        )
        // NetApi 在单测里不会被真正调用（只测 buildUrl），传一个普通 client 即可
        val net = NetApi({ OkHttpClient() }, { OkHttpClient() })
        val spider = CmsSpider(site, net)
        // ext 里的附加 query 要 init 之后才写入 extraQuery，这里不走网络
        runBlocking { spider.init(site.ext) }
        return spider
    }

    private fun buildUrlOf(spider: CmsSpider, query: String): String {
        val m: Method = CmsSpider::class.java.getDeclaredMethod("buildUrl", String::class.java)
        m.isAccessible = true
        return m.invoke(spider, query) as String
    }

    /** 统计 URL query 里名为 key 的参数出现次数（按 & 切分后精确比对参数名） */
    private fun countParam(url: String, key: String): Int {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return 0
        return q.split('&').count { seg -> seg.substringBefore('=') == key }
    }

    // ---------- 真实配置里的两种"不规范 api"写法 ----------

    @Test
    fun `api 末尾有尾随空格时正确剔除`() {
        // 真实样本：爱坤┃资源  "api": "https://ikunzyapi.com/api.php/provide/vod "
        val s = spiderFor("https://ikunzyapi.com/api.php/provide/vod ")
        val url = buildUrlOf(s, "ac=list&pg=1")
        assertEquals("https://ikunzyapi.com/api.php/provide/vod?ac=list&pg=1", url)
        assertTrue("URL 不应含空格：$url", !url.contains(" "))
    }

    @Test
    fun `api 自带 query 时不产生重复参数`() {
        // 真实样本：豪华┃资源  "api": "https://hhzyapi.com/api.php/provide/vod/?ac=list"
        val s = spiderFor("https://hhzyapi.com/api.php/provide/vod/?ac=list")
        val url = buildUrlOf(s, "ac=list&pg=1")
        // ac 只应出现一次
        assertEquals("ac 重复了：$url", 1, countParam(url, "ac"))
        assertEquals("https://hhzyapi.com/api.php/provide/vod?ac=list&pg=1", url)
    }

    @Test
    fun `api 自带的 ac=list 不会污染详情请求`() {
        // 回归：修复前详情请求会变成 ?ac=list&ac=detail&ids=1，
        // 靠 PHP "取最后一个同名参数"才碰巧能用，站方一变就静默走错接口。
        val s = spiderFor("https://hhzyapi.com/api.php/provide/vod/?ac=list")
        val url = buildUrlOf(s, "ac=detail&ids=123")
        assertEquals("ac 重复了：$url", 1, countParam(url, "ac"))
        assertTrue("详情请求必须以 ac=detail 为准：$url", url.contains("ac=detail"))
        assertTrue(url.contains("ids=123"))
    }

    @Test
    fun `api 带 query 时剥离末尾斜杠`() {
        // 回归：剥离 query 后残留的尾斜杠若原样拼回，会得到 "…/vod/?ac=list"，
        // 多数 PHP 路由能容忍，但路径型站方（WAF/伪静态）会直接 404。
        val s = spiderFor("https://hhzyapi.com/api.php/provide/vod/?ac=list")
        val url = buildUrlOf(s, "ac=list&pg=1")
        assertTrue("? 前不应残留斜杠：$url", !url.contains("/?"))
    }

    @Test
    fun `api 自带的固定参数被保留`() {
        // 形如 ?token=abc 的固定参数不能被引擎的 query 冲掉
        val s = spiderFor("https://x.example.com/api.php/provide/vod/?token=abc123")
        val url = buildUrlOf(s, "ac=list&pg=1")
        assertTrue("token 丢失：$url", url.contains("token=abc123"))
        assertTrue(url.contains("ac=list"))
    }

    @Test
    fun `无 query 的标准 api 行为不变`() {
        val s = spiderFor("https://hhzyapi.com/api.php/provide/vod/")
        assertEquals("https://hhzyapi.com/api.php/provide/vod?ac=list&pg=1", buildUrlOf(s, "ac=list&pg=1"))
    }

    @Test
    fun `ext 的附加参数也参与覆盖`() {
        // ext 形如 "token=xyz"，应作为固定参数附加
        val s = spiderFor("https://x.example.com/vod/", ext = "token=xyz")
        val url = buildUrlOf(s, "ac=list")
        assertTrue(url.contains("token=xyz"))
    }

    // ---------- 故障语义：异常不得被伪装成"源返回空" ----------

    @Test
    fun `网络故障向上抛出而不是静默返回 null`() = runTest {
        // 回归：runGuarded 此前 catch 后返回 null，SourceRepository 把 null 一律当作
        // 哨兵 TIMEOUT_MARKER（源正常但无数据），于是 TLS 握手失败这类**网络故障**
        // 被显示成"接口正常但该分类暂无数据"，排查方向被完全带偏。
        val site = SiteConfig(key = "boom", name = "坏源", type = 1, api = "https://127.0.0.1:1/vod")
        val net = NetApi({ OkHttpClient() }, { OkHttpClient() })
        // 端口 1 不通 → NetApi 兜底返回 TextResponse(-1,...) → request() 抛 CmsException
        val spider = CmsSpider(site, net)

        val thrown = runCatching { spider.homeContent(false) }.exceptionOrNull()
        assertTrue(
            "网络不通时应当抛出异常以区分故障，实际得到：$thrown",
            thrown != null,
        )
    }

    // ---------- 子分类参数与列表动作（真机 bug 回归） ----------
    //
    // 样本取自 https://tv.203511.xyz/0821.json 里的 5 个「采集」源，实测结论：
    //   1. 苹果CMS 只认 `t=`，`tid=` 会被**静默忽略**并返回未过滤的第一页
    //      → 症状是"切任何子分类内容都一样"，不报错、无日志，最难排查；
    //   2. `ac=list` 的 list 节点只有 vod_id/vod_name/type_id，**没有 vod_pic**
    //      → 症状是首页封面全空白；带图要 `ac=videolist`(type=0 XML) 或 `ac=detail`(type=1 JSON)。

    private val cmsJson = """
        {"code":1,"msg":"ok","page":1,"pagecount":3,"limit":20,"total":60,
         "list":[{"vod_id":11,"vod_name":"片A","type_id":6,
                  "vod_pic":"https://cdn.example.com/a.jpg","vod_remarks":"HD"}],
         "class":[{"type_id":6,"type_name":"动作片"},{"type_id":7,"type_name":"喜剧片"}]}
    """.trimIndent()

    /** 返回 (spider, 实际发出的请求 URL 列表) */
    private fun recordingSpider(type: Int, api: String): Pair<CmsSpider, MutableList<String>> {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seen += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(cmsJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return CmsSpider(
            SiteConfig(key = "s", name = "记录源", type = type, api = api),
            NetApi({ client }, { client }),
        ) to seen
    }

    @Test
    fun `子分类必须用 t 而不是 tid`() = runTest {
        val (spider, seen) = recordingSpider(1, "https://bfzyapi.com/api.php/provide/vod")
        spider.categoryContent("6", "1", false, null)
        val url = seen.first()
        assertTrue("子分类必须带 t=：$url", countParam(url, "t") == 1)
        assertTrue("tid 会被服务端忽略，不得再发：$url", countParam(url, "tid") == 0)
    }

    @Test
    fun `JSON 源列表动作为 ac=detail，XML 源为 ac=videolist`() = runTest {
        val (s1, seen1) = recordingSpider(1, "https://bfzyapi.com/api.php/provide/vod")
        s1.categoryContent("6", "1", false, null)
        assertTrue("type=1 应走 ac=detail：${seen1.first()}", seen1.first().contains("ac=detail"))

        val (s0, seen0) = recordingSpider(0, "https://cj.lziapi.com/api.php/provide/vod/at/xml/")
        s0.categoryContent("6", "1", false, null)
        assertTrue("type=0 应走 ac=videolist：${seen0.first()}", seen0.first().contains("ac=videolist"))
    }

    @Test
    fun `首页分两次请求：ac=list 取分类树，列表动作取封面`() = runTest {
        val (spider, seen) = recordingSpider(1, "https://360zy.com/api.php/provide/vod?")
        val result = spider.homeContent(false)
        assertEquals("首页应发两次请求：$seen", 2, seen.size)
        assertTrue("第一次必须是 ac=list（只有它带 class）：${seen[0]}", seen[0].contains("ac=list"))
        assertTrue("第二次必须是带封面的列表动作：${seen[1]}", seen[1].contains("ac=detail"))
        assertTrue("分类树应解析出来", result?.classes?.map { it.typeId } == listOf("6", "7"))
        assertTrue(
            "首页列表应来自带 vod_pic 的那次响应",
            result?.list?.firstOrNull()?.pic == "https://cdn.example.com/a.jpg",
        )
    }
}