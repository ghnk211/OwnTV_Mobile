package tv.own.owntv.mobile.tvbox.engine.py

import com.chaquo.python.PyObject
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.PlayNormalize
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.engine.jar.JarResultParser
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.TbLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Python 型源（type 3 + api 以 .py 结尾，方案 §2.3 / §5.1）。
 *
 * ## 加载链路
 * ```
 * site.api ─下载/本地读→ files/tvbox/py/{md5}.py
 *   → base.loader.load_spider(path, module_name) → 模块级 Spider 子类实例
 *   → callAttr 五接口（独立执行器 + 分级超时）→ JSON 桥 → JarResultParser 归一化
 * ```
 *
 * ## 归一化为什么"零转换"复用 [JarResultParser]
 * 脚本返回的是 TVBox 协议 dict；这里用 `base.loader.dumps` 转成 JSON 文本后，
 * [JarResultParser.asMap] 的 String 分支直接吃 —— 与 jar 源返回 JSON 串的链路完全一致，
 * `class`/`filters`/`vod_*` 字段兼容、`CipherUrlDecoder` 密文解码全部白捡。
 *
 * ## 与 [tv.own.owntv.mobile.tvbox.engine.jar.JarSpider] 的对齐点
 * - **分级超时**：init/detail 35s、home/category/player 20s、search 13s 下限；
 *   站点声明 timeout 时内层 = max(下限, timeout+5s)。
 * - **runGuarded 兜 Throwable**：Python 异常（PyException）击穿到协程层就是普通异常，
 *   脚本可能触发宿主 Error，统一兜住并把真实异常抛给 SourceRepository 记账。
 * - 超时只"不再等结果"，跑起来的 Python 函数无法被中断，由外层先收口记账。
 *
 * @param instance `base.loader.load_spider` 返回的脚本 Spider 实例
 * @param loader `base.loader` 模块（loads/dumps 桥）
 * @param floors 各接口超时下限；默认与 JarSpider 同构，测试可注入小值
 */
class PySpider(
    private val site: SiteConfig,
    private val instance: PyObject,
    private val loader: PyObject,
    private val floors: Floors = Floors(),
) : Spider {

    /** 各接口内层超时下限（ms），语义与 JarSpider 同构 */
    data class Floors(
        val initMs: Long = 35_000L,
        val homeMs: Long = 20_000L,
        val categoryMs: Long = 20_000L,
        val detailMs: Long = 35_000L,
        val searchMs: Long = 13_000L,
        val playMs: Long = 20_000L,
    )

    override val siteKey: String get() = site.key
    override val displayName: String get() = site.name

    @Volatile
    private var inited = false

    override val initialized: Boolean get() = inited

    // ---------------- 五接口 ----------------

    override suspend fun init(ext: String?) {
        if (inited) return
        // site_key 打到实例属性：base.spider.fetch 桥接时按源埋点（不依赖脚本 __init__）
        runCatching { instance.put("site_key", site.key) }
        warnMissingDependencies()
        call("init", timeoutFor(floors.initMs), ext ?: site.ext ?: "")
        inited = true
        TbLog.tag(TAG).i("%s Python 初始化完成", site.name)
    }

    override suspend fun homeContent(filter: Boolean): SpiderResult? = runGuarded("homeContent") {
        normalize(call("homeContent", timeoutFor(floors.homeMs), filter))
    }

    /** 首页推荐兜底（与 JarSpider 同构：homeContent 空时由上层用它填首页） */
    override suspend fun homeVideoContent(): SpiderResult? = runGuarded("homeVideoContent") {
        normalize(call("homeVideoContent", timeoutFor(floors.homeMs)))
    }

    override suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: String?,
    ): SpiderResult? = runGuarded("categoryContent") {
        normalize(
            call(
                "categoryContent", timeoutFor(floors.categoryMs),
                tid, pg.ifBlank { "1" }, filter, pyExtend(extend),
            ),
        )
    }

    override suspend fun detailContent(ids: String): SpiderResult? = runGuarded("detailContent") {
        val idList = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (idList.isEmpty()) return@runGuarded null
        val raw = call("detailContent", timeoutFor(floors.detailMs), pyList(idList))
        // 留一行原始预览：区分「脚本返回 null / 字段名不同 / 条目被过滤」
        TbLog.tag(TAG).d("%s 详情原始返回：%s", site.name, JarResultParser.preview(raw))
        // 与 JarSpider 同构：直播/索引类源详情常只给线路不给名字，名称判据放宽 + id 兜底
        normalize(raw, requireName = false, fallbackId = idList.first())
    }

    override suspend fun searchContent(key: String, quick: Boolean, pg: String?): SpiderResult? =
        runGuarded("searchContent") {
            if (key.isBlank()) return@runGuarded null
            normalize(call("searchContent", timeoutFor(floors.searchMs), key, quick, pg ?: "1"))
        }

    override suspend fun playerContent(flag: String, id: String, vipFlags: String?): PlayResult? =
        runGuarded("playerContent") {
            if (id.isBlank()) return@runGuarded null
            val flags = vipFlags?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            val raw = call("playerContent", timeoutFor(floors.playMs), flag, id, pyList(flags))
            PlayNormalize.normalizePlay(raw, flag, id, TAG, site.name)
        }

    override fun destroy() {
        runCatching { instance.callAttr("destroy") }
            .onFailure { TbLog.tag(TAG).d("%s destroy 异常：%s", site.name, it.message) }
        inited = false
    }

    // ---------------- 调用桥 ----------------

    /**
     * 调脚本方法并把返回值转成归一化层可消费的形态。
     *
     * 返回值桥：
     * - str → 原样传（[PlayNormalize] 要区分"裸地址文本"与"JSON 文本"）
     * - 其余（dict/list/数字/布尔）→ `base.loader.dumps` 转 JSON 文本 —— PyObject 是
     *   Java 代理视图，直接喂 [JarResultParser] 会走 bean 反射分支；JSON 文本命中的
     *   是 String 分支，与 jar 源同一条归一化链路。
     *
     * ## 超时为什么用 suspendCancellableCoroutine + 独立执行器
     * Chaquopy 的 `callAttr` 是同步阻塞调用，协程取消只能发生在挂起点，三条弯路都实测过：
     * 1. `withTimeoutOrNull { withContext(IO) { runCatching { … } } }`：体内无挂起点，
     *    取消无处传播，等 Python 跑满全程；
     * 2. `withTimeoutOrNull { async(IO) { … }.await() }`：async 是当前协程的子 Job，
     *    取消后结构化并发仍等子 Job 结束，阻塞调用不可取消；
     * 3. `coroutineScope { async { … }.await() }`：同上，且 coroutineScope 显式等子协程。
     *
     * 正解：把同步调用丢进 [PY_EXECUTOR] 独立线程，协程只 `suspendCancellableCoroutine`
     * 等结果。超时到点 → continuation 立即以 null 恢复（协程层面"不再等结果"），
     * 线程里的 Python 函数继续跑完（不可中断），由源健康统计 + 单站点串行兜底。
     */
    private suspend fun call(name: String, timeoutMs: Long, vararg args: Any?): Any? {
        // outcome 三层语义必须分清：
        // - null                       → withTimeoutOrNull 真超时（协程没等到结果）
        // - Result.success(pyObject?)  → 脚本正常返回（pyObject 为 null 即 Python None）
        // - Result.failure(throwable)  → 脚本抛异常，向上交给 [runGuarded]
        // 直接把 PyObject? 当 outcome 类型，Python 返回 None（如 init 无 return）会被
        // 误判成超时打日志，故包一层 Result。
        val outcome: Result<PyObject?>? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val task = Runnable {
                    // 线程里跑完再交还：结果或异常都走 continuation（取消后 resume 是 no-op）
                    cont.resumeWith(Result.success(runCatching { instance.callAttr(name, *args) }))
                }
                try {
                    PY_EXECUTOR.execute(task)
                } catch (t: Throwable) {
                    // 执行器拒绝任务（极端：已 shutdown）：按脚本异常向上交
                    cont.resumeWith(Result.success(Result.failure<PyObject?>(t)))
                }
            }
        }
        if (outcome == null) {
            TbLog.tag(TAG).w("%s %s 超时（%dms，脚本不会被中断）", site.name, name, timeoutMs)
            return null
        }
        // 线程里抛的异常（PyException 等）已从 suspendCancellableCoroutine 原样抛出，
        // 由 [runGuarded] 的 catch Throwable 交给 SourceRepository 记账。
        val py = outcome.getOrElse { throw it }
        return py?.let { stringify(it) }
    }

    /** PyObject → 归一化层入参（str 原样；其余走 JSON 桥） */
    private fun stringify(py: PyObject): Any? {
        val type = runCatching { py.type().toString() }.getOrDefault("")
        if (type.contains("str")) return py.toString()
        return runCatching { loader.callAttr("dumps", py).toString() }
            .onFailure { TbLog.tag(TAG).w(it, "%s 返回值无法序列化为 JSON", site.name) }
            .getOrNull()
    }

    /**
     * 结果归一化（与 JarSpider.normalizeResult 的详情放宽口径一致）。
     * @param requireName 名称是否硬性判据（详情路径传 false）
     * @param fallbackId 条目无 id 时的兜底 id（详情路径必传）
     */
    private fun normalize(
        raw: Any?,
        requireName: Boolean = true,
        fallbackId: String? = null,
    ): SpiderResult? {
        // 超时/空返回/脚本返回 None 统一按"无结果"处理：喂给 toResult 只会得到空壳
        // SpiderResult（classes/list 全空），UI 会显示"加载完但是空列表"而不是失败重试
        if (raw == null) return null
        val result = JarResultParser.toResult(raw, site.api, requireName, fallbackId)
        if (result == null) {
            TbLog.tag(TAG).d("%s 返回无法归一化：%s", site.name, JarResultParser.describe(raw))
        }
        return result
    }

    /** JSON 文本 → Python list（detailContent 的 ids、playerContent 的 vipFlags） */
    private fun pyList(items: List<String>): PyObject =
        loader.callAttr("loads", encodeList(items))

    /**
     * extend 串 → Python dict。
     *
     * 生态脚本普遍直接 `extend['cateId']` / `extend.get(...)`，无判空；
     * 空 extend 必须给**空 dict**，非法 JSON 退化为 `{"value": 原文}`（不丢信息），
     * 语义与 JarSpider.extendToMap 一致。
     */
    private fun pyExtend(extend: String?): PyObject {
        val json = if (extend.isNullOrBlank()) {
            "{}"
        } else {
            runCatching {
                val el = com.google.gson.JsonParser.parseString(extend)
                if (el.isJsonObject) el.toString() else null
            }.getOrNull() ?: com.google.gson.JsonObject().apply { addProperty("value", extend) }.toString()
        }
        return loader.callAttr("loads", json)
    }

    /** list → JSON 文本（转义交给 Gson，避免手拼转义出错） */
    private fun encodeList(items: List<String>): String =
        com.google.gson.Gson().toJson(items)

    /**
     * 计算某接口的内层超时：`max(接口下限, 站点声明 timeout + 余量)`。
     * 与 JarSpider.timeoutFor 同构：让「超时」发生在能正确记账的外层，内层只防脚本卡死。
     */
    private fun timeoutFor(floorMs: Long): Long {
        val siteMs = site.timeout?.takeIf { it > 0 }?.let { it * 1000L } ?: 0L
        return maxOf(floorMs, siteMs + SITE_TIMEOUT_MARGIN_MS)
    }

    // ---------------- 护栏 ----------------

    /**
     * 统一的调用护栏（与 JarSpider.runGuarded 同构）。
     * catch Throwable：Python 异常桥到宿主后混有 Error 系；协程取消仍向上抛。
     * 真实异常重新抛给 SourceRepository，由仓库层按 countHealth 统一记账，
     * 避免这里记账后返回 null 被误判成"源返回空"。
     */
    private suspend inline fun <T> runGuarded(
        tag: String,
        crossinline block: suspend () -> T?,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        TbLog.tag(TAG).w("%s %s 失败：%s", site.name, tag, msg)
        throw t
    }

    /**
     * `getDependence()` 声明的依赖核对：未预装只能告警不能装（Chaquopy 的 pip 是 build 期的）。
     * 不在 UI 弹错，debug 面板（logcat/app.log）可见。
     */
    private suspend fun warnMissingDependencies() {
        val raw = runCatching { call("getDependence", timeoutFor(floors.initMs)) }.getOrNull() ?: return
        val deps = runCatching {
            val el = com.google.gson.JsonParser.parseString(raw.toString())
            if (el.isJsonArray) el.asJsonArray.mapNotNull { it.asString } else emptyList()
        }.getOrDefault(emptyList())
        val missing = deps.map { it.trim() }.filter { it.isNotBlank() && it !in PREINSTALLED }
        if (missing.isNotEmpty()) {
            TbLog.tag(TAG).w(
                "%s 声明了未预装依赖 %s（pip 为构建期安装，运行时装不了）",
                site.name, missing.joinToString(),
            )
        }
    }

    companion object {
        private const val TAG = "PySpider"

        /** 站点声明 timeout 时的内层余量（与 JarSpider 同构） */
        private const val SITE_TIMEOUT_MARGIN_MS = 5_000L

        /** 构建期已 pip 预装的包（getDependence 命中即免告警） */
        private val PREINSTALLED = setOf(
            "requests", "urllib3", "lxml", "pycryptodome", "certifi",
            "charset-normalizer", "idna", "chardet",
        )

        /**
         * 同步 callAttr 的独立执行器。
         *
         * 不能用 Dispatchers.IO：IO 上跑同步阻塞调用时协程取消传不进去，
         * 结构化并发还要等它结束；这里要的是"协程层面可超时脱身、线程里继续跑完"。
         * 线程数取 8：SourceRepository 全局 Semaphore(4) 限流 + 单站点串行，
         * 8 足够覆盖聚合搜索的并发腿，也避免 27 个 py 源各起一线程的低内存盒子压力。
         */
        private val PY_EXECUTOR = Executors.newFixedThreadPool(8, object : ThreadFactory {
            private val n = AtomicInteger(1)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "py-spider-" + n.getAndIncrement()).apply { isDaemon = false }
        })
    }
}