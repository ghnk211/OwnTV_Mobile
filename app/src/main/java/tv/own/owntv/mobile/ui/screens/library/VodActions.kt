package tv.own.owntv.mobile.ui.screens.library

import tv.own.owntv.mobile.ui.components.MobileIcons
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.own.owntv.mobile.ui.screens.home.formatCount
import tv.own.owntv.mobile.ui.screens.home.formatYear
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.ContentMenu
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.StageConfirmItems
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.input.ImeAction
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.components.ContentMenuSheet
import tv.own.owntv.mobile.ui.components.downloadActionFor
import tv.own.owntv.mobile.ui.components.icon
import tv.own.owntv.mobile.ui.components.label
import tv.own.owntv.mobile.ui.components.onTap
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MoveToCategorySheet
import tv.own.owntv.mobile.ui.components.NewCategoryDialog
import tv.own.owntv.mobile.ui.components.ReorderSheet
import tv.own.owntv.mobile.ui.components.SheetAction
import tv.own.owntv.mobile.ui.components.TmdbDetailsSheet
import tv.own.owntv.mobile.ui.components.movieDetails
import tv.own.owntv.mobile.ui.components.seriesDetails
import tv.own.owntv.mobile.ui.components.sheetListHeight

/** Which follow-up the sheet handed off to. Only ever one at a time. */
private enum class VodDialog { MOVE, MOVE_TO_CATEGORY, NEW_CATEGORY, DETAILS, TMDB_NAME, SUBTITLES, DOWNLOAD_ALL }

/** The menu's groups, in the TV's order (labels in [VodMenu]). */
private const val GROUP_WATCH = 0
private const val GROUP_LIBRARY = 1
private const val GROUP_ORGANISE = 2
private const val GROUP_DETAILS = 3

/**
 * The long-press menu for a film or a show, and everything it opens.
 *
 * The keys are the TV app's, because the order the user arranged in Settings is saved against exactly
 * those strings — a menu rearranged on the television comes out rearranged here.
 *
 * The sheet closes the moment an action is tapped, so [onDismiss] is deliberately *not* called from
 * it: the caller is released only once the sheet is gone **and** nothing it opened is still up.
 */
@Composable
fun VodMenu(
    item: VodItem,
    tab: LibraryTab,
    selected: LiveKey,
    isFavorite: Boolean,
    originName: String,
    /** The section's categories, for the header's "2026 · Disney+ Kids Movies". */
    categories: List<VodCategory>,
    vm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val movie = tab == LibraryTab.MOVIES
    // Collected here, not read as `.value`: from the movie page nothing else is watching it.
    val watchedIds by vm.watchedIds.collectAsStateWithLifecycle()
    val watched = movie && item.id in watchedIds
    val mode by vm.metadataMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sheetOpen by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<VodDialog?>(null) }
    LaunchedEffect(sheetOpen, dialog) { if (!sheetOpen && dialog == null) onDismiss() }

    // Resolved once the menu opens, and again after a refetch. Null while it is still being looked up,
    // which is why the TMDB rows appear a moment after the rest — they are the only ones that need it.
    var meta by remember { mutableStateOf<MetadataCacheEntity?>(null) }
    var metaTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(metaTick) { if (mode.enrich) meta = vm.metaFor(tab, item.id) }

    // Films only, and only when there is something to delete — the TV app's gating.
    var subtitles by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(item.id) {
        subtitles = if (movie) vm.downloadedSubtitles(item.id).map { it.cacheId } else emptyList()
    }

    // What this item's Download action should now say and do — the row watches its own transfer.
    val downloadRows by vm.downloadsOf(item.id).collectAsStateWithLifecycle(emptyList())
    // A TVBOX film has no downloadable address; its line/episode choice is on the VOD detail page.
    val tvboxItem by produceState(false, item.id, movie) {
        value = movie && vm.isTvboxItem(item.id)
    }
    val downloadAction = downloadActionFor(downloadRows)
    val inHistory by produceState(selected == LiveKey.History, item.id) { value = vm.isInHistory(item.id) }
    // "Download all episodes 12" — the episodes stored for the show; nothing for one never opened.
    val episodes by produceState(0, item.id) { if (!movie) value = vm.episodeCount(item.id) }

    if (sheetOpen) {
        val canMove = vm.contextKeyOf(selected) != null
        val trailer = tv.own.owntv.core.metadata.TrailerKeys.split(meta?.trailerKey).firstOrNull()
        // WATCH, LIBRARY, ORGANISE, DETAILS. What is not available for this
        // title stays in its place, dimmed — Play trailer without a trailer, Remove from history when it is
        // not in History, details without a TMDB match, Delete subtitles with none downloaded.
        val actions = buildList {
            add(
                SheetAction(
                    key = "play_trailer",
                    label = stringResource(R.string.content_play_trailer),
                    icon = MobileIcons.PlayCircle,
                    group = GROUP_WATCH,
                    enabled = trailer != null,
                    onClick = { trailer?.let { context.playTrailer(it) } },
                ),
            )
            if (movie) {
                add(
                    SheetAction(
                        key = "play_external",
                        label = stringResource(R.string.content_play_external_short),
                        icon = MobileIcons.OpenInNew,
                        group = GROUP_WATCH,
                        onClick = { vm.playExternal(item.id) },
                    ),
                )
            }
            add(
                SheetAction(
                    key = "favourite",
                    label = stringResource(
                        if (isFavorite) R.string.content_remove_favourite else R.string.content_add_favourite,
                    ),
                    icon = if (isFavorite) MobileIcons.Favorite else MobileIcons.FavoriteBorder,
                    group = GROUP_LIBRARY,
                    onClick = { vm.setFavorite(item.id, !isFavorite) },
                ),
            )
            if (movie) {
                add(
                    SheetAction(
                        key = "mark_watched",
                        label = stringResource(
                            if (watched) R.string.content_mark_unwatched else R.string.content_mark_watched,
                        ),
                        icon = MobileIcons.Check,
                        group = GROUP_LIBRARY,
                        onClick = { vm.setWatched(item.id, !watched) },
                    ),
                )
            }
            if (!tvboxItem) {
                add(
                    SheetAction(
                        key = "download",
                        label = downloadAction.label(
                            stringResource(
                                if (movie) R.string.content_download else R.string.content_download_all_episodes,
                            ),
                        ),
                        icon = downloadAction.icon(),
                        destructive = downloadAction.deletes,
                        group = GROUP_LIBRARY,
                        value = episodes.takeIf { it > 0 && downloadRows.isEmpty() }?.let(::formatCount),
                        onClick = downloadAction.onTap(
                            // A show queues every episode, so it asks first with the count.
                            onDownload = { if (movie || episodes == 0) vm.download(item.id) else dialog = VodDialog.DOWNLOAD_ALL },
                            onRetry = { vm.retryDownloads(downloadRows) },
                            onDelete = { vm.deleteDownloads(downloadRows) },
                        ),
                    ),
                )
            }
            add(
                SheetAction(
                    key = "remove_history",
                    label = stringResource(R.string.content_remove_history),
                    icon = MobileIcons.History,
                    group = GROUP_LIBRARY,
                    enabled = inHistory,
                    onClick = { vm.removeFromHistory(item.id) },
                ),
            )
            if (canMove) {
                add(
                    SheetAction(
                        key = "move",
                        label = stringResource(R.string.content_move),
                        icon = MobileIcons.Move,
                        group = GROUP_ORGANISE,
                        onClick = { dialog = VodDialog.MOVE },
                    ),
                )
                add(
                    SheetAction(
                        key = "move_to_category",
                        label = stringResource(R.string.content_move_to_category),
                        icon = MobileIcons.Folder,
                        group = GROUP_ORGANISE,
                        onClick = { dialog = VodDialog.MOVE_TO_CATEGORY },
                    ),
                )
            }
            add(
                SheetAction(
                    key = "hide",
                    label = stringResource(R.string.common_hide),
                    icon = MobileIcons.VisibilityOff,
                    group = GROUP_ORGANISE,
                    onClick = { vm.hide(item.id) },
                ),
            )
            add(
                SheetAction(
                    key = "tmdb_details",
                    label = stringResource(if (movie) R.string.content_movie_details else R.string.content_series_details),
                    icon = MobileIcons.Info,
                    group = GROUP_DETAILS,
                    // Only once a confident match has actually resolved: there is nothing to show otherwise.
                    enabled = mode.enrich && meta != null,
                    onClick = { dialog = VodDialog.DETAILS },
                ),
            )
            if (mode.enrich) {
                // Always offered while enrichment is on, so a wrong match — or a "no match" the app
                // remembers for a week — can be cleared and searched again on the spot.
                add(
                    SheetAction(
                        key = "refetch_tmdb",
                        label = stringResource(R.string.content_refetch_tmdb),
                        icon = MobileIcons.Refresh,
                        group = GROUP_DETAILS,
                        onClick = {
                            Toast.makeText(context, R.string.content_researching_tmdb, Toast.LENGTH_SHORT).show()
                            scope.launch {
                                vm.clearMeta(tab, item.id)
                                meta = vm.metaFor(tab, item.id)
                            }
                        },
                    ),
                )
                add(
                    SheetAction(
                        key = "set_tmdb_name",
                        label = stringResource(R.string.content_set_tmdb_name),
                        icon = MobileIcons.Edit,
                        group = GROUP_DETAILS,
                        onClick = { dialog = VodDialog.TMDB_NAME },
                    ),
                )
            }
            // Films only, as on the TV.
            if (movie) {
                add(
                    SheetAction(
                        key = "delete_subtitles",
                        label = stringResource(R.string.content_delete_subtitles),
                        icon = MobileIcons.Subtitles,
                        group = GROUP_DETAILS,
                        enabled = subtitles.isNotEmpty(),
                        onClick = { dialog = VodDialog.SUBTITLES },
                    ),
                )
            }
        }

        val categoryName = item.categoryId?.let { id ->
            categories.firstOrNull { it.key == LiveKey.Folder(id) }?.label(tab)
        }
        ContentMenuSheet(
            menu = if (movie) ContentMenu.MOVIE else ContentMenu.SERIES,
            title = item.name,
            actions = actions,
            onDismiss = { sheetOpen = false },
            groupLabels = mapOf(
                GROUP_WATCH to stringResource(R.string.content_menu_group_watch),
                GROUP_LIBRARY to stringResource(R.string.content_menu_group_library),
                GROUP_ORGANISE to stringResource(R.string.content_menu_group_organise),
                GROUP_DETAILS to stringResource(R.string.content_menu_group_details),
            ),
            subtitle = listOfNotNull(item.year?.takeIf { it > 0 }?.let(::formatYear), categoryName)
                .joinToString(stringResource(R.string.content_epg_bits_separator)).ifBlank { null },
            leading = {
                AsyncImage(
                    item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(7.dp)),
                )
            },
        )
    }

    when (dialog) {
        VodDialog.MOVE -> ReorderSheet(
            title = item.name,
            openAt = item.id,
            load = { vm.moveList(selected) },
            onSave = { ids, moved -> vm.contextKeyOf(selected)?.let { vm.commitMove(it, ids, moved) } },
            onDismiss = { dialog = null },
        )
        VodDialog.MOVE_TO_CATEGORY -> MoveToCategorySheet(
            originName = originName,
            targets = vm.customCategories.collectAsStateWithLifecycle().value,
            onNewCategory = { dialog = VodDialog.NEW_CATEGORY },
            onMove = { targetId, keep ->
                vm.contextKeyOf(selected)?.let { vm.moveToCategory(item.id, it, targetId, keep) }
            },
            onDismiss = { dialog = null },
        )
        VodDialog.NEW_CATEGORY -> NewCategoryDialog(
            onCreate = { vm.createCustomCategory(it) },
            // Back to the picker, where the category just created is waiting.
            onDismiss = { dialog = VodDialog.MOVE_TO_CATEGORY },
        )
        VodDialog.DETAILS -> VodDetails(tab, item.id, meta, mode.tmdbWins, vm) { dialog = null }
        VodDialog.TMDB_NAME -> SetTmdbNameDialog(
            load = { vm.tmdbNamePrefill(tab, item.id) },
            onSave = { title, year ->
                scope.launch {
                    vm.setTmdbName(tab, item.id, title, year)
                    metaTick++
                }
            },
            onDismiss = { dialog = null },
        )
        VodDialog.SUBTITLES -> DeleteSubtitlesSheet(
            load = { vm.downloadedSubtitles(item.id).map { it.cacheId to (it.languageName ?: it.fileName) } },
            onDelete = { cacheId -> vm.deleteSubtitle(cacheId) },
            onDismiss = { dialog = null },
        )
        VodDialog.DOWNLOAD_ALL -> tv.own.owntv.mobile.ui.components.BulkDownloadConfirm(
            tv.own.owntv.mobile.ui.components.BulkDownloadAsk(episodes) { vm.download(item.id) },
        ) { dialog = null }
        null -> Unit
    }
}

/** The details sheet needs the row itself, which the grid does not carry — so it is fetched here. */
@Composable
private fun VodDetails(
    tab: LibraryTab,
    itemId: Long,
    meta: MetadataCacheEntity?,
    tmdbWins: Boolean,
    vm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val movie = tab == LibraryTab.MOVIES
    val entity by produceState<Any?>(null, itemId) {
        value = if (movie) vm.movieById(itemId) else vm.seriesById(itemId)
    }
    val details = when (val e = entity) {
        is MovieEntity -> movieDetails(e, meta, tmdbWins)
        is SeriesEntity -> seriesDetails(e, meta, tmdbWins)
        else -> null
    }
    details?.let { TmdbDetailsSheet(it, onDismiss) }
}

/**
 * Type the exact title TMDB should be searched under, when the automatic match got it wrong.
 *
 * Saving a blank title is how the override comes off again, which is why there is no separate Clear
 * button: the field starts filled, and emptying it says "use the provider's name".
 */
@Composable
private fun SetTmdbNameDialog(
    load: suspend () -> LibraryViewModel.TmdbNamePrefill?,
    onSave: (title: String, year: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        load()?.let {
            title = it.title
            year = it.year?.toString().orEmpty()
        }
    }
    val heading = stringResource(R.string.setup_tmdb_name)
    // Stage sheet: the explanation, the title and year fields, Save and Cancel.
    MobileBottomSheet(onDismissRequest = onDismiss, title = heading) {
        StageSheetHeader(title = heading, subtitle = stringResource(R.string.setup_tmdb_description))
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StageSearchField(
                value = title,
                onValueChange = { title = it },
                placeholder = stringResource(R.string.common_title),
                icon = null,
                imeAction = ImeAction.Next,
            )
            StageSearchField(
                value = year,
                onValueChange = { s -> year = s.filter { it.isDigit() }.take(4) },
                placeholder = stringResource(R.string.setup_year_optional),
                icon = null,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            )
        }
        StageConfirmItems(
            confirm = stringResource(R.string.common_save),
            confirmEnabled = true,
            onConfirm = { onSave(title.trim(), year.trim().toIntOrNull()) },
            onDismiss = onDismiss,
        )
    }
}

/** The subtitles downloaded for this film or episode. Tapping one deletes it; the row goes with it. */
@Composable
fun DeleteSubtitlesSheet(
    load: suspend () -> List<Pair<Long, String>>,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember { mutableStateListOf<Pair<Long, String>>() }
    LaunchedEffect(Unit) { rows.addAll(load()) }
    val title = stringResource(R.string.content_delete_subtitles)
    // Stage sheet: one danger row per subtitle file; a tap deletes it and the row goes.
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title)
        LazyColumn(Modifier.heightIn(max = sheetListHeight())) {
            items(rows, key = { it.first }) { (cacheId, label) ->
                StageMenuItem(
                    label = label,
                    onClick = {
                        onDelete(cacheId)
                        rows.removeAll { it.first == cacheId }
                    },
                    icon = MobileIcons.Delete,
                    danger = true,
                )
            }
        }
    }
}

/**
 * Trailers open in whatever plays YouTube on this phone — the app if it is installed, the browser if
 * it is not. The television has to embed a player because a TV often has neither; a phone always has
 * one, and it will be better than anything embedded here.
 */
internal fun Context.playTrailer(key: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
    runCatching { startActivity(intent) }
}
