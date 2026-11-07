package tv.own.owntv.mobile.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.metadata.MetadataBudget
import tv.own.owntv.core.metadata.MetadataBudgetStatus
import tv.own.owntv.core.metadata.MetadataConfig
import tv.own.owntv.core.metadata.MetadataProvider
import tv.own.owntv.core.metadata.profileAllowsAdultMetadata
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.profile.ProfileManager
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.player.PlaybackPrefsStore
import tv.own.owntv.core.repository.SourceEditor
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.core.repository.SourceTester
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.StartupChannelRef
import tv.own.owntv.core.settings.StartupMode
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.stalkerCredentials
import tv.own.owntv.core.stalker.stalkerExpiryOf
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncCounts
import tv.own.owntv.core.sync.TrendingActivityTracker
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.mobile.sync.LocalPlaylistSync
import tv.own.owntv.core.sync.work.CatalogSyncState
import tv.own.owntv.core.trending.TrendingAvailability
import tv.own.owntv.core.trending.trendingAvailability

/**
 * The one view model behind every settings page.
 *
 * It deliberately exposes [settings] itself rather than mirroring two hundred preferences as
 * properties: each row collects the flow it displays and calls the setter it owns, so adding a row
 * costs one line here instead of three. Everything a row cannot do on its own — a resync, a deleted
 * source, a cleared history — is a function below, because those need a scope that outlives the row.
 */
class SettingsViewModel(
    private val context: Context,
    val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val sourceRepository: SourceRepository,
    private val profileDao: ProfileDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    private val catalogSync: CatalogSyncScheduler,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val customize: CustomizationStore,
    private val okHttpClient: OkHttpClient,
    private val playbackPrefs: PlaybackPrefsStore,
    private val metadataProvider: MetadataProvider,
    private val metadataBudget: MetadataBudget,
    private val xtreamClient: XtreamClient,
    private val stalkerClient: StalkerClient,
    private val stalkerAuth: StalkerAuthManager,
    private val sourceTester: SourceTester,
    private val importFinalizer: ImportFinalizer,
    private val trendingDao: TrendingDao,
    private val trendingActivity: TrendingActivityTracker,
    private val connectionLimits: tv.own.owntv.core.live.ConnectionLimits,
    // Measuring opens streams, so whatever is playing has to stop first — on a single-connection
    // account the measurement IS the thing that cuts the picture off.
    private val player: tv.own.owntv.player.OwnTVPlayer,
    private val livePreview: tv.own.owntv.player.LivePreviewEngine,
    private val enginePool: tv.own.owntv.player.LiveEnginePool,
    private val sourceEditor: SourceEditor,
    private val profileManager: ProfileManager,
) : ViewModel() {

    /** Run a setter on a scope that survives the row being scrolled off the screen. */
    fun edit(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settings.block() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sources: StateFlow<List<SourceEntity>> = settings.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId < 0) sourceDao.observeAll() else sourceDao.observeForProfile(profileId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<List<ProfileEntity>> = profileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The one profile this app keeps, which is the profile the App lock, Kids mode and Allow downloads
     * rows edit. Null only while Room is still answering.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeProfile: StateFlow<ProfileEntity?> = settings.activeProfileId
        .flatMapLatest { id -> if (id < 0) flowOf(null) else profileDao.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ask the profile's own PIN — what the App lock and Kids-off rows do before they change anything. */
    fun verifyPin(profile: ProfileEntity, pin: String): Boolean = profileManager.verifyPin(profile, pin)

    /** App lock PIN: null keeps the existing one, "" removes the lock, anything else sets it. */
    fun setAppLockPin(pin: String?) {
        viewModelScope.launch {
            val profile = activeProfileNow() ?: return@launch
            profileManager.edit(profile, profile.name, profile.avatarId, profile.isKids, pin)
        }
    }

    /** Kids mode is a flag on the profile row, so the rest of it is handed back unchanged. */
    fun setKidsMode(enabled: Boolean) {
        viewModelScope.launch {
            val profile = activeProfileNow() ?: return@launch
            profileManager.edit(profile, profile.name, profile.avatarId, enabled, pin = null)
        }
    }

    /** Download permission, stored per profile but now with only one profile to store it for. */
    fun setDownloadsAllowed(allowed: Boolean) {
        viewModelScope.launch {
            val id = settings.activeProfileId.first()
            if (id >= 0) settings.setDownloadsAllowed(id, allowed)
        }
    }

    // Read fresh by id rather than from [activeProfile]: a row edited while a sheet was open is newer
    // than anything the sheet captured, and `edit` writes the whole row.
    private suspend fun activeProfileNow(): ProfileEntity? =
        profileDao.getById(settings.activeProfileId.first())

    fun syncState(sourceId: Long): Flow<CatalogSyncState> = catalogSync.observeSync(sourceId)

    private data class TrendingSettingsData(
        val sourceIds: Set<Long> = emptySet(),
        val states: List<TrendingSnapshotEntity> = emptyList(),
        val metadataEnabled: Boolean = true,
    )

    /**
     * Why the Now Trending row is, or is not, on Home — core's own rule, so the sentence under the
     * row here is the sentence the television shows for the same playlist.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val trendingAvailability: StateFlow<TrendingAvailability> =
        combine(settings.activeProfileId, settings.metadataConfigFlow) { profileId, metadata ->
            profileId to metadata.enabled
        }.flatMapLatest { (profileId, metadataEnabled) ->
            flow {
                val sourceIds = if (profileId < 0) emptyList() else sourceDao.sourceIdsForProfile(profileId)
                if (sourceIds.isEmpty()) {
                    emit(TrendingSettingsData(metadataEnabled = metadataEnabled))
                } else {
                    emitAll(
                        trendingDao.observeStatesForSources(sourceIds).map { states ->
                            TrendingSettingsData(sourceIds.toSet(), states, metadataEnabled)
                        },
                    )
                }
            }
        }.combine(trendingActivity.active) { data, active ->
            trendingAvailability(
                states = data.states,
                metadataEnabled = data.metadataEnabled,
                building = active.keys.any { it in data.sourceIds },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendingAvailability.WaitingForSync)

    /** The playlist the rest of the app is filtered to, or -1 when every playlist is shown. */
    val defaultSourceId: StateFlow<Long> = settings.defaultSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    fun setDefaultSource(id: Long) {
        viewModelScope.launch { settings.setDefaultSource(id) }
    }

    /**
     * When each account runs out, by source id. Xtream reads the panel's `exp_date`; a MAG portal is
     * asked for its profile. A plain M3U file has no account, so it is never listed, and any failure
     * simply leaves the line off the row rather than showing a wrong date.
     *
     * Fetched once per source while the page is on screen — the answer is a date, not a live number.
     */
    private val expiryCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    val sourceExpiry: StateFlow<Map<Long, String>> = sources
        .map { list ->
            val out = HashMap<Long, String>()
            for (s in list) {
                if (s.type != SourceType.XTREAM && s.type != SourceType.STALKER) continue
                val value = expiryCache[s.id] ?: fetchExpiry(s)?.also { expiryCache[s.id] = it } ?: continue
                out[s.id] = value
            }
            out
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private suspend fun fetchExpiry(s: SourceEntity): String? = runCatching {
        when (s.type) {
            SourceType.XTREAM -> xtreamClient.accountExpiryMs(s)?.let {
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))
            }
            SourceType.STALKER -> s.mac?.let { StalkerClient.canonicalizeMac(it) }?.let { mac ->
                val creds = s.stalkerCredentials(mac)
                stalkerAuth.withAuthRetry(creds) { session ->
                    val info = runCatching {
                        stalkerClient.getAccountInfo(session.apiBase, mac, session.token, creds.userAgent)
                    }.getOrDefault(emptyMap())
                    stalkerExpiryOf(info) ?: stalkerExpiryOf(session.profile)
                }
            }
            else -> null
        }
    }.getOrNull()

    /** How many channels, films and shows a playlist actually holds — re-counted when a sync or the background backfill ends. */
    fun contentCounts(sourceId: Long): Flow<SyncCounts> = catalogSync.catalogueSettled(sourceId)
        .map { importFinalizer.contentCounts(sourceId) }

    /**
     * Fetch the catalogue again. [removeMissing] is the destructive half of the TV app's two resync
     * choices: it lets the run prune titles the provider has stopped listing, and it is never the
     * default.
     */
    fun resync(source: SourceEntity, removeMissing: Boolean = false) {
        // A playlist on this phone needs no network, and core's CONNECTED constraint (INTERNET +
        // TRUSTED + VALIDATED) would park the job forever on a device without validated internet.
        if (LocalPlaylistSync.isLocal(source)) {
            LocalPlaylistSync.enqueue(context, source.id, reason = "manual", forcePrune = removeMissing)
        } else {
            catalogSync.enqueueSync(source.id, reason = "manual", forcePrune = removeMissing)
        }
    }

    /**
     * Stores a playlist's "Clean channel names" option and its own words ([customText], `;`-separated;
     * blank = none). Names are cleaned at sync time, so the playlist re-syncs whenever the shown names
     * would change: the option was switched, or its words changed while it is on.
     */
    fun saveChannelNameCleanup(source: SourceEntity, enabled: Boolean, customText: String) {
        val strip = customText.trim().ifEmpty { null }
        if (enabled == source.cleanChannelNames && strip == source.channelNameStrip) return
        val namesChange = enabled || source.cleanChannelNames
        viewModelScope.launch {
            sourceDao.updateChannelNameCleanup(source.id, enabled, strip)
            if (namesChange) resync(source)
        }
    }

    fun cancelResync(source: SourceEntity) {
        catalogSync.cancelSync(source.id)
    }

    /**
     * Save an edited playlist. Blank fields keep what is stored, so a password field left empty on an
     * edit does not wipe the password. Saving, the Stalker session drop and the decision to fetch the
     * playlist again are core's [SourceEditor].
     */
    fun updateSource(
        id: Long,
        name: String,
        urlOrServer: String,
        user: String,
        pass: String,
        userAgent: String,
        httpReferer: String,
        autoRefresh: PlaylistRefresh,
        mac: String = "",
        stalkerSerialNumber: String = "",
        stalkerDeviceId: String = "",
        stalkerDeviceId2: String = "",
        stalkerSignature: String = "",
        syncLive: Boolean = true,
        syncMovies: Boolean = true,
        syncSeries: Boolean = true,
        preferHls: Boolean = false,
    ) {
        viewModelScope.launch {
            val existing = sourceDao.getById(id) ?: return@launch
            // A Stalker login is optional, so a cleared username removes it (and its password).
            val stalker = existing.type == tv.own.owntv.core.model.SourceType.STALKER
            val newUser = user.trim().takeIf { it.isNotBlank() } ?: existing.username.takeUnless { stalker }
            val updated = existing.copy(
                name = name.trim().ifBlank { existing.name },
                url = urlOrServer.trim().ifBlank { existing.url },
                username = newUser,
                password = if (stalker && newUser == null) null else pass.takeIf { it.isNotBlank() } ?: existing.password,
                mac = StalkerClient.canonicalizeMac(mac) ?: existing.mac,
                stalkerSerialNumber = stalkerSerialNumber.trim().takeIf { it.isNotBlank() },
                stalkerDeviceId = stalkerDeviceId.trim().takeIf { it.isNotBlank() },
                stalkerDeviceId2 = stalkerDeviceId2.trim().takeIf { it.isNotBlank() },
                stalkerSignature = stalkerSignature.trim().takeIf { it.isNotBlank() },
                userAgent = userAgent.trim().takeIf { it.isNotBlank() },
                httpReferer = httpReferer.trim().takeIf { it.isNotEmpty() },
                syncLive = syncLive,
                syncMovies = syncMovies,
                syncSeries = syncSeries,
                preferHls = preferHls,
            )
            sourceEditor.save(existing, updated)
            settings.setPlaylistAutoRefresh(id, autoRefresh)
            expiryCache.remove(id)
        }
    }

    /**
     * Removing a playlist cascades through every channel, film and episode it brought in — hundreds
     * of thousands of rows on a big provider. The row says so and hides its actions until it is done,
     * and the delete itself finishes even if the page is left: a half-deleted playlist is worse than
     * a wait.
     */
    private val _deletingSourceIds = MutableStateFlow<Set<Long>>(emptySet())
    val deletingSourceIds: StateFlow<Set<Long>> = _deletingSourceIds.asStateFlow()

    fun deleteSource(source: SourceEntity) {
        if (source.id in _deletingSourceIds.value) return
        viewModelScope.launch {
            _deletingSourceIds.value = _deletingSourceIds.value + source.id
            try {
                catalogSync.cancelSync(source.id)
                stalkerAuth.invalidate(source.id)
                withContext(NonCancellable) {
                    sourceRepository.deleteSource(source)
                    if (defaultSourceId.value == source.id) settings.setDefaultSource(-1L)
                }
            } finally {
                _deletingSourceIds.value = _deletingSourceIds.value - source.id
                expiryCache.remove(source.id)
            }
        }
    }

    /** Null while no test is on screen; [SourceTestState.Running] while the request is in flight. */
    sealed interface SourceTestState {
        val sourceName: String
        data class Running(override val sourceName: String) : SourceTestState

        /**
         * The connection measurement, which takes minutes rather than the fraction of a second the
         * liveness check takes — hence its own state, with progress the user has to be able to see.
         */
        data class Measuring(
            override val sourceName: String,
            val progress: tv.own.owntv.core.live.ProbeProgress,
        ) : SourceTestState

        data class Done(
            override val sourceName: String,
            val result: SourceTestResult,
            /** What is stored for this playlist, measured or published. Null before anything is known. */
            val limit: tv.own.owntv.core.live.ConnectionLimit? = null,
        ) : SourceTestState
    }

    private val _sourceTest = MutableStateFlow<SourceTestState?>(null)
    val sourceTest: StateFlow<SourceTestState?> = _sourceTest.asStateFlow()

    /** Ask the provider whether the account is still good, without waiting for a sync to fail. */
    /**
     * The Info sheet: what is already known, plus a quick liveness check.
     *
     * The measured stream limit is read straight from the playlist row and never re-measured here —
     * measuring costs minutes and stops playback, so it belongs behind Re-test.
     */
    fun testSource(source: SourceEntity) {
        viewModelScope.launch {
            _sourceTest.value = SourceTestState.Running(source.name)
            val result = sourceTester.test(source)
            // The sheet may have been dismissed while the request ran; don't re-open it.
            if (_sourceTest.value != null) {
                _sourceTest.value = SourceTestState.Done(source.name, result, connectionLimits.known(source))
            }
        }
    }

    /**
     * Re-test: measure how many streams this provider really allows, by opening them.
     *
     * Stops playback first, and deliberately not gently — the user agreed to a warning that says so.
     */
    /** Per-playlist "Ignore connection limit": Multiview and recordings stop counting the provider's limit. */
    fun setIgnoreConnectionLimit(source: SourceEntity, ignore: Boolean) {
        viewModelScope.launch { sourceDao.updateIgnoreConnectionLimit(source.id, ignore) }
    }

    fun retestSource(source: SourceEntity) {
        measureJob?.cancel()
        measureJob = viewModelScope.launch {
            runCatching { player.stop() }
            runCatching { livePreview.stop() }
            runCatching { enginePool.releaseAll() }
            _sourceTest.value = SourceTestState.Measuring(
                source.name,
                tv.own.owntv.core.live.ProbeProgress(1, 1, tv.own.owntv.core.live.MAX_PROBE_STREAMS),
            )
            val limit = connectionLimits.measureAndStore(source, force = true) { progress ->
                if (_sourceTest.value is SourceTestState.Measuring) {
                    _sourceTest.value = SourceTestState.Measuring(source.name, progress)
                }
            }
            val result = sourceTester.test(source)
            if (_sourceTest.value != null) _sourceTest.value = SourceTestState.Done(source.name, result, limit)
        }
    }

    /**
     * Abandon a measurement in progress.
     *
     * Cancelling the coroutine is what closes the streams — the probe releases them in a `finally` —
     * so nothing keeps holding a connection the user is about to want back. What the run had already
     * confirmed is discarded rather than saved: half a measurement is a guess.
     */
    fun skipConnectionMeasurement() {
        measureJob?.cancel()
        measureJob = null
        _sourceTest.value = null
    }

    private var measureJob: kotlinx.coroutines.Job? = null

    fun dismissSourceTest() { _sourceTest.value = null }

    /** Clear what the user has watched. Null clears everything; a type clears just that section. */
    fun clearHistory(type: MediaType? = null) {
        viewModelScope.launch {
            val profileId = settings.activeProfileId.first()
            if (profileId < 0) return@launch
            // Continue-watching comes from the resume table, not from history, and an episode's
            // progress is stored under EPISODE — both go with it; Live has no resume position to
            // clear. That, and recording each deletion so a local sync does not hand the cleared
            // history straight back, is what userDataWriter does.
            userDataWriter.clearHistory(profileId, type)
        }
    }

    /**
     * Every folder a section has, hidden ones included — the customize page is where a hidden folder
     * is brought back, so it cannot browse the same filtered list the section itself does.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun categories(type: MediaType): Flow<List<CategoryEntity>> = sources.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else categoryDao.observe(list.map { it.id }, type)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun customizations(type: MediaType): Flow<SectionCustomizations> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) flowOf(SectionCustomizations()) else customize.observe(pid, type)
        }

    // --- Per-item playback choices the player remembered, and the rows that forget them ---

    /**
     * How many individual items have a remembered zoom.
     *
     * Counted because it is forgotten on its own row: wanting every film back at the
     * default aspect is not a request to lose the levels set on the quiet ones.
     */
    val savedZoomCount: StateFlow<Int> = playbackPrefs.observeZoomCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun clearSavedZoom() { viewModelScope.launch { playbackPrefs.clearZoom() } }

    // --- Per-playlist override of the global Catch-up time zone ---

    /** `null` mode follows the global catch-up time zone; [offsetMin] only matters for MANUAL. */
    fun setSourceCatchupTimezone(sourceId: Long, mode: String?, offsetMin: Int?) {
        viewModelScope.launch { sourceDao.updateCatchupTimezone(sourceId, mode, offsetMin) }
    }

    // --- Metadata: which tier is answering, what is left of the allowance, and a lookup to prove it ---

    val metadataTier: StateFlow<MetadataConfig.Tier> = settings.metadataConfigFlow
        .map { it.tier }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetadataConfig.Tier.DEFAULT_WORKER)

    /**
     * What is left of this install's allowance. Only meaningful on the shared default Worker — an own
     * key or a self-hosted server is the user's own resource and is never metered, so the page hides
     * this on those two rather than showing a limit that does not exist.
     */
    private val _metadataBudgetStatus = MutableStateFlow<MetadataBudgetStatus?>(null)
    val metadataBudgetStatus: StateFlow<MetadataBudgetStatus?> = _metadataBudgetStatus.asStateFlow()

    fun refreshMetadataBudget() {
        viewModelScope.launch {
            _metadataBudgetStatus.value = runCatching { metadataBudget.status() }.getOrNull()
        }
    }

    sealed interface MetadataTestState {
        data object Idle : MetadataTestState
        data object Testing : MetadataTestState
        data class Ok(val title: String, val year: Int?, val tmdbId: Int) : MetadataTestState
        data class Fail(val failure: MetadataFailure) : MetadataTestState
    }

    sealed interface MetadataFailure {
        data object EmptyTitle : MetadataFailure
        data object ServerUnavailable : MetadataFailure
        data class NoMatch(val query: String) : MetadataFailure
        data class Unknown(val rawMessage: String?) : MetadataFailure
    }

    private val _metadataTest = MutableStateFlow<MetadataTestState>(MetadataTestState.Idle)
    val metadataTest: StateFlow<MetadataTestState> = _metadataTest.asStateFlow()

    /**
     * Look one title up through whichever tier is configured. This is the only way to find out that a
     * key or a server address is wrong without waiting for posters to quietly stop appearing.
     */
    fun testMetadataLookup(title: String) {
        if (_metadataTest.value == MetadataTestState.Testing) return
        val q = title.trim()
        if (q.isEmpty()) {
            _metadataTest.value = MetadataTestState.Fail(MetadataFailure.EmptyTitle)
            return
        }
        _metadataTest.value = MetadataTestState.Testing
        viewModelScope.launch {
            val profileId = settings.activeProfileId.first()
            val includeAdult = profileAllowsAdultMetadata(profileDao.getById(profileId)?.isKids)
            val result = runCatching { metadataProvider.searchMovie(q, includeAdult = includeAdult) }
            _metadataTest.value = result.fold(
                onSuccess = { hits ->
                    val top = hits?.firstOrNull()
                    when {
                        hits == null -> MetadataTestState.Fail(MetadataFailure.ServerUnavailable)
                        top == null -> MetadataTestState.Fail(MetadataFailure.NoMatch(q))
                        else -> MetadataTestState.Ok(top.title, top.year, top.tmdbId)
                    }
                },
                onFailure = {
                    MetadataTestState.Fail(MetadataFailure.Unknown(it.message?.takeIf { m -> m.isNotBlank() }))
                },
            )
        }
    }

    /** Clears a stale result, so a key that has just been removed stops reporting its old match. */
    fun resetMetadataTest() { _metadataTest.value = MetadataTestState.Idle }

    // --- Proxy and DNS, saved as a form and testable before it is saved ---

    fun saveProxy(enabled: Boolean, host: String, port: Int, username: String, password: String) {
        viewModelScope.launch { settings.saveProxy(enabled, host, port, username, password) }
    }

    fun saveDns(enabled: Boolean, host: String, port: Int, dohUrl: String) {
        viewModelScope.launch { settings.saveDns(enabled, host, port, dohUrl) }
    }

    private val _proxyTest = MutableStateFlow<NetworkTestState>(NetworkTestState.Idle)
    val proxyTest: StateFlow<NetworkTestState> = _proxyTest.asStateFlow()

    private val _dnsTest = MutableStateFlow<NetworkTestState>(NetworkTestState.Idle)
    val dnsTest: StateFlow<NetworkTestState> = _dnsTest.asStateFlow()

    /**
     * Try the typed proxy, not the saved one — the point is to find out whether it works *before*
     * committing it, so a wrong port cannot take the whole app offline.
     */
    fun testProxy(host: String, port: Int, username: String, password: String) {
        if (_proxyTest.value == NetworkTestState.Testing) return
        _proxyTest.value = NetworkTestState.Testing
        viewModelScope.launch {
            _proxyTest.value = probeProxy(okHttpClient, host, port, username, password)
        }
    }

    fun testDns(host: String, port: Int, dohUrl: String) {
        if (_dnsTest.value == NetworkTestState.Testing) return
        _dnsTest.value = NetworkTestState.Testing
        viewModelScope.launch { _dnsTest.value = probeDns(host, port, dohUrl) }
    }

    // --- App startup, which is stored per profile ---

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val startupMode: StateFlow<StartupMode> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(StartupMode.HOME) else settings.startupMode(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupMode.HOME)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val startupChannel: StateFlow<StartupChannelRef?> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(null) else settings.startupChannel(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setStartupMode(mode: StartupMode) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (pid >= 0) settings.setStartupMode(pid, mode)
        }
    }

    fun setStartupChannel(channel: ChannelEntity) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (pid < 0) return@launch
            settings.setSpecificStartupChannel(
                pid,
                StartupChannelRef(channel.sourceId, channel.remoteId, channel.name, channel.id),
            )
        }
    }

    /** Names matching [query], bounded — the startup picker is a search box, not the whole playlist. */
    suspend fun searchChannels(query: String): List<ChannelEntity> {
        val ids = sources.value.filter { it.syncLive }.map { it.id }
        if (ids.isEmpty()) return emptyList()
        return channelDao.searchList(query.trim(), ids, limit = 60)
    }
}
