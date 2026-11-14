package tv.own.owntv.core.download

import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadManifest

/**
 * What a source resolver hands the transfer engine: a fresh, playable address plus the headers it
 * must travel with.
 *
 * [maxConnections] 0 means "unknown" (the ramp finds out); a one-stream source passes 1 so the
 * engine never opens a second connection beside playback.
 */
data class DownloadSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val maxConnections: Int = 0,
    val manifest: DownloadManifest = DownloadManifest.NONE,
    val containerExt: String? = null,
)

/**
 * The App-side half of downloads: Core defines the queue and the transfer, the host resolves its
 * own kind of address (a TVBOX `tvbox://` handle, for example) on every attempt.
 *
 * Deliberately a plain `interface`, not a `fun interface`: SAM conversion allows exactly one
 * abstract method, and this has two.
 */
interface DownloadSourceResolver {
    fun matches(d: DownloadEntity): Boolean

    /**
     * Called once per transfer attempt. It must return a fresh address — a direct URL carrying a
     * token or expiry must never be reused from a database row. Failures throw; the engine wraps
     * them as [DownloadReason.SOURCE_UNAVAILABLE] and retries.
     */
    suspend fun resolve(d: DownloadEntity): DownloadSource
}

/**
 * The single installed resolver. WorkManager runs in the app process by default, so the instance
 * installed from `Application.onCreate` is available to a headless download too.
 */
object DownloadSourceResolvers {
    @Volatile
    private var installed: DownloadSourceResolver? = null

    fun install(resolver: DownloadSourceResolver) {
        installed = resolver
    }

    /** Core unit tests cannot depend on an App resolver; they must be able to remove it. */
    internal fun clear() {
        installed = null
    }

    internal fun forDownload(d: DownloadEntity): DownloadSourceResolver? =
        installed?.takeIf { it.matches(d) }
}