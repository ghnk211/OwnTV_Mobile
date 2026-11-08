package tv.own.owntv.core.parser

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mozilla.universalchardet.UniversalDetector
import tv.own.owntv.core.network.StreamHeaders
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Lenient parser for plain-text playlist files (`.txt`) — the format most Chinese IPTV lists use:
 *
 * ```
 * 央视频道,#genre#
 * CCTV1,http://host/live.m3u8
 * CCTV2|http://host/live2.m3u8
 * CCTV3 http://host/live3.m3u8
 * http://host/live4.m3u8
 * #EXTINF:-1 tvg-name="CCTV5",CCTV5
 * http://host/live5.m3u8
 * ```
 *
 * It emits the same [M3uEntry] stream as [M3uParser], so everything downstream — stable keys,
 * categories, hash-diffed upserts, refresh, playback, deletion — is shared with M3U/M3U8 without a
 * new source type. Blank lines, `#` comments, header lines and lines without a URL are skipped;
 * duplicate URLs are dropped. An input with no usable entry is an error, so the importer never
 * leaves an empty source behind.
 *
 * Unlike M3U, a TXT list is a *live* list: every entry is tagged `type="live"`. Otherwise a line
 * such as "支持作者,https://…/clip.mp4" would be silently reclassified as a movie by
 * [ProgressiveVideoUrl] and vanish from the Live TV grid on a `SourceType.M3U` source (whose sync
 * scope is live-only).
 */
class TxtPlaylistParser(private val m3uParser: M3uParser = M3uParser()) {

    /**
     * Reads the whole (bounded) input because charset detection needs the bytes, not a line stream.
     * TXT lists are small — a few hundred KB in practice — and the cap keeps a bad URL from filling
     * the heap.
     */
    suspend fun parse(input: InputStream, onEntry: suspend (M3uEntry) -> Unit): M3uHeader {
        val bytes = readBounded(input)
        // Covers a local .txt file (or a URL served as application/octet-stream) that is really a
        // picture or an archive — those take the explicit-extension path, which never peeks.
        PlaylistFormat.assertTextual(bytes, bytes.size.coerceAtMost(512))
        val text = decode(bytes)

        // A `.txt` link occasionally serves a perfectly ordinary M3U document. Decode first (so the
        // text is clean UTF-8 regardless of the file's own encoding) and hand it to the full M3U
        // parser, which understands tvg-logo, KODIPROP, catchup and the rest.
        if (text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                .startsWith(PlaylistFormat.M3U_HEADER, ignoreCase = true)
        ) {
            var delegated = 0
            val header = m3uParser.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))) { entry ->
                delegated++
                onEntry(entry)
            }
            if (delegated == 0) throw IOException(EMPTY)
            return header
        }
        return parseLoose(text, onEntry)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_BYTES) throw IOException(TOO_LARGE)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * UTF-8 (with or without BOM) first; UTF-16 BOMs next; then a strict UTF-8 decode decides
     * between "already UTF-8" and "legacy Chinese encoding". juniversalchardet gets first refusal
     * on the latter so Big5 files are not forced through GBK, and GB18030 (a superset of GBK, and
     * able to decode every byte sequence) is the fallback.
     */
    private fun decode(bytes: ByteArray): String {
        val (offset, bomCharset) = byteOrderMark(bytes)
        if (bomCharset != null) {
            return String(bytes, offset, bytes.size - offset, bomCharset).removePrefix("\uFEFF")
        }
        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8.removePrefix("\uFEFF")

        val detected = runCatching {
            val detector = UniversalDetector(null)
            detector.handleData(bytes, 0, bytes.size)
            detector.dataEnd()
            detector.detectedCharset
        }.getOrNull()
        val charset = fallbackCharset(detected)
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun byteOrderMark(bytes: ByteArray): Pair<Int, Charset?> {
        fun at(i: Int): Int = bytes[i].toInt() and 0xFF
        return when {
            bytes.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF ->
                3 to StandardCharsets.UTF_8
            bytes.size >= 2 && at(0) == 0xFF && at(1) == 0xFE ->
                2 to StandardCharsets.UTF_16LE
            bytes.size >= 2 && at(0) == 0xFE && at(1) == 0xFF ->
                2 to StandardCharsets.UTF_16BE
            else -> 0 to null
        }
    }

    private fun fallbackCharset(detected: String?): Charset {
        val candidates = ArrayList<String>(4)
        // universalchardet reports windows-1252 for many GBK files; using it would turn every
        // Chinese channel name into mojibake, so it is only a last resort here.
        detected?.takeIf {
            it.isNotBlank() &&
                !it.equals("windows-1252", ignoreCase = true) &&
                !it.equals("ISO-8859-1", ignoreCase = true)
        }?.let { candidates.add(it) }
        candidates.add("GB18030")
        candidates.add("GBK")
        candidates.add("windows-1252")
        return candidates.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.ISO_8859_1
    }

    private suspend fun parseLoose(text: String, onEntry: suspend (M3uEntry) -> Unit): M3uHeader {
        val ctx = currentCoroutineContext()
        var urlTvg: String? = null
        var group: String? = null
        var pendingName: String? = null
        val seenUrls = HashSet<String>()
        val usedNames = HashMap<String, Int>()
        var emitted = 0

        for (raw in text.lineSequence()) {
            ctx.ensureActive()
            val line = raw.trim().trimStart('\uFEFF').trim()
            if (line.isEmpty()) continue

            // `分组名,#genre#` — the de-facto grouping marker of Chinese TXT lists. Maps onto the
            // same group-title/category machinery an M3U playlist uses.
            if (line.contains(PlaylistFormat.GENRE_MARKER, ignoreCase = true)) {
                val label = line.substringBefore(',').substringBefore('|')
                    .removePrefix("#").trim().takeIf { it.isNotBlank() }
                if (label != null) group = label
                pendingName = null
                continue
            }

            if (line.startsWith("#")) {
                when {
                    line.startsWith(PlaylistFormat.M3U_HEADER, ignoreCase = true) ->
                        urlTvg = attribute(line, "url-tvg") ?: attribute(line, "x-tvg-url") ?: urlTvg
                    line.startsWith(PlaylistFormat.EXTINF, ignoreCase = true) -> {
                        pendingName = extInfName(line).takeIf { it.isNotBlank() }
                        group = attribute(line, "group-title") ?: group
                    }
                    // Any other directive/comment: ignore.
                }
                continue
            }

            val (inlineName, rawUrl) = splitNameAndUrl(line)
            if (rawUrl.isEmpty()) continue
            val (url, headers) = splitHeaders(rawUrl)
            if (!isPlayableUrl(url) || !seenUrls.add(url)) continue

            val name = pendingName ?: inlineName ?: deriveName(url, usedNames)
            pendingName = null
            onEntry(
                M3uEntry(
                    name = name,
                    streamUrl = url,
                    logo = null,
                    groupTitle = group,
                    tvgId = null,
                    tvgChno = null,
                    type = "live",
                    tvgType = null,
                    catchup = null,
                    catchupSource = null,
                    catchupDays = null,
                    headers = headers,
                ),
            )
            emitted++
        }
        if (emitted == 0) throw IOException(EMPTY)
        return M3uHeader(urlTvg = urlTvg)
    }

    /**
     * Splits at the first `scheme://`, which handles `name,url`, `name|url`, `name url` and a bare
     * URL line with one rule instead of three ambiguous delimiter guesses. Anything before the
     * scheme is the title, with the leftover separator trimmed off.
     */
    private fun splitNameAndUrl(line: String): Pair<String?, String> {
        val match = URL_SCHEME.find(line) ?: return null to ""
        val url = line.substring(match.range.first).trim()
        val name = line.substring(0, match.range.first)
            .trimEnd(',', '|', '\uFF0C', '\uFF5C', ':', '\uFF1A', ';', '\uFF1B', '-', '=', ' ', '\t')
            .trim().takeIf { it.isNotBlank() }
        return name to url
    }

    /** `url|User-Agent=Foo&Referer=Bar` → clean URL + the same header map M3U entries carry. */
    private fun splitHeaders(raw: String): Pair<String, Map<String, String>> {
        val pipe = raw.indexOf('|')
        if (pipe <= 0 || raw.indexOf("://") !in 0 until pipe) return raw to emptyMap()
        val suffix = raw.substring(pipe + 1)
        if (!suffix.contains('=')) return raw to emptyMap()
        val headers = LinkedHashMap<String, String>(4)
        suffix.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@forEach
            val name = StreamHeaders.canonicalName(pair.substring(0, eq).trim()) ?: return@forEach
            val encoded = pair.substring(eq + 1)
            val value = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded).trim()
            if (value.isNotEmpty()) headers[name] = value
        }
        return raw.substring(0, pipe) to headers
    }

    private fun isPlayableUrl(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 3) return false
        val rest = url.substring(schemeEnd + 3)
        return rest.isNotBlank() && url.none { it == ' ' || it == '\t' }
    }

    /** A nameless URL still needs a readable title; the host, numbered when it repeats. */
    private fun deriveName(url: String, usedNames: HashMap<String, Int>): String {
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast('@').substringBefore(':')
        val base = host.ifBlank { afterScheme.take(32).ifBlank { "Stream" } }
        val n = (usedNames[base] ?: 0) + 1
        usedNames[base] = n
        return if (n == 1) base else "$base $n"
    }

    /** Display name after the first comma outside a quoted attribute (`Movie, The` stays intact). */
    private fun extInfName(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return line.substring(i + 1).trim()
            }
        }
        return line.substringAfterLast(',', "").trim()
    }

    private fun attribute(line: String, key: String): String? {
        val needle = "$key=\""
        var from = 0
        while (from < line.length) {
            val at = line.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return null
            val preceding = if (at == 0) ' ' else line[at - 1]
            if (!preceding.isLetterOrDigit() && preceding != '-' && preceding != '_') {
                val valueStart = at + needle.length
                val end = line.indexOf('"', valueStart)
                if (end > valueStart) return line.substring(valueStart, end).takeIf { it.isNotBlank() }
            }
            from = at + 1
        }
        return null
    }

    internal companion object {
        const val EMPTY = "playlist_empty"
        const val TOO_LARGE = "playlist_too_large"
        const val MAX_BYTES = 32L * 1024L * 1024L
        private const val READ_CHUNK = 16 * 1024
        private val URL_SCHEME = Regex("""(?i)\b[a-z][a-z0-9+.-]*://""")
    }
}
