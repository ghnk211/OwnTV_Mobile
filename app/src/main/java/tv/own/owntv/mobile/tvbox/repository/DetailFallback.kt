package tv.own.owntv.mobile.tvbox.repository

import tv.own.owntv.mobile.tvbox.engine.Movie

/**
 * 列表数据 → 详情页的过渡与兜底（解决"点进详情和列表看到的不一样"）。
 *
 * ## 问题现象
 * 列表页点进详情，海报/年份/地区会变样，甚至集数与角标矛盾
 * （实测：列表角标写"正片"，详情却给出 218 集）。
 *
 * ## 根因有两层
 *
 * ### 一层：详情页没有用列表已有的数据，等于"重新查一遍"
 * 列表接口（`ac=list`）与详情接口（`ac=detail`）是源的两个不同实现，
 * 字段完整度经常不一致：列表给全了封面，详情漏了；或者反过来。
 * 更糟的是有些源详情接口返回的条目**和列表那条根本不是同一部**（排序/推荐位错位）。
 *
 * 现在详情页只拿到 `siteKey` + `vodId` 就重新请求，
 * 于是详情页显示什么完全由详情接口决定 —— 与用户刚点的卡片无关。
 *
 * ### 二层：源数据本身自相矛盾
 * 角标说"正片"（单片）但 `vod_play_url` 有 218 集。这种源数据质量问题
 * 客户端无法修正，但**必须让用户看见矛盾**，否则会以为 App 坏了。
 *
 * ## 本工具的职责
 * 1. **过渡**：详情加载中先用列表数据渲染，用户立刻看到自己点的那张卡
 * 2. **兜底**：详情缺字段时用列表值补（封面/年份/地区/简介）
 * 3. **矛盾检测**：把"角标说单片但有多集"这类矛盾标出来，UI 上给提示
 */
object DetailFallback {

    /**
     * 用列表数据补齐详情缺失的字段。
     *
     * 规则：**详情有的字段一律优先**（它更准、字段更全），
     * 只有详情缺失时才回落列表值。这样既不丢详情信息，又能修掉"详情漏封面"的情况。
     */
    fun merge(detail: Movie, fromList: Movie?): Movie {
        if (fromList == null) return detail
        return detail.copy(
            // 详情常见缺失项，逐一用列表值补
            pic = detail.pic?.takeIf { it.isNotBlank() } ?: fromList.pic,
            remark = detail.remark?.takeIf { it.isNotBlank() } ?: fromList.remark,
            year = detail.year?.takeIf { it.isNotBlank() } ?: fromList.year,
            area = detail.area?.takeIf { it.isNotBlank() } ?: fromList.area,
            actor = detail.actor?.takeIf { it.isNotBlank() } ?: fromList.actor,
            director = detail.director?.takeIf { it.isNotBlank() } ?: fromList.director,
            // 简介：详情常有 HTML，列表的纯文本可作兜底
            content = detail.content?.takeIf { it.isNotBlank() } ?: fromList.content,
            // 名称与 id 是身份，绝不能被列表值覆盖 —— 但**空名字没有身份可保护**：
            // drpy 的 js: 内联二级（$js.toString）常只回播放线路不回 vod_name
            // （DJ音乐[js] 实证），此时详情条目名字是空串，回落列表值才是对的
            // （"详情返回了另一部"另有 DetailViewModel 的 mismatch 警告专门拦截）。
            name = detail.name.ifBlank { fromList.name },
            id = detail.id,
        )
    }

    /**
     * 检测源数据的自相矛盾。
     *
     * 目前只查一种（也是最常见、最容易被用户当成 App bug 的）：
     * **角标声明"正片/电影/单片"，却解析出多集**。
     *
     * 命中时返回提示文案，不命中返回 null。
     * 刻意只报"确定是矛盾"的，不做过度推断 —— 宁可漏报也不要给用户错误警告。
     */
    fun detectConflict(movie: Movie, totalEpisodes: Int): String? {
        if (totalEpisodes <= 1) return null
        val remark = movie.remark?.trim().orEmpty()
        if (remark.isEmpty()) return null

        // 角标里出现这些词，说明源自己声明这是"一整部"而不是"分集"
        val singleMovieHints = listOf("正片", "电影", "高清", "BD", "蓝光", "完整版", "未删减")
        // "更新至第12集"这类反而是正常的分集标记，不算矛盾
        val episodicHints = listOf("集", "话", "期", "连载", "更新")

        val looksSingleMovie = singleMovieHints.any { remark.contains(it, ignoreCase = true) }
        val looksEpisodic = episodicHints.any { remark.contains(it) }

        return if (looksSingleMovie && !looksEpisodic) {
            "源数据异常：角标标注「$remark」但解析出 $totalEpisodes 集，实际可播放内容以本页为准"
        } else null
    }
}
