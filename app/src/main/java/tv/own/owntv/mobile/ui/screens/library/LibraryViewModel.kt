package tv.own.owntv.mobile.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.content.movieCountFlow
import tv.own.owntv.core.content.moviePagingSource
import tv.own.owntv.core.content.seriesCountFlow
import tv.own.owntv.core.content.seriesPagingSource
import tv.own.owntv.core.customize.CategoryRestorePlan
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.planCategoryRestore
import tv.own.owntv.core.customize.restoreCategoryToDefault
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.customize.CategoryRailEditor
import tv.own.owntv.core.customize.MoveKind
import tv.own.owntv.core.customize.railCategories
import tv.own.owntv.core.customize.seals
import tv.own.owntv.core.customize.sealsItemIn
import tv.own.owntv.core.categorylock.visibleOnceRead
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.LinkedSubtitle
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.parseLiveKey
import tv.own.owntv.core.live.serialize
import tv.own.owntv.core.metadata.MetadataMode
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.TitleNormalizer
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.core.storage.MediaFolders
import tv.own.owntv.core.subtitles.SubtitleController
import tv.own.owntv.mobile.ui.components.ReorderItem

/** Which half of the library is on screen. */
enum class LibraryTab { MOVIES, SERIES }

/** One entry of the categories sheet. [builtIn] labels are translated; the rest are provider or user names.
 *  [sourceId] is the playlist a provider folder belongs to (its mark in the sheet). [locked]: behind the
 *  Customize PIN; [sealed] while the PIN has not opened it this session. */
data class VodCategory(
    val key: LiveKey,
    val title: String? = null,
    val builtIn: BuiltIn? = null,
    val sourceId: Long? = null,
    val locked: Boolean = false,
    val sealed: Boolean = false,
) {
    enum class BuiltIn { ALL, FAVORITES, HISTORY }
}

/** A poster in the grid. Films and shows differ in almost nothing the grid draws, so they share one. */
data class VodItem(
    val id: Long,
    val name: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val sourceId: Long,
    /** The provider folder it was synced into — the menu's "2026 · Disney+ Kids Movies". */
    val categoryId: Long?,
    /** A film's length; null on a show. */
    val durationSecs: Int? = null,
)

/**
 * Movies and Series for the phone.
 *
 * One view model for both, because they are the same screen twice: the same rail selection type, the
 * same queries in core's [moviePagingSource] / [seriesPagingSource], the same chips, and the same
 * long-press actions. What differs is a table name.
 *
 * Nothing here queries until a tab is actually collected — a catalogue of 170,000 films must not cost
 * anything at launch, and the count under the chips is the only whole-list question ever asked.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class LibraryViewModel(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    /** Lets the browse screen tell core's catalogue drain which category to fill first. */
    private val catalogPriority: tv.own.owntv.core.sync.CatalogPriority,
    private val categoryDao: CategoryDao,
    private val customCategoryDao: CustomCategoryDao,
    private val contentOrderDao: ContentOrderDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val progressDao: ProgressDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val downloadManager: DownloadManager,
    private val vodTuner: VodTuner,
    private val metadata: MetadataRepository,
    private val subtitleController: SubtitleController,
) : ViewModel() {

    private val _tab = MutableStateFlow(LibraryTab.MOVIES)
    val tab: StateFlow<LibraryTab> = _tab

    private val _selectedMovies = MutableStateFlow<LiveKey>(LiveKey.All)
    private val _selectedSeries = MutableStateFlow<LiveKey>(LiveKey.All)

    /** The chip the current tab is on. */
    val selected: StateFlow<LiveKey> =
        combine(_tab, _selectedMovies, _selectedSeries) { tab, movies, series ->
            if (tab == LibraryTab.MOVIES) movies else series
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveKey.All)

    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveProfileSources(-1L, emptyList()))

    private val mediaType: StateFlow<MediaType> = _tab
        .map { if (it == LibraryTab.MOVIES) MediaType.MOVIE else MediaType.SERIES }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaType.MOVIE)

    /**
     * This profile's customizations for the open tab, with the categories a lock keeps shut counted as
     * hidden; null until read. The grids wait for them: one built from the empty default would show a
     * locked category's titles for a moment.
     */
    private val customOnceRead: StateFlow<SectionCustomizations?> = combine(ctx, mediaType) { c, type -> c to type }
        .flatMapLatest { (c, type) -> customize.visibleOnceRead(c.profileId, type) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val custom: StateFlow<SectionCustomizations> = customOnceRead.filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionCustomizations())

    /** The locked provider folders not open this session: what they hold leaves every count. */
    private val sealedCategoryIds: Flow<Set<Long>> = custom.map { it.sealedCategoryIds }.distinctUntilChanged()

    val sortMode: StateFlow<SettingsRepository.SortMode> = _tab
        .flatMapLatest { if (it == LibraryTab.MOVIES) settings.sortMovies else settings.sortSeries }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.SortMode.PLAYLIST)

    val viewMode: StateFlow<SettingsRepository.VodViewMode> = settings.vodViewMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.VodViewMode.GRID)

    /** How many posters a row holds. 0 means "decide from the width of the screen". */
    val gridColumns: StateFlow<Int> = settings.vodGridColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val vodCategories = combine(ctx, mediaType) { c, type -> c to type }
        .flatMapLatest { (c, type) ->
            if (c.profileId < 0) flowOf(emptyList()) else categoryDao.observe(c.sourceIdsFor(type), type)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Categories this profile does not see — its hidden ones, plus every adult category on a kids
     *  profile. Resolved to ids so All and History drop those items too, as the TV app does. */
    private val hiddenCategoryIds: StateFlow<Set<Long>> = combine(
        vodCategories,
        custom,
        ctx.flatMapLatest { profileDao.observeById(it.profileId) },
    ) { cats, cust, profile ->
        AdultCategoryClassifier.hiddenCategoryIds(cats, cust.hiddenCategories, profile?.isKids == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Eagerly: nothing collects it, it is only read as `.value` (the pager, Move, restore) — shared
    // "while subscribed" it stayed empty and Move was never offered in a folder (Live TV had the same fix).
    private val folderContextKeys: StateFlow<Map<Long, String>> = vodCategories
        .map { cats -> cats.associateBy({ it.id }, { CustomizeKeys.category(it) }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Contexts that actually carry a manual order — core's query takes the cheap path for the rest,
     *  and the pager is rebuilt when a folder gains or loses one. */
    private val orderedContexts: StateFlow<Set<String>> = combine(ctx, mediaType) { c, type -> c to type }
        .flatMapLatest { (c, type) ->
            if (c.profileId < 0) flowOf(emptyList()) else contentOrderDao.observeContextKeys(c.profileId, type)
        }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The chip strip: All, Favorites, History, then the profile's own arranged folders. */
    val categories: StateFlow<List<VodCategory>> = combine(
        vodCategories,
        custom,
        sortMode,
        ctx.flatMapLatest { profileDao.observeById(it.profileId) },
    ) { cats, cust, sort, profile ->
        val folders = cats.railCategories(
            cust,
            kids = profile?.isKids == true,
            alphabetical = sort == SettingsRepository.SortMode.ALPHA,
        )
        val sourceOf = cats.associateBy({ it.id }, { it.sourceId })
        buildList {
            add(VodCategory(LiveKey.All, builtIn = VodCategory.BuiltIn.ALL))
            add(VodCategory(LiveKey.Favorites, builtIn = VodCategory.BuiltIn.FAVORITES))
            add(VodCategory(LiveKey.History, builtIn = VodCategory.BuiltIn.HISTORY))
            folders.forEach { e ->
                add(
                    VodCategory(
                        key = e.categoryId?.let { LiveKey.Folder(it) } ?: LiveKey.Custom(e.customId!!),
                        title = e.displayName,
                        sourceId = e.categoryId?.let(sourceOf::get),
                        locked = e.locked,
                        sealed = e.sealed,
                    ),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every entry's count for the categories sheet — read only while the sheet is open. */
    val categoryCounts: StateFlow<Map<LiveKey, Int>> = combine(ctx, mediaType, hiddenCategoryIds, categories, ::CountArgs)
        .combine(sealedCategoryIds) { args, sealed -> args to sealed }
        .flatMapLatest { (args, sealed) ->
            val (c, type, hidden, items) = args
            if (c.profileId < 0) return@flatMapLatest flowOf(emptyMap())
            val ids = c.sourceIdsFor(type)
            val movie = type == MediaType.MOVIE
            val single = items.filter { it.key !is LiveKey.Folder }.map { item ->
                (if (movie) movieCountFlow(item.key, c.profileId, ids, hidden, movieDao, customCategoryDao, sealed)
                else seriesCountFlow(item.key, c.profileId, ids, hidden, seriesDao, customCategoryDao, sealed))
                    .map { mapOf(item.key to it) }
            }
            // A sealed folder shows no count: how many titles it holds is the lock's business.
            val folders = (if (movie) movieDao.observeCountsByCategory(ids.ifEmpty { listOf(-1L) }) else seriesDao.observeCountsByCategory(ids.ifEmpty { listOf(-1L) }))
                .map { rows -> rows.filter { it.categoryId !in sealed }.associate { LiveKey.Folder(it.categoryId) as LiveKey to it.itemCount } }
            combine(single + folders) { parts -> parts.fold(emptyMap<LiveKey, Int>()) { acc, m -> acc + m } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private data class CountArgs(
        val ctx: ActiveProfileSources,
        val type: MediaType,
        val hidden: Set<Long>,
        val items: List<VodCategory>,
    )

    /** Settings › Layout › Navigation › Show counts. */
    val sheetCounts: StateFlow<Boolean> = settings.sheetCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Which playlist a poster or row comes from — only when more than one is shown, as on Live TV. */
    val playlistMarks: StateFlow<Map<Long, PlaylistMark>> = combine(ctx, mediaType) { c, type ->
        if (c.sourceIdsFor(type).size < 2) emptyMap()
        else c.sources.withIndex().associate { (i, s) -> s.id to PlaylistMark.of(s.name, i) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Hide and reorder a category from the strip, through the same editor Settings → Customize uses. */
    private val categoryEditor = CategoryRailEditor(customize, categoryDao, profileDao)

    /** Hide the long-pressed category. It comes back from Settings → Customize. */
    fun hideCategory(key: LiveKey) {
        viewModelScope.launch {
            val c = ctx.value
            if (c.profileId < 0) return@launch
            categoryEditor.hide(c.profileId, mediaType.value, key)
            // The strip is about to lose this chip; leaving it selected would show a folder that is
            // no longer there.
            if (selected.value == key) select(LiveKey.All)
        }
    }

    /** Rename the long-pressed category; a blank name restores the provider's own. */
    fun renameCategory(key: LiveKey, name: String?) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            val contextKey = when (key) {
                is LiveKey.Folder -> folderContextKeys.value[key.id]
                is LiveKey.Custom -> key.id
                else -> null
            } ?: return@launch
            customize.renameCategory(pid, mediaType.value, contextKey, name?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    /** Move the long-pressed category one step through the strip, and keep it there. */
    fun moveCategory(key: LiveKey, kind: MoveKind) {
        viewModelScope.launch {
            val c = ctx.value
            if (c.profileId < 0) return@launch
            categoryEditor.move(
                profileId = c.profileId,
                sourceIds = c.sourceIdsFor(mediaType.value),
                type = mediaType.value,
                alphabetical = sortMode.value == SettingsRepository.SortMode.ALPHA,
                key = key,
                kind = kind,
            )
        }
    }

    /** How many items the current selection holds — the one whole-list question the screen asks. */
    val count: StateFlow<Int> = combine(mediaType, selected, ctx, hiddenCategoryIds, ::Counted)
        .combine(sealedCategoryIds) { counted, sealed -> counted to sealed }
        .flatMapLatest { (counted, sealed) ->
            val (type, key, c, hidden) = counted
            when {
                c.profileId < 0 -> flowOf(0)
                type == MediaType.MOVIE ->
                    movieCountFlow(key, c.profileId, c.sourceIdsFor(type), hidden, movieDao, customCategoryDao, sealed)
                else ->
                    seriesCountFlow(key, c.profileId, c.sourceIdsFor(type), hidden, seriesDao, customCategoryDao, sealed)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private data class Counted(
        val type: MediaType,
        val key: LiveKey,
        val ctx: ActiveProfileSources,
        val hidden: Set<Long>,
    )

    private data class Args(
        val key: LiveKey,
        val ctx: ActiveProfileSources,
        val cust: SectionCustomizations,
        val hidden: Set<Long>,
        val query: String = "",
        val sort: SettingsRepository.SortMode = SettingsRepository.SortMode.PLAYLIST,
    )

    private val _query = MutableStateFlow("")

    /** The tool row's search, inside the open category. Cleared when the category changes. */
    val query: StateFlow<String> = _query

    fun setQuery(text: String) {
        _query.value = text
    }

    /**
     * The search and the sort order are inputs of the pager, so changing either re-queries. The order
     * used to be read once, when the pager was made — so picking another one in the sheet re-sorted
     * nothing until something else happened to rebuild the list.
     */
    private fun args(selected: Flow<LiveKey>, sort: Flow<SettingsRepository.SortMode>): Flow<Args> =
        combine(selected, ctx, customOnceRead.filterNotNull(), hiddenCategoryIds, ::Args)
            .combine(orderedContexts) { args, _ -> args }
            .combine(_query.debounce(SEARCH_DEBOUNCE_MS)) { args, q -> args.copy(query = q.trim()) }
            .combine(sort) { args, s -> args.copy(sort = s) }

    /** The films of the current selection. Collected only while the Movies tab is on screen. */
    val movies: Flow<PagingData<VodItem>> =
        args(_selectedMovies, settings.sortMovies)
            .flatMapLatest { (key, c, cust, hidden, query, sort) ->
                if (c.profileId < 0) {
                    flowOf(PagingData.empty())
                } else {
                    Pager(pagingConfig()) {
                        moviePagingSource(
                            key = key,
                            profileId = c.profileId,
                            sourceIds = c.sourceIdsFor(MediaType.MOVIE),
                            query = query,
                            sort = sort,
                            movieDao = movieDao,
                            customCategoryDao = customCategoryDao,
                            contextKey = { folderContextKeys.value[it] },
                            hasManualOrder = { it in orderedContexts.value },
                        )
                    }.flow.map { paging ->
                        paging.customized(key, cust, hidden, { CustomizeKeys.movie(it) }, { it.categoryId })
                            .map { m -> m.asItem(cust) }
                    }
                }
            }
            .cachedIn(viewModelScope)

    /** [movies] for shows. */
    val series: Flow<PagingData<VodItem>> =
        args(_selectedSeries, settings.sortSeries)
            .flatMapLatest { (key, c, cust, hidden, query, sort) ->
                if (c.profileId < 0) {
                    flowOf(PagingData.empty())
                } else {
                    Pager(pagingConfig()) {
                        seriesPagingSource(
                            key = key,
                            profileId = c.profileId,
                            sourceIds = c.sourceIdsFor(MediaType.SERIES),
                            query = query,
                            sort = sort,
                            seriesDao = seriesDao,
                            customCategoryDao = customCategoryDao,
                            contextKey = { folderContextKeys.value[it] },
                            hasManualOrder = { it in orderedContexts.value },
                        )
                    }.flow.map { paging ->
                        paging.customized(key, cust, hidden, { CustomizeKeys.series(it) }, { it.categoryId })
                            .map { s -> s.asItem(cust) }
                    }
                }
            }
            .cachedIn(viewModelScope)

    /**
     * Hidden items and hidden categories drop out, and so does everything in a category a lock keeps
     * shut; an item moved into a custom category leaves the folder it came from while staying in All.
     *
     * Applied on each fresh `PagingData` inside the pager chain: Paging forbids transforming one the
     * UI has already collected, so a customization change re-creates the pager instead.
     */
    private fun <T : Any> PagingData<T>.customized(
        key: LiveKey,
        cust: SectionCustomizations,
        hidden: Set<Long>,
        itemKey: (T) -> String,
        categoryOf: (T) -> Long?,
    ): PagingData<T> {
        if (cust.hiddenItems.isEmpty() && hidden.isEmpty() && cust.movedFromOrigin.isEmpty() && cust.sealedCategories.isEmpty()) return this
        return filter { item ->
            itemKey(item) !in cust.hiddenItems && !cust.seals(key) && !cust.sealsItemIn(categoryOf(item)) &&
                (categoryOf(item) == null || categoryOf(item) !in hidden) &&
                (cust.movedFromOrigin[itemKey(item)]?.let { origin ->
                    key !is LiveKey.Folder || origin != folderContextKeys.value[key.id]
                } ?: true)
        }
    }

    private fun MovieEntity.asItem(cust: SectionCustomizations) = VodItem(
        id = id,
        name = cust.itemNames[CustomizeKeys.movie(this)] ?: name,
        posterUrl = posterUrl,
        year = year,
        rating = rating,
        sourceId = sourceId,
        categoryId = categoryId,
        durationSecs = durationSecs,
    )

    private fun SeriesEntity.asItem(cust: SectionCustomizations) = VodItem(
        id = id,
        name = cust.itemNames[CustomizeKeys.series(this)] ?: name,
        posterUrl = posterUrl,
        year = year,
        rating = rating,
        sourceId = sourceId,
        categoryId = categoryId,
    )

    val favoriteIds: StateFlow<Set<Long>> = combine(ctx, mediaType) { c, type -> c to type }
        .flatMapLatest { (c, type) -> favoriteDao.observeFavoriteIds(c.profileId, type) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Resume positions for films, keyed by film id — the bar along the bottom of a poster. Only a
     *  started film has a row, so this is a handful of entries and not the catalogue. */
    val movieProgress: StateFlow<Map<Long, PlaybackProgressEntity>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyList()) else progressDao.observeMovieProgress(c.profileId)
        }
        .map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Per show, how far into its most recently watched episode this profile is, in 0..1 — the bar on
     *  a show's poster. A finished latest episode has no entry: there is nothing left to resume. */
    private val latestEpisodes = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyList()) else progressDao.observeLatestEpisodeProgressPerSeries(c.profileId)
        }

    val seriesProgress: StateFlow<Map<Long, Float>> = latestEpisodes
        .map { rows ->
            rows.filter { it.durationMs > 0 && it.positionMs < (it.durationMs * WATCHED_FRACTION).toLong() }
                .associate { it.seriesId to it.positionMs.toFloat() / it.durationMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The ✓ of a poster or row: a film watched to the end, or a show whose latest episode was (Home's rule;
     * core has no "whole series finished").
     */
    val watchedIds: StateFlow<Set<Long>> = combine(_tab, movieProgress, latestEpisodes) { tab, movies, episodes ->
        if (tab == LibraryTab.MOVIES) {
            movies.values.filter { it.durationMs > 0 && it.positionMs >= (it.durationMs * WATCHED_FRACTION).toLong() }
                .mapTo(HashSet()) { it.itemId }
        } else {
            episodes.filter { it.durationMs > 0 && it.positionMs >= (it.durationMs * WATCHED_FRACTION).toLong() }
                .mapTo(HashSet()) { it.seriesId }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _seasonCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** Seasons stored for a show, for its list row ("1 season"). A show never opened has none stored: 0, not shown. */
    val seasonCounts: StateFlow<Map<Long, Int>> = _seasonCounts

    /** Read the season counts of the rows on screen, once each — one batched query per scroll settle. */
    fun loadSeasonCounts(seriesIds: List<Long>) {
        val missing = seriesIds.filterNot { it in _seasonCounts.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val found = seriesDao.storedSeasonCounts(missing).associate { it.seriesId to it.seasonCount }
            _seasonCounts.value += missing.associateWith { found[it] ?: 0 }
        }
    }

    /** What restoring [key]'s category to the playlist default would undo (empty when nothing) — the TV's. */
    suspend fun categoryRestorePlan(key: LiveKey): CategoryRestorePlan {
        val contextKey = categoryContextKey(key) ?: return CategoryRestorePlan()
        val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return CategoryRestorePlan()
        val type = mediaType.value
        return planCategoryRestore(customize, contentOrderDao, pid, type, contextKey, customCategoryDao) {
            when (key) {
                is LiveKey.Folder -> if (type == MediaType.MOVIE) {
                    movieDao.snapshotByCategoryManual(key.id, pid, contextKey, false, Int.MAX_VALUE).mapTo(HashSet()) { CustomizeKeys.movie(it) }
                } else {
                    seriesDao.snapshotByCategoryManual(key.id, pid, contextKey, false, Int.MAX_VALUE).mapTo(HashSet()) { CustomizeKeys.series(it) }
                }
                else -> customCategoryDao.stableItemKeys(pid, contextKey).toSet()
            }
        }
    }

    /** Back to the playlist's order and contents (core's restoreCategoryToDefault). */
    fun restoreCategory(key: LiveKey) {
        val contextKey = categoryContextKey(key) ?: return
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            restoreCategoryToDefault(customize, contentOrderDao, pid, mediaType.value, contextKey, categoryRestorePlan(key))
        }
    }

    /** The content_order / movedFromOrigin context behind a category, or null for the fixed entries. */
    private fun categoryContextKey(key: LiveKey): String? = when (key) {
        is LiveKey.Folder -> folderContextKeys.value[key.id]
        is LiveKey.Custom -> key.id
        else -> null
    }

    fun select(tab: LibraryTab) {
        _tab.value = tab
    }

    /**
     * Set while this view model is serving Favourites or Watch history rather than the Library tab.
     * Those screens are one folder each: the selection is theirs to fix, and it must not become the
     * remembered category, or opening Favourites would move the Library tab too.
     */
    private var lockedKey: LiveKey? = null

    /** What the Library tab was showing before the pin, so [unlock] can put it back. Both halves,
     *  because Movies and Series remember their category separately. */
    private var previousKeys: Pair<LiveKey, LiveKey>? = null

    fun lock(key: LiveKey) {
        if (lockedKey == null) previousKeys = _selectedMovies.value to _selectedSeries.value
        lockedKey = key
        _selectedMovies.value = key
        _selectedSeries.value = key
    }

    /**
     * Release the pin and put the Library tab back where it was.
     *
     * **This is not optional bookkeeping.** On the television the equivalent view models are single
     * instances shared with the browse sections, and a pin that outlived the screen that took it
     * froze their category rails — focusable, but every click a no-op. The screen that locks
     * therefore unlocks on dispose, in both apps, so the pairing cannot drift.
     */
    fun unlock() {
        val (movies, series) = previousKeys ?: return
        lockedKey = null
        previousKeys = null
        _selectedMovies.value = movies
        _selectedSeries.value = series
    }

    fun select(key: LiveKey) {
        if (lockedKey != null) return
        val current = if (_tab.value == LibraryTab.MOVIES) _selectedMovies else _selectedSeries
        if (current.value == key) return
        current.value = key
        _query.value = ""
        val movies = _tab.value == LibraryTab.MOVIES
        viewModelScope.launch {
            if (movies) settings.setLastMoviesCategory(key.serialize())
            else settings.setLastSeriesCategory(key.serialize())
            // Core fills a lazily-added Stalker catalogue in the provider's own order; tell it the
            // user is here so this category is served next. A no-op for a playlist that
            // was not lazily added, and for a category that is already complete.
            if (key is LiveKey.Folder) {
                runCatching { catalogPriority.requestFirst(key.id) }
            } else {
                catalogPriority.clear()
            }
        }
    }

    fun setSort(mode: SettingsRepository.SortMode) {
        viewModelScope.launch {
            if (_tab.value == LibraryTab.MOVIES) settings.setSortMovies(mode) else settings.setSortSeries(mode)
        }
    }

    fun setViewMode(mode: SettingsRepository.VodViewMode) {
        viewModelScope.launch { settings.setVodViewMode(mode) }
    }

    /** The pinch gesture, and the only writer of the column count. 0 hands it back to the screen. */
    fun setGridColumns(columns: Int) {
        viewModelScope.launch { settings.setVodGridColumns(columns) }
    }

    init {
        // The remembered category of each tab, at start and again after every profile switch: this view
        // model outlives the switch, and each profile has its own. After a switch a tab with nothing it
        // can show goes back to All rather than keep the previous profile's folder.
        viewModelScope.launch {
            var atLaunch = true
            ctx.filter { it.profileId >= 0L }.distinctUntilChanged { a, b -> a.profileId == b.profileId }.collect { c ->
                val launch = atLaunch.also { atLaunch = false }
                if (lockedKey != null) return@collect
                suspend fun remembered(on: Boolean, stored: String, type: MediaType): LiveKey? =
                    if (!on) null else parseLiveKey(stored)?.takeIf { !folderGone(it, c.sourceIdsFor(type)) }
                val movies = remembered(settings.rememberCategoryMovies.first(), settings.lastMoviesCategory.first(), MediaType.MOVIE)
                val series = remembered(settings.rememberCategorySeries.first(), settings.lastSeriesCategory.first(), MediaType.SERIES)
                if (movies != null || !launch) _selectedMovies.value = movies ?: LiveKey.All
                if (series != null || !launch) _selectedSeries.value = series ?: LiveKey.All
            }
        }
        // Another playlist can leave a tab on a folder the playlists now shown do not have: its titles stayed
        // listed under an empty label. Such a folder falls back to All, as on Live TV.
        viewModelScope.launch {
            ctx.filter { it.profileId >= 0L }.distinctUntilChanged { a, b -> a.sourceIds == b.sourceIds }.drop(1).collect { c ->
                if (folderGone(_selectedMovies.value, c.sourceIdsFor(MediaType.MOVIE))) _selectedMovies.value = LiveKey.All
                if (folderGone(_selectedSeries.value, c.sourceIdsFor(MediaType.SERIES))) _selectedSeries.value = LiveKey.All
            }
        }
    }

    /** Whether [key] is a provider folder that none of [sourceIds] has (another playlist's, or deleted). */
    private suspend fun folderGone(key: LiveKey, sourceIds: List<Long>): Boolean =
        key is LiveKey.Folder && categoryDao.getById(key.id)?.sourceId !in sourceIds

    // --- The long-press actions ------------------------------------------------------------------

    /** [favorite] is what the menu showed, not [favoriteIds]' `.value`: from the movie page nothing collects that. */
    fun setFavorite(itemId: Long, favorite: Boolean) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            val type = mediaType.value
            if (!favorite) userDataWriter.removeFavorite(pid, type, itemId)
            else favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = type, itemId = itemId))
        }
    }

    /**
     * Mark a film watched without playing it. The 1 ms / 1 ms sentinel is the TV app's: it satisfies
     * the "finished" rule while leaving Play starting from the beginning.
     */
    fun setWatched(movieId: Long, watched: Boolean) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            if (watched) {
                progressDao.save(
                    PlaybackProgressEntity(
                        profileId = pid,
                        mediaType = MediaType.MOVIE,
                        itemId = movieId,
                        positionMs = 1L,
                        durationMs = 1L,
                    ),
                )
            } else {
                userDataWriter.clearProgress(pid, MediaType.MOVIE, movieId)
            }
        }
    }

    /** Hide the item from every list (undone in Settings → Customize). */
    fun hide(itemId: Long) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            when (mediaType.value) {
                MediaType.MOVIE -> movieDao.getById(itemId)?.let {
                    customize.setItemHidden(pid, MediaType.MOVIE, CustomizeKeys.movie(it), it.name, true)
                }
                else -> seriesDao.getSeriesById(itemId)?.let {
                    customize.setItemHidden(pid, MediaType.SERIES, CustomizeKeys.series(it), it.name, true)
                }
            }
        }
    }

    /** Whether Remove from history has anything to remove (the TV's: dimmed otherwise). */
    suspend fun isInHistory(itemId: Long): Boolean {
        val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return false
        return historyDao.exists(pid, mediaType.value, itemId)
    }

    /** The episodes stored for a show — Download all episodes' count. 0 for a show never opened. */
    suspend fun episodeCount(seriesId: Long): Int = seriesDao.episodeCount(seriesId)

    fun removeFromHistory(itemId: Long) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            userDataWriter.removeHistory(pid, mediaType.value, itemId)
        }
    }

    fun playExternal(itemId: Long) {
        viewModelScope.launch {
            movieDao.getById(itemId)?.let { vodTuner.playExternal(it) }
        }
    }

    /** Download a film, or every episode of a show — the same layout on disk the TV app writes. */
    fun download(itemId: Long) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            if (mediaType.value == MediaType.MOVIE) {
                val movie = movieDao.getById(itemId) ?: return@launch
                if (!AdultCategoryClassifier.allows(pid, movie.categoryId, profileDao, categoryDao)) return@launch
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
                val show = seriesDao.getSeriesById(itemId) ?: return@launch
                if (!AdultCategoryClassifier.allows(pid, show.categoryId, profileDao, categoryDao)) return@launch
                seriesDao.episodesBySeriesOnce(show.id).forEach { episode ->
                    downloadManager.enqueue(
                        profileId = pid,
                        mediaType = MediaType.EPISODE,
                        itemId = episode.id,
                        title = episode.name.takeIf { it.isNotBlank() } ?: show.name,
                        posterUrl = show.posterUrl,
                        streamUrl = episode.streamUrl,
                        relativeDir = episodeDir(show.name, episode.seasonNumber),
                        fileName = episodeFileName(episode.name, episode.episodeNumber, episode.containerExt, episode.streamUrl),
                    )
                }
            }
        }
    }

    /**
     * The download rows behind one library row's Download action: the film's own row, or every
     * episode row of the show. A show's episodes are looked up by series id, which is what
     * `observeForSeries` is for.
     */
    fun downloadsOf(itemId: Long): Flow<List<DownloadEntity>> = if (mediaType.value == MediaType.MOVIE) {
        ctx.flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyList()) else downloadManager.observe(c.profileId)
        }.map { list -> list.filter { it.mediaType == MediaType.MOVIE && it.itemId == itemId } }
    } else {
        downloadManager.observeForSeries(itemId)
    }

    /** Start the failed rows over. */
    fun retryDownloads(rows: List<DownloadEntity>) {
        rows.filter { it.status == DownloadStatus.FAILED }.forEach { downloadManager.retry(it) }
    }

    /** Cancel what is in flight, or remove what has already been saved. */
    fun deleteDownloads(rows: List<DownloadEntity>) {
        rows.forEach { downloadManager.delete(it) }
    }

    // --- Reordering, and moving into the user's own categories --------------------------------------

    /** The stable key of the list being looked at, or null when it has no stored order to move in. */
    fun contextKeyOf(key: LiveKey): String? = when (key) {
        is LiveKey.Folder -> folderContextKeys.value[key.id]
        is LiveKey.Custom -> key.id
        LiveKey.Favorites -> ContentOrderEntity.FAV_CONTEXT
        // History and All are computed views: there is no order of their own to change.
        LiveKey.History, LiveKey.All, LiveKey.Catchup -> null
    }

    /** The items of [key] in their current order — what the Move sheet shuffles. */
    suspend fun moveList(key: LiveKey): List<ReorderItem> {
        val c = ctx.value
        if (c.profileId < 0 || custom.value.seals(key)) return emptyList()
        val contextKey = contextKeyOf(key) ?: return emptyList()
        val type = mediaType.value
        val ids = c.sourceIdsFor(type).ifEmpty { listOf(-1L) }
        val items = if (type == MediaType.MOVIE) {
            when (key) {
                is LiveKey.Folder -> movieDao.snapshotByCategoryManual(key.id, c.profileId, contextKey, sortMode.value == SettingsRepository.SortMode.ALPHA, MOVE_LIST_LIMIT)
                is LiveKey.Custom -> customCategoryDao.snapshotMovies(c.profileId, key.id, ids, sortMode.value == SettingsRepository.SortMode.ALPHA, MOVE_LIST_LIMIT)
                LiveKey.Favorites -> movieDao.snapshotFavoritesManual(c.profileId, contextKey, ids, MOVE_LIST_LIMIT)
                else -> return emptyList()
            }.map { ReorderItem(it.id, it.name) }
        } else {
            when (key) {
                is LiveKey.Folder -> seriesDao.snapshotByCategoryManual(key.id, c.profileId, contextKey, sortMode.value == SettingsRepository.SortMode.ALPHA, MOVE_LIST_LIMIT)
                is LiveKey.Custom -> customCategoryDao.snapshotSeries(c.profileId, key.id, ids, sortMode.value == SettingsRepository.SortMode.ALPHA, MOVE_LIST_LIMIT)
                LiveKey.Favorites -> seriesDao.snapshotFavoritesManual(c.profileId, contextKey, ids, MOVE_LIST_LIMIT)
                else -> return emptyList()
            }.map { ReorderItem(it.id, it.name) }
        }
        return items
    }

    /** Saves a Move: [itemIds] in their new order, [moved] the ones the user moved (only those keep a place). */
    fun commitMove(contextKey: String, itemIds: List<Long>, moved: Set<Long>) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            contentOrderDao.saveMove(pid, mediaType.value, contextKey, itemIds, moved)
        }
    }

    /** The user's own combined categories — the Move-to-category sheet's targets. */
    val customCategories: StateFlow<List<Pair<String, String>>> = custom
        // A locked one the PIN has not opened is left out: moving a title into it would file it out of sight.
        .map { c -> c.customCategories.filter { it.id !in c.sealedCategories }.map { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createCustomCategory(name: String) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            customize.createCustomCategory(pid, mediaType.value, name)
        }
    }

    /**
     * Move (or copy, [keepInOrigin]) an item into a custom category. Without [keepInOrigin] it leaves
     * where it came from: a favourite row is deleted, a custom-category membership is deleted, and a
     * provider folder is marked — the folder then stops showing it while All still does.
     */
    fun moveToCategory(itemId: Long, originKey: String, targetId: String, keepInOrigin: Boolean) {
        if (targetId == originKey) return
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            val type = mediaType.value
            val itemKey = if (type == MediaType.MOVIE) {
                movieDao.getById(itemId)?.let { CustomizeKeys.movie(it) }
            } else {
                seriesDao.getSeriesById(itemId)?.let { CustomizeKeys.series(it) }
            } ?: return@launch
            customCategoryDao.appendItem(pid, type, targetId, itemId)
            if (!keepInOrigin) {
                when {
                    originKey == ContentOrderEntity.FAV_CONTEXT -> userDataWriter.removeFavorite(pid, type, itemId)
                    CustomizeKeys.isCustom(originKey) -> userDataWriter.removeCustomCategoryMember(pid, type, originKey, itemId)
                    else -> customize.setItemMovedFromOrigin(pid, type, itemKey, originKey, moved = true)
                }
            }
        }
    }

    // --- TMDB, and the subtitles that were downloaded for an item -----------------------------------

    /** Whether TMDB is enriching at all, and which side wins a field. Gates half of the menu. */
    val metadataMode: StateFlow<MetadataMode> = settings.metadataMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetadataMode.PROVIDER_PLUS_TMDB)

    /**
     * The TMDB record for one item, resolved on demand.
     *
     * The TV app resolves on focus and debounces it, because a D-pad sweeps over a hundred cards. A
     * long-press is deliberate and hits exactly one item, so there is nothing here to debounce.
     */
    suspend fun metaFor(tab: LibraryTab, itemId: Long): MetadataCacheEntity? = runCatching {
        if (tab == LibraryTab.MOVIES) movieDao.getById(itemId)?.let { metadata.resolveMovie(it) }
        else seriesDao.getSeriesById(itemId)?.let { metadata.resolveSeries(it) }
    }.getOrNull()

    /** Forget this item's match and cached details, so the next [metaFor] asks TMDB again. */
    suspend fun clearMeta(tab: LibraryTab, itemId: Long) {
        runCatching {
            if (tab == LibraryTab.MOVIES) movieDao.getById(itemId)?.let { metadata.clearMovie(it) }
            else seriesDao.getSeriesById(itemId)?.let { metadata.clearSeries(it) }
        }
    }

    /** What the Set-TMDB-name dialog opens with: the saved override, else the cleaned provider title. */
    data class TmdbNamePrefill(val title: String, val year: Int?, val hasOverride: Boolean)

    suspend fun tmdbNamePrefill(tab: LibraryTab, itemId: Long): TmdbNamePrefill? {
        val name: String
        val year: Int?
        if (tab == LibraryTab.MOVIES) {
            val movie = movieDao.getById(itemId) ?: return null
            metadata.movieOverride(movie)?.let { return TmdbNamePrefill(it.title, it.year, hasOverride = true) }
            name = movie.name
            year = movie.year
        } else {
            val show = seriesDao.getSeriesById(itemId) ?: return null
            metadata.seriesOverride(show)?.let { return TmdbNamePrefill(it.title, it.year, hasOverride = true) }
            name = show.name
            year = show.year
        }
        val norm = TitleNormalizer.normalize(name)
        return TmdbNamePrefill(norm.query, year ?: norm.year, hasOverride = false)
    }

    /** Save the hand-typed title, or clear it with a blank one, and re-resolve under it. */
    suspend fun setTmdbName(tab: LibraryTab, itemId: Long, title: String, year: Int?) {
        runCatching {
            if (tab == LibraryTab.MOVIES) {
                val movie = movieDao.getById(itemId) ?: return
                if (title.isBlank()) metadata.clearMovieOverride(movie)
                else metadata.setMovieOverride(movie, title, year)
            } else {
                val show = seriesDao.getSeriesById(itemId) ?: return
                if (title.isBlank()) metadata.clearSeriesOverride(show)
                else metadata.setSeriesOverride(show, title, year)
            }
        }
    }

    /** The row itself, for the details sheet — the poster grid carries only what it draws. */
    suspend fun movieById(itemId: Long): MovieEntity? = movieDao.getById(itemId)

    suspend fun seriesById(itemId: Long): SeriesEntity? = seriesDao.getSeriesById(itemId)

    /** Subtitles downloaded for this film, for the Delete-subtitles sheet. Films only, as on the TV. */
    suspend fun downloadedSubtitles(itemId: Long): List<LinkedSubtitle> =
        movieDao.getById(itemId)?.let { subtitleController.downloadsForMovie(it) }.orEmpty()

    fun deleteSubtitle(cacheId: Long) {
        viewModelScope.launch { subtitleController.deleteCached(cacheId) }
    }

    private companion object {
        const val WATCHED_FRACTION = 0.95f

        /** Typing re-queries a catalogue of up to 170k titles; one query per pause, not per letter. */
        const val SEARCH_DEBOUNCE_MS = 250L

        /** The Move sheet holds its list in memory, so a huge folder is cut off — the live list's limit. */
        const val MOVE_LIST_LIMIT = 5_000

        // Placeholders off — see the Guide's pager: a not-yet-loaded item draws a tile with no
        // height, and a list of those composes the whole catalogue.
        fun pagingConfig() = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            initialLoadSize = 90,
            maxSize = 300,
            enablePlaceholders = false,
        )
    }
}

/** Where an episode's file goes — the TV app's layout, so one library serves both apps. */
internal fun episodeDir(showName: String, seasonNumber: Int): String =
    MediaFolders.seasonDir(showName, seasonNumber)

internal fun episodeFileName(name: String, episodeNumber: Int, containerExt: String?, streamUrl: String): String {
    val stem = StorageAccess.sanitize(name.ifBlank { "episode-$episodeNumber" })
    return "$stem.${containerExt ?: StorageAccess.extOf(streamUrl)}"
}

/** Where films go on disk. Core owns the name so the two apps cannot drift apart. */
internal val MOVIES_DIR: String = MediaFolders.MOVIES
