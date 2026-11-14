package tv.own.owntv.mobile.ui.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.mobile.ui.screens.library.MOVIES_DIR
import tv.own.owntv.mobile.ui.screens.library.episodeDir
import tv.own.owntv.mobile.ui.screens.library.episodeFileName

/**
 * What a long-press menu can do to a channel, a film or a show, wherever it was pressed.
 *
 * Home and Search both offer the short menu — everything an item can do to itself, and nothing that
 * needs the list it would normally sit in. One copy, because two would be a favourite that works on
 * one screen and not the other the first time either is changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentActions(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val favoriteDao: FavoriteDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    private val customize: CustomizationStore,
    private val downloadManager: DownloadManager,
) {

    /** The favourites of one kind, for the profile that is active now. */
    fun favoriteIds(type: MediaType): Flow<Set<Long>> = activeProfileSources(settings, sourceDao)
        .map { it.profileId }
        .distinctUntilChanged()
        .flatMapLatest { favoriteDao.observeFavoriteIds(it, type) }
        .map { it.toSet() }

    suspend fun toggleFavorite(type: MediaType, itemId: Long) {
        val pid = profileId() ?: return
        if (favoriteDao.isFavorite(pid, type, itemId).first()) userDataWriter.removeFavorite(pid, type, itemId)
        else favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = type, itemId = itemId))
    }

    /** Hide the item everywhere. Undone in Settings → Customize, exactly as on the television. */
    suspend fun hide(type: MediaType, itemId: Long) {
        val pid = profileId() ?: return
        when (type) {
            MediaType.LIVE -> channelDao.getById(itemId)?.let {
                customize.setItemHidden(pid, MediaType.LIVE, CustomizeKeys.channel(it), it.name, true)
            }
            MediaType.MOVIE -> movieDao.getById(itemId)?.let {
                customize.setItemHidden(pid, MediaType.MOVIE, CustomizeKeys.movie(it), it.name, true)
            }
            else -> seriesDao.getSeriesById(itemId)?.let {
                customize.setItemHidden(pid, MediaType.SERIES, CustomizeKeys.series(it), it.name, true)
            }
        }
    }

    /** Download a film, or every episode of a show — the same layout on disk the library writes. */
    suspend fun download(type: MediaType, itemId: Long) {
        val pid = profileId() ?: return
        if (type == MediaType.MOVIE) {
            val movie = movieDao.getById(itemId) ?: return
            // A tvbox:// row is an identity handle, not a downloadable address. The detail page owns
            // the line/episode choice; this menu must never queue a task that can only fail.
            if (movie.streamUrl.startsWith(tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper.STREAM_PREFIX)) return
            if (!AdultCategoryClassifier.allows(pid, movie.categoryId, profileDao, categoryDao)) return
            downloadManager.enqueue(
                profileId = pid,
                mediaType = MediaType.MOVIE,
                itemId = movie.id,
                title = movie.name,
                posterUrl = movie.posterUrl,
                streamUrl = movie.streamUrl,
                relativeDir = MOVIES_DIR,
                fileName = "${StorageAccess.sanitize(movie.name)}." +
                    (movie.containerExt ?: StorageAccess.extOf(movie.streamUrl)),
            )
        } else {
            val show = seriesDao.getSeriesById(itemId) ?: return
            if (!AdultCategoryClassifier.allows(pid, show.categoryId, profileDao, categoryDao)) return
            seriesDao.episodesBySeriesOnce(show.id).forEach { episode ->
                downloadManager.enqueue(
                    profileId = pid,
                    mediaType = MediaType.EPISODE,
                    itemId = episode.id,
                    title = episode.name.takeIf { it.isNotBlank() } ?: show.name,
                    posterUrl = show.posterUrl,
                    streamUrl = episode.streamUrl,
                    relativeDir = episodeDir(show.name, episode.seasonNumber),
                    fileName = episodeFileName(
                        episode.name,
                        episode.episodeNumber,
                        episode.containerExt,
                        episode.streamUrl,
                    ),
                )
            }
        }
    }

    private suspend fun profileId(): Long? =
        activeProfileSources(settings, sourceDao).first().profileId.takeIf { it >= 0 }
}
