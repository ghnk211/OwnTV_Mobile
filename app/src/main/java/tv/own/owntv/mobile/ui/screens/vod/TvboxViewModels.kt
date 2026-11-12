package tv.own.owntv.mobile.ui.screens.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tv.own.owntv.core.categorylock.visibleOnceRead
import tv.own.owntv.core.customize.CategoryRailEditor
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.MoveKind
import tv.own.owntv.core.customize.railCategories
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType
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
import tv.own.owntv.mobile.tvbox.play.TvboxExtKey
import tv.own.owntv.mobile.tvbox.play.TvboxPlayResolver
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
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
        if (id == _state.value.activeId) return
        viewModelScope.launch {
            // 先移动高亮再切：`configs` 列表在切换时不会重新发射，只靠 collect 的话
            // 当前项会一直停在旧配置上 —— 用户看到的就是「点了没反应 / 切不了配置」
            // （真机实证 2026-11-10：日志已切到新配置，UI 高亮仍停在上一行）。
            _state.value = _state.value.copy(activeId = id)
            configRepository.switchConfig(id)
            _state.value = _state.value.copy(activeId = configRepository.activeId())
        }
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

/**
 * 分类条上的一枚芯片：Core `categories` 行叠加本档案定制（P1，方案 §4.8 B1）。
 * [key] 是 Core 定制 key `<sourceId>:<siteKey>/<typeId>`，隐藏/改名/排序/锁都写它；
 * [typeName] 保留站点原名（索引与详情身份用），[displayName] 是改名后的展示名。
 */
data class TvboxCategory(
    val key: String,
    val categoryId: Long,
    val typeId: String,
    val typeName: String,
    val displayName: String,
    val locked: Boolean,
    val sealed: Boolean,
)

data class TvboxHomeUiState(
    /** 站点列表（儿童档案已过滤；聚合搜索也只用这里剩下的源）。 */
    val sites: List<SiteConfig> = emptyList(),
    val currentSite: SiteConfig? = null,
    val categories: List<TvboxCategory> = emptyList(),
    val filters: List<FilterDef> = emptyList(),
    val selectedFilters: Map<String, String> = emptyMap(),
    val movies: List<Movie> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: TvboxLoadError? = null,
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
    private val provisioner: tv.own.owntv.mobile.tvbox.repository.TvboxSourceProvisioner,
    private val indexer: tv.own.owntv.mobile.tvbox.index.TvboxIndexer,
    private val categoryDao: CategoryDao,
    private val customize: CustomizationStore,
) : ViewModel() {

    private val _state = MutableStateFlow(TvboxHomeUiState())
    val state: StateFlow<TvboxHomeUiState> = _state.asStateFlow()

    /** 当前分类（tid/name），详情/播放要带去当锁与儿童判定的身份（方案 §5.5.3 key 口径）。 */
    private var currentCategoryName: String = ""
    private var currentCategoryTid: String = ""

    /** The extend the current category was last indexed under; a change bumps the cursor generation. */
    private var currentExtend: String? = null

    /** The active configuration's Core source id, refreshed on every openSite. */
    private var tvboxSourceId: Long? = null

    /** 分类条的数据源（P1）：当前站点 + 它的 Core 源 id；openSite 时置位。
     *  SharedFlow 而非 StateFlow：重开同一站点也要重新发射（StateFlow 会合并相等值）。 */
    private val railSite = kotlinx.coroutines.flow.MutableSharedFlow<Pair<SiteConfig, Long>?>(replay = 1)

    /** 站点已打开、等分类条第一次发射后自动进第一个分类。 */
    private var pendingFirstCategory = false

    /**
     * 分类条 = Core 分类行 × 本档案定制（方案 §4.8 B1）：隐藏/改名/排序/锁全部复用
     * Library 的 [railCategories] 口径，锁行保留在条上（sealed 标记），点击弹 PIN。
     * 儿童档案的分类名判定仍走 [TvboxCategoryPolicy]（站点 ext 标记 + 词表，P3 词表上移 Core）。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val rail = combine(railSite, settings.activeProfileId) { site, pid -> site to pid }
        .flatMapLatest { (siteSource, pid) ->
            if (siteSource == null || pid < 0) {
                flowOf("" to emptyList())
            } else {
                val (site, sourceId) = siteSource
                combine(
                    categoryDao.observe(listOf(sourceId), MediaType.MOVIE),
                    customize.visibleOnceRead(pid, MediaType.MOVIE).filterNotNull(),
                    profileDao.observeById(pid),
                ) { cats, cust, profile ->
                    val siteCats = cats.filter { it.remoteId?.startsWith("${site.key}/") == true }
                    val kids = profile?.isKids == true
                    val visible = if (kids) siteCats.filterNot { policy.blockedByKids(profile, site, it.name) } else siteCats
                    val byId = visible.associateBy { it.id }
                    site.key to visible.railCategories(cust, kids = kids).mapNotNull { c ->
                        // 自定义合并分类是 Library 的概念，VOD 分类条只显示站点分类。
                        val entity = c.categoryId?.let(byId::get) ?: return@mapNotNull null
                        val typeId = entity.remoteId?.substringAfter('/') ?: return@mapNotNull null
                        TvboxCategory(
                            key = c.key,
                            categoryId = entity.id,
                            typeId = typeId,
                            typeName = entity.name,
                            displayName = c.displayName,
                            locked = c.locked,
                            sealed = c.sealed,
                        )
                    }
                }
            }
        }

    /**
     * homeContent 返回的「分类 → 筛选树」。
     *
     * 筛选树按分类（typeId）分组，切分类时必须同步换行 —— 否则电视剧页面仍显示
     * 电影的「动作片/喜剧片」，点下去提交的是另一分类的 key（真机实证 2026-11-10：
     * 切换分类后筛选行不变、点了也不生效）。
     */
    private var homeFilters: Map<String, List<FilterDef>> = emptyMap()

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
        viewModelScope.launch {
            rail.collect { (siteKey, cats) ->
                // 切站点的竞态防护：只认当前站点的发射。
                if (siteKey != _state.value.currentSite?.key) return@collect
                _state.value = _state.value.copy(categories = cats)
                when {
                    pendingFirstCategory -> {
                        pendingFirstCategory = false
                        if (cats.isNotEmpty()) {
                            selectCategory(cats.first().typeId, cats.first().typeName)
                        } else {
                            // 分类全被隐藏（或儿童全过滤）：停留在家首页推荐列表。
                            _state.value = _state.value.copy(loading = false)
                        }
                    }
                    // 打开中的分类被隐藏：落到第一个可见分类，与 Library 隐藏后回 All 同语义。
                    currentCategoryTid.isNotBlank() && cats.isNotEmpty() && cats.none { it.typeId == currentCategoryTid } ->
                        selectCategory(cats.first().typeId, cats.first().typeName)
                }
            }
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
            val visible = policy.visibleSites(profile, configRepository.sites.value.filter { configRepository.isPlayable(it) })
            _state.value = _state.value.copy(sites = visible)
            val saved = configRepository.currentHomeSiteKey()
            val target = visible.firstOrNull { it.key == saved }
                ?: configRepository.resolveHomeSite()?.let { home -> visible.firstOrNull { it.key == home.key } }
            if (target != null && target.key != _state.value.currentSite?.key) {
                openSite(target)
            } else if (target == null) {
                railSite.tryEmit(null)
                _state.value = _state.value.copy(currentSite = null, categories = emptyList(), movies = emptyList())
            }
        }
    }

    fun openSite(site: SiteConfig) {
        viewModelScope.launch {
            _state.value = _state.value.copy(currentSite = site, loading = true, error = null, movies = emptyList(), page = 1, selectedFilters = emptyMap(), categories = emptyList())
            val profile = currentProfile()
            val result = sourceRepository.homeContent(site, filter = true)
            // Index what the remote showed, not the filtered view: content is shared between
            // profiles, and the kids check runs when a profile reads it.
            val sourceId = provisioner.activeSourceId()
            tvboxSourceId = sourceId
            if (sourceId != null) {
                indexer.indexClasses(sourceId, site, result?.classes.orEmpty())
                indexer.indexHome(sourceId, site, result?.list.orEmpty())
                indexer.markSynced(sourceId)
            }
            homeFilters = result?.filters.orEmpty()
            // 分类为空时兜底：homeVideoContent（纯推荐源）
            val movies = filterMovies(profile, site, result?.list.orEmpty())
            val hasClasses = !result?.classes.isNullOrEmpty()
            // 分类条改由 Core 发射（indexClasses 落库后）：第一次发射到达时自动进第一个分类。
            pendingFirstCategory = hasClasses && sourceId != null
            railSite.tryEmit(sourceId?.let { site to it })
            if (pendingFirstCategory) {
                _state.value = _state.value.copy(movies = movies, pageCount = result?.pagecount ?: 1)
            } else {
                _state.value = _state.value.copy(loading = false, filters = emptyList(), movies = movies, pageCount = result?.pagecount ?: 1)
            }
        }
    }

    fun selectCategory(tid: String, typeName: String) {
        // 换分类 = 换一套筛选维度：筛选行同步切换，旧分类的选择不能带过去。
        currentExtend = null
        _state.value = _state.value.copy(
            filters = homeFilters[tid].orEmpty(),
            selectedFilters = emptyMap(),
        )
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
        currentCategoryTid.ifBlank { _state.value.categories.firstOrNull()?.typeId ?: "" }

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
            val sourceId = tvboxSourceId ?: provisioner.activeSourceId()
            if (sourceId != null && result != null) {
                indexer.indexCategoryPage(sourceId, site, tid, typeName, result.list, page)
                // 筛选变了：旧分页不再描述这个分类，标记游标让旧行随 TTL 过期（方案 §4.8 B2）。
                if (replace && extend != currentExtend) {
                    indexer.bumpGeneration(sourceId, site.key, tid)
                }
            }
            if (replace) currentExtend = extend
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

    /**
     * 把筛选选择序列化成 **JSON 对象**（不是 query 串）。
     *
     * ## 为什么必须是 JSON（真机实证 2026-11-10，「糯米┃秒播」筛选无反应）
     * 两个引擎都按 JSON 解析 extend：
     * - jar：`JarSpider.extendToMap` → `JsonParser.parseString(extend)`
     * - CMS：`CmsSpider.parseFilterExtend` → `{"year":"2025"}`
     * 早前这里拼的是 `class=动作片&year=2025`，两边都解析失败：jar 退化成一个
     * 无用键，CMS 则把整串当成 `year` 的裸值 —— 表现就是「点筛选，列表纹丝不动」。
     */
    private fun buildExtend(site: SiteConfig, selected: Map<String, String>, filters: List<FilterDef>): String? {
        if (selected.isEmpty()) return null
        val obj = buildJsonObject {
            filters.forEach { f ->
                val v = selected[f.key]
                if (!v.isNullOrBlank() && v != "全部" && !v.equals("all", ignoreCase = true)) {
                    put(f.key, v)
                }
            }
        }
        return obj.takeIf { it.isNotEmpty() }?.toString()
    }

    /** 分类条 ⋯ 菜单：上锁/撤锁（方案 §5.5.7）。无 Customize PIN 时给引导，不在 TVbox 内设 PIN。
     *  锁状态写 Core `lockedCats`，分类条经 observeVisible 自动刷新。 */
    fun toggleLock(category: TvboxCategory) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (lock.isNoPinProfile(pid)) {
                _state.value = _state.value.copy(lockEvent = TvboxLockEvent.NO_PIN)
                return@launch
            }
            lock.toggleLock(pid, category.key, !category.locked)
            _state.value = _state.value.copy(
                lockEvent = if (category.locked) TvboxLockEvent.UNLOCKED else TvboxLockEvent.LOCKED,
            )
        }
    }

    /** 分类条 ⋯ 菜单：隐藏（与 Library/Customize 同一份 hiddenCats）。 */
    fun hideCategory(key: String) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (pid >= 0) customize.setCategoryHidden(pid, MediaType.MOVIE, key, true)
        }
    }

    /** 分类条 ⋯ 菜单：改名；空名恢复站点原名。与 Library 共用 categoryNames，两处同步。 */
    fun renameCategory(key: String, name: String?) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (pid >= 0) customize.renameCategory(pid, MediaType.MOVIE, key, name?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    private val categoryEditor by lazy { CategoryRailEditor(customize, categoryDao, profileDao) }

    /** 分类条 ⋯ 菜单：上移/下移一步，走 Library 同一个编辑器（手动位置跨排序保留）。 */
    fun moveCategory(category: TvboxCategory, kind: MoveKind) {
        val sourceId = tvboxSourceId ?: return
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (pid < 0) return@launch
            categoryEditor.move(
                profileId = pid,
                sourceIds = listOf(sourceId),
                type = MediaType.MOVIE,
                alphabetical = false,
                key = LiveKey.Folder(category.categoryId),
                kind = kind,
            )
        }
    }

    fun consumeLockEvent() {
        _state.value = _state.value.copy(lockEvent = null)
    }

    /**
     * 点击已锁定分类：PIN 通过后放行进入（方案 §5.5.6 列表层强校验）。
     * epoch 防绕过协议在 [TvboxCategoryLock.unlockKey] 内；失败由 UI 出 WRONG/WAIT。
     */
    suspend fun unlockCategory(category: TvboxCategory, pin: String): PinAttempt {
        val pid = settings.activeProfileId.first()
        val result = lock.unlockKey(pid, category.key, pin)
        if (result is PinAttempt.Accepted) {
            selectCategory(category.typeId, category.typeName)
        }
        return result
    }

    private suspend fun filterMovies(profile: ProfileEntity?, site: SiteConfig, movies: List<Movie>): List<Movie> =
        movies
            .filter { policy.movieAllowed(profile, site, it) }
            // 生态里 CMS 源常返回相对路径海报；详情页已补全，网格同一口径。
            .map { it.copy(pic = TextUtil.absoluteUrl(it.pic, site.api)) }
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
    /** The Core `movies.id` this detail indexed to; null when indexing failed. */
    val movieId: Long? = null,
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
    private val vodTuner: VodTuner,
    private val provisioner: tv.own.owntv.mobile.tvbox.repository.TvboxSourceProvisioner,
    private val indexer: tv.own.owntv.mobile.tvbox.index.TvboxIndexer,
    private val userData: tv.own.owntv.mobile.tvbox.index.TvboxUserData,
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
            val sourceId = provisioner.activeSourceId()
            val baseKey = TvboxCategoryKey(site.key, site.name, typeId, typeName.orEmpty())
            // 分类名此时还不知道（详情才知道 typeName）——先用站点级判定拦截 ext 标记的站
            if (policy.blockedByKids(profile, site, null)) {
                _state.value = _state.value.copy(loading = false, blockedByKids = true)
                return@launch
            }
            if (lock.seals(pid, sourceId, site.key, baseKey.typeId, baseKey.typeName)) {
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
            detailMovie = movie
            // Index the detail payload: the plot/year/type name here are richer than the list's, and
            // the resulting Core id is what a favourite and a resume position are keyed by.
            val movieId = sourceId?.let { id ->
                // typeId as well as typeName: the category row is what gives this film its kids/lock
                // attribution, and the writer keeps the first non-null one it was given.
                indexer.ensureIndexed(id, site, movie, categoryTypeId = key.typeId, categoryTypeName = key.typeName)
            }
            val sealedNow = lock.seals(pid, sourceId, site.key, key.typeId, key.typeName)
            val lines = resolver.parseLines(movie.playUrl, movie.playFrom)
            // 续播以 Core 进度为准（extKey 带线路/集数）；未索引成功的流没有进度可读。
            val coreResume = movieId?.let { userData.progress(pid, it) }
            val ext = TvboxExtKey.decode(coreResume?.extKey)
            val resumeLine = ext?.let { e -> lines.indexOfFirst { l -> l.episodes.any { it.id == e.episodeId } } }?.takeIf { it >= 0 }
            _state.value = _state.value.copy(
                loading = false,
                name = movie.name,
                pic = TextUtil.absoluteUrl(movie.pic, site.api),
                remark = movie.remark,
                typeName = movie.typeName,
                year = movie.year,
                content = movie.content?.takeIf { it.isNotBlank() },
                movieId = movieId,
                lines = lines,
                favorite = movieId?.let { userData.isFavorite(pid, it) } ?: false,
                sealed = sealedNow,
                currentLineIndex = resumeLine ?: 0,
                resumeIndex = ext?.index ?: -1,
                resumePositionMs = coreResume?.positionMs ?: 0,
            )
        }
    }

    private var categoryKey: TvboxCategoryKey? = null

    /** The detail payload of the currently open film, kept so a later sighting can re-index it. */
    private var detailMovie: Movie? = null

    fun selectLine(index: Int) {
        _state.value = _state.value.copy(currentLineIndex = index)
    }

    fun toggleFavorite() {
        val s = _state.value
        val site = s.site ?: return
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            // The Core id is the only path: a favourite written there shows up in the Favourites
            // page, the backup and every other profile-scoped surface.
            val movieId = s.movieId ?: detailMovie?.let { movie ->
                provisioner.activeSourceId()?.let { sourceId ->
                    indexer.ensureIndexed(
                        sourceId,
                        site,
                        movie,
                        categoryTypeId = categoryKey?.typeId,
                        categoryTypeName = categoryKey?.typeName,
                    )
                }
            } ?: return@launch
            val added = userData.toggleFavorite(pid, movieId)
            _state.value = _state.value.copy(movieId = movieId, favorite = added)
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
        val result = lock.unlock(pid, provisioner.activeSourceId(), site.key, key?.typeId, key?.typeName ?: s.typeName ?: "", pin)
        if (result is PinAttempt.Accepted) {
            _state.value = _state.value.copy(sealed = false)
            // open() 在 seals 命中时提前返回、没取详情 —— 解锁后必须重跑，
            // 否则页面停在"No playable episodes"（回归发现）。
            open(site.key, s.vodId, key?.typeId, key?.typeName ?: s.typeName)
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
            // Index right before playback too: opening the player is the last chance to give this
            // stream a Core identity, so its favourite and resume position are written against a row.
            val indexedId = s.movieId ?: detailMovie?.let { movie ->
                provisioner.activeSourceId()?.let { sourceId ->
                    indexer.ensureIndexed(sourceId, site, movie, categoryTypeId = key.typeId, categoryTypeName = key.typeName)
                }
            }
            if (indexedId != null && indexedId != s.movieId) {
                _state.value = _state.value.copy(movieId = indexedId)
            }
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
                        episodeFlag = episode.flag,
                        episodeId = episode.id,
                        episodeIndex = episodeIndex,
                    )
                    val ok = vodTuner.playTvbox(play, queue, startPositionMs, ctx, movieId = indexedId)
                    _state.value = _state.value.copy(resolving = false)
                    if (ok) onPlayStarted()
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
