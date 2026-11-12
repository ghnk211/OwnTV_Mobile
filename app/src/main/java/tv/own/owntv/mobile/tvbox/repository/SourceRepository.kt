package tv.own.owntv.mobile.tvbox.repository

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.EngineKind
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.onIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 单源调用结果（聚合搜索用） */
data class SourceSearchResult(
    val siteKey: String,
    val siteName: String,
    val movies: List<Movie>,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && movies.isNotEmpty()
}

/**
 * 站点路由与调度（方案 5.7 / 10.3）。
 *
 * 职责：
 * - 站点 → 引擎路由
 * - 五接口调度，统一超时与健康统计
 * - 结果缓存（首页/分类第一页 5 分钟）
 * - 聚合搜索：并发 + 流式输出，先到先显示
 */
class SourceRepository(
    private val configRepository: TvboxConfigRepository,
    private val engines: List<Engine>,
    private val jsWarmUp: tv.own.owntv.mobile.tvbox.engine.js.JsWarmUp? = null,
) {

    /** 全局并发上限 4，防止某源拖垮全局 */
    private val globalSemaphore = Semaphore(4)

    /** 单次调用超时 */
    private val callTimeoutMs = 15_000L

    /** 搜索单源超时 */
    private val searchTimeoutMs = 8_000L

    /** init（脚本下载 + JS context 建立）单独预算，不与业务调用挤同一个超时（A4-1） */
    private val initTimeoutMs = 20_000L

    /** A8：首页/聚合搜索/换源链可并发读写，裸 HashMap 有结构损坏风险，换 ConcurrentHashMap */
    private val cache = ConcurrentHashMap<String, CachedResult>()

    /** A7：详情缓存（与 home 缓存同款容器/并发语义，独立 TTL） */
    private val detailCache = ConcurrentHashMap<String, CachedResult>()

    private data class CachedResult(val result: SpiderResult, val at: Long)

    fun siteOf(siteKey: String): SiteConfig? = configRepository.siteMap[siteKey]

    fun allSites(): List<SiteConfig> = configRepository.sites.value

    /** 站点列表流（换配置后自动推新值，供搜索/设置页跟着变） */
    fun allSitesFlow(): Flow<List<SiteConfig>> = configRepository.sites

    fun isCooling(siteKey: String): Boolean = SourceHealth.isCooling(siteKey)

    /**
     * 当前构建有引擎可跑、且未被标记无效的站点。
     *
     * 这是**所有候选源枚举**的共同上游：聚合搜索、首页借源兜底、
     * 详情/播放换源都走它；按 siteKey 直接访问（历史/收藏/详情）不走它。
     */
    fun supportedSites(): List<SiteConfig> =
        configRepository.sites.value.filter { canServe(it) }

    /**
     * 站点是否"既被配置标记为支持、又真有引擎能跑"。
     *
     * 这是站点可见性 / 首页绑定 / 聚合搜索 / 实际调用共用的唯一谓词；
     * `isSupported` 只看配置形态，JS 源在 native 不可用时会被引擎剔除。
     */
    fun canServe(site: SiteConfig): Boolean =
        site.isSupported && engineFor(site) != null

    private fun engineFor(site: SiteConfig): Engine? =
        engines.firstOrNull { it.supports(site) }

    fun kindOf(site: SiteConfig): EngineKind = engineFor(site)?.kind ?: EngineKind.NONE

    // ---------------- 缓存 ----------------

    private fun cacheKey(siteKey: String, tag: String): String = "$siteKey|$tag"

    private fun readCache(key: String): SpiderResult? =
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }?.result

    private fun writeCache(key: String, result: SpiderResult?) {
        if (result == null) return
        cache[key] = CachedResult(result, System.currentTimeMillis())
        // 简单上限，避免无限增长
        if (cache.size > 200) {
            val oldest = cache.minByOrNull { it.value.at }?.key
            oldest?.let { cache.remove(it) }
        }
    }

    /** 配置切换时失效全部缓存与引擎实例 */
    fun invalidateAll() {
        cache.clear()
        detailCache.clear()
        engines.forEach { it.invalidateAll() }
    }

    // ---------------- 五接口调度 ----------------

    suspend fun homeContent(site: SiteConfig, filter: Boolean = false): SpiderResult? {
        val key = cacheKey(site.key, "home")
        if (!filter) readCache(key)?.let { return it }
        val r = call(site, "home") { spider -> spider.homeContent(filter) }
        if (!filter) writeCache(key, r)
        return r
    }

    suspend fun categoryContent(
        site: SiteConfig,
        tid: String,
        pg: String,
        filter: Boolean = false,
        extend: String? = null,
        countHealth: Boolean = true,
        useCache: Boolean = true,
    ): SpiderResult? {
        // ## 缓存键必须带 extend（2026-10-31 真机：首页三级筛选"选了没反应"）
        // 同一 `tid + pg=1` 下，**筛选不同 → 内容不同**，而 extend 不参与键的话，
        // 先加载过的"未筛选第 1 页"会把后续每一次筛选请求全部吃掉：
        // 用户点「最新」/「最热」/「战神」，胶囊标签变了、列表一条没变，
        // 而且连一个网络请求都不会发出（cache 命中直接 return），日志里干净得像没点过。
        // 这类"看起来是 UI 不响应、其实是数据层短路"的坑，只有把筛选条件并进键才根治。
        val extendKey = extend?.trim().orEmpty()
        // ## useCache=false 的用途：目录探测
        // 网盘源的目录第一次请求常常返回空（AList 正在后台刷新目录缓存），
        // 探测要能重试，就必须绕开缓存 —— 否则空结果会被 `cat|tid` 缓存住，
        // 第二次"重试"直接命中缓存，永远拿不到内容。
        val cacheable = useCache && !filter && pg == "1"
        val key = cacheKey(site.key, "cat|$tid|$pg|$extendKey")
        if (cacheable) readCache(key)?.let { return it }
        val r = call(site, "cat|$tid|$pg", countHealth = countHealth) { spider ->
            spider.categoryContent(tid, pg, filter, extend)
        }
        if (cacheable) writeCache(key, r)
        return r
    }

    /**
     * 详情默认超时。
     *
     * 对齐 TVBoxOS `SourceViewModel.getDetail` 的 `future.get(30, SECONDS)`：
     * jar 源（尤其网盘系 csp_PanWebShare）的详情要经「分享页 → 直链解析」，
     * 15s 不够；站点自己声明 `timeout` 时仍以站点值为准（见 [call]）。
     */
    private val detailTimeoutMs = 30_000L

    /**
     * 详情（A7）：**10 分钟 TTL 详情缓存**（key=`siteKey|d|ids`，上限 50）。
     *
     * 详情（集数/线路列表）基本静态 —— 回详情页、换线后重进、换源接管前查详情
     * 全部命中，省掉每次 1~30s 的 detailContent 往返；换源链（PlayViewModel/
     * DetailViewModel 并行探测）二轮起近零成本。显式刷新传 [forceRefresh]。
     */
    suspend fun detailContent(
        site: SiteConfig,
        ids: String,
        forceRefresh: Boolean = false,
    ): SpiderResult? {
        val key = cacheKey(site.key, "d|$ids")
        if (!forceRefresh) {
            detailCache[key]
                ?.takeIf { System.currentTimeMillis() - it.at < DETAIL_TTL_MS }
                ?.let {
                    TbLog.tag(TAG).d("详情缓存命中：%s", key)
                    return it.result
                }
        }
        val r = call(site, ids, timeoutMs = detailTimeoutMs) { it.detailContent(ids) }
        if (r != null) {
            detailCache[key] = CachedResult(r, System.currentTimeMillis())
            while (detailCache.size > DETAIL_CACHE_MAX) {
                val oldest = detailCache.minByOrNull { it.value.at }?.key ?: break
                detailCache.remove(oldest)
            }
        }
        return r
    }

    suspend fun searchContent(
        site: SiteConfig,
        key: String,
        quick: Boolean = false,
        pg: String? = null,
    ): SpiderResult? = call(site, key, timeoutMs = searchTimeoutMs) {
        it.searchContent(key, quick, pg)
    }

    suspend fun playerContent(
        site: SiteConfig,
        flag: String,
        id: String,
        vipFlags: String? = null,
        /**
         * 是否计入源健康统计。
         * **预取下一集必须传 false**（见 NextEpisodePrefetcher）：
         * 预取不是用户发起的真实请求，失败不该把源推向冷却 ——
         * 否则弱源会被后台预取悄悄拖进冷却，违背"异常源不连坐"的设计口径。
         */
        countHealth: Boolean = true,
    ): PlayResult? = call(site, id, countHealth = countHealth) { it.playerContent(flag, id, vipFlags) }

    /**
     * 统一调用包装：并发许可 → 冷却短路 → 超时 → 健康统计。
     * 失败只影响单源，返回 null 由上层给"换源"引导。
     *
     * 用 TIMEOUT 哨兵严格区分三种结局（引擎层约定：抛异常 = 故障，返回 null = 空）：
     *   - 引擎抛异常 → 兜底 catch 记账，计失败/冷却，UI 显示真实错误
     *   - 返回 null    → recordEmpty，"源响应了但内容为空"，不计故障
     *   - withTimeout 超时 → 记超时失败
     */
    /**
     * @param countHealth 是否计入源健康统计。
     *   **兜底探测必须传 false**（见 [homeFallback]）：兜底会在一次用户点击里
     *   连续试 `homeVideoContent` + 借主源 `homeContent`，若都记失败，
     *   坏源点两下就攒够 3 次阈值被冷却 —— 真机实测"只有前两个页签能用，
     *   其余全部提示冷却 4 分钟"，根因就是这里重复计数。
     */
    private suspend fun <T : Any> call(
        site: SiteConfig,
        tag: String,
        timeoutMs: Long = callTimeoutMs,
        countHealth: Boolean = true,
        block: suspend (Spider) -> T?,
    ): T? {
        // 调用方已取消时不再占许可、不再发请求（搜索换词取消的关键闸口）
        kotlin.coroutines.coroutineContext.ensureActive()
        if (SourceHealth.isCooling(site.key)) {
            TbLog.d("站点 %s 冷却中，跳过调用", site.name)
            return null
        }
        // 站点级超时（生态 site.timeout，单位秒）：慢站声明大超时后不再被全局默认掐断
        val effectiveTimeout = site.timeout?.takeIf { it > 0 }?.let { it * 1000L } ?: timeoutMs
        val engine = engineFor(site)
        if (engine == null) {
            TbLog.w("站点 %s(type=%d) 无可用引擎（M1 仅支持 CMS 型源）", site.name, site.type)
            return null
        }

        // init 是否还在进行（A4-3：init/下载期失败不计健康）；同协程内顺序变更，无并发问题
        var initPhase = true
        return try {
            val start = System.currentTimeMillis()
            val outcome = globalSemaphore.withPermit {
                val spider = engine.obtain(site)
                if (!spider.initialized) {
                    // A4-1：init 移出业务超时预算 —— 冷启动的脚本下载+编译不该吃掉
                    // search(8s)/call(15s) 的业务预算；init 单独 20s。
                    withTimeoutOrNull(initTimeoutMs) { spider.init(site.ext) }
                        ?: return@withPermit INIT_TIMEOUT_MARKER
                }
                initPhase = false
                withTimeoutOrNull(effectiveTimeout) {
                    block(spider) ?: return@withTimeoutOrNull TIMEOUT_MARKER
                }
            }
            when (outcome) {
                INIT_TIMEOUT_MARKER -> {
                    // A4-3：init/下载期超时不计失败 —— 一次网络抖动不该把健康源推向冷却
                    TbLog.tag(TAG).w("站点 %s init 超时（%dms，不计健康）：%s", site.name, initTimeoutMs, tag)
                    null
                }

                // 源返回 null：不算失败，但要记下"接口通了但没数据"，
                // 否则 UI 只能显示"加载失败，可切换站点重试"，用户无从判断是源挂了还是源本身空。
                // 注意不能走 recordFailure —— 那会把正常但内容为空的源判为故障并冷却。
                TIMEOUT_MARKER -> {
                    if (countHealth) {
                        SourceHealth.recordEmpty(site.key, "$tag: 源返回空（接口正常但无数据）")
                    }
                    TbLog.tag(TAG).d( "站点 %s %s 返回空（未计为故障）", site.name, tag)
                    null
                }

                null -> {
                    if (countHealth) {
                        SourceHealth.recordFailure(site.key, "超时（${effectiveTimeout}ms）")
                    }
                    TbLog.tag(TAG).w( "站点 %s 调用超时：%s", site.name, tag)
                    null
                }

                else -> {
                    if (countHealth) {
                        val cost = System.currentTimeMillis() - start
                        SourceHealth.recordSuccess(site.key, cost)
                    }
                    @Suppress("UNCHECKED_CAST")
                    outcome as T
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 兜 Throwable：jar 缺桩方法/字段抛的是 Error（NoSuchMethodError 等），
            // 走 Exception 分支会直接崩掉 App。真机上已实测到该场景。
            val msg = t.message ?: t.javaClass.simpleName
            if (initPhase) {
                // A4-3：init/下载期失败不计健康（含 JS 的 JsTimeoutException、脚本下载失败），
                // 只留日志 —— 一次网络抖动不该把健康源推向冷却，半权记账可后续再加
                TbLog.tag(TAG).w("站点 %s init 失败（不计健康）：%s", site.name, t.toString())
            } else {
                if (countHealth) {
                    SourceHealth.recordFailure(site.key, "$tag: $msg")
                }
                TbLog.w("站点 %s 调用失败（%s）：%s", site.name, tag, t.toString())
            }
            null
        }
    }



    // ---------------- 聚合搜索（方案 10.3） ----------------

    /**
     * 对全部 searchable 源并发搜索，流式输出。
     * 已冷却的源直接跳过。
     */
    suspend fun aggregateSearch(
        keyword: String,
        quick: Boolean = false,
        /**
         * 参与搜索的站点 key 白名单；空集合 = 不限（全部可搜索源都参与）。
         *
         * 用户在搜索页勾选"只用这几个源"时传入（对齐 TVBoxOS
         * `SearchHelper.getSourcesForSearch` + `SOURCES_FOR_SEARCH`）。
         */
        onlySiteKeys: Set<String> = emptySet(),
        /**
         * 并发上限。
         *
         * 刻意与 `globalSemaphore`（4）分开：全局信号量是所有接口共用的，
         * 搜索本身又是一次并发打十几个源，若再叠一层全局限流，
         * 首页/详情的请求会被一次搜索全部堵住。
         * 这里单独限搜索并发，既不让单次搜索打爆源站，也不影响其它链路。
         */
        concurrency: Int = SEARCH_CONCURRENCY,
        /** 每源完成时的进度回调（先到先显示），由调用方桥接到自己的事件流。 */
        onProgress: (SourceSearchResult) -> Unit,
    ): List<SourceSearchResult> = coroutineScope {
        if (keyword.isBlank()) return@coroutineScope emptyList()
        val targets = supportedSites()
            .filter { it.canSearch && !SourceHealth.isCooling(it.key) }
            .filter { onlySiteKeys.isEmpty() || it.key in onlySiteKeys }

        if (targets.isEmpty()) return@coroutineScope emptyList()

        // A4-2：冷源搜索降级 —— 未 init 的 JS 源本轮不发起（冷启动 9~11s 会白吃
        // 8.5s 预算还拖垮首屏），进度里标「预热中」并触发预热插队；context 就绪后
        // 自然参与后续搜索轮。比硬等超时再判死好得多。
        val (ready, warming) = targets.partition { site ->
            val engine = engineFor(site)
            engine == null || engine.kind != EngineKind.SPIDER_JS || engine.isInitialized(site)
        }
        warming.forEach { site ->
            TbLog.tag(TAG).i("搜索跳过未 init 的 JS 源（预热中）：%s", site.name)
jsWarmUp?.warmSoon(site)
            onProgress(SourceSearchResult(site.key, site.name, emptyList(), "预热中，本轮暂不参与"))
        }
        if (ready.isEmpty()) return@coroutineScope emptyList()

        val searchSemaphore = Semaphore(concurrency.coerceAtLeast(1))

        // 调用方作用域：上层 flatMapLatest 取消即全部站点请求一起取消，
        // 换词后旧请求不再跑完、也不再迟到 emission。
        val results = ready.map { site ->
            async {
                val r = searchSemaphore.withPermit {
                    withTimeoutOrNull(searchTimeoutMs + 500) {
                        searchContent(site, keyword, quick, null)
                    }
                }
                val item = SourceSearchResult(
                    siteKey = site.key,
                    siteName = site.name,
                    movies = r?.list.orEmpty(),
                    error = if (r == null) "超时或无结果" else null,
                )
                // A5：真流式 —— 谁先完成谁先 emit，快源 1~2s 即出结果，
                // 不再等最慢的 8.5s 超时源；过滤口径与原批量 emit 一致
                if (item.movies.isNotEmpty() || item.error != null) {
                    onProgress(item)
                }
                item
            }
        }

        val all = awaitAll(*results.toTypedArray())
        all.filter { it.movies.isNotEmpty() || it.error != null }
    }

    // ---------------- 分类辅助 ----------------

    /**
     * 首页推荐兜底（对齐 TVBoxOS `getHomeRecList`）。
     *
     * ## 为什么要两级兜底
     * 排查 vip.json 时发现：23 个源里 20 个 `homeContent` 返回空分类，
     * 首页一片空白。TVBoxOS 对此有两层处理：
     * 1. 先问源自己的 `homeVideoContent()`（部分源把推荐放在独立接口）
     * 2. 还空 → 用**其他能浏览的源**的首页内容填充（全局兜底）
     *
     * 只做第 1 层时，对"既没 home 也没 homeVideo"的源仍然空白；
     * 只做第 2 层时，当前源明明有推荐却拿别人的，会误导用户。
     * 故两级都做，且**当前源优先**。
     *
     * 兜底结果**不写缓存**：它依赖别的源的实时状态，缓存会掩盖源的真实可用性。
     *
     * @param allowBorrow 是否允许第二级（借别的源的内容）。
     *   用户**显式选定的源必须传 false**：选了 A 却列出 B 的片单，
     *   比给一张空页更让人困惑（真机反馈「选了某个源之后还是提示暂无首页内容」）。
     *   只有系统自动挑的默认源才借 —— 那时用户没有明确预期，空页反而像坏了。
     * @param ownOnly 只要第一级（源自己的 `homeVideoContent`），不做借源兜底。
     *   用于「homeContent 有分类但推荐 list 为空」的场景（drpy2 官源普遍如此：
     *   `home` 只给分类，推荐在 `homeVod`）：此时 classes 已可渲染，
     *   只缺「主页」页签的推荐流，问源自己要即可，绝不该借别人的内容
     *   —— 用户显式选了爱奇艺，主页却出现 360 的片单属于 bug 级误导。
     */
    suspend fun homeFallback(site: SiteConfig, allowBorrow: Boolean = true, ownOnly: Boolean = false): SpiderResult? {
        // 第一级：源自己的推荐接口（不计健康，理由见 call 的 countHealth 说明）
        // P1-5：兜底级联的超时从 10s 收到 5s —— 首页兜底是最坏白屏路径，
        // 自动挑到空源时 homeContent 15s + homeVideo 10s + 借源 10s 最坏 35s 白屏。
        // 2026-10-18 放回 10s：drpy2 官源的 homeVod 实现要抓源站首页 HTML
        // 解析推荐（如爱奇艺[js] req("https://www.iqiyi.com") 全页解析），
        // 模拟器/代理链路下 5s 必超时且**无任何日志可查**（withTimeoutOrNull
        // 静默 null）—— 表现为"主页页签空、JS 打印到一半戛然而止"。
        // 白屏风险靠外层 loadHome 的 loading 态兜住，不再牺牲真实慢源。
        val own = runCatching {
            call(site, "homeVideo", timeoutMs = 10_000, countHealth = false) { it.homeVideoContent() }
        }.getOrNull()
        if (own != null && own.list.isNotEmpty()) {
            TbLog.i("站点 %s 用 homeVideoContent 兜底，%d 条", site.name, own.list.size)
            return own
        }

        if (!allowBorrow || ownOnly) return null

        // 第二级：借一个能浏览的源的首页内容。
        // P1-5：跳过"最近首页返回过空"的源 —— 借一个同样没首页的只是再白等一个超时。
        val donor = supportedSites()
            .firstOrNull {
                it.key != site.key && !it.looksSearchOnly &&
                    !SourceHealth.isCooling(it.key) && !SourceHealth.isRecentlyEmpty(it.key) &&
                    engineFor(it) != null
            }
            ?: return null

        val borrowed = call(donor, "home", timeoutMs = 5_000, countHealth = false) {
            it.homeContent(false)
        } ?: return null
        val list = borrowed.list
        if (list.isEmpty()) return null

        TbLog.i("站点 %s 首页为空，借用 %s 的推荐（%d 条）", site.name, donor.name, list.size)
        // 借来的内容必须标记 donor：id/详情只在借主站点有效，
        // 点进详情要用 donor.key 去查，否则用当前空首页的站点必然查不到。
        return borrowed.copy(
            list = list.map { it.copy(donorSiteKey = donor.key) },
            classes = emptyList(),
        )
    }

    /** 站点首页分类；无分类时给一个"全部"兜底，便于 UI 直接展示 */
    suspend fun classesOf(site: SiteConfig): List<ClassInfo> {
        val r = homeContent(site, filter = false) ?: return emptyList()
        return r.classes.ifEmpty {
            listOf(ClassInfo("all", "全部"))
        }
    }

    /** 找同名的其他源（"换源"引导，方案 9.3） */
    suspend fun findAlternativeSources(keyword: String, excludeSiteKey: String): List<SiteConfig> {
        if (keyword.isBlank()) return emptyList()
        return supportedSites()
            .filter { it.canSearch && it.key != excludeSiteKey && !SourceHealth.isCooling(it.key) }
            .take(5)
    }

    companion object {
        private const val TAG = "SourceRepo"

        /**
         * 首页/分类第一页缓存 TTL。
         *
         * P2-6：改为会话内有效（对齐 TVBoxOS `sortCache` 语义，LRU-5 无 TTL）。
         * 5 分钟 TTL 会让用户回切已加载过的源时重新走一遍网络 RTT，
         * 而"回切秒开"正是参考项目的体感优势。配置切换时 [invalidateAll] 已兜底清空，
         * 不会看到别的配置的残留数据。上限 200 条由 [writeCache] 控制，不会无限增长。
         */
        private const val CACHE_TTL_MS = Long.MAX_VALUE

        /** A7：详情缓存 TTL 与上限（方案：LRU 50 条 / 10min） */
        private const val DETAIL_TTL_MS = 10 * 60 * 1000L
        private const val DETAIL_CACHE_MAX = 50

        /** 哨兵：源正常返回 null */
        private val TIMEOUT_MARKER = Any()

        /** 哨兵：init（下载/建 context）超时，不计健康（A4-3） */
        private val INIT_TIMEOUT_MARKER = Any()

        /** 聚合搜索的并发上限（详见 aggregateSearch 的 concurrency 说明） */
        private const val SEARCH_CONCURRENCY = 6
    }
}
