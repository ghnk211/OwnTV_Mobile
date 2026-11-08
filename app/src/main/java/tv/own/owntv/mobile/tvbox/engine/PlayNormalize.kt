package tv.own.owntv.mobile.tvbox.engine

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.engine.jar.JarResultParser
import tv.own.owntv.mobile.tvbox.util.TextUtil

/**
 * 播放结果归一化（Jar / Py 引擎共用）。
 *
 * 从 [tv.own.owntv.mobile.tvbox.engine.jar.JarSpider] 原样抽出（行为不变）：这段逻辑里有两条
 * 真机踩坑换来的判据（Base64 密文先解再判流地址；声称 parse=0 但地址不是流 → 转嗅探），
 * 两个引擎各抄一份必然漂移，故收口到一处。
 *
 * ## ⚠️ 为什么判"形态"而不是"内容"
 * 播放结果的 JSON 形如 `{"url":"https://x/y.m3u8","parse":0}`，整体字符串里含 `.m3u8`，
 * 按内容判流会把整段 JSON 当 URL 塞给 ExoPlayer（表现为"播放一直转圈"且日志无异常）。
 * 正确判据是形态：JSON 一定以 `{` 或 `[` 开头。
 */
internal object PlayNormalize {

    /**
     * 播放结果归一化。
     *
     * @param raw 脚本/jar 返回：裸地址文本、JSON 文本或已解析 Map 都收
     * @param flag 线路名，透传给 [PlayResult.flag]
     * @param id 播放条目 id：无结果时兜底成"需要嗅探"的 [PlayResult]
     * @param logTag 日志 tag（各引擎传自己的，保留排障归属）
     * @param sourceLabel 站点名（日志文案用）
     */
    fun normalizePlay(
        raw: Any?,
        flag: String,
        id: String,
        logTag: String,
        sourceLabel: String,
    ): PlayResult? {
        if (raw is String) {
            val text = raw.trim()
            // 空串 / 空白 JSON → 视为无数据，交由上层嗅探兜底
            if (text.isEmpty() || text == "null") return sniff(id)

            if (!looksLikeJson(text)) {
                // **密文解码**（真机实测，见 [CipherUrlDecoder]）。
                // 「快播」系源返回的是 Base64 密文（`eyJ...`），
                // 老逻辑会因为它不是流地址而直接丢去嗅探 —— 拿一串 Base64 当网页打开，
                // WebView 272ms 内 ERR_ABORTED，12 秒后必然失败。必须先解开。
                CipherUrlDecoder.decode(text)?.let { decoded ->
                    return PlayResult(
                        parse = if (TextUtil.looksLikeStream(decoded)) 0 else 1,
                        url = decoded,
                        flag = flag,
                    )
                }
                // 真正的裸直链：整段就是一个 URL
                if (!TextUtil.looksLikeStream(text)) return sniff(id)
                return PlayResult(parse = 0, url = text, flag = flag)
            }
        }
        val result = JarResultParser.toPlayResult(raw)
        if (result == null) {
            TbLog.tag(logTag).d("%s 播放结果无法归一化：%s", sourceLabel, JarResultParser.describe(raw))
            return sniff(id)
        }
        // 同样对 url 字段做一次密文解码（密文可能裹在 JSON 的 url 里）
        val decodedUrl = CipherUrlDecoder.decode(result.url)
            ?: return result.let { r ->
                // 声称直链(parse=0)但地址不是流地址 → 交由嗅探，别硬塞给 ExoPlayer
                if (r.parse == 0 && !TextUtil.looksLikeStream(r.url)) r.copy(parse = 1) else r
            }
        return result.copy(
            url = decodedUrl,
            parse = if (TextUtil.looksLikeStream(decodedUrl)) 0 else 1,
        )
    }

    /** 是否 JSON 文本（对象/数组）。JSON 必以 { 或 [ 开头，且不是裸 URL。 */
    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[")

    /** 兜底：告诉上层"这个地址需要嗅探/解析"，而非直接失败 */
    private fun sniff(id: String): PlayResult = PlayResult(parse = 1, url = id)
}
