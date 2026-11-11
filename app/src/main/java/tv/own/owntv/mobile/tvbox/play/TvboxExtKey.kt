package tv.own.owntv.mobile.tvbox.play

import org.json.JSONObject

/**
 * TVBOX 进度的线路/集数身份（方案 §4.6）：Core `playback_progress.extKey` 的 JSON 编解码。
 *
 * 一部片一条 Core 进度（`(profile, MOVIE, movieId)` 唯一），位置之外的"哪条线路哪一集"
 * 落在这里：`{"flag":"…","episodeId":"…","index":N}`。JSON 而不是分隔符拼接 ——
 * episodeId 可能是含任意字符的待解析标识，且备份会把它原样带进多层序列化。
 */
object TvboxExtKey {

    data class Identity(val flag: String, val episodeId: String, val index: Int)

    fun encode(flag: String, episodeId: String, index: Int): String =
        JSONObject().put("flag", flag).put("episodeId", episodeId).put("index", index).toString()

    /** 空/非法/缺 index 一律 null —— 续播退回第一集，而不是崩在解析上。 */
    fun decode(json: String?): Identity? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val index = o.optInt("index", -1)
        if (index < 0) return null
        return Identity(flag = o.optString("flag"), episodeId = o.optString("episodeId"), index = index)
    }
}