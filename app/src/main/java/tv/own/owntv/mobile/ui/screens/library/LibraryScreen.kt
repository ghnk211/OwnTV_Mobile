package tv.own.owntv.mobile.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AnimationLevel
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.home.groupedCountText
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.TwoPane
import tv.own.owntv.mobile.ui.components.peekOrNull
import tv.own.owntv.mobile.ui.nav.isExpandedWidth
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.home.durationText
import tv.own.owntv.mobile.ui.screens.home.formatRating
import tv.own.owntv.mobile.ui.screens.home.formatYear
import tv.own.owntv.mobile.ui.screens.live.LiveCategoryMenu
import tv.own.owntv.mobile.ui.screens.settings.customize.TextPromptDialog
import tv.own.owntv.core.categorylock.SealedCategoryAction
import tv.own.owntv.mobile.ui.shell.ProvidePageCrumb
import tv.own.owntv.mobile.ui.shell.PageCrumb
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChoiceRow
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StagePlaylistMark
import tv.own.owntv.mobile.ui.stage.StagePoster
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSegmented
import org.koin.compose.koinInject
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.nav.NavVisibility
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSizeBar
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.theme.LocalAnimations

/**
 * Movies and Series: each its own place on the bar, the same screen locked to its half.
 *
 * The header's crumb names the open category and its count ("‹ **Disney+ Kids Movies** · 136 titles"); a tap
 * opens the categories sheet. Under it the tool row — search inside the category, the sort order, the
 * categories, Grid | List — then three posters a row (rating, playlist mark or ✓, progress) or the list rows.
 *
 * The grid decides its own column count from the width it was given, until the user pinches or moves Poster
 * size: from then on their number is kept and travels with their backup, like the TV's fixed grid setting.
 * On a tablet the title opens in a pane beside the grid.
 */
@Composable
fun LibraryScreen(
    scrollToTop: SharedFlow<String>,
    route: String,
    onOpenItem: (LibraryTab, Long) -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    fixedTab: LibraryTab? = null,
    vm: LibraryViewModel = koinViewModel(),
) {
    LaunchedEffect(fixedTab) { fixedTab?.let { vm.select(it) } }

    val tab by vm.tab.collectAsStateWithLifecycle()
    // Library (no fixed tab) offers Movies | Series — only the halves the playlist has; with one, it is that one.
    val navVisibility: NavVisibility = koinInject()
    val sections by remember(navVisibility) { navVisibility.visibleSections() }
        .collectAsStateWithLifecycle(MainSection.allBrowse)
    val switchTabs = if (fixedTab != null) emptyList() else buildList {
        if (MainSection.MOVIES in sections) add(LibraryTab.MOVIES)
        if (MainSection.SERIES in sections) add(LibraryTab.SERIES)
    }
    LaunchedEffect(switchTabs) { switchTabs.singleOrNull()?.let { vm.select(it) } }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val sort by vm.sortMode.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val chosenColumns by vm.gridColumns.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val progress by vm.movieProgress.collectAsStateWithLifecycle()
    val seriesProgress by vm.seriesProgress.collectAsStateWithLifecycle()
    val watched by vm.watchedIds.collectAsStateWithLifecycle()
    val marks by vm.playlistMarks.collectAsStateWithLifecycle()
    val seasonCounts by vm.seasonCounts.collectAsStateWithLifecycle()
    val movies = tab == LibraryTab.MOVIES
    // The resume bar of one tile or row: a film's own position, a show's latest episode. None once watched.
    fun progressOf(id: Long): Float? = if (id in watched) null else when (tab) {
        LibraryTab.MOVIES -> progress[id]?.takeIf { it.durationMs > 1L }?.let { it.positionMs.toFloat() / it.durationMs }
        LibraryTab.SERIES -> seriesProgress[id]
    }

    val items = (if (movies) vm.movies else vm.series).collectAsLazyPagingItems()

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    gridState.ObeyGridScrollToTop(route, scrollToTop)
    listState.ObeyScrollToTop(route, scrollToTop)

    var menuFor by remember { mutableStateOf<VodItem?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var categoryMenuFor by remember { mutableStateOf<VodCategory?>(null) }
    var renamingFor by remember { mutableStateOf<VodCategory?>(null) }
    val lock = tv.own.owntv.mobile.ui.categorylock.rememberCategoryLock(
        if (tab == LibraryTab.MOVIES) tv.own.owntv.core.model.MediaType.MOVIE else tv.own.owntv.core.model.MediaType.SERIES,
    )

    val twoPane = isExpandedWidth()
    // Beside the grid, something is always open: the pane is a preview of the grid, so it falls back
    // to the first tile until the user taps another. Derived rather than stored, so a category whose
    // first title happens to be the one already showing still fills the pane instead of emptying it.
    var tappedItem by rememberSaveable { mutableStateOf<Long?>(null) }
    val openItem = tappedItem ?: if (twoPane) items.peekFirstId() else null
    // The title last opened keeps its marker when you come back to the list (`.selbar`, `.pc.sel`).
    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = { item: VodItem ->
        lastOpened = item.id
        // Wide: a tap picks the title for the card, a second tap on it opens its page.
        if (twoPane && item.id != openItem) tappedItem = item.id else onOpenItem(tab, item.id)
    }

    // A new selection's scroll position has nothing to do with the old one's, and neither does the
    // title that was open in the pane — it is not in this category any more.
    // Only on a real change: coming back from a title page re-enters this screen with the same selection,
    // and the saved scroll position must survive that.
    var shownSelection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, selected) {
        val selection = "$tab|$selected"
        if (shownSelection == selection) return@LaunchedEffect
        val first = shownSelection == null
        shownSelection = selection
        if (first) return@LaunchedEffect
        gridState.scrollToItem(0)
        listState.scrollToItem(0)
        tappedItem = null
    }

    // The header's crumb: "‹ Disney+ Kids Movies · 136 titles", a tap opens the categories.
    val categoryLabel = categories.firstOrNull { it.key == selected }?.label(tab).orEmpty()
    // A category the PIN has not opened names no count: how much it holds is the lock's business.
    val countText = if (categories.firstOrNull { it.key == selected }?.sealed == true) null
    else groupedCountText(if (movies) R.plurals.content_vod_title_count else R.plurals.content_vod_series_count, count)
    ProvidePageCrumb { owner -> PageCrumb(categoryLabel, countText, owner = owner) { categoriesOpen = true } }

    // A show's list row says how many seasons it has — read for the rows on screen only.
    if (!movies && viewMode == SettingsRepository.VodViewMode.LIST) {
        LaunchedEffect(listState, items) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collect { indices -> vm.loadSeasonCounts(indices.mapNotNull { items.peekOrNull(it)?.id }) }
        }
    }

    val listPane = @Composable {
    // The column count comes from the width this pane actually got, not the window's: beside a
    // detail pane, or next to the rail, the grid has less room than the screen is wide.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Roughly one poster per 110 dp of width, which is three on a phone and eight on a tablet in
    // landscape. The user's own number wins whenever they have set one.
    // Beside the card the posters are six across 520 dp.
    val columns = chosenColumns.takeIf { it > 0 }
        ?: (maxWidth.value.toInt() / (if (twoPane) WIDE_COLUMN_WIDTH_DP else COLUMN_WIDTH_DP)).coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    Column(Modifier.fillMaxSize()) {
        if (switchTabs.size > 1) {
            StageSegmented(
                options = switchTabs.map { stringResource(if (it == LibraryTab.MOVIES) R.string.common_nav_movies else R.string.common_nav_series) },
                selected = switchTabs.indexOf(tab).coerceAtLeast(0),
                onSelect = { vm.select(switchTabs[it]) },
                fill = true,
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = TOOLS_TOP),
            )
        }
        // `.tools`: the search field takes the room, then the order and Grid | List at the right. No categories
        // tool: the crumb under the title opens the same sheet.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = TOOLS_TOP, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageSearchField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = stringResource(R.string.common_search),
                inline = true,
                modifier = Modifier.weight(1f),
            )
            // The order and Grid | List sit at the right edge and the search takes all the rest; a long order
            // ("Hinzugefügt am" at a large font) shortens at 150 dp rather than squeezing the field to its icon.
            StageTool(
                onClick = { sortOpen = true },
                icon = MobileIcons.Sort,
                value = stringResource(sort.labelRes()),
                on = sortOpen,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            val views = SettingsRepository.VodViewMode.entries
            StageSegmented(
                options = views.map { stringResource(it.labelRes()) },
                selected = views.indexOf(viewMode),
                onSelect = { vm.setViewMode(views[it]) },
                icons = listOf(MobileIcons.GridView, MobileIcons.FormatListBulleted),
                iconOnly = true,
            )
        }
        // Empty only once the first page has actually come back — otherwise every category change
        // flashes "No movies here" for as long as the query takes on a 170k-title catalogue.
        if (categories.firstOrNull { it.key == selected }?.sealed == true) {
            tv.own.owntv.mobile.ui.categorylock.LockedCategoryState(onEnterPin = { lock.enterPin(selected) })
        } else if (items.itemCount == 0 && items.loadState.refresh !is LoadState.Loading) {
            EmptyLibrary(tab, query)
        } else if (viewMode == SettingsRepository.VodViewMode.LIST) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { item ->
                        VodListRow(
                            item = item,
                            seasons = seasonCounts[item.id]?.takeIf { !movies && it > 0 },
                            progress = progressOf(item.id),
                            watched = item.id in watched,
                            mark = marks[item.sourceId],
                            selected = item.id == lastOpened,
                            onClick = { open(item) },
                            onLongClick = { menuFor = item },
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToResize(columns) { vm.setGridColumns(it) },
            ) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { item ->
                        StagePoster(
                            imageUrl = item.posterUrl,
                            title = item.name,
                            rating = item.rating?.takeIf { it > 0 }?.let(::formatRating),
                            mark = marks[item.sourceId],
                            watched = item.id in watched,
                            progress = progressOf(item.id),
                            selected = item.id == (if (twoPane) openItem else lastOpened),
                            onClick = { open(item) },
                            onLongClick = { menuFor = item },
                        )
                    }
                }
            }
        }
    }

    if (sortOpen) {
        VodSortSheet(
            tab = tab,
            sort = sort,
            viewMode = viewMode,
            columns = columns,
            onSort = vm::setSort,
            onViewMode = vm::setViewMode,
            onColumns = vm::setGridColumns,
            onDismiss = { sortOpen = false },
        )
    }
    }
    }

    if (twoPane) {
        TwoPane(
            list = listPane,
            detail = {
                openItem?.let { id ->
                    LibraryTitleCard(tab = tab, itemId = id, onPlay = onPlay, onOpen = { onOpenItem(tab, id) })
                }
            },
            modifier = modifier,
            // The grid 520 of 791 dp.
            listShare = WIDE_GRID_SHARE,
        )
    } else {
        Box(modifier) { listPane() }
    }

    if (categoriesOpen) {
        val counts by vm.categoryCounts.collectAsStateWithLifecycle()
        val showCounts by vm.sheetCounts.collectAsStateWithLifecycle()
        VodCategoriesSheet(
            tab = tab,
            categories = categories,
            selected = selected,
            counts = counts,
            showCounts = showCounts,
            marks = marks,
            onSelect = vm::select,
            onHold = { categoryMenuFor = it },
            onDismiss = { categoriesOpen = false },
        )
    }

    menuFor?.let { item ->
        VodMenu(
            item = item,
            tab = tab,
            selected = selected,
            isFavorite = item.id in favorites,
            originName = categoryLabel,
            categories = categories,
            vm = vm,
            onDismiss = { menuFor = null },
        )
    }

    categoryMenuFor?.let { category ->
        val name = category.label(tab)
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
            initial = category.label(tab),
            onConfirm = { vm.renameCategory(category.key, it.takeIf { t -> t.isNotBlank() }); renamingFor = null },
            onDismiss = { renamingFor = null },
        )
    }
}

/**
 * `.chrow` of the list view: 92 dp — the poster 52 × 78 (radius 9), the title (14.5 / 700, two lines
 * allowed), "2026 · ★ 8.4 · 1 h 30 min" (12 muted, the rating in gold) or a show's seasons, the progress
 * under it (140 wide); at the end ✓ when watched, else the playlist mark. [selected] = the title last opened.
 */
@Composable
private fun VodListRow(
    item: VodItem,
    seasons: Int?,
    progress: Float?,
    watched: Boolean,
    mark: PlaylistMark?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalStagePalette.current
    val separator = stringResource(R.string.content_epg_bits_separator)
    val length = item.durationSecs?.takeIf { it >= 60 }?.let { durationText(it / 60L) }
        ?: seasons?.let { pluralStringResource(R.plurals.home_trending_seasons, it, it) }
    val line = buildAnnotatedString {
        val parts = listOfNotNull(
            item.year?.takeIf { it > 0 }?.let { { append(formatYear(it)) } },
            item.rating?.takeIf { it > 0 }?.let { { withStyle(SpanStyle(color = RatingGold)) { append("★ " + formatRating(it)) } } },
            length?.let { { append(it) } },
        )
        parts.forEachIndexed { i, part ->
            if (i > 0) append(separator)
            part()
        }
    }
    StageRow(onClick = onClick, onLongClick = onLongClick, selected = selected, height = 92.dp) {
        AsyncImage(
            item.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp, 78.dp).clip(RoundedCornerShape(9.dp)).background(palette.cardSolid),
        )
        Column(Modifier.weight(1f)) {
            Text(item.name, style = stageText(14.5f, 700), color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) {
                Text(line, style = stageText(12f, 400), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            progress?.let { StageProgress(it, Modifier.padding(top = 6.dp).width(140.dp), thin = true) }
        }
        when {
            watched -> StageIcon(MobileIcons.Check, 14.dp, stageAccent.accent)
            mark != null -> StagePlaylistMark(mark)
        }
    }
}

/**
 * Sort: "Sort movies · Applies to every category in this section", the four orders — each with its
 * line while it is not the current one, the current filled and ticked — then VIEW Grid | List and, on the
 * grid, POSTER SIZE (the number the pinch sets; to the right is bigger posters, fewer a row).
 */
@Composable
private fun VodSortSheet(
    tab: LibraryTab,
    sort: SettingsRepository.SortMode,
    viewMode: SettingsRepository.VodViewMode,
    columns: Int,
    onSort: (SettingsRepository.SortMode) -> Unit,
    onViewMode: (SettingsRepository.VodViewMode) -> Unit,
    onColumns: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(if (tab == LibraryTab.MOVIES) R.string.content_sort_movies_title else R.string.content_sort_series_title)
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title, subtitle = stringResource(R.string.content_sort_applies_hint))
        listOf(
            Triple(SettingsRepository.SortMode.PLAYLIST, MobileIcons.Layers, R.string.content_sort_provider_order_hint),
            Triple(SettingsRepository.SortMode.ALPHA, MobileIcons.FormatListBulleted, null),
            Triple(SettingsRepository.SortMode.RATING, MobileIcons.StarBorder, R.string.content_sort_rating_hint),
            Triple(SettingsRepository.SortMode.DATE_ADDED, MobileIcons.Schedule, R.string.content_sort_date_added_hint),
        ).forEach { (mode, icon, hint) ->
            val current = mode == sort
            StageMenuItem(
                label = stringResource(mode.labelRes()),
                onClick = { onSort(mode) },
                icon = icon,
                value = hint?.takeIf { !current }?.let { stringResource(it) },
                filled = current,
                checked = current,
            )
        }
        StageGroupLabel(stringResource(R.string.content_menu_group_view))
        val views = SettingsRepository.VodViewMode.entries
        StageChoiceRow(
            options = views.map { stringResource(it.labelRes()) },
            selected = views.indexOf(viewMode),
            onSelect = { onViewMode(views[it]) },
        )
        if (viewMode == SettingsRepository.VodViewMode.GRID) {
            StageGroupLabel(stringResource(R.string.content_poster_size))
            StageSizeBar(
                fraction = (MAX_COLUMNS - columns).toFloat() / (MAX_COLUMNS - MIN_COLUMNS),
                onChange = { onColumns(MAX_COLUMNS - kotlin.math.round(it * (MAX_COLUMNS - MIN_COLUMNS)).toInt()) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Nothing in the category, or nothing matching the search — core's sentences, in the Stage type. */
@Composable
private fun EmptyLibrary(tab: LibraryTab, query: String) {
    val movies = tab == LibraryTab.MOVIES
    val q = query.trim()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                q.isNotEmpty() -> stringResource(if (movies) R.string.content_no_movies_found else R.string.content_no_series_found, q)
                else -> stringResource(if (movies) R.string.content_no_movies_here else R.string.content_no_series_here)
            },
            style = stageText(13.5f, 500),
            color = LocalStagePalette.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/** The entry's name: a translated label for the three fixed ones; a provider folder without its tags. */
@Composable
internal fun VodCategory.label(tab: LibraryTab): String = when (builtIn) {
    VodCategory.BuiltIn.ALL -> stringResource(
        if (tab == LibraryTab.MOVIES) R.string.content_category_all_movies else R.string.content_category_all_series,
    )
    VodCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    VodCategory.BuiltIn.HISTORY -> stringResource(R.string.content_category_history)
    null -> remember(title) { ProviderTags.parse(title.orEmpty()).name }
}

private fun SettingsRepository.SortMode.labelRes() = when (this) {
    SettingsRepository.SortMode.PLAYLIST -> R.string.content_sort_provider_order
    SettingsRepository.SortMode.ALPHA -> R.string.settings_sort_alpha
    SettingsRepository.SortMode.RATING -> R.string.settings_sort_rating
    SettingsRepository.SortMode.DATE_ADDED -> R.string.settings_sort_date_added
}

private fun SettingsRepository.VodViewMode.labelRes() = when (this) {
    SettingsRepository.VodViewMode.GRID -> R.string.settings_view_grid
    SettingsRepository.VodViewMode.LIST -> R.string.settings_view_list
}

/** [tv.own.owntv.mobile.ui.screens.ObeyScrollToTop] for a grid. */
@Composable
private fun LazyGridState.ObeyGridScrollToTop(route: String, scrollToTop: SharedFlow<String>) {
    val instant = LocalAnimations.current == AnimationLevel.OFF
    LaunchedEffect(route, instant) {
        scrollToTop.collect { requested ->
            if (requested != route) return@collect
            if (instant) scrollToItem(0) else animateScrollToItem(0)
        }
    }
}

/**
 * Pinch the posters bigger or smaller.
 *
 * It watches for a second finger and only then takes the gesture, so an ordinary one-finger scroll
 * is never stolen from the grid underneath. One step per pinch: the count changes once and the
 * gesture then does nothing until the fingers come up, which stops a slow squeeze from running the
 * whole way from eight columns to two.
 */
private fun Modifier.pinchToResize(columns: Int, onChange: (Int) -> Unit): Modifier =
    pointerInput(columns) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var startDistance = 0f
            var changed = false
            var down = true
            while (down) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                    if (startDistance == 0f) {
                        startDistance = distance
                    } else if (!changed) {
                        val ratio = distance / startDistance
                        if (ratio > PINCH_OUT || ratio < PINCH_IN) {
                            // Fingers apart means bigger posters, which means fewer of them.
                            val next = if (ratio > 1f) columns - 1 else columns + 1
                            onChange(next.coerceIn(MIN_COLUMNS, MAX_COLUMNS))
                            changed = true
                        }
                    }
                    pressed.forEach { it.consume() }
                }
                down = event.changes.any { it.pressed }
            }
        }
    }

/**
 * The first title in the grid, without asking the pager to load a page for it — the detail pane
 * follows it, and a preview must never be the reason a page is fetched.
 */
private fun LazyPagingItems<VodItem>.peekFirstId(): Long? = itemSnapshotList.firstOrNull()?.id

/** The room between the crumb and the tools (`.tools` at 128 under a 102 dp header), as on Live TV. */
private val TOOLS_TOP = 26.dp

/** The rating's gold, `#ffcc4d` (the same on the poster chip). */
private val RatingGold = Color(0xFFFFCC4D)

private const val COLUMN_WIDTH_DP = 110
private const val WIDE_COLUMN_WIDTH_DP = 86
private const val WIDE_GRID_SHARE = 0.66f
private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 8
private const val PINCH_OUT = 1.25f
private const val PINCH_IN = 0.8f
