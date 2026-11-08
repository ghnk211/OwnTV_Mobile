package tv.own.owntv.mobile.tvbox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * playUrl 字符串协议测试（方案 5.2 格式约定）。
 */
class PlayUrlCodecTest {

    @Test
    fun `解析单线路`() {
        val lines = PlayUrlCodec.parse(
            "第01集${'$'}https://a.com/1.m3u8#第02集${'$'}https://a.com/2.m3u8",
            "云播",
        )
        assertEquals(1, lines.size)
        assertEquals("云播", lines[0].name)
        assertEquals(2, lines[0].episodeCount)
        assertEquals("第01集", lines[0].episodes[0].name)
        assertEquals("https://a.com/1.m3u8", lines[0].episodes[0].url)
    }

    @Test
    fun `解析多线路`() {
        val lines = PlayUrlCodec.parse(
            "第1集${'$'}https://a.com/1.m3u8#第2集${'$'}https://a.com/2.m3u8${'$'}${'$'}${'$'}第1集${'$'}https://b.com/1.m3u8",
            "线路A${'$'}${'$'}${'$'}线路B",
        )
        assertEquals(2, lines.size)
        assertEquals("线路A", lines[0].name)
        assertEquals("线路B", lines[1].name)
        assertEquals(1, lines[1].episodeCount)
    }

    @Test
    fun `无 playFrom 时自动命名线路`() {
        val lines = PlayUrlCodec.parse("第1集${'$'}https://a.com/1.m3u8${'$'}${'$'}${'$'}第1集${'$'}https://b.com/1.m3u8", null)
        assertEquals(2, lines.size)
        assertEquals("线路1", lines[0].name)
        assertEquals("线路2", lines[1].name)
    }

    @Test
    fun `缺少名称分隔符时自动补集名`() {
        val lines = PlayUrlCodec.parse("https://a.com/1.m3u8#https://a.com/2.m3u8", "线路")
        assertEquals(2, lines[0].episodeCount)
        assertEquals("第1集", lines[0].episodes[0].name)
        assertEquals("https://a.com/1.m3u8", lines[0].episodes[0].url)
    }

    @Test
    fun `空值返回空列表`() {
        assertTrue(PlayUrlCodec.parse(null, "x").isEmpty())
        assertTrue(PlayUrlCodec.parse("", "x").isEmpty())
        assertTrue(PlayUrlCodec.parse("   ", "x").isEmpty())
    }

    @Test
    fun `跳过空片段`() {
        val lines = PlayUrlCodec.parse("第1集${'$'}https://a.com/1.m3u8###第2集${'$'}https://a.com/2.m3u8", "线路")
        assertEquals(2, lines[0].episodeCount)
    }

    @Test
    fun `去除换行符`() {
        val raw = "第1集${'$'}https://a.com/1.m3u8\r\n#第2集${'$'}https://a.com/2.m3u8"
        val lines = PlayUrlCodec.parse(PlayUrlCodec.sanitize(raw), "线路")
        assertEquals(2, lines[0].episodeCount)
    }

    @Test
    fun `线路名数量不匹配时兜底命名`() {
        // playFrom 只有 1 个名字，但有 2 条线路
        val lines = PlayUrlCodec.parse(
            "第1集${'$'}https://a.com/1.m3u8${'$'}${'$'}${'$'}第1集${'$'}https://b.com/1.m3u8",
            "唯一线路",
        )
        assertEquals("唯一线路", lines[0].name)
        assertEquals("线路2", lines[1].name)
    }

    @Test
    fun `地址含美元符号只切一次`() {
        val lines = PlayUrlCodec.parse("正片${'$'}https://a.com/p?x=1&y=${'$'}2", "线路")
        assertEquals("https://a.com/p?x=1&y=${'$'}2", lines[0].episodes[0].url)
    }

    // ---------------- 集数编号与下标空洞（真机 bug：集名跳号、详情页与播放页错位） ----------------

    @Test
    fun `丢片段后集名不跳号`() {
        // 夹了空片段、以及"有名字但地址为空"的片段（采集站脏数据常见形态）
        val lines = PlayUrlCodec.parse(
            "https://a.com/1.m3u8###https://a.com/2.m3u8#第3集${'$'}",
            "线路",
        )
        val eps = lines[0].episodes
        assertEquals(2, eps.size)
        // 生成的集名必须按**有效条目**计数，不能按 split 位置：
        // 按下标计会产出「第1集、第4集」，用户看到的就是集数不完整
        assertEquals("第1集", eps[0].name)
        assertEquals("第2集", eps[1].name)
    }

    @Test
    fun `Episode 下标与列表位置严格一致`() {
        val eps = PlayUrlCodec.parse(
            "https://a.com/1.m3u8###https://a.com/2.m3u8#第3集${'$'}",
            "线路",
        )[0].episodes
        // index 一旦有空洞，`episodes.getOrNull(index)` 就会取到 null 或别的集，
        // 详情页传 index、播放页按下标取，两边坐标系不同 → 播错集
        eps.forEachIndexed { pos, ep -> assertEquals(pos, ep.index) }
    }

    // ---------------- 源数据倒序归一（对齐 TVBoxOS VodInfo.setVideo / isReverse） ----------------

    @Test
    fun `源返回倒序时自动归一成正序`() {
        val eps = PlayUrlCodec.parse(
            "第3集${'$'}https://a.com/3.m3u8#第2集${'$'}https://a.com/2.m3u8#第1集${'$'}https://a.com/1.m3u8",
            "线路",
        )[0].episodes
        assertEquals(listOf("第1集", "第2集", "第3集"), eps.map { it.name })
        assertEquals("https://a.com/1.m3u8", eps[0].url)
    }

    @Test
    fun `源本来就是正序时不动`() {
        val eps = PlayUrlCodec.parse(
            "第1集${'$'}https://a.com/1.m3u8#第2集${'$'}https://a.com/2.m3u8#第3集${'$'}https://a.com/3.m3u8",
            "线路",
        )[0].episodes
        assertEquals(listOf("第1集", "第2集", "第3集"), eps.map { it.name })
    }

    @Test
    fun `集名不含数字时不做倒序判定`() {
        // HD/SD 这类清晰度线没有集号可比，误判会把用户认得的顺序翻掉
        val eps = PlayUrlCodec.parse(
            "HD${'$'}https://a.com/hd.m3u8#SD${'$'}https://a.com/sd.m3u8",
            "线路",
        )[0].episodes
        assertEquals(listOf("HD", "SD"), eps.map { it.name })
    }

    @Test
    fun `线路数超过阈值时不做倒序判定`() {
        // 参考项目把归一限定在 ≤5 条线路：多线路通常是不同清晰度/轮播而非分集，
        // 翻转会破坏线路之间的原有排序逻辑
        val oneLine = (3 downTo 1).joinToString("#") { "第${it}集${'$'}https://a.com/${it}.m3u8" }
        val playUrl = (1..6).joinToString("$$$") { oneLine }
        val lines = PlayUrlCodec.parse(playUrl, (1..6).joinToString("$$$") { "线路$it" })
        assertEquals("第3集", lines[0].episodes[0].name)
    }

    @Test
    fun `两位数集号倒序能识别`() {
        // 连载剧常见 40→1；抽数字必须是整段数字，不能被当成 4 与 0 比较
        val src = (40 downTo 35).joinToString("#") { "第${it}集${'$'}https://a.com/${it}.m3u8" }
        val eps = PlayUrlCodec.parse(src, "线路")[0].episodes
        assertEquals("第35集", eps.first().name)
        assertEquals("第40集", eps.last().name)
    }

    // ---------- 至臻4K（csp_PanWebShare / Pan 网盘 spider）真实输出格式 ----------
    //
    // spider 的 vod_play_url 形如（merge/b/w.p）：
    //   [Y]第01集$[Y]第01集__<fid>+<pwd>+<fileId>#[Y]第02集$...
    // 集名带「[目录名]」前缀（merge/i/a.c() = "[" + 清洗后的目录名 + "]" + 文件名），
    // 前缀里可能带数字（如 [4K]、[2024]）——取数字判序时必须跳过它，
    // 否则所有集都取到同一个数，倒序判定失效。

    /** 按 spider 格式拼一条 vod_play_url */
    private fun panWebShareUrl(
        names: List<String>,
        dirTag: String = "[Y]",
    ): String = names.joinToString("#") { n ->
        "$dirTag$n\$${dirTag}${n}__fid123+pwd456+file789"
    }
    @Test
    fun `网盘 spider 格式的集名能完整解析`() {
        val eps = PlayUrlCodec.parse(panWebShareUrl((1..12).map { "第%02d集".format(it) }), "夸克网盘")[0].episodes
        assertEquals(12, eps.size)
        // 集名必须是完整的「[Y]第01集」，不是被截断的 "[Y"
        assertEquals("[Y]第01集", eps[0].name)
        assertEquals("[Y]第12集", eps[11].name)
        // URL 部分完整
        assertTrue(eps[0].url.startsWith("[Y]第01集__"))
    }

    @Test
    fun `目录前缀带数字时倒序判定仍准确`() {
        // [4K] 前缀：取数字必须取到集号（01）而不是前缀里的 4
        val eps = PlayUrlCodec.parse(panWebShareUrl((1..12).map { "第%02d集".format(it) }, "[4K]"), "夸克网盘")[0].episodes
        assertEquals("[4K]第01集", eps[0].name)
        assertEquals("[4K]第12集", eps[11].name)
    }

    @Test
    fun `网盘 spider 降序源能自动翻正`() {
        val eps = PlayUrlCodec.parse(panWebShareUrl((40 downTo 31).map { "第%02d集".format(it) }), "夸克网盘")[0].episodes
        assertEquals("[Y]第31集", eps.first().name)
        assertEquals("[Y]第40集", eps.last().name)
    }

    @Test
    fun `目录前缀带数字的降序源也能自动翻正`() {
        // [4K] 前缀：若从字符串头取数字，每集都取到 4 → 相邻比较全相等 → 翻不了
        val eps = PlayUrlCodec.parse(
            panWebShareUrl((40 downTo 31).map { "第%02d集".format(it) }, "[4K]"),
            "夸克网盘",
        )[0].episodes
        assertEquals("[4K]第31集", eps.first().name)
        assertEquals("[4K]第40集", eps.last().name)
    }

    @Test
    fun `后缀标签不影响取集号`() {
        // 第01集[HD] 这种后缀标签不能把集号吞掉
        val eps = PlayUrlCodec.parse(
            panWebShareUrl((40 downTo 31).map { "第%02d集[HD]".format(it) }),
            "夸克网盘",
        )[0].episodes
        assertEquals("第31集[HD]", eps.first().name.substringAfter("]"))
    }

        // ---------- 选集显示名：剥公共前缀 ----------

        private fun epsOf(names: List<String>) = names.mapIndexed { i, n ->
            tv.own.owntv.mobile.tvbox.engine.Episode(i, n, "https://a.com/$i", "flag")
        }

        @Test
        fun `网盘长集名剥掉公共前缀后可区分`() {
            // 至臻4K 实测格式：12 集只差 S01Exx，不剥的话格子全长得一样
            val names = (1..12).map { "[Y 玉-簟-秋]The·Lament·of·Autumn·2026·S01E%02d·2160p·WEB-DL.H265".format(it) }
            val shown = episodeDisplayNames(epsOf(names))
            assertEquals("S01E01·2160p·WEB-DL.H265", shown[0])
            assertEquals("S01E12·2160p·WEB-DL.H265", shown[11])
            // 12 个显示名互不相同
            assertEquals(12, shown.distinct().size)
        }

        @Test
        fun `普通短集名不动`() {
            val shown = episodeDisplayNames(epsOf(listOf("第01集", "第02集", "第03集")))
            assertEquals(listOf("第01集", "第02集", "第03集"), shown)
        }

        @Test
        fun `前缀太短不剥`() {
            // 公共前缀只有「第」一个字，剥了变成 01集/02集 反而难认
            val shown = episodeDisplayNames(epsOf(listOf("第01集", "第02集")))
            assertEquals(listOf("第01集", "第02集"), shown)
        }

        @Test
        fun `全部同名时保留原名`() {
            val shown = episodeDisplayNames(epsOf(listOf("正片", "正片")))
            assertEquals(listOf("正片", "正片"), shown)
        }

        @Test
        fun `单集不做处理`() {
            val shown = episodeDisplayNames(epsOf(listOf("[Y]第01集")))
            assertEquals(listOf("[Y]第01集"), shown)
        }

        @Test
        fun `前缀切在 token 中间会回退到分隔符`() {
            // S01E0 是公共前缀但不能切一半 —— 回退到 · 之后，保留完整 S01Exx
            val names = (1..3).map { "影片名·S01E%02d·1080p".format(it) }
            val shown = episodeDisplayNames(epsOf(names))
            assertEquals("S01E01·1080p", shown[0])
            assertEquals("S01E03·1080p", shown[2])
}
        @Test
        fun `线路名剥掉内部索引后缀`() {
            // 网盘 spider 的 from 是「选项名#序号」，序号对用户无意义
            val lines = PlayUrlCodec.parse(panWebShareUrl((1..3).map { "第%02d集".format(it) }), "百度原画#1")
            assertEquals("百度原画", lines[0].name)
        }

        @Test
        fun `线路名无索引时不动`() {
            val lines = PlayUrlCodec.parse(panWebShareUrl((1..3).map { "第%02d集".format(it) }), "高清线路")
            assertEquals("高清线路", lines[0].name)
    }

}
