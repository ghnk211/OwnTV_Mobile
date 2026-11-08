package tv.own.owntv.mobile.ui.screens.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.categorylock.lockAwareCatchupCount
import tv.own.owntv.core.categorylock.visibleOnceRead
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.restoreCategoryToDefault
import tv.own.owntv.core.customize.planCategoryRestore
import tv.own.owntv.core.customize.CategoryRestorePlan
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.customize.CategoryRailEditor
import tv.own.owntv.core.customize.MoveKind
import tv.own.owntv.core.customize.railCategories
import tv.own.owntv.core.customize.seals
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.liveLandingKey
import tv.own.owntv.core.live.liveCountFlow
import tv.own.owntv.core.live.livePagingSource
import tv.own.owntv.core.live.liveRestoreKey
import tv.own.owntv.core.live.serialize
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.player.ExternalPlayerLauncher
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.core.util.throttleLatest
import tv.own.owntv.mobile.sync.LocalPlaylistSync
import tv.own.owntv.mobile.ui.components.ReorderItem
import tv.own.owntv.mobile.ui.shell.StartupLiveSelection

/** One chip in the strip above the channel list. [builtIn] labels are translated; the rest are the
 *  user's or the provider's own names, so they are carried as text. */
data class LiveCategory(
    val key: LiveKey,
    val title: String? = null,
    val builtIn: BuiltIn? = null,
    /** The playlist a provider folder comes from, for its mark in the categories sheet. */
    val sourceId: Long? = null,
    /** Locked with the Customize PIN; [sealed] while the PIN has not opened it this session. */
    val locked: Boolean = false,
    val sealed: Boolean = false,
) {
    enum class BuiltIn { ALL, FAVORITES, HISTORY, CATCHUP }
}

/**
 * The Live tab's category list: the built-in rails the profile shows, then its own categories in its
 * own arranged order. Shared with the player's overlay ([tv.own.owntv.mobile.ui.screens.live.LiveTuner.overlayCategories]),
 * so a rename or a hide reaches both screens the moment it is made.
 */
internal fun buildLiveCategories(
    cats: List<tv.own.owntv.core.database.entity.CategoryEntity>,
    cust: SectionCustomizations,
    alphabetical: Boolean,
    builtIns: tv.own.owntv.core.live.LiveBuiltIns,
    catchupCount: Int,
    profile: tv.own.owntv.core.database.entity.ProfileEntity?,
): List<LiveCategory> {
    val folders = cats.railCategories(
        cust,
        kids = profile?.isKids == true,
        alphabetical = alphabetical,
    )
    return buildList {
        if (builtIns.showAll) add(LiveCategory(LiveKey.All, builtIn = LiveCategory.BuiltIn.ALL))
        add(LiveCategory(LiveKey.Favorites, builtIn = LiveCategory.BuiltIn.FAVORITES))
        add(LiveCategory(LiveKey.History, builtIn = LiveCategory.BuiltIn.HISTORY))
        if (catchupCount > 0 && builtIns.showCatchup) add(LiveCategory(LiveKey.Catchup, builtIn = LiveCategory.BuiltIn.CATCHUP))
        val sources = cats.associate { it.id to it.sourceId }
        folders.forEach { e ->
            add(
                LiveCategory(
                    key = e.categoryId?.let { LiveKey.Folder(it) } ?: LiveKey.Custom(e.customId!!),
                    title = e.displayName,
                    sourceId = e.categoryId?.let(sources::get),
                    locked = e.locked,
                    sealed = e.sealed,
                ),
            )
        }
    }
}

/**
 * Live TV for the phone.
 *
 * Every query, and the rule for which channels survive the profile's customizations, comes from
 * core — the same functions the TV app's Live screen calls, so hiding a channel or reordering a
 * folder on one device means the same thing on the other. What differs is only what the screen
 * needs: there is no preview pane to keep fed and no focused row to follow, so the guide is read in
 * one batch for the rows actually on screen instead of per channel as focus moves.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class LiveViewModel(
    private val context: Context,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val customCategoryDao: CustomCategoryDao,
    private val contentOrderDao: ContentOrderDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val epgRepository: EpgRepository,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
    private val streamUrlResolver: StreamUrlResolver,
    private val syncScheduler: CatalogSyncScheduler,
    private val startupSelection: StartupLiveSelection,
    epgDao: EpgDao,
    /** The app-wide reader the Guide uses too — one now/next cache, not two. */
    private val epgReader: LiveEpgReader,
) : ViewModel() {


    /** The same candidate set the Guide's picker uses — filtered by no source. */
    private val guideCandidates = tv.own.owntv.core.epg.GuideCandidates(epgDao)

    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveProfileSources(-1L, emptyList()))

    /**
     * This profile's customizations for Live TV, with the categories a lock keeps shut counted as hidden;
     * null until read. The channel list waits for them: one built from the empty default would show a
     * locked category's channels for a moment.
     */
    private val customOnceRead: StateFlow<SectionCustomizations?> = ctx
        .flatMapLatest { c -> customize.visibleOnceRead(c.profileId, MediaType.LIVE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val custom: StateFlow<SectionCustomizations> = customOnceRead.filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionCustomizations())

    /** The locked provider folders not open this session: their channels leave every count. */
    private val sealedCategoryIds: Flow<Set<Long>> = custom.map { it.sealedCategoryIds }.distinctUntilChanged()

    private val epgOffset: StateFlow<Int> = settings.epgOffsetMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val sortMode: StateFlow<SettingsRepository.SortMode> = settings.sortLive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.SortMode.PLAYLIST)

    private val liveCategories = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyList())
            else categoryDao.observe(c.liveSourceIds, MediaType.LIVE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Category DB ids this profile does not see — its hidden categories, plus every adult category
     * when the profile is a kids one. Resolved to ids so All / History / Catch-up can drop those
     * channels too, exactly as the TV app does: hiding a group has to hide its channels everywhere,
     * not just remove its chip.
     */
    private val hiddenCategoryIds: StateFlow<Set<Long>> =
        combine(liveCategories, custom, ctx.flatMapLatest { profileDao.observeById(it.profileId) }) { cats, cust, profile ->
            AdultCategoryClassifier.hiddenCategoryIds(cats, cust.hiddenCategories, profile?.isKids == true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val folderContextKeys: StateFlow<Map<Long, String>> = liveCategories
        .map { cats -> cats.associateBy({ it.id }, { CustomizeKeys.category(it) }) }
        // Eagerly: only ever read as `.value` (the menu's Move, the pager's manual order), never collected —
        // shared while-subscribed it stayed empty, and Move / Move to category never appeared in a folder.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Contexts that actually carry a manual order — core's paging query takes the cheap path for
     *  the rest, and the pager is rebuilt when a folder gains or loses one. */
    private val orderedContexts: StateFlow<Set<String>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyList())
            else contentOrderDao.observeContextKeys(c.profileId, MediaType.LIVE)
        }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The chip strip: the built-in lists, then the profile's categories in its own arranged order.
     *  Catch-up only appears when the provider actually advertises an archive. All channels and
     *  Catch-up are left out when the user hid them (Settings › Layout › Browsing & lists). */
    /**
     * The strip with the profile and playlists it was built for, from that profile's own category
     * edits: the shared [custom] starts empty and keeps the previous profile's edits for a moment after
     * a switch, and a strip built from either lacks custom categories and shows hidden ones. The
     * remembered-category restore waits for the first strip complete for the profile in use.
     */
    private val categoriesFor: StateFlow<Pair<ActiveProfileSources?, List<LiveCategory>>> = ctx.flatMapLatest { c ->
        if (c.profileId < 0) return@flatMapLatest flowOf(null to emptyList())
        combine(
            categoryDao.observe(c.liveSourceIds, MediaType.LIVE),
            customize.visibleOnceRead(c.profileId, MediaType.LIVE).filterNotNull(),
            combine(sortMode, settings.liveBuiltIns) { sort, builtIns -> sort to builtIns },
            channelDao.lockAwareCatchupCount(c.liveSourceIds.ifEmpty { listOf(-1L) }, sealedCategoryIds).distinctUntilChanged(),
            profileDao.observeById(c.profileId),
        ) { cats, cust, (sort, builtIns), catchupCount, profile -> c to buildCategories(cats, cust, sort, builtIns, catchupCount, profile) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    val categories: StateFlow<List<LiveCategory>> = categoriesFor.map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildCategories(
        cats: List<tv.own.owntv.core.database.entity.CategoryEntity>,
        cust: SectionCustomizations,
        sort: SettingsRepository.SortMode,
        builtIns: tv.own.owntv.core.live.LiveBuiltIns,
        catchupCount: Int,
        profile: tv.own.owntv.core.database.entity.ProfileEntity?,
    ): List<LiveCategory> = buildLiveCategories(
        cats = cats,
        cust = cust,
        alphabetical = sort == SettingsRepository.SortMode.ALPHA,
        builtIns = builtIns,
        catchupCount = catchupCount,
        profile = profile,
    )

    /**
     * The channel count beside every category in the sheet (the TV's railCounts): each fixed entry and
     * custom category by the crumb's own query, every provider folder from one grouped query. Only
     * collected while the sheet is open.
     */
    val categoryCounts: StateFlow<Map<LiveKey, Int>> = combine(ctx, hiddenCategoryIds, categories, sealedCategoryIds) { c, hidden, items, sealed ->
            if (c.profileId < 0) return@combine flowOf(emptyMap())
            val single = items.filter { it.key !is LiveKey.Folder }.map { item ->
                liveCountFlow(item.key, c.profileId, c.liveSourceIds, hidden, channelDao, customCategoryDao, sealed).map { mapOf(item.key to it) }
            }
            // A sealed folder shows no count: how many channels it holds is the lock's business.
            val folders = channelDao.observeCountsByCategory(c.liveSourceIds.ifEmpty { listOf(-1L) })
                .map { rows -> rows.filter { it.categoryId !in sealed }.associate { LiveKey.Folder(it.categoryId) as LiveKey to it.itemCount } }
            combine(single + folders) { parts -> parts.fold(emptyMap<LiveKey, Int>()) { acc, m -> acc + m } }.throttleLatest()
        }
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Settings › Layout › Navigation › Show counts. */
    val sheetCounts: StateFlow<Boolean> = settings.sheetCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** What restoring [key]'s category to the playlist default would undo (empty when nothing). */
    suspend fun categoryRestorePlan(key: LiveKey): CategoryRestorePlan {
        val contextKey = categoryContextKey(key) ?: return CategoryRestorePlan()
        val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return CategoryRestorePlan()
        return planCategoryRestore(customize, contentOrderDao, pid, MediaType.LIVE, contextKey, customCategoryDao) {
            when (key) {
                is LiveKey.Folder -> channelDao.snapshotByCategoryManual(key.id, pid, contextKey, false, Int.MAX_VALUE)
                    .mapTo(HashSet()) { CustomizeKeys.channel(it) }
                else -> customCategoryDao.stableItemKeys(pid, contextKey).toSet()
            }
        }
    }

    /** Back to the playlist's order and contents (core's restoreCategoryToDefault). */
    fun restoreCategory(key: LiveKey) {
        val contextKey = categoryContextKey(key) ?: return
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            restoreCategoryToDefault(customize, contentOrderDao, pid, MediaType.LIVE, contextKey, categoryRestorePlan(key))
        }
    }

    /** The content_order / movedFromOrigin context behind a category, or null for the fixed entries. */
    private fun categoryContextKey(key: LiveKey): String? = when (key) {
        is LiveKey.Folder -> folderContextKeys.value[key.id]
        is LiveKey.Custom -> key.id
        else -> null
    }

    /** Rename the long-pressed category; a blank name restores the provider's own. */
    fun renameCategory(key: LiveKey, name: String?) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            val contextKey = categoryContextKey(key) ?: return@launch
            customize.renameCategory(pid, MediaType.LIVE, contextKey, name?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    /** Hide and reorder a category from the strip, through the same editor Settings → Customize uses. */
    private val categoryEditor = CategoryRailEditor(customize, categoryDao, profileDao)

    /** Hide the long-pressed category. It comes back from Settings → Customize. */
    fun hideCategory(key: LiveKey) {
        viewModelScope.launch {
            val c = ctx.value
            if (c.profileId < 0) return@launch
            categoryEditor.hide(c.profileId, MediaType.LIVE, key)
            // The strip is about to lose this chip; leaving it selected would show a folder that is
            // no longer there.
            // Not a pick: the landing list is shown, the remembered category is left alone.
            if (selected.value == key) _selected.value = landingKey(categories.value.filter { it.key != key })
        }
    }

    /** Move the long-pressed category one step through the strip, and keep it there. */
    fun moveCategory(key: LiveKey, kind: MoveKind) {
        viewModelScope.launch {
            val c = ctx.value
            if (c.profileId < 0) return@launch
            categoryEditor.move(
                profileId = c.profileId,
                sourceIds = c.liveSourceIds,
                type = MediaType.LIVE,
                alphabetical = sortMode.value == SettingsRepository.SortMode.ALPHA,
                key = key,
                kind = kind,
            )
        }
    }

    private val _selected = MutableStateFlow<LiveKey>(LiveKey.All)
    val selected: StateFlow<LiveKey> = _selected

    /** Playlist names, but only when more than one is active — a single-playlist user does not need
     *  every row to repeat where it came from. */
    val providerNames: StateFlow<Map<Long, String>> = ctx
        .map { c -> if (c.sources.size > 1) c.sources.associate { it.id to it.name } else emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The profile's Live playlists — the ones that carry channels. Empty is not an empty Live TV, it
     * is a Live TV with nothing to draw it from: the tab asks for a source instead of a list (6.1).
     * Null until the profile's sources have been read, so a normal install never flashes the card.
     */
    val liveSources: StateFlow<List<SourceEntity>?> = ctx
        .map { c -> if (c.profileId < 0) null else c.sources.filter { it.syncLive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Channel numbers are drawn only when the setting the TV app reads is on. */
    val showChannelNumbers: StateFlow<Boolean> = settings.directTune
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val favoriteIds: StateFlow<Set<Long>> = ctx
        .flatMapLatest { favoriteDao.observeFavoriteIds(it.profileId, MediaType.LIVE) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * The Stage playlist mark per source, coloured by the playlist's position in the
     * profile's list — the TV's rule. Empty with one playlist, like [providerNames].
     */
    val playlistMarks: StateFlow<Map<Long, PlaylistMark>> = ctx
        .map { c ->
            if (c.liveSourceIds.size < 2) emptyMap()
            else c.sources.withIndex().associate { (i, s) -> s.id to PlaylistMark.of(s.name, i) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The order tool: Provider (the playlist's own) or A–Z, one tap between them as on the TV. */
    val sort: StateFlow<SettingsRepository.SortMode> = sortMode

    fun toggleSort() {
        viewModelScope.launch {
            settings.setSortLive(
                if (sortMode.value == SettingsRepository.SortMode.ALPHA) SettingsRepository.SortMode.PLAYLIST
                else SettingsRepository.SortMode.ALPHA,
            )
        }
    }

    private val _query = MutableStateFlow("")

    /** The search field above the list: filters the open category. */
    val query: StateFlow<String> = _query

    fun setQuery(text: String) {
        _query.value = text
    }

    /** The open category's channel count, for the crumb ("· 9 channels"). */
    val count: StateFlow<Int> = combine(_selected, ctx, hiddenCategoryIds, sealedCategoryIds) { key, c, hidden, sealed ->
            if (c.profileId < 0) flowOf(0)
            else liveCountFlow(key, c.profileId, c.liveSourceIds, hidden, channelDao, customCategoryDao, sealed)
        }
        .flatMapLatest { it.throttleLatest() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _nowPlaying = MutableStateFlow<Map<Long, XtEpgEntry>>(emptyMap())

    /** What is on now for the rows on screen, with its start and stop, keyed by channel id. */
    val nowPlaying: StateFlow<Map<Long, XtEpgEntry>> = _nowPlaying

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private data class Args(val key: LiveKey, val ctx: ActiveProfileSources, val cust: SectionCustomizations, val hidden: Set<Long>)

    val channels: Flow<PagingData<ChannelEntity>> =
        combine(_selected, ctx, customOnceRead.filterNotNull(), hiddenCategoryIds, ::Args)
            // A folder gaining or losing a manual order changes which query is correct, and the
            // cheap one does not observe content_order — so the pager has to be rebuilt. So does the
            // order tool, and the search field (settled for a moment, so each letter is not a query).
            .combine(orderedContexts) { args, _ -> args }
            .combine(sortMode) { args, _ -> args }
            .combine(_query.debounce(SEARCH_DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged()) { args, q -> args to q }
            .flatMapLatest { (args, q) ->
                val (key, c, cust, hidden) = args
                if (c.profileId < 0) {
                    flowOf(PagingData.empty())
                } else {
                    // Placeholders off — see the Guide's pager: a not-yet-loaded channel draws a row
                    // with no height, and a list of those composes the whole lineup.
                    // maxSize drops distant pages, so a 50 000-channel lineup cannot pin the whole
                    // catalogue in memory while flinging; initialLoadSize fills the viewport at once.
                    Pager(
                        PagingConfig(
                            pageSize = PAGE_SIZE,
                            prefetchDistance = PAGE_SIZE / 2,
                            initialLoadSize = PAGE_SIZE * 2,
                            maxSize = 400,
                            enablePlaceholders = false,
                        ),
                    ) {
                        livePagingSource(
                            key = key,
                            profileId = c.profileId,
                            sourceIds = c.liveSourceIds,
                            query = q,
                            sort = sortMode.value,
                            channelDao = channelDao,
                            customCategoryDao = customCategoryDao,
                            contextKey = { folderContextKeys.value[it] },
                            hasManualOrder = { it in orderedContexts.value },
                        )
                    }.flow.map { paging -> paging.applyCustomizations(key, cust, hidden) }
                }
            }
            .cachedIn(viewModelScope)

    /**
     * Hidden channels and hidden categories drop out, renames are applied, and a channel moved into
     * a custom category leaves the folder it came from (but stays in All).
     *
     * Applied inside the pager chain, on each fresh `PagingData`: Paging forbids transforming one the
     * UI has already collected, so a customization change re-creates the pager instead.
     */
    private fun PagingData<ChannelEntity>.applyCustomizations(
        key: LiveKey,
        cust: SectionCustomizations,
        hidden: Set<Long>,
    ): PagingData<ChannelEntity> {
        if (cust.hiddenItems.isEmpty() && cust.itemNames.isEmpty() && hidden.isEmpty() && cust.movedFromOrigin.isEmpty() && cust.sealedCategories.isEmpty()) return this
        return filter { ch ->
            tv.own.owntv.core.live.isChannelVisible(ch, cust, hidden, key) &&
                (cust.movedFromOrigin[CustomizeKeys.channel(ch)]?.let { origin ->
                    key !is LiveKey.Folder || origin != folderContextKeys.value[key.id]
                } ?: true)
        }.map { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
    }

    /** A remembered-category restore still waiting for its strip; a pick or a startup choice ends it. */
    private var restoreJob: Job? = null

    init {
        // Bring the remembered category back at start and after each profile or playlist switch (this view
        // model outlives both; each profile has its own). Core's rule decides: a hidden, deleted or locked
        // category stays away. The wait ends with the first strip complete for the profile in use.
        viewModelScope.launch {
            var atLaunch = true
            ctx.filter { it.profileId >= 0L }
                .distinctUntilChanged { a, b -> a.profileId == b.profileId && a.liveSourceIds == b.liveSourceIds }
                .collect { c ->
                    restoreJob?.cancel()
                    // "Start on" already chose this launch's category.
                    if (atLaunch.also { atLaunch = false } && startupApplied) return@collect
                    restoreJob = launch {
                        val stored = if (settings.rememberCategoryLive.first()) settings.lastLiveCategory.first() else return@launch
                        val strip = categoriesFor.first { (built, _) ->
                            built?.profileId == c.profileId && built.liveSourceIds == c.liveSourceIds
                        }.second
                        liveRestoreKey(stored, strip, { it.key }, { it.sealed })?.let { _selected.value = it }
                    }
                }
        }
        // "TV Guide follows Live TV": a category picked in the TV Guide (its own view model) is saved as this
        // one's remembered category; it is shown here at once, not at the next restore. Only picks write
        // that value, so this tab's own picks arrive here as no-ops.
        viewModelScope.launch {
            combine(settings.guideFollowsLiveCategory, settings.lastLiveCategory) { follows, stored -> stored.takeIf { follows } }
                .drop(1).distinctUntilChanged().filterNotNull().collect { stored ->
                    liveRestoreKey(stored, categories.value, { it.key }, { it.sealed })
                        ?.takeIf { it != _selected.value }?.let { _selected.value = it }
                }
        }
        // Another playlist or profile can leave the selection on a folder the new playlists do not have:
        // its channels stayed listed under a wrong name. Such a folder falls back to All channels — or,
        // with All hidden, to the new list's first category, once that list has arrived.
        viewModelScope.launch {
            ctx.filter { it.profileId >= 0L }.map { it.liveSourceIds }.distinctUntilChanged().drop(1).collect { ids ->
                val stale = _selected.value
                if (folderGone(stale, ids)) {
                    val next = withTimeoutOrNull(CATEGORIES_WAIT_MS) {
                        categories.first { list -> list.isNotEmpty() && list.none { it.key == stale } }
                    }?.let(::landingKey) ?: LiveKey.All
                    // A pick made while the new list was loading stands.
                    if (_selected.value == stale) _selected.value = next
                }
            }
        }
        // A hidden All or Catch-up — at start, from the remembered category, or switched off in Settings —
        // is never left selected. An empty list is the one not loaded yet, which must not pick Favorites
        // over the first category.
        viewModelScope.launch {
            combine(categories.filter { it.isNotEmpty() }, settings.liveBuiltIns, _selected) { list, builtIns, current ->
                if (builtIns.shows(current)) null else landingKey(list)
            }.collect { next -> if (next != null) _selected.value = next }
        }
    }

    /** Where Live TV lands when nothing else is chosen — core's rule, shared with the television. */
    private fun landingKey(list: List<LiveCategory>): LiveKey = liveLandingKey(list, { it.key }, { it.sealed })

    /** Whether [key] is a provider folder that none of [sourceIds] has (another playlist's, or deleted). */
    private suspend fun folderGone(key: LiveKey, sourceIds: List<Long>): Boolean =
        key is LiveKey.Folder && categoryDao.getById(key.id)?.sourceId !in sourceIds

    private var startupApplied = false

    /** What a visit to the Live TV tab opens on: [channel] brought into view, previewed here or not. */
    data class Opening(val channel: ChannelEntity, val preview: Boolean)

    /**
     * What this visit to the Live TV tab opens on, ahead of the remembered folder. First a channel from
     * Search: its own category (shown, not remembered — the channel was chosen, not the category), the
     * search box cleared; the preview is already Search's to start. Else "Start on: Favorites" or "Start
     * on: Last channel · Live TV", for this launch only, previewed here. Called by the Live TV tab itself:
     * the TV Guide borrowing this list of categories must not eat either. The restore is stopped before
     * any wait, so it cannot land on top.
     */
    suspend fun applyOpeningSelection(): Opening? {
        startupSelection.consumeReveal()?.let { id ->
            startupApplied = true
            restoreJob?.cancel()
            _query.value = ""
            val channel = channelDao.getById(id) ?: return null
            _selected.value = categoryKeyOf(channel)
            return Opening(channel, preview = false)
        }
        if (startupSelection.consumeFavorites()) {
            startupApplied = true
            restoreJob?.cancel()
            _selected.value = LiveKey.Favorites
            return null
        }
        val channel = startupSelection.consumeChannel(guide = false) ?: return null
        startupApplied = true
        restoreJob?.cancel()
        _selected.value = categoryKeyOf(channel)
        return Opening(channel, preview = true)
    }

    /** The category [channel] lives in, or the landing list (All channels while it is shown) when that
     *  category is not in the list (hidden, say) — never a hidden All. */
    suspend fun categoryKeyOf(channel: ChannelEntity): LiveKey {
        val folder = channel.categoryId?.let { LiveKey.Folder(it) } ?: return landingKey(categories.value)
        val listed = withTimeoutOrNull(CATEGORIES_WAIT_MS) { categories.first { list -> list.any { it.key == folder } } } != null
        return if (listed) folder else landingKey(categories.value)
    }

    /** "TV Guide follows Live TV" on: the TV Guide shares Live TV's remembered category. */
    val guideFollowsLive: StateFlow<Boolean> = settings.guideFollowsLiveCategory
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Where the TV Guide opens: null to keep its own category (the switch off); on, Live TV's remembered
     * category checked against the strip complete for this profile, or All channels when it cannot show it.
     */
    suspend fun guideOpeningKey(): LiveKey? {
        if (!settings.guideFollowsLiveCategory.first()) return null
        val c = ctx.first { it.profileId >= 0L }
        val stored = if (settings.rememberCategoryLive.first()) settings.lastLiveCategory.first() else null
        val strip = categoriesFor.first { (built, _) -> built?.profileId == c.profileId && built.liveSourceIds == c.liveSourceIds }.second
        return stored?.let { liveRestoreKey(it, strip, { e -> e.key }, { e -> e.sealed }) } ?: LiveKey.All
    }

    /** The user picks [key]: shown, remembered, and it ends a restore still waiting. */
    fun select(key: LiveKey) {
        restoreJob?.cancel()
        if (_selected.value == key) return
        _selected.value = key
        _query.value = ""
        viewModelScope.launch { settings.setLastLiveCategory(key.serialize()) }
    }

    /**
     * The first channel [key] holds, in the order the list draws it — what a tapped category starts
     * playing (G2/B1).
     *
     * A snapshot read rather than the pager's own list: the tap asks one question ("what is first?")
     * at one instant, and waiting for Paging to load a page would hold the screen still. A short
     * window is read instead of one row, so a hidden first channel does not answer "empty".
     */
    suspend fun firstChannelOf(key: LiveKey): ChannelEntity? {
        val c = ctx.value
        if (c.profileId < 0) return null
        val cust = customOnceRead.value ?: return null
        // A locked category the PIN has not opened has nothing to play.
        if (cust.seals(key)) return null
        val hidden = hiddenCategoryIds.value
        val ids = c.liveSourceIds.ifEmpty { listOf(-1L) }
        val alpha = sortMode.value == SettingsRepository.SortMode.ALPHA
        return withContext(Dispatchers.IO) {
            val raw = when (key) {
                is LiveKey.Folder -> {
                    val category = categoryDao.getById(key.id) ?: return@withContext null
                    channelDao.snapshotByCategoryManual(
                        categoryId = category.id,
                        profileId = c.profileId,
                        contextKey = CustomizeKeys.category(category),
                        alpha = alpha,
                        limit = FIRST_CHANNEL_SCAN,
                    )
                }
                is LiveKey.Custom -> customCategoryDao.snapshotChannels(c.profileId, key.id, ids, alpha, FIRST_CHANNEL_SCAN)
                LiveKey.All -> channelDao.snapshotAll(ids, FIRST_CHANNEL_SCAN)
                LiveKey.Favorites -> channelDao.snapshotFavoritesManual(c.profileId, ContentOrderEntity.FAV_CONTEXT, ids, FIRST_CHANNEL_SCAN)
                LiveKey.History -> channelDao.snapshotHistory(c.profileId, ids, FIRST_CHANNEL_SCAN)
                LiveKey.Catchup -> channelDao.snapshotCatchup(ids, FIRST_CHANNEL_SCAN)
            }
            raw.firstOrNull { tv.own.owntv.core.live.isChannelVisible(it, cust, hidden, key) }
        }?.let { ch -> cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch }
    }

    /**
     * Read the guide for the rows the user can see, in one query.
     *
     * Channels already answered are dropped first, so scrolling one row costs one row's worth of
     * guide rather than a screenful.
     */
    fun loadNowPlaying(visible: List<ChannelEntity>) {
        // An entry whose programme has ended is as good as missing: the next one is read in its place.
        val now = System.currentTimeMillis()
        val missing = visible.filter { ch -> _nowPlaying.value[ch.id]?.let { it.stopMs <= now } ?: true }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val found = epgReader.nowProgrammesFor(missing, custom.value, epgOffset.value)
            if (found.isEmpty()) return@launch
            // Capped like the guide's rowCache: scrolled-past channels are forgotten, and a
            // revisit simply re-reads them through the `missing` check above.
            _nowPlaying.value = (_nowPlaying.value + found).toList()
                .takeLast(MAX_NOW_PLAYING).toMap()
        }
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            // Ask the row itself, not `favoriteIds.value`: that is a WhileSubscribed state flow, and
            // the player's overlay never collects it, so its value would miss the favourite just
            // added there and a "remove" would add it again (IGNORE) instead of deleting it.
            if (favoriteDao.isFavorite(pid, MediaType.LIVE, channel.id).first()) {
                userDataWriter.removeFavorite(pid, MediaType.LIVE, channel.id)
            } else {
                favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = MediaType.LIVE, itemId = channel.id))
            }
        }
    }

    /** Hide the channel from every list (undone in Settings → Customize → Hidden channels). */
    fun hideChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            customize.setItemHidden(pid, MediaType.LIVE, CustomizeKeys.channel(channel), channel.name, true)
        }
    }

    /** Rename the channel for this profile (blank restores the provider's name). */
    fun renameChannel(channel: ChannelEntity, newName: String?) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            customize.renameItem(pid, MediaType.LIVE, CustomizeKeys.channel(channel), newName)
        }
    }

    fun currentEpgMatch(channel: ChannelEntity): String? = custom.value.epgMatchResolver.epgIdFor(channel)

    suspend fun availableEpgChannels(channelName: String, query: String): List<tv.own.owntv.core.epg.GuideCandidate> =
        if (ctx.value.profileId < 0) emptyList()
        else guideCandidates.forPicker(channelName, query)

    /** Map a channel to a guide channel by hand (null clears the override → back to auto-match). */
    fun setEpgMatch(channel: ChannelEntity, epgChannelId: String?) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            customize.setEpgMatch(pid, MediaType.LIVE, CustomizeKeys.channel(channel), epgChannelId)
            // The matched id may have no stored programmes yet — top it up from the cached XMLTV, then
            // drop the channel's stale now/next so the row updates immediately, not after a restart.
            val id = epgChannelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (id != null) runCatching { epgRepository.storeProgrammesForIdsFromCache(setOf(id)) }
            epgReader.invalidate(channel.id)
            _nowPlaying.value = _nowPlaying.value - channel.id
        }
    }

    fun currentEpgShift(channel: ChannelEntity): Int? = EpgShift.overrideFor(custom.value, channel)

    fun globalEpgShift(): Int = epgOffset.value

    /** Shift this channel's guide by [minutes] (null → follow the global offset). */
    fun setEpgShift(channel: ChannelEntity, minutes: Int?) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            customize.setEpgShift(pid, MediaType.LIVE, CustomizeKeys.channel(channel), minutes)
            epgReader.invalidate(channel.id)
            _nowPlaying.value = _nowPlaying.value - channel.id
        }
    }

    /** Hand the channel to VLC, MX Player or whatever else is installed. Stalker channels store a
     *  portal command rather than a URL, so it is resolved first — an external app cannot mint one. */
    fun playExternal(channel: ChannelEntity) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            if (!AdultCategoryClassifier.allows(pid, channel.categoryId, profileDao, categoryDao) || customize.sealsCategory(pid, channel.categoryId)) return@launch
            val source = sourceDao.getById(channel.sourceId)
            val url = if (streamUrlResolver.needsResolve(source)) {
                runCatching { streamUrlResolver.resolve(source!!, channel.streamUrl) }.getOrNull() ?: return@launch
            } else {
                channel.streamUrl
            }
            externalPlayerLauncher.launch(
                url = url,
                title = channel.name,
                userAgent = source?.userAgent,
                httpHeaders = SourceOverrides.headersWithReferer(channel.httpHeaders, source),
            )
        }
    }

    /** Take [channel] out of the custom category [key] only. If it had been moved out of its provider
     *  folder into here, it goes back there — the same as deleting the whole category does. */
    fun removeFromCustomCategory(channel: ChannelEntity, key: LiveKey.Custom) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            userDataWriter.removeCustomCategoryMember(pid, MediaType.LIVE, key.id, channel.id)
            val itemKey = CustomizeKeys.channel(channel)
            custom.value.movedFromOrigin[itemKey]?.let { origin ->
                customize.setItemMovedFromOrigin(pid, MediaType.LIVE, itemKey, origin, moved = false)
            }
        }
    }

    fun removeFromHistory(channelId: Long) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            userDataWriter.removeHistory(pid, MediaType.LIVE, channelId)
        }
    }

    /** The stable key of the list being looked at, or null when it has no stored order to move in. */
    fun contextKeyOf(key: LiveKey): String? = when (key) {
        is LiveKey.Folder -> folderContextKeys.value[key.id]
        is LiveKey.Custom -> key.id
        LiveKey.Favorites -> ContentOrderEntity.FAV_CONTEXT
        // Catch-up, History and All are computed views: there is no order of their own to change.
        LiveKey.History, LiveKey.All, LiveKey.Catchup -> null
    }

    /** The channels of [key] in their current order — what the Move sheet shuffles. */
    suspend fun moveList(key: LiveKey): List<ReorderItem> {
        val c = ctx.value
        if (c.profileId < 0 || custom.value.seals(key)) return emptyList()
        val contextKey = contextKeyOf(key) ?: return emptyList()
        val ids = c.liveSourceIds.ifEmpty { listOf(-1L) }
        val items = when (key) {
            is LiveKey.Folder -> channelDao.snapshotByCategoryManual(key.id, c.profileId, contextKey, sortMode.value == SettingsRepository.SortMode.ALPHA, MOVE_LIST_LIMIT)
            is LiveKey.Custom -> customCategoryDao.snapshotChannels(c.profileId, key.id, ids, sortMode.value == SettingsRepository.SortMode.ALPHA, MOVE_LIST_LIMIT)
            LiveKey.Favorites -> channelDao.snapshotFavoritesManual(c.profileId, contextKey, ids, MOVE_LIST_LIMIT)
            else -> return emptyList()
        }
        return items.map { ReorderItem(it.id, it.name) }
    }

    /** Saves a Move: [itemIds] in their new order, [moved] the ones the user moved (only those keep a place). */
    fun commitMove(contextKey: String, itemIds: List<Long>, moved: Set<Long>) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            contentOrderDao.saveMove(pid, MediaType.LIVE, contextKey, itemIds, moved)
        }
    }

    /** The user's own combined categories — the Move-to-category sheet's targets. A locked one the PIN
     *  has not opened is left out: moving a channel into it would file it where it cannot be seen. */
    val customCategories: StateFlow<List<Pair<String, String>>> = custom
        .map { c -> c.customCategories.filter { it.id !in c.sealedCategories }.map { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createCustomCategory(name: String) {
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            customize.createCustomCategory(pid, MediaType.LIVE, name)
        }
    }

    /**
     * Move (or copy, [keepInOrigin]) a channel into a custom category. Without [keepInOrigin] it
     * leaves where it came from: a favourite row is deleted, a custom-category membership is
     * deleted, and a provider folder is marked — the list then drops it from that folder while
     * keeping it in All.
     */
    fun moveToCategory(channel: ChannelEntity, originKey: String, targetId: String, keepInOrigin: Boolean) {
        if (targetId == originKey) return
        viewModelScope.launch {
            val pid = ctx.value.profileId.takeIf { it >= 0 } ?: return@launch
            val itemKey = CustomizeKeys.channel(channel)
            customCategoryDao.appendItem(pid, MediaType.LIVE, targetId, channel.id)
            if (!keepInOrigin) {
                when {
                    originKey == ContentOrderEntity.FAV_CONTEXT -> userDataWriter.removeFavorite(pid, MediaType.LIVE, channel.id)
                    CustomizeKeys.isCustom(originKey) -> userDataWriter.removeCustomCategoryMember(pid, MediaType.LIVE, originKey, channel.id)
                    else -> customize.setItemMovedFromOrigin(pid, MediaType.LIVE, itemKey, originKey, moved = true)
                }
            }
        }
    }

    /**
     * Pull to refresh: the same manual re-sync the TV app's settings screen enqueues, on every
     * playlist the profile has active. It runs in WorkManager, so leaving the screen does not cancel
     * it — the spinner only reports that it was asked for, which is why it is let go after a moment.
     */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            ctx.value.sources.forEach { source ->
                // A playlist on this phone has nothing to fetch over the network, and core's
                // CONNECTED constraint would never let the job start without validated internet.
                if (LocalPlaylistSync.isLocal(source)) {
                    LocalPlaylistSync.enqueue(context, source.id, reason = "manual")
                } else {
                    syncScheduler.enqueueSync(source.id, reason = "manual")
                }
            }
            kotlinx.coroutines.delay(REFRESH_SPINNER_MS)
            _refreshing.value = false
        }
    }

    private companion object {
        const val PAGE_SIZE = 60
        const val MOVE_LIST_LIMIT = 5_000
        const val REFRESH_SPINNER_MS = 1_200L
        const val SEARCH_DEBOUNCE_MS = 250L
        // "What's on now", one short string per answered channel. Capped — see loadNowPlaying.
        const val MAX_NOW_PLAYING = 500
        // How long a start-up selection waits for the category list on a cold start.
        const val CATEGORIES_WAIT_MS = 2_000L
        // Rows read to find a category's first *visible* channel (a hidden one is skipped).
        const val FIRST_CHANNEL_SCAN = 50
    }
}
