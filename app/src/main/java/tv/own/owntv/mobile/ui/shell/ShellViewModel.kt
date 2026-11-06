package tv.own.owntv.mobile.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.nav.NavVisibility
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.StartupMode

/** Shell state: which destinations the active playlist offers, and the tap that sends a list home. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModel(
    navVisibility: NavVisibility,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
) : ViewModel() {

    /**
     * True until Room and the preference have both answered, so the shell waits instead of guessing.
     *
     * On the way it fixes the one thing an upgrade can break: a stored active profile that no longer
     * names a row. Falling back to the first profile keeps that person's library, favorites and
     * history — the rows are all still on the device, only the pointer was lost — and it means the
     * shell never mistakes a half-second of loading for a fresh install and opens Setup over them.
     */
    private val _recovering = MutableStateFlow(true)

    val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val all = profileDao.getAllOnce()
                val active = settings.activeProfileId.first()
                if (all.isNotEmpty() && all.none { it.id == active }) settings.setActiveProfile(all.first().id)
            }
            _recovering.value = false
        }
    }

    /** Core's rule, unchanged — the same set the TV app's rail is built from. */
    val visibleSections: StateFlow<Set<MainSection>> = navVisibility.visibleSections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainSection.allBrowse)

    /**
     * The playlists the active profile can pick between, for the top bar's selector.
     *
     * The profile's own linked sources, **not** `activeProfileSources()` — that one has the chosen
     * playlist already applied, so a picker built on it would offer only the playlist it is showing.
     */
    val playlists: StateFlow<List<SourceEntity>> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) flowOf(emptyList()) else sourceDao.observeForProfile(pid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which one is showing. `-1` — or an id the profile no longer has — means All playlists. */
    val activePlaylistId: StateFlow<Long> = settings.defaultSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    /** Narrow every browse screen to one playlist, or to all of them. Persisted, so it survives a
     *  restart — it is the same value Settings → Playlists calls the default. */
    fun selectPlaylist(sourceId: Long) {
        viewModelScope.launch { settings.setDefaultSource(sourceId) }
    }

    private val _scrollToTop = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits a route whose list should jump back to the top — a long press on its nav item. */
    val scrollToTop: SharedFlow<String> = _scrollToTop.asSharedFlow()

    fun requestScrollToTop(route: String) {
        viewModelScope.launch { _scrollToTop.emit(route) }
    }

    /**
     * Where this launch should land, from the profile's own "Start on" setting.
     *
     * A stored channel is looked up three ways because a re-sync gives every channel a new row id:
     * the provider's own id first, then the name, then the old row id. Losing the channel is not a
     * reason to refuse to start, so anything unresolved lands on Home and says so.
     */
    suspend fun resolveStartup(): StartupTarget {
        val ctx = activeProfileSources(settings, sourceDao).first { it.profileId >= 0L }
        return when (val mode = settings.startupMode(ctx.profileId).first()) {
            StartupMode.HOME -> StartupTarget.Home
            StartupMode.FAVORITES -> StartupTarget.Favorites
            StartupMode.LAST_CHANNEL, StartupMode.LAST_CHANNEL_GUIDE, StartupMode.LAST_CHANNEL_LIVE -> {
                val channel = channelDao.recentlyWatched(ctx.profileId, 1).first().firstOrNull()
                when {
                    channel == null || !isVisible(channel, ctx.profileId, ctx.sources.map { it.id }.toSet()) -> StartupTarget.Home
                    mode == StartupMode.LAST_CHANNEL -> StartupTarget.Channel(channel.id)
                    else -> StartupTarget.ChannelIn(channel, guide = mode == StartupMode.LAST_CHANNEL_GUIDE)
                }
            }
            StartupMode.SPECIFIC_CHANNEL -> {
                val ref = settings.startupChannel(ctx.profileId).first()
                val channel = ref?.let {
                    (it.remoteId?.takeIf(String::isNotBlank)?.let { id -> channelDao.findByRemote(it.sourceId, id) })
                        ?: channelDao.findByName(it.sourceId, it.name)
                        ?: it.itemId.takeIf { id -> id > 0L }?.let { id -> channelDao.getById(id) }
                }
                if (channel != null && isVisible(channel, ctx.profileId, ctx.sources.map { it.id }.toSet())) {
                    StartupTarget.Channel(channel.id)
                } else {
                    StartupTarget.ChannelUnavailable
                }
            }
        }
    }

    /** A channel the profile cannot reach must not be opened by a setting the profile did not set, nor one
     *  in a locked category: a launch starts with every lock shut. */
    private suspend fun isVisible(channel: ChannelEntity, profileId: Long, sourceIds: Set<Long>): Boolean =
        channel.sourceId in sourceIds &&
            AdultCategoryClassifier.allows(profileId, channel.categoryId, profileDao, categoryDao) &&
            !customize.sealsCategory(profileId, channel.categoryId)
}

/** Where a launch lands. */
sealed interface StartupTarget {
    data object Home : StartupTarget
    data object Favorites : StartupTarget
    data class Channel(val id: Long) : StartupTarget

    /** The TV Guide ([guide]) or Live TV, on [channel]'s category with the channel in the preview. */
    data class ChannelIn(val channel: ChannelEntity, val guide: Boolean) : StartupTarget

    /** The chosen channel is gone — the playlist dropped it, or a re-sync renamed it. */
    data object ChannelUnavailable : StartupTarget
}

/**
 * "Start on: Favorites" or "Start on: Last channel · Live TV / TV Guide", handed to that screen.
 *
 * The shell decides it and the screen acts on it, and the two are built at different moments on
 * different screens, so it is a one-shot the shell sets and the screen takes exactly once — a second
 * visit in the same session is an ordinary visit.
 */
class StartupLiveSelection {
    private var pendingFavorites = false
    private var pendingChannel: StartupTarget.ChannelIn? = null

    fun requestFavorites() { pendingFavorites = true }

    fun consumeFavorites(): Boolean = pendingFavorites.also { pendingFavorites = false }

    fun requestChannel(target: StartupTarget.ChannelIn) { pendingChannel = target }

    /** The channel to open on, if the request was for this screen — Live TV ([guide] false) or the TV Guide. */
    fun consumeChannel(guide: Boolean): ChannelEntity? =
        pendingChannel?.takeIf { it.guide == guide }?.channel?.also { pendingChannel = null }

    private var pendingReveal: Long? = null

    /** Search's channel result: Live TV opens on that channel's own category. */
    fun requestReveal(channelId: Long) { pendingReveal = channelId }

    /**
     * The channel Search asked Live TV to open on, once. Taken by the Live TV tab only (the TV Guide
     * borrows Live TV's list but never this). Taking it drops a "Start on" still waiting: the user has
     * just said which channel they want.
     */
    fun consumeReveal(): Long? = pendingReveal?.also {
        pendingReveal = null
        pendingFavorites = false
        pendingChannel = null
    }
}
