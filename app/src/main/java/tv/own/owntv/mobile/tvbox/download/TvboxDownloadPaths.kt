package tv.own.owntv.mobile.tvbox.download

import tv.own.owntv.core.download.HlsUrls
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.mobile.tvbox.engine.Episode

/** Where a TVBOX episode is written under the user's download root. */
object TvboxDownloadPaths {

    /**
     * `Movies/<film>/<lineIndex+1>-<line>/`.
     *
     * The line index is part of the folder name because two lines can share a name ("线路1"): with
     * only the name, episode files would collide and SAF would hand back the existing document,
     * turning a second download into a truncate/append of the first.
     */
    fun dir(name: String, lineIndex: Int, lineName: String): String {
        val film = StorageAccess.sanitize(name)
        val line = StorageAccess.sanitize(lineName)
        return "Movies/$film/${lineIndex + 1}-$line"
    }

    fun fileName(episode: Episode, containerExt: String?): String {
        val stem = StorageAccess.sanitize(episode.name.ifBlank { "ep-${episode.index + 1}" })
        return "$stem.${containerExt ?: StorageAccess.extOf(episode.url)}"
    }

    /**
     * P0 only downloads a direct file, so an m3u8 has no container: the caller must refuse it
     * before the row exists (otherwise the engine would save the playlist text). P1 replaces this
     * with the playlist probe (ts/mp4).
     */
    fun probeContainer(url: String): String? =
        if (HlsUrls.isM3u8(url)) null else StorageAccess.extOf(url)
}