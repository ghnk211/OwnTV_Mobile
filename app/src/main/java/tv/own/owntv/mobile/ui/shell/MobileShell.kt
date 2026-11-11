package tv.own.owntv.mobile.ui.shell

import tv.own.owntv.mobile.ui.screens.MoreLeaf
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import tv.own.owntv.core.categorylock.firstUnsealed
import tv.own.owntv.core.categorylock.sealedCategoryIds
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.cast.CastRouteButton
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import kotlinx.coroutines.delay
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.mobile.ui.screens.settings.UpdateSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.StagePill
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import tv.own.owntv.core.metadata.MetadataBudget
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.nav.MobileDestination.Companion.standingFor
import tv.own.owntv.mobile.ui.nav.MobileDestination.Companion.visible
import tv.own.owntv.mobile.ui.nav.MobileNavHost
import tv.own.owntv.mobile.playback.PipController
import tv.own.owntv.mobile.ui.nav.PLAYER_ROUTE
import tv.own.owntv.mobile.ui.nav.isTitleRoute
import tv.own.owntv.mobile.ui.nav.SEARCH_ROUTE
import tv.own.owntv.mobile.ui.nav.SEARCH_ROUTE_PATTERN
import tv.own.owntv.mobile.ui.nav.SETUP_ROUTE_PATTERN
import tv.own.owntv.mobile.ui.profiles.ProfileGate
import tv.own.owntv.mobile.ui.profiles.ProfileGateSession
import tv.own.owntv.mobile.ui.profiles.ProfilesViewModel
import tv.own.owntv.mobile.ui.setup.SetupFlow
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.mobile.ui.player.FloatingMiniPlayer
import tv.own.owntv.mobile.ui.player.FloatingWindowMenu
import tv.own.owntv.mobile.ui.player.MiniPlayer
import tv.own.owntv.mobile.ui.player.SleepTimerSheet
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.morePageTitleRes
import tv.own.owntv.mobile.ui.screens.settings.settingsPageTitleRes
import tv.own.owntv.mobile.ui.screens.settings.isSettingsPage

/**
 * The frame every screen sits in (Stage): the Stage page background, the page header with the
 * top-right cluster, the content, and the floating bottom bar — no title bar and no page panel.
 *
 * The navigation is the bottom bar on a phone and a rail on anything wider, chosen by width rather
 * than by device type — a phone in landscape and a foldable opened flat are both "wider", and a
 * tablet in split-screen is not. The phone offers Home, Live TV and On demand; the rail adds
 * Downloads and More. Which of them appear is core's `NavVisibility` rule, the same one the TV app's
 * sidebar uses, so a channels-only playlist loses Live TV in both apps at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileShell(
    windowWidthDp: Int,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val shellViewModel: ShellViewModel = koinViewModel()
    val sections by shellViewModel.visibleSections.collectAsStateWithLifecycle()
    // Room and the active-profile preference both have to answer before this frame can say anything.
    // On the way, the view model points the stored id at a real row if it no longer does.
    val recovering by shellViewModel.recovering.collectAsStateWithLifecycle()
    if (recovering) return

    val profilesViewModel: ProfilesViewModel = koinViewModel()
    val gateSession: ProfileGateSession = koinViewModel()
    val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by profilesViewModel.activeProfileId.collectAsStateWithLifecycle()

    /*
     * What the app shows before the app itself, in the one order that is correct:
     *
     *  - Nothing at all while either answer is still missing. A blank frame is the price of never
     *    showing the wrong person's library, and of never guessing from half the inputs.
     *  - **No active profile is the setup flow, not a lock screen.** The wizard asks about the
     *    *profile*, the same way the television does, so its onboarding stays up until one is active
     *    and the lock never gets a turn on the way past. **Skip for now** is a legitimate end:
     *    finishing with no playlist at all still lands in the app.
     *  - Then the app lock — one profile, one PIN, and only when that profile has one. It is shown
     *    *instead of* the app, never over it, so no back gesture, deep link or notification tap can
     *    reveal a library behind a dialog.
     */
    val loadedProfiles = profiles ?: return
    val activeId = activeProfileId ?: return
    val activeProfile = loadedProfiles.firstOrNull { it.id == activeId }
    if (activeId < 0L || activeProfile == null) {
        // Deliberately not an unlock: the wizard may have recovered a profile that carries a PIN, and
        // `pinHash` below is the only thing that decides whether the app is locked.
        SetupFlow(onDone = { _ -> }, modifier = modifier)
        return
    }
    // Authentication is profile-bound: invalidate an old unlock before a changed active id can turn
    // into a shell destination, whether it changed by a deletion, a restore, or another writer.
    LaunchedEffect(activeId) { gateSession.invalidateIfNotProfile(activeId) }
    val locked = activeProfile.pinHash != null
    // An unlock on a profile with no PIN is not a choice the user has to make; nobody locks this app
    // by accident, and the session is what the rest of the frame checks.
    LaunchedEffect(activeId, locked) { if (!locked) gateSession.unlock(activeId) }
    if (locked && gateSession.unlockedProfileId != activeId) {
        ProfileGate(
            profile = activeProfile,
            onUnlocked = { gateSession.unlock(activeId) },
            modifier = modifier,
            vm = profilesViewModel,
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 600dp is Material's compact/medium boundary: below it a rail would eat the content.
    val useRail = windowWidthDp >= 600
    // A phone held sideways (409 dp tall) gets the compact rail and the one-line header.
    val compactRail = LocalConfiguration.current.screenHeightDp < 600
    val destinations =
        (if (useRail) MobileDestination.rail else MobileDestination.bottomBar).visible(sections)
    // A detail route ("live/42/false") keeps its tab selected and its tab's title: on a phone the
    // channel you opened is still Live TV, and the bottom bar must not go blank while you watch it.
    val current = destinations.firstOrNull { it.route == currentRoute }
        ?: destinations.firstOrNull { currentRoute?.startsWith("${it.route}/") == true }
        ?: destinations.standingFor(currentRoute)

    // The full screen player is the one destination that owns the whole display: no bars, no rail,
    // and no mini player, because the thing the mini player would be showing is already on screen.
    val fullscreen = currentRoute == PLAYER_ROUTE || currentRoute == SETUP_ROUTE_PATTERN
    val tuner: LiveTuner = koinInject()
    val vodTuner: VodTuner = koinInject()
    val channel by tuner.channel.collectAsStateWithLifecycle()
    val nowNext by tuner.nowNext.collectAsStateWithLifecycle()
    val film by vodTuner.playing.collectAsStateWithLifecycle()
    // Only ever ONE view of the picture at a time: the engine renders into a single surface, and a
    // second one attaching would take it away from the first. So no mini player on a screen that is
    // already showing the stream.
    // Live TV raises this while its preview shows the picture, so no mini player is put over it.
    val streamOnScreen = remember { mutableStateOf(false) }
    val showingStream = fullscreen || streamOnScreen.value
    // A mini player is something the user asks for in the full screen player, never something the
    // app decides for them — see [LocalMiniRequested].
    val miniRequested = remember { mutableStateOf(false) }
    val nothingPlaying = channel == null && film == null
    // The request dies with the stream, so the next one starts without a window the user never asked
    // for; without this, stopping and then previewing another channel would bring the old one back.
    LaunchedEffect(nothingPlaying) { if (nothingPlaying) miniRequested.value = false }
    val showMini = !nothingPlaying && !showingStream && miniRequested.value
    // Floating window, bar above the tabs, or neither — the user's choice, and the only thing that
    // changes is where the same stream is drawn.
    val settings: SettingsRepository = koinInject()
    val chosenMiniStyle by settings.miniPlayerStyle
        .collectAsStateWithLifecycle(SettingsRepository.MiniPlayerStyle.FLOATING)
    // Sound only has no picture, and a floating window with nothing in it is a smudge over the list the
    // user went back to. It docks instead, as a bar — which is also the shape that has room for a title
    // and the transport buttons, the only things left to show.
    // The engine that actually has the stream — live is on ExoPlayer by default, and mpv is stopped
    // there, so these two were permanently false for a live channel and a sound-only channel still
    // got the floating window it has no picture to fill.
    val playingEngine by tuner.activeEngine.collectAsStateWithLifecycle()
    val audioOnly by playingEngine.audioOnly.collectAsStateWithLifecycle()
    val audioOnlyMedia by playingEngine.audioOnlyMedia.collectAsStateWithLifecycle()
    // Casting has the same shape of problem as sound only: the picture is on the television, so a
    // floating window here would be an empty black square following the user around.
    val cast: CastController = koinInject()
    val castEngine by cast.engine.collectAsStateWithLifecycle()
    // OFF is not offered in Settings any more, but an older install may still have it stored.
    val miniStyle = if (castEngine != null || audioOnly || audioOnlyMedia ||
        chosenMiniStyle == SettingsRepository.MiniPlayerStyle.OFF
    ) {
        SettingsRepository.MiniPlayerStyle.DOCKED
    } else {
        chosenMiniStyle
    }

    // The playback notification was tapped, from a shade that may well outlive the activity that was
    // showing the player. Put it back.
    val pip: PipController = koinInject()
    val openPlayerRequested by pip.openPlayerRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            pip.openPlayerRequested.value = false
            if (channel != null || film != null) navController.navigate(PLAYER_ROUTE)
        }
    }

    // "Start on" — Home, the last channel watched, the Favorites folder, or one chosen channel. Once
    // per launch, and only once the database has said there is something to open: on a first run the
    // setup flow is the app, and a channel resolved against an empty database is not a missing
    // channel. A channel opens its own screen rather than the full screen player, because a phone is
    // picked up in places where sound arriving unannounced is not welcome.
    val context = LocalContext.current
    val startupLive: StartupLiveSelection = koinInject()
    val startupUnavailable = stringResource(tv.own.owntv.mobile.R.string.settings_startup_channel_unavailable)
    var startupHandled by remember { mutableStateOf(false) }
    // Reaching this point already means the setup flow and the profile chooser are both behind us —
    // that is what the branches above guarantee — so there is nothing left to wait for.
    LaunchedEffect(Unit) {
        if (startupHandled) return@LaunchedEffect
        startupHandled = true
        when (val target = shellViewModel.resolveStartup()) {
            StartupTarget.Home -> Unit
            StartupTarget.Favorites -> {
                startupLive.requestFavorites()
                navController.navigateToTab(MobileDestination.LIVE)
            }
            // Start on a channel: it plays full screen, as on the television.
            is StartupTarget.Channel -> {
                tuner.tune(target.id)
                navController.navigate(PLAYER_ROUTE)
            }
            is StartupTarget.ChannelIn -> {
                startupLive.requestChannel(target)
                navController.navigateToTab(if (target.guide) MobileDestination.GUIDE else MobileDestination.LIVE)
            }
            StartupTarget.ChannelUnavailable ->
                Toast.makeText(context, startupUnavailable, Toast.LENGTH_LONG).show()
        }
    }
    // The wizard's "Add a TV guide": setup is over, so EPG sources opens — and its Add opens itself.
    val addEpg = tv.own.owntv.mobile.ui.setup.SetupHandoff.addEpg
    LaunchedEffect(addEpg) {
        if (addEpg) navController.navigate(tv.own.owntv.mobile.ui.screens.settings.SettingsLeaf.EPG_SOURCES.route)
    }

    val favorite by tuner.isFavorite.collectAsStateWithLifecycle()
    var windowMenu by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }

    val playlists by shellViewModel.playlists.collectAsStateWithLifecycle()
    val activePlaylistId by shellViewModel.activePlaylistId.collectAsStateWithLifecycle()
    var playlistSheet by remember { mutableStateOf(false) }

    val chrome = remember { ShellChromeState() }
    val homeChrome = remember { HomeChrome() }
    val titleChrome = remember { HomeChrome() }
    val vodChrome = remember { VodChrome() }
    val pageCrumb = remember { mutableStateOf<PageCrumb?>(null) }
    // The header (and Home's picture) follows the page actually drawn, not the one just tapped: a page
    // slow to compose the first time (Settings) left the previous page on screen under its header.
    // The drawn page and the route under it (its crumb's "‹ where from"), taken when it reports in. A page
    // leaving the screen while it still holds the header gives it back to the current one.
    var shown by remember { mutableStateOf<Pair<NavBackStackEntry, String?>?>(null) }
    val reportShown: (NavBackStackEntry, Boolean) -> Unit = { entry, arrived ->
        if (arrived) {
            shown = entry to navController.previousBackStackEntry?.destination?.route?.takeIf { entry == navController.currentBackStackEntry }
        } else if (shown?.first == entry) {
            shown = null
        }
    }
    val shownPage = shown
    val headerEntry = shownPage?.first ?: backStackEntry
    val headerRoute = headerEntry?.destination?.route
    val onHome = headerRoute == MobileDestination.HOME.route
    val onTitlePage = isTitleRoute(headerRoute)
    val navLabels by settings.phoneNavLabels.collectAsStateWithLifecycle(true)
    val hideOnScroll by settings.phoneNavHideOnScroll.collectAsStateWithLifecycle(true)
    chrome.hideBottomOnScroll = hideOnScroll && !useRail
    // Each arrival starts with the header and the bar shown.
    LaunchedEffect(currentRoute) { chrome.reset() }

    // Say once per launch that the day's share of the shared metadata service is gone, rather than
    // letting posters and plots quietly stop appearing. It can happen on any screen, so it belongs
    // here; `remember` (not rememberSaveable) is exactly the once-per-launch scope wanted.
    val metadataBudget: MetadataBudget = koinInject()
    val budgetRefusedAt by metadataBudget.refusedAt.collectAsStateWithLifecycle()
    var budgetNoticeShown by remember { mutableStateOf(false) }
    LaunchedEffect(budgetRefusedAt) {
        if (budgetRefusedAt > 0L && !budgetNoticeShown) {
            budgetNoticeShown = true
            Toast.makeText(
                context,
                tv.own.owntv.mobile.R.string.settings_metadata_limit_reached,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // The startup update check, the television's setting and the television's five-second delay —
    // long enough that the check never competes with the first screen for the network.
    //
    // Where the two apps differ: the television posts a corner toast for every outcome, because a
    // remote has somewhere to point at it. Here nothing is shown unless there is genuinely an update
    // — "checking…" and "you are up to date" are answers to a question nobody asked, and on a phone
    // they would land on top of what the user opened the app to do. A failure is silent for the same
    // reason; Settings → App → Check for updates says it out loud, on request.
    val updateManager: UpdateManager = koinInject()
    val updateCheckOnStart by settings.updateCheckOnStart.collectAsStateWithLifecycle(initialValue = false)
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    var updateChecked by remember { mutableStateOf(false) }
    var updateSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(updateCheckOnStart) {
        if (updateCheckOnStart && !updateChecked) {
            updateChecked = true
            delay(5_000)
            updateManager.check()
        }
    }
    LaunchedEffect(updateState) {
        if (updateState is UpdateManager.State.Available) updateSheetOpen = true
    }
    // Not over the player, and not over setup: both own the whole screen for a reason.
    if (updateSheetOpen && !fullscreen) {
        UpdateSheet(
            onDismiss = {
                updateSheetOpen = false
                // Back to Idle, or the next recomposition reopens the sheet on the same release.
                updateManager.reset()
            },
        )
    }

    // The header: the page names itself; a page pushed on top of another says where it came from
    // in the crumb, and that crumb is the way back. A tab, Home and More are places, not pages, so they
    // carry no crumb; a detail page (a film, a channel) draws its own title and keeps only the crumb.
    val topLevel = headerRoute == MobileDestination.MORE.route ||
        (MobileDestination.rail + MobileDestination.bottomBar).any { it.route == headerRoute }
    // About names itself with the wordmark: its header is the crumb alone.
    val titleRes = if (headerRoute == MobileDestination.HOME.route || headerRoute == MoreLeaf.ABOUT.route) null else pageTitleRes(headerRoute)
    val previousRoute = if (shownPage != null) shownPage.second else navController.previousBackStackEntry?.destination?.route
    // Downloads says where its files are instead of where it came from: its crumb is the storage line.
    // Search's crumb is the query and its count.
    val ownCrumb = topLevel || headerRoute == MobileDestination.DOWNLOADS.route || headerRoute == SEARCH_ROUTE_PATTERN
    val settingsPage = isSettingsPage(headerRoute)
    val backLabel = if (ownCrumb || settingsPage || previousRoute == null) null else stringResource(anyTitleRes(previousRoute))
    // Settings draws "‹ More" above its title and its hint below; a settings page has only its crumb,
    // "‹ Settings · 13 settings".
    val showCrumb = ownCrumb || settingsPage || headerRoute == MobileDestination.SETTINGS.route

    // The playlist pill belongs to the browse places: in full on Live TV, Guide, Movies, Series and Downloads,
    // as the layers icon on Home (its hero needs the width). Settings and More pages leave it out.
    val activePlaylist = playlists.firstOrNull { it.id == activePlaylistId }
    val activeIndex = playlists.indexOf(activePlaylist)
    val clusterPlaylist = if (playlists.isEmpty()) null else when {
        // A title page has Home's layers pill.
        headerRoute == MobileDestination.HOME.route || onTitlePage -> ClusterPlaylist(
            label = stringResource(tv.own.owntv.mobile.R.string.content_playlist_picker_title),
            mark = null,
            switchable = playlists.size > 1,
            iconOnly = true,
        )
        headerRoute in listOf(
            MobileDestination.LIVE.route, MobileDestination.GUIDE.route,
            MobileDestination.MOVIES.route, MobileDestination.SERIES.route,
            MobileDestination.DOWNLOADS.route, SEARCH_ROUTE_PATTERN,
        ) -> ClusterPlaylist(
            label = when {
                playlists.size == 1 -> playlists.first().displayName()
                activePlaylist != null -> activePlaylist.displayName()
                else -> stringResource(tv.own.owntv.mobile.R.string.content_all_playlists)
            },
            // A playlist's own mark only means something next to the others'.
            mark = if (activePlaylist != null && playlists.size > 1) PlaylistMark.of(activePlaylist.displayName(), activeIndex) else null,
            switchable = playlists.size > 1,
            iconOnly = false,
        )
        else -> null
    }
    // VOD's sources are its TVbox configurations, and the VOD page owns that page. While the page
    // is on screen it lends the header the action, in the same layers pill Home uses for playlists.
    val vodSources = vodChrome.openSourceConfig
    val clusterWithSources = if (headerRoute == MobileDestination.VOD.route && vodSources != null) {
        ClusterPlaylist(
            label = stringResource(tv.own.owntv.mobile.R.string.tvbox_config_title),
            mark = null,
            switchable = false,
            iconOnly = true,
        )
    } else {
        clusterPlaylist
    }
    // `activeProfile` is the shell's own, decided above — one profile, and it has passed the lock.
    // The Stage palette decides the theme everywhere else in the shell, so the cast icon follows it too.
    val onSurfaceLight = !LocalStagePalette.current.isLight

    // The status and gesture bars' icons follow the app's own theme, not the phone's night mode (which
    // `enableEdgeToEdge` reads): dark on a Light page, light everywhere else and over the player.
    val lightBars = LocalStagePalette.current.isLight && !fullscreen
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    val dockedMini = showMini && miniStyle == SettingsRepository.MiniPlayerStyle.DOCKED
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier
            .fillMaxSize()
            .stageBackground()
            .nestedScroll(chrome.connection),
    ) {
        // Home's and a title page's picture run under the camera strip to the screen's edge (held
        // sideways the strip is on a side); everything else keeps clear of it.
        if (onHome && !fullscreen) HomeBackdrop(homeChrome)
        if (onTitlePage && !fullscreen) HomeBackdrop(titleChrome)
        // The sides' safe room (a camera strip, a gesture edge held sideways) is the shell's; the player owns
        // every pixel, camera strip included.
        Box(
            if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
        // ≥ 600 dp wide: the floating glass rail on the left, full height, with the header and the page
        // beside it — compact on a phone held sideways, with the mark, Search and the avatar on a tablet.
        val railRoom = if (useRail && !fullscreen) (if (compactRail) 70.dp else 84.dp) else 0.dp
        if (useRail && !fullscreen) {
            StageRail(
                destinations = destinations,
                current = current,
                compact = compactRail,
                profile = loadedProfiles.firstOrNull { it.id == activeId },
                onClick = { navController.onNavClick(it, current, shellViewModel) },
                onSearch = { navController.navigate(SEARCH_ROUTE) { launchSingleTop = true } },
                onAvatar = { navController.navigate(MobileDestination.MORE.route) { launchSingleTop = true } },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        ShellLayout(
            chrome = chrome,
            fullscreen = fullscreen,
            startInset = railRoom,
            header = {
                StagePageHeader(
                    title = titleRes?.let { stringResource(it) },
                    backLabel = backLabel,
                    onBack = { navController.popBackStack() },
                    heading = homeChrome.heading.takeIf { onHome },
                    crumb = pageCrumb.value.takeIf { showCrumb && it?.entryId == headerEntry?.id },
                    backPill = onTitlePage,
                    // Sideways the title is 24 with its crumb beside it; a tablet's is 34.
                    titleSize = if (!useRail) 28f else if (compactRail) 24f else 34f,
                    inlineCrumb = useRail && compactRail,
                ) {
                    // More draws no cluster, nor does About; the other More pages keep only
                    // the avatar.
                    val moreLeaf = MoreLeaf.entries.firstOrNull { it.route == headerRoute }
                    if (headerRoute == MobileDestination.MORE.route || moreLeaf == MoreLeaf.ABOUT) return@StagePageHeader
                    ShellCluster(
                        playlist = clusterWithSources,
                        profile = activeProfile,
                        // The TV Guide has Search among its own tools, so the cluster leaves it out; a
                        // title page has none either.
                        showSearch = headerRoute != SEARCH_ROUTE_PATTERN && headerRoute != MobileDestination.GUIDE.route &&
                            !onTitlePage && moreLeaf == null,
                        onPlaylist = {
                            if (headerRoute == MobileDestination.VOD.route && vodSources != null) {
                                vodSources()
                            } else if (playlists.size > 1) {
                                playlistSheet = true
                            }
                        },
                        onSearch = { navController.navigate(SEARCH_ROUTE) { launchSingleTop = true } },
                        onAvatar = { navController.navigate(MobileDestination.MORE.route) { launchSingleTop = true } },
                        extra = {
                            // Home's Resume pill, while its hero is in view (the scrolled heading has
                            // no room beside it). It gives up width first, so at UI zoom 120 % the avatar
                            // stays on the screen.
                            homeChrome.resume?.takeIf { onHome && homeChrome.heading == null }?.let { resume ->
                                StagePill(
                                    onClick = resume.onClick,
                                    text = resume.name,
                                    icon = MobileIcons.PlayArrow,
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 150.dp),
                                )
                            }
                            // It appears only when there is a receiver to send to; which way round it is
                            // drawn follows the app's own theme, not the phone's night mode.
                            CastRouteButton(light = onSurfaceLight)
                        },
                    )
                }
            },
            bottom = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = maxOf(18.dp, navBottom)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (dockedMini) {
                        // A channel wins when there is one, because the two tuners cannot both be
                        // playing and the live one is what the other stops before it starts.
                        val live = channel
                        MiniPlayer(
                            player = tuner.player,
                            engine = playingEngine,
                            title = live?.name ?: film?.title.orEmpty(),
                            subtitle = if (live != null) nowNext?.now?.title else film?.subtitle,
                            artworkUrl = live?.displayLogoUrl ?: film?.posterUrl,
                            onExpand = { navController.navigate(PLAYER_ROUTE) },
                            onStop = { if (live != null) tuner.stop() else vodTuner.stop() },
                            remote = castEngine,
                            isLive = live != null,
                            programmeStartMs = if (live != null) nowNext?.now?.startMs else null,
                            programmeStopMs = if (live != null) nowNext?.now?.stopMs else null,
                        )
                    }
                    if (!useRail) {
                        StageNavBar(
                            destinations = destinations,
                            current = current,
                            labels = navLabels,
                            onClick = { navController.onNavClick(it, current, shellViewModel) },
                            onLongClick = { shellViewModel.requestScrollToTop(it.route) },
                        )
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalStreamOnScreen provides streamOnScreen,
                        LocalMiniRequested provides miniRequested,
                        LocalHomeChrome provides homeChrome,
                        LocalTitleChrome provides titleChrome,
                        LocalVodChrome provides vodChrome,
                        LocalPageCrumb provides pageCrumb,
                        LocalShownEntry provides reportShown,
                    ) {
                        MobileNavHost(
                            navController = navController,
                            scrollToTop = shellViewModel.scrollToTop,
                        )
                    }
                    // Low over the page, under the mini player: a sync running while the user browses is
                    // news, but it is never what they came to the screen for.
                    if (fullscreen) {
                        // Top, clear of the player's own controls at the bottom, and recordings only.
                        SyncStatusPill(
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding(),
                            recordingsOnly = true,
                        )
                    } else {
                        SyncStatusPill(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                            onOpenDownloads = { navController.navigate(MobileDestination.DOWNLOADS.route) },
                        )
                    }
                    // Over the content rather than beside it, because that is what a floating window is.
                    if (showMini && miniStyle == SettingsRepository.MiniPlayerStyle.FLOATING) {
                        val live = channel
                        FloatingMiniPlayer(
                            player = tuner.player,
                            engine = playingEngine,
                            title = live?.name ?: film?.title.orEmpty(),
                            isLive = live != null,
                            artworkUrl = live?.displayLogoUrl ?: film?.posterUrl,
                            onExpand = { navController.navigate(PLAYER_ROUTE) },
                            onStop = { if (live != null) tuner.stop() else vodTuner.stop() },
                            onMenu = { windowMenu = true },
                        )
                    }
                }
            }
        }
        }
    }

    if (windowMenu) {
        FloatingWindowMenu(
            isFavorite = favorite,
            onToggleFavorite = tuner::toggleFavorite,
            onAudioOnly = { tuner.setAudioOnly(true) },
            onSleepTimer = { sleepSheet = true },
            onExpand = { navController.navigate(PLAYER_ROUTE) },
            onStop = { if (channel != null) tuner.stop() else vodTuner.stop() },
            onDismiss = { windowMenu = false },
        )
    }
    if (sleepSheet) {
        SleepTimerSheet(
            programmeEndMs = nowNext?.now?.stopMs,
            onDismiss = { sleepSheet = false },
        )
    }
    if (playlistSheet) {
        PlaylistSheet(
            playlists = playlists,
            // A stored default the profile no longer has is All playlists, exactly as the browse screens
            // already read it — so the tick is never on a playlist that is not being shown.
            activeId = activePlaylist?.id ?: -1L,
            names = playlists.map { it.displayName() },
            onSelect = shellViewModel::selectPlaylist,
            onDismiss = { playlistSheet = false },
        )
    }

    // Programme reminders: when one comes up, ask to switch, or switch — over whatever is on screen,
    // the player included. Notify only is a notification (ReminderNotifier), so nothing is drawn here.
    val reminders: tv.own.owntv.core.reminder.ReminderManager = koinInject()
    val reminderMode by settings.reminderMode.collectAsStateWithLifecycle(SettingsRepository.ReminderMode.ASK)
    // One on a channel filed under a locked category waits: it neither asks nor switches until the PIN
    // opens the category, so a reminder cannot show or tune what the lock keeps out of sight.
    val lockStore: tv.own.owntv.core.customize.CustomizationStore = koinInject()
    val reminderChannels: tv.own.owntv.core.database.dao.ChannelDao = koinInject()
    val dueReminder = remember(activeId, reminders, lockStore, reminderChannels) {
        reminders.due.firstUnsealed(activeId, lockStore.sealedCategoryIds(activeId, tv.own.owntv.core.model.MediaType.LIVE)) {
            reminderChannels.getById(it)?.categoryId
        }
    }.collectAsStateWithLifecycle(null).value
    val reminderScope = androidx.compose.runtime.rememberCoroutineScope()
    fun answer(r: tv.own.owntv.core.database.entity.ReminderEntity, watch: Boolean) {
        reminderScope.launch {
            reminders.dismiss(r.id)
            tv.own.owntv.mobile.reminder.ReminderNotifier.cancel(context, r.id)
        }
        if (watch) {
            tuner.tune(r.channelId)
            navController.navigate(PLAYER_ROUTE) { launchSingleTop = true }
        }
    }
    LaunchedEffect(dueReminder?.id, reminderMode) {
        val r = dueReminder ?: return@LaunchedEffect
        if (reminderMode == SettingsRepository.ReminderMode.SWITCH) answer(r, watch = true)
    }
    if (dueReminder != null && reminderMode == SettingsRepository.ReminderMode.ASK) {
        tv.own.owntv.mobile.reminder.ReminderPrompt(
            reminder = dueReminder,
            onWatch = { answer(dueReminder, watch = true) },
            onDismiss = { answer(dueReminder, watch = false) },
        )
    }
}

/**
 * Header slot, content, bottom chrome. The header and the bottom chrome slide by [ShellChromeState]'s
 * offsets and the content's edges follow them, so nothing is ever left under either. The status bar's
 * room stays the page's: a sliding header is clipped below it rather than passing under the clock.
 */
@Composable
private fun ShellLayout(
    chrome: ShellChromeState,
    fullscreen: Boolean,
    startInset: Dp,
    header: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (fullscreen) {
        content()
        return
    }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Layout(
        contents = listOf(
            {
                Box(
                    Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                            chrome.headerHeight = p.height.toFloat()
                            val shift = chrome.headerOffset.roundToInt().coerceIn(-p.height, 0)
                            layout(p.width, p.height + shift) { p.place(0, shift) }
                        },
                ) { header() }
            },
            // Clipped, as the page panel used to clip it: a scrolled list must not draw up into the header.
            { Box(Modifier.clipToBounds()) { content() } },
            {
                Box(
                    Modifier.layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minHeight = 0))
                        chrome.bottomHeight = p.height.toFloat()
                        layout(p.width, p.height) { p.place(0, 0) }
                    },
                ) { bottom() }
            },
        ),
    ) { (headerM, contentM, bottomM), constraints ->
        val inset = startInset.roundToPx()
        val width = (constraints.maxWidth - inset).coerceAtLeast(0)
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width)
        val top = topInset.roundToPx()
        val headerP = headerM.first().measure(loose)
        val bottomP = bottomM.first().measure(loose)
        val shown = (bottomP.height - chrome.bottomOffset.roundToInt()).coerceIn(0, bottomP.height)
        val contentTop = top + headerP.height
        val contentH = (constraints.maxHeight - contentTop - shown).coerceAtLeast(0)
        val contentP = contentM.first().measure(Constraints.fixed(width, contentH))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Relative: in a right-to-left language the rail is on the right, and the page must clear it
            // there — placed from the left it lay over the rail and took its taps.
            contentP.placeRelative(inset, contentTop)
            headerP.placeRelative(inset, top)
            bottomP.placeRelative(inset, constraints.maxHeight - shown)
        }
    }
}

/** What a route calls itself in the header, or null when the page draws its own title. */
private fun pageTitleRes(route: String?): Int? = when (route) {
    SEARCH_ROUTE_PATTERN -> tv.own.owntv.mobile.R.string.search_title
    else -> settingsPageTitleRes(route)
        ?: morePageTitleRes(route)
        ?: MobileDestination.entries.firstOrNull { it.route == route }?.labelRes
}

/** What a page is called in the crumb of the page opened from it: its title, or the place it sits under. */
private fun anyTitleRes(route: String): Int =
    pageTitleRes(route)
        ?: MobileDestination.entries.firstOrNull { route.startsWith("${it.route}/") }?.labelRes
        ?: MobileDestination.HOME.labelRes

/**
 * A playlist's name, or the name it would have been given had one been typed.
 *
 * The name field is optional and older mobile builds stored a blank one rather than filling it in,
 * so a playlist added before that was fixed has no name at all. Falling back at the point of display
 * repairs those rows without a migration, and matches what the setup flow now writes for a new one.
 */
@Composable
internal fun SourceEntity.displayName(): String = name.ifBlank {
    stringResource(
        when (type) {
            SourceType.XTREAM -> tv.own.owntv.mobile.R.string.setup_default_iptv
            SourceType.STALKER -> tv.own.owntv.mobile.R.string.setup_default_portal
            else -> tv.own.owntv.mobile.R.string.setup_name_default_playlist
        },
    )
}

/** Tapping the tab you are already on sends its list back to the top, as does a long press. */
private fun NavHostController.onNavClick(
    destination: MobileDestination,
    current: MobileDestination?,
    shell: ShellViewModel,
) {
    when {
        // A film or a channel opened under this tab: back to the tab's own list first (only the list
        // itself scrolls to the top).
        // A film lit under Library but opened from Search is not on Library's stack: go to Library then.
        destination == current && currentDestination?.route != destination.route ->
            if (!popBackStack(destination.route, inclusive = false)) navigateToTab(destination)
        destination == current -> shell.requestScrollToTop(destination.route)
        // A More or Settings page pushed on top of this tab: back down to the tab itself. Restoring the
        // tab's saved stack instead restored the very page on screen, so the tap did nothing.
        current == null && popBackStack(destination.route, inclusive = false) -> Unit
        else -> navigateToTab(destination)
    }
}

/**
 * Switch tabs the way a bottom bar is supposed to: one entry per tab on the back stack, each tab's
 * own scroll position and state kept, and back from any tab landing on the start destination rather
 * than walking every tab you visited.
 */
fun NavHostController.navigateToTab(destination: MobileDestination) {
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
