package tv.own.owntv.mobile.ui.screens.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.ItemCategory
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.ExternalPlayerLauncher
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.subtitles.SubtitleController
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.cast.CastHandoff
import tv.own.owntv.mobile.cast.CastRequest
import tv.own.owntv.mobile.playback.DataSaverGate
import tv.own.owntv.mobile.tvbox.download.TvboxDownloadKey
import tv.own.owntv.mobile.tvbox.play.TvboxExtKey
import tv.own.owntv.mobile.playback.PlaybackService
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.MpvPlaybackEngine
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackSession
import tv.own.owntv.player.PlaylistItem

/**
 * The `itemId` a playing recording carries.
 *
 * A recording has no catalogue row, and [VodPlayback.itemId] is not nullable — so it gets a sentinel
 * rather than a real channel id. A real one would make the mini-player's favourite and "open detail"
 * affordances act on the channel the recording came from, which is not what is on screen.
 */
const val RECORDING_ITEM_ID = -1L

/**
 * The `itemId` a TVbox stream carries when indexing failed.
 *
 * An indexed TVbox item plays under its real Core `movieId` (plan P2): progress, history and
 * favourites all key off it. Only when no Core row could be written does the stream fall back to
 * this sentinel — it still plays, but no resume position is kept, and the player screen hides the
 * favourite entry, because `itemId = -2` must never reach the Core tables.
 */
const val TVBOX_ITEM_ID = -2L

/** A film or an episode, playing. [mediaType] and [itemId] are what the resume position is written for. */
data class VodPlayback(
    val mediaType: MediaType,
    val itemId: Long,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    /** The show an episode belongs to. An episode is favourited by its series, never on its own. */
    val seriesId: Long? = null,
    /**
     * Which playlist this is streaming from, or null for a file already downloaded to the phone.
     *
     * Only used to tell core's `WatchSession` that the playlist is in use, so its background
     * catalogue drain steps aside. A local file spends no provider connection, so it holds no
     * session. Last in the list because every other caller passes these positionally.
     */
    val sourceId: Long? = null,
    /** The category of the film, the show or the recording's channel: what a category lock checks. */
    val categoryId: Long? = null,
    /** A TVbox stream: no subtitle interface exists for it, whatever its [itemId] says (plan §4.7). */
    val isExternal: Boolean = false,
)

/**
 * The VOD half of what is playing — [LiveTuner]'s twin, and for the same reason: a film outlives the
 * screen that started it, so the full screen player and the mini player are two views of one stream.
 *
 * There is one engine and one surface, so the two tuners cannot both be playing. This one holds the
 * live tuner and stops it before it starts, and clears itself when the live tuner starts something —
 * one direction only, which is what keeps the pair from chasing each other.
 *
 * Resume positions are written on a timer as well as at the end, because a phone's way of leaving a
 * film is to be taken away by a phone call, not to press Stop.
 */
class VodTuner(
    private val context: Context,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val historyDao: HistoryDao,
    private val progressDao: ProgressDao,
    private val settings: SettingsRepository,
    private val streamUrlResolver: StreamUrlResolver,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
    private val subtitleController: SubtitleController,
    private val metadata: MetadataRepository,
    private val session: PlaybackSession,
    private val liveTuner: LiveTuner,
    private val dataSaver: DataSaverGate,
    private val cast: CastController,
    val player: OwnTVPlayer,
    /** Lets core's background catalogue drain know a playlist is in use. */
    private val watchSession: tv.own.owntv.core.live.WatchSession,
    /** Category locks: nothing in a category the PIN has not opened plays. */
    private val customize: CustomizationStore,
    /** A TVBOX Core row reached through a Core screen: resolves the real stream before playing. */
    private val tvboxPlayback: tv.own.owntv.mobile.tvbox.play.TvboxPlaybackRouter,
) : CastHandoff {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val engine by lazy { MpvPlaybackEngine(player) }

    private val _playing = MutableStateFlow<VodPlayback?>(null)

    /** The film or episode on screen, or null when nothing of ours is playing. */
    val playing: StateFlow<VodPlayback?> = _playing

    init {
        // The VOD twin of LiveTuner's watch session — same reason, same shape. A downloaded file
        // carries no sourceId and holds no session: it spends no provider connection.
        scope.launch {
            var held: Long? = null
            playing.collect { item ->
                val next = item?.sourceId
                if (next != held) {
                    held?.let { watchSession.close(it) }
                    next?.let { watchSession.open(it) }
                    held = next
                }
            }
        }
    }


    /** The profile the current stream was started for — a mid-film profile switch must not write its
     *  resume position into the new profile's list. */
    private var playingProfileId = -1L

    /** Which side the current stream belongs to. In the TVBOX state [saveProgress] writes Core
     *  progress with a TVBOX `extKey` when the stream has a Core id; the engine/subtitle branches
     *  and the relock guard key off this too. In the CORE state the TVbox identity fields are
     *  cleared. Switching through [beginPlay] is the one place the switch happens (plan R4). */
    private enum class PlayKind { CORE, TVBOX }

    @Volatile
    private var playKind = PlayKind.CORE

    /** The TVbox identity fields, cleared on every CORE play (see [beginPlay]). */
    private fun beginPlay(kind: PlayKind) {
        playKind = kind
        if (kind == PlayKind.CORE) {
            tvboxSiteKey = null
            tvboxCategoryKey = null
            tvboxProgressCtx = null
            tvboxQueueCtxs = null
        }
        lastSavedPositionMs = -1L
    }

    /** The TVbox site and category of the current stream, for the relock guard (plan §5.5.8). */
    private var tvboxSiteKey: String? = null
    private var tvboxCategoryKey: tv.own.owntv.mobile.tvbox.play.TvboxCategoryKey? = null

    /** Where the current TVbox stream's progress is written (which episode of which vod). */
    private var tvboxProgressCtx: TvboxProgressCtx? = null

    /** Per-episode progress contexts of the playing TVbox queue, indexed like [player]'s queue. */
    private var tvboxQueueCtxs: List<TvboxProgressCtx>? = null

    /** The position the last [saveProgress] wrote; equal position means nothing new to persist. */
    @Volatile
    private var lastSavedPositionMs = -1L

    /** Identity of a playing TVbox episode, for the Core progress row's `extKey`. */
    data class TvboxProgressCtx(
        val episodeFlag: String,
        val episodeId: String,
        val episodeIndex: Int,
    )

    /** What the relock guard needs to decide whether a TVbox stream must stop now. Null when
     *  nothing TVbox-side is playing (or the stream has no category context, which cannot happen
     *  through [playTvbox]). */
    fun tvboxPlayingContext(): Pair<String, tv.own.owntv.mobile.tvbox.play.TvboxCategoryKey>? =
        if (playKind == PlayKind.TVBOX) tvboxSiteKey?.let { site -> tvboxCategoryKey?.let { site to it } } else null

    /** The season queue handed to the player, kept so a player-driven advance can be followed. */
    private data class PlayingQueue(
        val show: SeriesEntity,
        val episodes: List<EpisodeEntity>,
        val profileId: Long,
        val parentTmdbId: Long?,
    )

    private var playingQueue: PlayingQueue? = null

    init {
        scope.launch {
            liveTuner.channel.collect { if (it != null) clearPlaying() }
        }
        // The player advances a queue by itself (auto-next, and the HUD's prev/next), so everything
        // keyed to "the episode playing" — the title on screen, the resume position, the subtitle
        // search context — has to be re-pointed by whoever owns it. Nobody tells this class otherwise.
        scope.launch {
            player.queueItemChanged.collect { index ->
                // A TVbox queue advancing by itself re-points the progress context, so the five-second
                // timer keeps writing under the episode actually playing (same rule as Core episodes).
                if (playKind == PlayKind.TVBOX) {
                    tvboxProgressCtx = tvboxQueueCtxs?.getOrNull(index) ?: tvboxProgressCtx
                }
                val q = playingQueue ?: return@collect
                val episode = q.episodes.getOrNull(index) ?: return@collect
                // Deliberately no saveProgress() here: by the time this arrives the player is already
                // on the new episode at position ~0, so a write would stamp that over the finished
                // one's real position. The five-second timer saved it while it was still playing.
                _playing.value = VodPlayback(
                    MediaType.EPISODE,
                    episode.id,
                    q.show.name,
                    episodeLabel(episode),
                    q.show.posterUrl,
                    q.show.id,
                    q.show.sourceId,
                    q.show.categoryId,
                )
                playingProfileId = q.profileId
                subtitleController.setEpisode(q.profileId, q.show, episode, q.parentTmdbId)
                recordHistory(q.profileId, MediaType.EPISODE, episode.id)
            }
        }
        // The five-second resume-position saver, alive only while something is playing. It used
        // to tick forever from here, waking every 5 s for the life of the process even with
        // nothing on screen; saveProgress() itself already no-ops on null, this just stops the
        // wakeups too.
        var saver: Job? = null
        scope.launch {
            playing.collect { item ->
                saver?.cancel()
                saver = item?.let {
                    scope.launch {
                        while (true) {
                            delay(PROGRESS_INTERVAL_MS)
                            saveProgress()
                        }
                    }
                }
            }
        }
    }

    /**
     * Play a film. False means nothing opened here — it went to an external player, or the item could
     * not be resolved — so the caller must not navigate to the player screen.
     */
    suspend fun playMovie(movieId: Long, startPositionMs: Long = 0): Boolean {
        val movie = withContext(Dispatchers.IO) { movieDao.getById(movieId) } ?: return false
        val pid = currentProfileId() ?: return false
        if (!allows(pid, movie.categoryId)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(movie.sourceId) }

        // An indexed VOD row's streamUrl is only a `tvbox://` handle. It must be resolved back to a
        // real stream BEFORE the external-player check: handing the handle over would open a dead
        // address in VLC instead of playing anything. This is the single route every Core screen
        // (Favourites, Continue watching, search, detail) reaches playback through.
        if (source?.type == SourceType.TVBOX) {
            if (!dataSaver.allowsStreaming()) return false
            val prepared = tvboxPlayback.prepare(movie, pid) ?: return false
            saveProgress()
            liveTuner.stop()
            return playTvbox(
                resolved = prepared.play,
                queue = prepared.queue,
                startPositionMs = startPositionMs.takeIf { it > 0 } ?: prepared.positionMs,
                progressCtx = TvboxProgressCtx(
                    episodeFlag = prepared.episodeFlag,
                    episodeId = prepared.episodeId,
                    episodeIndex = prepared.episodeIndex,
                ),
                movieId = movie.id,
            )
        }

        // A protected item cannot go to an external player: no intent extra carries a licence
        // URL, so the other app would open it and fail on the first segment.
        if (settings.externalPlayerMovies.first() && movie.drmConfig == null) {
            return handOver(movie.name, source, movie.streamUrl, movie.httpHeaders, pid, MediaType.MOVIE, movie.id, startPositionMs)
        }

        if (!dataSaver.allowsStreaming()) return false
        val url = resolve(source, movie.streamUrl) ?: return false
        saveProgress()
        liveTuner.stop()
        val handedOver = cast.offer(
            this,
            CastRequest(
                url = url,
                title = movie.name,
                logoUrl = movie.posterUrl,
                isLive = false,
                startPositionMs = startPositionMs,
                httpHeaders = SourceOverrides.headersWithReferer(movie.httpHeaders, source),
                drm = movie.drmConfig != null,
            ),
        )
        if (!handedOver) {
            player.play(
                url = url,
                title = movie.name,
                year = movie.year?.toString(),
                isLive = false,
                startPositionMs = startPositionMs,
                userAgent = source?.userAgent,
                httpHeaders = SourceOverrides.headersWithReferer(movie.httpHeaders, source),
                drmConfig = movie.drmConfig,
                manifestType = movie.manifestType,
                contentKey = enginePinKey(movie.sourceId, "MOVIE", movie.remoteId),
                reconnectProvider = reconnectFor(source, movie.streamUrl),
                // Slim: films and series always start on mpv and ignore the playlist's override —
                // both settings rows are gone from this app.
                vodEngineOverride = EnginePreference.MPV_FIRST,
            )
        }
        began(pid, VodPlayback(MediaType.MOVIE, movie.id, movie.name, posterUrl = movie.posterUrl, sourceId = movie.sourceId, categoryId = movie.categoryId), handedOver)
        playingQueue = null
        // Turns on the player's "Add subtitles" search for this film. The TMDB id is a precision
        // boost when the metadata cache has one, never a requirement.
        subtitleController.setMovie(pid, movie, runCatching { metadata.resolveMovie(movie)?.tmdbId?.toLong() }.getOrNull())
        return true
    }

    /**
     * Play one episode — and queue the rest of the series behind it, so the player's Next/Previous
     * and its automatic advance at the end of an episode have somewhere to go. Watching a series is
     * watching episode after episode; stopping dead at the end of each one is not the same feature.
     */
    suspend fun playEpisode(episodeId: Long, startPositionMs: Long = 0): Boolean {
        val episode = withContext(Dispatchers.IO) { seriesDao.getEpisodeById(episodeId) } ?: return false
        val show = withContext(Dispatchers.IO) { seriesDao.getSeriesById(episode.seriesId) } ?: return false
        val pid = currentProfileId() ?: return false
        if (!allows(pid, show.categoryId)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(show.sourceId) }
        val title = show.name
        val subtitle = episodeLabel(episode)

        if (settings.externalPlayerSeries.first() && episode.drmConfig == null) {
            return handOver(title, source, episode.streamUrl, episode.httpHeaders, pid, MediaType.EPISODE, episode.id, startPositionMs)
        }

        if (!dataSaver.allowsStreaming()) return false
        saveProgress()
        liveTuner.stop()

        val episodes = withContext(Dispatchers.IO) { seriesDao.episodesBySeriesOnce(show.id) }
            .sortedWith(tv.own.owntv.core.content.EpisodeOrder.episodes)
            .ifEmpty { listOf(episode) }
        val startIndex = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val needsResolve = streamUrlResolver.needsResolve(source)

        // The receiver takes one item, not the season. Auto-advance to the next episode is one of the
        // things casting costs, along with the fallback ladder and the subtitle rendering — there is
        // no queue on a default receiver to hand the rest of the series to.
        val castUrl = resolve(source, episode.streamUrl)
        val handedOver = castUrl != null && cast.offer(
            this,
            CastRequest(
                url = castUrl,
                title = title,
                subtitle = subtitle,
                logoUrl = show.posterUrl,
                isLive = false,
                startPositionMs = startPositionMs,
                httpHeaders = SourceOverrides.headersWithReferer(episode.httpHeaders, source),
                drm = episode.drmConfig != null,
            ),
        )
        if (!handedOver) player.playEpisodes(
            items = episodes.map { ep ->
                PlaylistItem(
                    url = ep.streamUrl,
                    meta = MediaMeta(
                        title = title,
                        subtitle = episodeLabel(ep),
                        logoUrl = show.posterUrl,
                        contentKey = enginePinKey(show.sourceId, "EPISODE", ep.remoteId),
                        // v44 — audio/subtitle choices are remembered per series.
                        trackKey = enginePinKey(show.sourceId, "SERIES", show.remoteId),
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                    ),
                    // Stalker mints a link per episode, and it is short-lived — so it is minted as
                    // that episode loads, not once for the whole queue.
                    resolveUrl = if (needsResolve && source != null) {
                        { streamUrlResolver.resolve(source, ep.streamUrl, vod = true, episode = ep.episodeNumber) }
                    } else {
                        null
                    },
                    httpHeaders = SourceOverrides.headersWithReferer(ep.httpHeaders, source),
                    drmConfig = ep.drmConfig,
                    manifestType = ep.manifestType,
                )
            },
            startIndex = startIndex,
            startPositionMs = startPositionMs,
            userAgent = source?.userAgent,
            // Slim: same fixed MPV_FIRST default as play(movie) above.
            vodEngineOverride = EnginePreference.MPV_FIRST,
        )
        began(pid, VodPlayback(MediaType.EPISODE, episode.id, title, subtitle, show.posterUrl, show.id, show.sourceId, show.categoryId), handedOver)
        val parentTmdbId = runCatching { metadata.resolveSeries(show)?.tmdbId?.toLong() }.getOrNull()
        subtitleController.setEpisode(pid, show, episode, parentTmdbId)
        // Nothing follows a cast episode, so there is no queue to follow either — and a queue kept
        // here would have the player's advance callback re-pointing a stream it is not driving.
        playingQueue = if (handedOver) null else PlayingQueue(show, episodes, pid, parentTmdbId)
        return true
    }

    /**
     * Play a file that is already on this phone.
     *
     * Offline is the whole point of a download, so nothing here asks the provider anything: no URL to
     * resolve, no headers, no reconnect. The resume position is still the film's own, so one started
     * over the network carries on from where it stopped.
     */
    suspend fun playDownload(
        mediaType: MediaType,
        itemId: Long,
        filePath: String,
        title: String,
        posterUrl: String?,
    ): Boolean {
        val pid = currentProfileId() ?: return false
        val category = downloadCategory(mediaType, itemId)
        if (!downloadAllowed(category, pid)) return false
        if (settings.externalPlayerFor(mediaType).first()) {
            externalPlayerLauncher.launch(filePath, title)
            return false
        }
        saveProgress()
        liveTuner.stop()
        // A file on this phone is not reachable from a receiver — it would be asked for a path that
        // exists on the handset and nowhere else — so starting a download ends the cast rather than
        // leaving the television on the previous item.
        cast.release(this)
        val resume = withContext(Dispatchers.IO) { progressDao.get(pid, mediaType, itemId) }
        player.play(
            url = filePath,
            title = title,
            isLive = false,
            startPositionMs = resume?.positionMs ?: 0L,
        )
        began(pid, VodPlayback(mediaType, itemId, title, posterUrl = posterUrl, categoryId = (category as? ItemCategory.Found)?.id))
        playingQueue = null
        setDownloadSubtitleContext(pid, mediaType, itemId, filePath)
        return true
    }

    /**
     * Play a finished TVBOX download from this phone.
     *
     * Shares [playDownload]'s category/lock gate and external-player route, but keeps the TVBOX
     * progress identity (`extKey`) so the next online play resumes on the same line/episode. The
     * resume position is used only when it belongs to this episode: one `(profile, MOVIE, movieId)`
     * row serves every episode of the film.
     *
     * A local file is not an "external" TVBOX stream: `isExternal = true` would close the subtitle
     * entry (`PlayerScreen.kt:268`), and a downloaded file does have a subtitle interface.
     */
    suspend fun playTvboxDownload(download: DownloadEntity): Boolean {
        val pid = currentProfileId() ?: return false
        val path = download.filePath ?: return false
        val ref = TvboxDownloadKey.decode(download.extJson) ?: return false
        val category = downloadCategory(MediaType.MOVIE, download.itemId)
        if (!downloadAllowed(category, pid)) return false
        if (settings.externalPlayerFor(MediaType.MOVIE).first()) {
            externalPlayerLauncher.launch(path, download.title)
            return false
        }
        saveProgress()
        liveTuner.stop()
        cast.release(this)
        val saved = withContext(Dispatchers.IO) {
            progressDao.get(pid, MediaType.MOVIE, download.itemId)
        }
        val sameEpisode = saved != null && TvboxExtKey.decode(saved.extKey)?.let {
            it.flag == ref.flag && it.episodeId == ref.episodeId && it.index == ref.index
        } == true
        val startMs = if (sameEpisode && saved != null) saved.positionMs else 0L

        beginPlay(PlayKind.TVBOX)
        tvboxSiteKey = ref.siteKey
        // An offline file has no site/type context to re-lock mid-play; the category gate above is
        // the profile-level check, and the in-app detail page already enforced the lock.
        tvboxCategoryKey = null
        tvboxProgressCtx = TvboxProgressCtx(
            episodeFlag = ref.flag,
            episodeId = ref.episodeId,
            episodeIndex = ref.index,
        )
        player.play(
            url = path,
            title = download.title,
            subtitle = ref.episodeName.takeIf { it.isNotBlank() },
            isLive = false,
            startPositionMs = startMs,
            contentKey = "tvbox:${ref.siteKey}:${ref.vodId}:${ref.index}",
        )
        player.exitAudioOnly()
        session.attach(engine)
        PlaybackService.start(context)
        playingProfileId = pid
        _playing.value = VodPlayback(
            mediaType = MediaType.MOVIE,
            itemId = download.itemId,
            title = download.title,
            subtitle = ref.episodeName.takeIf { it.isNotBlank() },
            posterUrl = download.posterUrl,
            isExternal = false,
        )
        playingQueue = null
        recordHistory(pid, MediaType.MOVIE, download.itemId)
        setDownloadSubtitleContext(pid, MediaType.MOVIE, download.itemId, path)
        return true
    }

    /**
     * Play a finished live recording from the file on disk.
     *
     * Close to [playDownload] but deliberately thinner in three ways, all because a recording is not
     * a catalogue item:
     *
     * - **No watch history and no resume position.** Those are keyed by `(mediaType, itemId)`, and a
     *   recording's ids belong to the *channel* it came from. Writing them would put last night's
     *   programme into the history as though the channel itself had been watched, and would give the
     *   channel a resume position into a file.
     * - **No subtitle context.** OpenSubtitles matches films and episodes; a Tuesday news bulletin is
     *   neither, and the stream's own subtitle tracks are inside the `.ts` anyway.
     * - **`isLive = false`**, even though what it holds is live television. The flag describes the
     *   source, and a file on disk seeks, pauses and ends — treating it as live would take away the
     *   scrub bar on the one recording a user most wants to skip through.
     */
    suspend fun playRecording(filePath: String, title: String, posterUrl: String?, categoryId: Long? = null): Boolean {
        val pid = currentProfileId() ?: return false
        if (settings.externalPlayerFor(MediaType.LIVE).first()) {
            externalPlayerLauncher.launch(filePath, title)
            return false
        }
        saveProgress()
        liveTuner.stop()
        // A file on this phone is not reachable from a receiver, so starting one ends the cast rather
        // than leaving the television on whatever was playing before.
        cast.release(this)
        player.play(url = filePath, title = title, isLive = false)
        player.exitAudioOnly()
        session.attach(engine)
        PlaybackService.start(context)
        playingProfileId = pid
        // Not began(): a recording has no catalogue row. Core-table writes end here regardless —
        // the play kind marks the next Core-side stream, same as every other non-TVbox play.
        beginPlay(PlayKind.CORE)
        _playing.value = VodPlayback(MediaType.LIVE, RECORDING_ITEM_ID, title, posterUrl = posterUrl, categoryId = categoryId)
        playingQueue = null
        return true
    }

    /**
     * Play a resolved TVbox stream (plan §7 step 17, P2 §4.7).
     *
     * The [tv.own.owntv.mobile.tvbox.play.TvboxPlayResolver] has already done the parsing and the
     * lock/kids checks; this is pure hand-off to the shared engine, following the [playRecording]
     * skeleton for housekeeping. With a [movieId] (the indexed Core row) the stream writes Core
     * progress/history under the real profile, so Continue watching and the history page see it;
     * without one it degrades to the sentinel id: it plays, but nothing is persisted.
     *
     * A multi-episode queue hands [queue] over with per-item headers (decision C5: never share the
     * first episode's headers with the rest); a single stream passes null and plays [resolved] directly.
     */
    suspend fun playTvbox(
        resolved: tv.own.owntv.mobile.tvbox.play.ResolvedPlay,
        queue: List<PlaylistItem>?,
        startPositionMs: Long = 0,
        progressCtx: TvboxProgressCtx,
        movieId: Long? = null,
    ): Boolean {
        if (!dataSaver.allowsStreaming()) return false
        saveProgress()
        liveTuner.stop()
        // A TVbox stream is not reachable from a receiver either — same reasoning as a local file:
        // starting one ends the cast rather than leaving the television on the previous item.
        cast.release(this)

        beginPlay(PlayKind.TVBOX)
        tvboxSiteKey = resolved.siteKey
        tvboxCategoryKey = resolved.categoryKey
        tvboxProgressCtx = progressCtx
        tvboxQueueCtxs = queue?.mapIndexed { i, _ ->
            progressCtx.copy(episodeIndex = i)
        }

        val httpHeaders = tv.own.owntv.core.network.StreamHeaders.encode(resolved.headers)
        if (queue != null && queue.size > 1) {
            player.playEpisodes(
                items = queue,
                startIndex = resolved.episodeIndex.coerceIn(0, queue.size - 1),
                startPositionMs = startPositionMs,
                userAgent = resolved.userAgent,
                vodEngineOverride = EnginePreference.MPV_FIRST,
            )
        } else {
            player.play(
                url = resolved.url,
                title = resolved.title,
                subtitle = resolved.subtitle,
                logoUrl = resolved.posterUrl,
                isLive = false,
                startPositionMs = startPositionMs,
                userAgent = resolved.userAgent,
                httpHeaders = httpHeaders,
                contentKey = "tvbox:${resolved.siteKey}:${resolved.vodId}",
                vodEngineOverride = EnginePreference.MPV_FIRST,
            )
        }
        player.exitAudioOnly()
        session.attach(engine)
        PlaybackService.start(context)
        val pid = currentProfileId()
        val coreId = movieId?.takeIf { it > 0 && pid != null }
        // The real profile: the five-second saver writes Core progress for the indexed stream.
        // Only the unindexed fallback keeps -1, which short-circuits the Core write.
        playingProfileId = if (coreId != null) pid ?: -1L else -1L
        _playing.value = VodPlayback(
            mediaType = MediaType.MOVIE,
            itemId = coreId ?: TVBOX_ITEM_ID,
            title = resolved.title,
            subtitle = resolved.subtitle,
            posterUrl = resolved.posterUrl,
            // The one-stream guard and DownloadWatchGuard read WatchSession, which is keyed by
            // VodPlayback.sourceId. Without this a TVBOX stream held no "source" at all.
            sourceId = coreId?.let { withContext(Dispatchers.IO) { movieDao.getById(it)?.sourceId } },
            isExternal = true,
        )
        playingQueue = null
        if (coreId != null && pid != null) recordHistory(pid, MediaType.MOVIE, coreId)
        return true
    }

    /**
     * A downloaded film or episode can search for subtitles too — and better than a stream can: the
     * file is on disk, so OpenSubtitles can be matched on the file's own hash rather than on its name.
     */
    private suspend fun setDownloadSubtitleContext(pid: Long, mediaType: MediaType, itemId: Long, filePath: String) {
        runCatching {
            if (mediaType == MediaType.MOVIE) {
                val movie = withContext(Dispatchers.IO) { movieDao.getById(itemId) } ?: return
                subtitleController.setMovie(pid, movie, metadata.resolveMovie(movie)?.tmdbId?.toLong(), filePath)
            } else {
                val ep = withContext(Dispatchers.IO) { seriesDao.getEpisodeById(itemId) } ?: return
                val show = withContext(Dispatchers.IO) { seriesDao.getSeriesById(ep.seriesId) } ?: return
                subtitleController.setEpisode(pid, show, ep, metadata.resolveSeries(show)?.tmdbId?.toLong(), filePath)
            }
        }
    }

    /**
     * A kids profile must not reach an adult title just because its file is already on disk. A
     * download whose playlist has since been deleted cannot be classified at all, so a kids profile
     * is refused it — the same call the television makes.
     */
    /** Where a downloaded film or episode is filed: its film's or show's category, or unresolved once the playlist no longer has it. */
    private suspend fun downloadCategory(mediaType: MediaType, itemId: Long): ItemCategory = withContext(Dispatchers.IO) {
        when (mediaType) {
            MediaType.MOVIE -> movieDao.getById(itemId)?.let { ItemCategory.Found(it.categoryId) } ?: ItemCategory.Unresolved
            else -> seriesDao.getEpisodeById(itemId)
                ?.let { seriesDao.getSeriesById(it.seriesId) }
                ?.let { ItemCategory.Found(it.categoryId) } ?: ItemCategory.Unresolved
        }
    }

    /**
     * A download plays unless the profile may not see its category, or a lock keeps that category shut.
     * One whose film or show is gone has no category to check: it plays, except while the profile has a
     * lock that is not open, since it may have come from that category.
     */
    private suspend fun downloadAllowed(category: ItemCategory, profileId: Long): Boolean =
        AdultCategoryClassifier.allows(profileId, (category as? ItemCategory.Found)?.id, profileDao, categoryDao) &&
            !customize.sealsItem(profileId, category)

    /** What the profile may play from [categoryId]: not an adult one on a kids profile, nor a locked one the PIN has not opened. */
    private suspend fun allows(profileId: Long, categoryId: Long?): Boolean =
        AdultCategoryClassifier.allows(profileId, categoryId, profileDao, categoryDao) && !customize.sealsCategory(profileId, categoryId)

    /** Hand the item to VLC, MX Player or whatever else is installed — the long-press action, and the
     *  "External player" setting's route. History is recorded and the saved resume point handed over;
     *  where the other app stops cannot be read back. */
    suspend fun playExternal(movie: MovieEntity): Boolean {
        val pid = currentProfileId() ?: return false
        // An indexed VOD row has no address another app could open: play it here instead of handing
        // a tvbox:// handle to an external player that would only report a broken link.
        if (movie.streamUrl.startsWith(tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper.STREAM_PREFIX)) {
            return playMovie(movie.id, withContext(Dispatchers.IO) { progressDao.get(pid, MediaType.MOVIE, movie.id)?.positionMs ?: 0L })
        }
        if (!allows(pid, movie.categoryId)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(movie.sourceId) }
        return handOver(movie.name, source, movie.streamUrl, movie.httpHeaders, pid, MediaType.MOVIE, movie.id, savedPositionMs(pid, MediaType.MOVIE, movie.id))
    }

    /** [playExternal] for an episode of [show]. */
    suspend fun playExternal(show: SeriesEntity, episode: EpisodeEntity): Boolean {
        val pid = currentProfileId() ?: return false
        if (!allows(pid, show.categoryId)) return false
        val source = withContext(Dispatchers.IO) { sourceDao.getById(show.sourceId) }
        return handOver(show.name, source, episode.streamUrl, episode.httpHeaders, pid, MediaType.EPISODE, episode.id, savedPositionMs(pid, MediaType.EPISODE, episode.id))
    }

    private suspend fun savedPositionMs(profileId: Long, mediaType: MediaType, itemId: Long): Long =
        withContext(Dispatchers.IO) { progressDao.get(profileId, mediaType, itemId)?.positionMs ?: 0L }

    /** Stop playing altogether — the mini player's close button, and nothing else. */
    fun stop() {
        scope.launch {
            saveProgress()
            clearPlaying()
            cast.release(this@VodTuner)
            session.attach(null)
            PlaybackService.stop(context)
            player.stop()
        }
    }

    /** The television is taking the film; the phone keeps only where it had got to. */
    override fun releaseToCast(): Long {
        val position = player.position.value
        session.attach(null)
        player.stop()
        return position
    }

    /**
     * The cast ended, so the film comes back here — at the position the receiver had reached, which
     * is the whole point of the handover being a handover rather than a restart.
     */
    override fun resumeFromCast(positionMs: Long) {
        val current = _playing.value ?: return
        scope.launch {
            when (current.mediaType) {
                MediaType.EPISODE -> playEpisode(current.itemId, positionMs)
                else -> playMovie(current.itemId, positionMs)
            }
        }
    }

    /** Write the resume position now — on the timer, when another item starts, and when the user stops. */
    suspend fun saveProgress() {
        val current = _playing.value ?: return
        // Whichever engine actually has the film. Casting for an hour and coming back to a resume
        // position of zero would be the same bug as never writing one at all.
        val remote = cast.engine.value
        val position = remote?.position?.value ?: player.position.value
        val duration = remote?.duration?.value ?: player.duration.value
        if (position <= 0 || duration <= 0) return
        // keep-open at the end of a stream leaves the position frozen: writing the identical
        // record every five seconds only churns the file (regression §6.9).
        if (position == lastSavedPositionMs) return
        lastSavedPositionMs = position
        // A TVbox stream with a Core row writes the same progress table as every other film —
        // one row per (profile, MOVIE, movieId), the line/episode identity in extKey (plan §4.6).
        // An unindexed stream (sentinel id) plays but keeps no resume position.
        if (playKind == PlayKind.TVBOX) {
            val ctx = tvboxProgressCtx ?: return
            if (current.itemId > 0 && playingProfileId >= 0 && currentProfileId() == playingProfileId) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        progressDao.save(
                            PlaybackProgressEntity(
                                profileId = playingProfileId,
                                mediaType = MediaType.MOVIE,
                                itemId = current.itemId,
                                positionMs = position,
                                durationMs = duration,
                                extKey = tv.own.owntv.mobile.tvbox.play.TvboxExtKey.encode(
                                    flag = ctx.episodeFlag,
                                    episodeId = ctx.episodeId,
                                    index = ctx.episodeIndex,
                                ),
                            ),
                        )
                    }
                }
            }
            return
        }
        if (currentProfileId() != playingProfileId) return
        runCatching {
            withContext(Dispatchers.IO) {
                progressDao.save(
                    PlaybackProgressEntity(
                        profileId = playingProfileId,
                        mediaType = current.mediaType,
                        itemId = current.itemId,
                        positionMs = position,
                        durationMs = duration,
                    ),
                )
            }
        }
    }

    /** [onCast] = the receiver took it, so none of the local engine's housekeeping applies and the
     *  session is already pointed at the remote one. */
    private suspend fun began(profileId: Long, what: VodPlayback, onCast: Boolean = false) {
        if (!onCast) {
            // A new film starts with its picture on, whatever the last thing playing was doing. The
            // engine holds the sound-only flag across a change of stream, so without this a channel
            // watched without a picture handed the next film the same fate.
            player.exitAudioOnly()
            session.attach(engine)
        }
        PlaybackService.start(context)
        playingProfileId = profileId
        // A Core-side play taking over ends any TVbox stream's Core-table isolation: from here the
        // writes are catalogue-keyed again, exactly what the play kind promises not to redirect.
        beginPlay(PlayKind.CORE)
        _playing.value = what
        recordHistory(profileId, what.mediaType, what.itemId)
    }

    private suspend fun recordHistory(profileId: Long, mediaType: MediaType, itemId: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                historyDao.record(WatchHistoryEntity(profileId = profileId, mediaType = mediaType, itemId = itemId))
            }
        }
    }

    private fun clearPlaying() {
        _playing.value = null
        playingProfileId = -1L
        playingQueue = null
        beginPlay(PlayKind.CORE)
        // The player's ADD SUBTITLES entry exists only for a film or an episode. Left set, it would
        // still be offering to search OpenSubtitles for the film while a channel is playing.
        subtitleController.clear()
    }

    private suspend fun handOver(
        title: String,
        source: SourceEntity?,
        streamUrl: String,
        httpHeaders: String?,
        profileId: Long,
        mediaType: MediaType,
        itemId: Long,
        startPositionMs: Long,
    ): Boolean {
        val url = resolve(source, streamUrl) ?: return false
        externalPlayerLauncher.launch(
            url = url,
            title = title,
            userAgent = source?.userAgent,
            httpHeaders = SourceOverrides.headersWithReferer(httpHeaders, source),
            startPositionMs = startPositionMs,
        )
        runCatching {
            withContext(Dispatchers.IO) {
                historyDao.record(WatchHistoryEntity(profileId = profileId, mediaType = mediaType, itemId = itemId))
            }
        }
        return false
    }

    /** Stalker mints a play URL per open; the stored "URL" is a portal command until then. */
    private suspend fun resolve(source: SourceEntity?, streamUrl: String): String? =
        if (streamUrlResolver.needsResolve(source)) {
            runCatching { streamUrlResolver.resolve(source!!, streamUrl, vod = true) }.getOrNull()
        } else {
            streamUrl
        }

    /** A Stalker link dies before a long film ends; give the player a way to mint a fresh one. Null
     *  for M3U and Xtream, which also clears whatever the previous item left on the player. */
    private fun reconnectFor(source: SourceEntity?, streamUrl: String): ReconnectUrlProvider? =
        if (streamUrlResolver.needsResolve(source)) {
            ReconnectUrlProvider {
                runCatching { streamUrlResolver.resolve(source!!, streamUrl, vod = true) }.getOrNull()
            }
        } else {
            null
        }

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }

    /** "S2 · E4 Title" — core's wording, so the player's second line reads as it does on the TV. */
    private fun episodeLabel(episode: EpisodeEntity): String =
        if (episode.name.isBlank()) {
            context.getString(R.string.content_season_episode, episode.seasonNumber, episode.episodeNumber)
        } else {
            context.getString(
                R.string.content_season_episode_title,
                episode.seasonNumber,
                episode.episodeNumber,
                episode.name,
            )
        }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 5_000L
    }
}
