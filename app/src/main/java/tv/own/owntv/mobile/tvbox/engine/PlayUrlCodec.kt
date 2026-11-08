package tv.own.owntv.mobile.tvbox.engine

import tv.own.owntv.mobile.tvbox.util.TextUtil

/**
 * 播放线路/选集的 UI 视图模型（方案 5.2：不建独立实体，用字符串协议承载，UI 侧解析）。
 */
data class PlayLine(
    val index: Int,
    val name: String,
    val episodes: List<Episode>,
) {
    val episodeCount: Int get() = episodes.size
}

data class Episode(
    /**
     * 在 [PlayLine.episodes] 里的位置，**从 0 连续、无空洞**。
     *
     * 详情页把选中态、播放页的 `episodeIndex`、历史表的进度全都按这个数存取，
     * 一旦它来自 split 的原始位置（丢片段就会留空洞），同一个数字在两侧含义不同，
     * 表现为「点第 2 集却播第 4 集」「`getOrNull(index)` 取到 null」。
     */
    val index: Int,
    val name: String,
    val url: String,
    val flag: String,
) {
    /** 供 playerContent 使用的 id：源可能直接给直链，也可能给待解析标识 */
    val id: String get() = url
}

/**
 * 选集格子用的显示名：剥掉本线路所有集共享的公共前缀。
 *
 * 网盘 spider 的集名是「[目录名]影片名·年份·S01E01·清晰度…」这种长格式
 * （至臻4K 实测 `[Y 玉-簟-秋]The·Lament·of·Autumn·2026·S01E01·2160p·WE…`），
 * 公共前缀（目录 + 影片名）占掉大半宽度。手机格子只有 ~110dp，
 * 直接渲染时 12 个集截断后看起来**一模一样**，用户根本分不清点的是哪集
 * （正序/倒序点了也看不出变化）。剥掉共享前缀后剩下的
 * `S01E01·…`~`S01E12·…` 既有区分度又放得下。
 *
 * 保守规则（宁可不剥，不可剥错）：
 * - 只有 1 集、或没有公共前缀时原样返回
 * - 公共前缀回退到最后一个分隔符，避免把 `S01E0` 切一半、只剩 "1"/"2"
 * - 前缀短于 4 字符不剥（`第`、`[Y]` 这种剥了反而更难认）
 * - 剥完为空（所有集同名）时保留原名
 */
fun episodeDisplayNames(episodes: List<Episode>): List<String> {
    if (episodes.size < 2) return episodes.map { it.name }
    var prefix = episodes[0].name
    for (i in 1 until episodes.size) {
        prefix = commonPrefix(prefix, episodes[i].name)
        if (prefix.isEmpty()) break
    }
    if (prefix.length < MIN_STRIPPABLE_PREFIX) return episodes.map { it.name }
    return episodes.map { ep -> ep.name.removePrefix(prefix).ifEmpty { ep.name } }
}

/** 两个串的公共前缀，回退到最后一个分隔符（不把 token 切一半） */
private fun commonPrefix(a: String, b: String): String {
    var i = 0
    val n = minOf(a.length, b.length)
    while (i < n && a[i] == b[i]) i++
    var cut = i
    while (cut > 0 && a[cut - 1] !in NAME_SEPARATORS) cut--
    return if (cut > 0) a.substring(0, cut) else ""
}

/** 集名里的 token 分隔符（中英混排都要覆盖） */
private val NAME_SEPARATORS = charArrayOf('·', ' ', '.', ']', '-', '_', '第', '集', '话', '眷')

/** 公共前缀短于这个长度就不剥：`第`、`[Y]` 剥了反而更难认 */
private const val MIN_STRIPPABLE_PREFIX = 4

/**
 * playUrl 字符串协议工具。
 *
 * 格式约定（方案 5.2）：
 * - 线路分隔 `$$$`
 * - 集分隔 `#`
 * - 名称与地址分隔 `$`
 * 例：`第01集$https://xx/1.m3u8#第02集$https://xx/2.m3u8$$$第01集$https://yy/1.m3u8`
 */
object PlayUrlCodec {

    private const val LINE_SEP = "$$$"
    private const val EP_SEP = "#"
    private const val NAME_URL_SEP = '$'

    /** 只有线路很少（确实是分集）时才做倒序归一，阈值与参考项目一致 */
    private const val AUTO_REVERSE_MAX_LINES = 5

    /** 倒序判定最多探测多少对相邻集名 */
    private const val REVERSE_PROBE_PAIRS = 6

    /** 连续几票同向就下结论 */
    private const val REVERSE_VOTES = 2

    private val NUMBER = Regex("\\d+")

    /** 集名开头的 `[目录名]` 前缀（网盘 spider 格式，见 [firstNumber]） */
    private val LEADING_TAG = Regex("^\\[[^\\]]*\\]")

    /** 拆出线路 → 选集 */
    fun parse(playUrl: String?, playFrom: String?): List<PlayLine> {
        val urlPart = playUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lineNames = playFrom?.takeIf { it.isNotBlank() }
            ?.split(LINE_SEP)
            ?.map { TextUtil.normalizeName(it) }
            ?: emptyList()

        val pending = urlPart.split(LINE_SEP).mapIndexedNotNull { lineIdx, lineStr ->
            val line = lineStr.trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val raw = parseRawEpisodes(line)
            if (raw.isEmpty()) return@mapIndexedNotNull null
            val rawName = lineNames.getOrNull(lineIdx)?.takeIf { it.isNotBlank() }
            PendingLine(
                index = lineIdx,
                // 部分 spider 的线路名带内部索引后缀（网盘源实测「百度原画#1」），
                // 那是它给每条播放地址编号用的，对用户没有意义 —— 剥掉
                name = rawName?.let { cleanLineName(it) } ?: "线路${lineIdx + 1}",
                flag = lineNames.getOrNull(lineIdx).orEmpty(),
                raw = raw,
            )
        }

        // 归一只在「线路数少」时做：参考项目同样把 isReverse 限定在 ≤5 条线路，
        // 多线路通常是不同清晰度/备份源，各自顺序自有含义，翻转会把线路间排序逻辑打乱。
        val normalize = pending.size <= AUTO_REVERSE_MAX_LINES
        return pending.map { p ->
            val ordered = if (normalize && isSourceDescending(p.raw)) p.raw.asReversed() else p.raw
            PlayLine(
                index = p.index,
                name = p.name,
                episodes = ordered.mapIndexed { pos, (name, url) ->
                    // 集名必须到最后才补：先按 split 位置补名字，再翻转会「名字跑位」
                    Episode(
                        index = pos,
                        name = name ?: "第${pos + 1}集",
                        url = url,
                        flag = p.flag,
                    )
                },
            )
        }
    }

    /** 详情数据里的选集可能用 "$$$"/"#"/"$" 之外的分隔符，统一清洗一次 */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.replace("\r", "").replace("\n", "").trim().takeIf { it.isNotBlank() }
    }

    /**
     * 线路名清洗：剥掉尾部的 `#<数字>` 内部索引。
     *
     * 网盘 spider 的 `vod_play_from` 是「选项名#序号」逐条地址生成的
     * （`detailContentVodPlayFrom`：`option + "#" + i`），序号是它自己用来
     * 对地址编号的，用户在选线路上看到「百度原画#1」只会困惑。
     * 只剥尾部的纯数字索引，`线路#A` 这类带内容的标签不动。
     */
    private fun cleanLineName(name: String): String =
        name.replace(TRAILING_INDEX, "").trim().ifEmpty { name }

    /** 尾部 `#123` / `# 123` 索引 */
    private val TRAILING_INDEX = Regex("#\\s*\\d+\\s*$")

    /** 源没给集名时留 null，交给归一之后的统一补名 */
    private fun parseRawEpisodes(line: String): List<Pair<String?, String>> =
        line.split(EP_SEP).mapNotNull { epStr ->
            val s = epStr.trim()
            if (s.isEmpty()) return@mapNotNull null
            // 名称与地址用第一个 $ 分隔；地址本身可能含 $（少见但存在），故只切一次
            val sepIdx = s.indexOf(NAME_URL_SEP)
            val url = if (sepIdx < 0) s else s.substring(sepIdx + 1).trim()
            if (url.isEmpty()) return@mapNotNull null
            val name = if (sepIdx < 0) {
                null
            } else {
                TextUtil.normalizeName(s.substring(0, sepIdx)).takeIf { it.isNotEmpty() }
            }
            name to url
        }

    /**
     * 源返回的选集是不是「最新一集在前」。
     *
     * 连载剧采集站普遍按 40→1 给 `vod_play_url`。不识别的话，本项目的「正序」显示的是
     * 源序（其实是倒序）、点「倒序」又翻成正序 —— 按钮语义整体反了。
     * 判据沿用参考项目 TVBoxOS `VodInfo.isReverse`：抽集名里第一段数字，看前 6 对相邻关系，
     * 连续 2 票同向才下结论（票不够或集名里没有数字就不动，避免把清晰度线翻错）。
     */
    private fun isSourceDescending(raw: List<Pair<String?, String>>): Boolean {
        if (raw.size < 2) return false
        var asc = 0
        var desc = 0
        val names = raw.map { it.first.orEmpty() }
        val pairs = minOf(names.size - 1, REVERSE_PROBE_PAIRS)
        for (i in 0 until pairs) {
            val current = firstNumber(names[i])
            val next = firstNumber(names[i + 1])
            when {
                current < next -> if (++asc == REVERSE_VOTES) return false
                current > next -> if (++desc == REVERSE_VOTES) return true
            }
        }
        return false
    }

    /**
     * 集名里第一段数字；没有数字（或大到溢出）按 0 处理。
     *
     * 先把**开头**的 `[目录名]` 前缀剥掉再找：网盘 spider 的集名是
     * `[Y]第01集` / `[4K]第01集` 这种格式，前缀里带数字时（[4K]、[2024]）
     * 若从字符串头找，每一集都会取到前缀里那个同样的数字，相邻比较全部相等
     * → 倒序判定失效（源明明是 40→1 却翻不过来，UI 显示成一团乱序）。
     * 参考项目 `VodInfo.extractNumber` 同样从头找，有一样的问题；这里补一层。
     * 只剥开头一处：`第01集[HD]` 这类后缀标签不受影响。
     */
    private fun firstNumber(name: String): Int {
        val stripped = name.replace(LEADING_TAG, "")
        return NUMBER.find(stripped)?.value?.toIntOrNull() ?: 0
    }

    private data class PendingLine(
        val index: Int,
        val name: String,
        val flag: String,
        val raw: List<Pair<String?, String>>,
    )
}
