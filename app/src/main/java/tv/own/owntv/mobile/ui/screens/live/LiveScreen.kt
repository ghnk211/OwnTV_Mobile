package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.categorylock.SealedCategoryAction
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.categorylock.rememberCategoryLock
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.home.formatCount
import tv.own.owntv.mobile.ui.screens.settings.customize.TextPromptDialog
import tv.own.owntv.mobile.ui.shell.PageCrumb
import tv.own.owntv.mobile.ui.shell.ProvidePageCrumb
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * Live TV: the categories, and nothing else.
 *
 * The channel list this tab used to draw now lives in the player — the left overlay's second column —
 * so the tab itself is the list of lists: Favourites, Recently watched, Catch-up, All channels, then
 * the playlist's own folders and the user's. A tap is a request to watch, not to browse: the
 * category's first channel starts and the player opens over this page. An empty category starts All
 * channels' first one instead, keeps the pick on screen and says nothing about the swap (B2/B6).
 *
 * The rows are the ones the categories sheet draws ([LiveCategoriesSheet], still used by the TV
 * Guide), so a rename, a hide, a count or a lock means the same thing in both places. A category the
 * Customize PIN has not opened is played through the lock: the tap is answered by the PIN sheet, and
 * the play follows once it is right.
 */
@Composable
fun LiveScreen(
    scrollToTop: SharedFlow<String>,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    vm: LiveViewModel = koinViewModel(),
    /** Adds a playlist — the only thing a Live TV with no source can offer (6.1). */
    onOpenSetup: () -> Unit = {},
) {
    val tuner: LiveTuner = koinInject()
    val scope = rememberCoroutineScope()
    val lock = rememberCategoryLock(MediaType.LIVE)

    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val counts by vm.categoryCounts.collectAsStateWithLifecycle()
    val showCounts by vm.sheetCounts.collectAsStateWithLifecycle()
    val marks by vm.playlistMarks.collectAsStateWithLifecycle()
    // Null while the profile is still being read, so a normal install never flashes the empty state.
    val liveSources by vm.liveSources.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var categoryMenuFor by remember { mutableStateOf<LiveCategory?>(null) }
    var renamingFor by remember { mutableStateOf<LiveCategory?>(null) }

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = "live", scrollToTop = scrollToTop)

    val categoryHeading = stringResource(R.string.content_category_browser_title)

    // A channel opened from Search, or "Start on: Last channel · Live TV": the category it lives in
    // is the one highlighted. There is no list here to scroll it into, so a start-up channel starts
    // playing — Search has already opened the player itself.
    LaunchedEffect(Unit) {
        val opening = vm.applyOpeningSelection() ?: return@LaunchedEffect
        if (opening.preview) {
            tuner.tune(opening.channel.id)
            onOpenPlayer()
        }
    }

    ProvidePageCrumb { owner ->
        // Read outside the lambda: the crumb builder is not composable.
        PageCrumb(categoryHeading, null, chevron = false, owner = owner) {}
    }

    fun play(key: LiveKey) {
        vm.select(key)
        scope.launch {
            val first = vm.firstChannelOf(key) ?: vm.firstChannelOf(LiveKey.All) ?: return@launch
            tuner.tune(first.id)
            onOpenPlayer()
        }
    }

    // A locked folder: the PIN first, the play after it. An open one plays at once.
    val openCategory: (LiveCategory) -> Unit = { category ->
        lock.guarded(category.key, category.sealed, SealedCategoryAction.PLAY) { play(category.key) }
    }

    if (liveSources?.isEmpty() == true) {
        NoSources(onOpenSetup, modifier)
        return
    }

    val fixed = remember(categories) {
        listOf(LiveKey.Favorites, LiveKey.History, LiveKey.Catchup, LiveKey.All)
            .mapNotNull { key -> categories.firstOrNull { it.key == key } }
    }
    val groups = remember(categories) { categories.filter { it.builtIn == null } }
    val parsed = remember(groups) { groups.associateWith { ProviderTags.parse(it.title.orEmpty()) } }
    val country = remember(parsed) { ProviderTags.sharedCountry(parsed.values.toList()) }
    val heading = if (country != null) {
        stringResource(R.string.content_live_groups_country, country)
    } else {
        stringResource(R.string.content_live_groups)
    }
    val labels = fixed.associateWith { it.label() }
    val q = query.trim()
    val shownFixed = fixed.filter { q.isEmpty() || labels.getValue(it).contains(q, ignoreCase = true) }
    val shownGroups = groups.filter { q.isEmpty() || ProviderTags.groupLabel(parsed.getValue(it), country).contains(q, ignoreCase = true) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 100.dp),
    ) {
        item {
            StageSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.content_search_categories),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
            )
        }
        items(shownFixed, key = { it.key.toString() }) { category ->
            CategoryRow(
                category = category,
                label = labels.getValue(category),
                counts = counts,
                showCounts = showCounts,
                selected = selected,
                marks = marks,
                onPick = openCategory,
                // The four fixed lists have no name to change and no folder to hide: the sheet
                // offers its menu on the provider groups only, and so does the page.
                onHold = null,
            )
        }
        if (shownGroups.isNotEmpty()) item { StageGroupLabel(heading) }
        items(shownGroups, key = { it.key.toString() }) { category ->
            CategoryRow(
                category = category,
                label = ProviderTags.groupLabel(parsed.getValue(category), country),
                counts = counts,
                showCounts = showCounts,
                selected = selected,
                marks = marks,
                onPick = openCategory,
                onHold = { categoryMenuFor = it },
                tags = parsed.getValue(category).tags,
            )
        }
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
}

/**
 * One category on the page: its icon or lock, its name, what it holds, its playlist mark and tags.
 * A sealed folder shows no count — how much it holds is the lock's business — and the one the page
 * would open next is filled, exactly as the sheet fills it.
 */
@Composable
private fun CategoryRow(
    category: LiveCategory,
    label: String,
    counts: Map<LiveKey, Int>,
    showCounts: Boolean,
    selected: LiveKey,
    marks: Map<Long, PlaylistMark>,
    onPick: (LiveCategory) -> Unit,
    onHold: ((LiveCategory) -> Unit)?,
    tags: List<String> = emptyList(),
) {
    StageMenuItem(
        label = label,
        onClick = { onPick(category) },
        onLongClick = onHold?.let { hold -> { hold(category) } },
        icon = if (category.locked) MobileIcons.Lock else category.icon(),
        filled = category.key == selected,
        count = if (category.sealed) null else counts[category.key]?.takeIf { showCounts }?.let(::formatCount),
        tags = if (tags.isEmpty()) null else { { tags.forEach { StageTag(it) } } },
        mark = category.sourceId?.let(marks::get),
        height = 52.dp,
    )
}

/**
 * A Live TV with no playlist behind it. Not an empty category — there is nothing to search, nothing
 * to pick, and the only useful thing is the way to add one: the setup flow, on the Add a source step.
 */
@Composable
private fun NoSources(onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.content_epg_add_playlist_first),
                style = stageText(13.5f, 500),
                color = LocalStagePalette.current.muted,
                textAlign = TextAlign.Center,
            )
            StageButton(
                onClick = onOpenSetup,
                text = stringResource(R.string.setup_add_playlist),
                icon = MobileIcons.Add,
                style = StageButtonStyle.PRIMARY,
            )
        }
    }
}
