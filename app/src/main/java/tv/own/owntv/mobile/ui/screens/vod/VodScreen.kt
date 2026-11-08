package tv.own.owntv.mobile.ui.screens.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

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
    var pendingDetail by remember { mutableStateOf<Pair<String, String>?>(null) }

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
            onSetFilter = homeVm::setFilter,
            onLoadMore = homeVm::loadMore,
            onSearch = homeVm::search,
            onClearSearch = homeVm::clearSearch,
            onOpenDetail = { siteKey, vodId ->
                pendingDetail = siteKey to vodId
                detailVm.open(siteKey, vodId)
                page = VodPage.DETAIL
            },
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
}

private enum class VodPage { HOME, CONFIG, DETAIL }
