package tv.own.owntv.mobile.ui.screens.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.ui.shell.LocalVodChrome

/**
 * The on-demand tab: TVbox sources (plan §6 rows 2-3).
 *
 * One destination, an internal page state machine — no new top-level routes. Pages: config (import
 * and switch sources), home (sites / categories / grid / search), detail (lines, episodes, play).
 * Playing hands back through [onPlay], which the nav host wires to `PLAYER_ROUTE` — after
 * `playTvbox` has already started the stream (order matters: the player screen renders the tuner's
 * state, navigating first would show an empty player).
 */
@Composable
fun VodScreen(
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configVm: TvboxConfigViewModel = koinViewModel()
    val homeVm: TvboxHomeViewModel = koinViewModel()
    val detailVm: TvboxDetailViewModel = koinViewModel()

    val configState by configVm.state.collectAsStateWithLifecycle()
    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val detailState by detailVm.state.collectAsStateWithLifecycle()

    detailVm.onPlayStarted = onPlay

    var page by remember { mutableStateOf(VodPage.HOME) }
    // (siteKey, vodId, typeId, typeName): the list layer owns the lock-key identity (plan §5.5.3).
    var pendingDetail by remember { mutableStateOf<List<String>?>(null) }

    // The header's source pill is this page's configuration picker — the VOD twin of the playlist
    // pill on Home and Live TV. The action is lent while the screen is on and taken back with it,
    // so the header never offers a button that would open nothing.
    var sourceSheet by remember { mutableStateOf(false) }
    val vodChrome = LocalVodChrome.current
    val openSourceConfig = remember { { sourceSheet = true } }
    DisposableEffect(vodChrome, openSourceConfig) {
        vodChrome?.openSourceConfig = openSourceConfig
        onDispose { if (vodChrome?.openSourceConfig === openSourceConfig) vodChrome.openSourceConfig = null }
    }

    when (page) {
        VodPage.CONFIG -> TvboxConfigPage(
            state = configState,
            onImportUrl = configVm::importFromUrl,
            onImportText = configVm::importFromText,
            onSwitch = configVm::switchConfig,
            onDelete = configVm::deleteConfig,
            onBack = { page = VodPage.HOME },
            onConsumeBanner = configVm::consumeBanner,
        )

        VodPage.HOME -> TvboxHomePage(
            configState = configState,
            state = homeState,
            onOpenConfig = { page = VodPage.CONFIG },
            onOpenSite = homeVm::openSite,
            onSelectCategory = homeVm::selectCategory,
            onToggleLock = homeVm::toggleLock,
            onConsumeLockEvent = homeVm::consumeLockEvent,
            onUnlockCategory = homeVm::unlockCategory,
            onSetFilter = homeVm::setFilter,
            onLoadMore = homeVm::loadMore,
            onSearch = homeVm::search,
            onClearSearch = homeVm::clearSearch,
            onOpenDetail = { siteKey, vodId, typeId, typeName ->
                pendingDetail = listOf(siteKey, vodId, typeId, typeName)
                detailVm.open(siteKey, vodId, typeId, typeName)
                page = VodPage.DETAIL
            },
            categoryContext = homeVm.currentCategoryContext,
        )

        VodPage.DETAIL -> {
            val target = pendingDetail
            if (target == null) {
                page = VodPage.HOME
            } else {
                TvboxDetailPage(
                    state = detailState,
                    onBack = { page = VodPage.HOME },
                    onSelectLine = detailVm::selectLine,
                    onPlay = detailVm::play,
                    onToggleFavorite = detailVm::toggleFavorite,
                    onUnlockPin = detailVm::unlockWithPin,
                    onUnlocked = detailVm::markUnlocked,
                )
            }
        }
    }

    // Only the imported configurations: switching is the whole of this button's job, so importing
    // and deleting stay on the Sources page the VOD tile opens.
    if (sourceSheet) {
        TvboxSourcePickerSheet(
            state = configState,
            onSwitch = configVm::switchConfig,
            onDismiss = { sourceSheet = false },
        )
    }
}

private enum class VodPage { HOME, CONFIG, DETAIL }
