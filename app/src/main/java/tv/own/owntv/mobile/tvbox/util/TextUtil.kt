package tv.own.owntv.mobile.tvbox.util

/**
 * UA 池与请求头工具。
 * 关键约定：嗅探 WebView、播放器请求头（PlayResult.header）、桩网络三方 UA 必须对齐，
 * 否则同源不同 UA 会触发防盗链（方案 7.2）。
 */
object UaUtil {

    /** 默认移动端 Chrome UA */
    const val DEFAULT_UA: String =
        "Mozilla/5.0 (Linux; Android 12; V1938CT Build/QP1A.190711.020; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 " +
            "Mobile Safari/537.36"

    /** 桌面 UA：部分 CMS 站点对移动 UA 返回精简页 */
    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * UA 门控兜底 UA（TVBox 生态兼容）。
     *
     * ## 背景（真机实测）
     * 影视仓（`xn--5mqx81b535a.com` / `47.120.41.246:8025`）按 User-Agent 分流：
     * - okhttp 系 UA → 302 到真实配置 `tv.json` / 真 jar（lubin.php）/ 真 m3u（zb.php）
     * - 浏览器系 UA → 302 到官网 HTML 落地页（17KB，"影视仓 & OK影视 - 官方配置分发"）
     *
     * 本项自默认注入的移动 Chrome UA 正好落在被门控挡掉的那一侧，表现为
     * 「配置无法解析 / csp_ 源找不到实现类 / 直播源加载失败」。
     * 参考项目 TVBoxOS 同样在 `ApiConfig` 硬编码 `okhttp/3.15` 过同一条门控。
     *
     * **不要**改成浏览器 UA —— 那正是被挡的一侧。只要含 `okhttp` 前缀即可过门控，
     * 与 OkHttp 版本无关（3.15 / 4.9 / 4.12 实测均放行）。
     */
    const val GATE_UA: String = "okhttp/4.12.0"

    private val pool = listOf(DEFAULT_UA, DESKTOP_UA)

    /** 按站点 key 稳定取一个 UA，保证同源同 UA */
    fun uaFor(siteKey: String?): String {
        if (siteKey.isNullOrEmpty()) return DEFAULT_UA
        val idx = (siteKey.hashCode().toLong() and 0x7fffffff).toInt() % pool.size
        return pool[idx]
    }

    fun isMobile(ua: String?): Boolean {
        val u = ua?.lowercase() ?: return false
        return u.contains("mobile") || u.contains("android") || u.contains("iphone")
    }
}

/** 字符串/HTML 工具：归一化阶段统一处理（方案 5.2） */
object TextUtil {

    /**
     * 可直连起播的**流媒体后缀**单一真源（视频 + 音频）。
     *
     * 播前判定（[looksLikeStream]）与嗅探判定（`SniffMatcher`）此前各持一份名单，
     * 且不一致 —— 嗅探侧早就含 `m4a/mp3/aac`（对齐参考项目 `DefaultConfig.snifferMatch`），
     * 播前侧没有，于是**音频直链被判成"需要解析"**，绕一圈 JSON 解析器 + WebView 后失败
     * （DJ音乐[js] 的 `.m4a`，见 docs/DJ音乐js源解析播放分析.md B2）。
     * 现在两处共用这一份，避免再次走偏。
     *
     * 顺序有意义：越具体/越常见的靠前（`SniffMatcher` 按 `firstOrNull` 取命中项用于日志）。
     */
    val STREAM_SUFFIXES: List<String> = listOf(
        ".m3u8", ".mpd", ".mp4", ".flv", ".mkv", ".webm", ".m4v", ".mov", ".3gp", ".ts",
        // 与参考项目对齐：老片源常用的 AVI / RM / RMVB / WMV / MPG
        // `.rmvb` 必须单独列出：SniffMatcher.looksDirect 用 endsWith 判末段，
        // `.rm` 不会命中 `movie.rmvb`（2026-10-23 OXF-AN00 真机缺陷 9）。
        ".avi", ".rm", ".rmvb", ".wmv", ".mpg",
        // 音频：部分源（DJ音乐、电台/听书类）的直链就是纯音频，
        // 少了这几个这类源要么被送去嗅探失败，要么一条都嗅探不出来
        ".m4a", ".mp3", ".aac", ".flac", ".wav", ".ogg",
    )

    /**
     * query 后缀判定可用的后缀（从 [STREAM_SUFFIXES] 剔除 `.ts`）。
     *
     * ## 为什么要剔 `.ts`
     * query 段里 `.ts` 撞车概率太高（时间戳 `t=1792391603` 不会撞，但
     * `v=1.2.ts`、`type=xx.ts` 这类会），一旦误判成"直链"就会跳过网页解析、
     * 直接把一个 HTML 页面喂给播放器。其余后缀（`.m3u8`/`.mp4`/`.m4a` 等）
     * 出现在 query 里基本都是真实文件名，误判风险极低。
     */
    private val QUERY_SAFE_SUFFIXES: List<String> = STREAM_SUFFIXES.filter { it != ".ts" }

    /**
     * query 里"声明式"媒体后缀：`（后缀）（紧跟分隔符）`。
     *
     * 用正则而不是 `contains` 是为了卡边界 —— `?url=https://a.com/x.mp4.txt`
     * 里的 `.mp4` 后面跟的是 `.`，不是分隔符，不算命中。
     */
    private val QUERY_MEDIA_TAIL: Regex = Regex(
        """(${QUERY_SAFE_SUFFIXES.joinToString("|") { Regex.escape(it) }})(?=[&"'\s]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val ENTITIES = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&nbsp;" to " ",
        "&#39;" to "'",
        "&#34;" to "\"",
    )

    /** 解码 HTML 实体 + 去除首尾空白 */
    fun decode(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        val input: String = s
        var out = input
        ENTITIES.forEach { (k, v) -> out = out.replace(k, v) }
        // 数字实体 &#38; &#x26;
        out = NUM_ENTITY.replace(out) { m ->
            val body = m.groupValues[1]
            val code = if (body.startsWith("x") || body.startsWith("X")) {
                body.drop(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
            code?.takeIf { it in 1..0x10FFFF }?.toChar()?.toString() ?: m.value
        }
        return out.trim()
    }

    private val NUM_ENTITY = Regex("&#([xX]?[0-9a-fA-F]+);")

    /** 归一化标题：合并多余空白、去掉首尾空格 */
    fun normalizeName(s: String?): String =
        decode(s).orEmpty().replace(Regex("\\s+"), " ").trim()

    /**
     * 补全相对地址：base 为空或无法解析时原样返回。
     *
     * 放行名单里的协议都是「自带绝对定位」的，拼 base 只会毁掉它们：
     * `file://` 是 csp_ spider 解密加密封面后的交付形态（封面密文在 spider 侧
     * AES 解密写进 cacheDir，pic 返回 `file:///data/.../covers/<md5>.img`，
     * 见 tvbox_sites CoverCache）。漏放行时 JarResultParser 拿 site.api
     * （形如 `csp_ChengGuo`）当 base，把它拼成
     * `http://csp_ChengGuo/file:///...`，Coil 必失败 → 整屏首字占位图
     * （真机实证 2026-10-20）。
     */
    fun absoluteUrl(url: String?, base: String?): String {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return u
        if (u.startsWith("http://") || u.startsWith("https://") ||
            u.startsWith("rtsp://") || u.startsWith("magnet:") ||
            u.startsWith("data:") || u.startsWith("file://") ||
            u.startsWith("content://") || u.startsWith("//")
        ) {
            return if (u.startsWith("//")) "https:$u" else u
        }
        val b = base?.trim().orEmpty()
        if (b.isEmpty()) return u
        val host = runCatching {
            val p = if (b.startsWith("http")) b else "http://$b"
            java.net.URI(p)
        }.getOrNull() ?: return u
        val origin = "${host.scheme}://${host.authority}"
        return when {
            u.startsWith("/") -> origin + u
            else -> origin.trimEnd('/') + "/" + u
        }
    }

    /**
     * 猜 MIME：扩展名兜底（方案 8.1）。
     *
     * ## 音频后缀必须单独认（DJ音乐[js] 真机实证 2026-10-19）
     * 该源的播放直链形如 `https://mp4.djuu.com/<file>.m4a`。此前名单里只有视频后缀，
     * `.m4a` 一路落到通配兜底；而 ExoPlayerEngine
     * 把通配值视为"未知后缀"并映射成 `APPLICATION_M3U8`（生态里大量 m3u8 伪装成
     * 无后缀/怪后缀，这是有意的兜底）——结果 ExoPlayer 拿 `HlsPlaylistParser` 去解一个
     * MP4 音频，抛 `Input does not start with the #EXTM3U header`，
     * 表现为"详情页能看到播放地址、一点播放就失败"。
     *
     * 注意 [tv.own.owntv.mobile.tvbox.util.TextUtil.STREAM_SUFFIXES]（播前直链判定）与这份名单
     * 是**两个消费方**：前者只管"是不是流"，后者决定"按什么容器解"，漏一个就断链。
     */
    fun guessMime(url: String?): String {
        val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        mimeOfSuffix(path)?.let { return it }
        // 路径没有后缀时，看 query 里声明的真实文件名。
        // CDN 常把文件名塞进 `response-content-disposition` 的 `filename*`，
        // 路径是个纯哈希（wexguard「师兄┃秒播」实证，见 [queryMediaSuffix]）。
        queryMediaSuffix(url)?.let { mimeOfSuffix(it) }?.let { return it }
        // query 声明 mp4 的网盘型直链：路径无后缀，但 mime_type=video_mp4
        // （见 [urlDeclaresMp4]，wexguard 真机实证 2026-10-19）
        if (urlDeclaresMp4(url)) return "video/mp4"
        return "video/*"
    }

    /**
     * 按后缀（或含后缀的任意文本）映射 MIME；不认识返回 null。
     *
     * 提取成独立函数是为了让"路径"和"query 声明"两条来源走**同一张表** ——
     * 早前两处各写一份 when，加一个后缀就得记得改两个地方。
     */
    private fun mimeOfSuffix(text: String): String? = when {
        text.contains(".m3u8") -> "application/vnd.apple.mpegurl"
        text.contains(".mpd") -> "application/dash+xml"
        text.contains(".mp4") -> "video/mp4"
        text.contains(".flv") -> "video/x-flv"
        text.contains(".ts") -> "video/mp2t"
        text.contains(".mkv") -> "video/x-matroska"
        text.contains(".webm") -> "video/webm"
        text.contains(".mov") -> "video/quicktime"
        text.contains(".3gp") -> "video/3gpp"
        text.contains(".m4v") -> "video/x-m4v"
        // ---- 音频（MP4 容器为主，Mp4Extractor 可直接嗅解）----
        text.contains(".m4a") -> "audio/mp4"
        text.contains(".aac") -> "audio/mp4a-latm"
        text.contains(".mp3") -> "audio/mpeg"
        text.contains(".flac") -> "audio/flac"
        text.contains(".wav") -> "audio/wav"
        text.contains(".ogg") -> "audio/ogg"
        else -> null
    }

    /**
     * URL 的 query 段里"声明"的媒体后缀（如 `.mp4`），没有则 null。
     *
     * ## 为什么需要（wexguard「师兄┃秒播」真机实证 2026-10-19）
     * CDN 直链把真实文件名放在 query 里，路径是个纯哈希：
     * ```
     * https://ykj-eos-dg5-01...cmecloud.cn/f51399d42f5949cd...?response-content-disposition=
     *   attachment%3B%20filename%2A%3DUTF-8%27%27...HD.mp4&X-Amz-Signature=...
     * ```
     * 路径 `/f51399d42f5949cd...` 没有任何后缀，后缀只存在于 `filename*` 的值里。
     * 只按路径判定 → 这条**只需 1.2 秒就能拿到的 mp4 直链**被判成"网页地址"，
     * 白等 4 个第三方解析器 32 秒才失败，最后靠换线路兜回来。
     *
     * ## 边界为什么这么严
     * query 里"看着像后缀其实不是"的形态很多（`?url=https://a.com/x.mp4.txt`），
     * 所以要求后缀后紧跟 `&` / `#` / 引号 / 空白 / 行尾。`.ts` 已被剔除，
     * 原因见 [QUERY_SAFE_SUFFIXES]。
     */
    fun queryMediaSuffix(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        // 解码后再判：`filename%2A%3D...mp4` 这类整体编码的形态也能命中
        val decoded = runCatching {
            java.net.URLDecoder.decode(query, "UTF-8")
        }.getOrDefault(query).lowercase()
        return QUERY_MEDIA_TAIL.find(decoded)?.groupValues?.getOrNull(1)
    }

    /**
     * URL 的 query 是否显式声明 mp4（`mime_type=video_mp4` 及其变体）。
     *
     * ## 为什么需要它（wexguard「师兄┃秒播」真机实证 2026-10-19）
     * 网盘型直链路径不带任何媒体后缀，容器类型只写在 query 里：
     * ```
     * https://v3-p-netdisk.toutiaovod.com/.../video/tos/.../?a=264482&...&mime_type=video_mp4&...
     * ```
     * [guessMime] 只看 `?` 之前的路径 → 未知容器（通配兜底）→ 播放器按"未知后缀默认
     * HLS"起播 → `HlsPlaylistParser` 解 MP4 抛 `Input does not start with the
     * #EXTM3U header`（code=3002），且"按 HLS 重试"救不了（本来就是 HLS）。
     *
     * 三种写法都认：`video_mp4`（下划线，头条系）、`video/mp4`（斜杠）、
     * `video%2Fmp4` / `video%2fmp4`（URL 编码斜杠，大小写不敏感）。
     */
    fun urlDeclaresMp4(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("mime_type=video_mp4") ||
            lower.contains("mime_type=video/mp4") ||
            lower.contains("mime_type=video%2fmp4")
    }

    /** 是否是已知视频流容器（用于嗅探规则匹配与播前判断） */
    /**
     * 是不是可直连的流媒体地址。
     *
     * ## 必须看**全文**，不能只取`?` 之前的路径
     * 真机实测踩过的坑（源：热播┃快速，影片《莫离》）：
     * ```
     * http://43.248.96.62:9090/nby/m3u8/getM3u8?name=jx.91by.top&time=...&url=NBY-4195....m3u8
     * ```
     * 这是**标准 m3u8 直链**，但媒体后缀只出现在 query 段里。
     * 原来的 `substringBefore('?')` 把 query 切掉后路径变成 `/nby/m3u8/getM3u8`，
     * 不含任何媒体后缀 → 被误判成"二级播放页" → 送去 WebView 嗅探
     * → 12 秒后必然报「未在页面中找到视频地址」。
     *
     * 这类"代理式m3u8 接口"（先取 token 再换流地址）在影视源里非常普遍，
     * 不是个别站的特例。
     */
    fun looksLikeStream(url: String?): Boolean {
        val u = url?.lowercase()?.trim().orEmpty()
        if (u.isEmpty()) return false
        // rtmp/rtsp 协议直链：路径末段通常是房间号/频道名，**没有后缀可言**，
        // 只按后缀表判会把它们打成 parse=1 送进 WebView 嗅探，必然失败
        // （2026-10-24 OXF-AN00 真机补测 RTMP/RTSP 实证：CMS 给出
        // `rtmp://.../live` 被判网页 → SniffRoute → 秒失败）。
        if (u.startsWith("rtmp://") || u.startsWith("rtsp://")) return true
        // 全文匹配：路径里的后缀、query 里的后缀都要认
        return STREAM_SUFFIXES.any { u.contains(it) }
    }
}
