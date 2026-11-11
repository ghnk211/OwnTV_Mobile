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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.customize.MoveKind
import androidx.compose.runtime.rememberCoroutineScope
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.episodeDisplayNames
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
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
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.stageAccent
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
            // 停留式消费：立即消费会让横幅只活一帧（回归里横幅从未可见）。给足阅读时间。
            LaunchedEffect(banner) {
                kotlinx.coroutines.delay(4_000)
                onConsumeBanner()
            }
            Text(
                text = when (banner) {
                    is TvboxBanner.Imported ->
                        pluralStringResource(R.plurals.tvbox_import_ok, banner.siteCount, banner.siteCount) +
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

/**
 * The header's "switch source" sheet: the imported configurations and nothing else. Importing a
 * new one and deleting an old one stay on the Sources page; this is the quick switch between the
 * configurations already there, which is what the button beside the title is for.
 */
@Composable
internal fun TvboxSourcePickerSheet(
    state: TvboxConfigUiState,
    onSwitch: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStagePalette.current
    val title = stringResource(R.string.tvbox_config_title)
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title)
        if (state.configs.isEmpty()) {
            Text(
                stringResource(R.string.tvbox_config_empty),
                style = stageText(12f, 500),
                color = palette.muted,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 18.dp),
            )
            return@MobileBottomSheet
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(state.configs, key = { it.id }) { meta ->
                StageMenuItem(
                    label = meta.name,
                    onClick = {
                        onSwitch(meta.id)
                        onDismiss()
                    },
                    filled = meta.id == state.activeId,
                )
            }
        }
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
    onToggleLock: (TvboxCategory) -> Unit,
    onConsumeLockEvent: () -> Unit,
    onUnlockCategory: suspend (TvboxCategory, String) -> PinAttempt,
    onHideCategory: (String) -> Unit,
    onRenameCategory: (String, String?) -> Unit,
    onMoveCategory: (TvboxCategory, MoveKind) -> Unit,
    onSetFilter: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (String, String, String, String) -> Unit,
    categoryContext: Pair<String, String>,
) {
    val palette = LocalStagePalette.current
    // The one selected style every chip row on this page uses — categories and the filter
    // dimensions alike: the bottom bar's accent tile and accent text.
    val accent = stageAccent.accent
    var siteMenu by remember { mutableStateOf(false) }
    var lockMenuFor by remember { mutableStateOf<TvboxCategory?>(null) }
    var pinSheetFor by remember { mutableStateOf<TvboxCategory?>(null) }
    var renamingFor by remember { mutableStateOf<TvboxCategory?>(null) }
    val lockedPrefix = stringResource(R.string.tvbox_locked_prefix)

    // Lock/unlock feedback (no-PIN guidance included); held for a read, then consumed.
    state.lockEvent?.let { event ->
        val message = stringResource(
            when (event) {
                TvboxLockEvent.LOCKED -> R.string.tvbox_lock_locked
                TvboxLockEvent.UNLOCKED -> R.string.tvbox_lock_unlocked
                TvboxLockEvent.NO_PIN -> R.string.tvbox_lock_no_pin
            },
        )
        LaunchedEffect(event) {
            kotlinx.coroutines.delay(3_000)
            onConsumeLockEvent()
        }
        Text(
            message,
            style = stageText(12f, 600),
            color = palette.text,
            modifier = Modifier
                .padding(horizontal = PageSidePadding, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.cardSolid)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: site selector and config. Search lives in the shell header now — one entry, the
        // global search page, whose online section covers the TVbox sites (plan P3).
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
                    itemsIndexed(state.categories, key = { _, c -> c.key }) { _, cat ->
                        val sealed = cat.sealed
                        // The open category is marked the way the bottom bar marks the tab the user is
                        // on — the accent's 14 % tile for the fill and the accent for the text, the
                        // same treatment the filter rows below use.
                        val on = cat.typeId == categoryContext.first
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) accent.copy(alpha = 0.14f) else palette.cardSolid)
                                .combinedClickable(
                                    // plan §5.5.6: a locked row shows its lock and pops the PIN sheet.
                                    onClick = {
                                        if (sealed) pinSheetFor = cat else onSelectCategory(cat.typeId, cat.typeName)
                                    },
                                    // plan §5.5.7: the category row's "..." entry — long press opens the menu.
                                    onLongClick = { lockMenuFor = cat },
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                (if (sealed) lockedPrefix else "") + cat.displayName,
                                style = stageText(12f, 600),
                                color = when {
                                    sealed -> palette.muted
                                    on -> accent
                                    else -> palette.text
                                },
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
                                .background(if (on) accent.copy(alpha = 0.14f) else palette.cardSolid)
                                .clickable { onSetFilter(f.key, v.v) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                v.n,
                                style = stageText(11.5f, 600),
                                color = if (on) accent else palette.text,
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
                    onOpenDetail = { site, vod ->
                        // A home-row movie may be borrowed from another site (donorSiteKey);
                        // rows from the current site's own list belong to that site.
                        val owner = site.ifBlank { state.currentSite.key }
                        onOpenDetail(owner, vod, categoryContext.first, categoryContext.second)
                    },
                    onLoadMore = onLoadMore,
                    modifiers = state.movies.associate { it.id to it.remark },
                )
            }
        }
    }

    // plan §5.5.7: long-press menu → rename / hide / move / lock (StageMenuItem), the same
    // customization verbs the Library rail uses, applied through the shared Core store.
    lockMenuFor?.let { cat ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageSidePadding, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.cardSolid)
                .padding(vertical = 6.dp),
        ) {
            Text(
                cat.displayName,
                style = stageText(13f, 700),
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StageMenuItem(
                label = stringResource(R.string.settings_customize_rename),
                icon = MobileIcons.Edit,
                onClick = {
                    renamingFor = cat
                    lockMenuFor = null
                },
            )
            StageMenuItem(
                label = stringResource(R.string.settings_customize_hide),
                icon = MobileIcons.VisibilityOff,
                onClick = {
                    onHideCategory(cat.key)
                    lockMenuFor = null
                },
            )
            StageMenuItem(
                label = stringResource(R.string.settings_row_menu_move_up),
                icon = MobileIcons.KeyboardArrowUp,
                onClick = {
                    onMoveCategory(cat, MoveKind.UP)
                    lockMenuFor = null
                },
            )
            StageMenuItem(
                label = stringResource(R.string.settings_row_menu_move_down),
                icon = MobileIcons.KeyboardArrowDown,
                onClick = {
                    onMoveCategory(cat, MoveKind.DOWN)
                    lockMenuFor = null
                },
            )
            StageMenuItem(
                label = stringResource(if (cat.locked) R.string.tvbox_lock_action_unlock else R.string.tvbox_lock_action_lock),
                icon = MobileIcons.Check,
                onClick = {
                    onToggleLock(cat)
                    lockMenuFor = null
                },
            )
            StageMenuItem(
                label = stringResource(R.string.common_cancel),
                icon = MobileIcons.Close,
                onClick = { lockMenuFor = null },
            )
        }
    }

    // Rename dialog: blank restores the site's original name (the Core store treats null as reset).
    renamingFor?.let { cat ->
        TextPromptDialog(
            title = stringResource(R.string.settings_customize_rename),
            initial = cat.displayName,
            onDismiss = { renamingFor = null },
            onConfirm = { entered ->
                onRenameCategory(cat.key, entered.takeIf { it.isNotBlank() })
                renamingFor = null
            },
        )
    }

    // Locked-category PIN sheet (list-layer gate, plan §5.5.6).
    pinSheetFor?.let { cat ->
        var waitSeconds by remember { mutableStateOf(0) }
        var wrong by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PinEntrySheet(
            title = pinEntryTitle(wrong = wrong, waitSeconds = waitSeconds),
            description = stringResource(R.string.tvbox_pin_unlock_desc),
            onSubmit = { entered ->
                scope.launch {
                    when (onUnlockCategory(cat, entered)) {
                        PinAttempt.Accepted -> pinSheetFor = null
                        PinAttempt.Wrong -> wrong = true
                        is PinAttempt.Wait -> {
                            wrong = false
                            waitSeconds = 30
                        }
                    }
                }
            },
            onDismiss = { pinSheetFor = null },
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
        // vod_id 在同一分类里唯一；拼上 typeName 让跨分类合并也不撞 key（index 兜底会让
        // 翻页时整格重建，丢失状态）。
        items(count = movies.size, key = { i -> movies[i].id + movies[i].typeName.orEmpty() }) { i ->
            val movie = movies[i]
            StagePoster(
                imageUrl = movie.pic,
                title = movie.name,
                subtitle = modifiers[movie.id],
                // A home-row movie may have been borrowed from another site (donorSiteKey);
                // rows from the current site's own list belong to that site.
                onClick = { onOpenDetail(movie.donorSiteKey ?: "", movie.id) },
            )
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
            // 解析层的可执行引导（换线/换源/服务未就绪）原样透传 —— 它比固定文案有用得多。
            state.resolveError != null -> EmptyHint(state.resolveError)
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
                        pluralStringResource(R.plurals.tvbox_episodes, episodes.size, episodes.size),
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
