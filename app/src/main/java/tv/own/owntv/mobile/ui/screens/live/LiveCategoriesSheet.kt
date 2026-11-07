package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.customize.CategoryRestorePlan
import tv.own.owntv.core.customize.categoryRestoreSummary
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.home.formatCount
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The categories sheet: "Categories · Live TV", search, the fixed entries with
 * their icons — Favourites, Recently watched, Catch-up, All channels — then "GROUPS · DE" and the
 * provider groups by their parsed names, with tags, counts (Show counts) and playlist marks. The open
 * category is filled. A hold on a group opens its menu ([onHold]). A locked group carries the lock, and
 * one the PIN has not opened shows no count: how much it holds is the lock's business.
 */
@Composable
internal fun LiveCategoriesSheet(
    categories: List<LiveCategory>,
    selected: LiveKey,
    counts: Map<LiveKey, Int>,
    showCounts: Boolean,
    marks: Map<Long, PlaylistMark>,
    onSelect: (LiveKey) -> Unit,
    onHold: (LiveCategory) -> Unit,
    onDismiss: () -> Unit,
    /** The TV Guide's "Guide category · Same list as Live TV"; Live TV's own by default. */
    title: String = stringResource(R.string.content_category_browser_title),
    extra: String = stringResource(R.string.common_nav_live_tv),
) {
    var query by remember { mutableStateOf("") }
    val fixed = listOf(LiveKey.Favorites, LiveKey.History, LiveKey.Catchup, LiveKey.All)
        .mapNotNull { key -> categories.firstOrNull { it.key == key } }
    val groups = categories.filter { it.builtIn == null }
    val parsed = remember(groups) { groups.associateWith { ProviderTags.parse(it.title.orEmpty()) } }
    val country = remember(parsed) { ProviderTags.sharedCountry(parsed.values.toList()) }
    val heading = if (country != null) stringResource(R.string.content_live_groups_country, country) else stringResource(R.string.content_live_groups)
    val labels = fixed.associateWith { it.label() }
    val q = query.trim()
    val shownFixed = fixed.filter { q.isEmpty() || labels.getValue(it).contains(q, ignoreCase = true) }
    val shownGroups = groups.filter { q.isEmpty() || ProviderTags.groupLabel(parsed.getValue(it), country).contains(q, ignoreCase = true) }
    fun pick(key: LiveKey) {
        onSelect(key)
        onDismiss()
    }
    fun countOf(key: LiveKey) = counts[key]?.takeIf { showCounts }?.let(::formatCount)

    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title, extra = extra)
        StageSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.content_search_categories),
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
        )
        // `max-height: 700px` of an 887 dp frame, less the header and the search above.
        val listMax = (LocalConfiguration.current.screenHeightDp * 0.79f).dp - 141.dp
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMax)) {
            items(shownFixed, key = { it.key.toString() }) { c ->
                StageMenuItem(
                    label = labels.getValue(c),
                    onClick = { pick(c.key) },
                    icon = c.icon(),
                    filled = c.key == selected,
                    count = countOf(c.key),
                )
            }
            if (shownGroups.isNotEmpty()) item(key = "heading") { StageGroupLabel(heading) }
            items(shownGroups, key = { it.key.toString() }) { c ->
                val name = parsed.getValue(c)
                StageMenuItem(
                    label = ProviderTags.groupLabel(name, country),
                    onClick = { pick(c.key) },
                    onLongClick = { onHold(c) },
                    icon = if (c.locked) MobileIcons.Lock else null,
                    filled = c.key == selected,
                    count = if (c.sealed) null else countOf(c.key),
                    tags = if (name.tags.isEmpty()) null else { { name.tags.forEach { StageTag(it) } } },
                    mark = c.sourceId?.let(marks::get),
                )
            }
        }
    }
}

private fun LiveCategory.icon() = when (builtIn) {
    LiveCategory.BuiltIn.FAVORITES -> MobileIcons.FavoriteBorder
    LiveCategory.BuiltIn.HISTORY -> MobileIcons.History
    LiveCategory.BuiltIn.CATCHUP -> MobileIcons.Catchup
    else -> MobileIcons.LiveTv
}

/**
 * Hold a category: rename it, or hide it. Both go through the same store as Settings → Customize,
 * so either can be undone there.
 */
@Composable
internal fun LiveCategoryMenu(
    title: String,
    onRename: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title)
        StageMenuItem(stringResource(R.string.settings_customize_rename), onClick = { onDismiss(); onRename() }, icon = MobileIcons.Edit)
        StageMenuItem(stringResource(R.string.settings_customize_hide), onClick = { onDismiss(); onHide() }, icon = MobileIcons.VisibilityOff)
    }
}

/**
 * The restore's confirmation: the category, "This will:" and one line per change, then Restore
 * (filled) and Cancel — or, with nothing to undo, the sentence saying it is already at the default.
 * [plan] is null while it is still being read.
 */
@Composable
internal fun CategoryRestoreConfirm(
    title: String,
    custom: Boolean,
    plan: CategoryRestorePlan?,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStagePalette.current
    val lines = plan?.let { categoryRestoreSummary(it, LocalContext.current.resources) }
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title)
        if (lines != null && lines.isEmpty()) {
            Text(
                stringResource(R.string.content_category_restore_nothing),
                style = stageText(13f, 600), color = palette.body,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 18.dp),
            )
            StageMenuItem(stringResource(R.string.common_ok), onClick = onDismiss, icon = MobileIcons.Check)
            return@MobileBottomSheet
        }
        Text(
            stringResource(R.string.content_category_restore_question),
            style = stageText(13f, 600), color = palette.body,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        )
        lines.orEmpty().forEach { line ->
            Row(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).background(palette.muted, CircleShape))
                Text(line, style = stageText(12.5f, 500), color = palette.muted)
            }
        }
        Box(Modifier.size(1.dp, 10.dp))
        StageMenuItem(
            stringResource(if (custom) R.string.content_category_restore_custom else R.string.content_category_restore_playlist),
            onClick = { onDismiss(); onRestore() },
            icon = MobileIcons.Refresh,
            filled = true,
            enabled = plan != null,
        )
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}
