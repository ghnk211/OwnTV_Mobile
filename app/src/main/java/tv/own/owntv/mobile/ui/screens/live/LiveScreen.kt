package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.layout.widthIn
import tv.own.owntv.mobile.ui.screens.home.formatYear
import tv.own.owntv.mobile.ui.player.CatchupOptions
import tv.own.owntv.mobile.ui.player.CatchupSheet as JumpBackSheet
import androidx.activity.compose.BackHandler
import tv.own.owntv.mobile.ui.shell.LocalMiniRequested
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.ui.components.peekOrNull
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.screens.home.timeLeftText
import tv.own.owntv.mobile.ui.screens.settings.customize.TextPromptDialog
import tv.own.owntv.mobile.ui.shell.ProvidePageCrumb
import tv.own.owntv.mobile.ui.shell.PageCrumb
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StagePlaylistMark
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.paging.compose.LazyPagingItems
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.mobile.ui.screens.guide.LiveGuideGrid
import tv.own.owntv.mobile.ui.screens.guide.LiveGuideTools
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.categorylock.SealedCategoryAction
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.home.groupedCountText
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.TwoPane
import tv.own.owntv.mobile.ui.nav.isExpandedWidth
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * Live TV: a strip of categories, a list of channels, and a long-press menu on each one.
 *
 * The TV app shows three panels at once — rail, list, preview — because it has the width for it and
 * a remote that moves between them. A phone has neither, so the same three things become one
 * scrolling list, a chip strip above it, and a screen you open by tapping a channel.
 *
 * A tablet has the width, so at expanded size the third panel comes back: the list on the left and
 * the channel playing beside it. It starts empty rather than tuning the first channel by itself —
 * opening Live TV is not a request to watch something.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    scrollToTop: SharedFlow<String>,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    vm: LiveViewModel = koinViewModel(),
) {
    // Channels kept with "Add to Multiview" are spent by playing one. While any are waiting, a tap
    // plays rather than browses — see the tap handler below.
    val tuner: LiveTuner = koinInject()
    val multiviewSelection by tuner.multiviewSelection.collectAsStateWithLifecycle()
    val multiviewPending = multiviewSelection.isNotEmpty()

    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val marks by vm.playlistMarks.collectAsStateWithLifecycle()
    val showNumbers by vm.showChannelNumbers.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val providers by vm.providerNames.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = "live", scrollToTop = scrollToTop)

    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    val lock = tv.own.owntv.mobile.ui.categorylock.rememberCategoryLock(tv.own.owntv.core.model.MediaType.LIVE)
    var categoryPicker by remember { mutableStateOf(false) }
    var categoryMenuFor by remember { mutableStateOf<LiveCategory?>(null) }
    var renamingFor by remember { mutableStateOf<LiveCategory?>(null) }
    var catchupFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var jumpBackOpen by remember { mutableStateOf(false) }
    // Wide: the list on the left, the preview and its programme on the right — the same preview
    // the phone draws on top, so a tap plays and a second tap is full screen there too.
    val twoPane = isExpandedWidth()

    // While the pane is showing the channel, the picture is already on screen, so the shell must not
    // also put a floating window over it. Cleared on the way out, or leaving Live TV with a channel
    // open would take the mini player away on every other screen too.
    val streamOnScreen = LocalStreamOnScreen.current
    val playing by tuner.channel.collectAsStateWithLifecycle()
    val playingNowNext by tuner.nowNext.collectAsStateWithLifecycle()
    // A tap plays the channel in a preview (on top of Live TV on a phone, beside it when wide), instead
    // of opening a channel page.
    // Settings › Live TV › Live preview: off, a tap opens the channel full screen with no preview first.
    val settings = koinInject<SettingsRepository>()
    val previewOn by settings.livePreviewEnabled.collectAsStateWithLifecycle(true)
    val showPreview = playing != null
    val paneHasChannel = showPreview
    DisposableEffect(paneHasChannel) {
        streamOnScreen.value = paneHasChannel
        onDispose { streamOnScreen.value = false }
    }
    // The preview is watching, as the channel page was, so leaving Live TV stops it — except for the
    // two ways out meant to keep it: full screen, and the player's own mini-player button.
    val miniRequested = LocalMiniRequested.current
    var toFullscreen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!toFullscreen && !miniRequested.value) tuner.stop() }
    }
    // Back closes the preview first, and stops the stream with it.
    BackHandler(enabled = showPreview) { tuner.stop() }
    val openFullscreen = {
        toFullscreen = true
        // A preview becomes a watched channel only here (History, the media session).
        tuner.promote()
        onOpenPlayer()
    }

    // The header's crumb: "‹ Sky Cinema · 9 channels", a tap opens the categories. A locked
    // list (Favourites, History) is one folder already and its page names it.
    val categoryLabel = categories.firstOrNull { it.key == selected }?.label().orEmpty()
    val liveView by vm.liveView.collectAsStateWithLifecycle()
    // "Live TV opens in: Guide" draws the guide grid in place of the list.
    val guideView = liveView == SettingsRepository.LiveView.GUIDE
    // A category the PIN has not opened names no count: how many channels it holds is the lock's business.
    val countText = when {
        guideView -> stringResource(R.string.content_live_guide_view)
        categories.firstOrNull { it.key == selected }?.sealed == true -> null
        else -> groupedCountText(R.plurals.content_live_channel_count, count)
    }
    ProvidePageCrumb { owner -> PageCrumb(categoryLabel, countText, owner = owner) { categoryPicker = true } }

    // The guide is read for what is actually on screen. Watching the visible range rather than each
    // row means one batched query per scroll settle instead of one per row appearing.
    LaunchedEffect(listState, channels) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                vm.loadNowPlaying(indices.mapNotNull { channels.peekOrNull(it) })
            }
    }

    val nowMs by rememberClockTick()
    // Each clock tick re-reads the rows whose programme has just ended.
    LaunchedEffect(nowMs) {
        vm.loadNowPlaying(listState.layoutInfo.visibleItemsInfo.mapNotNull { channels.peekOrNull(it.index) })
    }

    // Changing category scrolls back to the top: the position of the old list means nothing in the new one.
    LaunchedEffect(selected) { listState.scrollToItem(0) }

    // A channel from Search, or "Start on: Last channel · Live TV": its category, its row scrolled to.
    // Search has already started its preview; a startup one is previewed here, and with Live preview off
    // only shown, not played — opening Live TV is not watching.
    LaunchedEffect(Unit) {
        val opening = vm.applyOpeningSelection() ?: return@LaunchedEffect
        if (opening.preview && settings.livePreviewEnabled.first()) tuner.preview(opening.channel.id)
        scrollToChannel(listState, channels, opening.channel.id)
    }

    // The guide grid of Guide view reads through the TV Guide's own view model (its rows, its order).
    val guideVm: tv.own.owntv.mobile.ui.screens.guide.GuideViewModel = koinViewModel()

    val listPane = @Composable {
    Column(Modifier.fillMaxSize()) {
        val current = playing
        if (!twoPane && showPreview && current != null) {
            LivePreview(
                tuner = tuner,
                channel = current,
                nowNext = playingNowNext,
                nowMs = nowMs,
                onFullscreen = openFullscreen,
                modifier = Modifier.padding(top = PREVIEW_TOP),
            )
        } else if (guideView) {
            LiveGuideTools(
                guide = guideVm,
                onGuideView = vm::toggleLiveView,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = TOOLS_TOP, bottom = 12.dp),
            )
        } else {
            // `.tools`: the search field takes the room, then the order tool (Provider / A–Z), then Guide view.
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = TOOLS_TOP, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageSearchField(
                    value = query,
                    onValueChange = vm::setQuery,
                    placeholder = stringResource(R.string.content_search_in, categoryLabel),
                    inline = true,
                    modifier = Modifier.weight(1f),
                )
                StageTool(
                    onClick = vm::toggleSort,
                    icon = MobileIcons.Sort,
                    label = stringResource(
                        if (sort == SettingsRepository.SortMode.ALPHA) R.string.settings_sort_alpha else R.string.content_epg_sort_provider,
                    ),
                )
                StageTool(
                    onClick = vm::toggleLiveView,
                    icon = MobileIcons.Guide,
                    contentDescription = stringResource(R.string.content_live_guide_view),
                )
            }
        }
        if (categoryPicker) {
            val counts by vm.categoryCounts.collectAsStateWithLifecycle()
            val showCounts by vm.sheetCounts.collectAsStateWithLifecycle()
            LiveCategoriesSheet(
                categories = categories,
                selected = selected,
                counts = counts,
                showCounts = showCounts,
                marks = marks,
                onSelect = vm::select,
                onHold = { categoryMenuFor = it },
                onDismiss = { categoryPicker = false },
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (categories.firstOrNull { it.key == selected }?.sealed == true) {
                tv.own.owntv.mobile.ui.categorylock.LockedCategoryState(onEnterPin = { lock.enterPin(selected) })
            } else if (guideView) {
                LiveGuideGrid(
                    guide = guideVm,
                    category = selected,
                    onTune = { channel -> tuner.tune(channel.id) },
                    onOpenPlayer = openFullscreen,
                )
            } else if (channels.itemCount == 0) {
                EmptyChannels(query)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val channel = channels[index]
                        if (channel != null) {
                            LiveChannelRow(
                                channel = channel,
                                showNumber = showNumbers,
                                now = nowPlaying[channel.id]?.takeIf { it.stopMs > nowMs },
                                nowMs = nowMs,
                                mark = marks[channel.sourceId],
                                isFavorite = channel.id in favorites,
                                current = showPreview && channel.id == playing?.id,
                                // Beside the list the channel opens in the pane; on a phone it is
                                // a screen of its own, which is the same screen either way.
                                onClick = {
                                    when {
                                        // Channels are waiting to become a Multiview grid, so the
                                        // tap that says "now" must actually start playing. Sent to
                                        // the channel page instead, it took two taps to open the
                                        // grid and the first one looked like it had done nothing.
                                        multiviewPending -> {
                                            // Selected, not started: the grid is about to open and
                                            // would only have to stop it again — and losing that
                                            // race is what played a second channel under the tiles.
                                            tuner.selectWithoutPlaying(channel)
                                            onOpenPlayer()
                                        }
                                        // The second tap on the playing channel is full screen.
                                        channel.id == playing?.id -> openFullscreen()
                                        !previewOn -> { tuner.tune(channel.id); openFullscreen() }
                                        else -> tuner.preview(channel.id)
                                    }
                                },
                                onLongClick = { menuFor = channel },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (twoPane) {
        TwoPane(
            list = listPane,
            detail = {
                val current = playing
                if (current == null) {
                    SelectAChannel()
                } else {
                    // The video at 16:9 across the pane (210 dp sideways, 345 on a tablet), the programme
                    // under it; a short screen scrolls the pane rather than cutting the programme off.
                    LivePreview(
                        tuner = tuner,
                        channel = current,
                        nowNext = playingNowNext,
                        nowMs = nowMs,
                        onFullscreen = openFullscreen,
                        wide = true,
                        tablet = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 600,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = TOOLS_TOP),
                    )
                }
            },
            // Half and half (list 390 of 791 dp).
            listShare = 0.5f,
            modifier = modifier,
        )
    } else {
        Box(modifier) { listPane() }
    }

    menuFor?.let { channel ->
        ChannelMenu(
            channel = channel,
            selected = selected,
            isFavorite = channel.id in favorites,
            originName = categories.firstOrNull { it.key == selected }?.label().orEmpty(),
            providerName = providers[channel.sourceId],
            vm = vm,
            // On a phone the archive opens here, over the preview, as the channel page used to open it.
            onOpenCatchup = {
                tuner.tune(channel.id)
                catchupFor = channel
            },
            onDismiss = { menuFor = null },
        )
    }

    categoryMenuFor?.let { category ->
        val name = remember(category) { ProviderTags.parse(category.title.orEmpty()).name }
        LiveCategoryMenu(
            title = name,
            onRename = { renamingFor = category },
            onHide = { lock.guarded(category.key, category.sealed, SealedCategoryAction.HIDE) { vm.hideCategory(category.key) } },
            onDismiss = { categoryMenuFor = null },
        )
    }
    renamingFor?.let { category ->
        TextPromptDialog(
            title = stringResource(R.string.settings_customize_rename),
            initial = remember(category) { ProviderTags.parse(category.title.orEmpty()).name },
            onConfirm = { vm.renameCategory(category.key, it.takeIf { t -> t.isNotBlank() }); renamingFor = null },
            onDismiss = { renamingFor = null },
        )
    }
    catchupFor?.let { channel ->
        CatchupSheet(
            channelName = channel.name,
            load = tuner::catchupProgrammes,
            onPick = tuner::playCatchup,
            onJumpBack = { catchupFor = null; jumpBackOpen = true },
            onDismiss = { catchupFor = null },
        )
    }
    if (jumpBackOpen) {
        JumpBackSheet(
            options = CatchupOptions(
                offsetsSec = tuner.jumpOptions(),
                windowSec = tuner.archiveWindowSec(),
                onPick = tuner::jumpBackTo,
                programmeStart = tuner::currentProgrammeStart,
            ),
            onDismiss = { jumpBackOpen = false },
        )
    }
}

/** The chip's text: a translated label for the four built-in lists, the stored name otherwise. */
@Composable
internal fun LiveCategory.label(): String = when (builtIn) {
    LiveCategory.BuiltIn.ALL -> stringResource(R.string.content_category_all_channels)
    LiveCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    LiveCategory.BuiltIn.HISTORY -> stringResource(R.string.content_category_recently_watched)
    LiveCategory.BuiltIn.CATCHUP -> stringResource(R.string.content_catchup)
    // A provider folder by its name alone: "DE| PRIME ᴿᴬᵂ" is "PRIME" (its tags are drawn as tags).
    null -> ProviderTags.parse(title.orEmpty()).name
}

/** The preview pane before a channel is picked — the television's own sentence for the same panel. */
@Composable
private fun SelectAChannel() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_preview_select_channel),
            style = stageText(15f, 400),
            color = LocalStagePalette.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Nothing in the open category, or nothing matching the search — the TV's two sentences. */
@Composable
private fun EmptyChannels(query: String) {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = if (query.isNotBlank()) stringResource(R.string.content_no_channels_found, query.trim()) else stringResource(R.string.content_no_channels_here),
            style = stageText(13.5f, 500),
            color = LocalStagePalette.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * `.chrow`: 66 dp — the number (28 wide, 13 / 600 dim), the logo (52 × 38, no plate unless its ink
 * would vanish), the provider's name without its marks (14.5 / 700), "programme · time left" (12 muted)
 * and a 3 dp progress line at 70%; then the provider tags, ↺ for catch-up, ♥ when a favourite and the
 * playlist mark. [current] is the playing channel (`.fx` + `.selbar`).
 */
@Composable
internal fun LiveChannelRow(
    channel: ChannelEntity,
    showNumber: Boolean,
    now: XtEpgEntry?,
    nowMs: Long,
    mark: PlaylistMark?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    current: Boolean = false,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val name = remember(channel.name) { ProviderTags.parse(channel.name) }
    StageRow(
        onClick = onClick,
        onLongClick = onLongClick,
        current = current,
        selected = current,
        height = 66.dp,
    ) {
        if (showNumber) {
            Text(
                channel.number?.takeIf { it > 0 }?.let(::formatYear).orEmpty(),
                style = stageText(13f, 600).copy(textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
                color = palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 28 dp by design; a playlist with five-digit numbers widens it rather than cutting them.
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
        Box(Modifier.size(52.dp, 38.dp).clip(RoundedCornerShape(StageRadii.Plate)), contentAlignment = Alignment.Center) {
            ChannelLogoImage(
                url = channel.displayLogoUrl,
                modifier = Modifier.fillMaxSize(),
                plateOn = palette.page,
                fallback = { StageIcon(MobileIcons.LiveTv, 19.dp, palette.dim) },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name.name, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                val left = timeLeftText(((now.stopMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000)
                Text(
                    now.title + stringResource(R.string.content_epg_bits_separator) + left,
                    style = stageText(12f, 400),
                    color = if (current) palette.body else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                val span = (now.stopMs - now.startMs).coerceAtLeast(1)
                StageProgress(
                    (nowMs - now.startMs).toFloat() / span,
                    Modifier.padding(top = 6.dp).fillMaxWidth(0.7f),
                    thin = true,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            name.tags.forEach { StageTag(it) }
            if (channel.catchup) StageIcon(MobileIcons.Catchup, 14.dp, palette.dim)
            if (isFavorite) StageIcon(MobileIcons.Favorite, 14.dp, accent)
            mark?.let { StagePlaylistMark(it) }
        }
    }
}

/** The room between the crumb and the tools (`.tools` at 128 under a 102 dp header). */
private val TOOLS_TOP = 26.dp

/** The room between the crumb and the preview (`.video` at 122). */
private val PREVIEW_TOP = 20.dp

/** How long the start-up channel's row is waited for before the list is left where it is. */
internal const val STARTUP_ROW_WAIT_MS = 3_000L

/** How long Live TV keeps loading pages to bring an opening channel's row into view. */
private const val OPENING_ROW_WAIT_MS = 5_000L

/**
 * Scrolls to [channelId] in the paged [channels], asking for page after page until it is loaded — a
 * channel deep in a large category is not in the first pages. Gives up after [OPENING_ROW_WAIT_MS], and
 * at once when the user drags the list: their scroll wins.
 */
private suspend fun scrollToChannel(listState: LazyListState, channels: LazyPagingItems<ChannelEntity>, channelId: Long) = coroutineScope {
    val userScrolled = async { listState.interactionSource.interactions.first { it is DragInteraction.Start } }
    withTimeoutOrNull(OPENING_ROW_WAIT_MS) {
        while (!userScrolled.isCompleted) {
            val index = channels.itemSnapshotList.items.indexOfFirst { it.id == channelId }
            if (index >= 0) {
                listState.scrollToItem(index)
                break
            }
            val count = channels.itemCount
            if (count > 0) channels[count - 1] // reading the last row asks Paging for the next page
            delay(OPENING_ROW_POLL_MS)
        }
    }
    userScrolled.cancel()
}

private const val OPENING_ROW_POLL_MS = 50L
