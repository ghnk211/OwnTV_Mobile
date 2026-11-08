package tv.own.owntv.mobile.ui.screens.vod

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.categorylock.PinAttempt
import androidx.compose.runtime.rememberCoroutineScope
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.episodeDisplayNames
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.categorylock.PinEntrySheet
import tv.own.owntv.mobile.ui.categorylock.pinEntryTitle
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.ui.screens.settings.customize.TextPromptDialog
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StagePoster
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The TVbox pages: config (import/switch), home (sites/categories/grid/search), detail
 * (lines/episodes/play). Stage components throughout; every user-visible copy comes from the
 * three-language string tables — the business layer's own messages arrive as data.
 */

private val PageSidePadding = 20.dp

// ---------------------------------------------------------------------------
// Config page
// ---------------------------------------------------------------------------

@Composable
internal fun TvboxConfigPage(
    state: TvboxConfigUiState,
    onImportUrl: (String) -> Unit,
    onImportText: (String, String?) -> Unit,
    onSwitch: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
    onConsumeBanner: () -> Unit,
) {
    val palette = LocalStagePalette.current
    var url by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Long?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportText(uri.toString(), uri.lastPathSegment?.substringAfterLast('/'))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = PageSidePadding)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StageButton(onClick = onBack, icon = MobileIcons.ArrowBack)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.tvbox_config_title),
                style = stageText(17f, 800),
                color = palette.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))

        // Import by URL: the whole ecosystem's entry point.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.cardSolid)
                    .clickable(enabled = false) {}
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = url.ifEmpty { stringResource(R.string.tvbox_config_url_hint) },
                    style = stageText(12.5f, 500),
                    color = if (url.isEmpty()) palette.muted else palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            stringResource(R.string.tvbox_config_url_hint),
            style = stageText(11f, 500),
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // URL entry reuses the rename dialog (a plain text prompt with a keyboard).
        var enteringUrl by remember { mutableStateOf(false) }
        if (enteringUrl) {
            TextPromptDialog(
                title = stringResource(R.string.tvbox_config_url_title),
                initial = url,
                allowBlank = false,
                confirmLabel = stringResource(R.string.tvbox_config_import),
                keyboardType = KeyboardType.Uri,
                onConfirm = {
                    url = it
                    onImportUrl(it.trim())
                },
                onDismiss = { enteringUrl = false },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageButton(
                onClick = { enteringUrl = true },
                text = stringResource(R.string.tvbox_config_import),
                style = StageButtonStyle.PRIMARY,
                enabled = !state.importing,
            )
            StageButton(
                onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                text = stringResource(R.string.tvbox_config_local),
                enabled = !state.importing,
            )
        }
        if (state.importing) {
            Text(
                stringResource(R.string.tvbox_config_importing),
                style = stageText(11.5f, 500),
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.spiderDownloading) {
            Text(
                stringResource(R.string.tvbox_config_spider_downloading),
                style = stageText(11.5f, 500),
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        state.banner?.let { banner ->
            LaunchedEffect(banner) { onConsumeBanner() }
            Text(
                text = when (banner) {
                    is TvboxBanner.Imported ->
                        stringResource(R.string.tvbox_import_ok, banner.siteCount) +
                            if (banner.spiderDownloading) stringResource(R.string.tvbox_import_downloading) else ""
                    is TvboxBanner.Failed -> banner.message
                },
                style = stageText(12f, 600),
                color = if (banner is TvboxBanner.Failed) palette.danger else palette.text,
                modifier = Modifier.padding(top = 10.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.tvbox_config_list_title),
            style = stageText(13f, 700),
            color = palette.text,
        )
        Spacer(Modifier.height(8.dp))

        if (state.configs.isEmpty()) {
            Text(
                stringResource(R.string.tvbox_config_empty),
                style = stageText(12f, 500),
                color = palette.muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.configs, key = { it.id }) { meta ->
                    StageSettingRow(
                        title = meta.name,
                        subtitle = meta.url ?: stringResource(R.string.tvbox_config_source_local),
                        icon = MobileIcons.VideoLibrary,
                        onClick = { onSwitch(meta.id) },
                        current = meta.id == state.activeId,
                        trailing = {
                            StageButton(
                                onClick = { deleting = meta.id },
                                icon = MobileIcons.Delete,
                                small = true,
                            )
                        },
                    )
                }
            }
        }
    }

    deleting?.let { id ->
        TextPromptDialog(
            title = stringResource(R.string.tvbox_config_delete_confirm),
            initial = "",
            allowBlank = true,
            confirmLabel = stringResource(R.string.tvbox_config_delete),
            onConfirm = {
                onDelete(id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Home page
// ---------------------------------------------------------------------------

@Composable
internal fun TvboxHomePage(
    configState: TvboxConfigUiState,
    state: TvboxHomeUiState,
    onOpenConfig: () -> Unit,
    onOpenSite: (SiteConfig) -> Unit,
    onSelectCategory: (String, String) -> Unit,
    onSetFilter: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    val palette = LocalStagePalette.current
    var siteMenu by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    val lockedPrefix = stringResource(R.string.tvbox_locked_prefix)

    Column(Modifier.fillMaxSize()) {
        // Top bar: site selector, search, config.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PageSidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StageButton(
                onClick = { siteMenu = true },
                text = state.currentSite?.name ?: stringResource(R.string.tvbox_home_pick_site),
                trailing = state.sites.size.let { if (it > 0) it.toString() else null },
            )
            Spacer(Modifier.weight(1f))
            StageButton(onClick = { searchOpen = true }, icon = MobileIcons.Search)
            StageButton(onClick = onOpenConfig, icon = MobileIcons.VideoLibrary)
        }
        Spacer(Modifier.height(10.dp))

        if (state.currentSite == null) {
            EmptyHint(stringResource(R.string.tvbox_home_no_site))
        } else {
            // Category chips (locked rows keep their row and show a lock — plan §5.5.4).
            if (state.categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PageSidePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(state.categories, key = { _, c -> c.typeId + c.typeName }) { _, cat ->
                        val sealed = cat.typeId in state.sealedKeys
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.cardSolid)
                                .clickable { onSelectCategory(cat.typeId, cat.typeName) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                (if (sealed) lockedPrefix else "") + cat.typeName,
                                style = stageText(12f, 600),
                                color = if (sealed) palette.muted else palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Filter chips per dimension.
            state.filters.forEach { f ->
                if (!f.usable) return@forEach
                val selectedValue = state.selectedFilters[f.key].orEmpty()
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PageSidePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(f.values, key = { it.v }) { v ->
                        val on = selectedValue == v.v
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) palette.text.copy(alpha = 0.14f) else palette.cardSolid)
                                .clickable { onSetFilter(f.key, v.v) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                v.n,
                                style = stageText(11.5f, 600),
                                color = palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (state.loading) {
                EmptyHint(stringResource(R.string.tvbox_loading))
            } else if (state.error != null) {
                EmptyHint(stringResource(R.string.tvbox_error_load))
            } else if (state.movies.isEmpty()) {
                EmptyHint(stringResource(R.string.tvbox_home_empty))
            } else {
                PosterGrid(
                    movies = state.movies,
                    onOpenDetail = onOpenDetail,
                    onLoadMore = onLoadMore,
                    modifiers = state.movies.associate { it.id to it.remark },
                )
            }
        }
    }

    // Search overlay
    if (searchOpen) {
        TvboxSearchPage(
            state = state,
            onSearch = onSearch,
            onClearSearch = onClearSearch,
            onOpenDetail = onOpenDetail,
            onClose = { searchOpen = false },
        )
    }

    // Site picker sheet
    if (siteMenu) {
        SitePickerSheet(
            sites = state.sites,
            currentKey = state.currentSite?.key,
            onPick = {
                onOpenSite(it)
                siteMenu = false
            },
            onDismiss = { siteMenu = false },
        )
    }
}

@Composable
private fun SitePickerSheet(
    sites: List<SiteConfig>,
    currentKey: String?,
    onPick: (SiteConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var dismissed by remember { mutableStateOf(false) }
    if (!dismissed) {
        // Drawn as a full-screen list overlay for space; Stage rows.
        Column(Modifier.fillMaxSize().background(LocalStagePalette.current.scrim)) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(horizontal = PageSidePadding), verticalAlignment = Alignment.CenterVertically) {
                StageButton(onClick = onDismiss, icon = MobileIcons.Close)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.tvbox_home_pick_site),
                    style = stageText(15f, 800),
                    color = LocalStagePalette.current.text,
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(contentPadding = PaddingValues(horizontal = PageSidePadding, vertical = 8.dp)) {
                items(sites, key = { it.key }) { site ->
                    StageRow(onClick = { onPick(site); dismissed = true }, current = site.key == currentKey) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                site.name,
                                style = stageText(13f, 700),
                                color = LocalStagePalette.current.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                site.api,
                                style = stageText(10.5f, 500),
                                color = LocalStagePalette.current.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(
    movies: List<Movie>,
    onOpenDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    modifiers: Map<String, String?>,
) {
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= movies.size - 8
        }
    }
    LaunchedEffect(nearEnd, movies.size) {
        snapshotFlow { nearEnd }.distinctUntilChanged().collect { if (it) onLoadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = movies.size, key = { i -> movies[i].id + i }) { i ->
            val movie = movies[i]
            StagePoster(
                imageUrl = movie.pic,
                title = movie.name,
                subtitle = modifiers[movie.id],
                onClick = { onOpenDetail(movie.donorSiteKey ?: "", movie.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Search page
// ---------------------------------------------------------------------------

@Composable
private fun TvboxSearchPage(
    state: TvboxHomeUiState,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalStagePalette.current
    Column(Modifier.fillMaxSize().background(palette.scrim)) {
        Spacer(Modifier.height(24.dp))
        Row(Modifier.padding(horizontal = PageSidePadding), verticalAlignment = Alignment.CenterVertically) {
            StageButton(onClick = onClose, icon = MobileIcons.ArrowBack)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.tvbox_search_title),
                style = stageText(15f, 800),
                color = palette.text,
            )
        }
        Spacer(Modifier.height(12.dp))
        var entering by remember { mutableStateOf(true) }
        if (entering) {
            TextPromptDialog(
                title = stringResource(R.string.tvbox_search_title),
                initial = state.searchKeyword,
                allowBlank = false,
                confirmLabel = stringResource(R.string.tvbox_search_go),
                onConfirm = {
                    onSearch(it.trim())
                    entering = false
                },
                onDismiss = {
                    entering = false
                    onClose()
                },
            )
        }
        if (state.searching) {
            EmptyHint(stringResource(R.string.tvbox_searching))
        } else if (state.searchResults.isEmpty()) {
            EmptyHint(stringResource(R.string.tvbox_search_empty))
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = PageSidePadding, vertical = 4.dp)) {
                state.searchResults.forEach { group ->
                    item(key = "site-" + group.siteKey) {
                        Text(
                            group.siteName,
                            style = stageText(12.5f, 700),
                            color = palette.muted,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    items(group.movies, key = { group.siteKey + it.id + it.name }) { movie ->
                        StageRow(onClick = { onOpenDetail(group.siteKey, movie.id) }) {
                            Text(
                                movie.name,
                                style = stageText(12.5f, 600),
                                color = palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail page
// ---------------------------------------------------------------------------

@Composable
internal fun TvboxDetailPage(
    state: TvboxDetailUiState,
    onBack: () -> Unit,
    onSelectLine: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onUnlockPin: suspend (String) -> PinAttempt,
    onUnlocked: () -> Unit,
) {
    val palette = LocalStagePalette.current
    var pinSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = PageSidePadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StageButton(onClick = onBack, icon = MobileIcons.ArrowBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.name,
                    style = stageText(16f, 800),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaLine = listOfNotNull(state.year, state.typeName, state.remark).joinToString(" · ")
                if (metaLine.isNotEmpty()) {
                    Text(
                        metaLine,
                        style = stageText(11f, 500),
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StageButton(onClick = onToggleFavorite, icon = if (state.favorite) MobileIcons.Check else MobileIcons.Add)
        }

        when {
            state.loading -> EmptyHint(stringResource(R.string.tvbox_loading))
            state.blockedByKids -> EmptyHint(stringResource(R.string.tvbox_detail_blocked_kids))
            state.error != null -> EmptyHint(stringResource(R.string.tvbox_error_detail))
            state.sealed -> {
                EmptyHint(stringResource(R.string.tvbox_detail_locked))
                LaunchedEffect(state.sealed) { pinSheet = true }
            }
            else -> {
                // Lines selector
                if (state.lines.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PageSidePadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(state.lines, key = { _, l -> l.index }) { i, line ->
                            val on = i == state.currentLineIndex
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) palette.text.copy(alpha = 0.14f) else palette.cardSolid)
                                    .clickable { onSelectLine(i) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.tvbox_line_name, i + 1, line.name),
                                    style = stageText(11.5f, 600),
                                    color = palette.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val episodes = state.lines.getOrNull(state.currentLineIndex)?.episodes.orEmpty()
                if (episodes.isEmpty()) {
                    EmptyHint(stringResource(R.string.tvbox_detail_no_episodes))
                } else {
                    val names = episodeDisplayNames(episodes)
                    Text(
                        stringResource(R.string.tvbox_episodes, episodes.size),
                        style = stageText(12f, 700),
                        color = palette.muted,
                        modifier = Modifier.padding(horizontal = PageSidePadding, vertical = 6.dp),
                    )
                    val resumeIndex = state.resumeIndex
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = PageSidePadding, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(count = episodes.size) { i ->
                            val label = names.getOrNull(i) ?: episodes[i].name
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (i == resumeIndex) palette.text.copy(alpha = 0.16f) else palette.cardSolid)
                                    .clickable { onPlay(i) }
                                    .padding(vertical = 12.dp, horizontal = 6.dp),
                            ) {
                                Text(
                                    label,
                                    style = stageText(11.5f, 600),
                                    color = palette.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pinSheet && state.sealed) {
        var waitSeconds by remember { mutableStateOf(0) }
        var wrong by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PinEntrySheet(
            title = pinEntryTitle(wrong = wrong, waitSeconds = waitSeconds),
            description = stringResource(R.string.tvbox_pin_unlock_desc),
            onSubmit = { entered ->
                scope.launch {
                    when (onUnlockPin(entered)) {
                        PinAttempt.Accepted -> {
                            onUnlocked()
                            pinSheet = false
                        }
                        PinAttempt.Wrong -> wrong = true
                        is PinAttempt.Wait -> {
                            wrong = false
                            waitSeconds = 30
                        }
                    }
                }
            },
            onDismiss = { pinSheet = false },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun EmptyHint(text: String) {
    val palette = LocalStagePalette.current
    Box(Modifier.fillMaxSize().padding(horizontal = PageSidePadding), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = stageText(12.5f, 500, lineHeight = 1.45f),
            color = palette.muted,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
