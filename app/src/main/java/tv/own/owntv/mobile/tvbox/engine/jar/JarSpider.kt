package tv.own.owntv.mobile.tvbox.engine.jar

import tv.own.owntv.mobile.tvbox.util.TbLog

import android.content.Context
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Jar 型源（type 3，方案 5.3）。
 *
 * ## 加载链路
 * ```
 * config.spider ─下载/md5校验→ files/spiders/{md5}.jar
 *     → DexClassLoader(jarPath, odexDir, null, stubParent)
 *     → Class.forName("com.github.catvod.crawler." + api.removePrefix("csp_"))
 *     → newInstance()
 *     → 反射调五接口
 * ```
 *
 * ## 为什么要 parent-first
 * jar 里的实现类 `extends com.github.catvod.crawler.Spider`。若用 child-first（各生态自带的
 * Loader 默认行为），jar 会去加载自己 dex 里的 Spider 副本，于是 jar 与宿主是**两个不同的类型**，
 * 宿主拿到的实例 `as Spider` 会 ClassCastException；更致命的是 jar 传给宿主的 `bean.Result`
 * 也是 jar 私有的类，归一化层读不到字段。
 *
 * DexClassLoader 默认即 parent-first（ClassLoader.loadClass 先问 parent），只要把宿主
 * ClassLoader 作为 parent 传入，`com.github.catvod.**` 就统一落到桩上，类型全局唯一。
 *
 * ## 反射调用的工程要点
 * - jar 方法是同步阻塞的（内部含 OkHttp 同步调用），必须 `withContext(Dispatchers.IO)`
 * - 每个接口独立超时，超时按"该源本次无数据"处理，不计入源健康（避免误伤）
 * - 解包 `InvocationTargetException`，把 jar 内部的真实异常（NoSuchFieldError 等）透出，
 *   否则全是 null，日志里看不到根因
 * - detailContent 有 String / List 两个重载（TVBox 新协议用 List），都要试
 */
class JarSpider(
    private val site: SiteConfig,
    private val context: Context,
    /** jar 内 `com.github.catvod.crawler.Spider` 的实例（宿主桩的子类） */
    private val target: Any,
) : Spider {

    override val siteKey: String get() = site.key
    override val displayName: String get() = site.name

    @Volatile
    private var inited = false

    override val initialized: Boolean get() = inited

    private val cls: Class<*> = target.javaClass

    // ---------------- 五接口 ----------------

    override suspend fun init(ext: String?) {
        if (inited) return
        // 对齐 TVBoxOS JarLoader.getSpider 的顺序：先 initApi(SpiderApi) 再 init(Context, ext)。
        // 部分 jar override initApi 把 api 存进字段，之后在业务接口里调
        // api.log/multiReq/webParse —— 缺这一步运行期就是 NPE（且栈里看不出原因）。
        injectApi()
        // jar 的实现类可能只覆盖两参版，也可能只覆盖单参版，两个签名都要能命中。
        // 注意 getMethod 拿到的是"最派生的重载"，桩里两个都open，故按签名精确取不会落空。
        val two = findMethod("init", arrayOf(Context::class.java, String::class.java))
        val one = findMethod("init", arrayOf(Context::class.java))
        when {
            two != null -> invoke(
                "init", arrayOf(Context::class.java, String::class.java),
                timeoutFor(INIT_TIMEOUT_FLOOR_MS), context, ext ?: site.ext,
            )
            one != null -> invoke(
                "init", arrayOf(Context::class.java),
                timeoutFor(INIT_TIMEOUT_FLOOR_MS), context,
            )
            else -> {
                TbLog.tag(TAG).w( "%s 的实现类未暴露 init(Context) / init(Context,String)，跳过初始化", site.name)
            }
        }
        inited = true
        TbLog.tag(TAG).i( "%s 初始化完成（%s）", site.name, cls.name)
    }

    /**
     * 注入宿主能力集合（对齐 TVBoxOS `spider.initApi(new SpiderApi())`）。
     *
     * 反射调用而非直接强调：target 是 jar 里的代理/实现类，签名对不上时
     * （例如老 jar 没有 initApi）静默跳过即可，不影响其余接口。
     */
    private fun injectApi() {
        runCatching {
            val m = findMethod("initApi", arrayOf(com.github.catvod.crawler.SpiderApi::class.java))
            m?.isAccessible = true
            m?.invoke(target, com.github.catvod.crawler.SpiderApi())
        }.onFailure {
            TbLog.tag(TAG).d( "%s initApi 注入失败（不影响其余接口）：%s", site.name, it.toString())
        }
    }

    override suspend fun homeContent(filter: Boolean): SpiderResult? = runGuarded("homeContent") {
        val raw = invoke(
            "homeContent", arrayOf(Boolean::class.javaPrimitiveType!!),
            timeoutFor(HOME_TIMEOUT_FLOOR_MS), filter,
        )
        normalizeResult(raw, filter)
    }

    /**
     * 首页推荐兜底（对齐 TVBoxOS `getHomeRecList` → `sp.homeVideoContent()`）。
     *
     * 部分源不在 `homeContent` 里给推荐，而是单独走 `homeVideoContent`。
     * `homeContent` 返回空时用它填首页，能救回一批"能看但首页空白"的源。
     */
    override suspend fun homeVideoContent(): SpiderResult? = runGuarded("homeVideoContent") {
        val raw = invoke("homeVideoContent", arrayOf(), timeoutFor(HOME_TIMEOUT_FLOOR_MS))
        normalizeResult(raw, false)
    }

    override suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: String?,
    ): SpiderResult? = runGuarded("categoryContent") {
        // 生态里第 4 参有 HashMap 与 String 两种形态（加固壳用的是 HashMap），
        // 宿主桩两个重载都声明了，jar 调哪个都能命中；这里对应地两个都试。
        val raw = tryInvoke(
            "categoryContent",
            CATEGORY_HASHMAP_TYPES,
            timeoutFor(CATEGORY_TIMEOUT_FLOOR_MS),
            arrayOf<Any?>(tid, pg.ifBlank { "1" }, filter, extendToMap(extend)),
        ) ?: invoke(
            "categoryContent",
            arrayOf(String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!, String::class.java),
            timeoutFor(CATEGORY_TIMEOUT_FLOOR_MS),
            tid, pg.ifBlank { "1" }, filter, extend,
        )
        normalizeResult(raw, filter)
    }

    /**
     * 详情。TVBox 协议存在两个重载：
     * - 旧：`detailContent(String ids)`，逗号分隔
     * - 新：`detailContent(List<String> ids)` —— **加固壳走的就是这个**
     *
     * ## 为什么恒先试 List（对齐 TVBoxOS `getDetail`）
     * TVBoxOS 永远只传 `List<String> ids`（`ids.add(id); sp.detailContent(ids)`）。
     * 生态主流（加固壳 / 新采集 jar）只实现 List 重载，先试 String 会**白白多打一次
     * 走宿主桩默认实现（返回 null）的无效调用** —— 对 12~60s 级的慢源详情，
     * 这一次浪费可能正好把耗时顶过内层超时（曾表现为「没有返回播放地址」）。
     * 老 jar 只实现 String 重载时 List 调用返回 null，仍会正确回退。
     */
    override suspend fun detailContent(ids: String): SpiderResult? = runGuarded("detailContent") {
        val idList = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (idList.isEmpty()) return@runGuarded null

        val raw = tryInvoke(
            "detailContent",
            arrayOf(List::class.java),
            timeoutFor(DETAIL_TIMEOUT_FLOOR_MS),
            arrayOf<Any?>(idList),
        ) ?: invoke(
            "detailContent",
            arrayOf(String::class.java),
            timeoutFor(DETAIL_TIMEOUT_FLOOR_MS),
            ids,
        )

        // 详情是最容易"请求成功但结果为空"的接口（直播类源、索引类源尤其如此）。
        // 留一行原始预览，才能区分「jar 返回 null / 返回了但字段名不同 / 条目被过滤」。
        TbLog.tag(TAG).d("%s 详情原始返回：%s", site.name, JarResultParser.preview(raw))

        // 两条必须放宽的判据（真机实证：虎牙/斗鱼/哔哩直播源同时踩中）：
        // 1. requireName=false —— 直播/索引类源的详情常常只给线路不给名字
        // 2. fallbackId=请求 id —— 它们的详情返回体里**没有 vod_id**，
        //    若把 id 当硬性判据，条目会被整体丢弃 → UI 报「未返回详情数据」
        normalizeResult(raw, filter = false, requireName = false, fallbackId = idList.first())
    }

    override suspend fun searchContent(key: String, quick: Boolean, pg: String?): SpiderResult? =
        runGuarded("searchContent") {
            if (key.isBlank()) return@runGuarded null
            // 优先带翻页的三参重载，部分 jar 只有两参
            val raw = tryInvoke(
                "searchContent",
                arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!, String::class.java),
                timeoutFor(SEARCH_TIMEOUT_FLOOR_MS),
                arrayOf<Any?>(key, quick, pg ?: "1"),
            ) ?: invoke(
                "searchContent",
                arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!),
                timeoutFor(SEARCH_TIMEOUT_FLOOR_MS),
                key, quick,
            )
            normalizeResult(raw, false)
        }

    override suspend fun playerContent(flag: String, id: String, vipFlags: String?): PlayResult? {
        // 记录"当前播放源"：spider 拼的本地代理地址不带 siteKey
        // （实证：`127.0.0.1:9978/proxy?do=hxq&url=...`），本地代理分发时只能靠它定位 spider 实例
        // （对齐 TVBoxOS 的 currentPlaySourceKey）。
        //
        // ## 必须在调用 jar 之前登记（真机实证 2026-11-10，「糯米┃秒播」）
        // 加固壳在 playerContent **内部**就会先回调本地代理（先 `do=ck` 取 cookie，
        // 再拼 `do=m3u8`）。此前在 `.also {}` 里等 jar 返回后才登记，代理收到
        // `do=ck` 时既无 siteKey 也无当前播放源，只能回 502；壳随即判定本地代理不可用，
        // 最终返回 `http://127.0.0.1:-1/proxy?do=m3u8&url=...` 这种死链 ——
        // 表现正是「列表能解析、点播放一直失败」。
        JarProxyHub.onPlay(site.key)
        return runGuarded("playerContent") {
            if (id.isBlank()) return@runGuarded null
            // 第 3 参 vipFlags 有 String / List 两种形态，加固壳用的是 List。
            // 宿主桩两个重载都声明了，这里对应地都试。
            val raw = tryInvoke(
                "playerContent",
                arrayOf(String::class.java, String::class.java, List::class.java),
                timeoutFor(PLAY_TIMEOUT_FLOOR_MS),
                arrayOf<Any?>(flag, id, vipFlags?.let { listOf(it) }),
            ) ?: invoke(
                "playerContent",
                arrayOf(String::class.java, String::class.java, String::class.java),
                timeoutFor(PLAY_TIMEOUT_FLOOR_MS),
                flag, id, vipFlags,
            )
            normalizePlay(raw, flag, id)
        }
    }

    override fun destroy() {
        runCatching {
            val m = findMethod("destroy", arrayOf())
            m?.invoke(target)
        }.onFailure { TbLog.tag(TAG).d( "%s destroy 异常：%s", site.name, it.message) }
        inited = false
    }

    // ---------------- 归一化 ----------------

    private fun normalizeResult(
        raw: Any?,
        filter: Boolean,
        requireName: Boolean = true,
        fallbackId: String? = null,
    ): SpiderResult? {
        // 部分 jar 的 category/search 直接返回 List<Movie>（不包 Result）
        if (raw is List<*>) {
            val movies = raw.mapNotNull { JarResultParser.toMovie(it, site.api, requireName, fallbackId) }
            return if (movies.isEmpty()) null else SpiderResult(list = movies, total = movies.size)
        }
        val result = JarResultParser.toResult(raw, site.api, requireName, fallbackId) ?: run {
            TbLog.tag(TAG).d( "%s 返回无法归一化：%s", site.name, JarResultParser.describe(raw))
            return null
        }
        // filter=true 时 jar 用 filterDef 给筛选树，透传给上层。
        // 判空用 isEmpty 而不是 == null：filters 是非空 Map，恒不为 null，
        // 写成 == null 会让这个分支永远不生效，筛选树永远拿不到。
        return if (filter && result.filters.isEmpty()) {
            result.copy(filters = JarResultParser.toFilters(raw))
        } else {
            result
        }
    }

    /**
     * 播放结果归一化。
     *
     * ## ⚠️ 这里有个必须小心的坑：新桩下返回值恒为 `String`
     *
     * 桩改成 `playerContent(...): String?` 之后，**所有** jar 返回都是字符串，
     * 于是"裸流地址"和"JSON 文本"这两件事无法再靠 Kotlin 类型区分（都是 String）。
     * 判据必须是**形态**而不是内容（JSON 以 `{`/`[` 开头），否则整段 JSON 会被当 URL
     * 塞给 ExoPlayer（表现为"播放一直转圈/无法播放"，且日志里看不出任何异常）。
     *
     * 具体逻辑抽在 [tv.own.owntv.mobile.tvbox.engine.PlayNormalize]，与 Py 引擎共用一份 ——
     * 密文解码、parse 形态判据都是真机踩坑换来的，两处各抄一份必然漂移。
     */
    private fun normalizePlay(raw: Any?, flag: String, id: String): PlayResult? =
        tv.own.owntv.mobile.tvbox.engine.PlayNormalize.normalizePlay(raw, flag, id, TAG, site.name)

    // ---------------- 反射 ----------------

    private fun findMethod(name: String, types: Array<Class<*>>): Method? =
        runCatching { cls.getMethod(name, *types) }.getOrNull()

    /**
     * 反射调用（阻塞，放 IO 线程 + 独立超时）。
     * 超时返回 null 而非抛异常：避免 CancellationException 被上层当成源故障统计。
     *
     * @param timeoutMs 本接口的内层超时。由 [timeoutFor] 按接口分级 + 站点 timeout 计算，
     *   **刻意大于** SourceRepository 对应外层默认 —— 让外层（认站点级 timeout）先收口记账，
     *   内层只兜"jar 卡死永不返回"的极端情况。
     */
    private suspend fun invoke(
        name: String,
        types: Array<Class<*>>,
        timeoutMs: Long,
        vararg args: Any?,
    ): Any? {
        val method = findMethod(name, types)
        if (method == null) {
            TbLog.tag(TAG).d( "%s 无方法 %s%s（该源不支持此接口）", site.name, name, types.joinToString(", ", "(", ")"))
            return null
        }
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    method.invoke(target, *args)
                } catch (e: InvocationTargetException) {
                    // 解包 jar 内部真实异常，否则根因（如 NoSuchFieldError）被 InvocationTargetException 吃掉
                    throw e.targetException ?: e
                }
            }
        }
    }

    /** 尝试调用；方法不存在或调用失败返回 null，交由调用方退到备用重载 */
    private suspend fun tryInvoke(
        name: String,
        types: Array<Class<*>>,
        timeoutMs: Long,
        args: Array<Any?>,
    ): Any? = runCatching { invoke(name, types, timeoutMs, *args) }.getOrNull()

    /**
     * 统一的调用护栏。
     *
     * ## 为什么 catch Throwable 而不是 Exception
     * jar 里缺桩方法/字段时抛的是 **Error** 而非 Exception：
     * `NoSuchMethodError` / `NoSuchFieldError` / `ClassCastException`(继承自 RuntimeException) 等。
     * 真机实测 `spider.Nmvod` 因桩缺 `init(Context)` 直接 `NoSuchMethodError` 崩掉整个 App，
     * 而这类错误走不到上层 try/catch(Exception)。故这里必须兜 Throwable。
     *
     * 只吞 `Throwable` 里的 Error 类，协程取消（CancellationException）仍向上抛。
     */
    private suspend inline fun <T> runGuarded(
        tag: String,
        crossinline block: suspend () -> T?,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // ## 必须抛给 SourceRepository，而不是在这里记账后返回 null
        // 仓库看到 null 会走 TIMEOUT_MARKER → recordEmpty，把真实故障记成"源返回空"，
        // 自动标记永远攒不满连续失败；同时它也不知道本次调用是否 countHealth=false
        //（首页兜底/下一集预取不该连坐）。仓库层的 catch(Throwable) 会统一记账。
        val msg = t.message ?: t.javaClass.simpleName
        TbLog.tag(TAG).w("%s %s 失败：%s", site.name, tag, msg)
        throw t
    }

    /**
     * 计算某接口的内层超时：`max(接口下限, 站点声明 timeout + 余量)`。
     *
     * 站点没声明 timeout 时用接口下限（此时 SourceRepository 的外层默认更短，
     * 由外层先决定并记账）；声明了则内层始终比外层多 5s，
     * 保证「超时」这件事发生在能正确记账的那一层，而不是被内层提前吞成「返回空」。
     */
    private fun timeoutFor(floorMs: Long): Long {
        val siteMs = site.timeout?.takeIf { it > 0 }?.let { it * 1000L } ?: 0L
        return maxOf(floorMs, siteMs + SITE_TIMEOUT_MARGIN_MS)
    }

    companion object {
        private const val TAG = "JarSpider"

        /**
         * 各接口的内层超时**下限**（ms）。
         *
         * ## 为什么曾经是统一的 12s，以及它造成的 bug
         * 原来所有 jar 接口共用 `CALL_TIMEOUT_MS = 12_000`，内层比 SourceRepository 的
         * 站点级超时（`site.timeout*1000`，至臻4K 配置为 60s）**先到点**，
         * 慢源详情被提前掐断 → jar 降级返回「有影片字段、vod_play_url 为空」→
         * UI 显示「没有返回播放地址」（部分作品重进/命中缓存后又偶发成功）。
         *
         * 现在按下限分级（对齐 TVBoxOS：详情 30s 保底、播放用站点 timeout）：
         * - init / detail 35s：壳引导与详情解析是两条最长的路径
         * - home / category 20s：与 SourceRepository 默认 15s 保持 5s 余量
         * - search 13s：外层搜索默认 8s
         * - player 20s：外层播放默认 15s
         */
        private const val INIT_TIMEOUT_FLOOR_MS = 35_000L
        private const val HOME_TIMEOUT_FLOOR_MS = 20_000L
        private const val CATEGORY_TIMEOUT_FLOOR_MS = 20_000L
        private const val DETAIL_TIMEOUT_FLOOR_MS = 35_000L
        private const val SEARCH_TIMEOUT_FLOOR_MS = 13_000L
        private const val PLAY_TIMEOUT_FLOOR_MS = 20_000L

        /** 站点声明 timeout 时的内层余量：外层先收口，内层只防 jar 卡死 */
        private const val SITE_TIMEOUT_MARGIN_MS = 5_000L

        /**
         * `categoryContent` 的 HashMap 形态签名。
         *
         * dexdump 实测加固壳的调用描述符是
         * `categoryContent(Ljava/lang/String;Ljava/lang/String;ZLjava/util/HashMap;)Ljava/lang/String;`
         * —— 第 4 参是 `java.util.HashMap`，不是 FongMi 早期的 `String extend`。
         * 宿主桩两个重载都声明了，这里对应地先试 HashMap。
         */
        private val CATEGORY_HASHMAP_TYPES: Array<Class<*>> = arrayOf(
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            HashMap::class.java,
        )

        /**
         * `extend` 形态转换：json 串 → HashMap；非法 JSON 时退化为单键 map（不丢信息）。
         *
         * ## ⚠️ 恒不返回 null —— 生态 jar 会在首条指令解引用 extend
         *
         * 真机排查「💖┃至臻┃4K💖」（csp_PanWebShare）：首页有推荐，电影/连续剧等子类
         * 全部"暂无数据"。dexdump 实测 `categoryContent` 的**第一条指令**就是
         * `extend.get("cateId")`，无任何判空：
         * ```
         * 0000: const-string v0, "cateId"
         * 0002: invoke-virtual {v14, v0}, Ljava/util/HashMap;.get:(Ljava/lang/Object;)...
         * ```
         * 而 TVBoxOS 传给 categoryContent 的恒是 `sortData.filterSelect`（非 null 的
         * `HashMap`，无筛选时为空 map）；本方法早前在 extend 为空时返回 null → jar 内 NPE
         * → tryInvoke 静默退到 String 重载 → 桩基类默认返回 null → 被上层误报"暂无数据"。
         *
         * 故空 extend 必须给**空 map** 而不是 null：`extend.get(...)` 得 null → 该维度
         * 不拼进 URL，语义与 TVBoxOS 完全一致。
         */
        internal fun extendToMap(extend: String?): HashMap<String, Any?> {
            if (extend.isNullOrBlank()) return HashMap()
            return runCatching {
                val parsed = com.google.gson.JsonParser.parseString(extend).takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: return HashMap<String, Any?>().apply { put("extend", extend) }
                HashMap<String, Any?>().apply {
                    parsed.entrySet().forEach { e ->
                        val v = e.value
                        put(
                            e.key,
                            if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else v.toString(),
                        )
                    }
                }
            }.getOrElse { HashMap<String, Any?>().apply { put("extend", extend) } }
        }
    }
}