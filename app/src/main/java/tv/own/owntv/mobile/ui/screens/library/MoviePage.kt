package tv.own.owntv.mobile.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.metadata.HeadlineTitle
import tv.own.owntv.core.metadata.MetadataCast
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.metadata.TrailerKeys
import tv.own.owntv.core.trending.ProviderVariantParser
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.downloadActionFor
import tv.own.owntv.mobile.ui.components.icon
import tv.own.owntv.mobile.ui.components.jsonList
import tv.own.owntv.mobile.ui.components.label
import tv.own.owntv.mobile.ui.components.onTap
import tv.own.owntv.mobile.ui.player.rememberResumeGate
import tv.own.owntv.mobile.ui.screens.home.durationText
import tv.own.owntv.mobile.ui.screens.home.formatRating
import tv.own.owntv.mobile.ui.screens.home.formatYear
import tv.own.owntv.mobile.ui.shell.LocalTitleChrome
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageCastRow
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageLabel
import tv.own.owntv.mobile.ui.stage.StagePoster
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTitleArt
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The movie page: the backdrop across the top (lent to the shell, behind the ‹ pill and the cluster),
 * the TMDB title art — or the title — at 230 dp, "Movies · **category**", the meta line (year · genres ·
 * length · ★ rating, then the quality tags), the plot, Play · Trailer · ⋯, Favourite · Download · versions,
 * the cast, and "More in <category>" — four more of its posters.
 *
 * Play goes through the Resume playback setting, so a part-watched film offers Resume or Start over; ⋯ is
 * the grid's own options menu; versions searches for the title, as Home's "All versions" does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoviePage(
    vm: DetailViewModel,
    onPlay: () -> Unit,
    onOpenItem: (LibraryTab, Long) -> Unit,
    onOpenSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val movie by vm.movie.collectAsStateWithLifecycle()
    val page by vm.titlePage.collectAsStateWithLifecycle()
    val favorite by vm.isFavorite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val itemDownloads by vm.itemDownloads.collectAsStateWithLifecycle()
    val palette = LocalStagePalette.current
    val density = LocalDensity.current
    val film = movie
    val meta = page.meta

    // The backdrop is the page's, drawn by the shell behind the header and the status bar (`.bdrop` 420).
    val titleChrome = LocalTitleChrome.current
    DisposableEffect(titleChrome) { onDispose { titleChrome?.backdropUrl = null } }
    SideEffect {
        titleChrome?.backdropUrl = film?.let { MetadataImages.backdrop(meta?.backdropPath, size = "w1280") ?: it.backdropUrl ?: it.posterUrl }
        titleChrome?.backdropHeight = 420.dp
        titleChrome?.backdropAlignment = BiasAlignment(0.4f, -0.4f)
    }
    film ?: return
    // A TVBOX row is a catalogue identity, not a downloadable address; its line/episode choice
    // lives on the VOD detail page, so this page must not render a dead Download button.
    val isTvbox = film.streamUrl.startsWith(tv.own.owntv.mobile.tvbox.index.TvboxIndexMapper.STREAM_PREFIX)

    // The title art is placed from the top of the screen, as drawn, whatever the status bar and header take.
    val estimatedTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp
    var contentTop by remember { mutableStateOf<Dp?>(null) }
    val topGap = (ART_TOP - (contentTop ?: estimatedTop)).coerceAtLeast(0.dp)
    val scroll = rememberScrollState()
    // The picture fades as the page scrolls up, so the cast never sits over it — straight to the draw phase.
    val fadePx = with(density) { 360.dp.toPx() }
    LaunchedEffect(titleChrome, scroll) {
        snapshotFlow { 1f - scroll.value / fadePx }.collect { titleChrome?.backdropAlpha = it.coerceIn(0f, 1f) }
    }

    // ⋯ is the grid's own menu: the library's view model, pinned to All (there is no folder to move in here).
    val library: LibraryViewModel = koinViewModel()
    DisposableEffect(library) {
        library.select(LibraryTab.MOVIES)
        library.lock(LiveKey.All)
        onDispose { library.unlock() }
    }
    // Collected for the page's whole life: it keeps the library's profile and playlists read, so the menu's
    // actions find them the moment it opens (and it names the film's category in the menu's header).
    val categories by library.categories.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val downloadsAllowed by org.koin.compose.koinInject<tv.own.owntv.core.settings.SettingsRepository>().activeDownloadsAllowed
        .collectAsStateWithLifecycle(true)
    val resumeGate = rememberResumeGate()

    val title = HeadlineTitle.of(film.name)
    val tmdbWins = page.tmdbWins
    val plot = listOf(meta?.overview, film.plot).let { if (tmdbWins) it else it.reversed() }.firstOrNull { !it.isNullOrBlank() }
    val rating = listOf(meta?.rating, film.rating).let { if (tmdbWins) it else it.reversed() }.firstOrNull { it != null && it > 0 }
    val trailer = TrailerKeys.split(meta?.trailerKey).firstOrNull()
    val cast = remember(meta?.castJson) { MetadataCast.parse(meta?.castJson) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (scroll.value == 0) contentTop = with(density) { coords.positionInWindow().y.toDp() }
            }
            .verticalScroll(scroll)
            .padding(start = 18.dp, end = 18.dp, top = topGap, bottom = 22.dp),
    ) {
        var logoFailed by remember(meta?.logoPath) { mutableStateOf(false) }
        val logo = MetadataImages.logo(meta?.logoPath)?.takeIf { !logoFailed }
        if (logo != null) {
            StageTitleArt(
                url = logo,
                contentDescription = title,
                onFailed = { logoFailed = true },
                modifier = Modifier.fillMaxWidth(0.85f).height(84.dp),
            )
        } else {
            Text(title, style = stageText(36f, 800, (-1f / 36f).em, lineHeight = 1.02f), color = palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        // "Movies · Disney+ Kids Movies" (`.crumb`): where it sits, the category in the accent.
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.common_nav_movies))
                page.categoryName?.let {
                    append(stringResource(R.string.content_epg_bits_separator))
                    withStyle(SpanStyle(color = stageAccent.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(it) }
                }
            },
            style = stageText(13f, 500),
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        MovieMeta(
            parts = listOfNotNull(
                (film.year ?: meta?.year)?.takeIf { it > 0 }?.let(::formatYear),
                jsonList(meta?.genresJson).take(3).takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.content_epg_bits_separator)),
                film.durationSecs?.takeIf { it >= 60 }?.let { durationText(it / 60L) },
            ),
            rating = rating?.let(::formatRating),
            tags = ProviderVariantParser.displaySignals(film.name).let { listOfNotNull(it.quality.label) + it.capabilities },
        )
        plot?.let {
            Text(it, style = stageText(13f, 500, lineHeight = 1.5f), color = palette.body, modifier = Modifier.padding(top = 10.dp))
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageButton(
                onClick = { resumeGate(progress?.takeIf { it.durationMs > 1L }?.positionMs ?: 0L) { vm.play(it, onPlay) } },
                text = stringResource(R.string.content_play),
                icon = MobileIcons.PlayArrow,
                style = StageButtonStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            )
            trailer?.let { key ->
                StageButton(onClick = { context.playTrailer(key) }, text = stringResource(R.string.home_trending_trailer), icon = MobileIcons.PlayCircle)
            }
            val more = stringResource(R.string.content_key_options)
            StageButton(onClick = { menuOpen = true }, icon = MobileIcons.MoreHoriz, modifier = Modifier.semantics { contentDescription = more })
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageButton(
                onClick = vm::toggleFavorite,
                text = stringResource(R.string.content_favorite),
                icon = if (favorite) MobileIcons.Favorite else MobileIcons.FavoriteBorder,
                modifier = Modifier.weight(1f),
            )
            val download = downloadActionFor(itemDownloads)
            if (downloadsAllowed && !isTvbox) StageButton(
                onClick = download.onTap(
                    onDownload = vm::download,
                    onRetry = { vm.retryDownloads(itemDownloads) },
                    onDelete = { vm.deleteDownloads(itemDownloads) },
                ),
                text = download.label(),
                icon = download.icon(),
                style = if (download.deletes) StageButtonStyle.DANGER else StageButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            val versions = stringResource(R.string.home_trending_all_versions)
            StageButton(
                onClick = { onOpenSearch(film.canonicalTitle.ifBlank { title }) },
                icon = MobileIcons.Layers,
                modifier = Modifier.semantics { contentDescription = versions },
            )
        }
        if (cast.isNotEmpty()) {
            StageLabel(stringResource(R.string.content_media_cast), Modifier.padding(top = 22.dp, bottom = 10.dp))
            StageCastRow(cast)
        }
        if (page.more.isNotEmpty()) {
            StageLabel(stringResource(R.string.content_vod_more_in, page.categoryName.orEmpty()), Modifier.padding(top = 22.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                page.more.forEach { other ->
                    StagePoster(
                        imageUrl = other.posterUrl,
                        title = other.name,
                        rating = other.rating?.takeIf { it > 0 }?.let(::formatRating),
                        onClick = { onOpenItem(LibraryTab.MOVIES, other.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // A short category still lays its posters out at a quarter of the row each.
                repeat(MORE_COLUMNS - page.more.size) { Box(Modifier.weight(1f)) }
            }
        }
    }

    if (menuOpen) {
        VodMenu(
            item = VodItem(
                id = film.id,
                name = film.name,
                posterUrl = film.posterUrl,
                year = film.year,
                rating = film.rating,
                sourceId = film.sourceId,
                categoryId = film.categoryId,
                durationSecs = film.durationSecs,
            ),
            tab = LibraryTab.MOVIES,
            selected = LiveKey.All,
            isFavorite = favorite,
            originName = "",
            categories = categories,
            vm = library,
            onDismiss = { menuOpen = false },
        )
    }
}

/**
 * The meta line (`metaLine`): the parts with faint dots between, then ★ and the rating in gold, then the tags.
 * 13 on the movie page, 12.5 on the series page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MovieMeta(parts: List<String>, rating: String?, tags: List<String>, size: Float = 13f) {
    val muted = LocalStagePalette.current.muted
    val style = stageText(size, 600)
    val dot = stringResource(R.string.content_epg_bits_separator).trim()
    @Composable
    fun Dot() = Text(dot, style = style, color = muted.copy(alpha = 0.5f))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Dot()
            Text(part, style = style, color = muted)
        }
        rating?.let {
            if (parts.isNotEmpty()) Dot()
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                StageIcon(MobileIcons.Star, size.dp, RatingGold)
                Text(it, style = style, color = RatingGold)
            }
        }
        tags.forEach { StageTag(it) }
    }
}

/** Where the title art starts, from the top of the screen (`top: 230px` of the frame). */
private val ART_TOP = 230.dp

/** "More in …" is one row of four posters. */
private const val MORE_COLUMNS = 4

/** The rating's gold, `#ffcc4d`. */
private val RatingGold = Color(0xFFFFCC4D)
