package tv.own.owntv.mobile.tvbox.config.model

import java.net.URI

/**
 * 配置里的广告规则分类（纯逻辑，无 Android 依赖）。
 *
 * TVBox 生态把两类规则都塞进 `ads`/`rules[].regex`：
 * - **域名/URL 片段**：给 WebView 拦截用；
 * - **m3u8 播放列表规则**：`#EXT-X-DISCONTINUITY`、`#EXTINF`、纯数字等，给 M3U8 净化用。
 *
 * 两者不能混在一起：把 `#EXTINF` 当域名去 `url.contains()` 永远匹配不上，
 * 而把 `googleads` 当 m3u8 正则又会在净化器里无效。这里先分类。
 */
object AdRuleHeuristics {

    private val PLAYLIST_MARKERS = listOf(
        "#EXT-X-DISCONTINUITY",
        "#EXTINF",
        "#EXT-X-ENDLIST",
        "#EXT-X-KEY",
        "#EXT-X-CUE-OUT",
        "#EXT-X-CUE-IN",
        "#EXT-X-DATERANGE",
    )

    /** 是否属于 M3U8 净化规则（对齐参考项目 `M3u8.isAd`） */
    fun isPlaylistRule(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        if (PLAYLIST_MARKERS.any { s.contains(it, ignoreCase = true) }) return true
        // 纯数字/小数 = 按分片时长匹配的广告规则
        return s.toDoubleOrNull()?.let { it != 0.0 } == true
    }
}

/**
 * 一组已归一化的广告规则。
 *
 * 匹配顺序：
 * 1. URL host 与规则 host **精确或子域后缀**匹配（`doubleclick.net` 命中 `ad.doubleclick.net`）；
 * 2. 兼容生态历史写法：URL 小写后 `contains` 规则片段；
 * 3. loopback 永不判广告，避免误杀宿主自己的 Jar/JS/M3U8 代理。
 *
 * 构造只走 [of]，内部集合全部不可变，可安全跨线程共享。
 */
class AdRuleSet private constructor(
    private val hosts: Set<String>,
    private val fragments: List<String>,
    /** 原始 m3u8 规则，供 M3u8Purifier 使用 */
    val playlistRules: List<String>,
) {

    val isEmpty: Boolean get() = hosts.isEmpty() && fragments.isEmpty()

    /** 规则总条数（日志/诊断用） */
    val size: Int get() = hosts.size + fragments.size + playlistRules.size

    fun isAd(url: String): Boolean {
        if (isEmpty) return false
        val lower = url.lowercase()
        val host = runCatching { URI(lower).host }.getOrNull()?.lowercase()
        if (host != null && isLoopback(host)) return false
        if (host != null && hosts.any { host == it || host.endsWith(".$it") }) return true
        return fragments.any { it.isNotEmpty() && lower.contains(it) }
    }

    /**
     * 分片时长是否命中配置里的数字广告规则（参考 `M3u8.isAd` 的纯数字规则）。
     *
     * 匹配口径按规则形态区分：
     * - **整数规则等值匹配**：`"1"` 只命中 1.0 秒分片，不再连带吞掉 10~19 秒
     *   （旧实现 `startsWith` 的误杀面太大）；
     * - **小数规则前缀匹配 + 整数部分相等**：生态配置写 `15.1666` 是为了命中
     *   15.166667 这类被截断的 EXTINF，改成严格等值反而会让真配置失效；
     *   但必须要求整数部分相等 —— 否则 `"1.0"` 会经 `startsWith` 连带命中
     *   `10.0`/`19.0`（整数等值修不到这种带小数位的整数写法，
     *   2026-10-24 收紧）。
     *
     * 负号规则需要"组末分片"语义，MVP 不启用，避免误杀。
     */
    fun matchesPlaylistDuration(durationText: String): Boolean {
        val duration = durationText.trim()
        if (duration.isEmpty() || playlistRules.isEmpty()) return false
        val durationValue = duration.toDoubleOrNull() ?: return false
        return playlistRules.any { rule ->
            val r = rule.trim()
            if (r.isEmpty() || r.startsWith("-")) return@any false
            val ruleValue = r.toDoubleOrNull() ?: return@any false
            if (r.contains('.')) {
                duration.startsWith(r) && integerPart(duration) == integerPart(r)
            } else {
                kotlin.math.abs(durationValue - ruleValue) < 0.001
            }
        }
    }

    /** 时长文本的整数部分（`"15.1666"` → `"15"`；无小数点的 `.5` 形态按 `0` 计） */
    private fun integerPart(text: String): String =
        text.substringBefore('.').ifEmpty { "0" }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")

    companion object {
        val EMPTY = AdRuleSet(emptySet(), emptyList(), emptyList())

        fun of(raw: List<String>): AdRuleSet {
            if (raw.isEmpty()) return EMPTY
            val hosts = LinkedHashSet<String>()
            val fragments = LinkedHashSet<String>()
            val playlistRules = ArrayList<String>()

            raw.forEach { item ->
                val s = item.trim()
                if (s.isEmpty()) return@forEach
                if (AdRuleHeuristics.isPlaylistRule(s)) {
                    playlistRules += s
                    return@forEach
                }

                val lower = s.lowercase()
                // 无点号、无路径的裸关键词（googleads/adservice 等）只能走 contains
                if (!lower.contains('.') && !lower.contains('/') &&
                    !lower.startsWith("http://") && !lower.startsWith("https://") &&
                    !lower.startsWith("//")
                ) {
                    fragments += lower
                    return@forEach
                }

                val parseable = when {
                    lower.startsWith("http://") || lower.startsWith("https://") -> lower
                    lower.startsWith("//") -> "http:$lower"
                    else -> "http://$lower"
                }
                val uri = runCatching { URI(parseable) }.getOrNull()
                val host = uri?.host?.trim()?.lowercase()
                    ?.takeIf { it.isNotBlank() && it != "localhost" }
                if (host == null) {
                    fragments += lower
                    return@forEach
                }

                val path = uri.path.orEmpty()
                // 只有 host（或无路径）才做全站匹配；带 path 的完整 URL 只做精确片段匹配，
                // 否则一条 "https://cdn.x.com/ads/a.js" 会把整个 cdn.x.com 都判成广告。
                if (path.isBlank() || path == "/") hosts += host else fragments += lower
            }

            if (hosts.isEmpty() && fragments.isEmpty() && playlistRules.isEmpty()) return EMPTY
            return AdRuleSet(hosts, fragments.toList(), playlistRules.toList())
        }
    }
}
