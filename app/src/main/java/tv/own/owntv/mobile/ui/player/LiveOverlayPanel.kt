package tv.own.owntv.mobile.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.ChannelLines
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.screens.ContentActions
import tv.own.owntv.mobile.ui.screens.home.formatCount
import tv.own.owntv.mobile.ui.screens.home.formatYear
import tv.own.owntv.mobile.ui.screens.home.timeLeftText
import tv.own.owntv.mobile.ui.screens.live.ChannelMenu
import tv.own.owntv.mobile.ui.screens.live.LiveCategoryRow
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.live.LiveViewModel
import tv.own.owntv.mobile.ui.screens.live.icon
import tv.own.owntv.mobile.ui.screens.live.label
import tv.own.owntv.mobile.ui.stage.LocalScrollLongTitles
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StagePalette
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The player's left overlay: what is on, what else there is, and what is coming — three columns over
 * the picture, the picture still visible to the right of them.
 *
 * It is the Live TV tab's own material, re-cut for the player: categories with their counts, that
 * category's channels with the playing one marked and the user's favourites starred, and the
 * channel's guide. Nothing here queries on its own account — the categories and their channels come
 * from [LiveTuner], the same object that is playing, so the panel and the picture cannot disagree
 * about which channel is on or what is in a folder.
 *
 * **Widths (G7):** the channel column is a third of the screen and the guide column another third;
 * the categories take the smaller share beside them. That is narrow — a phone's third is 130 dp — so
 * a name that does not fit scrolls rather than ending in an ellipsis, and the channel rows here are
 * the narrow ones ([OverlayChannelRow]) rather than the 66 dp row with a logo the sheets draw.
 *
 * The third column is absent when no guide feed exists at all (B4), and says "no programme data"
 * when one does but has nothing for this channel (B5).
 */
@Composable
internal fun LiveOverlayPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The archive picker's "Go back to…": the player owns that sheet, because its offsets are the
     * player's (how far back the saved copy or the provider's archive reaches). Handed the channel,
     * so it can wait for the switch before it asks.
     */
    onJumpBackTo: (ChannelEntity) -> Unit = {},
    tuner: LiveTuner = koinInject(),
    actions: ContentActions = koinInject(),
    /** The channel menu's own actions (favourite, rename, move, record) live on the tab's view model. */
    liveVm: LiveViewModel = koinViewModel(),
) {
    val settingRepo: SettingsRepository = koinInject()
    // The same switch the Live TV list reads: off hides every channel number in the app, this one
    // included — the row then starts at its logo.
    val showNumbers by settingRepo.directTune.collectAsStateWithLifecycle(true)
    val playing by tuner.channel.collectAsStateWithLifecycle()
    val nowNext by tuner.nowNext.collectAsStateWithLifecycle()
    val favorites by remember { actions.favoriteIds(MediaType.LIVE) }.collectAsStateWithLifecycle(emptySet())

    var rows by remember { mutableStateOf<List<LiveCategoryRow>?>(null) }
    var epgSupported by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LiveKey?>(null) }
    var channels by remember { mutableStateOf<List<ChannelLines.Group>>(emptyList()) }
    // The channel a long press was made on, and its archive while that sheet is up.
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var catchupFor by remember { mutableStateOf<ChannelEntity?>(null) }
    // The column is a snapshot; a rename or a hide in the menu has to be read again to show.
    var reload by remember { mutableStateOf(0) }

    // The first column, read on the way in and again whenever the channel menu has had a chance to
    // change it — a category created from that menu is a row that has to appear here at once.
    LaunchedEffect(reload) {
        val listed = tuner.overlayCategories()
        rows = listed
        epgSupported = tuner.epgSupported()
        when {
            // A reload (a rename, a folder made or hidden) must not move the column being looked at.
            selected != null && listed.any { it.category.key == selected } -> Unit
            // It opens on the category the playing channel lives in — the same "where am I" the
            // tab's page shows — and falls back to the first row when that folder is not listed.
            selected == null -> {
                val folder = playing?.categoryId?.let { LiveKey.Folder(it) }
                selected = listed.firstOrNull { it.category.key == folder }?.category?.key ?: listed.firstOrNull()?.category?.key
            }
            // The open category went away: the column has nothing to show, so it opens again.
            else -> selected = listed.firstOrNull()?.category?.key
        }
    }
    LaunchedEffect(selected, reload) {
        val key = selected ?: return@LaunchedEffect
        channels = tuner.channelsInCategoryForPicker(key)
    }

    BackHandler(onBack = onDismiss)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The panel sits at the start edge, so in right-to-left the wash comes from the right.
    val wash = if (rtl) {
        arrayOf(0.3f to Color.Transparent, 1f to Color(0xBF020506))
    } else {
        arrayOf(0f to Color(0xBF020506), 0.7f to Color.Transparent)
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(*wash))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        // Measured from the box itself, not from the window: a phone turned upright and back kept
        // the portrait width in `LocalWindowInfo`, and the columns came back a third of their size.
        // The constraints follow the layout, so they are right after every rotation.
        val landscape = maxWidth > maxHeight
        val screenWidth = maxWidth
        // Held upright the category column is 90 dp: an icon would take a quarter of it and leave
        // the name two characters, so upright rows are text and count only.
        val decorations = landscape
        // The picture stays visible on the right: the panel is a strip, not a screen. Over the
        // film there is no page and no theme to borrow — the player's own material, white on black.
        CompositionLocalProvider(
            LocalStagePalette provides StagePalette.Dark,
            LocalScrollLongTitles provides true,
        ) {
            Row(
                Modifier
                    // The three columns and the picture beside them (G7): what is left over on the
                    // right is the picture, never covered.
                    .fillMaxHeight()
                    .padding(if (landscape) 14.dp else 8.dp)
                    // The panel's own taps stay in it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1 — the categories, each with the channels it holds (T7).
                Column(
                    Modifier
                        .width(screenWidth * categoryShare(landscape))
                        .fillMaxHeight()
                        .playerPane(RoundedCornerShape(20.dp))
                        .padding(vertical = 8.dp),
                ) {
                    StageGroupLabel(stringResource(R.string.content_category_browser_title))
                    LazyColumn(Modifier.fillMaxHeight()) {
                        items(rows.orEmpty(), key = { it.category.key.toString() }) { row ->
                            StageMenuItem(
                                label = row.category.label(),
                                onClick = { selected = row.category.key },
                                icon = if (decorations) row.category.icon() else null,
                                count = row.count?.takeIf { it > 0 }?.let(::formatCount),
                                filled = row.category.key == selected,
                                height = 42.dp,
                                compact = true,
                            )
                        }
                    }
                }
                // 2 — the channels of the open category: playing, favourite, what is on now.
                Column(
                    Modifier
                        .width(screenWidth * channelShare(landscape))
                        .fillMaxHeight()
                        .playerPane(RoundedCornerShape(20.dp))
                        .padding(vertical = 8.dp),
                ) {
                    StageGroupLabel(stringResource(R.string.content_channel_overlay_title))
                    val now = produceState(emptyMap<Long, XtEpgEntry>(), channels) {
                        value = tuner.nowProgrammes(channels.map { it.channel })
                    }
                    val nowMs by rememberClockTick()
                    LazyColumn(Modifier.fillMaxHeight()) {
                        items(channels, key = { it.channel.id }) { group ->
                            // One row per channel: the playlist's repeats are the lines under it,
                            // and the row is playing when any of its lines is (B7).
                            val channel = group.channel
                            OverlayChannelRow(
                                channel = channel,
                                showLogo = decorations,
                                showNumber = showNumbers,
                                now = now.value[channel.id]?.takeIf { it.stopMs > nowMs },
                                nowMs = nowMs,
                                isFavorite = group.lines.any { it.id in favorites },
                                current = group.lines.any { it.id == playing?.id },
                                onClick = { tuner.switchTo(channel) },
                                onLongClick = { menuFor = channel },
                            )
                        }
                    }
                }
                // 3 — the guide of the channel on screen. Absent only when there is neither an
                // EPG feed nor a guide for this channel: an Xtream playlist answers the guide from
                // its own API, with no XMLTV feed added anywhere (B4/B5).
                if (epgSupported || nowNext != null) {
                    Column(
                        Modifier
                            .width(screenWidth * guideShare(landscape))
                            .fillMaxHeight()
                            .playerPane(RoundedCornerShape(20.dp))
                            .padding(vertical = 8.dp),
                    ) {
                        StageGroupLabel(stringResource(R.string.content_epg_title))
                        GuideColumn(nowNext)
                    }
                }
            }
        }
    }

    // A long press on a channel: everything it can do to itself, in the sheet the Live tab used to
    // open — favourite, rename, hide, move to a category, record, its archive.
    menuFor?.let { channel ->
        // "Move to a category" needs a list the channel can be moved *out of*: All channels is a
        // computed view with no order of its own, so the channel's own folder stands in as the
        // origin — which is what the move means anyway ("out of that folder, into this one").
        val open = selected ?: LiveKey.All
        val origin = if (liveVm.contextKeyOf(open) != null) {
            open
        } else {
            channel.categoryId?.let { LiveKey.Folder(it) }?.takeIf { liveVm.contextKeyOf(it) != null } ?: open
        }
        val originName = rows?.firstOrNull { it.category.key == origin }?.category?.label().orEmpty()
        ChannelMenu(
            channel = channel,
            selected = origin,
            isFavorite = channel.id in favorites,
            originName = originName,
            providerName = null,
            vm = liveVm,
            onOpenCatchup = { catchupFor = channel },
            onDismiss = { menuFor = null; reload++ },
        )
    }
    catchupFor?.let { channel ->
        // The Live tab's own picker, for the channel the menu was opened on rather than the one
        // playing: "Go back to…" hands the channel to the player, whose offsets these are.
        tv.own.owntv.mobile.ui.screens.live.CatchupSheet(
            channelName = channel.name,
            load = { tuner.catchupProgrammesOf(channel) },
            onPick = { programme -> tuner.playCatchup(programme, channel) },
            onJumpBack = { catchupFor = null; onJumpBackTo(channel) },
            onDismiss = { catchupFor = null },
        )
    }
}

/**
 * Column widths as shares of the screen, by orientation.
 *
 * Held sideways there is room to keep a third of the picture: the reference picture's proportions,
 * where the category column is narrow, the channel column holds a name and its programme, and the
 * guide sits beside it at the same width.
 *
 * Held upright there is no room beside the panel for anything — a third of 360 dp is 120 dp — so
 * the panel takes the width it needs and the picture waits behind it. The three shares still add up
 * to less than the screen, which leaves the sliver on the right that closes the panel.
 */
private fun categoryShare(landscape: Boolean) = if (landscape) 0.16f else 0.25f
private fun channelShare(landscape: Boolean) = if (landscape) 0.26f else 0.34f
private fun guideShare(landscape: Boolean) = if (landscape) 0.26f else 0.32f

/**
 * A channel row, laid out as the reference picture lays it out: the provider's number in a hairline
 * box, its logo, the name over what is on now, and a heart at the end when it is a favourite.
 *
 * The number box is where "playing" is said instead: the channel on screen trades its number for a
 * few bars that move, and takes its name in the accent — the number is how you find a channel, the
 * bars are how you know you are already on it.
 *
 * The name scrolls when it is longer than the column: a third of a screen cannot hold
 * "CCTV-1 综合" and the list is meant to be read at a glance, not guessed at from four characters.
 */
@Composable
private fun OverlayChannelRow(
    channel: ChannelEntity,
    /** The logo plate: worth its 38 dp held sideways, not worth it held upright. */
    showLogo: Boolean,
    showNumber: Boolean,
    now: XtEpgEntry?,
    nowMs: Long,
    isFavorite: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val name = remember(channel.name) { ProviderTags.parse(channel.name) }
    StageRow(
        onClick = onClick,
        onLongClick = onLongClick,
        current = current,
        selected = current,
        minHeight = 56.dp,
        gap = 8.dp,
    ) {
        // 1 — the number, or the moving bars for the channel on screen. Both keep the same width so
        // the logos and the names below them stay in one column.
        when {
            current -> PlayingBars(accent, Modifier.width(NUMBER_PLATE_WIDTH).height(14.dp))
            showNumber && (channel.number ?: 0) > 0 -> NumberPlate(channel.number!!, palette.dim)
            else -> Unit
        }
        // 2 — the channel's logo, on the page's own plate so a transparent PNG still reads.
        if (showLogo) {
            Box(
                Modifier.size(30.dp, 21.dp).clip(RoundedCornerShape(StageRadii.Plate)),
                contentAlignment = Alignment.Center,
            ) {
                ChannelLogoImage(
                    url = channel.displayLogoUrl,
                    modifier = Modifier.fillMaxSize(),
                    plateOn = palette.page,
                    fallback = { StageIcon(MobileIcons.LiveTv, 13.dp, palette.dim) },
                )
            }
        }
        // 3 — the name over what is on now.
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                name.name,
                style = stageText(13.5f, 700),
                color = if (current) accent else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().basicMarquee(),
            )
            if (now != null) {
                val left = timeLeftText(((now.stopMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000)
                Text(
                    text = now.title + stringResource(R.string.content_epg_bits_separator) + left,
                    style = stageText(11f, 400),
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp).basicMarquee(),
                )
            }
        }
        // 4 — the favourite mark, at the end where a thumb is not covering the name. The lines a
        // channel is offered on are folded into this one row; how many there were is the Sources
        // sheet's business, not a number the row needs to carry.
        if (isFavorite) StageIcon(MobileIcons.Favorite, 14.dp, accent)
    }
}

/** The width the number box and the playing bars share, so a row keeps its indent either way. */
private val NUMBER_PLATE_WIDTH = 34.dp

/** The provider's own channel number in a hairline box — the reference picture's `.no`. */
@Composable
private fun NumberPlate(number: Int, color: Color) {
    Box(
        Modifier
            .width(NUMBER_PLATE_WIDTH)
            .height(20.dp)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatYear(number),
            style = stageText(11.5f, 600),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Three bars breathing out of step: the television's "this is the one playing". Only ever one row
 * draws it, so the endless animation costs a single row's recomposition.
 */
@Composable
private fun PlayingBars(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "now-playing")
    val first by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar1",
    )
    val second by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar2",
    )
    val third by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar3",
    )
    val heights = listOf(first, second, third)
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { fraction ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .background(color, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/**
 * Now, then next, then the rest of the window. The first row carries its progress, because the
 * question this column answers is "how much of it is left".
 */
@Composable
private fun GuideColumn(nowNext: EpgNowNext?) {
    val palette = LocalStagePalette.current
    val nowMs by rememberClockTick()
    val entries = remember(nowNext) {
        listOfNotNull(nowNext?.now) + listOfNotNull(nowNext?.next) + nowNext?.upcoming.orEmpty()
    }
    if (entries.isEmpty()) {
        // A guide feed exists, so this channel simply has nothing in it (B5) — saying so beats an
        // empty column that looks like a failure to load.
        Text(
            text = stringResource(R.string.content_epg_no_programme),
            style = stageText(13f, 500),
            color = palette.muted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxHeight()) {
        // No keys: at most six rows, and a guide window is replaced wholesale rather than edited —
        // a key would only be something else that could collide.
        items(entries) { entry ->
            GuideEntry(entry, onAir = entry.startMs <= nowMs && nowMs < entry.stopMs, nowMs = nowMs)
        }
    }
}

@Composable
private fun GuideEntry(entry: XtEpgEntry, onAir: Boolean, nowMs: Long) {
    val palette = LocalStagePalette.current
    val times = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.content_live_time_range_plain,
                    times.format(java.util.Date(entry.startMs)),
                    times.format(java.util.Date(entry.stopMs)),
                ),
                style = stageText(10.5f, 600),
                color = if (onAir) stageAccent.accent else palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onAir) {
                Text(
                    text = timeLeftText(((entry.stopMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000),
                    style = stageText(10.5f, 500),
                    color = palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = entry.title,
            style = stageText(13f, if (onAir) 700 else 600),
            color = if (onAir) palette.text else palette.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).basicMarquee(),
        )
        if (onAir) {
            val span = (entry.stopMs - entry.startMs).coerceAtLeast(1)
            StageProgress((nowMs - entry.startMs).toFloat() / span, Modifier.padding(top = 6.dp), thin = true)
        }
    }
}
