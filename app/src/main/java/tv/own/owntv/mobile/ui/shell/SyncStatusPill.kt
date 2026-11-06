package tv.own.owntv.mobile.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import tv.own.owntv.core.download.DownloadActivityTracker
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.core.recording.RecordingActivityTracker
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.sync.EpgActivityTracker
import tv.own.owntv.core.sync.SyncActivityTracker
import tv.own.owntv.core.sync.SyncProgressCounts
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.TrendingActivityTracker
import tv.own.owntv.core.sync.compactCount
import tv.own.owntv.core.sync.displayText
import tv.own.owntv.core.setup.displayText
import tv.own.owntv.core.util.classifySyncFailure
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * "Syncing Sky Sports · 1.2K channels", low over the content while a sync runs.
 *
 * A first import takes minutes, and on a phone it happens while the user is somewhere else in the
 * app entirely — without this the catalogue simply appears to be empty and stay empty. One line per
 * running sync, so two playlists and a guide are three readable lines instead of one that hides the
 * other two, and a finished sync stays up for five seconds so the result is not missed.
 *
 * Every string and every count comes from core's own sync text, the same functions the television
 * calls, so the two apps narrate one sync identically.
 */
@Composable
fun SyncStatusPill(
    modifier: Modifier = Modifier,
    /**
     * Show recordings and nothing else. Over the player the pill used to be hidden outright, so a
     * running recording was invisible for as long as the user was watching — which is most of the
     * time it is running. A catalogue sync over the picture is noise; a recording is the one line
     * that is time-critical and unrecoverable, so it is the one that earns the space.
     */
    recordingsOnly: Boolean = false,
    onOpenDownloads: () -> Unit = {},
) {
    val catalogTracker: SyncActivityTracker = koinInject()
    val epgTracker: EpgActivityTracker = koinInject()
    val trendingTracker: TrendingActivityTracker = koinInject()
    val downloadTracker: DownloadActivityTracker = koinInject()
    val recordingTracker: RecordingActivityTracker = koinInject()
    val connectivity: ConnectivityObserver = koinInject()

    val activeCatalog by catalogTracker.active.collectAsStateWithLifecycle()
    val activeEpg by epgTracker.active.collectAsStateWithLifecycle()
    val activeTrending by trendingTracker.active.collectAsStateWithLifecycle()
    val activeDownload by downloadTracker.active.collectAsStateWithLifecycle()
    val activeRecordings by recordingTracker.active.collectAsStateWithLifecycle()
    val lastCompleted by catalogTracker.lastCompleted.collectAsStateWithLifecycle()
    val lastTrendingCompleted by trendingTracker.lastCompleted.collectAsStateWithLifecycle()

    // Two syncs finishing together must not replace one another before either has been read, so
    // completions queue and are shown one at a time.
    val completedQueue = remember { mutableStateListOf<SyncActivityTracker.CompletedSync>() }
    var currentCompleted by remember { mutableStateOf<SyncActivityTracker.CompletedSync?>(null) }
    var currentTrending by remember { mutableStateOf<TrendingActivityTracker.CompletedBuild?>(null) }

    LaunchedEffect(lastCompleted) {
        val completed = lastCompleted ?: return@LaunchedEffect
        if (completedQueue.none { it.timestamp == completed.timestamp && it.sourceId == completed.sourceId }) {
            completedQueue.add(completed)
        }
    }
    LaunchedEffect(lastTrendingCompleted) {
        val completed = lastTrendingCompleted ?: return@LaunchedEffect
        currentTrending = completed
        trendingTracker.consumeCompleted(completed.timestamp)
    }
    LaunchedEffect(currentCompleted, completedQueue.size) {
        if (currentCompleted == null && completedQueue.isNotEmpty()) {
            val next = completedQueue.removeAt(0)
            currentCompleted = next
            catalogTracker.consumeCompleted(next.timestamp)
        }
    }
    LaunchedEffect(currentCompleted) {
        if (currentCompleted != null) { delay(COMPLETED_MS); currentCompleted = null }
    }
    LaunchedEffect(currentTrending) {
        if (currentTrending != null) { delay(COMPLETED_MS); currentTrending = null }
    }

    // Sorted once per progress change, not once per emission per row: the trackers emit at Hz
    // rates while a sync runs, and the sort has nothing new to say between two emissions of the
    // same maps.
    val rows = remember(activeCatalog, activeEpg, activeTrending, activeDownload, activeRecordings, recordingsOnly) {
        buildList {
        // Recordings first, always: they are time-critical and unrecoverable, so they are never
        // the line that gets collapsed into "+N more". Then downloads, which the user also started
        // deliberately. The background syncs are the ones that can afford to be hidden.
        activeRecordings.values.sortedBy { it.id }.forEach { add(SyncLine.Recording(it)) }
        if (recordingsOnly) return@buildList
        activeDownload?.let { add(SyncLine.Download(it)) }
        activeCatalog.values.sortedBy { it.sourceId }.forEach { add(SyncLine.Catalog(it)) }
        activeTrending.values.sortedBy { it.sourceId }.forEach { add(SyncLine.Trending(it)) }
        activeEpg.values.sortedBy { it.sourceId }.forEach { add(SyncLine.Epg(it)) }
        }
    }
    val shown = rows.take(MAX_ROWS)
    val hidden = rows.size - shown.size

    AnimatedVisibility(
        visible = shown.isNotEmpty() || currentCompleted != null || currentTrending != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val res = LocalContext.current.resources
        Column(
            Modifier
                .padding(MobileDimens.GapSmall)
                .stageGlass(PillShape)
                .clip(PillShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shown.forEach { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // Only the download line goes anywhere: the pill is otherwise a notice, and the
                    // sync lines have no screen of their own to open.
                    modifier = if (line is SyncLine.Download) {
                        Modifier.clickable(onClick = onOpenDownloads)
                    } else {
                        Modifier
                    },
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = stageAccent.accent,
                    )
                    PillText(line.text(res))
                }
                // Only Now Trending gets a second line. A catalogue sync's headline already carries
                // its counts, but the trending build spends minutes on one stage, and these numbers
                // are the only sign it is moving rather than stuck.
                if (line is SyncLine.Trending) {
                    PillText(trendingDetailLine(res, line.build), detail = true)
                }
            }
            if (hidden > 0) {
                PillText(res.getQuantityString(R.plurals.sync_status_more, hidden, hidden))
            }
            currentCompleted?.let {
                PillText(completedLine(res, it, connectivity.isOnlineNow()))
            }
            currentTrending?.let {
                PillText(trendingCompletedLine(res, it))
                PillText(
                    res.getString(
                        R.string.sync_status_trending_detail_completed,
                        it.movieMatches,
                        it.movieCandidates,
                        it.seriesMatches,
                        it.seriesCandidates,
                    ),
                    detail = true,
                )
            }
        }
    }
}

/** A line of the pill in Stage type: the headline in the text colour, the detail under it muted. */
@Composable
private fun PillText(text: String, detail: Boolean = false) {
    val palette = LocalStagePalette.current
    Text(
        text = text,
        style = if (detail) stageText(11f, 500) else stageText(12.5f, 700),
        color = if (detail) palette.muted else palette.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private sealed interface SyncLine {
    data class Catalog(val sync: SyncActivityTracker.ActiveSync) : SyncLine
    data class Download(val download: DownloadActivityTracker.ActiveDownload) : SyncLine
    data class Recording(val recording: tv.own.owntv.core.recording.RecordingProgress) : SyncLine
    data class Trending(val build: TrendingActivityTracker.ActiveBuild) : SyncLine
    data class Epg(val sync: EpgActivityTracker.ActiveEpgSync) : SyncLine
}

/**
 * The `|| processed > 0` on each phase flag mirrors the television: the syncer clears a phase's
 * active flag when it moves on, and without this a finished phase's count would vanish mid-sync.
 */
private fun SyncLine.text(res: android.content.res.Resources): String = when (this) {
    is SyncLine.Catalog -> {
        val counts = sync.stage?.let {
            SyncProgressCounts(
                live = it.liveProcessed,
                movies = it.moviesProcessed,
                series = it.seriesProcessed,
                liveActive = it.liveActive || it.liveProcessed > 0,
                moviesActive = it.moviesActive || it.moviesProcessed > 0,
                seriesActive = it.seriesActive || it.seriesProcessed > 0,
            )
        }
        val text = counts?.displayText(res).orEmpty()
        if (text.isBlank()) res.getString(R.string.sync_status_catalog, sync.sourceName)
        else res.getString(R.string.sync_status_catalog_with_counts, sync.sourceName, text)
    }
    is SyncLine.Epg -> {
        val parts = buildList {
            if (sync.channels > 0) {
                add(res.getQuantityString(R.plurals.sync_count_channels, sync.channels, compactCount(res, sync.channels)))
            }
            if (sync.programmes > 0) {
                add(res.getQuantityString(R.plurals.sync_count_epg, sync.programmes, compactCount(res, sync.programmes)))
            }
        }
        val text = parts.joinToString(res.getString(R.string.sync_counts_separator))
        if (text.isBlank()) res.getString(R.string.sync_status_epg, sync.sourceName)
        else res.getString(R.string.sync_status_epg_with_counts, sync.sourceName, text)
    }
    // No percentage: a recording ends on the clock, not on a byte count.
    // With the size as it grows: a recording has no total to count towards, so the bytes already
    // written are the only sign it is moving rather than stuck.
    is SyncLine.Recording -> listOfNotNull(
        res.getString(R.string.recording_pill_line, recording.title),
        recording.bytes.takeIf { it > 0 }?.let { res.getString(R.string.common_size_mb, sizeMb(it)) },
    ).joinToString(res.getString(R.string.content_epg_bits_separator))
    is SyncLine.Download -> {
        val percent = download.progress?.let { (it * 100).toInt() }
        if (percent == null) res.getString(R.string.sync_status_download, download.title)
        else res.getString(R.string.sync_status_download_with_progress, download.title, percent)
    }
    is SyncLine.Trending -> when (build.stage) {
        TrendingActivityTracker.Stage.STARTING ->
            res.getString(R.string.sync_status_trending_starting, build.sourceName)
        TrendingActivityTracker.Stage.RECEIVED ->
            res.getQuantityString(R.plurals.sync_status_trending_received, build.candidates, build.candidates)
        TrendingActivityTracker.Stage.PREPARING -> res.getString(R.string.sync_status_trending_preparing)
        TrendingActivityTracker.Stage.MATCHING_MOVIES -> res.getString(R.string.sync_status_trending_matching_movies)
        TrendingActivityTracker.Stage.MATCHING_SERIES -> res.getString(R.string.sync_status_trending_matching_series)
        TrendingActivityTracker.Stage.LOADING_SEASONS -> res.getString(R.string.sync_status_trending_loading_seasons)
        TrendingActivityTracker.Stage.ENRICHING ->
            res.getQuantityString(R.plurals.sync_status_trending_building, build.matched, build.matched)
        TrendingActivityTracker.Stage.PUBLISHING ->
            res.getQuantityString(R.plurals.sync_status_trending_publishing, build.matched, build.matched)
    }
}

/** The numbers under the trending headline — checked out of candidates, matched out of target. */
private fun trendingDetailLine(
    res: android.content.res.Resources,
    build: TrendingActivityTracker.ActiveBuild,
): String = when (build.stage) {
    TrendingActivityTracker.Stage.STARTING -> res.getString(R.string.sync_status_trending_detail_waiting)
    TrendingActivityTracker.Stage.RECEIVED -> res.getString(
        R.string.sync_status_trending_detail_received,
        build.movieCandidates,
        build.seriesCandidates,
    )
    TrendingActivityTracker.Stage.PREPARING -> res.getString(
        R.string.sync_status_trending_detail_preparing,
        build.preparationProcessed,
        build.preparationTotal,
    )
    TrendingActivityTracker.Stage.MATCHING_MOVIES -> res.getString(
        R.string.sync_status_trending_detail_movies,
        build.movieChecked,
        build.movieCandidates,
        build.movieMatches,
        build.movieTarget,
    )
    TrendingActivityTracker.Stage.MATCHING_SERIES -> res.getString(
        R.string.sync_status_trending_detail_series,
        build.seriesChecked,
        build.seriesCandidates,
        build.seriesMatches,
        build.seriesTarget,
    )
    TrendingActivityTracker.Stage.LOADING_SEASONS -> res.getString(
        R.string.sync_status_trending_detail_loading_seasons,
        build.seasonsProcessed,
        build.seasonsTotal,
    )
    TrendingActivityTracker.Stage.ENRICHING,
    TrendingActivityTracker.Stage.PUBLISHING,
    -> res.getString(
        R.string.sync_status_trending_detail_selected,
        build.movieMatches,
        build.seriesMatches,
        build.finalItems,
    )
}

private fun completedLine(
    res: android.content.res.Resources,
    completed: SyncActivityTracker.CompletedSync,
    online: Boolean,
): String = when (val result = completed.result) {
    is SyncResult.Success -> {
        val changes = buildList {
            if (result.categoriesAdded > 0) {
                add(res.getQuantityString(R.plurals.sync_categories_added, result.categoriesAdded, result.categoriesAdded))
            }
            if (result.categoriesRemoved > 0) {
                add(res.getQuantityString(R.plurals.sync_categories_removed, result.categoriesRemoved, result.categoriesRemoved))
            }
        }.joinToString(res.getString(R.string.sync_counts_separator))
        if (changes.isBlank()) res.getString(R.string.sync_status_complete, completed.sourceName)
        else res.getString(R.string.sync_status_complete_with_changes, completed.sourceName, changes)
    }
    is SyncResult.Failed -> res.getString(
        R.string.sync_status_failed,
        completed.sourceName,
        classifySyncFailure(result.message, online).displayText(res),
    )
    SyncResult.Cancelled -> res.getString(R.string.sync_status_cancelled, completed.sourceName)
}

private fun trendingCompletedLine(
    res: android.content.res.Resources,
    completed: TrendingActivityTracker.CompletedBuild,
): String = when {
    completed.preservedFailure -> res.getString(R.string.sync_status_trending_preserved)
    completed.eligible ->
        res.getQuantityString(R.plurals.sync_status_trending_ready, completed.itemCount, completed.itemCount)
    else -> res.getQuantityString(
        R.plurals.sync_status_trending_below_minimum,
        completed.itemCount,
        completed.itemCount,
    )
}

/** How long a finished sync's line stays up before the pill drops it. */
private const val COMPLETED_MS = 5_000L

/** Beyond this many concurrent syncs the pill summarises the rest rather than covering the screen. */
private const val MAX_ROWS = 3

private val PillShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

/** Megabytes to one decimal, formatted for the locale. */
private fun sizeMb(bytes: Long): String =
    java.text.NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(bytes / 1_048_576.0)
