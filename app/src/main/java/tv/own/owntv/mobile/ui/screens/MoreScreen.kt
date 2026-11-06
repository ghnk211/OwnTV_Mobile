package tv.own.owntv.mobile.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.profiles.ProfilesViewModel
import tv.own.owntv.mobile.ui.shell.ShellAvatar
import tv.own.owntv.mobile.ui.shell.ShellViewModel
import tv.own.owntv.mobile.ui.shell.displayName
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTagTone
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.player.PlaybackErrorLog

/**
 * More, opened from the avatar: the current profile card, then Settings and Downloads, YOUR THINGS
 * (Favourites, History), KEEP IT SAFE (Backup & Restore, Local sync) and APP (Error log, About) — each
 * row with what it holds before you go and look. Two layers: More, then a page.
 *
 * Backup, Local sync, the error log and About are here rather than in Settings because none of them is a
 * setting. The card only says who is watching and which playlist is open; with one profile there is
 * nothing to switch to, and the lock and kids settings live in Settings › App.
 */
@Composable
fun MoreScreen(
    scrollToTop: SharedFlow<String>,
    onNavigate: (MobileDestination) -> Unit,
    onOpenLeaf: (MoreLeaf) -> Unit,
    modifier: Modifier = Modifier,
    counts: MoreCountsViewModel = koinViewModel(),
    profilesVm: ProfilesViewModel = koinViewModel(),
    shell: ShellViewModel = koinViewModel(),
) {
    val favorites by counts.favorites.collectAsStateWithLifecycle()
    val history by counts.history.collectAsStateWithLifecycle()
    val pinned by counts.pinnedCount.collectAsStateWithLifecycle()
    val running by counts.runningDownloads.collectAsStateWithLifecycle()
    val lastBackup by counts.lastBackup.collectAsStateWithLifecycle()
    val syncOn by counts.syncOn.collectAsStateWithLifecycle()
    val profiles by profilesVm.profiles.collectAsStateWithLifecycle()
    val activeId by profilesVm.activeProfileId.collectAsStateWithLifecycle()
    val playlists by shell.playlists.collectAsStateWithLifecycle()
    val activePlaylistId by shell.activePlaylistId.collectAsStateWithLifecycle()
    val profile = profiles.orEmpty().firstOrNull { it.id == activeId }

    // The log is a file, not a flow: read again whenever More comes back (an export or a clear on its page).
    val context = LocalContext.current
    var logVersion by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) { logVersion++; onPauseOrDispose { } }
    val logCount by produceState(0, logVersion) { value = withContext(Dispatchers.IO) { PlaybackErrorLog.read(context).size } }

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileDestination.MORE.route, scrollToTop = scrollToTop)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            val playlistLabel = playlists.firstOrNull { it.id == activePlaylistId }?.displayName()
                ?: stringResource(R.string.content_all_playlists)
            ProfileCard(
                name = profile?.name.orEmpty(),
                line = playlistLabel + stringResource(R.string.content_epg_bits_separator) +
                    pluralStringResource(R.plurals.more_playlist_count, playlists.size, playlists.size),
                avatar = { ShellAvatar(profile, onClick = null, diameter = 44.dp) },
            )
        }
        item {
            Column(Modifier.padding(top = 10.dp)) {
                MoreItem(
                    R.string.common_nav_settings, MobileIcons.Settings,
                    pinned.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.more_settings_pinned_count, it, it) },
                ) { onNavigate(MobileDestination.SETTINGS) }
                MoreItem(
                    R.string.common_nav_downloads, MobileIcons.Download,
                    running.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.more_downloads_running_count, it, it) },
                ) { onNavigate(MobileDestination.DOWNLOADS) }
                StageGroupLabel(stringResource(R.string.more_group_your_things))
                MoreItem(R.string.content_category_favorites, MobileIcons.FavoriteBorder, favorites.total.toString()) { onOpenLeaf(MoreLeaf.FAVORITES) }
                MoreItem(R.string.content_category_history, MobileIcons.History, history.total.toString()) { onOpenLeaf(MoreLeaf.HISTORY) }
                StageGroupLabel(stringResource(R.string.more_group_keep_safe))
                // Never backed up is the one row that warns (`.tag.warn`); a backup says how long ago.
                val backupValue = lastBackup?.let { relativeShort(it.at) }
                StageMenuItem(
                    label = stringResource(R.string.settings_backup_restore),
                    onClick = { onOpenLeaf(MoreLeaf.BACKUP) },
                    icon = MobileIcons.Download,
                    count = backupValue,
                    tags = if (backupValue == null) {
                        { StageTag(stringResource(R.string.common_never), tone = StageTagTone.WARN, icon = MobileIcons.Warning) }
                    } else null,
                    height = 48.dp,
                    chevron = true,
                )
                MoreItem(R.string.local_sync_title, MobileIcons.Phone, stringResource(if (syncOn) R.string.common_on else R.string.common_off)) { onOpenLeaf(MoreLeaf.LOCAL_SYNC) }
                StageGroupLabel(stringResource(R.string.settings_app_group))
                MoreItem(R.string.settings_playback_error_log, MobileIcons.Warning, logCount.toString()) { onOpenLeaf(MoreLeaf.ERROR_LOG) }
                MoreItem(R.string.settings_about, MobileIcons.Info, BuildConfig.VERSION_NAME) { onOpenLeaf(MoreLeaf.ABOUT) }

                // Dev-only and English-only: R8 removes the row from a published build.
                if (BuildConfig.DEV_TOOLS) {
                    RebuildTrendingRow()
                }
            }
        }
    }
}

/** `.mi` 48 high: icon, label, the value (`.n`) and the chevron. */
@Composable
private fun MoreItem(labelRes: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String?, onClick: () -> Unit) {
    StageMenuItem(label = stringResource(labelRes), onClick = onClick, icon = icon, count = value, height = 48.dp, chevron = true)
}

/**
 * The glass profile card (radius 20, padding 12): the 44 dp avatar, the name (16 / 800), "All playlists ·
 * 3 playlists" (11.5 muted). It shows — there is one profile, so there is nothing to switch to and
 * nothing to open.
 */
@Composable
private fun ProfileCard(name: String, line: String, avatar: @Composable () -> Unit) {
    val palette = LocalStagePalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .stageGlass(shape)
            .clip(shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Column(Modifier.weight(1f)) {
            Text(name, style = stageText(16f, 800), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = stageText(11.5f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "2 days ago": Android's own relative time, so it is localised without a string of ours. */
private fun relativeShort(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * The television's "Rebuild Now Trending", on the phone.
 *
 * Trending is read-only on mobile — the row shows what core last stored, and the
 * refresh is scheduled a few days apart. That timer is the problem when a trending bug is being
 * chased: reproducing it means waiting days, or driving the television. This forces the download for
 * every playlist on the active profile, right now, ignoring the timer.
 *
 * Deliberately unthrottled beyond its own two-second chip, for the reason the television's is:
 * `BuildConfig.DEV_TOOLS` is false in every published APK, so R8 deletes this function and the row
 * that calls it, and a maintainer chasing a bug needs to press it as often as the bug requires.
 *
 * English-only, like the row above it and by the same exception — it never reaches a user. The
 * chip is the one exception: core already has that word translated, so it costs nothing to use it.
 */
@Composable
private fun RebuildTrendingRow(
    settings: SettingsRepository = koinInject(),
    sourceDao: SourceDao = koinInject(),
    scheduler: CatalogSyncScheduler = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    StageMenuItem(
        label = "Rebuild Now Trending",
        icon = MobileIcons.Build,
        value = if (running) stringResource(R.string.settings_rebuilding) else null,
        height = 48.dp,
        onClick = {
            if (running) return@StageMenuItem
            running = true
            scope.launch {
                val profileId = settings.activeProfileId.first()
                if (profileId >= 0) {
                    sourceDao.sourceIdsForProfile(profileId)
                        .forEach { scheduler.enqueueTrendingRefresh(it, force = true) }
                }
                // The work is a background job, so there is nothing to await — the chip is there to
                // say the press landed, not to report the result. Watch the row itself for that.
                delay(2_500)
                running = false
            }
        },
    )
}
