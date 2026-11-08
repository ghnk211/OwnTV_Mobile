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
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.model.MediaType
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
        _playing.value = VodPlayback(MediaType.LIVE, RECORDING_ITEM_ID, title, posterUrl = posterUrl, categoryId = categoryId)
        playingQueue = null
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
