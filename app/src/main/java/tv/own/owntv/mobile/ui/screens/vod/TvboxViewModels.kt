package tv.own.owntv.mobile.ui.screens.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryLock
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryPolicy
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.data.prefs.TvboxPrefs
import tv.own.owntv.mobile.tvbox.di.TvboxRuntime
import tv.own.owntv.mobile.tvbox.engine.FilterDef
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.PlayLine
import tv.own.owntv.mobile.tvbox.engine.PlayUrlCodec
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.play.ResolvedPlay
import tv.own.owntv.mobile.tvbox.play.TvboxCategoryKey
import tv.own.owntv.mobile.tvbox.play.TvboxPlayResolver
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.SourceSearchResult
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxStore
import tv.own.owntv.mobile.tvbox.util.TextUtil
import tv.own.owntv.mobile.ui.screens.library.VodTuner

/**
 * The three TVbox view models. Business logic stays thin: the ported repositories own the parsing
 * chains, these only orchestrate calls, hold UI state, and apply the kids/lock filters (plan §5.5.6
 * — list layer) before anything reaches the screen.
 */

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

data class TvboxConfigUiState(
    val configs: List<TvboxConfigRepository.ConfigMeta> = emptyList(),
    val activeId: Long = -1L,
    val importing: Boolean = false,
    /** One-shot result banner; the screen renders it with localized copy. */
    val banner: TvboxBanner? = null,
    val spiderDownloading: Boolean = false,
)

sealed interface TvboxBanner {
    data class Imported(val siteCount: Int, val spiderDownloading: Boolean) : TvboxBanner
    /** Business-layer message (import/parse diagnostics), passed through as data. */
    data class Failed(val message: String) : TvboxBanner
}

class TvboxConfigViewModel(
    private val configRepository: TvboxConfigRepository,
    private val runtime: TvboxRuntime,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TvboxConfigUiState())
    val state: StateFlow<TvboxConfigUiState> = _state.asStateFlow()

    /** The active config's site list, observed by the home VM through [SourceRepository.sites]. */
    val sites get() = configRepository.sites

    init {
        viewModelScope.launch {
            configRepository.configs.collect { list ->
                _state.value = _state.value.copy(configs = list, activeId = configRepository.activeId())
            }
        }
        viewModelScope.launch {
            configRepository.spiderPrefetching.collect { _state.value = _state.value.copy(spiderDownloading = it) }
        }
        viewModelScope.launch { runtime.restore() }
    }

    fun importFromUrl(url: String) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, banner = null)
            val r = configRepository.importFromUrl(url)
            _state.value = _state.value.copy(importing = false, banner = when (r) {
                is TvboxConfigRepository.ImportResult.Success ->
                    TvboxBanner.Imported(r.siteCount, r.spiderDownloading)
                is TvboxConfigRepository.ImportResult.Failure ->
                    TvboxBanner.Failed(r.message)
            })
        }
    }

    fun importFromText(text: String, fileName: String?) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, banner = null)
            val r = configRepository.importFromText(text, fileName)
            _state.value = _state.value.copy(importing = false, banner = when (r) {
                is TvboxConfigRepository.ImportResult.Success ->
                    TvboxBanner.Imported(r.siteCount, r.spiderDownloading)
                is TvboxConfigRepository.ImportResult.Failure ->
                    TvboxBanner.Failed(r.message)
            })
        }
    }

    fun switchConfig(id: Long) {
        viewModelScope.launch { configRepository.switchConfig(id) }
    }

    fun deleteConfig(id: Long) {
        viewModelScope.launch { configRepository.deleteConfig(id) }
    }

    fun renameConfig(id: Long, name: String) {
        viewModelScope.launch { configRepository.renameConfig(id, name) }
    }

    fun consumeBanner() {
        _state.value = _state.value.copy(banner = null)
    }
}

// ---------------------------------------------------------------------------
// Home (sites / categories / list / search)
// ---------------------------------------------------------------------------

/** Home errors as data; the screen renders localized copy. */
enum class TvboxLoadError { LOAD_FAILED, DETAIL_FAILED }

data class TvboxHomeUiState(
    /** 站点列表（儿童档案已过滤；聚合搜索也只用这里剩下的源）。 */
    val sites: List<SiteConfig> = emptyList(),
    val currentSite: SiteConfig? = null,
    val categories: List<tv.own.owntv.mobile.tvbox.engine.ClassInfo> = emptyList(),
    /** 当前分类行的锁定 key（上锁图标显示用；行不隐藏，点击弹 PIN，方案 §5.5.4）。 */
    val sealedKeys: Set<String> = emptySet(),
    val filters: List<FilterDef> = emptyList(),
    val selectedFilters: Map<String, String> = emptyMap(),
    val movies: List<Movie> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: TvboxLoadError? = null,
    /** 搜索面板状态（聚合搜索：先到先显示）。 */
    val searchKeyword: String = "",
    val searchResults: List<SourceSearchResult> = emptyList(),
    val searching: Boolean = false,
    /** One-shot lock/unlock feedback; the screen renders localized copy. */
    val lockEvent: TvboxLockEvent? = null,
)

/** 分类锁操作结果（方案 §5.5.7：无 PIN 引导 / 上锁成功 / 解锁成功）。 */
enum class TvboxLockEvent { LOCKED, UNLOCKED, NO_PIN }

class TvboxHomeViewModel(
    private val configRepository: TvboxConfigRepository,
    private val sourceRepository: SourceRepository,
    private val policy: TvboxCategoryPolicy,
    private val lock: TvboxCategoryLock,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
) : ViewModel() {

    private val _state = MutableStateFlow(TvboxHomeUiState())
    val state: StateFlow<TvboxHomeUiState> = _state.asStateFlow()

    /** 当前分类（tid/name），详情/播放要带去当锁与儿童判定的身份（方案 §5.5.3 key 口径）。 */
    private var currentCategoryName: String = ""
    private var currentCategoryTid: String = ""

    /** 供 UI 把海报的来源分类身份带给详情（typeId 优先的锁 key 必须在列表层确定）。 */
    val currentCategoryContext: Pair<String, String>
        get() = currentCategoryTid to currentCategoryName

    init {
        // 配置/站点变化 → 重算站点列表与首页绑定源
        viewModelScope.launch {
            configRepository.homeSiteVersion.collect {
                refreshSites()
            }
        }
        viewModelScope.launch {
            settings.activeProfileId.collect { refreshSites() }
        }
        refreshSites()
    }

    private suspend fun currentProfile(): ProfileEntity? {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return null
        return profileDao.getById(pid)
    }

    private fun refreshSites() {
        viewModelScope.launch {
            val profile = currentProfile()
            val visible = policy.visibleSites(profile, configRepository.sites.value.filter { it.isSupported })
            _state.value = _state.value.copy(sites = visible)
            val saved = configRepository.currentHomeSiteKey()
            val target = visible.firstOrNull { it.key == saved }
                ?: configRepository.resolveHomeSite()?.let { home -> visible.firstOrNull { it.key == home.key } }
            if (target != null && target.key != _state.value.currentSite?.key) {
                openSite(target)
            } else if (target == null) {
                _state.value = _state.value.copy(currentSite = null, categories = emptyList(), movies = emptyList())
            }
        }
    }

    fun openSite(site: SiteConfig) {
        viewModelScope.launch {
            _state.value = _state.value.copy(currentSite = site, loading = true, error = null, movies = emptyList(), page = 1, selectedFilters = emptyMap())
            val profile = currentProfile()
            val result = sourceRepository.homeContent(site, filter = true)
            val classes = policy.visibleCategories(profile, site, result?.classes.orEmpty())
            // 分类为空时兜底：homeVideoContent（纯推荐源）
            val movies = filterMovies(profile, site, result?.list.orEmpty())
            val lockPid = settings.activeProfileId.first()
            val sealed = lock.sealedKeys(lockPid, site.key, classes)
            tv.own.owntv.mobile.tvbox.util.TbLog.tag("TvboxLock").i(
                "openSite sealedKeys：pid=%d site=%s classes=%d sealed=%s",
                lockPid, site.key, classes.size, sealed,
            )
            if (classes.isEmpty()) {
                _state.value = _state.value.copy(loading = false, categories = emptyList(), sealedKeys = emptySet(), filters = emptyList(), movies = movies, pageCount = result?.pagecount ?: 1)
                return@launch
            }
            _state.value = _state.value.copy(
                loading = false,
                categories = classes,
                sealedKeys = sealed,
                filters = result?.filters?.get(classes.first().typeId).orEmpty(),
            )
            loadCategory(classes.first().typeId, classes.first().typeName, replace = true)
        }
    }

    fun selectCategory(tid: String, typeName: String) {
        loadCategory(tid, typeName, replace = true)
    }

    fun setFilter(key: String, value: String) {
        val next = _state.value.selectedFilters.toMutableMap()
        next[key] = value
        val site = _state.value.currentSite ?: return
        val extend = buildExtend(site, next, _state.value.filters)
        _state.value = _state.value.copy(selectedFilters = next)
        loadCategory(categoryTid(), currentCategoryName, replace = true, extend = extend)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.page >= s.pageCount) return
        loadCategory(categoryTid(), currentCategoryName, replace = false, page = s.page + 1, extend = buildExtend(s.currentSite ?: return, s.selectedFilters, s.filters))
    }

    private fun categoryTid(): String =
        _state.value.categories.firstOrNull { it.typeName == currentCategoryName }?.typeId
            ?: _state.value.categories.firstOrNull()?.typeId ?: ""

    private fun loadCategory(tid: String, typeName: String, replace: Boolean, page: Int = 1, extend: String? = null) {
        val site = _state.value.currentSite ?: return
        if (replace) {
            currentCategoryName = typeName
            currentCategoryTid = tid
            _state.value = _state.value.copy(loading = true, error = null, movies = emptyList(), page = 1)
        } else {
            _state.value = _state.value.copy(loadingMore = true)
        }
        viewModelScope.launch {
            val profile = currentProfile()
            val result = sourceRepository.categoryContent(site, tid, page.toString(), filter = extend != null, extend = extend)
            val incoming = filterMovies(profile, site, result?.list.orEmpty())
            val merged = if (replace || page <= 1) incoming else _state.value.movies + incoming
            _state.value = _state.value.copy(
                loading = false,
                loadingMore = false,
                movies = merged,
                page = result?.page ?: page,
                pageCount = result?.pagecount ?: 1,
                error = if (result == null) TvboxLoadError.LOAD_FAILED else null,
            )
        }
    }

    private fun buildExtend(site: SiteConfig, selected: Map<String, String>, filters: List<FilterDef>): String? {
        if (selected.isEmpty()) return null
        val pairs = filters.mapNotNull { f -> selected[f.key]?.takeIf { it.isNotBlank() }?.let { f.key to it } }
        if (pairs.isEmpty()) return null
        return pairs.joinToString("&") { (k, v) -> "$k=$v" }
    }

    fun search(keyword: String) {
        if (keyword.isBlank() || _state.value.searching) return
        viewModelScope.launch {
            _state.value = _state.value.copy(searchKeyword = keyword, searching = true, searchResults = emptyList())
            val profile = currentProfile()
            val visibleKeys = _state.value.sites.filter { it.canSearch }.map { it.key }.toSet()
            val results = sourceRepository.aggregateSearch(keyword, onlySiteKeys = visibleKeys)
                .filter { r -> r.isSuccess }
                .map { r ->
                    val site = _state.value.sites.firstOrNull { it.key == r.siteKey }
                    r.copy(movies = r.movies.filter { m -> site == null || policy.movieAllowed(profile, site, m) })
                }
                .filter { it.movies.isNotEmpty() }
            _state.value = _state.value.copy(searching = false, searchResults = results)
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(searchKeyword = "", searchResults = emptyList(), searching = false)
    }

    /** 分类行 ⋯ 菜单：上锁/解锁（方案 §5.5.7）。无 Customize PIN 时给引导，不在 TVbox 内设 PIN。 */
    fun toggleLock(tid: String, typeName: String) {
        val site = _state.value.currentSite ?: return
        tv.own.owntv.mobile.tvbox.util.TbLog.tag("TvboxLock").i("toggleLock 调用：%s / %s", site.key, typeName)
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            val hasPin = !lock.isNoPinProfile(pid)
            if (!hasPin) {
                _state.value = _state.value.copy(lockEvent = TvboxLockEvent.NO_PIN)
                return@launch
            }
            val key = lock.keyOf(site.key, tid, typeName)
            val locked = key in _state.value.sealedKeys
            tv.own.owntv.mobile.tvbox.util.TbLog.tag("TvboxLock").i("toggleLock 执行：pid=%d locked->%s", pid, !locked)
            lock.toggleLock(pid, site.key, tid, typeName, !locked)
            tv.own.owntv.mobile.tvbox.util.TbLog.tag("TvboxLock").i("toggleLock 完成")
            // 重算锁定集合（无 PIN 时 sealedKeys 为空集，锁行照常显示）
            val sealed = lock.sealedKeys(pid, site.key, _state.value.categories)
            _state.value = _state.value.copy(
                sealedKeys = sealed,
                lockEvent = if (locked) TvboxLockEvent.UNLOCKED else TvboxLockEvent.LOCKED,
            )
        }
    }

    fun consumeLockEvent() {
        _state.value = _state.value.copy(lockEvent = null)
    }

    /**
     * 点击已锁定分类：PIN 通过后放行进入（方案 §5.5.6 列表层强校验）。
     * epoch 防绕过协议在 [TvboxCategoryLock.unlock] 内；失败由 UI 出 WRONG/WAIT。
     */
    suspend fun unlockCategory(tid: String, typeName: String, pin: String): PinAttempt {
        val site = _state.value.currentSite ?: return PinAttempt.Wrong
        val pid = settings.activeProfileId.first()
        val result = lock.unlock(pid, site.key, tid, typeName, pin)
        if (result is PinAttempt.Accepted) {
            selectCategory(tid, typeName)
        }
        return result
    }

    private suspend fun filterMovies(profile: ProfileEntity?, site: SiteConfig, movies: List<Movie>): List<Movie> =
        movies.filter { policy.movieAllowed(profile, site, it) }
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

data class TvboxDetailUiState(
    val site: SiteConfig? = null,
    val vodId: String = "",
    val name: String = "",
    val pic: String? = null,
    val remark: String? = null,
    val typeName: String? = null,
    val year: String? = null,
    val content: String? = null,
    val lines: List<PlayLine> = emptyList(),
    val currentLineIndex: Int = 0,
    val favorite: Boolean = false,
    val loading: Boolean = false,
    val error: TvboxLoadError? = null,
    val resolving: Boolean = false,
    val resumeIndex: Int = -1,
    val resumePositionMs: Long = 0,
    /** 儿童模式命中：详情拒绝展示。 */
    val blockedByKids: Boolean = false,
    /** 解析层业务消息（换线/换源引导），数据透传。 */
    val resolveError: String? = null,
    /** 分类锁命中：进入前先弹 PIN。 */
    val sealed: Boolean = false,
)

class TvboxDetailViewModel(
    private val sourceRepository: SourceRepository,
    private val configRepository: TvboxConfigRepository,
    private val resolver: TvboxPlayResolver,
    private val policy: TvboxCategoryPolicy,
    private val lock: TvboxCategoryLock,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val store: TvboxStore,
    private val vodTuner: VodTuner,
) : ViewModel() {

    private val _state = MutableStateFlow(TvboxDetailUiState())
    val state: StateFlow<TvboxDetailUiState> = _state.asStateFlow()

    /** 起播成功后由 UI 触发导航（PLAY_ROUTE）。 */
    var onPlayStarted: () -> Unit = {}

    /** 打开详情：儿童模式命中直接拒绝；锁命中标记 sealed（UI 弹 PIN）。
     *  [typeId]/[typeName] 来自列表层 —— 锁 key 的 typeId 在列表页就确定了（§5.5.3）。 */
    fun open(siteKey: String, vodId: String, typeId: String?, typeName: String?) {
        val site = configRepository.siteMap[siteKey] ?: return
        viewModelScope.launch {
            _state.value = TvboxDetailUiState(site = site, vodId = vodId, loading = true)
            val pid = settings.activeProfileId.first()
            val profile = profileDao.getById(pid)
            val baseKey = TvboxCategoryKey(site.key, site.name, typeId, typeName.orEmpty())
            // 分类名此时还不知道（详情才知道 typeName）——先用站点级判定拦截 ext 标记的站
            if (policy.blockedByKids(profile, site, null)) {
                _state.value = _state.value.copy(loading = false, blockedByKids = true)
                return@launch
            }
            if (lock.seals(pid, site.key, baseKey.typeId, baseKey.typeName)) {
                _state.value = _state.value.copy(loading = false, sealed = true)
                return@launch
            }
            val detail = sourceRepository.detailContent(site, vodId)
            val movie = detail?.list?.firstOrNull()
            if (movie == null) {
                _state.value = _state.value.copy(loading = false, error = TvboxLoadError.DETAIL_FAILED)
                return@launch
            }
            // 详情层二次校验：key 沿用列表层身份（typeId 不因详情返回而漂移，方案 R14 口径）
            val key = (categoryKey ?: baseKey)
            if (policy.blockedByKids(profile, site, key.typeName)) {
                _state.value = _state.value.copy(loading = false, blockedByKids = true)
                return@launch
            }
            categoryKey = key
            val sealedNow = lock.seals(pid, site.key, key.typeId, key.typeName)
            val lines = resolver.parseLines(movie.playUrl, movie.playFrom)
            val resume = store.latestProgressForVod(site.key, vodId)
            _state.value = _state.value.copy(
                loading = false,
                name = movie.name,
                pic = TextUtil.absoluteUrl(movie.pic, site.api),
                remark = movie.remark,
                typeName = movie.typeName,
                year = movie.year,
                content = movie.content?.takeIf { it.isNotBlank() },
                lines = lines,
                favorite = store.isFavorite(site.key, vodId),
                sealed = sealedNow,
                resumeIndex = resume?.episodeIndex ?: -1,
                resumePositionMs = resume?.positionMs ?: 0,
            )
        }
    }

    private var categoryKey: TvboxCategoryKey? = null

    fun selectLine(index: Int) {
        _state.value = _state.value.copy(currentLineIndex = index)
    }

    fun toggleFavorite() {
        val s = _state.value
        val site = s.site ?: return
        viewModelScope.launch {
            val added = store.toggleFavorite(
                TvboxStore.FavoriteEntry(
                    siteKey = site.key,
                    vodId = s.vodId,
                    name = s.name,
                    pic = s.pic,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            _state.value = _state.value.copy(favorite = added)
        }
    }

    /** 解锁并进入（PIN 通过后 UI 调）。 */
    fun markUnlocked() {
        _state.value = _state.value.copy(sealed = false)
    }

    /**
     * PIN 解锁（PIN 弹窗回调）：走 [TvboxCategoryLock.unlock] 的 epoch 防绕过协议，
     * 限速器是 Core 的共享单例，等待秒数与 Core 门一致。
     */
    suspend fun unlockWithPin(pin: String): PinAttempt {
        val s = _state.value
        val site = s.site ?: return PinAttempt.Wrong
        val pid = settings.activeProfileId.first()
        val key = categoryKey
        val result = lock.unlock(pid, site.key, key?.typeId, key?.typeName ?: s.typeName ?: "", pin)
        if (result is PinAttempt.Accepted) {
            _state.value = _state.value.copy(sealed = false)
        }
        return result
    }

    /**
     * 解析并起播（方案 §3.2 的调用链尾部）：
     * resolver.resolve → 逐集 PlaylistItem → VodTuner.playTvbox → onPlayStarted()。
     */
    fun play(episodeIndex: Int, startPositionMs: Long = 0) {
        val s = _state.value
        val site = s.site ?: return
        val key = categoryKey ?: return
        val line = s.lines.getOrNull(s.currentLineIndex) ?: return
        val episode = line.episodes.getOrNull(episodeIndex) ?: return
        if (s.resolving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(resolving = true, error = null)
            val pid = settings.activeProfileId.first()
            when (val outcome = resolver.resolve(
                profileId = pid,
                site = site,
                vodId = s.vodId,
                flag = episode.flag,
                episodeId = episode.id,
                episodeIndex = episodeIndex,
                categoryKey = key,
                detailLines = s.lines,
                title = s.name,
                subtitle = episode.name,
                posterUrl = s.pic,
            )) {
                is TvboxPlayResolver.Outcome.Blocked -> {
                    _state.value = _state.value.copy(
                        resolving = false,
                        sealed = !outcome.byKids,
                        blockedByKids = outcome.byKids,
                    )
                }
                is TvboxPlayResolver.Outcome.Error -> {
                    _state.value = _state.value.copy(resolving = false, resolveError = outcome.message)
                }
                is TvboxPlayResolver.Outcome.Ready -> {
                    val play = outcome.play
                    val queue = buildQueue(site, s, play)
                    val ctx = VodTuner.TvboxProgressCtx(
                        siteKey = site.key,
                        vodId = s.vodId,
                        episodeFlag = episode.flag,
                        episodeId = episode.id,
                        episodeIndex = episodeIndex,
                    )
                    val ok = vodTuner.playTvbox(play, queue, startPositionMs, ctx)
                    _state.value = _state.value.copy(resolving = false)
                    if (ok) {
                        store.recordHistory(
                            TvboxStore.HistoryEntry(
                                siteKey = site.key,
                                vodId = s.vodId,
                                name = s.name,
                                pic = s.pic,
                                episodeIndex = episodeIndex,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        onPlayStarted()
                    }
                }
            }
        }
    }

    /**
     * 逐集 PlaylistItem（拍板 C5：每集各自 headers）。当前集用解析好的直链与头；
     * 其余集携带源声明的头并在加载时惰性解析直链。
     */
    private fun buildQueue(
        site: SiteConfig,
        s: TvboxDetailUiState,
        play: ResolvedPlay,
    ): List<tv.own.owntv.player.PlaylistItem> {
        val line = s.lines.getOrNull(s.currentLineIndex) ?: return emptyList()
        if (line.episodes.size <= 1) return emptyList()
        val headers = tv.own.owntv.core.network.StreamHeaders.encode(play.headers)
        return line.episodes.mapIndexed { i, ep ->
            if (i == play.episodeIndex) {
                tv.own.owntv.player.PlaylistItem(
                    url = play.url,
                    meta = tv.own.owntv.player.MediaMeta(
                        title = s.name,
                        subtitle = ep.name,
                        logoUrl = s.pic,
                        contentKey = "tvbox:${site.key}:${s.vodId}:$i",
                    ),
                    httpHeaders = headers,
                )
            } else {
                tv.own.owntv.player.PlaylistItem(
                    url = ep.id,
                    meta = tv.own.owntv.player.MediaMeta(
                        title = s.name,
                        subtitle = ep.name,
                        logoUrl = s.pic,
                        contentKey = "tvbox:${site.key}:${s.vodId}:$i",
                    ),
                    // Lazy per-episode resolution: playerContent mints the direct URL as the item loads.
                    resolveUrl = {
                        val r = sourceRepository.playerContent(site, ep.flag, ep.id, null)
                        r?.url?.takeIf { it.startsWith("http", true) } ?: ep.id
                    },
                    httpHeaders = headers,
                )
            }
        }
    }
}
