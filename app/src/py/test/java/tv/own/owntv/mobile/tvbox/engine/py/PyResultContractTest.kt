package tv.own.owntv.mobile.tvbox.engine.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.engine.PlayNormalize
import tv.own.owntv.mobile.tvbox.engine.jar.JarResultParser

/**
 * Py 返回值归一化的黄金样本（docs/TVbox-PY源迁移方案.md §6）。
 *
 * PySpider 的返回值桥是 `base.loader.dumps`（ensure_ascii=False），即脚本 dict 会变成
 * 与下例一字不差的 JSON 文本，再走 [JarResultParser.asMap] 的 String 分支 ——
 * 与 jar 源返回 JSON 串同一条链路。本测试把该链路的契约钉死：
 * 键名（`class`/`filters`/`vod_*`）、数字形态（int 不带 .0）、详情放宽判据、播放归一化。
 */
class PyResultContractTest {

    @Test
    fun `homeContent golden sample parses classes list and filters`() {
        // 形如北京.py 的 homeContent 返回（dumps 后中文原样，不转 \uXXXX）
        val json = """
            {"class": [{"type_id": "0", "type_name": "北京频道"}, {"type_id": "1", "type_name": "卫视"}],
             "filters": {"0": [{"key": "cateId", "name": "分类", "value": [{"n": "全部", "v": ""}, {"n": "新闻", "v": "news"}]}]},
             "list": [{"vod_id": "kaku", "vod_name": "北京时间", "vod_pic": "https://x/p.jpg", "vod_remarks": "直播"}]}
        """.trimIndent()

        val r = JarResultParser.toResult(json, "https://x/北京.py")
        assertNotNull(r)
        assertEquals(2, r!!.classes.size)
        assertEquals("0", r.classes[0].typeId)
        assertEquals("北京频道", r.classes[0].typeName)
        assertEquals(1, r.list.size)
        assertEquals("kaku", r.list[0].id)
        assertEquals("北京时间", r.list[0].name)
        assertEquals("直播", r.list[0].remark)
        // 筛选树按 type_id 归位，"全部"项的空串 v 不能被吞
        assertEquals(1, r.filters["0"]?.size ?: 0)
        val def = r.filters["0"]!!.first()
        assertEquals("cateId", def.key)
        assertEquals("", def.values.first().v)
    }

    @Test
    fun `python int stays clean string without decimal point`() {
        // 脚本常用 int 当 vod_id；JSON 数字经 Gson 读成 Double，取值必须去掉 ".0"
        val json = """{"list": [{"vod_id": 123, "vod_name": "整数 id"}]}"""
        val r = JarResultParser.toResult(json)
        assertNotNull(r)
        assertEquals("123", r!!.list[0].id)
    }

    @Test
    fun `detail golden sample tolerates missing name and id`() {
        // 直播/索引类源详情常见：只给线路不给名字、无 vod_id —— 详情路径判据放宽
        val json = """
            {"list": [{"vod_pic": "https://x/p.jpg",
                       "vod_play_from": "北京时间",
                       "vod_play_url": "kaku${'$'}https://live/hls.m3u8"}]}
        """.trimIndent()
        val r = JarResultParser.toResult(json, requireName = false, fallbackId = "kaku")
        assertNotNull(r)
        assertEquals("kaku", r!!.list[0].id)
        assertEquals("北京时间", r.list[0].playFrom)
    }

    @Test
    fun `playerContent golden sample keeps header and parse`() {
        val json =
            """{"parse": 0, "url": "https://live/hls.m3u8", "header": {"User-Agent": "ok", "Referer": "https://x/"}}"""
        val p = PlayNormalize.normalizePlay(json, "线路", "kaku", "PySpider", "测试源")
        assertNotNull(p)
        assertEquals(0, p!!.parse)
        assertEquals("https://live/hls.m3u8", p.url)
        assertNotNull(p.header)
        assertTrue(p.header.toString().contains("ok"))
    }
}