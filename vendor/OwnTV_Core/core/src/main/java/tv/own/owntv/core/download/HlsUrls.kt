package tv.own.owntv.core.download

/** The one rule for "is this address an HLS playlist", shared by the engine and the App. */
object HlsUrls {

    /**
     * True for a `.m3u8`/`.m3u` path, ignoring any query or fragment.
     *
     * A TVBOX direct URL can carry a token in the query (`…/index.m3u8?token=…`), so the whole URL
     * must not be suffix-tested. A playlist served from a bare endpoint (`/play/123`) is not
     * detectable here; the resolver's `manifest` field covers that case.
     */
    fun isM3u8(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/').lowercase()
        return name.endsWith(".m3u8") || name.endsWith(".m3u")
    }
}