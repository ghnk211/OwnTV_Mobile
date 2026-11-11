package tv.own.owntv.mobile.tvbox.play

import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.tvbox.util.TextUtil
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.PlaylistItem

/**
 * Plays a TVBOX row reached through a Core screen (Library, Favourites, Continue watching, search).
 *
 * The Core row's `streamUrl` is only a `tvbox://` handle: handing it to the player — or to an
 * external player — would open a dead address. Everything after the handle is the ordinary TVbox
 * chain: resolve the site, fetch detail, parse lines, resolve the direct URL through
 * [TvboxPlayResolver] (which still enforces kids + category locks), then hand it to `playTvbox`.
 *
 * Null means "this is not a playable TVBOX row right now" — the source or site is gone, the remote
 * no longer has the item, or the user's profile may not play it. The caller leaves the Core row and
 * the user's favourite in place; only playback fails.
 */
class TvboxPlaybackRouter(
    private val sourceDao: SourceDao,
    private val configRepository: TvboxConfigRepository,
    private val sourceRepository: SourceRepository,
    private val resolver: TvboxPlayResolver,
    private val progressDao: ProgressDao,
    private val categoryDao: CategoryDao,
) {

    data class Prepared(
        val play: ResolvedPlay,
        val queue: List<PlaylistItem>,
        val episodeFlag: String,
        val episodeId: String,
        val episodeIndex: Int,
        val positionMs: Long,
    )

    suspend fun prepare(movie: MovieEntity, profileId: Long): Prepared? {
        val source = sourceDao.getById(movie.sourceId) ?: return null
        if (source.type != SourceType.TVBOX) return null
        val siteKey = TvboxIndexMapper.originSiteKey(movie.remoteId, movie.extJson) ?: return null
        val vodId = TvboxIndexMapper.splitRemoteId(movie.remoteId)?.second ?: return null
        val site = configRepository.siteMap[siteKey]
            ?: configRepository.sites.value.firstOrNull { it.key == siteKey }
            ?: return null

        val detail = sourceRepository.detailContent(site, vodId) ?: return null
        val item = detail.list.firstOrNull() ?: return null
        val lines = resolver.parseLines(item.playUrl, item.playFrom)
        if (lines.isEmpty()) return null

        // Resume the line + episode + position the profile left off at (plan §4.5.1 step 4).
        // The episode id is the stable identity; the index is the fallback when the remote's
        // episode list changed shape since the position was written.
        val saved = progressDao.get(profileId, MediaType.MOVIE, movie.id)
        val ext = TvboxExtKey.decode(saved?.extKey)
        var line = lines.first()
        var episodeIndex = 0
        if (ext != null) {
            val byId = lines.indexOfFirst { l -> l.episodes.any { it.id == ext.episodeId } }
            if (byId >= 0) {
                line = lines[byId]
                episodeIndex = line.episodes.indexOfFirst { it.id == ext.episodeId }
            } else {
                val byFlag = lines.indexOfFirst { l -> l.episodes.any { it.flag == ext.flag } }
                if (byFlag >= 0) {
                    line = lines[byFlag]
                    episodeIndex = ext.index.coerceIn(0, (line.episodes.size - 1).coerceAtLeast(0))
                }
            }
        }
        val episode = line.episodes.getOrNull(episodeIndex) ?: return null
        // The indexed category row gives the lock its canonical typeId; a search-origin row has
        // none, and the lock falls back to the name lookup (plan R14).
        val category = movie.categoryId?.let { categoryDao.getById(it) }
        val categoryKey = TvboxCategoryKey(
            siteKey = siteKey,
            siteName = site.name,
            typeId = category?.remoteId?.takeIf { it.startsWith("$siteKey/") }?.substringAfter('/'),
            typeName = category?.name ?: item.typeName.orEmpty(),
        )
        val outcome = resolver.resolve(
            profileId = profileId,
            site = site,
            vodId = vodId,
            flag = episode.flag,
            episodeId = episode.id,
            episodeIndex = episodeIndex,
            categoryKey = categoryKey,
            detailLines = lines,
            title = item.name,
            subtitle = episode.name,
            posterUrl = TextUtil.absoluteUrl(item.pic, site.api),
        )
        val play = (outcome as? TvboxPlayResolver.Outcome.Ready)?.play ?: return null
        return Prepared(
            play = play,
            queue = buildQueue(site, item, play),
            episodeFlag = episode.flag,
            episodeId = episode.id,
            episodeIndex = episodeIndex,
            positionMs = saved?.positionMs ?: 0L,
        )
    }

    /** Same queue shape the detail screen builds: each episode carries its own lazily resolved URL. */
    private fun buildQueue(
        site: tv.own.owntv.mobile.tvbox.config.model.SiteConfig,
        item: Movie,
        play: ResolvedPlay,
    ): List<PlaylistItem> {
        val line = play.lines.getOrNull(play.lineIndex) ?: return emptyList()
        if (line.episodes.size <= 1) return emptyList()
        val headers = tv.own.owntv.core.network.StreamHeaders.encode(play.headers)
        return line.episodes.mapIndexed { index, episode ->
            val key = "tvbox:${site.key}:${item.id}:$index"
            if (index == play.episodeIndex) {
                PlaylistItem(
                    url = play.url,
                    meta = MediaMeta(title = item.name, subtitle = episode.name, logoUrl = play.posterUrl, contentKey = key),
                    httpHeaders = headers,
                )
            } else {
                PlaylistItem(
                    url = episode.id,
                    meta = MediaMeta(title = item.name, subtitle = episode.name, logoUrl = play.posterUrl, contentKey = key),
                    resolveUrl = {
                        val resolved = sourceRepository.playerContent(site, episode.flag, episode.id, null)
                        resolved?.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: episode.id
                    },
                    httpHeaders = headers,
                )
            }
        }
    }
}