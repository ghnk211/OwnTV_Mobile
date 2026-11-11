package tv.own.owntv.mobile.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.categorylock.clearWhenSealed
import tv.own.owntv.core.categorylock.sealedCategoryIds
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.sealsItemIn
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.LinkedSubtitle
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SeriesSortOrderDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.metadata.MetadataMode
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.EpisodeLoad
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.core.subtitles.SubtitleController

/**
 * One film or one show, opened.
 *
 * A show's episodes are not in the database until somebody asks for them — Xtream and Stalker hand
 * them over per show, on open — so opening one is a load with a spinner, and re-opening it is not.
 *
 * Which item is being shown is state rather than a constructor argument, so the screen can be told
 * once in a `LaunchedEffect` and the view model needs no parameter injection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val progressDao: ProgressDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
    private val seriesRepository: SeriesRepository,
    private val downloadManager: DownloadManager,
    private val tuner: VodTuner,
    private val seriesSortOrderDao: SeriesSortOrderDao,
    private val metadata: MetadataRepository,
    private val subtitleController: SubtitleController,
    private val customize: CustomizationStore,
) : ViewModel() {

    private data class Target(val tab: LibraryTab, val id: Long)

    private val target = MutableStateFlow<Target?>(null)
    private val profileId = MutableStateFlow(-1L)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** Why the open show has no episodes when the provider refused or failed the request; null otherwise. */
    private val _episodesFailure = MutableStateFlow<String?>(null)
    val episodesFailure: StateFlow<String?> = _episodesFailure

    /** The season whose episodes are listed. Null until the show has loaded. */
    private val _season = MutableStateFlow<Int?>(null)
    val season: StateFlow<Int?> = _season

    private val _movie = MutableStateFlow<MovieEntity?>(null)

    /** The film, or null on a show. Read once: a catalogue row does not change while it is open. */
    val movie: StateFlow<MovieEntity?> = _movie

    private val _show = MutableStateFlow<SeriesEntity?>(null)

    init {
        // The title's category was just locked (the relock on leaving the app, a profile change): the
        // page stops showing it, as the lists already have.
        viewModelScope.clearWhenSealed(_movie, sealedIds(MediaType.MOVIE)) { it.categoryId }
        viewModelScope.clearWhenSealed(_show, sealedIds(MediaType.SERIES)) { it.categoryId }
    }

    private fun sealedIds(type: MediaType): Flow<Set<Long>> =
        profileId.flatMapLatest { pid -> if (pid < 0) flowOf(emptySet()) else customize.sealedCategoryIds(pid, type) }

    /** Whether the profile may have a title of [categoryId]: not an adult one on a kids profile, nor a locked one the PIN has not opened. */
    private suspend fun allows(profileId: Long, categoryId: Long?): Boolean =
        AdultCategoryClassifier.allows(profileId, categoryId, profileDao, categoryDao) && !customize.sealsCategory(profileId, categoryId)
    val show: StateFlow<SeriesEntity?> = _show

    /**
     * What a title page shows beyond the catalogue row: TMDB's record (title art, genres, cast, trailer, its
     * plot and rating), whether TMDB wins a field, the category by its parsed name, and — on the movie page
     * — a few more titles from that category. The series page uses the first three.
     */
    data class TitlePage(
        val meta: MetadataCacheEntity? = null,
        val tmdbWins: Boolean = false,
        val categoryName: String? = null,
        val more: List<MovieEntity> = emptyList(),
    )

    private val _titlePage = MutableStateFlow(TitlePage())
    val titlePage: StateFlow<TitlePage> = _titlePage

    /** The page arrives in two steps: the category and its titles at once, TMDB when it answers. */
    private suspend fun loadMoviePage(film: MovieEntity) {
        val mode = settings.metadataMode.first()
        val category = film.categoryId?.let { categoryDao.getById(it) }
        val pid = profileId.value
        val more = if (category == null || pid < 0) {
            emptyList()
        } else {
            val cust = customize.observeVisible(pid, MediaType.MOVIE).first()
            movieDao.snapshotByCategoryManual(category.id, pid, CustomizeKeys.category(category), false, MORE_IN_CATEGORY * 3)
                .filter { it.id != film.id && CustomizeKeys.movie(it) !in cust.hiddenItems && !cust.sealsItemIn(it.categoryId) }
                .take(MORE_IN_CATEGORY)
        }
        _titlePage.value = TitlePage(
            tmdbWins = mode.tmdbWins,
            categoryName = category?.let { ProviderTags.parse(it.name).name },
            more = more,
        )
        if (mode.enrich) {
            val meta = runCatching { metadata.resolveMovie(film) }.getOrNull()
            if (target.value == Target(LibraryTab.MOVIES, film.id)) _titlePage.value = _titlePage.value.copy(meta = meta)
        }
    }

    /** The series page's category and TMDB record, in the same two steps. */
    private suspend fun loadSeriesPage(show: SeriesEntity) {
        val mode = settings.metadataMode.first()
        _titlePage.value = TitlePage(
            tmdbWins = mode.tmdbWins,
            categoryName = show.categoryId?.let { categoryDao.getById(it) }?.let { ProviderTags.parse(it.name).name },
        )
        if (mode.enrich) {
            val meta = runCatching { metadata.resolveSeries(show) }.getOrNull()
            if (target.value == Target(LibraryTab.SERIES, show.id)) _titlePage.value = _titlePage.value.copy(meta = meta)
        }
    }

    val episodes: StateFlow<List<EpisodeEntity>> = target
        .flatMapLatest { t ->
            if (t?.tab == LibraryTab.SERIES) seriesDao.episodesBySeries(t.id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * How this show's seasons and episodes are presented. Persisted per profile and per series in the
     * database the TV app writes too, so a show reversed on the television opens reversed here.
     *
     * Presentation only: playing on from one episode to the next always follows episode order.
     */
    data class SeriesOrder(val seasonsDescending: Boolean = false, val episodesDescending: Boolean = false)

    val order: StateFlow<SeriesOrder> = combine(target, profileId) { t, pid -> t to pid }
        .flatMapLatest { (t, pid) ->
            if (t?.tab != LibraryTab.SERIES || pid < 0) flowOf(null) else seriesSortOrderDao.observe(pid, t.id)
        }
        .map { row -> row?.let { SeriesOrder(it.seasonsDescending, it.episodesDescending) } ?: SeriesOrder() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesOrder())

    fun setOrder(seasonsDescending: Boolean, episodesDescending: Boolean) {
        val t = target.value ?: return
        viewModelScope.launch {
            val pid = profileId.value.takeIf { it >= 0 } ?: return@launch
            seriesSortOrderDao.setOrder(pid, t.id, seasonsDescending, episodesDescending)
        }
    }

    /** The seasons this show actually has episodes for, in the order the user chose — Specials
     *  (season 0) after the numbered seasons in either order, as on the television. */
    val seasons: StateFlow<List<Int>> = combine(episodes, order) { list, o ->
        val numbers = list.map { it.seasonNumber }.distinct().sorted()
        (if (o.seasonsDescending) numbers.reversed() else numbers).sortedBy { it == 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())


    // --- Download state, so the download button reflects what is actually happening ---
    // The screen only ever asked the manager to start a transfer and then never watched it, which is
    // why the icon never changed. Shaped exactly like the television's MovieViewModel/SeriesViewModel.

    /** Every download row of this profile, keyed by film id — the film's own button reads one entry. */
    val downloadStates: StateFlow<Map<Long, DownloadEntity>> = profileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList()) else downloadManager.observe(pid) }
        .map { list -> list.filter { it.mediaType == MediaType.MOVIE }.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Episode download rows keyed by episode id — one per episode row and episode sheet. */
    val episodeDownloadStates: StateFlow<Map<Long, DownloadEntity>> = profileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList()) else downloadManager.observe(pid) }
        .map { list -> list.filter { it.mediaType == MediaType.EPISODE }.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Every episode download of the open show — the show-level button's aggregate. */
    val seriesDownloads: StateFlow<List<DownloadEntity>> = target
        .flatMapLatest { t ->
            if (t?.tab != LibraryTab.SERIES) flowOf(emptyList()) else downloadManager.observeForSeries(t.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The rows behind the header button: one film, or every episode of the show. */
    val itemDownloads: StateFlow<List<DownloadEntity>> =
        combine(target, downloadStates, seriesDownloads) { t, movies, episodes ->
            when (t?.tab) {
                LibraryTab.MOVIES -> listOfNotNull(movies[t.id])
                LibraryTab.SERIES -> episodes
                else -> emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isFavorite: StateFlow<Boolean> = combine(target, profileId) { t, pid -> t to pid }
        .flatMapLatest { (t, pid) ->
            if (t == null || pid < 0) flowOf(false) else favoriteDao.isFavorite(pid, t.tab.mediaType(), t.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Where the film was left, for the Resume button. */
    val progress: StateFlow<PlaybackProgressEntity?> = combine(target, profileId) { t, pid -> t to pid }
        .flatMapLatest { (t, pid) ->
            if (t?.tab != LibraryTab.MOVIES || pid < 0) flowOf(null)
            else progressDao.observe(pid, MediaType.MOVIE, t.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Where each episode was left — the bar under the episode rows. */
    val episodeProgress: StateFlow<Map<Long, PlaybackProgressEntity>> =
        combine(target, profileId) { t, pid -> t to pid }
            .flatMapLatest { (t, pid) ->
                if (t?.tab != LibraryTab.SERIES || pid < 0) flowOf(emptyList())
                else progressDao.observeSeriesEpisodeProgress(pid, t.id)
            }
            .map { list -> list.associateBy { it.itemId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Episodes counted as finished: ≥95 % watched, which is also what "mark watched" writes. */
    val completedIds: StateFlow<Set<Long>> = episodeProgress
        .map { prog -> prog.values.filter { it.isFinished() }.map { it.itemId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * What the "Next up" card offers: the episode still in progress, else the one after the last
     * finished, else the first — and nothing at all once the whole show has been watched.
     */
    val nextUpId: StateFlow<Long?> = combine(episodes, episodeProgress) { eps, prog ->
        if (eps.isEmpty()) return@combine null
        val ordered = eps.sortedWith(tv.own.owntv.core.content.EpisodeOrder.episodes)
        val lastWatched = prog.values.maxByOrNull { it.updatedAt } ?: return@combine ordered.first().id
        if (lastWatched.isFinished()) {
            val index = ordered.indexOfFirst { it.id == lastWatched.itemId }
            if (index in 0 until ordered.size - 1) ordered[index + 1].id else null
        } else {
            ordered.firstOrNull { it.id == lastWatched.itemId }?.id
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Hide watched" for the episode list. Off on every open, exactly as on the television. */
    private val _hideWatched = MutableStateFlow(false)
    val hideWatched: StateFlow<Boolean> = _hideWatched
    fun setHideWatched(value: Boolean) { _hideWatched.value = value }

    /**
     * Point the screen at an item. For a show this also fetches the episode list if the provider has
     * one to give, and opens on the season of the episode last watched — coming back to a series
     * three seasons in should not mean scrolling back to it every time.
     */
    fun open(tab: LibraryTab, id: Long) {
        if (target.value == Target(tab, id)) return
        target.value = Target(tab, id)
        _season.value = null
        _movie.value = null
        _show.value = null
        _episodesFailure.value = null
        _titlePage.value = TitlePage()
        viewModelScope.launch {
            profileId.value = currentProfileId() ?: -1L
            if (tab == LibraryTab.MOVIES) {
                // A title from a locked category the PIN has not opened does not open (search, a link).
                val film = movieDao.getById(id)?.takeUnless { customize.sealsCategory(profileId.value, it.categoryId) }
                _movie.value = film
                if (film != null) loadMoviePage(film)
                return@launch
            }
            _loading.value = true
            val entity = seriesDao.getSeriesById(id)?.takeUnless { customize.sealsCategory(profileId.value, it.categoryId) }
            _show.value = entity
            if (entity != null) {
                launch { loadSeriesPage(entity) }
                _episodesFailure.value = (seriesRepository.loadEpisodes(entity) as? EpisodeLoad.Failed)?.reason
            }
            _loading.value = false
            val pid = profileId.value
            val lastWatched = if (pid >= 0) progressDao.lastWatchedEpisodeId(pid, id) else null
            _season.value = lastWatched?.let { seriesDao.getEpisodeById(it)?.seasonNumber }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _season.value = seasonNumber
    }

    /** Play, from [positionMs]. [onStarted] runs only when the picture is opening here rather than
     *  in another app, which is what decides whether the player screen is worth navigating to. */
    fun play(positionMs: Long, onStarted: () -> Unit) {
        val t = target.value ?: return
        viewModelScope.launch {
            val started = when (t.tab) {
                LibraryTab.MOVIES -> tuner.playMovie(t.id, positionMs)
                LibraryTab.SERIES -> firstEpisodeToPlay()?.let { tuner.playEpisode(it, positionMs) } == true
            }
            if (started) onStarted()
        }
    }

    fun playEpisode(episodeId: Long, positionMs: Long, onStarted: () -> Unit) {
        viewModelScope.launch { if (tuner.playEpisode(episodeId, positionMs)) onStarted() }
    }

    fun playExternal(episode: EpisodeEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            show.value?.let { tuner.playExternal(it, episode) }
            onDone()
        }
    }

    fun toggleFavorite() {
        val t = target.value ?: return
        viewModelScope.launch {
            val pid = profileId.value.takeIf { it >= 0 } ?: return@launch
            if (isFavorite.value) userDataWriter.removeFavorite(pid, t.tab.mediaType(), t.id)
            else favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = t.tab.mediaType(), itemId = t.id))
        }
    }

    fun setEpisodeWatched(episode: EpisodeEntity, watched: Boolean) {
        viewModelScope.launch {
            val pid = profileId.value.takeIf { it >= 0 } ?: return@launch
            if (watched) {
                progressDao.save(
                    PlaybackProgressEntity(
                        profileId = pid,
                        mediaType = MediaType.EPISODE,
                        itemId = episode.id,
                        positionMs = 1L,
                        durationMs = 1L,
                    ),
                )
            } else {
                userDataWriter.clearProgress(pid, MediaType.EPISODE, episode.id)
            }
        }
    }

    /**
     * Download the film, or **every episode of the show** — which is what the button has always
     * said, and what the television has always done.
     *
     * It used to take the episodes of the season on screen, and the season on screen is null until
     * either the user taps a season chip or the show has an episode they have already watched. So on
     * a show opened for the first time the filter matched nothing and the button did nothing at all.
     * The episodes are read from the database here rather than from the listed ones, the same way
     * `SeriesViewModel.downloadSeries` does, so what is queued never depends on what is on screen.
     */
    fun download() {
        viewModelScope.launch {
            val pid = profileId.value.takeIf { it >= 0 } ?: return@launch
            val film = movie.value
            if (film != null) {
                if (!allows(pid, film.categoryId)) return@launch
                // See LibraryViewModel.download: a tvbox:// handle is not a downloadable address.
                if (film.streamUrl.startsWith(tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper.STREAM_PREFIX)) return@launch
                downloadManager.enqueue(
                    profileId = pid,
                    mediaType = MediaType.MOVIE,
                    itemId = film.id,
                    title = film.name,
                    posterUrl = film.posterUrl,
                    streamUrl = film.streamUrl,
                    relativeDir = MOVIES_DIR,
                    fileName = "${StorageAccess.sanitize(film.name)}." +
                        (film.containerExt ?: StorageAccess.extOf(film.streamUrl)),
                )
                return@launch
            }
            val current = show.value ?: return@launch
            if (!allows(pid, current.categoryId)) return@launch
            seriesDao.episodesBySeriesOnce(current.id).forEach { downloadEpisode(current, it, pid) }
        }
    }

    /** How many episodes [season] holds, handed to [then] when there are any. */
    fun countSeasonEpisodes(season: Int, then: (Int) -> Unit) {
        viewModelScope.launch {
            val current = show.value ?: return@launch
            seriesDao.episodesBySeriesOnce(current.id).count { it.seasonNumber == season }.takeIf { it > 0 }?.let(then)
        }
    }

    /** The series page's ⬇: every episode of [season], as the TV's "Download season". */
    fun downloadSeason(season: Int) {
        viewModelScope.launch {
            val pid = profileId.value.takeIf { it >= 0 } ?: return@launch
            val current = show.value ?: return@launch
            if (!allows(pid, current.categoryId)) return@launch
            seriesDao.episodesBySeriesOnce(current.id).filter { it.seasonNumber == season }.forEach { downloadEpisode(current, it, pid) }
        }
    }

    fun download(episode: EpisodeEntity) {
        viewModelScope.launch {
            val pid = profileId.value.takeIf { it >= 0 } ?: return@launch
            val current = show.value ?: return@launch
            if (!allows(pid, current.categoryId)) return@launch
            downloadEpisode(current, episode, pid)
        }
    }

    /** Start the failed rows over — the button that showed the failure is the one that retries it. */
    fun retryDownloads(rows: List<DownloadEntity>) {
        rows.filter { it.status == DownloadStatus.FAILED }.forEach { downloadManager.retry(it) }
    }

    /** Cancel what is in flight, or remove what has already been saved. Both are one tap, both undoable. */
    fun deleteDownloads(rows: List<DownloadEntity>) {
        rows.forEach { downloadManager.delete(it) }
    }

    private suspend fun downloadEpisode(show: SeriesEntity, episode: EpisodeEntity, profileId: Long) {
        downloadManager.enqueue(
            profileId = profileId,
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

    val metadataMode: StateFlow<MetadataMode> = settings.metadataMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetadataMode.PROVIDER_PLUS_TMDB)

    /**
     * TMDB's record for every episode of the listed season, keyed by episode id — the titles, stills, plots
     * and air dates of the rows. The TV's flow: what is cached at once, and an incomplete
     * season fetched in one request after [SEASON_DWELL_MS], so a show opened by mistake costs nothing and
     * leaving cancels the fetch.
     */
    val seasonMeta: StateFlow<Map<Long, MetadataCacheEntity>> =
        combine(show, episodes, _season, seasons) { series, eps, season, all ->
            val active = season ?: all.firstOrNull()
            series to eps.filter { it.seasonNumber == active }
        }
            .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second.map { it.id } == b.second.map { it.id } }
            .flatMapLatest { (series, listed) ->
                flow {
                    if (series == null || listed.isEmpty()) {
                        emit(emptyMap())
                        return@flow
                    }
                    val cached = runCatching { metadata.cachedSeasonEpisodes(series, listed) }.getOrDefault(emptyMap())
                    emit(cached)
                    if (cached.size >= listed.size) return@flow
                    delay(SEASON_DWELL_MS)
                    emit(runCatching { metadata.resolveSeasonEpisodes(series, listed) }.getOrDefault(cached))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Episodes as stills two a row or as rows — the setting the TV's series page writes too. */
    val episodeViewMode: StateFlow<SettingsRepository.VodViewMode> = settings.episodeViewMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.VodViewMode.LIST)

    fun setEpisodeViewMode(mode: SettingsRepository.VodViewMode) {
        viewModelScope.launch { settings.setEpisodeViewMode(mode) }
    }

    /** The TMDB record for one episode — the still, the plot, the air date. Null when off or unmatched. */
    suspend fun episodeMeta(episode: EpisodeEntity): MetadataCacheEntity? = runCatching {
        show.value?.let { metadata.resolveEpisode(it, episode) }
    }.getOrNull()

    /** Forget what TMDB said about this episode, so the next resolve asks again. */
    suspend fun clearEpisodeMeta(episode: EpisodeEntity) {
        show.value?.let { metadata.clearEpisode(it, episode) }
    }

    suspend fun downloadedSubtitles(episode: EpisodeEntity): List<LinkedSubtitle> =
        show.value?.let { subtitleController.downloadsForEpisode(it, episode) }.orEmpty()

    fun deleteSubtitle(cacheId: Long) {
        viewModelScope.launch { subtitleController.deleteCached(cacheId) }
    }

    /** Play on a show means carry on: the episode left unfinished, else the first one. */
    private suspend fun firstEpisodeToPlay(): Long? {
        val t = target.value ?: return null
        val pid = profileId.value
        val lastWatched = if (pid >= 0) progressDao.lastWatchedEpisodeId(pid, t.id) else null
        return lastWatched ?: episodes.value.minWithOrNull(tv.own.owntv.core.content.EpisodeOrder.episodes)?.id
    }

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }
}

/** "More in Disney+ Kids Movies": one row of four. */
private const val MORE_IN_CATEGORY = 4

/** How long a season stays listed before its missing TMDB details are fetched — the TV's grid dwell. */
private const val SEASON_DWELL_MS = 1_000L

private fun LibraryTab.mediaType() =
    if (this == LibraryTab.MOVIES) MediaType.MOVIE else MediaType.SERIES

/** Finished at 95 %, which is also where the mark-watched marker and the TV app's card sit. */
private fun PlaybackProgressEntity.isFinished(): Boolean =
    durationMs > 0 && positionMs >= (durationMs * 0.95f).toLong()
