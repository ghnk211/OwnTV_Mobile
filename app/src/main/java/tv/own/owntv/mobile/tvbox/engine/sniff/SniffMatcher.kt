package tv.own.owntv.mobile.tvbox.engine.sniff

import tv.own.owntv.mobile.tvbox.config.model.AdRuleHeuristics
import tv.own.owntv.mobile.tvbox.config.model.SniffRule

/**
 * 嗅探规则匹配器（方案 5.6 第1 步）。
 *
 * ## 为什么单独抽一层
 * 匹配逻辑必须在 WebView 之前就能单测 —— 真机上调 WebView 的
 * `shouldInterceptRequest` 极其难复现（时序、页面脚本、缓存都不可控）。
 * 把"给定 url + contentType + flag，是否是视频流"这个纯函数独立出来，
 * 就能用普通 JVM 单测覆盖各种边界，WebView 只负责喂数据。
 *
 * ## 判定优先级（高 → 低）
 * 1. **明确拒绝**：配置里 `shows`/`flags` 显式排除的域名/关键字（广告域名极多）
 * 2. **URL 后缀命中**：`.m3u8` / `.mp4` / `.flv` 等，最可靠
 * 3. **Content-Type 命中**：`video/mp4` 等video 类型、`application/vnd.apple.mpegurl`
 * 4. **flag 关键字命中**：部分源直链无后缀无类型，只能靠站点约定的 flag 关键字
 *
 * 宁可漏判也不误判：误判会把广告请求当视频直链，播放直接失败；
 * 漏判只是这一条线路嗅探失败，用户还能换线路。
 */
object SniffMatcher {

    /**
     * 内置默认后缀表：**直接复用 `TextUtil.STREAM_SUFFIXES`**（视频 + 音频）。
     *
     * ## 为什么不再自己持有一份
     * 早前这里与播前判定（`TextUtil.looksLikeStream`）各有一份名单且不一致：
     * 本侧早含 `m4a/mp3/aac`，播前侧没有，于是音频直链在播前被判成"需要解析"，
     * 先打一遍 JSON 解析器再降级 WebView 嗅探，最后仍失败（DJ音乐[js] `.m4a` 实证）。
     * 两份名单迟早还会再走偏，收敛成一份。
     *
     * 判定语义仍与播前侧不同（这里是**路径末段** `endsWith`，播前侧是全文 `contains`），
     * 原因见 [match] 第 3 步的注释 —— 那是两处刻意保留的差异，不是笔误。
     */
    private val DEFAULT_SUFFIX = tv.own.owntv.mobile.tvbox.util.TextUtil.STREAM_SUFFIXES

    /**
     * 无后缀直链特征（对齐参考项目 `DefaultConfig.snifferMatch` 的后三条分支）。
     *
     * ## 为什么必须加这个
     * 只按后缀判会漏掉一大类真实地址 —— 它们的播放 URL **不带扩展名**，
     * 路径形如 `/player/m3u8play.php?url=xxx`、`/playlist/m3u8/?vid=xxx`。
     * 这些是参考项目专门为绕开"用后缀判定失效"而积累的特征，
     * 少一条就意味着一个站整片都播不出来。
     *
     * 全部来自参考项目实测有效的样本，逐条搬过来，不自行发挥。
     */
    private val SUFFIXLESS_HINTS = listOf(
        "video/tos",                 // 火山引擎 VOD
        "/m3u8?pt=m3u8",             // 部分 CMS 的 m3u8 代理
        "default.ixigua.com/",       // 西瓜视频
        "dycdn-tos.pstatp",          // 今日头条 CDN
        "/player/m3u8play.php?url=",
        "player.m3u8play.php?url=",
        "/playlist/m3u8/?vid=",
        ".php?type=m3u8&",
        ".php?type=m3u8&type=",
        "/download.aspx?",
        "/api/up_api.php?",
        ".66yk.cn",                  // 66影视
        "netease.com/file/",         // 网易云音乐
    )

    /** 明确是流媒体但后缀被伪装的类型 */
    private val STREAM_CONTENT_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml",
    )

    /**
 * 静态资源特征：一定是噪声。
 * 注意只列"扩展名/路径形态明确"的，不能加 `log`/`stat`/`config` 这类裸词 ——
 * 真实直链里出现 `config.hls.mp4`、`stat/1.m3u8` 的情况很常见，会误杀。
 */
    private val NOISE_SUFFIX = listOf(
        ".gif", ".jpg", ".jpeg", ".png", ".webp", ".ico", ".svg",
        ".css", ".js", ".woff", ".woff2", ".ttf", ".eot",
        "/favicon", "/robots.txt", "/sw.js",
        // 注意：不能写裸 "/manifest" —— DASH 流的清单就叫 dash/manifest.mpd，
        // 会被误杀（已由SniffMatcherTest 固化）
        "/manifest.json", "/manifest.webmanifest",
    )

    /** 广告/统计关键词：必须是带域名前缀形态或专有子域，避免裸词误杀 */
    private val NOISE_KEYWORDS = listOf(
        "googleads", "doubleclick", "googlesyndication", "adservice", "admob",
        "popads", "propellerads", "google-analytics", "googletagmanager",
        "/analytics", "/beacon", "/track", "/pixel", "spacer.gif",
        "adsbygoogle", "scorecardresearch",
    )

    /** 广告的域名片段需精确一点，避免误杀主站（用 "stat" 匹配 "statistics.com" 之类太宽） */
    private val AD_HOSTS = setOf(
        "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
        "cpc.cpm", "ads.", "adservice.", "admob.",
    )

    /** 判定结果 */
    data class Hit(
        val url: String,
        /** 命中原因，用于日志与 UI 提示（"后缀 .m3u8" / "Content-Type video/mp4" / "flag 关键字"） */
        val reason: String,
        /**
         * 是否为**强证据**命中（P1-2）。
         *
         * 强 = 路径末段真带媒体后缀，或响应头声明 video 类型；弱 = 靠无后缀特征 / 查询串 / flag 猜的。
         *
         * 为什么要区分：解析器站的**接口地址**（`https://jx.xxx.com/api.php?type=m3u8&url=…`）
         * 会命中"无后缀特征"这类弱规则，但它返回的是 JSON 或网页，不是流。
         * 一旦被当成直链采用，播放器报容器错误 → 整条换线换源链重来（感知上慢 10s+）。
         * 弱命中只要落在**解析器自己的域名**上就可以降级为兜底候选，真正带后缀的仍然直接采用。
         */
        val strong: Boolean = false,
    )

    /**
     * 预编译后的自定义规则集（P0-6）。
     *
     * ## 为什么要预编译
     * [match] 的调用方是 WebView 的 `shouldInterceptRequest` —— **每条请求一次**，
     * 一个播放页动辄上百条请求。改前每条请求都要重走
     * `SniffRule.hostList()`（拆 hosts 字符串）+ 每条 rule 的 `trim().lowercase()`，
     * 全是纯重复计算，而且发生在 WebView 的拦截线程上。
     *
     * 现在规则在**[sniffResult] 进入时编译一次**，请求阶段只做 `contains`。
     * 纯数据对象，可 JVM 单测，不持有 Context / 不发请求。
     */
    class CompiledRules internal constructor(internal val entries: List<Entry>) {

        internal class Entry(
            val name: String,
            /** 已 lowercase 的 host 片段；空 = 全站生效 */
            val hosts: List<String>,
            /** 已 lowercase 的关键字 */
            val keywords: List<String>,
            /** 已编译的嗅探正则（TVBox `rules[].regex`，播放列表类已剔除） */
            val regexes: List<Regex>,
        )

        val isEmpty: Boolean get() = entries.isEmpty()

        internal fun hit(lowerUrl: String, rawUrl: String): Hit? {
            for (e in entries) {
                if (e.hosts.isNotEmpty() &&
                    e.hosts.none { it == "*" || lowerUrl.contains(it) }
                ) {
                    continue
                }
                val kw = e.keywords.firstOrNull { lowerUrl.contains(it) }
                if (kw != null) return Hit(rawUrl, "自定义规则 ${e.name.ifBlank { kw }}")
                val re = e.regexes.firstOrNull {
                    runCatching { it.containsMatchIn(rawUrl) }.getOrDefault(false)
                } ?: continue
                return Hit(rawUrl, "自定义正则 ${e.name.ifBlank { re.pattern }}")
            }
            return null
        }
    }

    /**
     * 编译配置里的自定义规则。空规则/全空白条目直接丢弃 ——
     * 改前是在每条请求里 `continue` 掉，现在省掉这部分遍历。
     *
     * `regex` 只编译非播放列表项：`#EXT-X-*` / 纯数字是 M3U8 净化规则，
     * 拿来匹配请求 URL 没有意义，纯数字还会命中几乎所有地址。
     */
    fun compile(rules: List<SniffRule>): CompiledRules = CompiledRules(
        rules.mapNotNull { r ->
            val keywords = r.rule.mapNotNull { k ->
                k.trim().lowercase().takeIf { it.isNotEmpty() }
            }
            val regexes = r.regex.asSequence()
                .filterNot { AdRuleHeuristics.isPlaylistRule(it) }
                .mapNotNull { pattern ->
                    runCatching { Regex(pattern.trim(), RegexOption.IGNORE_CASE) }.getOrNull()
                }
                .toList()
            if (keywords.isEmpty() && regexes.isEmpty()) return@mapNotNull null
            CompiledRules.Entry(
                name = r.name,
                hosts = r.hostList().map { it.lowercase() },
                keywords = keywords,
                regexes = regexes,
            )
        },
    )

    /**
     * @param url 被拦截的请求地址
     * @param contentType 响应 Content-Type，可能为 null（WebView 拦截时不一定拿得到）
     * @param flag 站点线路名，部分源用它区分"这条是视频那条是广告"
     * @param rules 配置里的自定义规则；为空时用内置规则
     */
    fun match(
        url: String,
        contentType: String? = null,
        flag: String? = null,
        rules: List<SniffRule> = emptyList(),
    ): Hit? = matchCompiled(url, compile(rules), contentType, flag)

    /**
     * 热路径版本：规则已由 [compile] 预编译（嗅探拦截回调每请求调用一次）。
     *
     * 单独起名而不是重载 [match]：两边都给默认值会与 `match(url)` 产生
     * 重载歧义，编译器直接报错。
     */
    fun matchCompiled(
        url: String,
        compiled: CompiledRules,
        contentType: String? = null,
        flag: String? = null,
    ): Hit? {
        if (url.isBlank()) return null
        val lower = url.lowercase()

        // 1) 广告/静态资源排除 —— 先做，能挡掉绝大多数噪声
        if (isNoise(lower)) return null

        // 2) 自定义规则优先（配置是用户的明确意图）
        compiled.hit(lower, url)?.let { return it }

        // 3) URL 后缀
        // 只认**路径末段**（最后一个 `/` 之后的文件名），不能整串 contains：
        // 解析器域名 `jx.m3u8.tv`、`m3u8.example.com` 里的 `.m3u8` 不是流后缀，
        // 整串 contains 会把解析器页面 URL 误判成直链（真机实测：聚合页命中
        // `jx.m3u8.tv/jiexi/?url=...`，起播 HTML 页面 → NETWORK 错误 → 换线换源全败）。
        val path = lower.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        val suffix = DEFAULT_SUFFIX.firstOrNull { lastSegment.endsWith(it) }
        if (suffix != null) return Hit(url, "后缀 $suffix", strong = true)

        // 3.2) 查询串里声明的媒体后缀（对齐参考项目 snifferMatch 第一条 `\.(m3u8|mpd)\?.*`）
        // 真实形态：`https://ssl.data.m3u8cache.com/m3u8.php?f=9664....m3u8&expire=...`
        // —— m3u8 藏在**查询参数值**里，末段是 `m3u8.php` 不含后缀，按末段判会漏。
        // 判定统一收敛到 TextUtil.queryMediaSuffix（带边界，且覆盖 mp4 等全媒体后缀）：
        // CDN 直链路径是无后缀哈希、真实文件名在 `filename*=...mp4` 里，
        // 只认 m3u8/mpd 会把它判成网页（wexguard「师兄┃秒播」实证 2026-10-19）。
        if (tv.own.owntv.mobile.tvbox.util.TextUtil.queryMediaSuffix(lower) != null) {
            return Hit(url, "查询串含流后缀")
        }

        // 3.5) 无后缀直链特征（`/player/m3u8play.php?url=` 这类）
        SUFFIXLESS_HINTS.firstOrNull { lower.contains(it) }?.let {
            return Hit(url, "无后缀特征 $it")
        }

        // 4) Content-Type（响应头是强证据：`video/mp4` / mpegurl 不会给网页）
        contentType?.let { ct ->
            val t = ct.substringBefore(';').trim().lowercase()
            if (t.startsWith("video/")) return Hit(url, "Content-Type $t", strong = true)
            if (t in STREAM_CONTENT_TYPES) return Hit(url, "Content-Type $t", strong = true)
        }

        // 5) flag 关键字（无后缀无类型的源）
        flag?.let { f ->
            if (f.isNotBlank() && f.contains("m3u8", true)) return Hit(url, "flag 含 m3u8")
        }

        return null
    }

    /** 噪声/广告识别 */
    private fun isNoise(lower: String): Boolean {
        if (AD_HOSTS.any { lower.contains(it) }) return true
        if (NOISE_KEYWORDS.any { lower.contains(it) }) return true
        val path = lower.substringBefore('?')
        if (NOISE_SUFFIX.any { path.endsWith(it) || path.contains(it) }) return true
        return false
    }

    /**
     * 该地址是否"看起来就是直链"，用于决定要不要走嗅探。
     *
     * ## 必须和 [match] 用同一套判定
     * 之前这里只看后缀，而 [match] 还认无后缀特征，导致
     * `/player/m3u8play.php?url=xxx` 这类**已经是直链**的地址被判成"需要嗅探"，
     * 白白跑一趟 12 秒 WebView，最后还是拿不到直链。
     */
    fun looksDirect(url: String?): Boolean {
        val u = url?.trim()?.lowercase().orEmpty()
        if (u.isEmpty()) return false
        // 与 TextUtil.looksLikeStream 对齐：rtmp/rtsp 协议直链没有后缀，
        // 不放行会被送进 WebView 嗅探（2026-10-24 真机补测实证）。
        if (u.startsWith("rtmp://") || u.startsWith("rtsp://")) return true
        // 与 match 保持一致：后缀只认路径末段，防 `jx.m3u8.tv` 这类域名误判
        val lastSegment = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (DEFAULT_SUFFIX.any { lastSegment.endsWith(it) }) return true
        // query 里声明的媒体文件名（CDN 直链的典型形态：路径是哈希，
        // `filename*=...HD.mp4` 才是真实文件名）。此前这里只看路径末段，
        // 于是这类直链被判成网页 → 白等 4 个第三方解析器 32 秒
        // （wexguard「师兄┃秒播」实证 2026-10-19）。
        if (tv.own.owntv.mobile.tvbox.util.TextUtil.queryMediaSuffix(u) != null) return true
        return SUFFIXLESS_HINTS.any { u.contains(it) }
    }

}