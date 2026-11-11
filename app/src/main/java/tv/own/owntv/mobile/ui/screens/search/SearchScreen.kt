package tv.own.owntv.mobile.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.content.SearchIntent
import tv.own.owntv.core.content.SearchKind
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.tvbox.search.OnlineSearchGroup
import tv.own.owntv.mobile.tvbox.search.OnlineSearchRow
import tv.own.owntv.mobile.tvbox.search.OnlineSearchState
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.home.formatRating
import tv.own.owntv.mobile.ui.screens.home.groupedCountText
import tv.own.owntv.mobile.ui.shell.PageCrumb
import tv.own.owntv.mobile.ui.shell.ProvidePageCrumb
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageEyebrow
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StagePlaylistMark
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageTabs
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText

/** With several kinds shown: the first four channels, then "All n channels" (the television's number). */
private const val ALL_TAB_CHANNELS = 4
private const val MIN_QUERY = 2

/**
 * Search, the shipped television's: the field, then — once two characters are typed — the
 * tabs All · Live TV · Movies · Series with their counts and the results grouped LIVE TV (three, then "All n
 * channels"), ON TV (programmes from the guide), MOVIES, SERIES; the header's crumb is the query and how
 * much it found. Before typing: JUMP TO, the recent searches and the card that says what is searched.
 *
 * A tap goes to the item — Live TV on that channel, the film's or the show's page. Nothing plays from
 * Search, and a hold does nothing: the item's own page has every option.
 */
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onOpenChannel: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenSiteResult: (siteKey: String, vodId: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = koinViewModel(),
) {
    // Arriving with a title already chosen — "All versions" on Home's trending hero. Keyed on the
    // text, so returning to a search the user has since edited does not type over it again.
    LaunchedEffect(initialQuery) { if (initialQuery.isNotBlank()) vm.setQuery(initialQuery) }

    val query by vm.query.collectAsStateWithLifecycle()
    val intent by vm.intent.collectAsStateWithLifecycle()
    val typed by vm.results.collectAsStateWithLifecycle()
    val kinds by vm.kinds.collectAsStateWithLifecycle()
    val curated by vm.curated.collectAsStateWithLifecycle()
    val recents by vm.recentSearches.collectAsStateWithLifecycle()
    val marks by vm.playlistMarks.collectAsStateWithLifecycle()
    val categories by vm.categoryNames.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val palette = LocalStagePalette.current

    val trimmed = query.trim()
    val searching = trimmed.length >= MIN_QUERY
    val total = typed.channels.size + typed.programmes.size + typed.movies.size + typed.series.size + online.rowCount
    if (searching) {
        val accent = stringResource(R.string.search_query_quoted, trimmed)
        val rest = groupedCountText(R.plurals.search_result_count, total)
        ProvidePageCrumb { owner -> PageCrumb(accent, rest, chevron = false, owner = owner) {} }
    }

    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focus alone does not reliably raise the IME across Android versions, keyboard apps and
    // navigation states. Request focus, then explicitly show the keyboard once the field is
    // attached. Delayed: requesting in the same frame as composition is dropped on some builds.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        delay(150)
        runCatching { keyboard?.show() }
    }
    LaunchedEffect(query, intent, kinds) { listState.scrollToItem(0) }
    // Back clears the filter first, as on the television; the next Back leaves Search.
    BackHandler(enabled = kinds.isNotEmpty()) { vm.clearKinds() }

    val shown: SearchResults = if (searching) typed.only(kinds) else curated
    // One filter button on lists that kind alone, flat and uncapped; otherwise the kinds are grouped.
    val singleKind = searching && kinds.size == 1
    // The online supplement keeps the results list up while sites answer, so an empty local match is
    // not "nothing found" until the online round has finished too.
    val onlineActive = online.hasSites && (online.searching || !online.isEmpty)
    val goChannel: (Long) -> Unit = { vm.rememberQuery(); onOpenChannel(it) }

    Column(modifier.fillMaxSize().imePadding()) {
        StageSearchField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = stringResource(R.string.search_field_hint),
            imeAction = ImeAction.Search,
            onSearch = { vm.rememberQuery(); keyboard?.hide() },
            onClear = { vm.setQuery("") },
            clearLabel = stringResource(R.string.search_clear),
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp).fillMaxWidth().focusRequester(focus),
        )
        if (searching) {
            // The television's filter buttons: a tap switches one on or off, several can be on, none on is everything.
            val all = SearchKind.entries
            StageTabs(
                tabs = all.map { it.label() to typed.count(it) },
                selected = all.indices.filter { all[it] in kinds }.toSet(),
                onSelect = { vm.toggleKind(all[it]) },
                modifier = Modifier.padding(start = 18.dp, end = 14.dp, top = 14.dp),
            )
        }

        when {
            searching && shown.isEmpty && !onlineActive -> NothingFound(stringResource(R.string.search_nothing_found, trimmed), stringResource(R.string.search_nothing_found_body))
            !searching && intent == null -> Launcher(recents = recents, onIntent = vm::setIntent, onPick = vm::setQuery, onClear = vm::clearRecentSearches)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 24.dp),
            ) {
                if (!searching) item(key = "jump") { JumpTo(selected = intent, onIntent = vm::setIntent) }
                if (!searching && shown.isEmpty) {
                    item(key = "empty") { NothingFound(stringResource(R.string.search_nothing_in_list, intent.label()), null) }
                }
                results(
                    shown = shown,
                    capChannels = searching && !singleKind,
                    headings = !singleKind,
                    marks = marks,
                    categories = categories,
                    nowPlaying = nowPlaying,
                    onChannel = goChannel,
                    onMovie = { vm.rememberQuery(); onOpenMovie(it) },
                    onSeries = { vm.rememberQuery(); onOpenSeries(it) },
                    onAllChannels = { vm.showOnly(SearchKind.LIVE) },
                )
                if (onlineActive) {
                    onlineSection(online, first = shown.isEmpty, onOpenSiteResult = { site, vod -> vm.rememberQuery(); onOpenSiteResult(site, vod) })
                }
            }
        }
    }
}

/** LIVE TV (capped in the All tab, then "All n channels"), ON TV, MOVIES, SERIES — each under `.sgh` "LABEL · n". */
private fun LazyListScope.results(
    shown: SearchResults,
    capChannels: Boolean,
    headings: Boolean,
    marks: Map<Long, PlaylistMark>,
    categories: Map<Long, String>,
    nowPlaying: Map<Long, String>,
    onChannel: (Long) -> Unit,
    onMovie: (Long) -> Unit,
    onSeries: (Long) -> Unit,
    onAllChannels: () -> Unit,
) {
    if (shown.channels.isNotEmpty()) {
        if (headings) item(key = "h-live") { GroupHeading(R.string.common_nav_live_tv, shown.channels.size, first = true) }
        val cap = capChannels && shown.channels.size > ALL_TAB_CHANNELS
        items(if (cap) shown.channels.take(ALL_TAB_CHANNELS) else shown.channels, key = { "c" + it.channel.id }) { row ->
            ChannelResult(row.channel, row.categoryName, nowPlaying[row.channel.id], marks[row.channel.sourceId]) { onChannel(row.channel.id) }
        }
        if (cap) {
            item(key = "all-channels") {
                StageTool(
                    onClick = onAllChannels,
                    label = groupedCountText(R.plurals.search_all_channels, shown.channels.size),
                    icon = MobileIcons.FormatListBulleted,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                )
            }
        }
    }
    if (shown.programmes.isNotEmpty()) {
        if (headings) item(key = "h-ontv") { GroupHeading(R.string.search_on_tv, shown.programmes.size, first = shown.channels.isEmpty()) }
        items(shown.programmes, key = { "p" + it.programme.id + "-" + it.channel.id }) { found ->
            ProgrammeResult(found.channel, found.programme.title, found.programme.startMs, found.programme.stopMs, marks[found.channel.sourceId]) { onChannel(found.channel.id) }
        }
    }
    if (shown.movies.isNotEmpty()) {
        if (headings) item(key = "h-movies") { GroupHeading(R.string.common_nav_movies, shown.movies.size, first = shown.channels.isEmpty() && shown.programmes.isEmpty()) }
        items(shown.movies, key = { "m" + it.id }) { m ->
            VodResult(m.posterUrl, m.name, m.year ?: m.parsedYear, m.rating, m.categoryId?.let { categories[it] }, marks[m.sourceId]) { onMovie(m.id) }
        }
    }
    if (shown.series.isNotEmpty()) {
        if (headings) item(key = "h-series") { GroupHeading(R.string.common_nav_series, shown.series.size, first = shown.channels.isEmpty() && shown.programmes.isEmpty() && shown.movies.isEmpty()) }
        items(shown.series, key = { "s" + it.id }) { s ->
            VodResult(s.posterUrl, s.name, s.year ?: s.parsedYear, s.rating, s.categoryId?.let { categories[it] }, marks[s.sourceId]) { onSeries(s.id) }
        }
    }
}

/** The online supplement (plan P3): a heading, then each site's hits under its name, streaming in. */
private fun LazyListScope.onlineSection(
    online: OnlineSearchState,
    first: Boolean,
    onOpenSiteResult: (String, String) -> Unit,
) {
    item(key = "h-online") { GroupHeading(R.string.search_online, online.rowCount, first = first) }
    if (online.searching && online.isEmpty) {
        item(key = "online-searching") {
            Text(
                stringResource(R.string.search_online_searching),
                style = stageText(12.5f, 500),
                color = LocalStagePalette.current.muted,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
    online.groups.forEach { g ->
        item(key = "online-site-" + g.siteKey) {
            StageLabel(g.siteName, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp))
        }
        items(g.rows, key = { "online-" + g.siteKey + "-" + it.vodId }) { row ->
            OnlineResult(row) { onOpenSiteResult(row.siteKey, row.vodId) }
        }
    }
}

/** An online hit: the poster, the title, "year · category · remark". No playlist mark — it is remote. */
@Composable
private fun OnlineResult(row: OnlineSearchRow, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val sep = stringResource(R.string.content_epg_bits_separator)
    val line = listOfNotNull(
        row.year?.takeIf { it.isNotBlank() },
        row.typeName?.takeIf { it.isNotBlank() },
        row.remark?.takeIf { it.isNotBlank() },
    ).joinToString(sep)
    StageRow(onClick = onClick, height = 76.dp) {
        AsyncImage(
            row.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(palette.cardSolid),
        )
        Column(Modifier.weight(1f)) {
            Text(row.title, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = stageText(12f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** `.label` "LIVE TV · 9", 12 in from the rows; 16 above every group but the first. */
@Composable
private fun GroupHeading(labelRes: Int, count: Int, first: Boolean) {
    StageLabel(
        stringResource(labelRes) + stringResource(R.string.content_epg_bits_separator) + count,
        Modifier.padding(start = 12.dp, end = 12.dp, top = if (first) 0.dp else 16.dp, bottom = 4.dp),
    )
}

/** The channel's logo on its 46 × 34 plate (a plate only where its ink would vanish). */
@Composable
private fun LogoPlate(channel: ChannelEntity) {
    val palette = LocalStagePalette.current
    Box(Modifier.size(46.dp, 34.dp).clip(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        ChannelLogoImage(
            url = channel.displayLogoUrl,
            modifier = Modifier.fillMaxSize(),
            plateOn = palette.page,
            fallback = { StageIcon(MobileIcons.LiveTv, 17.dp, palette.dim) },
        )
    }
}

/** `.chrow` 62: the plate, the name without its marks, "category · number · on now", then its tags and playlist mark. */
@Composable
private fun ChannelResult(channel: ChannelEntity, category: String?, now: String?, mark: PlaylistMark?, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val name = remember(channel.name) { ProviderTags.parse(channel.name) }
    val sep = stringResource(R.string.content_epg_bits_separator)
    val line = listOfNotNull(
        category?.takeIf { it.isNotBlank() }?.let { ProviderTags.parse(it).name },
        channel.number?.takeIf { it > 0 }?.toString(),
        now,
    ).joinToString(sep)
    StageRow(onClick = onClick, height = 62.dp) {
        LogoPlate(channel)
        Column(Modifier.weight(1f)) {
            Text(name.name, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = stageText(12f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            name.tags.forEach { StageTag(it) }
            mark?.let { StagePlaylistMark(it) }
        }
    }
}

/** ON TV: the channel's plate, the programme, "channel · 21:50 – 22:40". */
@Composable
private fun ProgrammeResult(channel: ChannelEntity, title: String, startMs: Long, stopMs: Long, mark: PlaylistMark?, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val locales = LocalConfiguration.current.locales
    val times = remember(locales) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val line = ProviderTags.parse(channel.name).name + stringResource(R.string.content_epg_bits_separator) +
        stringResource(R.string.content_live_time_range_plain, times.format(java.util.Date(startMs)), times.format(java.util.Date(stopMs)))
    StageRow(onClick = onClick, height = 62.dp) {
        LogoPlate(channel)
        Column(Modifier.weight(1f)) {
            Text(title, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = stageText(12f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        mark?.let { StagePlaylistMark(it) }
    }
}

/** `.chrow` 76: the 42 × 63 poster, the title, "2005 · ★ 6.2 · category", the playlist mark. */
@Composable
private fun VodResult(poster: String?, title: String, year: Int?, rating: Double?, category: String?, mark: PlaylistMark?, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val sep = stringResource(R.string.content_epg_bits_separator)
    val line: AnnotatedString = buildAnnotatedString {
        var first = true
        fun next() { if (!first) append(sep); first = false }
        year?.takeIf { it > 0 }?.let { next(); append(it.toString()) }
        rating?.takeIf { it > 0 }?.let { r ->
            next()
            withStyle(SpanStyle(color = RatingStar)) { append(STAR) }
            append(" " + formatRating(r))
        }
        category?.takeIf { it.isNotBlank() }?.let { next(); append(ProviderTags.parse(it).name) }
    }
    StageRow(onClick = onClick, height = 76.dp) {
        AsyncImage(
            poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(palette.cardSolid),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = stageText(12f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        mark?.let { StagePlaylistMark(it) }
    }
}

private val RatingStar = Color(0xFFFFCC4D)
private const val STAR = "★"

/** JUMP TO: Continue watching · Unwatched · Channels, the chosen one on. */
@Composable
private fun JumpTo(selected: SearchIntent?, onIntent: (SearchIntent?) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        StageLabel(stringResource(R.string.search_jump_to), Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchIntent.entries.forEach { entry ->
                StageTool(
                    onClick = { onIntent(if (entry == selected) null else entry) },
                    label = entry.label(),
                    icon = when (entry) {
                        SearchIntent.CONTINUE -> MobileIcons.PlayOutline
                        SearchIntent.UNWATCHED -> MobileIcons.AutoAwesome
                        SearchIntent.CHANNELS -> MobileIcons.LiveTv
                    },
                    on = entry == selected,
                )
            }
        }
        Box(Modifier.size(1.dp, 10.dp))
    }
}

/** Before typing: JUMP TO, RECENT SEARCHES with Clear, and the glass card that says what is searched. */
@Composable
private fun Launcher(recents: List<String>, onIntent: (SearchIntent?) -> Unit, onPick: (String) -> Unit, onClear: () -> Unit) {
    val palette = LocalStagePalette.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 24.dp)) {
        item(key = "jump") { JumpTo(selected = null, onIntent = onIntent) }
        if (recents.isNotEmpty()) {
            item(key = "recent-h") { StageLabel(stringResource(R.string.search_recent), Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
            items(recents, key = { "r$it" }) { term -> StageMenuItem(term, onClick = { onPick(term) }, icon = MobileIcons.History) }
            item(key = "recent-clear") {
                StageTool(onClick = onClear, label = stringResource(R.string.search_clear_recent), icon = MobileIcons.Delete, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            }
        }
        item(key = "card") {
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 22.dp).fillMaxWidth().stageGlass(shape).padding(16.dp)) {
                StageEyebrow(stringResource(R.string.search_title), icon = MobileIcons.Search)
                Text(stringResource(R.string.search_card_title), style = stageText(16f, 800), color = palette.text, modifier = Modifier.padding(vertical = 6.dp))
                Text(stringResource(R.string.search_card_body), style = stageText(12f, 500, lineHeight = 1.5f), color = palette.muted)
            }
        }
    }
}

/** "Nothing found for “qwzx”" (20 / 800) and what Search looks in. */
@Composable
private fun NothingFound(title: String, body: String?) {
    val palette = LocalStagePalette.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text(title, style = stageText(20f, 800), color = palette.text)
        body?.let { Text(it, style = stageText(12.5f, 500, lineHeight = 1.5f), color = palette.muted, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** The filter buttons' words: Live TV · TV Shows · Movies · Series. */
@Composable
private fun SearchKind.label(): String = stringResource(
    when (this) {
        SearchKind.LIVE -> R.string.common_nav_live_tv
        SearchKind.TV_SHOWS -> R.string.search_on_tv
        SearchKind.MOVIES -> R.string.common_nav_movies
        SearchKind.SERIES -> R.string.common_nav_series
    },
)

@Composable
private fun SearchIntent?.label(): String = when (this) {
    SearchIntent.CONTINUE -> stringResource(R.string.search_continue)
    SearchIntent.UNWATCHED -> stringResource(R.string.search_unwatched)
    SearchIntent.CHANNELS -> stringResource(R.string.search_channels)
    null -> stringResource(R.string.search_title)
}
