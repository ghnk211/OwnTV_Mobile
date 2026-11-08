package tv.own.owntv.mobile.tvbox.engine

import tv.own.owntv.mobile.tvbox.util.TbLog


/**
 * 播放地址密文解码器。
 *
 * ## 问题背景（真机实测，2026-10-07）
 * 同一个源（热播┃快速）、同一部《藏海传》，两边拿到的地址**根本不是一回事**：
 *
 * | | 参考项目 TVBoxOS | 本项目|
 * |---|---|---|
 * | 播放地址 | `http://43.248.96.62:9090/nby/m3u8/getM3u8?name=jx.91by&top&time=...` | `eyJ1cmwiOiJOQklrUE1Q...` |
 * | 结果 | 正常播放 | 嗅探 12 秒后失败 |
 *
 * `eyJ` 是 **Base64 编码的 JSON**（`{"url":"..."}` 的 Base64 前缀）。
 * 这类源（热播/荐片/瓜子等一票"快播"系）把真实地址加密后塞进 `vod_play_url`，
 * **客户端必须先 Base64 解码才能拿到地址**。
 *
 * ## 我为什么之前一直没发现
 * 症状极具误导性：解码前的密文"看起来不像媒体后缀"，于是被判定成
 * 「二级播放页 → 需要嗅探」，然后把 `eyJ1cmwi...` 当网页URL 丢给 WebView。
 * WebView 对一串 Base64 的响应是 272ms 内 `ERR_ABORTED`，
 * `shouldInterceptRequest` 一次都不触发 —— 看起来像"华为 WebView 不работает"，
 * 实际是**上游数据根本没解码**。方向错了整整一轮。
 *
 * ## 为什么要做成独立可测的纯逻辑
 * 判定条件（像不像 Base64 JSON、解出来是不是合法 JSON、字段叫什么）很容易写错，
 * 而真机复现一个源要好几分钟。把判定与取值拆成纯函数后单测能全覆盖。
 */
object CipherUrlDecoder {

    private const val TAG = "CipherUrl"

    /** 媒体后缀 —— 解出来的 url 至少得是其中一个才认为解码成功 */
    private val MEDIA_SUFFIX = listOf(
        ".m3u8", ".mpd", ".mp4", ".flv", ".ts", ".webm", ".m4v", ".mov", ".mkv", ".avi", ".wmv", ".rm", ".mpg",
    )

    /** URL 里可能出现的、需要挑出来的地址字段名（按优先级） */
    private val URL_KEYS = listOf("url", "playUrl", "play_url", "m3u8", "mp4", "src", "link", "video")

    /**
     * 这串东西**是否**是待解密的地址。
     *
     * 判定顺序刻意保守 —— 宁可漏判（走原来的老路）也不误判：
     * 1. 不能是 http/data/blob 开头的（那些是真地址）
     * 2. 必须像 Base64（长度够、字符集合法、无 padding 残留问题）
     * 3. 解出来必须是合法 JSON
     * 4. JSON 里必须能取出 http 开头的地址
     *
     * 只有 4 条全过才return true。**任何一步不确定就当作不是密文**，
     * 交给原有的直连/嗅探流程，这样最坏情况只是没优化，不会把能播的源弄坏。
     */
    fun isEncoded(raw: String?): Boolean = decode(raw) != null

    /**
     * 解出真实地址；不是密文或解不出来时返回 null。
     *
     * @return 真实播放地址
     */
    fun decode(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // 已经是明文地址 —— 不是密文
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return null
        if (s.startsWith("data:") || s.startsWith("blob:") || s.startsWith("file:")) return null
        if (!looksBase64(s)) return null

        val json = decodeBase64ToJson(s) ?: return null
        val url = pickUrl(json) ?: return null

        TbLog.tag(TAG).i( "密文解码成功：%.20s… -> %s", s, url.take(120))
        return url
    }

    /**
     * 解出完整JSON 文本（供调用方读取 `parse` 等其他字段）。
     * 不是密文时返回 null。
     */
    fun decodeJson(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return null
        if (!looksBase64(s)) return null
        return decodeBase64ToJson(s)
    }

    /**
     * 像不像 Base64。
     *
     * ## 这里**不能**要求"长度是 4 的倍数"
     * 实测踩过：不少源发的是**无 padding** 的 URL_SAFE Base64，
     * 那种长度 mod 4 会是 2 或 3（`3n/4` 向上取整），
     * 一旦按"必须整除 4"过滤，所有这类源会被全部漏掉 —— 而它们恰恰是主流。
     *
     * 所以长度只做**下限**约束（太短的肯定不是密文），
     * 合法性（长度整除 / 尾部比特为0）交给 [Base64Compat.decode] 去验，
     * 那里返回 null 就自然被过滤掉了。
     */
    private fun looksBase64(s: String): Boolean {
        // 密文一般几十~几百字符；`eyJ`（JSON 开头）解出来至少十几字节
        if (s.length < 16) return false
        // 标准 Base64：A-Za-z0-9+/=
        // URL_SAFE 变体：-_     padding：末尾若干 =
        return s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' }
    }

    private fun decodeBase64ToJson(s: String): String? {
        val bytes = Base64Compat.decode(s)
        if (bytes == null) {
            TbLog.tag(TAG).d( "Base64 解码失败：含非法字符或长度不合法")
            return null
        }
        val text = String(bytes, Charsets.UTF_8)
        // 解出来必须像 JSON 对象才认
        if (!text.trimStart().startsWith("{")) return null
        return text
    }

    /**
     * 从解密后的 JSON 文本里挑出地址。
     *
     * 不只读顶层 `url` —— 部分源把地址放在 `data.url` / `result.url` 里，
     * 所以做了一层浅层嵌套查找。
     *
     * 这里用**手写极简取值**而不是 org.json：
     * `org.json` 在本地 JVM 单测里是桩实现，而"能不能解出地址"
     * 这个判定必须能单测 —— 判错的代价是把本来能播的源弄坏。
     */
    private fun pickUrl(json: String): String? {
        // 先按 URL_KEYS 在**全文**里找，覆盖顶层与一层嵌套（嵌套结构里 key 名一样）
        for (key in URL_KEYS) {
            findStringField(json, key)?.let { if (isPlausibleUrl(it)) return it }
        }
        return null
    }

    /**
     * 从 JSON 文本里取 `key` 对应的字符串值。
     *
     * 只处理 `"key" : "value"` 这种朴素形态 —— 真实数据本来也就是这种，
     * 复杂的转义（`\"` / `\u`）在这些源里不出现，不必上完整 JSON 解析器。
     */
    private fun findStringField(json: String, key: String): String? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(needle, from)
            if (at < 0) return null
            from = at + needle.length
            // 跳过空白与冒号
            var i = from
            while (i < json.length && (json[i] == ' ' || json[i] == '\n' || json[i] == '\r' || json[i] == '\t')) i++
            if (i >= json.length || json[i] != ':') continue
            i++
            while (i < json.length && (json[i] == ' ' || json[i] == '\n' || json[i] == '\r' || json[i] == '\t')) i++
            if (i >= json.length || json[i] != '"') continue
            // 读到收尾引号
            val sb = StringBuilder()
            var j = i + 1
            while (j < json.length && json[j] != '"') {
                if (json[j] == '\\' && j + 1 < json.length) {
                    // 支持最常见的两种转义
                    when (json[j + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(json[j + 1])
                    }
                    j += 2
                    continue
                }
                sb.append(json[j])
                j++
            }
            return sb.toString()
        }
    }

    /** 解出来的字符串像不像一个能播的地址 */
    private fun isPlausibleUrl(s: String?): Boolean {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (!(v.startsWith("http://", true) || v.startsWith("https://", true))) return false
        // 带媒体后缀的最可信；不带后缀但形似 URL 的也可能（走解析器二次处理）
        return true
    }

    /**
     * 是否是"解出来仍然不是直链"的情况（需要再交给解析器/嗅探）。
     *
     * 解出 `http://xxx/player/m3u8play.php?url=...` 这类时仍需走原流程，
     * 此时 `decode()` 返回的地址照样有用 —— 它至少是正确的二级页地址。
     */
    fun looksStream(url: String?): Boolean {
        val u = url?.substringBefore('?')?.lowercase().orEmpty()
        return MEDIA_SUFFIX.any { u.contains(it) }
    }
}
