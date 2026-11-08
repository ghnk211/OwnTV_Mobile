package tv.own.owntv.core.parser

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets

/** Which parser a playlist needs. M3U/M3U8 keeps the original parser; TXT gets the lenient one. */
internal enum class PlaylistKind { M3U, TXT }

/**
 * Picks the parser for a playlist stream.
 *
 * An explicit `.m3u`/`.m3u8` or `.txt` extension wins outright. That is deliberate: it means an
 * existing M3U source never sees a different parser, a different stream wrapper or an extra read —
 * the whole pre-TXT path stays byte-for-byte what it was. Only links and files with no recognised
 * extension (Xtream `get.php` exports, uploaded files whose name was dropped, bare CDN paths) pay
 * for a bounded peek, and the peeked bytes are replayed so nothing is lost.
 *
 * The sniff is intentionally one-sided: a `#EXTM3U` header means M3U; everything else is handed to
 * [TxtPlaylistParser], which understands both plain text lines and `#EXTINF` blocks. A playlist
 * with no header at all therefore ends up with the parser that can still read it.
 */
internal object PlaylistFormat {

    /** Bounded peek: enough to see a header past a BOM and a few comment lines. */
    private const val SNIFF_BYTES = 8 * 1024

    /** Extension hint from a URL or a local path; null when the location says nothing useful. */
    fun fromLocation(location: String): PlaylistKind? {
        val path = location.substringBefore('#').substringBefore('?')
        val lastSlash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        val name = path.substring(lastSlash + 1).lowercase()
        return when {
            name.endsWith(".m3u8") || name.endsWith(".m3u") -> PlaylistKind.M3U
            name.endsWith(".txt") || name.endsWith(".text") -> PlaylistKind.TXT
            else -> null
        }
    }

    fun isTxtLocation(location: String): Boolean = fromLocation(location) == PlaylistKind.TXT

    /**
     * Returns the chosen kind and a stream that still starts at byte zero. The caller owns the
     * returned stream and closes it as it would have closed [input].
     */
    fun detect(location: String, input: InputStream): Detected {
        val explicit = fromLocation(location)
        if (explicit != null) return Detected(explicit, input)

        val head = ByteArray(SNIFF_BYTES)
        var total = 0
        while (total < head.size) {
            val read = input.read(head, total, head.size - total)
            if (read <= 0) break
            total += read
        }
        val kind = sniff(head, total)
        // Content checks only matter for a text playlist: an M3U export fetched from a bare path
        // must not start failing because its first bytes happen to look unusual.
        if (kind == PlaylistKind.TXT) assertTextual(head, total)
        val replay: InputStream = if (total == 0) {
            input
        } else {
            SequenceInputStream(ByteArrayInputStream(head, 0, total), input)
        }
        return Detected(kind, replay)
    }

    /** Header-based sniff. UTF-8 decoding replaces bad bytes, so it never throws on a GBK file. */
    fun sniff(bytes: ByteArray, length: Int): PlaylistKind {
        val text = String(bytes, 0, length.coerceAtMost(bytes.size), StandardCharsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (text.startsWith(M3U_HEADER, ignoreCase = true)) PlaylistKind.M3U else PlaylistKind.TXT
    }

    /**
     * True when [head] looks like a text playlist rather than an image, archive or HTML error page.
     * Used by the "Test source" probe, which only reads the first kilobyte.
     */
    fun looksLikePlaylistText(head: String): Boolean {
        val text = head.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.isEmpty()) return false
        if (text.startsWith(M3U_HEADER, ignoreCase = true) ||
            text.startsWith(EXTINF, ignoreCase = true) ||
            text.contains(GENRE_MARKER, ignoreCase = true)
        ) {
            return true
        }
        if (text.startsWith("<!DOCTYPE", ignoreCase = true) || text.startsWith("<html", ignoreCase = true)) {
            return false
        }
        return URL_SCHEME.containsMatchIn(text)
    }

    /** Rejects obvious binary payloads that a mislabelled `.txt` URL can serve instead of a list. */
    internal fun assertTextual(bytes: ByteArray, length: Int) {
        if (length == 0) return
        val utf16 = length >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        if (!utf16) {
            for (i in 0 until length.coerceAtMost(512)) {
                if (bytes[i] == 0.toByte()) throw IOException(NON_TEXT_CONTENT)
            }
        }
        val prefix = String(bytes, 0, length.coerceAtMost(12), StandardCharsets.ISO_8859_1)
        if (prefix.startsWith("\u0089PNG", ignoreCase = true) ||
            prefix.startsWith("%PDF", ignoreCase = true) ||
            prefix.startsWith("GIF8", ignoreCase = true) ||
            prefix.startsWith("PK\u0003\u0004", ignoreCase = true) ||
            prefix.startsWith("Rar!", ignoreCase = true) ||
            (length >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) ||
            (length >= 12 && prefix.substring(4).startsWith("ftyp", ignoreCase = true))
        ) {
            throw IOException(NON_TEXT_CONTENT)
        }
    }

    data class Detected(val kind: PlaylistKind, val stream: InputStream)

    internal const val M3U_HEADER = "#EXTM3U"
    internal const val EXTINF = "#EXTINF"
    internal const val GENRE_MARKER = "#genre#"
    internal const val NON_TEXT_CONTENT = "playlist_non_text_content"

    /** `scheme://` — the one thing every recognised playlist line has. */
    private val URL_SCHEME = Regex("""(?i)\b[a-z][a-z0-9+.-]*://""")
}
