package tv.own.owntv.mobile.tvbox.engine.py

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.EngineKind
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.util.Md5Util
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.TbLog
import tv.own.owntv.mobile.tvbox.util.onIo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Python 引擎门面 + 实例池（docs/TVbox-PY源迁移方案.md §2.3 / §5.1）。
 *
 * 形态复刻 [tv.own.owntv.mobile.tvbox.engine.jar.JarEngine]：supports 判定 → py 文件就绪 →
 * 加载脚本实例 → 按 siteKey 缓存 [PySpider]，配置切换 `invalidateAll()` 整体丢弃。
 *
 * ## LRU 上限 [MAX_LIVE_INSTANCES]
 * fun.json 一份配置就有 27 个 py 源，实例数随站点数无界增长；CPython 对象常驻
 * 低内存 TV 盒子不可接受。超出上限销毁最久未用的实例（accessOrder = true）。
 *
 * @param pyFileProvider 站点 py 脚本 → 本地文件（下载/本地相对路径落盘见
 *   `TvboxConfigRepository.ensurePyFile`）
 */
class PyEngine(
    private val context: Context,
    private val pyFileProvider: suspend (SiteConfig) -> File?,
) : Engine {

    override val kind: EngineKind = EngineKind.SPIDER_PY

    /** siteKey → Spider 实例（LRU，accessOrder） */
    private val instances = LinkedHashMap<String, PySpider>(8, 0.75f, true)

    private val lock = Any()

    /** 建实例的并发闸：避免同一站点被并发建出多个 Spider */
    private val buildMutex = Mutex()

    /** 站点 key → 最近一次加载失败原因（按站点存，避免并发预热时错误串台） */
    private val lastErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun supports(site: SiteConfig): Boolean = supportsSite(site)

    override suspend fun obtain(site: SiteConfig): Spider {
        synchronized(lock) { instances[site.key] }?.let { return it }
        return buildMutex.withLock {
            // 双检：等锁期间可能已被别的协程建好
            synchronized(lock) { instances[site.key] }?.let { return@withLock it }
            val created = create(site) ?: throw PyLoadException(
                lastErrors[site.key] ?: "「${site.name}」Python 脚本不可用",
            )
            synchronized(lock) {
                instances[site.key] = created
                trimToLimit()
            }
            created
        }
    }

    /** 加载 + 实例化；任何一环失败返回 null（由 [obtain] 转成异常计入源健康） */
    private suspend fun create(site: SiteConfig): PySpider? = onIo {
        val file = pyFileProvider(site) ?: run {
            fail(site, "py 脚本不可用（下载/本地读取失败）：${site.api}")
            return@onIo null
        }
        // CPython 惰性启动（约 100~300ms，不进冷启关键路径）
        runCatching { ensurePythonStarted(context) }.getOrElse { e ->
            fail(site, "Python 运行时启动失败：${e.message ?: e.javaClass.simpleName}")
            return@onIo null
        }
        val loader = Python.getInstance().getModule("base.loader")
        // 脚本 print → 宿主 TbLog（logcat + app.log 双写）；幂等，重复调无害
        runCatching { loader.callAttr("redirect_stdio") }

        val moduleName = "user_" + Md5Util.md5(site.key)
        val instance = runCatching {
            loader.callAttr("load_spider", file.absolutePath, moduleName)
        }.getOrElse { e ->
            // PyException 里带着 loader 的 ValueError 文案（未定义 Spider 子类等）
            fail(site, "脚本加载失败：${e.message?.take(160) ?: e.javaClass.simpleName}")
            return@onIo null
        } ?: run {
            fail(site, "脚本实例化返回空：${file.name}")
            return@onIo null
        }

        lastErrors.remove(site.key)
        TbLog.tag(TAG).i("%s → %s（%s）", site.name, moduleName, file.name)
        PySpider(site, instance, loader)
    }

    override fun isCooling(siteKey: String): Boolean = SourceHealth.isCooling(siteKey)

    override fun invalidateAll() {
        synchronized(lock) {
            instances.values.forEach { runCatching { it.destroy() } }
            instances.clear()
        }
        TbLog.tag(TAG).d("引擎池已清空")
    }

    /** 超出上限销毁最久未用的实例（LinkedHashMap accessOrder 保证"最久未用"在队首） */
    private fun trimToLimit() {
        while (instances.size > MAX_LIVE_INSTANCES) {
            val eldest = instances.entries.firstOrNull() ?: return
            TbLog.tag(TAG).i(
                "Python 实例达到上限 %d，销毁最久未用的 %s",
                MAX_LIVE_INSTANCES, eldest.value.displayName,
            )
            instances.remove(eldest.key)
            runCatching { eldest.value.destroy() }
                .onFailure { TbLog.tag(TAG).w(it, "销毁 Python 实例失败：%s", eldest.value.displayName) }
        }
    }

    /** 记录失败原因（按站点存；reason 只写"发生了什么"，站点名由上层统一拼） */
    private fun fail(site: SiteConfig, reason: String): Nothing? {
        TbLog.tag(TAG).e("%s 加载失败：%s", site.name, reason)
        lastErrors[site.key] = reason.take(160)
        return null
    }

    companion object {
        private const val TAG = "PyEngine"

        /** LRU 上限：超出销毁最久未用实例 */
        const val MAX_LIVE_INSTANCES = 12

        /** Python 启动闸：[Python.start] 全进程只允许一次 */
        private val startLock = Any()

        /** py 脚本加载失败时抛出，由 SourceRepository 统一计入源健康 */
        class PyLoadException(msg: String) : Exception(msg)

        /**
         * 纯 api 判据，抽成静态供 JVM 单测直接断言（引擎构造需要 Context）。
         * 必须与 `SiteConfig.isPySite` / `SiteConfig.isSupported` 逐条对齐。
         */
        internal fun supportsSite(site: SiteConfig): Boolean = site.type == 3 && site.isPySite

        /**
         * 惰性启动 CPython。
         *
         * `Python.start` 约 100~300ms，放在 Application.onCreate 会进冷启关键路径，
         * 故推迟到首次 obtain。启动后常驻，进程内只做一次。
         */
        internal fun ensurePythonStarted(context: Context) {
            if (Python.isStarted()) return
            synchronized(startLock) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                    TbLog.tag(TAG).i("CPython 运行时已启动")
                }
            }
        }
    }
}