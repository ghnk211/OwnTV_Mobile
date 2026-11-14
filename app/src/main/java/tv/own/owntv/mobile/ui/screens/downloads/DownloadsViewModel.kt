package tv.own.owntv.mobile.ui.screens.downloads

import tv.own.owntv.core.storage.missingFiles
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.categorylock.anyLockSealed
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.ItemCategory
import tv.own.owntv.core.customize.sealsItem
import tv.own.owntv.core.customize.sealsItemIn
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.download.DownloadActivityTracker
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.download.DownloadStorageInfo
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.MediaRoot
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.core.trending.ProviderVariantParser
import tv.own.owntv.mobile.ui.screens.library.VodTuner

/**
 * What has been saved for offline watching, and how the queue is getting on.
 *
 * The queue itself is core's — the same worker that feeds the television — so this only reads it,
 * filters out what the profile is not allowed to see, and offers the four buttons a download can
 * need. The one thing computed here is the transfer rate: core records bytes, not speed, so the
 * screen measures it from two readings a second apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
    private val downloadManager: DownloadManager,
    private val vodTuner: VodTuner,
    tracker: DownloadActivityTracker,
) : ViewModel() {

    /**
     * The profile's downloads, minus rows whose film or show the user has hidden — hidden is hidden
     * everywhere, Downloads included. The file stays on disk and the row comes back on unhide. A title in
     * a locked category stays out the same way until the PIN opens that category; one whose film or show
     * is gone stays out while the profile has a lock that is not open, since its category is unknown.
     */
    val downloads: StateFlow<List<DownloadEntity>> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) {
                flowOf(emptyList())
            } else {
                combine(
                    downloadDao.observeForProfile(pid),
                    customize.observeVisible(pid, MediaType.MOVIE),
                    customize.observeVisible(pid, MediaType.SERIES),
                    profileDao.observeById(pid),
                    customize.anyLockSealed(pid),
                ) { list, custMovie, custSeries, profile, anySealed ->
                    val noSeals = !anySealed && custMovie.sealedCategoryIds.isEmpty() && custSeries.sealedCategoryIds.isEmpty()
                    if (custMovie.hiddenItems.isEmpty() && custSeries.hiddenItems.isEmpty() && noSeals &&
                        profile?.isKids != true
                    ) {
                        list
                    } else {
                        // One bulk read per table instead of one query per row: every emission
                        // re-ran the whole chain. Episodes have no bulk lookup in core, so those
                        // stay per-row; movies and series are prefetched (empty lists guarded —
                        // Room rejects `IN ()`).
                        val movieIds = list
                            .filter { it.mediaType == MediaType.MOVIE }
                            .map { it.itemId }.distinct()
                        val movies = if (movieIds.isEmpty()) emptyMap()
                        else movieDao.getByIds(movieIds).associateBy { it.id }
                        val episodes = list
                            .filter { it.mediaType == MediaType.EPISODE }
                            .associate { it.itemId to seriesDao.getEpisodeById(it.itemId) }
                        val seriesIds = episodes.values.filterNotNull()
                            .map { it.seriesId }.distinct()
                        val series = if (seriesIds.isEmpty()) emptyMap()
                        else seriesDao.getSeriesByIds(seriesIds).associateBy { it.id }
                        val isKids = profile?.isKids == true
                        list.filterNot { d ->
                            when (d.mediaType) {
                                MediaType.MOVIE -> movies[d.itemId]?.let { movie ->
                                    CustomizeKeys.movie(movie) in custMovie.hiddenItems || custMovie.sealsItemIn(movie.categoryId) ||
                                        (isKids && AdultCategoryClassifier.isAdult(movie.categoryId?.let { categoryDao.getById(it)?.name }))
                                } ?: (isKids || custMovie.sealsItem(ItemCategory.Unresolved, anySealed))
                                MediaType.EPISODE -> episodes[d.itemId]
                                    ?.let { series[it.seriesId] }
                                    ?.let { s ->
                                        CustomizeKeys.series(s) in custSeries.hiddenItems || custSeries.sealsItemIn(s.categoryId) ||
                                            (isKids && AdultCategoryClassifier.isAdult(s.categoryId?.let { categoryDao.getById(it)?.name }))
                                    } ?: (isKids || custSeries.sealsItem(ItemCategory.Unresolved, anySealed))
                                else -> false
                            }
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Paths of finished items whose file is gone (drive formatted, swapped or unplugged). */
    val missingFiles: StateFlow<Set<String>> = downloads
        .map { list -> list// A paused or failed one has a partial file that can go missing too (a formatted stick).
        .filter { it.status == tv.own.owntv.core.model.DownloadStatus.COMPLETED || (it.status in RESUMABLE && it.downloadedBytes > 0) }.map { it.filePath } }
        .missingFiles(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Free and total space on whichever volume the downloads are being written to.
     *
     * Keyed on the download **root** as well as on the list. Keyed on the list alone, changing the
     * folder left the bar showing the old volume until the app was restarted — and on a screen with
     * nothing downloaded yet the list never changes, so it never refreshed at all.
     */
    val storage: StateFlow<DownloadStorageInfo?> =
        combine(downloads, settings.downloadRoot) { _, _ -> Unit }
            .mapLatest { downloadManager.storageInfo() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The one transfer running now, with its speed — the running row's "12.4 MB/s · 3 min left". */
    val active: StateFlow<DownloadActivityTracker.ActiveDownload?> = tracker.active

    /**
     * What a row shows besides the stored title: the show's name for an episode, 16:9 art, and the parts of
     * "2026 · 1 h 38 min · FHD" / "Season 1 · Episode 3" — the TV's lookup. Once per download (the list
     * re-emits on every progress tick), and absent for a title its playlist no longer has.
     */
    val details: StateFlow<Map<Long, DownloadDetails>> = run {
        val cache = HashMap<Long, DownloadDetails?>()
        downloads.mapLatest { list ->
            list.mapNotNull { d ->
                val found = if (cache.containsKey(d.id)) cache[d.id] else lookUp(d).also { cache[d.id] = it }
                found?.let { d.id to it }
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    private suspend fun lookUp(d: DownloadEntity): DownloadDetails? = when (d.mediaType) {
        MediaType.MOVIE -> if (d.isTvbox()) {
            // A TVBOX row's title already carries "film · episode"; the film row would overwrite
            // that with the bare film name.
            DownloadDetails(title = d.title, artUrl = d.posterUrl, quality = null)
        } else {
            movieDao.getById(d.itemId)?.let { m ->
                DownloadDetails(
                    title = m.name,
                    artUrl = m.backdropUrl ?: m.posterUrl,
                    year = m.year ?: m.parsedYear,
                    runtimeSecs = m.durationSecs,
                    quality = ProviderVariantParser.displaySignals(m.name).quality.label,
                )
            }
        }
        MediaType.EPISODE -> seriesDao.getEpisodeById(d.itemId)?.let { ep ->
            val show = seriesDao.getSeriesById(ep.seriesId)
            DownloadDetails(
                title = show?.name ?: d.title,
                artUrl = show?.backdropUrl ?: show?.posterUrl ?: d.posterUrl,
                runtimeSecs = ep.durationSecs,
                season = ep.seasonNumber,
                episode = ep.episodeNumber,
            )
        }
        else -> null
    }

    /**
     * The volumes downloads can go to without asking for anything: this app's own folder on internal
     * storage, and one on every memory card or stick that is plugged in.
     *
     * On a phone with no card this is a list of one, which is why it is no longer the whole story —
     * "Choose another folder" opens the system picker beside it. A television browses real
     * directories instead, because it holds all-files access; a phone bound for Google Play cannot,
     * so the Storage Access Framework is the only way it reaches a folder of the user's own choosing.
     */
    val volumes: List<StorageAccess.StorageRoot> = StorageAccess.appRoots(context)

    val downloadRoot: StateFlow<String> = settings.downloadRoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * The chosen folder as something to read — `Films/OwnTV` — or null when downloads are going to
     * one of the volumes above and the row for it is already ticked.
     */
    val chosenFolder: StateFlow<String?> = settings.downloadRoot
        .map { root -> root.takeIf { MediaTarget.isDocument(it) }?.let(StorageAccess::folderLabel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * True when downloads are pointed at a folder the app can no longer write to: the user withdrew
     * the permission in system settings, or the storage it was on came out.
     *
     * Worth saying out loud rather than leaving to the first failed download, because from the
     * outside the two look identical and only one of them is fixable by pressing Retry.
     */
    val folderLost: StateFlow<Boolean> = settings.downloadRoot
        .map { root -> MediaTarget.isDocument(root) && !StorageAccess.hasTree(context, root) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setDownloadRoot(path: String) {
        viewModelScope.launch {
            releasePreviousTree(path)
            settings.setDownloadRoot(path)
        }
    }

    /**
     * Remember the folder the user just picked in the system picker.
     *
     * The grant has to be made **persistable** first: a tree URI is granted to the activity that
     * asked and lasts only as long as the process, while downloads outlive both — a transfer runs in
     * a foreground service and a recording can start days later. If the system refuses to persist
     * it, the folder is not adopted at all rather than adopted and silently unusable tomorrow.
     */
    fun setDownloadTree(tree: Uri) {
        viewModelScope.launch {
            if (!StorageAccess.persistAccess(context, tree)) return@launch
            val stored = tree.toString()
            releasePreviousTree(stored)
            settings.setDownloadRoot(stored)
            withContext(Dispatchers.IO) { MediaRoot.of(context, stored).ensureFolders() }
        }
    }

    /** Hand back the grant on a folder that is no longer the download folder. */
    private suspend fun releasePreviousTree(replacement: String) {
        val previous = settings.downloadRoot.first()
        if (previous.isNotBlank() && previous != replacement) {
            StorageAccess.releaseTree(context, previous)
        }
    }

    /**
     * Whether the queue waits for Wi-Fi. It sits with the folder because the two are the only
     * choices this screen has, and a user who opens them is asking one question: where these go and
     * what they cost.
     */
    /** What playing does to a running download on a one-stream playlist (the options sheet's row). */
    val downloadWatchChoice: StateFlow<SettingsRepository.DownloadWatchChoice> = settings.downloadWatchChoice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DownloadWatchChoice.ASK)

    fun setDownloadWatchChoice(choice: SettingsRepository.DownloadWatchChoice) {
        viewModelScope.launch { settings.setDownloadWatchChoice(choice) }
    }

    val wifiOnly: StateFlow<Boolean> = settings.downloadsWifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setWifiOnly(on: Boolean) {
        viewModelScope.launch { settings.setDownloadsWifiOnly(on) }
    }

    /** Put a saved file's full path on the clipboard — see the Location row in the download menu. */
    fun copyPath(path: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(path, path))
    }

    private val _playing = MutableStateFlow(false)
    /** True once a download has actually started playing, so the screen knows to open the player. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun play(download: DownloadEntity) {
        val path = download.filePath ?: return
        viewModelScope.launch {
            _playing.value = if (download.isTvbox()) {
                vodTuner.playTvboxDownload(download)
            } else {
                vodTuner.playDownload(
                    mediaType = download.mediaType,
                    itemId = download.itemId,
                    filePath = path,
                    title = download.title,
                    posterUrl = download.posterUrl,
                )
            }
        }
    }

    fun playerOpened() {
        _playing.value = false
    }

    /**
     * **Move** a finished download to wherever the user picked in the system's own save dialog. The
     * app's own folder is invisible to a file manager, so this is how a saved film leaves the app.
     *
     * It used to be a copy, and a copy left the user with two of everything: the row still pointed
     * at the app's hidden copy, so deleting the download in OwnTV freed nothing the user could see
     * and left the exported file behind with nothing tracking it. Now the row **follows the file** —
     * the list, the location row and playback all point at the copy the user chose to keep.
     *
     * The order matters and is deliberate: copy, then take a lasting grant, then re-point the row,
     * and only then delete the original. If the grant is refused the move is **abandoned as a copy**
     * — the row keeps pointing at a file that definitely plays, rather than at one that would stop
     * working the next time the app starts.
     */
    fun export(download: DownloadEntity, target: Uri) {
        val source = MediaTarget.of(context, download.filePath) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val copied = runCatching {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    source.openInput().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (!copied) return@launch
            if (!StorageAccess.persistAccess(context, target)) return@launch
            downloadDao.upsert(
                download.copy(filePath = target.toString(), updatedAt = System.currentTimeMillis()),
            )
            source.delete()
        }
    }

    fun retry(download: DownloadEntity) = downloadManager.retry(download)
    fun pause(download: DownloadEntity) = downloadManager.pause(download)
    fun resume(download: DownloadEntity) = downloadManager.resume(download)
    fun delete(download: DownloadEntity) = downloadManager.delete(download)

}

/**
 * A TVBOX row stores a `tvbox://` identity handle, not a URL. The App owns this classification;
 * Core deliberately does not know what TVBOX is.
 */
private fun DownloadEntity.isTvbox(): Boolean =
    streamUrl.startsWith(tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper.STREAM_PREFIX)

/** See [DownloadsViewModel.details]. */
data class DownloadDetails(
    val title: String,
    val artUrl: String?,
    val year: Int? = null,
    val runtimeSecs: Int? = null,
    val quality: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** Stopped downloads that keep a partial file on the drive. */
private val RESUMABLE = setOf(tv.own.owntv.core.model.DownloadStatus.PAUSED, tv.own.owntv.core.model.DownloadStatus.FAILED)
