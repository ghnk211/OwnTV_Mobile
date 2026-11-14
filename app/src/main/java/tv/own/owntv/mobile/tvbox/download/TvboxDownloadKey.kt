package tv.own.owntv.mobile.tvbox.download

import org.json.JSONObject

/**
 * The identity and the detail of one TVBOX episode download.
 *
 * A TVBOX film is one `movies` row, so `(profileId, mediaType, itemId)` cannot tell its episodes
 * apart. [identity] is the extra unique-key column; [encode] is the JSON kept in the download row's
 * `extJson`, which is what a later resolve needs.
 *
 * The field names mirror `TvboxExtKey` (flag/episodeId/index) on purpose: the offline player writes
 * the same progress identity back to `playback_progress.extKey`.
 */
object TvboxDownloadKey {

    data class Ref(
        val siteKey: String,
        val vodId: String,
        val flag: String,
        val episodeId: String,
        val episodeName: String,
        val index: Int,
        val lineName: String,
    )

    /**
     * NUL-separated, never a bare `|`: a CMS `flag` is the play URL and may itself contain `|`, so
     * `a|b` + `c` and `a` + `b|c` could otherwise build the same key. NUL cannot appear in a URL or
     * a net-disk id.
     *
     * A handful of sites leave both the flag and the episode id empty; the list index is then the
     * only thing that tells their episodes apart.
     */
    fun identity(r: Ref): String = listOf(
        r.siteKey,
        r.vodId,
        r.flag,
        r.episodeId,
        if (r.flag.isBlank() && r.episodeId.isBlank()) r.index.toString() else "",
    ).joinToString("\u0000")

    fun encode(r: Ref): String = JSONObject().apply {
        put("kind", "tvbox")
        put("siteKey", r.siteKey)
        put("vodId", r.vodId)
        put("flag", r.flag)
        put("episodeId", r.episodeId)
        put("episodeName", r.episodeName)
        put("index", r.index)
        put("lineName", r.lineName)
    }.toString()

    /** Blank, malformed, a different kind, or an impossible index all mean "not a TVBOX row". */
    fun decode(json: String?): Ref? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optString("kind") != "tvbox") return null
        val siteKey = o.optString("siteKey")
        val vodId = o.optString("vodId")
        val index = o.optInt("index", -1)
        if (siteKey.isBlank() || vodId.isBlank() || index < 0) return null
        return Ref(
            siteKey = siteKey,
            vodId = vodId,
            flag = o.optString("flag"),
            episodeId = o.optString("episodeId"),
            episodeName = o.optString("episodeName"),
            index = index,
            lineName = o.optString("lineName"),
        )
    }
}