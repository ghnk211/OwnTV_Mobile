package tv.own.owntv.mobile.ui.shell

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import tv.own.owntv.mobile.ui.stage.StageEyebrow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.profiles.ProfileAvatar
import tv.own.owntv.mobile.ui.profiles.ProfileInitial
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StagePill
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageShadow
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * How far the header and the bottom chrome have slid away. The header always rides the scroll (the
 * title lives on the page and leaves with it; back on the way up); the bottom chrome only when the user
 * has Hide while scrolling on. Read in the layout phase, so sliding never recomposes a screen.
 */
@Stable
class ShellChromeState {
    /** 0 = header fully shown … -[headerHeight] = gone. */
    var headerOffset by mutableFloatStateOf(0f)
    var headerHeight by mutableFloatStateOf(0f)

    /** 0 = bottom chrome fully shown … [bottomHeight] = gone. */
    var bottomOffset by mutableFloatStateOf(0f)
    var bottomHeight by mutableFloatStateOf(0f)
    var hideBottomOnScroll = true

    fun reset() {
        headerOffset = 0f
        bottomOffset = 0f
    }

    /**
     * The header's travel is consumed, because the content's top moves with it and would otherwise travel
     * twice; the bottom chrome's is not, since the content's bottom edge moving does not move the list.
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            val header = (headerOffset + dy).coerceIn(-headerHeight, 0f)
            val consumed = header - headerOffset
            headerOffset = header
            if (hideBottomOnScroll) bottomOffset = (bottomOffset - dy).coerceIn(0f, bottomHeight)
            return Offset(0f, consumed)
        }
    }
}

/**
 * What Home hands the shell. Its backdrop belongs to the page, not to the list, so it is drawn behind
 * the header and the status bar (`.bdrop`); once the hero has scrolled away the header carries its eyebrow
 * and title instead; and the Resume pill sits in the cluster while the hero is in view.
 */
@Stable
class HomeChrome {
    var backdropUrl by mutableStateOf<String?>(null)
    var backdropHeight by mutableStateOf(470.dp)

    /** Where the picture is cropped (`object-position`): Home 62% / 30%, a movie page 70% / 30%. */
    var backdropAlignment by mutableStateOf(BiasAlignment(0.24f, -0.4f))

    /** 1 = the full picture … 0 = gone; read in the draw phase only. */
    var backdropAlpha by mutableFloatStateOf(1f)
    var heading by mutableStateOf<HomeHeading?>(null)
    var resume by mutableStateOf<HomeResume?>(null)
}

data class HomeHeading(val eyebrow: String, val title: String)

data class HomeResume(val name: String, val onClick: () -> Unit)

val LocalHomeChrome = staticCompositionLocalOf<HomeChrome?> { null }

/**
 * A title page (a film, a show) lends the shell its backdrop the same way Home does. Its own
 * holder, not Home's: on the way back the two pages are briefly both alive, and one clearing the other's
 * picture would leave Home without its own.
 */
val LocalTitleChrome = staticCompositionLocalOf<HomeChrome?> { null }

/**
 * A place's own crumb (Live TV's "‹ **Sky Cinema** · 9 channels"): the [accent] part, the muted [rest] after a dot,
 * and what a tap opens (the categories). Set by the place while it is on screen.
 */
data class PageCrumb(
    val accent: String,
    val rest: String?,
    val chevron: Boolean = true,
    val owner: Any? = null,
    /** A line the page styles itself, drawn instead of accent · rest (Downloads: "USB drive · **57.6 GB free** of 57.7 GB"). */
    val styled: androidx.compose.ui.text.AnnotatedString? = null,
    /** The back stack entry of the page that set it ([ProvidePageCrumb] fills it in). */
    val entryId: String? = null,
    val onClick: () -> Unit,
)

/**
 * Lend the shell [crumb] while this place is on screen. Leaving clears it only if it is still this place's: during
 * a tab switch the next place sets its own crumb before this one is disposed, and clearing that left Live TV
 * without a crumb after Movies.
 */
@Composable
fun ProvidePageCrumb(crumb: (owner: Any) -> PageCrumb) {
    val holder = LocalPageCrumb.current
    val owner = remember { Any() }
    // A place being left keeps its crumb until it is disposed, after the next page is already on screen;
    // the entry is what lets the shell show it on its own page only (Home showed Live TV's, then jumped).
    val entryId = (androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current as? androidx.navigation.NavBackStackEntry)?.id
    androidx.compose.runtime.SideEffect { holder.value = crumb(owner).copy(entryId = entryId) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (holder.value?.owner === owner) holder.value = null } }
}

val LocalPageCrumb = staticCompositionLocalOf<MutableState<PageCrumb?>> { mutableStateOf(null) }

/** Where the shell hears that a page is drawn ([ReportShownPage]); its header follows that page. */
val LocalShownEntry = staticCompositionLocalOf<(androidx.navigation.NavBackStackEntry, Boolean) -> Unit> { { _, _ -> } }

/**
 * Tell the shell [entry]'s page is drawn — once, when it arrives, so a page leaving never claims the header back —
 * and that it is gone, when it leaves the screen.
 */
@Composable
fun ReportShownPage(entry: androidx.navigation.NavBackStackEntry) {
    val report = LocalShownEntry.current
    androidx.compose.runtime.DisposableEffect(entry.id) {
        report(entry, true)
        onDispose { report(entry, false) }
    }
}

/**
 * `.bdrop`: the picture across the top, cropped at 62% / 30%, dissolving into the page from 45% up (light
 * fades earlier: 0 → 28% at 38% → 100% at 78%), with the 55% → 0 page wash over its top 30%.
 */
@Composable
fun HomeBackdrop(chrome: HomeChrome) {
    val url = chrome.backdropUrl ?: return
    val palette = LocalStagePalette.current
    val mask = if (palette.isLight) {
        Brush.verticalGradient(0f to Color.Black, 0.22f to Color.Black, 0.62f to Color.Black.copy(alpha = 0.28f), 1f to Color.Transparent)
    } else {
        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
    }
    val wash = Brush.verticalGradient(0f to palette.wash.copy(alpha = 0.55f), 0.3f to palette.wash.copy(alpha = 0f))
    Box(
        Modifier
            .fillMaxWidth()
            .height(chrome.backdropHeight)
            .graphicsLayer {
                alpha = chrome.backdropAlpha
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(mask, blendMode = BlendMode.DstIn)
                drawRect(wash)
            },
    ) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, alignment = chrome.backdropAlignment, modifier = Modifier.fillMaxSize())
    }
}

/** Home's heading in the header: the eyebrow, then the title in 22 / 800. */
@Composable
private fun HomeHeadingText(heading: HomeHeading) {
    Column {
        StageEyebrow(heading.eyebrow)
        Text(
            heading.title,
            style = stageText(22f, 800, lineHeight = 1.2f),
            color = LocalStagePalette.current.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The bottom bar (`.ph .nav`): a floating glass capsule, 64 dp (56 icons only), its items 64 × 52 on an
 * 18 dp radius. The current place is the accent with a 14% tile and a glowing 5 dp dot under it. A tap on
 * the current place sends its list to the top, as does a long press on any place.
 */
@Composable
fun StageNavBar(
    destinations: List<MobileDestination>,
    current: MobileDestination?,
    labels: Boolean,
    onClick: (MobileDestination) -> Unit,
    onLongClick: (MobileDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val shape = RoundedCornerShape(StageRadii.BottomBar)
    Row(
        modifier
            .fillMaxWidth()
            .height(if (labels) 64.dp else 56.dp)
            .stageGlass(shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            val on = destination == current
            val label = stringResource(destination.barLabelRes)
            val interaction = remember { MutableInteractionSource() }
            // Each place takes an equal share of the bar (64 dp in English) so a longer word —
            // "Startseite", "Programmführer" — has the room there is before it has to shrink.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .then(if (on) Modifier.background(accent.copy(alpha = 0.14f)) else Modifier)
                        .stageClickable(interaction, { onClick(destination) }, { onLongClick(destination) }, Role.Tab)
                        .semantics {
                            selected = on
                            if (!labels) contentDescription = label
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    val tint = if (on) accent else palette.navText
                    StageIcon(destination.icon, 22.dp, tint)
                    if (labels) {
                        // Any language, any text size: a two-word label may take a second line, and a label
                        // still too wide shrinks (to 8 sp) before it would cut a word (German at 130%).
                        BasicText(
                            label,
                            style = stageText(10.5f, 700, lineHeight = 1f).copy(color = tint, textAlign = TextAlign.Center),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.5.sp, stepSize = 0.25.sp),
                            modifier = Modifier.padding(horizontal = 1.dp),
                        )
                    }
                }
                // `.it.on::after`: 5 dp, 6 dp below the item, glowing.
                if (on) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(top = 52.dp + 1.dp)
                            .stageShadow(accent, 8.dp, shape = CircleShape)
                            .size(5.dp)
                            .background(accent, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * The rail (`railH`): a floating glass column 58 dp wide on a 29 dp radius, 12 dp from the left edge and
 * centred on the screen, its items 40 dp wide on a 14 dp radius — the current place in the accent on a 14% tile.
 * [compact] (a phone held sideways): 34 dp items, nothing but the places, set 12 dp below the centre. A tablet's
 * has the app's mark on top, Search first, 40 dp items and the avatar at the bottom.
 */
@Composable
fun StageRail(
    destinations: List<MobileDestination>,
    current: MobileDestination?,
    compact: Boolean,
    profile: ProfileEntity?,
    onClick: (MobileDestination) -> Unit,
    onSearch: () -> Unit,
    onAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val itemHeight = if (compact) 34.dp else 40.dp
    @Composable
    fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onTap: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(40.dp, itemHeight)
                .clip(RoundedCornerShape(14.dp))
                .then(if (on) Modifier.background(accent.copy(alpha = 0.14f)) else Modifier)
                .stageClickable(interaction, onTap, null, Role.Tab)
                .semantics {
                    selected = on
                    contentDescription = label
                },
            contentAlignment = Alignment.Center,
        ) { StageIcon(icon, 19.dp, if (on) accent else palette.navText) }
    }
    Column(
        modifier
            .padding(start = 12.dp, top = if (compact) 24.dp else 0.dp)
            .width(58.dp)
            .stageGlass(RoundedCornerShape(29.dp))
            // A big display size can make even the compact rail taller than a sideways phone: it scrolls then.
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (!compact) {
            tv.own.owntv.mobile.ui.components.BrandMark(tv.own.owntv.mobile.ui.components.rememberAppliedIcon(), 30.dp, Modifier.padding(bottom = 6.dp))
            Item(MobileIcons.Search, stringResource(R.string.common_nav_search), false, onSearch)
        }
        destinations.forEach { destination ->
            Item(destination.railIcon, stringResource(destination.labelRes), destination == current) { onClick(destination) }
        }
        if (!compact) Box(Modifier.padding(top = 6.dp)) { ShellAvatar(profile, onAvatar) }
    }
}

/** More is the four tiles on the rail (`more`); everything else keeps its own icon. */
private val MobileDestination.railIcon
    get() = if (this == MobileDestination.MORE) MobileIcons.Tiles else icon

/**
 * The page header (`.hdr`): the page's own title (28 / 800) with the top-right [cluster] beside it, and
 * under it the crumb — "‹ where you came from" on a pushed page, which goes back when tapped (the
 * title names the page, the crumb says where from). Home has no title, only its [heading] once scrolled;
 * a detail page has only the crumb. A title page ([backPill]: a film, a show) has neither — the glass ‹
 * pill, then the cluster, over its own backdrop; the page names itself below.
 */
@Composable
fun StagePageHeader(
    title: String?,
    backLabel: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    heading: HomeHeading? = null,
    crumb: PageCrumb? = null,
    backPill: Boolean = false,
    titleSize: Float = 28f,
    inlineCrumb: Boolean = false,
    cluster: @Composable () -> Unit,
) {
    val palette = LocalStagePalette.current
    if (backPill) {
        Row(
            modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StagePill(onClick = onBack, icon = MobileIcons.ArrowBack, contentDescription = backLabel)
            Box(Modifier.weight(1f))
            cluster()
        }
        return
    }
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(
            verticalAlignment = if (heading != null || backLabel != null) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The cluster keeps its room (its playlist pill is only the mark) so the avatar is always there; a
            // title too long for the rest ("Programmübersicht" at a large font) shrinks rather than being cut.
            // Home's scrolled heading shares the row. A pushed page's way back ("‹ More") sits ABOVE its title
            // (`header(…, { back })`); a place's own crumb sits below.
            Column(Modifier.weight(1f)) {
                if (backLabel != null) {
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .padding(bottom = 4.dp)
                            .stageClickable(interaction, onBack, null)
                            .semantics { contentDescription = backLabel },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StageIcon(MobileIcons.ArrowBack, 13.dp, palette.muted)
                        // `.crumb` is muted 500; only a bold part (a category) takes the accent.
                        Text(backLabel, style = stageText(13f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (heading != null) {
                    HomeHeadingText(heading)
                } else if (title != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText(
                            title,
                            style = stageText(titleSize, 800, (-0.6f / 28f).em, lineHeight = 1.05f).copy(color = palette.text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = titleSize.sp, stepSize = 0.5.sp),
                            // The cluster is 36 dp tall; a one-line title sits on its centre line.
                            modifier = Modifier.padding(top = 2.dp).weight(1f, fill = false),
                        )
                        if (inlineCrumb && crumb != null) PageCrumbRow(crumb, Modifier.weight(1f, fill = false))
                    }
                }
            }
            if (heading != null) {
                Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.TopEnd) { cluster() }
            } else {
                cluster()
            }
        }
        if (crumb != null && !(inlineCrumb && title != null && heading == null)) PageCrumbRow(crumb, Modifier.padding(top = 5.dp))
    }
}

/** The crumb line (`.crumb`): ‹, the accent part, then the muted rest — under the title, or beside it sideways. */
@Composable
private fun PageCrumbRow(crumb: PageCrumb, modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.stageClickable(interaction, crumb.onClick, null),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The TV Guide's crumb has none: it is a place, not a way back.
        if (crumb.chevron) StageIcon(MobileIcons.ArrowBack, 13.dp, palette.muted)
        crumb.styled?.let {
            Text(it, style = stageText(13f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        Text(
            crumb.accent, style = stageText(13f, 700), color = stageAccent.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        crumb.rest?.let { Text(stringResource(R.string.content_epg_bits_separator).trimStart() + it, style = stageText(13f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * The top-right cluster (`.cl`): the playlist pill, search and the avatar that opens More. [playlist] is
 * the pill's state: null hides it (settings and More pages), [ClusterPlaylist.iconOnly] is Home's layers
 * pill. [extra] takes anything that must stay reachable from every page (the cast button); a weighted
 * item there (Home's Resume pill) is the one that shortens, so the avatar never leaves the screen.
 */
@Composable
fun ShellCluster(
    playlist: ClusterPlaylist?,
    profile: ProfileEntity?,
    showSearch: Boolean,
    onPlaylist: () -> Unit,
    onSearch: () -> Unit,
    onAvatar: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        extra()
        playlist?.let { pl ->
            if (pl.iconOnly) {
                StagePill(onClick = onPlaylist, icon = MobileIcons.Layers, contentDescription = pl.label)
            } else {
                // Owner, 2026-10-11: the mark alone (GOLD, JUNI, X) — or the layers icon for All playlists —
                // with the chevron; the name is left to the sheet and to TalkBack. Saves the header's width
                // for the page title at large zoom.
                StagePill(
                    onClick = onPlaylist,
                    icon = MobileIcons.Layers.takeIf { pl.mark == null },
                    mark = pl.mark,
                    trailingIcon = MobileIcons.KeyboardArrowDown.takeIf { pl.switchable },
                    contentDescription = pl.label,
                )
            }
        }
        if (showSearch) {
            StagePill(onClick = onSearch, icon = MobileIcons.Search, contentDescription = stringResource(R.string.common_nav_search))
        }
        ShellAvatar(profile, onAvatar)
    }
}

/** What the playlist pill says: the label, the chosen playlist's mark (none for All playlists), and whether it opens the sheet. */
data class ClusterPlaylist(val label: String, val mark: PlaylistMark?, val switchable: Boolean, val iconOnly: Boolean)

/**
 * `.av`: the profile as a 32 dp circle — its own picture (the drawn tile or a photo), or with none its
 * initial on a warm gradient. A tap opens More. More's profile card draws it at 44 dp and the
 * profile chooser at 104, neither with a tap of its own; the initial is 0.41 of the circle.
 */
@Composable
internal fun ShellAvatar(profile: ProfileEntity?, onClick: (() -> Unit)?, diameter: androidx.compose.ui.unit.Dp = 32.dp) {
    val interaction = remember { MutableInteractionSource() }
    val more = stringResource(R.string.common_nav_more)
    val modifier = Modifier
        .size(diameter)
        .clip(CircleShape)
        .then(if (onClick != null) Modifier.stageClickable(interaction, onClick, null).semantics { contentDescription = more } else Modifier)
    if (profile != null && (profile.avatarId >= 0 || !profile.avatarPath.isNullOrBlank())) {
        ProfileAvatar(profile.avatarId, modifier, profile.avatarPath.orEmpty())
    } else {
        ProfileInitial(profile?.name, diameter, modifier)
    }
}
