package tv.own.owntv.mobile.tvbox.engine.cms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 苹果 CMS 响应解析测试（方案 14：MockWebServer 之外先做纯解析单测）。
 */
class CmsParserTest {

    @Test
    fun `解析标准列表响应`() {
        val body = """
        {
          "code": 1, "msg": "数据列表",
          "page": 1, "pagecount": 5, "limit": 20, "total": 100,
          "class": [
            { "type_id": 1, "type_name": "电影" },
            { "type_id": 2, "type_name": "电视剧" }
          ],
          "list": [
            { "vod_id": 101, "vod_name": "测试影片A", "vod_pic": "https://p/a.jpg",
              "vod_remarks": "更新至10集", "vod_year": "2024", "vod_area": "大陆" },
            { "vod_id": "102", "vod_name": "测试影片B" }
          ]
        }
        """.trimIndent()

        val page = CmsParser.parsePage(body)
        assertTrue(page.ok)
        assertEquals(2, page.movies.size)
        assertEquals(2, page.classes.size)
        assertEquals(1, page.page)
        assertEquals(5, page.pageCount)
        assertEquals(100, page.total)
        assertEquals("测试影片A", page.movies[0].name)
        assertEquals("101", page.movies[0].id)   // 数字 id 字符串化
        assertEquals("102", page.movies[1].id)   // 字符串 id 保持
    }

    @Test
    fun `code 为 0 但 list 非空视为成功`() {
        val body = """{"code":0,"list":[{"vod_id":1,"vod_name":"A"}]}"""
        val page = CmsParser.parsePage(body)
        assertTrue(page.ok)
        assertEquals(1, page.movies.size)
    }

    @Test
    fun `缺少播放字段时仍能解析`() {
        val body = """{"code":1,"list":[{"vod_id":1,"vod_name":"A"}]}"""
        val page = CmsParser.parsePage(body)
        assertTrue(page.ok)
        assertEquals(null, page.movies[0].playUrl)
    }

    @Test
    fun `解析详情对象`() {
        val body = """
        { "vod_id": 88, "vod_name": "详情影片", "vod_play_from": "云播",
          "vod_play_url": "第1集${'$'}https://a.com/1.m3u8#第2集${'$'}https://a.com/2.m3u8" }
        """.trimIndent()
        val m = CmsParser.parseDetail(body)
        assertNotNull(m)
        assertEquals("88", m!!.id)
        assertEquals("云播", m.playFrom)
        assertTrue(m.playUrl!!.contains("m3u8"))
    }

    @Test
    fun `详情包在 list 数组中也能解析`() {
        val body = """{"list":[{"vod_id":9,"vod_name":"包裹影片","vod_play_url":"第1集${'$'}https://a/1.m3u8"}]}"""
        val m = CmsParser.parseDetail(body)
        assertNotNull(m)
        assertEquals("包裹影片", m!!.name)
    }

    @Test
    fun `详情直接是数组也能解析`() {
        val body = """[{"vod_id":7,"vod_name":"数组影片"}]"""
        val m = CmsParser.parseDetail(body)
        assertNotNull(m)
        assertEquals("数组影片", m!!.name)
    }

    @Test
    fun `缺少 id 或 name 的条目被丢弃`() {
        val body = """{"code":1,"list":[{"vod_name":"无ID"},{"vod_id":1},{"vod_id":2,"vod_name":"正常"}]}"""
        val page = CmsParser.parsePage(body)
        assertEquals(1, page.movies.size)
        assertEquals("正常", page.movies[0].name)
    }

    @Test
    fun `空响应与非法 JSON 不崩溃`() {
        assertEquals(false, CmsParser.parsePage(null).ok)
        assertEquals(false, CmsParser.parsePage("").ok)
        assertEquals(false, CmsParser.parsePage("not json").ok)
        assertEquals(false, CmsParser.parsePage("[1,2,3]").ok)
    }

    @Test
    fun `HTML 包裹的 CDATA 能剥离`() {
        val body = "<script><![CDATA[{\"code\":1,\"list\":[{\"vod_id\":1,\"vod_name\":\"CDATA影片\"}]}]]></script>"
        val stripped = CmsParser.stripHtmlWrapper(body)
        assertNotNull(stripped)
        val page = CmsParser.parsePage(stripped)
        assertTrue(page.ok)
        assertEquals("CDATA影片", page.movies[0].name)
    }

    @Test
    fun `HTML 页面被识别为非接口数据`() {
        val html = "<!DOCTYPE html><html><head><title>x</title></head><body>hi</body></html>"
        assertEquals(null, CmsParser.stripHtmlWrapper(html))
    }

    @Test
    fun `识别 CMS 响应`() {
        assertTrue(CmsParser.looksLikeCms("""{"code":1,"list":[]}"""))
        assertTrue(CmsParser.looksLikeCms("""{"pagecount":3}"""))
        assertEquals(false, CmsParser.looksLikeCms("<html></html>"))
        assertEquals(false, CmsParser.looksLikeCms(null))
    }

    @Test
    fun `零值年份视为空`() {
        val body = """{"code":1,"list":[{"vod_id":1,"vod_name":"A","vod_year":"0","vod_area":"0"}]}"""
        val page = CmsParser.parsePage(body)
        assertEquals(null, page.movies[0].year)
        assertEquals(null, page.movies[0].area)
    }

    // ---------------- 苹果CMS XML 形态（老版 api/xml.php） ----------------

    /** XML 列表响应：分页属性、CDATA 名称、dd 播放线路都要映射到 JSON 分支同构字段 */
    @Test
    fun `XML 列表响应被解析为同构结构`() {
        val body = """
        <rss version="1.0">
          <list page="2" pagecount="9" pagesize="20" recordcount="180">
            <video>
              <id>101</id>
              <tid>1</tid>
              <name><![CDATA[测试影片]]></name>
              <type>电影</type>
              <pic>https://a.com/1.jpg</pic>
              <lang>国语</lang>
              <area>大陆</area>
              <year>2024</year>
              <note>更新至第3集</note>
              <des><![CDATA[简介文本]]></des>
              <dd flag="高清"><![CDATA[第1集${'$'}https://a.com/1.m3u8#第2集${'$'}https://a.com/2.m3u8]]></dd>
              <dd flag="流畅"><![CDATA[第1集${'$'}https://b.com/1.m3u8]]></dd>
            </video>
          </list>
          <class><ty id="1">电影</ty><ty id="2">剧集</ty></class>
        </rss>
        """.trimIndent()
        val page = CmsParser.parsePage(body)
        assertTrue("XML 列表应解析成功", page.ok)
        assertEquals(1, page.movies.size)
        assertEquals(2, page.page)
        assertEquals(9, page.pageCount)
        assertEquals(180, page.total)
        val m = page.movies[0]
        assertEquals("101", m.id)
        assertEquals("测试影片", m.name)
        assertEquals("更新至第3集", m.remark)
        assertEquals("高清" + "$$$" + "流畅", m.playFrom)
        assertTrue("播放地址应含两条线路", m.playUrl!!.contains("$$$"))
        assertTrue(m.playUrl!!.contains("https://a.com/1.m3u8"))
        assertTrue(m.playUrl!!.contains("https://b.com/1.m3u8"))
        assertEquals(2, page.classes.size)
        assertEquals("1", page.classes[0].typeId)
    }

    /** XML 详情响应：取第一个 video，dd 线路合并规则与列表一致 */
    @Test
    fun `XML 详情响应取首个 video`() {
        val body = """
        <rss>
          <list>
            <video>
              <id>77</id>
              <name><![CDATA[详情影片]]></name>
              <dd flag="云播"><![CDATA[第1集${'$'}https://a.com/1.m3u8]]></dd>
            </video>
          </list>
        </rss>
        """.trimIndent()
        val m = CmsParser.parseDetail(body)
        assertNotNull(m)
        assertEquals("77", m!!.id)
        assertEquals("云播", m.playFrom)
        assertTrue(m.playUrl!!.contains("https://a.com/1.m3u8"))
    }

    /** 非法 XML 不能崩溃，也不能被误判为成功 */
    @Test
    fun `非法 XML 不崩溃且判为失败`() {
        assertEquals(false, CmsParser.parsePage("<rss><list><video>").ok)
        assertEquals(null, CmsParser.parseDetail("<rss><list><video>"))
    }

    /** XXE 防护：带 DOCTYPE 的 XML 必须被拒（防止读 App 私有文件） */
    @Test
    fun `带 DOCTYPE 的 XML 被拒绝解析`() {
        val xxe = """<?xml version="1.0"?>
        <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <rss><list><video><id>1</id><name>&xxe;</name></video></list></rss>
        """.trimIndent()
        assertEquals(false, CmsParser.parsePage(xxe).ok)
        assertEquals(null, CmsParser.parseDetail(xxe))
    }

    /**
     * 回归：stripHtmlWrapper 必须原样放行 XML。
     *
     * 真机踩坑：苹果CMS XML 的 name/des 必带 `<![CDATA[...]]>`，
     * 而 stripHtmlWrapper 的 CDATA 正则会把整个 XML 文档替换成内层文本
     * （`<rss>...<![CDATA[美少女]]></rss>` → `美少女`），
     * 导致 XML 站点永远解析不出片单。
     */
    @Test
    fun `stripHtmlWrapper 原样放行 XML 文档`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
        <rss version="1.0"><list page="1"><video>
          <id>1</id><name><![CDATA[测试名]]></name>
          <dd flag="线路"><![CDATA[第1集${'$'}https://a.com/1.m3u8]]></dd>
        </video></list></rss>""".trimIndent()
        val stripped = CmsParser.stripHtmlWrapper(xml)
        assertEquals("XML 必须原样放行", xml, stripped)
        // 放行后还能正确解析出内容（端到端验证）
        val page = CmsParser.parsePage(stripped)
        assertTrue(page.ok)
        assertEquals("测试名", page.movies[0].name)
    }

    /** stripHtmlWrapper 的 XML 判定口径 */
    @Test
    fun `looksLikeXml 识别 XML 头与 rss 根`() {
        assertTrue(CmsParser.looksLikeXml("""<?xml version="1.0"?><rss/>"""))
        assertTrue(CmsParser.looksLikeXml("  <rss version=\"1.0\"></rss>"))
        assertEquals(false, CmsParser.looksLikeXml("""{"code":1}"""))
        assertEquals(false, CmsParser.looksLikeXml("""<script><![CDATA[{json}]]></script>"""))
    }
}
