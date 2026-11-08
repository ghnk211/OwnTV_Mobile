package tv.own.owntv.mobile.tvbox.engine.cms

import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.net.NetApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 2026-10-23 OXF-AN00 真机缺陷 8：CMS 站点级 header 从未生效。
 *
 * 根因是 `initialized` 恒 true → `SourceRepository` 不调用 `init()`，
 * 而 `extraHeaders/extraQuery` 只在 `init()` 里解析。
 */
class CmsSpiderHeaderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "text/xml; charset=utf-8")
                .setBody(XML)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun site() = SiteConfig(
        key = "localtest",
        name = "本地测试",
        type = 0,
        api = server.url("/api.php").toString(),
        header = buildJsonObject { put("Referer", "http://ref.example/") },
    )

    @Test
    fun cmsInitAppliesSiteHeader() = runBlocking {
        val spider = CmsSpider(site(), NetApi({ client }, { client }))
        assertFalse("CMS 源现在必须经过一次 init", spider.initialized)

        spider.init(null)
        assertTrue(spider.initialized)

        val result = spider.homeContent(filter = false)
        assertNotNull(result)

        val seen = ArrayList<RecordedRequest>()
        repeat(server.requestCount) {
            server.takeRequest(1, TimeUnit.SECONDS)?.let { seen += it }
        }
        assertTrue("首页至少发两次请求（ac=list + ac=videolist）", seen.size >= 2)
        seen.forEach { req ->
            assertEquals("站点级 Referer 必须进入每一次 CMS 请求", "http://ref.example/", req.getHeader("Referer"))
        }
    }

    private companion object {
        val XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="5.1">
              <list page="1" pagecount="1" pagesize="20" recordcount="1">
                <video>
                  <id>1</id><name>测试片</name>
                  <dd flag="本地">正片${'$'}https://cdn.example.com/a.m3u8</dd>
                </video>
              </list>
              <class><ty id="1">测试</ty></class>
            </rss>
        """.trimIndent()
    }
}
