package tv.own.owntv.mobile.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.SearchIntent
import tv.own.owntv.core.content.SearchKind
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.tvbox.search.OnlineSearchAdapter
import tv.own.owntv.mobile.tvbox.search.OnlineSearchState
import tv.own.owntv.mobile.tvbox.search.TvboxOnlineSearch

/**
 * Search, the shipped television's: one field over channels, films, shows and the guide.
 *
 * The searching itself is core's [SearchReader] — the same rules the television searches by, so a hidden
 * title stays hidden and a renamed channel keeps its new name on both. What is here is the phone's part:
 * the typing delay, the recent terms, the tab, and what the rows say beside each result — what is on each
 * channel, a film's category, which playlist it came from. Search shows; it never plays or edits.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val searchReader: SearchReader,
    private val settings: SettingsRepository,
    sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val epgReader: LiveEpgReader,
    private val customize: CustomizationStore,
    private val onlineSearch: TvboxOnlineSearch,
) : ViewModel() {

    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveProfileSources(-1L, emptyList()))

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _intent = MutableStateFlow<SearchIntent?>(null)
    val intent: StateFlow<SearchIntent?> = _intent.asStateFlow()

    /**
     * What the query found — every match, up to core's cap, in one go. Debounced, because every
     * keystroke otherwise costs three queries over a catalogue that can hold a quarter of a million rows.
     */
    val results: StateFlow<SearchResults> = combine(
        _query.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(),
        ctx,
    ) { q, c -> q to c }
        .flatMapLatest { (q, c) ->
            if (q.length < MIN_QUERY) flowOf(SearchResults())
            else flow { emit(searchReader.search(c.profileId, c, q)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /** The list behind a JUMP TO tool: Continue watching, Unwatched favourites, or Channels. */
    val curated: StateFlow<SearchResults> = combine(_intent, ctx) { i, c -> i to c }
        .flatMapLatest { (i, c) ->
            if (i == null) flowOf(SearchResults()) else flow { emit(searchReader.curated(c.profileId, c, i)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    val recentSearches: StateFlow<List<String>> = settings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which playlist a row comes from, as on the browse screens; empty with one playlist. */
    val playlistMarks: StateFlow<Map<Long, PlaylistMark>> = ctx
        .map { c -> if (c.sources.size < 2) emptyMap() else c.sources.withIndex().associate { (i, s) -> s.id to PlaylistMark.of(s.name, i) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Category names for the film and show rows ("2005 · ★ 6.2 · Disney+ Movies"). */
    val categoryNames: StateFlow<Map<Long, String>> = combine(results, curated) { a, b ->
        (a.movies.mapNotNull { it.categoryId } + a.series.mapNotNull { it.categoryId } +
            b.movies.mapNotNull { it.categoryId } + b.series.mapNotNull { it.categoryId }).toSet()
    }
        .distinctUntilChanged()
        .mapLatest { ids -> ids.mapNotNull { id -> categoryDao.getById(id)?.let { id to it.name } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** What is on each found channel now, for its line ("Sky Cinema · 255 · Oppenheimer"). */
    val nowPlaying: StateFlow<Map<Long, String>> = combine(results, curated, ctx) { a, b, c -> Triple(a.channels + b.channels, c, Unit) }
        .mapLatest { (rows, c, _) ->
            val channels = rows.map { it.channel }.distinctBy { it.id }
            if (channels.isEmpty() || c.profileId < 0) emptyMap()
            else epgReader.nowProgrammesFor(channels, customize.observeVisible(c.profileId, MediaType.LIVE).first(), settings.epgOffsetMinutes.first())
                .mapValues { it.value.title }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setQuery(q: String) {
        _query.value = q
        // Typing is the user leaving the launcher behind; the curated list would otherwise stay up
        // underneath the results.
        if (q.isNotBlank()) _intent.value = null
    }

    /** Picking a JUMP TO tool clears the query, so the two never compete for the same list. */
    fun setIntent(i: SearchIntent?) {
        _intent.value = i
        if (i != null) _query.value = ""
    }

    /**
     * The filter buttons (the television's, 12 Oct): Live TV · TV Shows · Movies · Series, each on or off,
     * several at once, none on meaning everything. Kept across new search text; Back clears it.
     */
    private val _kinds = MutableStateFlow<Set<SearchKind>>(emptySet())
    val kinds: StateFlow<Set<SearchKind>> = _kinds.asStateFlow()

    fun toggleKind(k: SearchKind) {
        _kinds.value = if (k in _kinds.value) _kinds.value - k else _kinds.value + k
    }

    /** "All n channels": Live TV alone. */
    fun showOnly(k: SearchKind) {
        _kinds.value = setOf(k)
    }

    fun clearKinds() {
        _kinds.value = emptySet()
    }

    /**
     * The online supplement (plan P3): TVbox site hits for the typed term, streamed as each site
     * answers and deduped against what the local index already shows. Runs only when a film could be
     * shown (no kind filter, or Movies on) and debounced longer than the local search — a remote round
     * costs a dozen site requests, not one Room query. Swapping the term cancels the previous round.
     */
    val online: StateFlow<OnlineSearchState> = combine(
        combine(
            _query.map { it.trim() }.debounce(REMOTE_DEBOUNCE_MS).distinctUntilChanged(),
            _kinds,
            ctx,
        ) { q, kinds, c -> Triple(q, kinds, c) }
            .flatMapLatest { (q, kinds, c) ->
                val moviesShown = kinds.isEmpty() || SearchKind.MOVIES in kinds
                if (q.length < MIN_QUERY || !moviesShown) flowOf(OnlineSearchState())
                else onlineSearch.search(q, c.profileId)
            },
        results,
    ) { raw, local ->
        OnlineSearchAdapter.dedupe(raw, local.movies.mapNotNull { it.remoteId }.toSet())
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnlineSearchState())

    /** Remembered only when a result is actually opened — a term nobody used is not a search. */
    fun rememberQuery() {
        viewModelScope.launch { settings.addRecentSearch(_query.value) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settings.clearRecentSearches() }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val REMOTE_DEBOUNCE_MS = 800L
        const val MIN_QUERY = 2
    }
}
