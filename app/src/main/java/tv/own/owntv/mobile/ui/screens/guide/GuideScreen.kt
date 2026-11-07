package tv.own.owntv.mobile.ui.screens.guide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.categorylock.SealedCategoryAction
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.peekOrNull
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.components.sheetOpen
import tv.own.owntv.mobile.ui.player.VideoStage
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.home.durationText
import tv.own.owntv.mobile.ui.screens.home.formatCount
import tv.own.owntv.mobile.ui.screens.home.formatYear
import tv.own.owntv.mobile.ui.screens.home.timeLeftText
import tv.own.owntv.mobile.ui.screens.live.LiveCategoriesSheet
import tv.own.owntv.mobile.ui.screens.live.LiveCategory
import tv.own.owntv.mobile.ui.screens.live.LiveCategoryMenu
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.live.LiveViewModel
import tv.own.owntv.mobile.ui.screens.live.STARTUP_ROW_WAIT_MS
import tv.own.owntv.mobile.ui.screens.live.epgShiftLabel
import tv.own.owntv.mobile.ui.screens.live.label
import tv.own.owntv.mobile.ui.screens.settings.customize.TextPromptDialog
import tv.own.owntv.mobile.ui.shell.LocalMiniRequested
import tv.own.owntv.mobile.ui.shell.ProvidePageCrumb
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.shell.PageCrumb
import tv.own.owntv.mobile.ui.shell.StartupLiveSelection
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageChoiceRow
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageLiveBadge
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSizeBar
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.drawInnerRing
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * The TV Guide: the picked programme on top — a small live preview, its eyebrow, title, details,
 * Remind me and Record — then the tools (day · Now · category · order · search) and the grid. Or, by the
 * View choice in the order sheet, the On now list or one channel's Timeline.
 *
 * A tap on a cell picks it: the top block shows it and the preview plays its channel (Video preview on).
 * A tap on the picked cell, or a hold on any, opens the programme sheet. The channel column plays
 * the channel; a hold there fixes its guide.
 */
@Composable
fun GuideScreen(
    scrollToTop: SharedFlow<String>,
    onOpenPlayer: () -> Unit,
    onAddEpg: () -> Unit,
    modifier: Modifier = Modifier,
    vm: GuideViewModel = koinViewModel(),
    // The category list, its counts and its hold menu are Live TV's own.
    live: LiveViewModel = koinViewModel(),
) {
    val tuner: LiveTuner = koinInject()
    val categories by live.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val followsLive by live.guideFollowsLive.collectAsStateWithLifecycle()
    // All channels or Catch-up the user hid is not left on screen: the guide moves to where Live TV
    // lands. An empty list is the one not loaded yet.
    LaunchedEffect(categories, selected) {
        if ((selected == LiveKey.All || selected == LiveKey.Catchup) && categories.isNotEmpty() && categories.none { it.key == selected }) {
            vm.select(tv.own.owntv.core.live.liveLandingKey(categories, { it.key }, { it.sealed }))
        }
    }
    val channels = vm.channels.collectAsLazyPagingItems()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val storedMode by vm.viewMode.collectAsStateWithLifecycle()
    val density by vm.densityPct.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val summary by vm.matchSummary.collectAsStateWithLifecycle()
    val showPreview by vm.showPreview.collectAsStateWithLifecycle()
    val sort by vm.sortGuide.collectAsStateWithLifecycle()
    val playing by tuner.channel.collectAsStateWithLifecycle()
    val nowMs by rememberClockTick()

    // Grid unless the user picked otherwise (the phone's old default was On now: the grid did not fit).
    val mode = storedMode ?: SettingsRepository.GuideView.GRID

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = "guide", scrollToTop = scrollToTop)
    val timeScroll = rememberScrollState()

    var picked by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var sheetFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var channelMenuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var categoryMenuFor by remember { mutableStateOf<LiveCategory?>(null) }
    val lock = tv.own.owntv.mobile.ui.categorylock.rememberCategoryLock(tv.own.owntv.core.model.MediaType.LIVE)
    var renamingFor by remember { mutableStateOf<LiveCategory?>(null) }
    var dayOpen by remember { mutableStateOf(false) }
    var orderOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(query.isNotEmpty()) }

    // A different category, day or search is a different list; the old scroll position means nothing.
    LaunchedEffect(selected, day, query, mode) { listState.scrollToItem(0) }

    // The crumb: "Sky Cinema · Today" (On now: "· On now"); a tap opens the categories.
    val categoryLabel = categories.firstOrNull { it.key == selected }?.label().orEmpty()
    val dayLabel = dayLabel(day)
    val crumbRest = if (mode == SettingsRepository.GuideView.ON_NOW) stringResource(R.string.home_row_on_now) else dayLabel
    ProvidePageCrumb { owner -> PageCrumb(categoryLabel, crumbRest, chevron = false, owner = owner) { categoriesOpen = true } }

    // The preview plays what was picked here, so leaving the guide stops it — except into full screen or
    // the player's mini-player, as Live TV's preview.
    var tunedHere by remember { mutableStateOf(false) }
    var toFullscreen by remember { mutableStateOf(false) }
    val miniRequested = LocalMiniRequested.current
    DisposableEffect(Unit) { onDispose { if (tunedHere && !toFullscreen && !miniRequested.value) tuner.stop() } }
    val streamOnScreen = LocalStreamOnScreen.current
    val previewShowing = mode == SettingsRepository.GuideView.GRID && showPreview && playing != null && playing?.id == picked?.first?.id
    DisposableEffect(previewShowing) {
        streamOnScreen.value = previewShowing
        onDispose { streamOnScreen.value = false }
    }
    BackHandler(enabled = tunedHere && playing != null) { tuner.stop(); tunedHere = false }
    // Only the grid has the preview: On now and Timeline would play it with no picture on screen.
    LaunchedEffect(mode) {
        if (mode != SettingsRepository.GuideView.GRID && tunedHere) {
            tuner.stop()
            tunedHere = false
        }
    }
    val openFullscreen = { toFullscreen = true; onOpenPlayer() }
    fun preview(channel: ChannelEntity) {
        if (!showPreview || playing?.id == channel.id) return
        tunedHere = true
        tuner.tune(channel.id)
    }
    fun watch(channel: ChannelEntity) {
        tunedHere = true
        if (playing?.id != channel.id) tuner.tune(channel.id)
        openFullscreen()
    }

    val reminderRows by vm.reminderRows.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val marks = remember(reminderRows, recordingRows) {
        GuideMarks(
            reminders = reminderRows.mapTo(HashSet()) { it.channelId to it.startMs },
            recordings = recordingRows
                .filter { it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.SCHEDULED }
                .mapTo(HashSet()) { it.channelId to it.programmeStartMs },
        )
    }

    // Before a pick, the top block shows what is on the first channel now.
    val first = channels.itemSnapshotList.items.firstOrNull()
    val fallback by produceState<Pair<ChannelEntity, EpgProgrammeEntity>?>(null, first?.id, day, nowMs / NOW_STEP_MS) {
        val ch = first ?: return@produceState run { value = null }
        val rows = vm.row(ch)
        val p = rows.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs } ?: rows.firstOrNull { it.stopMs > nowMs }
        value = p?.let { ch to it }
    }
    val top = picked ?: fallback

    // "Start on: Last channel · TV Guide": its category, its row scrolled to, and what is on it now picked
    // and previewing, as a tap on the channel does. Only the grid has the preview; other views just show it.
    // Otherwise, with "TV Guide follows Live TV" on, the guide opens on Live TV's remembered category.
    val startupSelection: StartupLiveSelection = koinInject()
    val settings: SettingsRepository = koinInject()
    LaunchedEffect(Unit) {
        val channel = startupSelection.consumeChannel(guide = true)
        if (channel == null) {
            live.guideOpeningKey()?.let(vm::select)
            return@LaunchedEffect
        }
        vm.select(live.categoryKeyOf(channel))
        withTimeoutOrNull(STARTUP_ROW_WAIT_MS) {
            snapshotFlow { channels.itemSnapshotList.items.indexOfFirst { it.id == channel.id } }.first { it >= 0 }
        }?.let { listState.scrollToItem(it) }
        val now = System.currentTimeMillis()
        val onNow = vm.row(channel).firstOrNull { it.startMs <= now && now < it.stopMs } ?: return@LaunchedEffect
        picked = channel to onNow
        val grid = (settings.guideView.first() ?: SettingsRepository.GuideView.GRID) == SettingsRepository.GuideView.GRID
        if (grid && settings.guidePreview.first()) preview(channel)
    }

    // Now: back to today, the grid centred on the now-line (it opens so too).
    fun jumpToNow() = vm.requestNow()
    val nowRequests by vm.nowRequests.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().imePadding()) {
        if (mode == SettingsRepository.GuideView.GRID) {
            top?.let { (ch, p) ->
                GuideTopBlock(
                    channel = ch,
                    programme = p,
                    nowMs = nowMs,
                    tuner = tuner,
                    playing = showPreview && playing?.id == ch.id,
                    vm = vm,
                    onVideo = { if (showPreview && playing?.id == ch.id) openFullscreen() else if (showPreview) preview(ch) else watch(ch) },
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
                )
            }
        }
        val toolsTop = if (mode == SettingsRepository.GuideView.GRID) 12.dp else TOOLS_TOP
        if (searching) {
            val keyboard = LocalSoftwareKeyboardController.current
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = toolsTop, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageSearchField(
                    value = query,
                    onValueChange = vm::setQuery,
                    placeholder = stringResource(R.string.content_epg_search_hint),
                    inline = true,
                    onSearch = { keyboard?.hide() },
                    modifier = Modifier.weight(1f),
                )
                StageTool(
                    onClick = { vm.setQuery(""); searching = false },
                    icon = MobileIcons.Close,
                    contentDescription = stringResource(R.string.content_close),
                )
            }
        } else {
            GuideTools(
                dayLabel = dayLabel,
                categoryLabel = categoryLabel,
                nowLabelled = mode == SettingsRepository.GuideView.ON_NOW,
                showSearch = mode != SettingsRepository.GuideView.ON_NOW,
                categoriesOn = categoriesOpen,
                orderOn = orderOpen,
                onDay = { dayOpen = true },
                onNow = ::jumpToNow,
                onCategory = { categoriesOpen = true },
                onOrder = { orderOpen = true },
                onSearch = { searching = true },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = toolsTop, bottom = 10.dp),
            )
        }

        if (matching) {
            GuideNotice(stringResource(R.string.content_epg_matching))
        } else {
            summary?.takeIf { review.isEmpty() }?.let { s ->
                GuideNotice(epgMatchSummaryText(s), action = stringResource(R.string.common_done), onAction = vm::clearReview)
            }
        }
        // Not an empty state: the rows below are drawn, they are simply all blank until it is fixed.
        if (stats?.mismatchedIds == true) GuideNotice(stringResource(R.string.content_epg_mismatched_ids))

        Box(Modifier.weight(1f)) {
            if (categories.firstOrNull { it.key == selected }?.sealed == true) {
                tv.own.owntv.mobile.ui.categorylock.LockedCategoryState(onEnterPin = { lock.enterPin(selected) })
            } else if (channels.itemCount == 0) {
                EmptyGuide(
                    query = query,
                    stats = stats,
                    filterHidesEverything = sort == SettingsRepository.GuideSort.FAVORITES || sort == SettingsRepository.GuideSort.CATCHUP,
                    onAddEpg = onAddEpg,
                )
            } else {
                when (mode) {
                    SettingsRepository.GuideView.GRID -> GuideGrid(
                        vm = vm,
                        channels = channels,
                        listState = listState,
                        timeScroll = timeScroll,
                        window = window,
                        densityPct = density,
                        nowMs = nowMs,
                        marks = marks,
                        nowRequests = nowRequests,
                        picked = picked?.let { (c, p) -> c.id to p.startMs },
                        onTap = { ch, p ->
                            if (picked?.first?.id == ch.id && picked?.second?.startMs == p.startMs) {
                                sheetFor = ch to p
                            } else {
                                picked = ch to p
                                preview(ch)
                            }
                        },
                        onHold = { ch, p -> sheetFor = ch to p },
                        onChannel = { ch ->
                            vm.cachedRow(ch.id)?.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }?.let { picked = ch to it }
                            if (showPreview) preview(ch) else watch(ch)
                        },
                        onChannelHold = { channelMenuFor = it },
                    )
                    SettingsRepository.GuideView.ON_NOW -> OnNowList(
                        vm = vm,
                        channels = channels,
                        listState = listState,
                        favorites = favorites,
                        nowMs = nowMs,
                        onOpen = { ch, p -> sheetFor = ch to p },
                        onWatch = ::watch,
                        onMenu = { channelMenuFor = it },
                    )
                    SettingsRepository.GuideView.TIMELINE -> GuideTimeline(
                        vm = vm,
                        channels = channels,
                        listState = listState,
                        nowMs = nowMs,
                        onOpen = { ch, p -> sheetFor = ch to p },
                    )
                }
            }
        }
    }

    if (categoriesOpen) {
        val counts by live.categoryCounts.collectAsStateWithLifecycle()
        val showCounts by live.sheetCounts.collectAsStateWithLifecycle()
        val marksBySource by live.playlistMarks.collectAsStateWithLifecycle()
        LiveCategoriesSheet(
            categories = categories,
            selected = selected,
            counts = counts,
            showCounts = showCounts,
            marks = marksBySource,
            onSelect = { key ->
                vm.select(key)
                if (followsLive) live.select(key) // one remembered category for both
            },
            onHold = { categoryMenuFor = it },
            onDismiss = { categoriesOpen = false },
            title = stringResource(R.string.content_epg_guide_category),
            extra = stringResource(R.string.content_epg_category_hint),
        )
    }
    categoryMenuFor?.let { category ->
        LiveCategoryMenu(
            title = remember(category) { ProviderTags.parse(category.title.orEmpty()).name },
            onRename = { renamingFor = category },
            onHide = {
                lock.guarded(category.key, category.sealed, SealedCategoryAction.HIDE) {
                    live.hideCategory(category.key)
                    if (selected == category.key) vm.select(tv.own.owntv.core.live.liveLandingKey(categories.filter { it.key != category.key }, { it.key }, { it.sealed }))
                }
            },
            onDismiss = { categoryMenuFor = null },
        )
    }
    renamingFor?.let { category ->
        TextPromptDialog(
            title = stringResource(R.string.settings_customize_rename),
            initial = remember(category) { ProviderTags.parse(category.title.orEmpty()).name },
            onConfirm = { live.renameCategory(category.key, it.takeIf { t -> t.isNotBlank() }); renamingFor = null },
            onDismiss = { renamingFor = null },
        )
    }
    if (dayOpen) {
        val days by vm.guideDays.collectAsStateWithLifecycle()
        val past by vm.pastDays.collectAsStateWithLifecycle()
        GuideDaySheet(
            selected = day,
            days = days,
            past = past,
            onPick = { offset ->
                vm.selectDay(offset)
                // On now is always now: another day is read in the grid.
                if (offset != 0 && mode == SettingsRepository.GuideView.ON_NOW) vm.setViewMode(SettingsRepository.GuideView.GRID)
            },
            onDismiss = { dayOpen = false },
        )
    }
    if (orderOpen) {
        val showEmpty by vm.showEmpty.collectAsStateWithLifecycle()
        GuideOrderSheet(
            sort = sort,
            catchupOffered = (stats?.catchupChannels ?: 0) > 0,
            showEmpty = showEmpty,
            showPreview = showPreview,
            mode = mode,
            densityPct = density,
            guideChannels = stats?.guideChannels,
            matching = matching,
            onSort = vm::setSortGuide,
            onShowEmpty = vm::setShowEmpty,
            onShowPreview = { on ->
                vm.setShowPreview(on)
                if (!on && tunedHere) { tuner.stop(); tunedHere = false }
            },
            onMode = vm::setViewMode,
            onDensity = vm::setDensityPct,
            onAutoMatch = { orderOpen = false; vm.autoMatchEpg() },
            onDismiss = { orderOpen = false },
        )
    }
    if (review.isNotEmpty()) {
        val includeLogos by vm.includeGuideLogos.collectAsStateWithLifecycle()
        EpgReviewSheet(
            suggestions = review,
            matchedCount = (summary as? EpgMatchSummary.AutoMatched)?.applied,
            includeLogos = includeLogos,
            onIncludeLogos = vm::setIncludeGuideLogos,
            onAccept = vm::acceptSuggestion,
            onSkip = vm::skipSuggestion,
            onAcceptAll = vm::acceptAllSuggestions,
            onDone = vm::clearReview,
        )
    }
    channelMenuFor?.let { channel ->
        GuideChannelSheet(channel = channel, vm = vm, onDismiss = { channelMenuFor = null })
    }
    sheetFor?.let { (channel, programme) ->
        ProgrammeSheet(
            channel = channel,
            programme = programme,
            isFavorite = channel.id in favorites,
            nowMs = nowMs,
            vm = vm,
            onWatch = { watch(channel) },
            onWatchFromStart = {
                tunedHere = true
                vm.playCatchup(channel, programme)
                openFullscreen()
            },
            onDismiss = { sheetFor = null },
        )
    }
}

/** Live TV in Guide view: Today · Now · Guide view (on), over the guide's grid. */
@Composable
internal fun LiveGuideTools(guide: GuideViewModel, onGuideView: () -> Unit, modifier: Modifier = Modifier) {
    val day by guide.day.collectAsStateWithLifecycle()
    var dayOpen by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StageTool(onClick = { dayOpen = true }, icon = MobileIcons.CalendarMonth, value = dayLabel(day))
        StageTool(onClick = { guide.requestNow() }, icon = MobileIcons.Now, label = stringResource(R.string.content_epg_now_short))
        StageTool(onClick = onGuideView, icon = MobileIcons.Guide, label = stringResource(R.string.content_live_guide_view), on = true)
    }
    if (dayOpen) {
        val days by guide.guideDays.collectAsStateWithLifecycle()
        val past by guide.pastDays.collectAsStateWithLifecycle()
        GuideDaySheet(selected = day, days = days, past = past, onPick = guide::selectDay, onDismiss = { dayOpen = false })
    }
}

/**
 * Live TV's Guide view grid: Live TV's own category, read through the guide's rows. A tap on a cell
 * opens the programme sheet; the channel column plays the channel in Live TV's preview.
 */
@Composable
internal fun LiveGuideGrid(guide: GuideViewModel, category: LiveKey, onTune: (ChannelEntity) -> Unit, onOpenPlayer: () -> Unit) {
    LaunchedEffect(category) {
        guide.useLiveList()
        guide.select(category)
    }
    val channels = guide.channels.collectAsLazyPagingItems()
    val window by guide.window.collectAsStateWithLifecycle()
    val density by guide.densityPct.collectAsStateWithLifecycle()
    val favorites by guide.favoriteIds.collectAsStateWithLifecycle()
    val day by guide.day.collectAsStateWithLifecycle()
    val nowMs by rememberClockTick()
    val listState = rememberLazyListState()
    val timeScroll = rememberScrollState()
    val tuner: LiveTuner = koinInject()
    var sheetFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var channelMenuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    val nowRequests by guide.nowRequests.collectAsStateWithLifecycle()
    GuideGrid(
        vm = guide,
        channels = channels,
        listState = listState,
        timeScroll = timeScroll,
        window = window,
        densityPct = density,
        nowMs = nowMs,
        marks = GuideMarks(),
        nowRequests = nowRequests,
        picked = null,
        onTap = { ch, p -> sheetFor = ch to p },
        onHold = { ch, p -> sheetFor = ch to p },
        onChannel = onTune,
        onChannelHold = { channelMenuFor = it },
    )
    channelMenuFor?.let { channel -> GuideChannelSheet(channel = channel, vm = guide, onDismiss = { channelMenuFor = null }) }
    sheetFor?.let { (channel, programme) ->
        ProgrammeSheet(
            channel = channel,
            programme = programme,
            isFavorite = channel.id in favorites,
            nowMs = nowMs,
            vm = guide,
            onWatch = { if (tuner.channel.value?.id != channel.id) tuner.tune(channel.id); onOpenPlayer() },
            onWatchFromStart = { guide.playCatchup(channel, programme); onOpenPlayer() },
            onDismiss = { sheetFor = null },
        )
    }
}

/** "Today", "Tomorrow", "Yesterday", else "Wed 14 Oct" in the locale's own order. */
@Composable
private fun dayLabel(offset: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (offset) {
        0 -> stringResource(R.string.content_epg_today)
        1 -> stringResource(R.string.content_epg_tomorrow)
        -1 -> stringResource(R.string.content_epg_yesterday)
        else -> remember(locale, offset) {
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
            SimpleDateFormat(pattern, locale).format(cal.time)
        }
    }
}

/** The tools: Today ⌄ · Now · ≋ category ⌄ · order · search (not in On now). */
@Composable
private fun GuideTools(
    dayLabel: String,
    categoryLabel: String,
    nowLabelled: Boolean,
    showSearch: Boolean,
    categoriesOn: Boolean,
    orderOn: Boolean,
    onDay: () -> Unit,
    onNow: () -> Unit,
    onCategory: () -> Unit,
    onOrder: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StageTool(onClick = onDay, icon = MobileIcons.CalendarMonth, value = dayLabel, trailingIcon = MobileIcons.KeyboardArrowDown)
        StageTool(
            onClick = onNow,
            icon = MobileIcons.Now,
            label = if (nowLabelled) stringResource(R.string.content_epg_now_short) else null,
            contentDescription = if (nowLabelled) null else stringResource(R.string.content_epg_jump_now),
        )
        StageTool(
            onClick = onCategory,
            icon = MobileIcons.Layers,
            value = categoryLabel,
            trailingIcon = MobileIcons.KeyboardArrowDown,
            on = categoriesOn,
            modifier = Modifier.weight(1f, fill = false),
        )
        StageTool(onClick = onOrder, icon = MobileIcons.Sort, on = orderOn, contentDescription = stringResource(R.string.content_epg_order_title))
        if (showSearch) StageTool(onClick = onSearch, icon = MobileIcons.Search, contentDescription = stringResource(R.string.content_epg_search_hint))
    }
}

/**
 * The top block: the 132 × 88 preview (radius 14) with LIVE and "259 · Family" — the channel's
 * logo when Video preview is off or it is not playing — then the eyebrow "◷ Starts in 58 min · 20:05 –
 * 21:40 · 95 min", the title (21 / 800), the details (11 muted), and Remind me (accent tint) · Record.
 */
@Composable
private fun GuideTopBlock(
    channel: ChannelEntity,
    programme: EpgProgrammeEntity,
    nowMs: Long,
    tuner: LiveTuner,
    playing: Boolean,
    vm: GuideViewModel,
    onVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent
    val full by produceState(programme, programme.id, programme.startMs) { value = vm.fullProgramme(programme) }
    val reminderRows by vm.reminderRows.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val reminderSet = reminderRows.any { it.channelId == channel.id && it.startMs == programme.startMs }
    val recording = remember(recordingRows, channel.id, programme.startMs) { vm.recordingFor(channel, programme) }
    val requestReminder = rememberReminderRequest()
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(132.dp, 88.dp)
                .clip(shape)
                .background(Color.Black)
                .clickable(onClick = onVideo),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogoImage(
                url = channel.displayLogoUrl,
                modifier = Modifier.size(64.dp, 44.dp),
                plateOn = Color.Black,
                fallback = { StageIcon(MobileIcons.LiveTv, 22.dp, Color(0xFFA7B3AE)) },
            )
            // Covered, not removed, under a sheet — as Live TV's preview (a new surface waits for a keyframe).
            if (playing) VideoStage(player = tuner.player, modifier = Modifier.fillMaxSize())
            if (playing && sheetOpen()) Box(Modifier.fillMaxSize().background(Color.Black))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
            if (!palette.isLight) Box(Modifier.fillMaxSize().drawBehind { drawInnerRing(Color.White.copy(alpha = 0.08f), 1.dp.toPx(), 14.dp.toPx()) })
            StageLiveBadge(stringResource(R.string.player_live), Modifier.align(Alignment.TopStart).padding(6.dp))
            val name = remember(channel.name) { ProviderTags.parse(channel.name).name }
            Text(
                listOfNotNull(channel.number?.takeIf { it > 0 }?.let(::formatYear), name).joinToString(stringResource(R.string.content_epg_bits_separator)),
                style = stageText(10f, 700),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                StageIcon(MobileIcons.Schedule, 12.dp, accent.accent)
                Text(programmeEyebrow(programme, nowMs), style = stageText(10.5f, 700), color = accent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                programme.title,
                style = stageText(21f, 800),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            programmeDetailsLine(full)?.let {
                Text(it, style = stageText(11f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Remind me only for what is still to come; set, it reads "Reminder set" and a tap cancels.
                if (programme.startMs > nowMs) {
                    ReminderButton(
                        set = reminderSet,
                        onClick = { requestReminder(reminderSet) { vm.toggleReminder(channel, programme) } },
                    )
                }
                if (vm.canRecord(channel, programme) || recording != null) {
                    StageButton(
                        onClick = {
                            when (recording?.status) {
                                RecordingStatus.RECORDING -> vm.stopRecording(recording)
                                RecordingStatus.SCHEDULED -> vm.cancelRecording(recording)
                                else -> vm.record(channel, programme)
                            }
                        },
                        text = stringResource(recordLabel(recording?.status, programme, nowMs)),
                        icon = MobileIcons.Record,
                        small = true,
                    )
                }
            }
        }
    }
}

/** `.btn.sm` with the accent text on an accent-16 % fill — the top block's Remind me. */
@Composable
private fun ReminderButton(set: Boolean, onClick: () -> Unit) {
    val accent = stageAccent
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(accent.accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageIcon(if (set) MobileIcons.Check else MobileIcons.Bell, 16.dp, accent.accent)
        Text(
            stringResource(if (set) R.string.content_reminder_set else R.string.content_remind_me),
            style = stageText(12.5f, 700),
            color = accent.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Record, or what stops or cancels the recording already there — "Record from catch-up" once it has aired. */
private fun recordLabel(status: RecordingStatus?, programme: EpgProgrammeEntity, nowMs: Long): Int = when (status) {
    RecordingStatus.RECORDING -> R.string.recording_stop
    RecordingStatus.SCHEDULED -> R.string.common_cancel
    else -> if (programme.stopMs <= nowMs) R.string.recording_from_archive else R.string.recording_record
}

/**
 * Set or clear a reminder — and, the first time one is set on Android 13+, ask for the notification
 * permission so it can come while the app is closed. Refused, the reminder still comes in the app.
 */
@Composable
private fun rememberReminderRequest(): (alreadySet: Boolean, toggle: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { alreadySet, toggle ->
        val needs = !alreadySet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            pending = toggle
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggle()
        }
    }
}

/** "Starts in 58 min · 20:05 – 21:40 · 95 min" — or "Live now · …", or just the times once it has been on. */
@Composable
internal fun programmeEyebrow(p: EpgProgrammeEntity, now: Long, channelName: String? = null): String {
    val times = rememberGuideTimeFormat()
    val range = stringResource(R.string.content_live_time_range_plain, times.format(Date(p.startMs)), times.format(Date(p.stopMs)))
    val lead = when {
        p.startMs > now -> stringResource(R.string.content_epg_starts_in, durationText(((p.startMs - now) + 59_999) / 60_000))
        p.stopMs > now -> stringResource(R.string.content_live_now)
        else -> null
    }
    val length = ((p.stopMs - p.startMs) / 60_000L).toInt().takeIf { it > 0 }?.let { stringResource(R.string.player_duration_minutes, it) }
    return listOfNotNull(lead, range, length, channelName).joinToString(stringResource(R.string.content_epg_bits_separator))
}

/** "Animation · Family · 2023 · FSK 0" — only what the feed provides (the length is in the eyebrow). */
@Composable
private fun programmeDetailsLine(p: EpgProgrammeEntity): String? {
    val d = p.details
    return (d.categoryList + listOfNotNull(d.episode, d.year?.let(::formatYear), d.rating))
        .takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.content_epg_bits_separator))
}

/**
 * On now: `.chrow` 70 dp — the logo, the programme (14.5 / 700), "255 · Sky Cinema Highlights ·
 * 2 h 3 min left", the progress at 70 %, and what is next with its time in the accent; ♥ for a favourite.
 */
@Composable
private fun OnNowList(
    vm: GuideViewModel,
    channels: LazyPagingItems<ChannelEntity>,
    listState: LazyListState,
    favorites: Set<Long>,
    nowMs: Long,
    onOpen: (ChannelEntity, EpgProgrammeEntity) -> Unit,
    onWatch: (ChannelEntity) -> Unit,
    onMenu: (ChannelEntity) -> Unit,
) {
    val onNow by vm.onNow.collectAsStateWithLifecycle()
    val revision by vm.revision.collectAsStateWithLifecycle()
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val times = rememberGuideTimeFormat()
    val sep = stringResource(R.string.content_epg_bits_separator)

    // A new match clears what was read, so the rows on screen ask again — and so does the clock moving
    // on, which is what makes a finished programme give way to the one after it.
    LaunchedEffect(listState, channels, revision, nowMs) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices -> vm.loadOnNow(indices.mapNotNull { channels.peekOrNull(it) }) }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 6.dp)) {
        items(count = channels.itemCount, key = channels.itemKey { it.id }, contentType = { "onnow" }) { index ->
            val channel = channels[index] ?: return@items
            val slot = onNow[channel.id]
            val now = slot?.now?.takeIf { it.stopMs > nowMs }
            val next = slot?.next?.takeIf { it.startMs >= nowMs || now == null }
            val name = remember(channel.name) { ProviderTags.parse(channel.name).name }
            val number = channel.number?.takeIf { it > 0 }?.let(::formatYear)
            StageRow(
                onClick = { if (now != null) onOpen(channel, now) else onWatch(channel) },
                onLongClick = { onMenu(channel) },
                height = 70.dp,
            ) {
                Box(Modifier.size(52.dp, 38.dp).clip(RoundedCornerShape(StageRadii.Plate)), contentAlignment = Alignment.Center) {
                    ChannelLogoImage(
                        url = channel.displayLogoUrl,
                        modifier = Modifier.fillMaxSize(),
                        plateOn = palette.page,
                        fallback = { StageIcon(MobileIcons.LiveTv, 19.dp, palette.dim) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(now?.title ?: name, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = when {
                        now != null -> listOfNotNull(number, name, timeLeftText(((now.stopMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000)).joinToString(sep)
                        // Answered, and nothing is on: the feed's gap, or no guide at all.
                        slot != null -> listOfNotNull(number, stringResource(if (next != null) R.string.content_epg_no_programme else R.string.content_epg_no_guide)).joinToString(sep)
                        else -> listOfNotNull(number, name).joinToString(sep)
                    }
                    Text(line, style = stageText(12f, 400), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                    if (now != null) {
                        val span = (now.stopMs - now.startMs).coerceAtLeast(1)
                        StageProgress((nowMs - now.startMs).toFloat() / span, Modifier.padding(top = 6.dp).fillMaxWidth(0.7f), thin = true)
                    }
                    if (next != null) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(times.format(Date(next.startMs))) }
                                append(' ')
                                append(next.title)
                            },
                            style = stageText(11f, 400),
                            color = palette.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                if (channel.id in favorites) StageIcon(MobileIcons.Favorite, 16.dp, accent)
            }
        }
    }
}

/**
 * The programme sheet: the title and "20:05 – 21:40 · 259 · Sky Cinema Family", the synopsis, then
 * NEW Remind me (5 min before) · WATCH Watch channel, Watch from start ("after 20:05" until it can),
 * Play catch-up in… · RECORD Record, Record every showing, Record from catch-up · CHANNEL favourite, Pick
 * EPG manually, EPG time offset — the television's menu.
 */
@Composable
internal fun ProgrammeSheet(
    channel: ChannelEntity,
    programme: EpgProgrammeEntity,
    isFavorite: Boolean,
    nowMs: Long,
    vm: GuideViewModel,
    onWatch: () -> Unit,
    onWatchFromStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStagePalette.current
    val locale = Locale.current.platformLocale
    val times = rememberGuideTimeFormat()
    val full by produceState(programme, programme.id) { value = vm.fullProgramme(programme) }
    val reminderRows by vm.reminderRows.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val lead by vm.reminderLead.collectAsStateWithLifecycle()
    val catchupPlayer by vm.catchupPlayer.collectAsStateWithLifecycle()
    val reminderSet = reminderRows.any { it.channelId == channel.id && it.startMs == programme.startMs }
    val recording = remember(recordingRows, channel.id, programme.startMs) { vm.recordingFor(channel, programme) }
    val seriesRule by produceState<tv.own.owntv.core.database.entity.RecordingRuleEntity?>(null, channel.id, programme.title, recordingRows) {
        value = vm.seriesRuleFor(channel, programme)
    }
    val clash by produceState<String?>(null, channel.id, programme.startMs, recordingRows) { value = vm.clashFor(channel, programme) }
    val requestReminder = rememberReminderRequest()
    val upcoming = programme.stopMs > nowMs
    val canCatchup = vm.canCatchup(channel, programme)
    val name = remember(channel.name) { ProviderTags.parse(channel.name).name }
    val sep = stringResource(R.string.content_epg_bits_separator)
    var subSheet by remember { mutableStateOf<GuideChannelDialog?>(null) }

    if (subSheet == null) {
        MobileBottomSheet(onDismissRequest = onDismiss, title = programme.title) {
            StageSheetHeader(
                title = programme.title,
                subtitle = listOfNotNull(
                    stringResource(R.string.content_live_time_range_plain, times.format(Date(programme.startMs)), times.format(Date(programme.stopMs))),
                    channel.number?.takeIf { it > 0 }?.let(::formatYear),
                    name,
                ).joinToString(sep),
            )
            full.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = stageText(12.5f, 500, lineHeight = 1.5f),
                    color = palette.body,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (programme.startMs > nowMs) {
                    StageGroupLabel(stringResource(R.string.content_menu_group_new).uppercase(locale))
                    StageMenuItem(
                        stringResource(if (reminderSet) R.string.content_reminder_set else R.string.content_remind_me),
                        onClick = { requestReminder(reminderSet) { vm.toggleReminder(channel, programme) }; onDismiss() },
                        icon = MobileIcons.Bell,
                        value = if (reminderSet) null else reminderLeadText(lead),
                        checked = reminderSet,
                    )
                }
                StageGroupLabel(stringResource(R.string.content_menu_group_watch).uppercase(locale))
                StageMenuItem(stringResource(R.string.content_epg_watch_channel), onClick = { onDismiss(); onWatch() }, icon = MobileIcons.LiveTv)
                if (channel.catchup) {
                    StageMenuItem(
                        stringResource(R.string.content_epg_watch_start),
                        onClick = { onDismiss(); onWatchFromStart() },
                        icon = MobileIcons.Catchup,
                        enabled = canCatchup,
                        value = if (!canCatchup && programme.startMs > nowMs) {
                            stringResource(R.string.content_epg_after_time, times.format(Date(programme.startMs)))
                        } else null,
                    )
                    // Steps through the three choices in place: the value is the setting, the sheet stays open.
                    StageMenuItem(
                        stringResource(R.string.settings_catchup_player) + "…",
                        onClick = {
                            val all = SettingsRepository.CatchupPlayer.entries
                            vm.setCatchupPlayer(all[(all.indexOf(catchupPlayer) + 1) % all.size])
                        },
                        icon = MobileIcons.PlayCircle,
                        value = stringResource(
                            when (catchupPlayer) {
                                SettingsRepository.CatchupPlayer.ASK -> R.string.settings_catchup_player_ask
                                SettingsRepository.CatchupPlayer.INTERNAL -> R.string.settings_catchup_player_internal
                                SettingsRepository.CatchupPlayer.EXTERNAL -> R.string.settings_catchup_player_external
                            },
                        ),
                    )
                }
                if (vm.canRecord(channel, programme) || recording != null) {
                    StageGroupLabel(stringResource(R.string.content_menu_group_record).uppercase(locale))
                    StageMenuItem(
                        stringResource(recordLabel(recording?.status, programme, nowMs)),
                        onClick = {
                            when (recording?.status) {
                                RecordingStatus.RECORDING -> vm.stopRecording(recording)
                                RecordingStatus.SCHEDULED -> vm.cancelRecording(recording)
                                else -> vm.record(channel, programme)
                            }
                            onDismiss()
                        },
                        icon = if (programme.stopMs <= nowMs && recording == null) MobileIcons.Catchup else MobileIcons.Record,
                        // The clash, said before committing.
                        value = clash?.takeIf { recording == null && upcoming }?.let { stringResource(R.string.recording_clash_with, it) },
                    )
                    // "Every showing" only for a programme still to come — a rule is about the future.
                    if (upcoming) {
                        StageMenuItem(
                            stringResource(if (seriesRule != null) R.string.recording_stop_series else R.string.recording_record_series),
                            onClick = { seriesRule?.let { vm.stopSeries(it) } ?: vm.recordSeries(channel, programme); onDismiss() },
                            icon = MobileIcons.Record,
                        )
                    }
                }
                StageGroupLabel(stringResource(R.string.content_menu_group_channel).uppercase(locale))
                StageMenuItem(
                    stringResource(if (isFavorite) R.string.content_epg_remove_favourite else R.string.content_epg_add_favourite),
                    onClick = { vm.toggleFavorite(channel); onDismiss() },
                    icon = if (isFavorite) MobileIcons.Favorite else MobileIcons.FavoriteBorder,
                )
                StageMenuItem(stringResource(R.string.content_epg_pick_manually), onClick = { subSheet = GuideChannelDialog.MATCH_EPG }, icon = MobileIcons.Guide)
                StageMenuItem(
                    stringResource(R.string.content_epg_time_offset),
                    onClick = { subSheet = GuideChannelDialog.EPG_OFFSET },
                    icon = MobileIcons.Schedule,
                    value = vm.currentEpgShift(channel)?.let { epgShiftLabel(it) } ?: stringResource(R.string.content_epg_offset_global_short),
                )
            }
        }
    }
    subSheet?.let { GuideChannelFollowUp(it, channel, vm, onDismiss) }
}

/** "5 min before", or "At the start". */
@Composable
internal fun reminderLeadText(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.settings_reminder_at_start)
    else stringResource(R.string.content_remind_before, stringResource(R.string.player_duration_minutes, minutes))

/** Today first, then the days the guide keeps. */
@Composable
private fun GuideDaySheet(selected: Int, days: Int, past: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.content_epg_today)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Oldest first, as the days run; [past] days back only when there is catch-up to replay them.
            (-past until days).forEach { offset ->
                StageMenuItem(dayLabel(offset), onClick = { onPick(offset); onDismiss() }, icon = MobileIcons.CalendarMonth, filled = offset == selected)
            }
        }
    }
}

/**
 * Order + view: "Order channels by · Opens this way next time", the orders with ✓ on the current
 * one; SHOW Channels without guide, Video preview; VIEW Grid · On now · Timeline; SIZE (grid); GUIDE DATA
 * Auto-match EPG with how many guide channels there are.
 */
@Composable
private fun GuideOrderSheet(
    sort: SettingsRepository.GuideSort,
    catchupOffered: Boolean,
    showEmpty: Boolean,
    showPreview: Boolean,
    mode: SettingsRepository.GuideView,
    densityPct: Int,
    guideChannels: Int?,
    matching: Boolean,
    onSort: (SettingsRepository.GuideSort) -> Unit,
    onShowEmpty: (Boolean) -> Unit,
    onShowPreview: (Boolean) -> Unit,
    onMode: (SettingsRepository.GuideView) -> Unit,
    onDensity: (Int) -> Unit,
    onAutoMatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = Locale.current.platformLocale
    val title = stringResource(R.string.content_epg_order_title)
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title, subtitle = stringResource(R.string.content_epg_order_hint))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Provider is the playlist's order — the stored "Live TV" order is the same thing.
            val current = if (sort == SettingsRepository.GuideSort.LIVE_TV) SettingsRepository.GuideSort.PROVIDER else sort
            listOfNotNull(
                Triple(SettingsRepository.GuideSort.PROVIDER, R.string.content_epg_sort_provider, MobileIcons.Layers),
                Triple(SettingsRepository.GuideSort.ALPHA, R.string.content_epg_sort_alpha, MobileIcons.FormatListBulleted),
                // On the phone these two narrow the guide to those channels (see GuideViewModel.applyGuideSort),
                // so they keep the names that say so rather than the television's "… first".
                Triple(SettingsRepository.GuideSort.CATCHUP, R.string.content_epg_sort_catchup, MobileIcons.Catchup).takeIf { catchupOffered },
                Triple(SettingsRepository.GuideSort.FAVORITES, R.string.content_epg_sort_favorites, MobileIcons.FavoriteBorder),
            ).forEach { (entry, label, icon) ->
                StageMenuItem(stringResource(label), onClick = { onSort(entry) }, icon = icon, filled = entry == current, checked = entry == current)
            }
            StageGroupLabel(stringResource(R.string.content_menu_group_show).uppercase(locale))
            StageSettingRow(
                title = stringResource(R.string.content_epg_show_empty),
                icon = MobileIcons.Guide,
                minHeight = 48.dp,
                role = androidx.compose.ui.semantics.Role.Switch,
                onClick = { onShowEmpty(!showEmpty) },
            ) { StageSwitch(showEmpty) }
            StageSettingRow(
                title = stringResource(R.string.content_epg_show_preview),
                icon = MobileIcons.LiveTv,
                minHeight = 48.dp,
                role = androidx.compose.ui.semantics.Role.Switch,
                onClick = { onShowPreview(!showPreview) },
            ) { StageSwitch(showPreview) }
            StageGroupLabel(stringResource(R.string.content_menu_group_view).uppercase(locale))
            val views = listOf(SettingsRepository.GuideView.GRID, SettingsRepository.GuideView.ON_NOW, SettingsRepository.GuideView.TIMELINE)
            StageChoiceRow(
                options = listOf(
                    stringResource(R.string.settings_view_grid),
                    stringResource(R.string.home_row_on_now),
                    stringResource(R.string.content_epg_view_timeline),
                ),
                selected = views.indexOf(mode),
                onSelect = { onMode(views[it]) },
            )
            if (mode == SettingsRepository.GuideView.GRID) {
                StageGroupLabel(stringResource(R.string.settings_size).uppercase(locale))
                // 70 % at the left, 130 % at the right.
                StageSizeBar(
                    fraction = (densityPct - MIN_DENSITY).toFloat() / (MAX_DENSITY - MIN_DENSITY),
                    onChange = { onDensity((MIN_DENSITY + it * (MAX_DENSITY - MIN_DENSITY)).toInt()) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            StageGroupLabel(stringResource(R.string.content_menu_group_guide_data).uppercase(locale))
            StageMenuItem(
                stringResource(R.string.content_epg_match_button),
                onClick = { if (!matching) onAutoMatch() },
                icon = MobileIcons.AutoAwesome,
                enabled = !matching,
                value = guideChannels?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sync_count_channels, it, formatCount(it)) },
            )
        }
    }
}

/** Nothing to draw — and which of the four reasons decides what the user can do about it. */
@Composable
private fun EmptyGuide(query: String, stats: GuideStats?, filterHidesEverything: Boolean, onAddEpg: () -> Unit) {
    val palette = LocalStagePalette.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val noEpg = stats?.hasEpgSources == false
        val filtered = filterHidesEverything && (stats?.programmes ?: 0) > 0
        Text(
            when {
                query.isNotBlank() -> stringResource(R.string.content_epg_no_channels_query, query)
                filtered -> stringResource(R.string.content_epg_filter_hides_all)
                noEpg -> stringResource(R.string.content_epg_empty)
                else -> stringResource(R.string.content_epg_add_playlist)
            },
            style = stageText(13.5f, 600),
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
        if (query.isBlank() && noEpg && !filtered) {
            Text(
                stringResource(R.string.content_epg_add_description),
                style = stageText(12f, 500),
                color = palette.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
            StageButton(onClick = onAddEpg, text = stringResource(R.string.content_epg_add), style = StageButtonStyle.PRIMARY)
        }
    }
}

/** One line under the tools: matching, what a run did (with Done), or the mismatched-ids warning. */
@Composable
private fun GuideNotice(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val palette = LocalStagePalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = stageText(12f, 500), color = palette.muted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) StageTool(onClick = onAction, value = action)
    }
}

/** Programme clocks follow the phone's own locale, so 20:00 and 8:00 PM are both right somewhere. */
@Composable
internal fun rememberGuideTimeFormat(): DateFormat {
    val locales = LocalConfiguration.current.locales
    return remember(locales) { DateFormat.getTimeInstance(DateFormat.SHORT) }
}

internal const val MIN_DENSITY = 70
internal const val MAX_DENSITY = 130

/** The room between the crumb and the tools when there is no top block (`.tools` at 112 under a 102 dp header). */
private val TOOLS_TOP = 10.dp


/** The top block's default re-reads its programme this often. */
private const val NOW_STEP_MS = 60_000L
