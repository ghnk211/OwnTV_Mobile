package tv.own.owntv.mobile.ui.nav

import android.net.Uri
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.compose.animation.AnimatedContentScope
import org.koin.compose.koinInject
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.shell.ReportShownPage
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.SharedFlow
import tv.own.owntv.mobile.ui.screens.MoreLeaf
import tv.own.owntv.mobile.ui.screens.MoreLeafPage
import tv.own.owntv.mobile.ui.screens.MoreScreen
import tv.own.owntv.mobile.ui.player.PlayerScreen
import tv.own.owntv.mobile.ui.screens.downloads.DownloadsScreen
import tv.own.owntv.mobile.ui.screens.guide.GuideScreen
import tv.own.owntv.mobile.ui.screens.home.HomeScreen
import tv.own.owntv.mobile.ui.screens.library.DetailScreen
import tv.own.owntv.mobile.ui.screens.library.LibraryScreen
import tv.own.owntv.mobile.ui.screens.library.LibraryTab
import tv.own.owntv.mobile.ui.screens.live.LiveScreen
import tv.own.owntv.mobile.ui.screens.search.SearchScreen
import tv.own.owntv.mobile.ui.screens.vod.VodScreen
import tv.own.owntv.mobile.ui.screens.settings.SettingsGroup
import tv.own.owntv.mobile.ui.screens.settings.SettingsGroupScreen
import tv.own.owntv.mobile.ui.screens.settings.LocalSettingsNav
import tv.own.owntv.mobile.ui.screens.settings.SettingsLeaf
import tv.own.owntv.mobile.ui.screens.settings.SettingsLeafPage
import tv.own.owntv.mobile.ui.screens.settings.SettingsScreen
import tv.own.owntv.mobile.ui.setup.SetupEntry
import tv.own.owntv.mobile.ui.setup.SetupFlow
import tv.own.owntv.mobile.ui.shell.LocalMiniRequested
import tv.own.owntv.mobile.ui.shell.navigateToTab
import tv.own.owntv.mobile.ui.theme.LocalMobileMotion

/**
 * Every route in the app.
 *
 * A destination is a route rather than an expanding block so that the system back gesture is what
 * closes it, and so each keeps its own scroll state.
 *
 * The `when` below is exhaustive over [MobileDestination] and deliberately has no `else`: a
 * destination added without a screen is then a compile error rather than a blank page at runtime.
 * VOD is the one placeholder: its screen says the page is still being built.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MobileNavHost(
    navController: NavHostController,
    scrollToTop: SharedFlow<String>,
    modifier: Modifier = Modifier,
) {
    // One layout around the whole graph: it is what lets a poster tile on one screen and the header
    // image on the next be understood as the same picture rather than two pictures crossfading.
    val tuner: LiveTuner = koinInject()
    // A channel opened from Home, Favourites or History plays full screen, as on the television.
    val playChannel = { channelId: Long -> tuner.tune(channelId); navController.navigate(PLAYER_ROUTE) }
    // The Settings PIN asks once per visit: a route outside the guarded pages ends the visit.
    // A relock stopped what the player showed: its page closes rather than staying up empty.
    val sealedHost: tv.own.owntv.mobile.ui.categorylock.SealedPlaybackHost = koinInject()
    androidx.compose.runtime.LaunchedEffect(navController, sealedHost) {
        sealedHost.stopped.collect {
            if (navController.currentDestination?.route == PLAYER_ROUTE) navController.popBackStack()
        }
    }
    val pinSession: tv.own.owntv.mobile.ui.categorylock.SettingsPinSession = koinInject()
    androidx.compose.runtime.LaunchedEffect(navController, pinSession) {
        navController.currentBackStackEntryFlow.collect { entry ->
            if (!isPinGuardedRoute(entry.destination.route)) pinSession.end()
        }
    }
    // Cancel on the PIN: back where the user came from, or Home when Settings was opened as a tab.
    val leaveGuarded: () -> Unit = { if (!navController.popBackStack()) navController.navigateToTab(MobileDestination.HOME) }
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalSharedTransition provides this) {
    // Navigation's own default is a 700 ms crossfade that ignores the reduce-motion setting. The
    // app's effects spring is quicker, and it is genuinely nothing at all when animations are off.
    val fade = LocalMobileMotion.current.fast<Float>()
    NavHost(
        navController = navController,
        startDestination = MobileDestination.HOME.route,
        enterTransition = { fadeIn(fade) },
        // The page leaving goes at once: the shell's header is already the next page's, and a page fading
        // out under a header of another height was seen moving (More → Settings, and back).
        exitTransition = { fadeOut(snap()) },
    ) {
        MobileDestination.entries.forEach { destination ->
            page(destination.route) {
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                when (destination) {
                    MobileDestination.MORE -> MoreScreen(
                        scrollToTop = scrollToTop,
                        // A plain push, NOT `navigateToTab`. Downloads and Settings are reached only
                        // from here, and switching to them as tabs popped More off the back stack —
                        // so Back out of Settings landed on Home instead of on the hub it was opened
                        // from. Pushed, Back returns to More like every other row here.
                        onNavigate = {
                            navController.navigate(it.route) { launchSingleTop = true }
                        },
                        onOpenLeaf = { navController.navigate(it.route) { launchSingleTop = true } },
                    )
                    MobileDestination.HOME -> HomeScreen(
                        scrollToTop = scrollToTop,
                        onOpenChannel = playChannel,
                        onOpenMovie = { id ->
                            navController.navigate(detailRoute(destination.route, LibraryTab.MOVIES, id))
                        },
                        onOpenSeries = { id ->
                            navController.navigate(detailRoute(destination.route, LibraryTab.SERIES, id))
                        },
                        onPlayerOpened = { navController.navigate(PLAYER_ROUTE) },
                        onOpenSearch = { query -> navController.navigate(searchRoute(query)) },
                    )
                    MobileDestination.GUIDE -> GuideScreen(
                        scrollToTop = scrollToTop,
                        onOpenPlayer = { navController.navigate(PLAYER_ROUTE) },
                        onAddEpg = { navController.navigate(SettingsLeaf.EPG_SOURCES.route) },
                    )
                    MobileDestination.LIVE -> LiveScreen(
                        scrollToTop = scrollToTop,
                        onOpenPlayer = { navController.navigate(PLAYER_ROUTE) },
                        // Nothing to watch: the same Add a source step Settings' own row opens.
                        onOpenSetup = { navController.navigate(setupRoute(SetupEntry.ADD_CONTENT)) },
                    )
                    MobileDestination.DOWNLOADS -> DownloadsScreen(
                        onPlayerOpened = { navController.navigate(PLAYER_ROUTE) },
                        onOpenGuide = { navController.navigateToTab(MobileDestination.GUIDE) },
                    )
                    MobileDestination.MOVIES, MobileDestination.SERIES ->
                        LibraryScreen(
                            scrollToTop = scrollToTop,
                            route = destination.route,
                            fixedTab = destination.fixedTab(),
                            onOpenItem = { tab, id ->
                                navController.navigate(detailRoute(destination.route, tab, id))
                            },
                            onPlay = { navController.navigate(PLAYER_ROUTE) },
                        )
                    MobileDestination.VOD -> VodScreen(
                        onPlay = { navController.navigate(PLAYER_ROUTE) },
                    )
                    MobileDestination.SETTINGS -> tv.own.owntv.mobile.ui.categorylock.SettingsPinGate(leaveGuarded) {
                        SettingsScreen(onOpenRoute = { navController.navigate(it) })
                    }
                }
                }
            }
        }
        // A film or a show sits under the tab it was opened from, so the bar keeps that tab selected.
        LIBRARY_PARENTS.forEach { parent ->
            page(
                route = "${parent.route}/{$ARG_TAB}/{$ARG_ITEM_ID}",
                arguments = listOf(
                    navArgument(ARG_TAB) { type = NavType.StringType },
                    navArgument(ARG_ITEM_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                val tab = entry.arguments?.getString(ARG_TAB) ?: return@page
                val itemId = entry.arguments?.getLong(ARG_ITEM_ID) ?: return@page
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    DetailScreen(
                        tab = LibraryTab.valueOf(tab),
                        itemId = itemId,
                        onPlay = { navController.navigate(PLAYER_ROUTE) },
                        // "More in …" opens the next title under the same place.
                        onOpenItem = { nextTab, id -> navController.navigate(detailRoute(parent.route, nextTab, id)) },
                        onOpenSearch = { query -> navController.navigate(searchRoute(query)) },
                    )
                }
            }
        }
        page(
            route = SEARCH_ROUTE_PATTERN,
            arguments = listOf(navArgument(ARG_QUERY) { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            // Live TV is the tab a channel from Search opens under. Not a plain navigateToTab: that
            // saves the Live tab's stack and restores it, so a Search opened from Live TV (or saved
            // there earlier) came straight back on top and the tap seemed to do nothing. Opened from
            // Live TV, close Search; otherwise drop Live TV's saved pages before switching to it.
            val openLiveTab: () -> Unit = {
                if (!navController.popBackStack(MobileDestination.LIVE.route, inclusive = false)) {
                    navController.clearBackStack(MobileDestination.LIVE.route)
                    navController.navigateToTab(MobileDestination.LIVE)
                }
            }
            val startupLive: tv.own.owntv.mobile.ui.shell.StartupLiveSelection = koinInject()
            SearchScreen(
                initialQuery = entry.arguments?.getString(ARG_QUERY).orEmpty(),
                // Search goes to the channel: Live TV first, so the tab's stack keeps the page the
                // player will return to, then the player on top of it. There is no preview to play
                // it in any more — Live TV lists categories, not channels — so this is always the
                // full screen, and Back lands on the category the channel lives in.
                onOpenChannel = { channelId ->
                    startupLive.requestReveal(channelId) // taken by the Live TV tab, once
                    openLiveTab()
                    playChannel(channelId)
                },
                onOpenMovie = { id ->
                    navController.navigate(detailRoute(LibraryTab.MOVIES.parent.route, LibraryTab.MOVIES, id))
                },
                onOpenSeries = { id ->
                    navController.navigate(detailRoute(LibraryTab.SERIES.parent.route, LibraryTab.SERIES, id))
                },
            )
        }
        // The eight group pages and the full pages under them. Each is a route, so the
        // system back gesture is what closes it; a row that opens a page navigates through LocalSettingsNav.
        val openRoute: (String) -> Unit = { navController.navigate(it) { launchSingleTop = true } }
        SettingsGroup.entries.forEach { group ->
            page(group.route) {
                tv.own.owntv.mobile.ui.categorylock.SettingsPinGate(leaveGuarded) {
                    CompositionLocalProvider(LocalSettingsNav provides openRoute) { SettingsGroupScreen(group) }
                }
            }
        }
        SettingsLeaf.entries.forEach { leaf ->
            page(leaf.route) {
                tv.own.owntv.mobile.ui.categorylock.SettingsPinGate(leaveGuarded) {
                    CompositionLocalProvider(LocalSettingsNav provides openRoute) {
                        SettingsLeafPage(leaf, onSetup = { navController.navigate(setupRoute(it)) })
                    }
                }
            }
        }
        // The More pages. Separate routes from the settings ones on purpose: what left Settings has
        // left, so nothing under `settings/` can reach them and the settings search cannot see them.
        MoreLeaf.entries.forEach { leaf ->
            page(leaf.route) {
                // Backup & Restore and Local sync can restore or copy settings: the Settings PIN guards them too.
                tv.own.owntv.mobile.ui.categorylock.SettingsPinGate(leaveGuarded, enabled = leaf in PIN_GUARDED_MORE) {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        MoreLeafPage(
                            leaf = leaf,
                            // A channel or a title opened from Favourites lands on the screen it has
                            // under its own tab — the same convention the Guide uses for its channels.
                            onOpenChannel = { channelId, _ -> playChannel(channelId) },
                            onOpenItem = { tab, id ->
                                navController.navigate(detailRoute(tab.parent.route, tab, id))
                            },
                            onOpenPlayer = { navController.navigate(PLAYER_ROUTE) },
                            onOpenLanguage = { navController.navigate(tv.own.owntv.mobile.ui.screens.settings.SettingsLeaf.LANGUAGE.route) },
                        )
                    }
                }
            }
        }
        page(PLAYER_ROUTE) {
            // `onExit` is only ever the mini-player button, the swipe down and Sound only — the three
            // ways of saying "keep it playing". Back has its own path and stops the stream, so it
            // never reaches here and never raises the flag.
            val miniRequested = LocalMiniRequested.current
            PlayerScreen(
                onExit = {
                    miniRequested.value = true
                    navController.popBackStack()
                },
                // The long-press panel's rows open the real settings pages, pushed over the
                // player so Back returns to the picture.
                onOpenRoute = { navController.navigate(it) { launchSingleTop = true } },
            )
        }
        page(
            route = SETUP_ROUTE_PATTERN,
            arguments = listOf(navArgument(ARG_ENTRY) { type = NavType.StringType; defaultValue = SetupEntry.ADD_CONTENT.name }),
        ) { entry ->
            SetupFlow(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                entry = runCatching { SetupEntry.valueOf(entry.arguments?.getString(ARG_ENTRY).orEmpty()) }.getOrDefault(SetupEntry.ADD_CONTENT),
            )
        }
    }
        }
    }
}

/** Every route, reporting to the shell when its page is drawn (the header follows it). */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route = route, arguments = arguments) { entry ->
    ReportShownPage(entry)
    content(entry)
}

/** The tabs a film or a show can be opened from, and therefore returned to. */
/** The More pages that can restore or copy settings, guarded by the Settings PIN like Settings itself. */
private val PIN_GUARDED_MORE = setOf(MoreLeaf.BACKUP, MoreLeaf.LOCAL_SYNC)

/** Settings, every page under it, and [PIN_GUARDED_MORE]: one visit of the Settings PIN. */
private fun isPinGuardedRoute(route: String?): Boolean =
    route != null && (
        route == MobileDestination.SETTINGS.route || route.startsWith("${MobileDestination.SETTINGS.route}/") ||
            PIN_GUARDED_MORE.any { it.route == route }
        )

private val LIBRARY_PARENTS = listOf(
    MobileDestination.MOVIES,
    MobileDestination.SERIES,
    MobileDestination.HOME,
)

/** Movies and Series are one screen, each locked to its half; Library switches between them. */
private fun MobileDestination.fixedTab(): LibraryTab? = when (this) {
    MobileDestination.MOVIES -> LibraryTab.MOVIES
    MobileDestination.SERIES -> LibraryTab.SERIES
    else -> null
}

/** Where a film or a show opened from outside its own place lives: Movies or Series. */
private val LibraryTab.parent: MobileDestination
    get() = if (this == LibraryTab.MOVIES) MobileDestination.MOVIES else MobileDestination.SERIES

private fun detailRoute(parentRoute: String, tab: LibraryTab, itemId: Long) =
    "$parentRoute/${tab.name}/$itemId"

/** A film's or a show's own page — the shell gives it the title-page header. */
fun isTitleRoute(route: String?): Boolean = route?.endsWith("/{$ARG_TAB}/{$ARG_ITEM_ID}") == true

private const val ARG_TAB = "tab"
private const val ARG_ITEM_ID = "itemId"

/**
 * The full screen player. A route of its own, outside the tabs, because it is the one destination
 * that hides the shell — the bars would be covering the picture, and the back stack is what returns
 * the user to whatever they were looking at.
 */
const val PLAYER_ROUTE = "player"

/**
 * Adding a playlist, or restoring a backup. Like the player it hides the shell: the bars would offer
 * tabs that lead to empty screens, and on a first run there is nothing behind it to go back to.
 */
const val SETUP_ROUTE = "setup"
private const val ARG_ENTRY = "entry"
const val SETUP_ROUTE_PATTERN = "$SETUP_ROUTE?$ARG_ENTRY={$ARG_ENTRY}"

/** The setup flow entered at [entry] (Settings' Add a source). */
fun setupRoute(entry: SetupEntry): String = "$SETUP_ROUTE?$ARG_ENTRY=${entry.name}"

/**
 * One field over everything. A route rather than a tab, because search is reached from the top bar of
 * whichever screen the user is on, and back should return them to exactly that screen.
 */
private const val ARG_QUERY = "q"

const val SEARCH_ROUTE = "search"

/**
 * The same screen with a title already typed into it — Home's trending hero offers "All versions",
 * which is a search for that title. The argument is optional, so plain [SEARCH_ROUTE] still matches.
 */
const val SEARCH_ROUTE_PATTERN = "search?$ARG_QUERY={$ARG_QUERY}"

fun searchRoute(query: String): String = "search?$ARG_QUERY=${Uri.encode(query)}"
