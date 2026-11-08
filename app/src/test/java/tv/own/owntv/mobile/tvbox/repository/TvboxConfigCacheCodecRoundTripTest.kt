package tv.own.owntv.mobile.tvbox.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.config.model.CatchupConfig
import tv.own.owntv.mobile.tvbox.config.model.LiveConfig
import tv.own.owntv.mobile.tvbox.config.model.ParseConfig
import tv.own.owntv.mobile.tvbox.config.model.ProxyConfig
import tv.own.owntv.mobile.tvbox.config.model.SniffRule
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.config.model.TvBoxConfig
import tv.own.owntv.mobile.tvbox.config.parser.ConfigParser

/**
 * 缓存序列化 round-trip：`encode → ConfigParser.parse` 必须还原全部字段。
 *
 * 这是"缓存静默丢字段"坑（方案 §10 已知：漏掉 lives/spider 补全导致重启后字段消失）的
 * 字段集防线 —— 给缓存正文增删字段时，这个测试就是增删 [TvboxConfigCacheCodec.VERSION] 的提醒。
 */
class TvboxConfigCacheCodecRoundTripTest {

    private fun fullConfig(): TvBoxConfig {
        val header: JsonObject = buildJsonObject {
            put("User-Agent", "okhttp/5.5.0")
            put("Referer", "http://x/")
        }
        return TvBoxConfig(
            spider = "http://x/spider.jar;md5;e89ad80c",
            wallpaper = "http://x/bg.png",
            sites = listOf(
                SiteConfig(
                    key = "s1",
                    name = "站一",
                    type = 0,
                    api = "http://x/api.php/provide/vod",
                    searchable = 1,
                    quickSearch = 1,
                    filterable = 1,
                    ext = "{\"filter\":\"y\"}",
                    header = header,
                    jar = "http://x/site.jar",
                    userAgent = "custom-ua",
                    playUrl = "http://parse/x?url=",
                    timeout = 20,
                    categories = listOf("电影", "剧集"),
                ),
                SiteConfig(key = "s2", name = "站二", type = 3, api = "csp_Douban"),
            ),
            parses = listOf(ParseConfig(name = "超级解析", type = 1, url = "http://p/?url=", show = "1")),
            flags = listOf("qiyi", "qq"),
            rules = listOf(
                SniffRule(
                    name = "规则一",
                    hosts = "*.x.com",
                    rule = listOf("item_id=", "video/tos"),
                    ads = listOf("m3u8?"),
                    regex = listOf("http[s]?://[^\"']+\\.m3u8"),
                ),
            ),
            ads = listOf("http://ad.example"),
            proxy = listOf(ProxyConfig(name = "p1", hosts = listOf("x.com"), urls = listOf("http://u:p@h:1"))),
            lives = listOf(
                LiveConfig(
                    name = "直播一",
                    type = 0,
                    api = "http://x/live.txt",
                    jar = "http://x/live.jar",
                    ext = "{\"e\":1}",
                    userAgent = "live-ua",
                    epg = "http://x/epg",
                    logo = "http://x/logo.png",
                    playerType = 1,
                    timeout = 15,
                    group = "g",
                    catchup = CatchupConfig(type = "append", source = "http://x/{utc}", replace = "a,b", regex = "r"),
                ),
            ),
        )
    }

    @Test
    fun `encode 后能被 ConfigParser 还原且字段一致`() {
        val config = fullConfig()
        val text = TvboxConfigCacheCodec.encode(config)

        // 版本标记在最前
        val version = TvboxConfigCacheCodec.VERSION_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals(TvboxConfigCacheCodec.VERSION, version)

        val parsed = ConfigParser.parse(text)
        assertTrue("round-trip 应解析成功：${parsed.error}", parsed.isSuccess)
        val back = parsed.config!!

        assertEquals(config.spider, back.spider)
        assertEquals(config.wallpaper, back.wallpaper)
        assertEquals(config.flags, back.flags)
        assertEquals(config.ads, back.ads)

        // sites：逐字段对齐
        assertEquals(config.sites.size, back.sites.size)
        config.sites.zip(back.sites).forEach { (a, b) ->
            assertEquals(a.key, b.key)
            assertEquals(a.name, b.name)
            assertEquals(a.type, b.type)
            assertEquals(a.api, b.api)
            assertEquals(a.searchable, b.searchable)
            assertEquals(a.quickSearch, b.quickSearch)
            assertEquals(a.filterable, b.filterable)
            assertEquals(a.ext, b.ext)
            assertEquals(a.jar, b.jar)
            assertEquals(a.userAgent, b.userAgent)
            assertEquals(a.playUrl, b.playUrl)
            assertEquals(a.timeout, b.timeout)
            assertEquals(a.categories, b.categories)
            // header 的值经字符串化往返，等值比较（键序无关）
            assertEquals(a.header?.toString()?.length, b.header?.toString()?.length)
            a.header?.forEach { (k, v) ->
                assertEquals((b.header?.get(k) as? JsonPrimitive)?.content, (v as JsonPrimitive).content)
            }
        }

        // parses
        assertEquals(config.parses.map { it.name }, back.parses.map { it.name })
        assertEquals(config.parses.map { it.type }, back.parses.map { it.type })
        assertEquals(config.parses.map { it.url }, back.parses.map { it.url })

        // rules
        assertEquals(config.rules.map { it.hosts }, back.rules.map { it.hosts })
        assertEquals(config.rules.flatMap { it.rule }, back.rules.flatMap { it.rule })
        assertEquals(config.rules.flatMap { it.ads }, back.rules.flatMap { it.ads })

        // proxy
        assertEquals(config.proxy.map { it.name }, back.proxy.map { it.name })
        assertEquals(config.proxy.flatMap { it.hosts }, back.proxy.flatMap { it.hosts })

        // lives（方案 §10 坑：漏掉 lives 会导致"重启后直播源全没了"）
        assertEquals(config.lives.map { it.name }, back.lives.map { it.name })
        assertEquals(config.lives.map { it.api }, back.lives.map { it.api })
        assertEquals(config.lives.map { it.epg }, back.lives.map { it.epg })
        assertEquals(config.lives.map { it.catchup?.source }, back.lives.map { it.catchup?.source })
    }

    @Test
    fun `版本标记不符时可判定缓存过期`() {
        val text = TvboxConfigCacheCodec.encode(fullConfig())
        val stale = text.replace(
            "\"_cacheVersion\":${TvboxConfigCacheCodec.VERSION}",
            "\"_cacheVersion\":${TvboxConfigCacheCodec.VERSION - 1}",
        )
        assertEquals(
            TvboxConfigCacheCodec.VERSION - 1,
            TvboxConfigCacheCodec.VERSION_REGEX.find(stale)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    @Test
    fun `encode 产物是合法 JSON 且带版本标记`() {
        val text = TvboxConfigCacheCodec.encode(fullConfig())
        val root = runCatching { Json.parseToJsonElement(text) as JsonObject }
        assertTrue("encode 产物应为合法 JSON", root.isSuccess)
        assertTrue(root.getOrNull()?.containsKey("_cacheVersion") == true)
    }
}
