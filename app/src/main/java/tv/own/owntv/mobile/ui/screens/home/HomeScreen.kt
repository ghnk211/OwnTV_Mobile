package tv.own.owntv.mobile.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.home.GuideSliceState
import tv.own.owntv.core.home.HeroItem
import tv.own.owntv.core.home.HomeFeed
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.home.homeKey
import tv.own.owntv.core.launcher.LauncherContinuationItem
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeTrendingStyle
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.ContentActionsMenu
import tv.own.owntv.mobile.ui.components.ContentTarget
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.player.rememberResumeGate
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.shell.HomeHeading
import tv.own.owntv.mobile.ui.shell.HomeResume
import tv.own.owntv.mobile.ui.shell.LocalHomeChrome
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StagePoster
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.StageStill
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Home, Stage. The same rows as the television, in the same order, hidden by the same choices. With
 * Trending first and drawn as the hero, the top of the page is that title over its backdrop (the shell
 * draws the picture behind the header) and Keep watching sits under it in 16:9 stills; scrolled, the header
 * carries the hero's eyebrow and title instead. A tap resumes rather than opens: a film goes
 * straight to the picture, a channel to its own screen. A long press is the item's sheet.
 */
@Composable
fun HomeScreen(
    scrollToTop: SharedFlow<String>,
    onOpenChannel: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onPlayerOpened: () -> Unit,
    onOpenSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = koinViewModel(),
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val favoriteChannels by vm.favoriteChannels.collectAsStateWithLifecycle()
    val favoriteMovies by vm.favoriteMovies.collectAsStateWithLifecycle()
    val favoriteSeries by vm.favoriteSeries.collectAsStateWithLifecycle()
    val trendingExtras by vm.trendingExtras.collectAsStateWithLifecycle()
    val heroBackdrops by vm.heroBackdrops.collectAsStateWithLifecycle()
    val movieProgress by vm.movieProgress.collectAsStateWithLifecycle()
    val seriesProgress by vm.seriesProgress.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val homeChrome = LocalHomeChrome.current

    var menuFor by remember { mutableStateOf<ContentTarget?>(null) }
    var trendingMenu by remember { mutableStateOf<TrendingHomeItem?>(null) }
    var bulkDownload by remember { mutableStateOf<tv.own.owntv.mobile.ui.components.BulkDownloadAsk?>(null) }
    val downloadsAllowed by org.koin.compose.koinInject<tv.own.owntv.core.settings.SettingsRepository>().activeDownloadsAllowed
        .collectAsStateWithLifecycle(true)
    // A show downloads every episode, so it asks first with the count; a film starts at once.
    fun download(type: MediaType, id: Long) {
        if (type == MediaType.SERIES) vm.countEpisodes(id) { n -> bulkDownload = tv.own.owntv.mobile.ui.components.BulkDownloadAsk(n) { vm.download(type, id) } }
        else vm.download(type, id)
    }
    var infoFor by remember { mutableStateOf<TrendingHomeItem?>(null) }
    var unavailable by remember { mutableStateOf(false) }

    // Continue watching and the hero both start something part-watched, so both go through the
    // Resume playback setting rather than silently jumping to where the user left off.
    val resumeGate = rememberResumeGate()

    // Coming back from a film is exactly when "continue watching" is out of date.
    LaunchedEffect(Unit) { vm.refresh() }

    // What Home lent the shell goes back when Home goes.
    DisposableEffect(homeChrome) {
        onDispose {
            homeChrome?.backdropUrl = null
            homeChrome?.heading = null
            homeChrome?.resume = null
        }
    }
    val resumeTarget = resume
    val resumeChip = remember(resumeTarget) {
        resumeTarget?.let { target ->
            HomeResume(target.name) {
                when (target.type) {
                    MediaType.MOVIE -> resumeGate(target.positionMs) { vm.playMovie(target.itemId, it, onPlayerOpened) }
                    MediaType.EPISODE -> resumeGate(target.positionMs) { vm.playEpisode(target.itemId, it, onPlayerOpened) }
                    else -> onOpenChannel(target.itemId)
                }
            }
        }
    }

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileHomeRoute, scrollToTop = scrollToTop)

    // Where the content starts on screen: the hero's text is placed from the top of the screen, as drawn,
    // whatever the status bar and the header take.
    val estimatedTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp
    var contentTop by remember { mutableStateOf<Dp?>(null) }

    // Nothing of the hero's stays in the shell unless this pass puts it back (an emptied Home has none).
    SideEffect {
        homeChrome?.backdropUrl = null
        homeChrome?.heading = null
        homeChrome?.resume = null
    }

    // Null is the feed still being read, not an empty one: a spinner, not a false empty state.
    val state = feed ?: run {
        HomeSkeleton(modifier)
        return
    }
    // Computed once per feed: hasContent walks each row's loaded items.
    val rows = remember(state) {
        state.config.visibleOrder.filter { state.hasContent(it) }
    }

    // The television's three states, in its order. None offers "add a playlist": a user with none is in
    // setup, not here.
    if (state.config.visibleOrder.isEmpty()) {
        HomeMessage(stringResource(R.string.home_no_rows), stringResource(R.string.home_enable_rows), modifier)
        return
    }
    // While a large playlist keeps loading after setup, a short or empty Home says so rather than
    // reading as broken. Trending is promised only while its row is switched on.
    val loadingTitles by vm.libraryLoadingTitles.collectAsStateWithLifecycle()
    val loadingNote: (@Composable () -> Unit)? = if (loadingTitles > 0 && state.trendingItems.size < TrendingDao.MIN_ELIGIBLE_ITEMS) {
        { LibraryLoadingNote(loadingTitles, trendingOn = HomeRow.TRENDING in state.config.visibleOrder) }
    } else {
        null
    }
    if (rows.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            loadingNote?.invoke()
            HomeMessage(stringResource(R.string.home_start_watching), stringResource(R.string.home_continue_empty), Modifier.weight(1f))
        }
        return
    }

    val trending = state.trendingItems
    val trendingFirst = rows.firstOrNull() == HomeRow.TRENDING
    val fullBleed = trendingFirst && state.config.trendingStyle == HomeTrendingStyle.HERO
    val posterHero = trendingFirst && state.config.trendingStyle == HomeTrendingStyle.POSTERS
    var activeIndex by rememberSaveable(trending.size) { mutableIntStateOf(0) }
    var posterShown by remember { mutableIntStateOf(0) }
    LaunchedEffect(activeIndex) { unavailable = false }
    val shownTrending = when {
        fullBleed -> trending.getOrNull(activeIndex)
        posterHero -> trending.getOrNull(posterShown)
        else -> null
    }
    LaunchedEffect(shownTrending) { shownTrending?.let(vm::resolveTrendingExtras) }

    val topGap = ((if (posterHero) PostersTop else HeroTop) - (contentTop ?: estimatedTop)).coerceAtLeast(0.dp)
    val headingAfterPx = with(density) { (topGap + 100.dp).roundToPx() }
    val heroScrolled by remember(headingAfterPx) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > headingAfterPx }
    }
    val heading = shownTrending?.takeIf { heroScrolled }?.let { HomeHeading(trendingRank(it), it.snapshot.localizedTitle) }
    SideEffect {
        homeChrome?.backdropUrl = shownTrending?.let(::trendingBackdrop)
        homeChrome?.backdropHeight = if (posterHero) 300.dp else 470.dp
        homeChrome?.heading = heading
        // The Resume pill belongs to the full hero; the posters layout has none.
        homeChrome?.resume = resumeChip.takeIf { fullBleed }
    }
    // The picture fades as the hero scrolls up, so the rows never sit over it. Written straight
    // to the shell's draw phase: scrolling never recomposes Home for it.
    val fadePx = with(density) { 360.dp.toPx() }
    LaunchedEffect(homeChrome, listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) 0f else 1f - listState.firstVisibleItemScrollOffset / fadePx
        }.collect { homeChrome?.backdropAlpha = it.coerceIn(0f, 1f) }
    }

    val activate: (TrendingHomeItem) -> Unit = { item ->
        vm.activateTrending(item, onPlayerOpened, onOpenSeries) {
            if (fullBleed) unavailable = true
            else Toast.makeText(context, R.string.home_trending_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    fun isFavourite(item: TrendingHomeItem) = when (item) {
        is TrendingHomeItem.Movie -> item.movie.id in favoriteMovies
        is TrendingHomeItem.Series -> item.series.id in favoriteSeries
    }
    fun TrendingHomeItem.typeAndId() = when (this) {
        is TrendingHomeItem.Movie -> MediaType.MOVIE to movie.id
        is TrendingHomeItem.Series -> MediaType.SERIES to series.id
    }
    val now by rememberClockTick()

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                // Only at rest at the top: the first frame can come before the header has its height,
                // and a scrolled page has the header sliding away.
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    contentTop = with(density) { coords.positionInWindow().y.toDp() }
                }
            },
        contentPadding = PaddingValues(top = if (trendingFirst) 0.dp else 8.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        loadingNote?.let { note -> item("library-loading") { note() } }
        rows.forEach { row ->
            item(row.name) {
                when (row) {
                    HomeRow.TRENDING -> when {
                        fullBleed -> {
                            val item = trending[activeIndex.coerceIn(0, trending.lastIndex)]
                            TrendingHero(
                                items = trending,
                                activeIndex = activeIndex.coerceIn(0, trending.lastIndex),
                                onIndexChange = { activeIndex = it },
                                extras = trendingExtras[item.stableKey] ?: HomeViewModel.TrendingExtras(),
                                seasonCount = (item as? TrendingHomeItem.Series)?.let { state.trendingSeasonCounts[it.series.id] },
                                favourite = isFavourite(item),
                                unavailable = unavailable,
                                paused = trendingMenu != null || infoFor != null || menuFor != null,
                                topGap = topGap,
                                onPlay = { activate(item) },
                                onVersions = { onOpenSearch(item.snapshot.canonicalTitle) },
                                onInfo = { infoFor = item },
                                onFavourite = { item.typeAndId().let { (t, id) -> vm.toggleFavorite(t, id) } },
                                onMenu = { trendingMenu = item },
                            )
                        }
                        else -> TrendingPosters(
                            items = trending,
                            topGap = if (posterHero) topGap else 0.dp,
                            withTitle = posterHero,
                            onShown = { posterShown = it },
                            onActivate = activate,
                            onMenu = { trendingMenu = it },
                        )
                    }
                    HomeRow.HERO -> KeepWatchingRow(
                        title = stringResource(R.string.home_row_keep_watching),
                        items = state.heroItems,
                        backdrops = heroBackdrops,
                        nowMs = now,
                        onPlay = { item ->
                            when (item) {
                                is HeroItem.LiveHero -> onOpenChannel(item.channel.id)
                                is HeroItem.MovieHero -> resumeGate(item.seekToMs) { vm.playMovie(item.movie.id, it, onPlayerOpened) }
                                is HeroItem.SeriesHero -> resumeGate(item.seekToMs) { vm.playEpisode(item.episode.id, it, onPlayerOpened) }
                            }
                        },
                        onMenu = { menuFor = it.menuTarget() },
                    )
                    HomeRow.RECENT_CHANNELS -> LiveRow(
                        title = stringResource(R.string.home_row_recent_channels),
                        channels = state.recentLive,
                        guide = state.recentGuide,
                        mode = vm.modeOf(row, state),
                        onToggleMode = { vm.toggleLiveMode(row) },
                        onOpenChannel = onOpenChannel,
                        onMenu = { menuFor = it },
                    )
                    HomeRow.FAVORITE_CHANNELS -> LiveRow(
                        title = stringResource(R.string.home_row_favorite_channels),
                        channels = state.favoriteLive,
                        guide = state.favoriteGuide,
                        mode = vm.modeOf(row, state),
                        onToggleMode = { vm.toggleLiveMode(row) },
                        onOpenChannel = onOpenChannel,
                        onMenu = { menuFor = it },
                    )
                    HomeRow.FAVORITE_MOVIES -> PosterRow(
                        title = stringResource(R.string.home_row_favorite_movies),
                        posters = state.favoriteMovies.map { m ->
                            HomePoster(ContentTarget(MediaType.MOVIE, m.id, m.name, m.posterUrl), m.posterUrl, m.rating, progress = movieProgress[m.id])
                        },
                        onOpen = { onOpenMovie(it.id) },
                        onMenu = { menuFor = it },
                    )
                    HomeRow.FAVORITE_SERIES -> PosterRow(
                        title = stringResource(R.string.home_row_favorite_series),
                        posters = state.favoriteSeries.map { s ->
                            HomePoster(ContentTarget(MediaType.SERIES, s.id, s.name, s.posterUrl), s.posterUrl, s.rating, progress = seriesProgress[s.id])
                        },
                        onOpen = { onOpenSeries(it.id) },
                        onMenu = { menuFor = it },
                    )
                    HomeRow.CONTINUE_MOVIES -> ContinueRow(
                        title = stringResource(R.string.home_row_continue_movies),
                        items = state.continueMovies,
                        type = MediaType.MOVIE,
                        onPlay = { item -> resumeGate(item.positionMs) { vm.playMovie(item.sourceItemId, it, onPlayerOpened) } },
                        onMenu = { menuFor = it },
                    )
                    HomeRow.CONTINUE_SERIES -> ContinueRow(
                        title = stringResource(R.string.home_row_continue_series),
                        items = state.continueSeries,
                        type = MediaType.SERIES,
                        onPlay = { item -> resumeGate(item.positionMs) { vm.playEpisode(item.targetItemId, it, onPlayerOpened) } },
                        onMenu = { menuFor = it },
                    )
                    else -> Unit
                }
            }
        }
    }

    trendingMenu?.let { item ->
        val (type, id) = item.typeAndId()
        TrendingMenuSheet(
            item = item,
            versions = trendingExtras[item.stableKey]?.versions ?: 1,
            favourite = isFavourite(item),
            onPlay = { activate(item) },
            onVersions = { onOpenSearch(item.snapshot.canonicalTitle) },
            onFavourite = { vm.toggleFavorite(type, id) },
            onDownload = if (downloadsAllowed) ({ download(type, id) }) else null,
            onInfo = { infoFor = item },
            onDismiss = { trendingMenu = null },
        )
    }
    infoFor?.let { item ->
        LaunchedEffect(item) { vm.resolveTrendingExtras(item) }
        TrendingInfoSheet(
            item = item,
            genres = trendingExtras[item.stableKey]?.genres.orEmpty(),
            seasonCount = (item as? TrendingHomeItem.Series)?.let { state.trendingSeasonCounts[it.series.id] },
            preferredLanguage = state.trendingPreferredLanguage,
            resolveDetails = vm::resolveTrendingDetails,
            onDismiss = { infoFor = null },
        )
    }
    menuFor?.let { target ->
        ContentActionsMenu(
            target = target,
            isFavorite = target.id in when (target.type) {
                MediaType.LIVE -> favoriteChannels
                MediaType.MOVIE -> favoriteMovies
                else -> favoriteSeries
            },
            onToggleFavorite = { vm.toggleFavorite(target.type, target.id) },
            onDownload = { download(target.type, target.id) },
            onHide = { vm.hide(target.type, target.id) },
            onDismiss = { menuFor = null },
        )
    }
    bulkDownload?.let { tv.own.owntv.mobile.ui.components.BulkDownloadConfirm(it) { bulkDownload = null } }
}

/** A row with nothing in it is not drawn at all — an empty heading is worse than one row fewer. */
private fun HomeFeed.hasContent(row: HomeRow): Boolean = when (row) {
    // The television's own `rowHasData`, rule for rule. Trending needs enough titles to be worth a row,
    // and the two live rows in On now mode are decided by the guide, not the card list.
    HomeRow.TRENDING -> trendingItems.size >= TrendingDao.MIN_ELIGIBLE_ITEMS
    HomeRow.HERO -> heroItems.isNotEmpty()
    HomeRow.RECENT_CHANNELS -> when (config.recentLiveMode) {
        HomeLiveRowMode.CARDS -> recentLive.isNotEmpty()
        HomeLiveRowMode.ON_NOW -> recentGuide.hasContent
    }
    HomeRow.FAVORITE_CHANNELS -> when (config.favoriteLiveMode) {
        HomeLiveRowMode.CARDS -> favoriteLive.isNotEmpty()
        HomeLiveRowMode.ON_NOW -> favoriteGuide.hasContent
    }
    HomeRow.FAVORITE_MOVIES -> favoriteMovies.isNotEmpty()
    HomeRow.FAVORITE_SERIES -> favoriteSeries.isNotEmpty()
    HomeRow.CONTINUE_MOVIES -> continueMovies.isNotEmpty()
    HomeRow.CONTINUE_SERIES -> continueSeries.isNotEmpty()
    // Only the sports row, already filtered out above, falls through here.
    else -> false
}

/** A half-watched episode's menu is its show's: the show is what can be favourited or hidden. */
private fun HeroItem.menuTarget(): ContentTarget = when (this) {
    is HeroItem.MovieHero -> ContentTarget(MediaType.MOVIE, movie.id, movie.name, movie.posterUrl)
    is HeroItem.SeriesHero -> ContentTarget(MediaType.SERIES, series.id, series.name, series.posterUrl)
    is HeroItem.LiveHero -> ContentTarget(MediaType.LIVE, channel.id, channel.name, channel.displayLogoUrl)
}

/**
 * A row's heading (`17 / 800`), with the dim word after it — the count ("Keep watching 5") or the live
 * rows' mode ("On now", 12 / 700), which switches the mode when tapped.
 */
@Composable
private fun RowHeading(title: String, count: Int? = null, mode: String? = null, onMode: (() -> Unit)? = null) {
    val palette = LocalStagePalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = stageText(17f, 800), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline().weight(1f, fill = false))
        count?.let { Text(formatCount(it), style = stageText(13f, 800), color = palette.dim, modifier = Modifier.alignByBaseline()) }
        mode?.let {
            Text(it, style = stageText(12f, 700), color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline().stageClickable(interaction, onMode, null))
        }
    }
}

/**
 * Keep watching: 190 dp 16:9 stills with the watch progress, the title (12.5 / 700) and its
 * line. The still is the best picture there is; a channel is its logo on the dark card, no white tile.
 */
@Composable
private fun KeepWatchingRow(
    title: String,
    items: List<HeroItem>,
    backdrops: Map<String, String>,
    nowMs: Long,
    onPlay: (HeroItem) -> Unit,
    onMenu: (HeroItem) -> Unit,
) {
    val palette = LocalStagePalette.current
    Column {
        RowHeading(title, count = items.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
            items(items, key = { it.homeKey }) { item ->
                val interaction = remember { MutableInteractionSource() }
                Column(Modifier.width(190.dp).stageClickable(interaction, { onPlay(item) }, { onMenu(item) })) {
                    val progress = item.durationMs.takeIf { it > 0 && item.positionMs > 0 }?.let { item.positionMs.toFloat() / it }
                    when (item) {
                        is HeroItem.LiveHero -> StageStill(imageUrl = null) {
                            ChannelLogoImage(
                                url = item.channel.displayLogoUrl,
                                fallback = { StageIcon(MobileIcons.LiveTv, 28.dp, palette.dim) },
                                modifier = Modifier.align(Alignment.Center).fillMaxHeight(0.52f).fillMaxWidth(0.6f),
                            )
                        }
                        is HeroItem.MovieHero -> StageStill(
                            imageUrl = backdrops[item.homeKey] ?: item.movie.backdropUrl?.takeIf { it.isNotBlank() } ?: item.movie.posterUrl,
                            progress = progress,
                        )
                        is HeroItem.SeriesHero -> StageStill(
                            imageUrl = backdrops[item.homeKey] ?: item.series.backdropUrl?.takeIf { it.isNotBlank() } ?: item.series.posterUrl,
                            progress = progress,
                        )
                    }
                    Text(
                        when (item) {
                            is HeroItem.MovieHero -> item.item.title
                            is HeroItem.SeriesHero -> item.series.name
                            is HeroItem.LiveHero -> item.channel.name
                        },
                        style = stageText(12.5f, 700),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    keepWatchingLine(item, nowMs)?.let {
                        Text(it, style = stageText(11f, 500), color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A poster on a Home row: what it is, its picture and rating, and how far in (0..1) it has been watched. */
private data class HomePoster(val target: ContentTarget, val posterUrl: String?, val rating: Double?, val progress: Float?)

/**
 * Favourite films or shows: 104 dp posters with the rating, the watch progress and ✓ once watched
 * to the end (for a show, the episode watched last). A tap opens the title's page.
 */
@Composable
private fun PosterRow(
    title: String,
    posters: List<HomePoster>,
    onOpen: (ContentTarget) -> Unit,
    onMenu: (ContentTarget) -> Unit,
) {
    Column {
        RowHeading(title, count = posters.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
            items(posters, key = { it.target.id }) { poster ->
                val watched = (poster.progress ?: 0f) >= WATCHED_FRACTION
                StagePoster(
                    imageUrl = poster.posterUrl,
                    title = poster.target.title,
                    rating = poster.rating?.takeIf { it > 0 }?.let { formatRating(it) },
                    watched = watched,
                    progress = poster.progress?.takeIf { it > 0f && !watched },
                    onClick = { onOpen(poster.target) },
                    onLongClick = { onMenu(poster.target) },
                    modifier = Modifier.width(PosterWidth),
                )
            }
        }
    }
}

/** Films or episodes left half-watched: posters with the bar showing how far in. A tap resumes. */
@Composable
private fun ContinueRow(
    title: String,
    items: List<LauncherContinuationItem>,
    type: MediaType,
    onPlay: (LauncherContinuationItem) -> Unit,
    onMenu: (ContentTarget) -> Unit,
) {
    Column {
        RowHeading(title, count = items.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
            items(items, key = { it.stableKey }) { item ->
                StagePoster(
                    imageUrl = item.posterUrl,
                    title = item.title,
                    subtitle = item.subtitle,
                    progress = item.durationMs.takeIf { it > 0 && item.positionMs > 0 }?.let { item.positionMs.toFloat() / it },
                    onClick = { onPlay(item) },
                    // The menu is about the film or the show, so a half-watched episode names its series.
                    onLongClick = { onMenu(ContentTarget(type, item.sourceItemId, item.containerTitle ?: item.title, item.posterUrl)) },
                    modifier = Modifier.width(PosterWidth),
                )
            }
        }
    }
}

/**
 * A channel row in either of the setting's two shapes: what is on now (cards) or the logos to
 * pick from. The dim word after the heading names the shape and switches it — stored where the TV reads it.
 */
@Composable
private fun LiveRow(
    title: String,
    channels: List<ChannelEntity>,
    guide: GuideSliceState,
    mode: HomeLiveRowMode,
    onToggleMode: () -> Unit,
    onOpenChannel: (Long) -> Unit,
    onMenu: (ContentTarget) -> Unit,
) {
    Column {
        RowHeading(
            title,
            mode = stringResource(if (mode == HomeLiveRowMode.ON_NOW) R.string.home_row_on_now else R.string.home_row_cards),
            onMode = onToggleMode,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
            items(channels, key = { it.id }, contentType = { mode }) { channel ->
                val menu = { onMenu(ContentTarget(MediaType.LIVE, channel.id, channel.name, channel.displayLogoUrl)) }
                if (mode == HomeLiveRowMode.ON_NOW) {
                    OnNowCard(channel, guide, onClick = { onOpenChannel(channel.id) }, onLongClick = menu)
                } else {
                    ChannelCard(channel, onClick = { onOpenChannel(channel.id) }, onLongClick = menu)
                }
            }
        }
    }
}

/** On now: 206 dp, the logo (46 × 34, no tile), the name, the programme and its progress. */
@Composable
private fun OnNowCard(channel: ChannelEntity, guide: GuideSliceState, onClick: () -> Unit, onLongClick: () -> Unit) {
    val palette = LocalStagePalette.current
    // The scan runs once per guide tick, not once per card per recomposition.
    val programmes = guide.programmes[channel.id]
    val nowMs = guide.now
    val now = remember(programmes, nowMs) { programmes?.firstOrNull { nowMs in it.startMs until it.stopMs } }
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .width(206.dp)
            .clip(RoundedCornerShape(StageRadii.Row))
            .background(palette.ink.copy(alpha = 0.045f))
            .stageClickable(interaction, onClick, onLongClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogoImage(
            url = channel.displayLogoUrl,
            fallback = { StageIcon(MobileIcons.LiveTv, 20.dp, palette.dim) },
            modifier = Modifier.size(46.dp, 34.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = stageText(12.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                now?.title ?: stringResource(R.string.content_no_epg),
                style = stageText(11f, 500),
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null && now.stopMs > now.startMs) {
                StageProgress(
                    fraction = (nowMs - now.startMs).toFloat() / (now.stopMs - now.startMs),
                    thin = true,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

/** Cards: the logo on a 96 dp dark card, the name under it. */
@Composable
private fun ChannelCard(channel: ChannelEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(96.dp).stageClickable(interaction, onClick, onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(StageRadii.Still)).background(palette.cardSolid),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogoImage(
                url = channel.displayLogoUrl,
                fallback = { StageIcon(MobileIcons.LiveTv, 20.dp, palette.dim) },
                modifier = Modifier.fillMaxHeight(0.6f).fillMaxWidth(0.7f),
            )
        }
        Text(
            channel.name,
            style = stageText(11.5f, 600),
            color = palette.muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** Home with nothing on it: a headline and a line saying what would fill it. */
@Composable
private fun HomeMessage(title: String, body: String, modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = stageText(20f, 800), color = palette.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = stageText(13.5f, 500), color = palette.muted, textAlign = TextAlign.Center)
    }
}

/** "Loading your library · N titles so far", and — [trendingOn] — when Now Trending will show. */
@Composable
private fun LibraryLoadingNote(titles: Int, trendingOn: Boolean) {
    val palette = LocalStagePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(
            pluralStringResource(R.plurals.home_library_loading, titles, formatCount(titles)),
            style = stageText(13.5f, 700), color = palette.text,
        )
        if (trendingOn) {
            Text(stringResource(R.string.home_library_loading_trending), style = stageText(12f, 500), color = palette.muted)
        }
    }
}

/** The feed still being read. A quiet spinner, not a blank screen that jumps into a full Home. */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = stageAccent.accent)
    }
}

/** The route this screen answers a "scroll back to the top" tap for. */
private const val MobileHomeRoute = "home"

/** Where the hero's eyebrow sits from the top of the screen, and the posters layout's. */
private val HeroTop = 262.dp
private val PostersTop = 170.dp

/** The Library's rule: watched to 95% is watched. */
private const val WATCHED_FRACTION = 0.95f
