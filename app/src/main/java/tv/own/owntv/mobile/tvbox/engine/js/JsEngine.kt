package tv.own.owntv.mobile.tvbox.engine.js

import com.whl.quickjs.wrapper.QuickJSContext
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.EngineKind
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.net.AddressFetcher
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.util.LocalSourceResolver
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.TbLog

/**
 * JS/DRPY 引擎。Jar 源在装配时排在它前面，`csp_*` 不会被抢走。
 *
 * ## 为什么要 LRU 上限
 * 一个 context 带着 drpy 运行时 + 依赖脚本的编译产物，
 * 多个 JS 源全部常驻不可接受。上限 [MAX_LIVE_CONTEXTS] 个，
 * 超出销毁最久未用的那个（accessOrder = true）。
 *
 * ## native 可用性探测
 * wrapper 2.1.0 的 `.so` 是 4KB 对齐，而 targetSdk=36：16KB 页设备上 `dlopen` 会抛
 * `UnsatisfiedLinkError`（Error，不是 Exception）。构造时探测一次并缓存；
 * 不可用时 [supports] 返回 false，站点在列表、首页绑定与引擎路由三处同时剔除。
 *
 * 移植并改造自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsEngine.kt`。
 */
class JsEngine(
    private val net: NetApi,
    private val store: JsLocalStore,
    private val cache: JsModuleCache,
    private val addressFetcher: AddressFetcher,
    private val activeSourceUrl: () -> String? = { null },
    private val nativeAvailable: Boolean = probeNative(),
) : Engine {

    override val kind: EngineKind = EngineKind.SPIDER_JS

    private val lock = Any()
    private val instances = LinkedHashMap<String, JsSpider>(8, 0.75f, true)

    init {
        JsProxyBridge.install()
    }

    override fun supports(site: SiteConfig): Boolean =
        supportsWith(nativeAvailable, site)

    override suspend fun obtain(site: SiteConfig): Spider = synchronized(lock) {
        instances[site.key]?.let { return@synchronized it }
        val runtimeUrl = resolveRuntimeUrl(site)
        val created = JsSpider(
            site = site,
            net = net,
            runtimeUrl = runtimeUrl,
            ruleUrl = site.ext,
            store = store,
            cache = cache,
            addressFetcher = addressFetcher,
            activeSourceUrl = activeSourceUrl,
        )
        instances[site.key] = created
        trimToLimit()
        created
    }

    override fun isCooling(siteKey: String): Boolean = SourceHealth.isCooling(siteKey)

    /** 当前已建实例的数量（预热按 LRU 名额决定是否只下脚本）。 */
    fun liveCount(): Int = synchronized(lock) { instances.size }

    override fun isInitialized(site: SiteConfig): Boolean = synchronized(lock) {
        instances[site.key]?.initialized == true
    }

    /** 只把脚本下载进 JsModuleCache、不建 context。临时 JsSpider 不入 LRU。 */
    suspend fun prefetchScripts(site: SiteConfig) {
        val spider = JsSpider(
            site = site,
            net = net,
            runtimeUrl = resolveRuntimeUrl(site),
            ruleUrl = site.ext,
            store = store,
            cache = cache,
            addressFetcher = addressFetcher,
            activeSourceUrl = activeSourceUrl,
        )
        runCatching { spider.warmScripts() }
            .onFailure { TbLog.tag(TAG).w(it, "预下载脚本失败：%s", site.name) }
    }

    override fun invalidateAll() {
        synchronized(lock) {
            instances.values.forEach { evict(it) }
            instances.clear()
        }
        JsProxyBridge.clear()
    }

    private fun trimToLimit() {
        while (instances.size > MAX_LIVE_CONTEXTS) {
            val eldest = instances.entries.firstOrNull() ?: return
            TbLog.tag(TAG).i(
                "JS context 数量达到上限 %d，销毁最久未用的 %s",
                MAX_LIVE_CONTEXTS, eldest.value.displayName,
            )
            instances.remove(eldest.key)
            evict(eldest.value)
        }
    }

    private fun evict(spider: JsSpider) {
        runCatching { spider.destroy() }
            .onFailure { TbLog.tag(TAG).w(it, "销毁 JS 源失败：%s", spider.displayName) }
    }

    /** 旧配置/裸相对的 api 使用点兜底（导入期 fixContentPath 已覆盖新配置）。 */
    private fun resolveRuntimeUrl(site: SiteConfig): String {
        val raw = site.api.trim()
        if (raw.isEmpty() || raw.contains("://") || LocalSourceResolver.isLocalPath(raw)) return raw
        return LocalSourceResolver.resolveAgainst(raw, activeSourceUrl())
    }

    companion object {
        private const val TAG = "JsEngine"
        const val MAX_LIVE_CONTEXTS = 4

        /** 纯 api 判据，抽成静态供 JVM 单测直接断言（引擎构造需要 Context）。 */
        internal fun supportsSite(site: SiteConfig): Boolean =
            site.apiIsJs || (site.type == 3 && site.isJsSite)

        /** native 可用性叠加：16KB 页设备上 JS 源整体降级，而不是崩进程。 */
        internal fun supportsWith(nativeAvailable: Boolean, site: SiteConfig): Boolean =
            nativeAvailable && supportsSite(site)

        /**
         * 探测 QuickJS native 是否可加载。`UnsatisfiedLinkError` 是 Error，
         * 必须按 Throwable 捕获 —— 只 catch Exception 会让进程直接崩在类加载上。
         */
        internal fun probeNative(): Boolean = try {
            val ctx = QuickJSContext.create()
            ctx.destroy()
            true
        } catch (t: Throwable) {
            TbLog.tag(TAG).w(t, "QuickJS native 不可用，JS 源判为不可用")
            false
        }
    }
}