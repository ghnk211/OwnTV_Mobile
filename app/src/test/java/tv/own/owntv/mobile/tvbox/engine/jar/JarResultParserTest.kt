package tv.own.owntv.mobile.tvbox.engine.jar

import com.github.catvod.bean.Class
import com.github.catvod.bean.Movie
import com.github.catvod.bean.Play
import com.github.catvod.bean.Result
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jar 返回值归一化测试（方案 5.2 / 5.3）。
 *
 * 重点覆盖 jar 生态里真实存在的**四种返回形态**与**字段类型不一致**：
 * - bean.Result / Gson JsonObject / Map / JSON 字符串
 * - vod_id 可能是 Int / Long / String，vod_pic 可能是 String 或空
 */
class JarResultParserTest {

    // ---------------- bean 形态（老协议） ----------------

    @Test
    fun `bean Result 归一化为 SpiderResult`() {
        val result = Result().apply {
            classes = listOf(
                Class().apply { type_id = "1"; type_name = "电影" },
                Class().apply { type_id = "2"; type_name = "剧集" },
            )
            list = listOf(
                Movie().apply {
                    vod_id = 100
                    vod_name = "流浪地球"
                },
            )
            page = 2
            pagecount = 5
            total = 120
        }

        val r = JarResultParser.toResult(result)!!

        assertEquals(2, r.classes.size)
        assertEquals("1", r.classes[0].typeId)
        assertEquals("电影", r.classes[0].typeName)
        assertEquals(1, r.list.size)
        assertEquals("100", r.list[0].id)
        assertEquals("流浪地球", r.list[0].name)
        assertEquals(2, r.page)
        assertEquals(5, r.pagecount)
        assertTrue(r.hasMore)
    }

    @Test
    fun `vod_id 为 Long 时也能字符串化`() {
        val m = Movie().apply {
            vod_id = 9_007_199_254_740_993L // 超出 Int 范围
            vod_name = "长整型ID"
        }
        val r = JarResultParser.toMovie(m)!!
        assertEquals("9007199254740993", r.id)
    }

    @Test
    fun `vod_id 为 Double 时取整不出现小数尾巴`() {
        val m = Movie().apply { vod_id = 123.0; vod_name = "浮点ID" }
        assertEquals("123", JarResultParser.toMovie(m)!!.id)
    }

    @Test
    fun `bean 字段全为 Any 时不同类型都能读出字符串`() {
        val m = Movie().apply {
            vod_id = 7
            vod_name = "类型混搭"
            vod_remarks = 12          // 数字角标
            vod_year = 2024
            vod_pic = "https://img.com/a.jpg"
        }
        val r = JarResultParser.toMovie(m)!!
        assertEquals("12", r.remark)
        assertEquals("2024", r.year)
        assertEquals("https://img.com/a.jpg", r.pic)
    }

    // ---------------- Gson JsonObject 形态（新协议） ----------------

    @Test
    fun `Gson JsonObject 归一化`() {
        val json = """
            {
              "classes": [{"type_id":"1","type_name":"电影"}],
              "list": [{"vod_id":"abc","vod_name":"测试","vod_pic":"/cover.jpg"}],
              "page":1,"pagecount":1,"total":1
            }
        """.trimIndent()
        val obj = JsonParser.parseString(json).asJsonObject

        val r = JarResultParser.toResult(obj, "https://img.com")!!

        assertEquals(1, r.classes.size)
        assertEquals("abc", r.list[0].id)
        assertEquals("测试", r.list[0].name)
        // 相对路径封面应被补成绝对地址
        assertEquals("https://img.com/cover.jpg", r.list[0].pic ?: "")
    }

    @Test
    fun `Gson 嵌套数组转 map 后 list 可读`() {
        val json = """
            {"list":[{"vod_id":1,"vod_name":"A"},
                     {"vod_id":2,"vod_name":"B"}]}
        """.trimIndent()
        val r = JarResultParser.toResult(JsonParser.parseString(json).asJsonObject)!!
        assertEquals(2, r.list.size)
        assertEquals(listOf("A", "B"), r.list.map { it.name })
    }

    // ---------------- 分类字段名：生态用 `class`（单数） ----------------

    @Test
    fun `生态标准 class 单数键能解析出分类`() {
        // 真机回归（2026-10-14 newwex.json）：TVBoxOS AbsSortJson 用
        // `@SerializedName("class")`，即 jar 返回 JSON 里分类数组的键是 **class（单数）**。
        // 早前只读 classes（复数）→ jar 源分类永远为空 → 首页只剩"全部"
        // 且误报"没有首页浏览内容"。
        val json = """
            {
              "class": [
                {"type_id":"1","type_name":"电影"},
                {"type_id":"2","type_name":"剧集"}
              ],
              "list": [{"vod_id":"a","vod_name":"片"}]
            }
        """.trimIndent()
        val r = JarResultParser.toResult(JsonParser.parseString(json).asJsonObject)!!
        assertEquals("class 单数键必须能解析出分类", 2, r.classes.size)
        assertEquals("电影", r.classes[0].typeName)
        assertEquals("剧集", r.classes[1].typeName)
    }

    @Test
    fun `classes 复数键仍兼容`() {
        // 个别魔改 jar 用复数；两个键都要认，不许二选一
        val json = """
            {"classes":[{"type_id":"1","type_name":"电影"}],"list":[]}
        """.trimIndent()
        val r = JarResultParser.toResult(JsonParser.parseString(json).asJsonObject)!!
        assertEquals(1, r.classes.size)
    }

    @Test
    fun `class 与 classes 同时存在时优先 class`() {
        val json = """
            {
              "class": [{"type_id":"1","type_name":"生态标准"}],
              "classes": [{"type_id":"9","type_name":"魔改"}],
              "list": []
            }
        """.trimIndent()
        val r = JarResultParser.toResult(JsonParser.parseString(json).asJsonObject)!!
        assertEquals("应优先取生态标准的 class（单数）", "生态标准", r.classes[0].typeName)
    }

    @Test
    fun `只有分类没有推荐列表时仍产出分类`() {
        // 这正是"分类全有但首页无推荐"的源：不该被当成"没有首页浏览内容"
        val json = """
            {"class":[{"type_id":"1","type_name":"电影"},{"type_id":"2","type_name":"动漫"}],"list":[]}
        """.trimIndent()
        val r = JarResultParser.toResult(JsonParser.parseString(json).asJsonObject)!!
        assertEquals(2, r.classes.size)
        assertEquals(0, r.list.size)
    }

    // ---------------- Map 形态 ----------------

    @Test
    fun `Map 形态归一化`() {
        val raw = mapOf(
            "classes" to listOf(mapOf("type_id" to "3", "type_name" to "综艺")),
            "list" to listOf(
                mapOf("vod_id" to "m1", "vod_name" to "节目1"),
                mapOf("vod_id" to "m2", "vod_name" to "节目2"),
            ),
            "page" to "1",   // 字符串页码也要认
            "pagecount" to "3",
        )

        val r = JarResultParser.toResult(raw)!!

        assertEquals(1, r.classes.size)
        assertEquals(2, r.list.size)
        assertEquals("1", r.page.toString())
        assertEquals(3, r.pagecount)
    }

    // ---------------- JSON 字符串形态 ----------------

    @Test
    fun `JSON 字符串形态归一化`() {
        val raw = """{"list":[{"vod_id":"s1","vod_name":"字符串源"}]}"""
        val r = JarResultParser.toResult(raw)!!
        assertEquals("字符串源", r.list[0].name)
    }

    @Test
    fun `非 JSON 字符串返回 null`() {
        assertNull(JarResultParser.toResult("这不是 JSON"))
        assertNull(JarResultParser.toResult(""))
        assertNull(JarResultParser.toResult(null))
    }

    // ---------------- 播放结果 ----------------

    @Test
    fun `Play bean 归一化为 PlayResult`() {
        val play = Play().apply {
            parse = 0
            url = "https://v.com/index.m3u8"
            flag = "云播"
            header = "User-Agent: okhttp\nReferer: https://v.com"
        }
        val r = JarResultParser.toPlayResult(play)!!

        assertEquals(0, r.parse)
        assertTrue(r.isDirect)
        assertEquals("https://v.com/index.m3u8", r.url)
        assertEquals("云播", r.flag)
        // 换行分隔的 header 串要被拆成 map
        assertEquals("okhttp", r.header?.get("User-Agent")?.toString()?.trim('"'))
    }

    @Test
    fun `播放结果 header 为 Map 时直接透传`() {
        val raw = mapOf(
            "url" to "https://v.com/a.mp4",
            "parse" to 1,
            "header" to mapOf("UA" to "TestAgent"),
        )
        val r = JarResultParser.toPlayResult(raw)!!
        assertEquals(1, r.parse)
        assertTrue(r.needSniff)
        assertEquals("TestAgent", r.header?.get("UA")?.toString()?.trim('"'))
    }

    @Test
    fun `播放结果缺 url 返回 null`() {
        assertNull(JarResultParser.toPlayResult(mapOf("parse" to 0)))
    }

    @Test
    fun `播放结果 urls 列表被提取`() {
        val raw = mapOf(
            "url" to "https://v.com/a.m3u8",
            "urls" to listOf("https://v.com/b.m3u8", "https://v.com/c.m3u8"),
        )
        val r = JarResultParser.toPlayResult(raw)!!
        assertEquals(2, r.urls.size)
    }

    // ---------------- playUrl 协议贯通 ----------------

    @Test
    fun `多线路 playUrl 归一化后保持原协议`() {
        val playUrl = "第01集\$https://a.com/1.m3u8#第02集\$https://a.com/2.m3u8\$\$\$线路B" +
            "\$第01集\$https://b.com/1.m3u8"
        val m = Movie().apply {
            vod_id = "x"
            vod_name = "多线路"
            vod_play_from = "线路A\$\$\$线路B"
            vod_play_url = playUrl
        }

        val r = JarResultParser.toMovie(m)!!

        assertEquals("线路A\$\$\$线路B", r.playFrom)
        assertEquals(playUrl, r.playUrl)
        assertTrue(r.hasPlayLines)
    }

    @Test
    fun `vod_pic_slide 作为封面兜底`() {
        val m = Movie().apply {
            vod_id = "s"
            vod_name = "只有横图"
            vod_pic_slide = "https://img.com/slide.jpg"
        }
        assertEquals("https://img.com/slide.jpg", JarResultParser.toMovie(m)!!.pic)
    }

    @Test
    fun `桩 bean 具备生态常用的扩展字段`() {
        // 回归：真机实测 csp_Nmvod 赋值 vod_pic_slide 时，若桩 bean 缺该字段会直接
        // NoSuchFieldError（parent-first 下 jar 用的就是宿主桩类）。新增 jar 生态字段时
        // 必须同步在 com.github.catvod.bean.Movie 里补桩，否则运行期才炸。
        val m = Movie().apply {
            vod_id = "1"
            vod_name = "扩展字段"
            vod_pic_slide = "https://img.com/s.jpg"
            vod_descr = "<p>简介</p>"
        }
        val r = JarResultParser.toMovie(m)!!
        assertEquals("https://img.com/s.jpg", r.pic)
        assertEquals("简介", r.content)
    }

    @Test
    fun `Java Bean 走公有字段反射读出字段`() {
        // 回归：早期 asMap 只处理 Gson JsonObject，导致 jar 用 bean.Result 返回时
        // 所有字段读不到（整页空白）。这条用例锁死"桩 bean 形态"这条主路径。
        val result = Result().apply {
            page = 3
            pagecount = 9
            list = listOf(
                Movie().apply { vod_id = "b1"; vod_name = "影片A" },
                Movie().apply { vod_id = "b2"; vod_name = "影片B" },
            )
        }
        val r = JarResultParser.toResult(result)!!
        assertEquals(3, r.page)
        assertEquals(9, r.pagecount)
        assertEquals(listOf("影片A", "影片B"), r.list.map { it.name })
    }

    @Test
    fun `缺关键字段的影片被丢弃`() {
        assertNull(JarResultParser.toMovie(mapOf("vod_name" to "没有id")))
        assertNull(JarResultParser.toMovie(mapOf("vod_id" to "1")))
        assertNull(JarResultParser.toMovie("不是bean"))
    }

    /**
     * drpy 的 `js:` 内联二级（`$js.toString`）走 `vod = VOD` 分支，**不做字段回填** ——
     * 源只写 `vod_play_from` / `vod_play_url` 时产物里就没有 `vod_name`（DJ音乐[js] 实证）。
     * 详情路径必须放行，否则整条被丢，详情页直接报「未返回详情数据」。
     */
    @Test
    fun `详情路径允许缺名称但列表路径不放宽`() {
        val detailOnly = mapOf(
            "vod_id" to "https://www.djuu.com/play/326664.html",
            "vod_play_from" to "在线播放",
            "vod_play_url" to "正片\$https://mp4.djuu.com/c115/166615/116/2026/b230f7285a3e4758.m4a",
        )
        // 列表路径：名字仍是硬性判据（避免出现无名空卡片）
        assertNull(JarResultParser.toMovie(detailOnly))

        val loose = JarResultParser.toMovie(detailOnly, requireName = false)!!
        assertEquals("", loose.name)
        assertEquals("在线播放", loose.playFrom)
        assertEquals(
            "正片\$https://mp4.djuu.com/c115/166615/116/2026/b230f7285a3e4758.m4a",
            loose.playUrl,
        )

        // toResult 侧同样：宽松时才保留这条
        val raw = mapOf("list" to listOf(detailOnly))
        assertEquals(0, JarResultParser.toResult(raw)!!.list.size)
        assertEquals(1, JarResultParser.toResult(raw, requireName = false)!!.list.size)
    }

    @Test
    fun `classes 项缺 type_name 时被跳过不影响其余`() {
        val raw = mapOf(
            "classes" to listOf(
                mapOf("type_id" to "1", "type_name" to "电影"),
                mapOf("type_id" to "2"),  // 缺 name
            ),
            "list" to emptyList<Any>(),
        )
        val r = JarResultParser.toResult(raw)!!
        assertEquals(1, r.classes.size)
    }

    // ---------------- 筛选树 ----------------

    @Test
    fun `filterDef 数组形态解析出筛选维度`() {
        val raw = mapOf(
            "filterDef" to mapOf(
                "1" to listOf(
                    mapOf("key" to "area", "name" to "地区", "value" to listOf("大陆", "香港")),
                ),
            ),
            "list" to emptyList<Any>(),
        )
        val filters = JarResultParser.toFilters(raw)
        assertNotNull(filters)
        val defs = filters!!["1"].orEmpty()
        assertTrue(defs.isNotEmpty())
        // 裸字符串数组：n 与 v 都取该字符串
        assertEquals("area", defs.first().key)
        assertEquals(listOf("大陆", "香港"), defs.first().values.map { it.n })
    }

    @Test
    fun `filterDef 标准 n v 形态`() {
        val raw = mapOf(
            "filters" to mapOf(
                "6" to listOf(
                    mapOf(
                        "key" to "class",
                        "name" to "剧情",
                        "value" to listOf(
                            mapOf("n" to "全部", "v" to ""),
                            mapOf("n" to "喜剧", "v" to "喜剧"),
                        ),
                    ),
                ),
            ),
        )
        val defs = JarResultParser.toFilters(raw)["6"].orEmpty()
        assertEquals(1, defs.size)
        assertEquals("class", defs[0].key)
        assertEquals("剧情", defs[0].name)
        assertEquals(listOf("全部" to "", "喜剧" to "喜剧"), defs[0].values.map { it.n to it.v })
    }

    @Test
    fun `filterDef 对象形态也解析`() {
        // 变体：维度是对象而不是数组，key 即参数名
        val raw = mapOf(
            "filterDef" to mapOf(
                "1" to mapOf(
                    "class" to mapOf("name" to "剧情", "value" to listOf("喜剧", "动作")),
                ),
            ),
        )
        val defs = JarResultParser.toFilters(raw)["1"].orEmpty()
        assertEquals(1, defs.size)
        assertEquals("class", defs[0].key)
        assertEquals(listOf("喜剧", "动作"), defs[0].values.map { it.v })
    }

    @Test
    fun `无筛选树返回空 map`() {
        assertTrue(JarResultParser.toFilters(mapOf("list" to emptyList<Any>())).isEmpty())
        assertTrue(JarResultParser.toFilters(null).isEmpty())
        // 有 filterDef 但没有任何可用维度 → 同样按"无筛选"处理，不该抛
        assertTrue(
            JarResultParser.toFilters(mapOf("filterDef" to mapOf("1" to emptyList<Any>()))).isEmpty()
        )
    }

    // ---------------- 诊断 ----------------

    @Test
    fun `describe 能给出可读诊断`() {
        val raw = mapOf("list" to listOf(1, 2, 3))
        val s = JarResultParser.describe(raw)
        assertTrue(s.contains("listSize=3"))
        assertTrue(JarResultParser.describe(null).contains("null"))
    }

    // ---------------- 桩契约（真机踩过的坑，做回归锁） ----------------

    @Test
    fun `桩 Spider 必须同时提供两个 init 重载`() {
        // 回归：真机实测 com.github.catvod.spider.Nmvod 调用 super.init(Context)，
        // 桩只有 init(Context, String) 时运行期直接
        // NoSuchMethodError: No super method init(Landroid/content/Context;)V —— 崩在 jar 里，
        // 宿主只看到 InvocationTargetException。两个签名缺一不可。
        val cls = com.github.catvod.crawler.Spider::class.java
        assertNotNull(
            "桩缺少 init(Context)，jar 实现类会 NoSuchMethodError",
            cls.getDeclaredMethod("init", android.content.Context::class.java),
        )
        assertNotNull(
            "桩缺少 init(Context, String)",
            cls.getDeclaredMethod("init", android.content.Context::class.java, String::class.java),
        )
    }

    @Test
    fun `桩 Spider 是抽象类且可被继承`() {
        // parent-first 委派的基石：jar 的实现类必须能 extends 宿主桩
        val cls = com.github.catvod.crawler.Spider::class.java
        assertTrue(java.lang.reflect.Modifier.isAbstract(cls.modifiers))
        assertNotNull(cls.getDeclaredConstructor())
    }

    // ---------------- JSON 数组元素归一化（回归） ----------------

    /**
     * 回归固化：字符串数组的元素此前会被抹成空 Map。
     *
     * 真机 bug：`gsonElementToMap` 对数组元素一律按 JsonObject 解析，
     * 遇到 `"vod_tag":["动作","悬疑"]` 这种字符串数组时，
     * `!isJsonObject → emptyMap()` → 元素语义全丢。
     * 表现为筛选项/标签整块数据静默消失，下游取不到值。
     */
    @Test
    fun `字符串数组的元素保持为字符串而非空 Map`() {
        val json = """{"vod_id":"1","vod_name":"测试","vod_tag":["动作","悬疑"]}"""
        val movie = JarResultParser.toMovie(json)!!
        assertEquals("测试", movie.name)
    }

    @Test
    fun `asMap 解析含混合类型数组的 JSON`() {
        val json = """{"list":["a",1,true,null,{"k":"v"},["n"]]}"""
        val map = JarResultParser.asMap(json)
        assertNotNull("混合数组不应导致整体解析失败", map)
        @Suppress("UNCHECKED_CAST")
        val list = map!!["list"] as? List<*>
        assertNotNull("list 应被解析为数组", list)
        assertEquals("混合数组应保留6 个元素", 6, list!!.size)
        assertEquals("a", list[0])
        assertEquals(1L, list[1])
        assertEquals(true, list[2])
        assertNull("null 元素应为 null 而不是字符串 \"null\"", list[3])
        assertTrue("对象元素应解析为 Map", list[4] is Map<*, *>)
    }

    @Test
    fun `对象数组元素解析为 Map 而非空`() {
        val json = """{"list":[{"vod_id":"a","vod_name":"甲"},{"vod_id":"b","vod_name":"乙"}]}"""
        val result = JarResultParser.toResult(json)!!
        assertEquals(2, result.list!!.size)
        assertEquals("甲", result.list!![0].name)
        assertEquals("乙", result.list!![1].name)
    }

    // ---------------- 详情缺 id（直播类源实测形态） ----------------

    /**
     * 虎牙/斗鱼/哔哩直播源的 `detailContent` 真实返回（2026-10-19 真机抓取）：
     * **没有 vod_id** —— 早前 id 是硬性判据，整条被丢弃，详情页直接报「未返回详情数据」。
     *
     * 用 `~` 占位再替换成 `$`：Kotlin 字符串模板会把 `$` 当变量引用，
     * 而 TVBox 的线路/集分隔符恰好是 `$$$` / `$`。
     */
    private val liveDetailJson = """
        {"list":[{"vod_name":"海南团播，6点抽18PM","vod_pic":"http://x/cover.jpg",
        "vod_remarks":"huya","vod_actor":"AmazingJ","vod_content":"https://www.huya.com/991108",
        "vod_play_from":"播放地址_阿里_flv~~~播放地址_腾讯_flv",
        "vod_play_url":"蓝光4M~Pw8KAp0mqW8i/3a64370X#超清~ab/cd"}]}
    """.trimIndent().replace('~', '$')

    @Test
    fun `详情缺 vod_id 时用 fallbackId 兜底而不是丢弃条目`() {
        val result = JarResultParser.toResult(
            liveDetailJson,
            requireName = false,
            fallbackId = "991108",
        )!!
        assertEquals("条目不应被丢弃", 1, result.list.size)
        assertEquals("991108", result.list[0].id)
        assertEquals("海南团播，6点抽18PM", result.list[0].name)
        // 线路必须原样保留：直播源的 url 段是密文，由 playerContent 解，
        // 此处任何"看起来不像直链就丢掉"的判断都会让播放链路断掉
        assertTrue(
            "vod_play_url 应完整保留",
            result.list[0].playUrl!!.contains("Pw8KAp0mqW8i"),
        )
        assertEquals(
            "播放地址_阿里_flv~~~播放地址_腾讯_flv".replace('~', '$'),
            result.list[0].playFrom,
        )
    }

    @Test
    fun `不传 fallbackId 时缺 id 的条目仍然丢弃（列表路径不受影响）`() {
        val result = JarResultParser.toResult(liveDetailJson, requireName = false)!!
        assertEquals("列表路径缺 id 仍应丢弃", 0, result.list.size)
    }
}