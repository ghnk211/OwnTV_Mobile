package tv.own.owntv.mobile.tvbox.download

import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.download.DownloadSource
import tv.own.owntv.core.download.DownloadSourceResolver
import tv.own.owntv.core.download.HlsUrls
import tv.own.owntv.core.model.DownloadManifest
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.mobile.tvbox.di.TvboxRuntime
import tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper
import tv.own.owntv.mobile.tvbox.play.TvboxPlayResolver
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository

/**
 * The Core SPI implementation for TVBOX rows (plan §6.1).
 *
 * Core owns the queue and the transfer; this only turns the row's `tvbox://<site>/<vod>` identity
 * back into a fresh direct URL and its headers. It runs on every attempt, including a headless
 * WorkManager process, so it must restore the TVBOX config/engines without relying on a screen.
 */
class TvboxDownloadSourceResolver(
    private val configRepository: TvboxConfigRepository,
    private val resolver: TvboxPlayResolver,
    private val runtime: TvboxRuntime,
    private val movieDao: MovieDao,
) : DownloadSourceResolver {

    /**
     * Every `tvbox://` row belongs to us, even when `extJson` is corrupt: the resolver then throws
     * and the engine reports `SOURCE_UNAVAILABLE`. Matching on `extJson` would instead fall through
     * to the scheme guard and say "this source can't be downloaded", which is the wrong diagnosis.
     */
    override fun matches(d: DownloadEntity): Boolean =
        d.streamUrl.startsWith(TvboxIndexMapper.STREAM_PREFIX)

    override suspend fun resolve(d: DownloadEntity): DownloadSource {
        val ref = TvboxDownloadKey.decode(d.extJson) ?: error("bad tvbox download identity")

        // A borrowed row's origin site is recorded on the Core row (donorSiteKey); ref.siteKey is
        // the fallback captured when the download was queued.
        val movie = movieDao.getById(d.itemId)
        val siteKey = movie?.let { TvboxIndexMapper.originSiteKey(it.remoteId, it.extJson) } ?: ref.siteKey
        val site = configRepository.siteMap[siteKey] ?: error("site $siteKey is gone")

        // Config + engine only. Calling TvboxRuntime.restore() here would also run syncAll() and
        // maintainIndex(); with an empty config list syncAll turns every TVBOX source's syncMovies
        // off, which is far too much side effect for a background resolve.
        runtime.ensureReady()

        // A background resolve must not push the source into cooldown when a direct URL expired.
        val stream = resolver.resolveUrl(site, ref.flag, ref.episodeId, countHealth = false)
            ?: error("playerContent failed for ${site.key}")

        val hls = HlsUrls.isM3u8(stream.url)
        return DownloadSource(
            url = stream.url,
            headers = stream.headers,
            maxConnections = 1,
            manifest = if (hls) DownloadManifest.HLS else DownloadManifest.NONE,
            containerExt = if (hls) null else StorageAccess.extOf(stream.url),
        )
    }
}