package tv.own.owntv.core.parser

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** A `lives` item that points at another playlist instead of listing its channels inline. */
data class TvBoxExternalSource(
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val referer: String? = null,
)

/**
 * Reads the `lives` section of a TVBox JSON configuration and emits the same [M3uEntry] stream the
 * M3U parser produces, so the sync layer keeps its stable keys, upserts, pruning and EPG handling.
 *
 * The common shape is an array of groups, each with a `name`/`group` and a `channels` array whose
 * items carry `name` and one or more `urls` (TVBox separates fallback lines with `#` inside a single
 * string). Several forks use `list`/`chs` instead of `channels`, put the groups in an object keyed
 * by group name, or put bare channels at the top level — all of those are walked here. Entries are
 * tagged `live` so an untagged `.mp4` URL is not reclassified as a movie.
 *
 * A `lives` item carrying `type`/`playerType`, or just a `url` ending in a playlist extension, is an
 * *external source reference* — the common shape in published TVBox configs. It is reported through
 * [onExternal] so the sync layer can fetch it with the app's HTTP client and feed the bytes through
 * the M3U parser. Items with neither a `url` nor inline channels (e.g. `type: 3` python sources)
 * cannot be turned into channels and are skipped.
 */
class TvBoxPlaylistParser {

    suspend fun parse(
        input: InputStream,
        onEntry: suspend (M3uEntry) -> Unit,
        onExternal: (suspend (TvBoxExternalSource) -> Unit)? = null,
    ): M3uHeader {
        val root = parseObject(readBounded(input))
        val lives = root.opt(KEY_LIVES) ?: throw IOException(ERR_LIVES_MISSING)
        readLives(lives, null, onEntry, onExternal)
        return M3uHeader(urlTvg = firstString(root, KEY_EPG, KEY_EPG_URL, KEY_URL_TVG, KEY_X_TVG_URL))
    }

    /** Walks groups, arrays and channel objects; [group] is inherited by nested items. */
    private suspend fun readLives(
        node: Any?,
        group: String?,
        onEntry: suspend (M3uEntry) -> Unit,
        onExternal: (suspend (TvBoxExternalSource) -> Unit)?,
    ) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) readLives(node.opt(i), group, onEntry, onExternal)
            is JSONObject -> {
                val channels = node.optJSONArray(KEY_CHANNELS)
                    ?: node.optJSONArray(KEY_LIST)
                    ?: node.optJSONArray(KEY_CHS)
                if (channels != null) {
                    val childGroup = firstString(node, KEY_GROUP, KEY_NAME, KEY_TITLE) ?: group
                    for (i in 0 until channels.length()) readLives(channels.opt(i), childGroup, onEntry, onExternal)
                    return
                }
                val external = externalSource(node)
                if (external != null) {
                    if (onExternal != null) {
                        currentCoroutineContext().ensureActive()
                        onExternal(external)
                    }
                    return
                }
                val entry = channel(node, group)
                if (entry != null) {
                    currentCoroutineContext().ensureActive()
                    onEntry(entry)
                    return
                }
                // No channels and no playable URL: this is either an external source reference or a
                // map of "group name" -> array of channels. Only the latter can produce entries.
                if (firstString(node, KEY_URL, KEY_STREAM_URL, KEY_PLAY_URL, KEY_STREAM) == null) {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = node.opt(key)
                        if (value is JSONArray || value is JSONObject) readLives(value, key, onEntry, onExternal)
                    }
                }
            }
            // A string item in `lives` is an external list reference, not a channel.
            else -> Unit
        }
    }

    /** Inline channels own a `urls` array; everything else with a `url` is another playlist. */
    private fun externalSource(node: JSONObject): TvBoxExternalSource? {
        if (node.optJSONArray(KEY_URLS) != null) return null
        val url = firstString(node, KEY_URL) ?: return null
        val declared = node.has(KEY_TYPE) || node.has(KEY_PLAYER_TYPE)
        if (!declared && !LIST_EXTENSIONS.any { url.path().endsWith(it) }) return null
        return TvBoxExternalSource(
            name = firstString(node, KEY_NAME, KEY_TITLE).orEmpty(),
            url = url,
            userAgent = firstString(node, KEY_UA, KEY_USER_AGENT),
            referer = firstString(node, KEY_REFERER, KEY_REFERRER),
        )
    }

    private fun channel(node: JSONObject, group: String?): M3uEntry? {
        val url = streamUrl(node) ?: return null
        val name = firstString(node, KEY_NAME, KEY_TITLE, KEY_TVG_NAME) ?: return null
        val headers = LinkedHashMap<String, String>()
        firstString(node, KEY_UA, KEY_USER_AGENT)?.let { headers[HEADER_UA] = it }
        firstString(node, KEY_REFERER, KEY_REFERRER)?.let { headers[HEADER_REFERER] = it }
        firstString(node, KEY_ORIGIN)?.let { headers[HEADER_ORIGIN] = it }
        node.optJSONObject(KEY_HEADERS)?.let { extra ->
            val keys = extra.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                extra.optString(key).takeIf { it.isNotBlank() }?.let { headers[key] = it }
            }
        }
        return M3uEntry(
            name = name,
            streamUrl = url,
            logo = firstString(node, KEY_LOGO, KEY_ICON, KEY_PIC, KEY_TVG_LOGO),
            groupTitle = group,
            tvgId = firstString(node, KEY_TVG_NAME, KEY_TVG_ID, KEY_EPG_ID),
            tvgChno = null,
            type = TYPE_LIVE,
            tvgType = null,
            catchup = null,
            catchupSource = null,
            catchupDays = null,
            headers = headers,
        )
    }

    /** TVBox puts fallback lines in a `urls` array, and often joins them with `#` in one string. */
    private fun streamUrl(node: JSONObject): String? {
        node.optJSONArray(KEY_URLS)?.let { urls ->
            for (i in 0 until urls.length()) {
                firstLine(urls.optString(i))?.takeIf { playable(it) }?.let { return it }
            }
        }
        val single = firstString(node, KEY_URL, KEY_STREAM_URL, KEY_PLAY_URL, KEY_STREAM)
            ?.let(::firstLine) ?: return null
        // A single `url` ending in a list extension is never a stream (external references are
        // already taken out by `externalSource`, this is the second line of defence).
        return single.takeIf { value -> playable(value) && !LIST_EXTENSIONS.any { value.path().endsWith(it) } }
    }

    private fun playable(value: String?): Boolean =
        value != null && value.isNotBlank() && value.contains(SCHEME)

    private fun firstLine(value: String?): String? =
        value?.substringBefore(HASH)?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.path(): String = substringBefore(QUERY).substringBefore(HASH).lowercase()

    /** JSON has to be read whole, but a config is small; the cap protects against a wrong URL. */
    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) throw IOException(ERR_TOO_LARGE)
            out.write(buffer, 0, read)
        }
        return decode(out.toByteArray())
    }

    private fun decode(bytes: ByteArray): String {
        val bom = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        var text = String(bytes, bom, bytes.size - bom, StandardCharsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        // Some hand-maintained configs carry // comments; org.json rejects them. Only strip a comment
        // that starts its own line, so the // inside http:// URLs is never touched.
        if (LINE_COMMENT.containsMatchIn(text)) {
            text = text.lineSequence().joinToString(NEWLINE) { line ->
                if (line.trimStart().startsWith(COMMENT)) "" else line
            }
        }
        return text
    }

    private fun parseObject(text: String): JSONObject {
        // Stripping a leading comment leaves an empty first line, so trim again before checking the
        // object marker and before handing the text to org.json.
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith(BRACE)) throw IOException(ERR_NOT_JSON)
        return runCatching { JSONObject(trimmed) }.getOrElse { throw IOException(ERR_INVALID_JSON) }
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.optString(key).trim().takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return null
    }

    companion object {
        /** A TVBox config is always added as a `.json` URL or a `.json` file. */
        fun isTvBoxLocation(location: String): Boolean {
            val path = location.substringBefore(HASH).substringBefore(QUERY)
            val lastSlash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return path.substring(lastSlash + 1).endsWith(JSON_EXTENSION, ignoreCase = true)
        }

        private const val MAX_BYTES = 32 * 1024 * 1024
        private const val READ_BUFFER = 64 * 1024
        private const val JSON_EXTENSION = ".json"
        private const val SCHEME = "://"
        private const val HASH = '#'
        private const val QUERY = '?'
        private const val BRACE = '{'
        private const val NEWLINE = "\n"
        private const val COMMENT = "//"
        private const val TYPE_LIVE = "live"

        private const val KEY_LIVES = "lives"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_LIST = "list"
        private const val KEY_CHS = "chs"
        private const val KEY_GROUP = "group"
        private const val KEY_NAME = "name"
        private const val KEY_TITLE = "title"
        private const val KEY_TYPE = "type"
        private const val KEY_PLAYER_TYPE = "playerType"
        private const val KEY_URLS = "urls"
        private const val KEY_URL = "url"
        private const val KEY_STREAM_URL = "streamUrl"
        private const val KEY_PLAY_URL = "playUrl"
        private const val KEY_STREAM = "stream"
        private const val KEY_LOGO = "logo"
        private const val KEY_ICON = "icon"
        private const val KEY_PIC = "pic"
        private const val KEY_TVG_LOGO = "tvg_logo"
        private const val KEY_TVG_NAME = "tvg_name"
        private const val KEY_TVG_ID = "tvgId"
        private const val KEY_EPG_ID = "epgId"
        private const val KEY_EPG = "epg"
        private const val KEY_EPG_URL = "epgUrl"
        private const val KEY_URL_TVG = "url-tvg"
        private const val KEY_X_TVG_URL = "x-tvg-url"
        private const val KEY_UA = "ua"
        private const val KEY_USER_AGENT = "userAgent"
        private const val KEY_REFERER = "referer"
        private const val KEY_REFERRER = "referrer"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_HEADERS = "headers"

        private const val HEADER_UA = "User-Agent"
        private const val HEADER_REFERER = "Referer"
        private const val HEADER_ORIGIN = "Origin"

        private val LIST_EXTENSIONS = listOf(".m3u", ".txt", ".json", ".php")
        private val LINE_COMMENT = Regex("""(?m)^\s*//""")

        private const val ERR_TOO_LARGE = "tvbox_config_too_large"
        private const val ERR_NOT_JSON = "tvbox_config_not_json"
        private const val ERR_INVALID_JSON = "tvbox_config_invalid_json"
        private const val ERR_LIVES_MISSING = "tvbox_config_lives_missing"
    }
}
