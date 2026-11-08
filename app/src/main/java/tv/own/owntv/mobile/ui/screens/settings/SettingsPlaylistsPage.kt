package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.ui.text.style.TextOverflow
import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.core.setup.detailLines
import tv.own.owntv.core.setup.headline
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.sync.SyncCounts
import tv.own.owntv.core.sync.SyncProgressCounts
import tv.own.owntv.core.sync.SyncScopeChoice
import tv.own.owntv.core.sync.breakdownText
import tv.own.owntv.core.sync.displayText
import tv.own.owntv.core.sync.resyncProgressPercent
import tv.own.owntv.core.sync.work.CatalogSyncState
import tv.own.owntv.mobile.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.ui.shell.displayName
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StagePill
import tv.own.owntv.mobile.ui.stage.StagePlaylistMark
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.setup.AddSourceForm
import tv.own.owntv.mobile.ui.setup.SourceFormValues
import tv.own.owntv.mobile.ui.setup.SourceKind
import tv.own.owntv.mobile.ui.setup.isJsonLocation
import tv.own.owntv.mobile.ui.setup.labelRes

/**
 * Settings › Sources & guide › Playlists: "+ Add source", then one row per playlist — its mark, its
 * name, the type with what it holds, when it synced and when it runs out — and on the right OK, Default, or the
 * import running. Addresses are never shown in the list. A tap opens its actions.
 */
@Composable
fun SettingsPlaylistsPage(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val defaultId by vm.defaultSourceId.collectAsStateWithLifecycle()
    val expiry by vm.sourceExpiry.collectAsStateWithLifecycle()
    val deleting by vm.deletingSourceIds.collectAsStateWithLifecycle()
    val test by vm.sourceTest.collectAsStateWithLifecycle()
    val playlistRefresh = vm.settings.playlistAutoRefresh.pref(emptyMap())
    val nav = LocalSettingsNav.current

    var menuSource by remember { mutableStateOf<SourceEntity?>(null) }
    var editSource by remember { mutableStateOf<SourceEntity?>(null) }
    var refreshFor by remember { mutableStateOf<SourceEntity?>(null) }
    var namesFor by remember { mutableStateOf<SourceEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<SourceEntity?>(null) }
    // Set while the "this will stop playback and take a while" confirmation is on screen.
    var confirmRetest by remember { mutableStateOf<SourceEntity?>(null) }

    SettingsCrumb(pluralStringResource(R.plurals.settings_sources_count, sources.size, sources.size), SettingsGroup.SOURCES)
    SettingsStagePage(modifier) {
        item(key = "add") {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                StageButton(onClick = { nav(SettingsLeaf.ADD_SOURCE.route) }, text = stringResource(R.string.settings_sources_add), icon = MobileIcons.Add, small = true)
            }
        }
        if (sources.isEmpty()) item(key = "empty") { PageNote(stringResource(R.string.settings_sources_empty)) }
        sources.forEachIndexed { index, source ->
            item(key = source.id) {
                PlaylistRow(
                    source = source,
                    index = index,
                    isDefault = source.id == defaultId,
                    expiry = expiry[source.id],
                    isDeleting = source.id in deleting,
                    counts = vm.contentCounts(source.id).collectAsStateWithLifecycle(null).value,
                    syncState = vm.syncState(source.id).collectAsStateWithLifecycle(CatalogSyncState.Idle).value,
                    onClick = { if (source.id !in deleting) menuSource = source },
                )
            }
        }
    }

    // The playlist's header, then Re-sync · Re-sync and remove · Edit · Names · Info · Auto refresh · Default · Delete.
    menuSource?.let { source ->
        val syncing = vm.syncState(source.id).collectAsStateWithLifecycle(CatalogSyncState.Idle).value.isActive
        val counts = vm.contentCounts(source.id).collectAsStateWithLifecycle(null).value
        val close = { menuSource = null }
        MobileBottomSheet(onDismissRequest = close) {
            StageSheetHeader(
                source.displayName(),
                subtitle = typeLine(source, counts?.breakdownText(LocalContext.current.resources)),
                leading = { StagePlaylistMark(PlaylistMark.of(source.displayName(), sources.indexOf(source))) },
            )
            if (syncing) {
                StageMenuItem(stringResource(R.string.settings_sources_cancel), onClick = { vm.cancelResync(source); close() }, icon = MobileIcons.Close)
            } else {
                StageMenuItem(stringResource(R.string.settings_sources_resync_now_full), onClick = { vm.resync(source); close() }, icon = MobileIcons.Refresh)
                StageMenuItem(stringResource(R.string.settings_sources_resync_remove_full), onClick = { vm.resync(source, removeMissing = true); close() }, icon = MobileIcons.Refresh)
            }
            StageMenuItem(stringResource(R.string.settings_sources_edit), onClick = { editSource = source; close() }, icon = MobileIcons.Edit)
            StageMenuItem(stringResource(R.string.settings_sources_clean_names), onClick = { namesFor = source; close() }, icon = MobileIcons.AutoAwesome)
            // "Info", not "Test": the expensive measurement lives behind Re-test inside the sheet.
            StageMenuItem(stringResource(R.string.settings_sources_info), onClick = { vm.testSource(source); close() }, icon = MobileIcons.Info)
            StageMenuItem(
                stringResource(R.string.setup_auto_refresh),
                onClick = { refreshFor = source; close() },
                icon = MobileIcons.Schedule,
                value = playlistRefreshLabel(playlistRefresh[source.id] ?: PlaylistRefresh()),
            )
            StageSettingRow(
                title = stringResource(R.string.setup_default_playlist),
                icon = MobileIcons.StarBorder,
                minHeight = 48.dp,
                onClick = { vm.setDefaultSource(if (source.id == defaultId) -1L else source.id) },
            ) { StageSwitch(source.id == defaultId) }
            StageMenuItem(stringResource(R.string.settings_sources_delete), onClick = { confirmDelete = source; close() }, icon = MobileIcons.Delete, danger = true)
        }
    }

    editSource?.let { source ->
        EditPlaylistDialog(
            source = source,
            onDismiss = { editSource = null },
            onSave = { v ->
                vm.updateSource(
                    id = source.id,
                    name = v.name,
                    urlOrServer = v.urlOrServer,
                    user = v.username,
                    pass = v.password,
                    userAgent = v.userAgent,
                    httpReferer = v.httpReferer,
                    autoRefresh = v.autoRefresh,
                    mac = v.mac,
                    stalkerSerialNumber = v.serialNumber,
                    stalkerDeviceId = v.deviceId,
                    stalkerDeviceId2 = v.deviceId2,
                    stalkerSignature = v.signature,
                    syncLive = v.live != SyncScopeChoice.Off,
                    syncMovies = v.movies != SyncScopeChoice.Off,
                    syncSeries = v.series != SyncScopeChoice.Off,
                    preferHls = v.preferHls,
                )
                editSource = null
            },
        )
    }

    refreshFor?.let { source ->
        val current = playlistRefresh[source.id] ?: PlaylistRefresh()
        StageChoiceSheet(
            eyebrow = source.displayName(),
            title = stringResource(R.string.setup_auto_refresh_title),
            text = stringResource(R.string.setup_auto_refresh_description),
            choices = PlaylistAutoRefresh.entries.map { SettingsChoice(it, stringResource(it.labelRes())) },
            selected = current.mode,
            onSelect = { mode -> vm.edit { setPlaylistAutoRefresh(source.id, current.copy(mode = mode)) } },
            onDismiss = { refreshFor = null },
        )
    }

    namesFor?.let { source ->
        ChannelNamesSheet(
            source = source,
            onSave = { enabled, text -> vm.saveChannelNameCleanup(source, enabled, text); namesFor = null },
            onDismiss = { namesFor = null },
        )
    }

    confirmDelete?.let { source ->
        StageConfirmSheet(
            title = stringResource(R.string.settings_sources_delete_title, source.displayName()),
            text = stringResource(R.string.settings_sources_delete_message),
            confirm = stringResource(R.string.common_delete),
            confirmIcon = MobileIcons.Delete,
            danger = true,
            onConfirm = { vm.deleteSource(source) },
            onDismiss = { confirmDelete = null },
        )
    }

    test?.let { state ->
        val palette = LocalStagePalette.current
        MobileBottomSheet(onDismissRequest = vm::dismissSourceTest) {
            StageSheetHeader(state.sourceName, eyebrow = stringResource(R.string.settings_sources_test_title))
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (state) {
                    is SettingsViewModel.SourceTestState.Running ->
                        Text(stringResource(R.string.setup_testing), style = stageText(12.5f, 500), color = palette.muted)
                    // The measurement is slow by nature, so it says which stream it is on.
                    is SettingsViewModel.SourceTestState.Measuring -> Text(
                        stringResource(R.string.settings_sources_probe_running, state.progress.stream, state.progress.maxStreams),
                        style = stageText(12.5f, 500), color = palette.muted,
                    )
                    is SettingsViewModel.SourceTestState.Done -> {
                        TestReport(state.result, state.limit)
                        if (sources.firstOrNull { it.name == state.sourceName }?.ignoreConnectionLimit == true) {
                            Text(stringResource(R.string.settings_sources_ignore_limit_on), style = stageText(12.5f, 500), color = palette.muted)
                        }
                    }
                }
            }
            // While measuring the only honest action abandons it; afterwards, the one that repeats it.
            if (state is SettingsViewModel.SourceTestState.Measuring) {
                StageMenuItem(stringResource(R.string.settings_sources_probe_skip), onClick = vm::skipConnectionMeasurement, icon = MobileIcons.Close)
            } else if (state is SettingsViewModel.SourceTestState.Done) {
                sources.firstOrNull { it.name == state.sourceName }?.let { src ->
                    StageMenuItem(stringResource(R.string.settings_sources_retest), onClick = { confirmRetest = src }, icon = MobileIcons.Refresh)
                    // For a provider that allows more than it says, when measuring cannot show it.
                    StageMenuItem(
                        stringResource(R.string.settings_sources_ignore_limit),
                        onClick = { vm.setIgnoreConnectionLimit(src, !src.ignoreConnectionLimit) },
                        icon = MobileIcons.Multiview,
                        value = stringResource(if (src.ignoreConnectionLimit) R.string.common_on else R.string.common_off),
                    )
                }
            }
            StageMenuItem(stringResource(R.string.common_done), onClick = vm::dismissSourceTest, icon = MobileIcons.Check)
        }
    }

    // Measuring opens real streams, so the user is told that playback stops and that it is slow.
    confirmRetest?.let { src ->
        StageConfirmSheet(
            title = stringResource(R.string.settings_sources_probe_title),
            text = stringResource(R.string.settings_sources_probe_warning),
            confirm = stringResource(R.string.settings_sources_retest),
            onConfirm = { vm.retestSource(src) },
            onDismiss = { confirmRetest = null },
        )
    }
}

/** The test's own verdict and its lines of detail — both sentences are core's, shared with the TV. */
@Composable
private fun TestReport(result: SourceTestResult, limit: tv.own.owntv.core.live.ConnectionLimit?) {
    val res = LocalContext.current.resources
    val palette = LocalStagePalette.current
    Text(result.headline(res), style = stageText(14.5f, 700), color = palette.text)
    result.detailLines(res, limit).forEach { line -> Text(line, style = stageText(12.5f, 500), color = palette.muted) }
}

/** "Xtream · 55K channels · 179K movies · 48K series" — the type and what it holds, never its address. */
@Composable
private fun typeLine(source: SourceEntity, counts: String?): String {
    val type = stringResource(
        when (source.type) {
            SourceType.XTREAM -> R.string.setup_xtream
            SourceType.STALKER -> R.string.setup_stalker_mac
            SourceType.M3U -> if (isJsonLocation(source.url)) R.string.setup_tvbox else R.string.setup_m3u
            SourceType.LOCAL_BACKUP -> R.string.settings_sources_backup
        },
    )
    return listOfNotNull(type, counts?.takeIf { it.isNotBlank() }).joinToString(stringResource(R.string.content_epg_bits_separator))
}

/**
 * One playlist (`.srow` with its mark): name, then the type and counts (the live counts while an import runs),
 * then when it synced and when it runs out; OK, Default (accent), Deleting or the import's progress on the right.
 */
@Composable
private fun PlaylistRow(
    source: SourceEntity,
    index: Int,
    isDefault: Boolean,
    expiry: String?,
    isDeleting: Boolean,
    counts: SyncCounts?,
    syncState: CatalogSyncState,
    onClick: () -> Unit,
) {
    val res = LocalContext.current.resources
    val palette = LocalStagePalette.current
    val active = syncState as? CatalogSyncState.Syncing
    val activeCounts = active?.progressCounts(source.type, counts)
    val visibleCounts = if (active == null) counts?.breakdownText(res) else activeCounts?.displayText(res)
    val sep = stringResource(R.string.content_epg_bits_separator)
    val second = listOfNotNull(
        source.lastSyncAt?.let { stringResource(R.string.settings_synced_at, syncedWhen(it)) },
        expiry?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_sources_expiry, it) },
    ).joinToString(sep)
    val first = if (active != null && visibleCounts.isNullOrBlank()) stringResource(R.string.settings_sources_preparing_detail) else typeLine(source, visibleCounts)
    StageSettingRow(
        title = source.displayName(),
        subtitle = listOf(first, second).filter { it.isNotBlank() }.joinToString("\n"),
        leading = { StagePlaylistMark(PlaylistMark.of(source.displayName(), index)) },
        onClick = onClick,
        onLongClick = onClick,
    ) {
        val (text, color) = when {
            isDeleting -> stringResource(R.string.settings_sources_deleting) to palette.danger
            active != null -> (resyncProgressPercent(active.baseItemCount, active.totalProcessed)
                ?.let { stringResource(R.string.sync_progress_percent, it) } ?: stringResource(R.string.sync_progress_syncing)) to stageAccent.accent
            isDefault -> stringResource(R.string.settings_sources_status_default) to stageAccent.accent
            else -> stringResource(R.string.common_ok) to palette.muted
        }
        Text(text, style = stageText(12.5f, 700), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A playlist's "Clean channel names": the switch, then the user's own words to remove. Saving
 * stores the choice; the playlist re-syncs when the shown names change.
 */
@Composable
private fun ChannelNamesSheet(
    source: SourceEntity,
    onSave: (enabled: Boolean, customText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(source.cleanChannelNames) }
    var customText by remember { mutableStateOf(source.channelNameStrip.orEmpty()) }
    val title = stringResource(R.string.settings_sources_clean_names_title, source.displayName())
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title, subtitle = stringResource(R.string.settings_sources_clean_names_description))
        StageSettingRow(
            title = stringResource(R.string.settings_sources_clean_names_toggle),
            subtitle = stringResource(R.string.settings_sources_clean_names_example),
            minHeight = 48.dp,
            onClick = { enabled = !enabled },
        ) { StageSwitch(enabled) }
        StageField(
            value = customText,
            onValueChange = { customText = it },
            label = stringResource(R.string.settings_sources_clean_names_custom),
            placeholder = stringResource(R.string.settings_sources_clean_names_custom_hint),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SheetButtons(
            confirm = stringResource(R.string.common_save),
            confirmEnabled = true,
            onConfirm = { onSave(enabled, customText) },
            onDismiss = onDismiss,
        )
    }
}

/** The add form again, full screen on the Stage page, with the playlist's own answers already in it. */
@Composable
private fun EditPlaylistDialog(
    source: SourceEntity,
    onDismiss: () -> Unit,
    onSave: (SourceFormValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The form's fields and pickers open sheets. The app's own sheet host draws in the main window,
        // under this dialog, where they could not be seen or used — so the dialog hosts its own.
        tv.own.owntv.mobile.ui.components.MobileSheetHost {
        Column(Modifier.fillMaxSize().stageBackground().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_sources_edit), style = stageText(13f, 500), color = LocalStagePalette.current.muted)
                    Text(source.displayName(), style = stageText(28f, 800), color = LocalStagePalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StagePill(onClick = onDismiss, icon = MobileIcons.Close, contentDescription = stringResource(R.string.settings_close))
            }
            AddSourceForm(
                onStartXtream = { _, _, _, _, _, _, _, _, _, _, _ -> },
                onStartM3u = { _, _, _, _, _ -> },
                onStartStalker = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                initial = source.toFormValues(),
                onSave = onSave,
            )
        }
        }
    }
}

private fun SourceEntity.toFormValues(): SourceFormValues {
    fun scope(on: Boolean) = if (on) SyncScopeChoice.Now else SyncScopeChoice.Off
    return SourceFormValues(
        kind = when (type) {
            SourceType.XTREAM -> SourceKind.XTREAM
            SourceType.STALKER -> SourceKind.STALKER
            // A TVBox config is stored as an M3U source; the .json location is what tells them apart.
            else -> if (isJsonLocation(url)) SourceKind.TVBOX else SourceKind.M3U
        },
        name = name,
        urlOrServer = url,
        username = username.orEmpty(),
        mac = mac.orEmpty(),
        serialNumber = stalkerSerialNumber.orEmpty(),
        deviceId = stalkerDeviceId.orEmpty(),
        deviceId2 = stalkerDeviceId2.orEmpty(),
        signature = stalkerSignature.orEmpty(),
        userAgent = userAgent.orEmpty(),
        httpReferer = httpReferer.orEmpty(),
        live = scope(syncLive),
        movies = scope(syncMovies),
        series = scope(syncSeries),
        preferHls = preferHls,
    )
}

/**
 * What a run has fetched so far. A section that is not being fetched shows what is already stored,
 * so switching from "syncing films" to "syncing series" does not blank the film count.
 */
private fun CatalogSyncState.Syncing.progressCounts(
    type: SourceType,
    stored: SyncCounts?,
): SyncProgressCounts? {
    if (type == SourceType.LOCAL_BACKUP) return null
    fun visible(active: Boolean, processed: Int, storedCount: Int) = if (active) processed else storedCount
    val live = visible(liveActive, liveProcessed, stored?.channels ?: 0)
    val movies = visible(moviesActive, moviesProcessed, stored?.movies ?: 0)
    val series = visible(seriesActive, seriesProcessed, stored?.series ?: 0)
    // An M3U is one flat list; splitting it into three counts would invent sections it does not have.
    val counts = if (type == SourceType.M3U) {
        SyncProgressCounts(live, 0, 0, liveActive = true, moviesActive = false, seriesActive = false)
    } else {
        SyncProgressCounts(
            live = live,
            movies = movies,
            series = series,
            liveActive = liveActive || live > 0,
            moviesActive = moviesActive || movies > 0,
            seriesActive = seriesActive || series > 0,
        )
    }
    return counts.takeIf { it.hasItems }
}

@Composable
internal fun playlistRefreshLabel(refresh: PlaylistRefresh): String =
    if (refresh.mode == PlaylistAutoRefresh.MANUAL) {
        pluralStringResource(R.plurals.settings_sources_refresh_days, refresh.manualDays, refresh.manualDays)
    } else {
        stringResource(refresh.mode.labelRes())
    }

internal fun SourceType.labelRes(): Int = when (this) {
    SourceType.M3U -> R.string.settings_sources_type_m3u
    SourceType.XTREAM -> R.string.settings_sources_type_xtream
    SourceType.STALKER -> R.string.settings_sources_type_stalker
    SourceType.LOCAL_BACKUP -> R.string.settings_sources_backup
}
