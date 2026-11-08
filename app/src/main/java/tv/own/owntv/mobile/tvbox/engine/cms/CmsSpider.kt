package tv.own.owntv.mobile.tvbox.engine.cms

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.CipherUrlDecoder
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.EngineKind
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.PlayUrlCodec
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.StripHtml
import tv.own.owntv.mobile.tvbox.util.TextUtil
import tv.own.owntv.mobile.tvbox.util.toHeaderMap
import tv.own.owntv.mobile.tvbox.util.toJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * CMS 接口型源（type 0/1/4，方案 5.5）。
 *
 * 实现要点：
 * - 无需脚本，SourceRepository 直接映射苹果 CMS 协议
 * - 详情用 `ac=detail&ids=`，空结果自动回退 `ac=videolist`
 * - 单站点串行：CMS 接口型源常无状态，但为防同一源并发打爆站方，加实例级互斥
 * - ext 形如 "token|host" 或直接放自定义参数，按 key=value 追加到 query
 */
class CmsSpider(
    private val site: SiteConfig,
    private val net: NetApi,
) : Spider {

    override val siteKey: String get() = site.key
    override val displayName: String get() = site.name

    /**
     * CMS 源没有 native 初始化成本，但 **init() 仍需执行一次**：
     * 站点级 `header`/`ext` 只在 init 里解析（2026-10-23 真机缺陷 8）。
     * 之前恒为 true 导致 extraHeaders 永远为空，Referer/UA/Cookie 全失效。
     */
    @Volatile
    private var inited = false
    override val initialized: Boolean get() = inited

    private val mutex = Mutex()

    /** 站点级请求头（配置 header + ext 解析出的 header） */
    private var extraHeaders: Map<String, String> = emptyMap()

    /** ext 里的附加 query 参数，如 "ac=list&token=abc" 或 "token=abc" */
    private var extraQuery: String = ""

    override suspend fun init(ext: String?) {
        mutex.withLock {
            if (inited) return@withLock
            val parsed = parseExt(ext ?: site.ext)
            extraHeaders = parsed.headers + site.header?.toHeaderMap().orEmpty()
            extraQuery = parsed.query
            inited = true
        }
        TbLog.tag(TAG).d( "%s 初始化完成, api=%s", site.name, site.api)
    }

    override suspend fun homeContent(filter: Boolean): SpiderResult? = runGuarded {
        // 首页要拿两样东西，而它们不在同一个响应里：
        //   分类树只有 ac=list 返回（ac=videolist/detail 不带 class 节点）；
        //   ac=list 的 list 节点又只有 vod_id/vod_name/type_id，没有 vod_pic ——
        //   拿它当首页数据，封面必然全空白。所以两次请求各取所需。
        val classBody = request(buildQuery(mapOf("ac" to "list", "pg" to "1")))
        val classPage = CmsParser.parsePage(classBody)
        if (!classPage.ok && classPage.movies.isEmpty()) {
            // 不记这段就永远只能看到"源返回空"，分不清是解析器没吃下、
            // 还是站方回了门户页/验证码页 —— 两者的修法完全相反
            TbLog.tag(TAG).w(
                "%s ac=list 无数据：%s；api=%s body(%d字节)=%s",
                site.name, classPage.message, site.api, classBody.length,
                classBody.take(200).replace('\n', ' '),
            )
            return@runGuarded null
        }
        val listPage = runCatching {
            CmsParser.parsePage(request(buildQuery(mapOf("ac" to listAc, "pg" to "1"))))
        }.getOrNull()?.takeIf { it.movies.isNotEmpty() } ?: classPage
        SpiderResult(
            classes = classPage.classes.map { ClassInfo(it.typeId, it.typeName) },
            // CMS 的筛选树挂在首页/分类响应里，不需要额外请求；
            // 不同站把 filter 放在 ac=list 或 ac=videolist 哪一边都有，取非空的那个
            filters = listPage.filters.ifEmpty { classPage.filters },
            list = listPage.movies.map { it.toMovie() },
            page = listPage.page,
            pagecount = listPage.pageCount,
            total = listPage.total,
        )
    }

    override suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: String?,
    ): SpiderResult? = runGuarded {
        val pageNum = pg.ifBlank { "1" }
        // extend 来自筛选面板，形如 {"year":"2024"} 或 "2024"
        val extra = parseFilterExtend(extend)
        val query = buildQuery(
            buildMap {
                put("ac", listAc)
                // 必须是 t：苹果CMS 不认 tid，带上 tid 等于没带，
                // 服务端返回未过滤的第一页，表现为"所有子分类内容一模一样"
                put("t", tid)
                put("pg", pageNum)
                putAll(extra)
            }
        )
        val body = request(query)
        val page = CmsParser.parsePage(body)
        if (!page.ok && page.movies.isEmpty()) return@runGuarded null

        SpiderResult(
            classes = page.classes.map { ClassInfo(it.typeId, it.typeName) },
            filters = page.filters,
            list = page.movies.map { it.toMovie() },
            page = page.page,
            pagecount = page.pageCount,
            total = page.total,
        )
    }

    override suspend fun detailContent(ids: String): SpiderResult? = runGuarded {
        val idList = ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (idList.isEmpty()) return@runGuarded null

        val movies = idList.mapNotNull { id ->
            fetchDetailOne(id) ?: return@mapNotNull null
        }
        if (movies.isEmpty()) return@runGuarded null

        SpiderResult(
            list = movies.map { it.toMovie() },
            page = 1,
            pagecount = 1,
            total = movies.size,
        )
    }

    /** 详情：标准 ac=detail，空结果回退 ac=videolist（方案 5.5） */
    private suspend fun fetchDetailOne(id: String): CmsParser.CmsMovie? {
        var body = request(buildQuery(mapOf("ac" to "detail", "ids" to id)))
        var movie = CmsParser.parseDetail(body) ?: CmsParser.parseEmbedded(body)

        if (movie == null || movie.playUrl.isNullOrBlank()) {
            TbLog.tag(TAG).d( "%s ac=detail 无播放地址，回退 ac=videolist", site.name)
            body = request(buildQuery(mapOf("ac" to "videolist", "ids" to id)))
            movie = CmsParser.parseDetail(body) ?: CmsParser.parseEmbedded(body)
        }
        return movie
    }

    override suspend fun searchContent(key: String, quick: Boolean, pg: String?): SpiderResult? = runGuarded {
        if (key.isBlank()) return@runGuarded null
        val query = buildQuery(
            buildMap {
                put("ac", "detail")
                put("wd", key)
                pg?.takeIf { it.isNotBlank() && it != "1" }?.let { put("pg", it) }
            }
        )
        var body = request(query)
        var page = CmsParser.parsePage(body)
        // 部分老站搜索参数为 ac=videolist
        if (page.movies.isEmpty()) {
            TbLog.tag(TAG).d( "%s 搜索 ac=detail 空，回退 ac=videolist", site.name)
            body = request(buildQuery(buildMap {
                put("ac", "videolist")
                put("wd", key)
                pg?.takeIf { it.isNotBlank() && it != "1" }?.let { put("pg", it) }
            }))
            page = CmsParser.parsePage(body)
        }
        if (page.movies.isEmpty()) return@runGuarded null

        SpiderResult(
            list = page.movies.map { it.toMovie() },
            page = page.page,
            pagecount = page.pageCount,
            total = page.total,
        )
    }

    override suspend fun playerContent(flag: String, id: String, vipFlags: String?): PlayResult? = runGuarded {
        if (id.isBlank()) return@runGuarded null

        // 已经是直链（部分源 vod_play_url 直接给 m3u8）
        if (TextUtil.looksLikeStream(id)) {
            return@runGuarded PlayResult(
                parse = 0,
                url = id,
                header = buildHeader(),
                flag = flag,
            )
        }

        // 优先试 ac=detail&ids= 拿该集直链
        val body = request(buildQuery(mapOf("ac" to "detail", "ids" to id)))
        val movie = CmsParser.parseDetail(body) ?: CmsParser.parseEmbedded(body)
        val direct = extractDirectUrl(movie?.playUrl, flag, id)

        if (direct != null) {
            // **密文解码**（真机实测的必备步骤，见 [CipherUrlDecoder] 类注释）。
            // 「快播」系源的 vod_play_url 里塞的是 Base64 密文（`eyJ...` = `{"url":...}`），
            // 不解开就会把一串Base64 当网页丢给 WebView 嗅探，12 秒后必然失败。
            val decoded = CipherUrlDecoder.decode(direct)
            if (decoded != null) {
                return@runGuarded PlayResult(
                    parse = if (TextUtil.looksLikeStream(decoded)) 0 else 1,
                    url = decoded,
                    header = buildHeader(),
                    flag = flag,
                )
            }

            return@runGuarded PlayResult(
                parse = if (TextUtil.looksLikeStream(direct)) 0 else 1,
                url = direct,
                header = buildHeader(),
                flag = flag,
            )
        }

        // id 本身可能就是密文（有的源把地址加密后直接塞进 vod_play_url 的分集段）
        CipherUrlDecoder.decode(id)?.let { decodedId ->
            return@runGuarded PlayResult(
                parse = if (TextUtil.looksLikeStream(decodedId)) 0 else 1,
                url = decodedId,
                header = buildHeader(),
                flag = flag,
            )
        }

        // 拿不到直链：交由嗅探处理（parse=1）
        PlayResult(
            parse = 1,
            url = id,
            header = buildHeader(),
            flag = flag,
        )
    }

    /** 从 playUrl 中挑出匹配线路/集号的直链 */
    private fun extractDirectUrl(playUrl: String?, flag: String, episodeId: String): String? {
        if (playUrl.isNullOrBlank()) return null
        val lines = PlayUrlCodec.parse(playUrl, null)
        // 1) 线路名匹配
        val byFlag = lines.firstOrNull { it.name == flag } ?: lines.firstOrNull()
        val target = byFlag?.episodes?.firstOrNull { it.url == episodeId || it.name == episodeId }
            ?: byFlag?.episodes?.firstOrNull()
        return target?.url
    }

    override fun destroy() {
        // CMS 源无实例资源
    }

    // ---------------- 内部工具 ----------------

    /** 统一请求：加锁（单站点串行）+ 错误抛出（由 runGuarded 计入健康） */
    private suspend fun request(query: String): String {
        val url = buildUrl(query)
        mutex.withLock {
            val resp = net.getText(url, site.key, extraHeaders.takeIf { it.isNotEmpty() })
            if (!resp.isSuccess) {
                throw CmsException("${site.name} 请求失败：${resp.error ?: "HTTP ${resp.code}"}")
            }
            val body = CmsParser.stripHtmlWrapper(resp.body)
                ?: throw CmsException("${site.name} 返回的是网页而非接口数据")
            return body
        }
    }

    /**
     * 拼接最终请求 URL。
     *
     * ## 为什么不能直接"$base?$query"
     * 部分配置的 `api` 自带 query 且写死了接口动作，真实样本：
     * ```
     * "api": "https://hhzyapi.com/api.php/provide/vod/?ac=list"   // 豪华┃资源
     * ```
     * 直接拼会得到 `?ac=list&ac=detail&ids=123` —— 同一参数出现两次。
     * PHP 的 $_GET 解析取**最后一个**，所以多数时候"碰巧能用"，但一旦
     * 站方换解析方式或加 WAF 校验就会静默走错接口，且极难排查。
     *
     * 做法：把 api 自带的 query 解析成键值对，用本次调用的参数**覆盖**同名项，
     * 其余（token 之类的固定参数）原样保留。
     */
    private fun buildUrl(query: String): String {
        val base = site.api.trim()
        val qIdx = base.indexOf('?')
        val rawBase = (if (qIdx >= 0) base.substring(0, qIdx) else base)
            // 剥掉末尾斜杠：部分配置写 "…/vod/?ac=list"，剥掉 query 后会留下
            // "…/vod/"，再拼 '?' 得到 "…/vod/?ac=..."。多数 PHP 路由能容忍，
            // 但路径型站方（WAF / 伪静态）会直接 404，属静默踩坑。
            .trimEnd('/')
        // base 自带的 query 保留顺序，值不动（可能是 token）
        val basePairs = LinkedHashMap<String, String>()
        if (qIdx >= 0) {
            base.substring(qIdx + 1).split('&').forEach { seg ->
                if (seg.isBlank()) return@forEach
                val eq = seg.indexOf('=')
                if (eq <= 0) basePairs[seg] = "" else basePairs[seg.substring(0, eq)] = seg.substring(eq + 1)
            }
        }
        // 本次调用的参数覆盖同名项（值已 URL 编码，不再二次编码）
        query.split('&').forEach { seg ->
            if (seg.isBlank()) return@forEach
            val eq = seg.indexOf('=')
            if (eq <= 0) basePairs.remove(seg) else basePairs[seg.substring(0, eq)] = seg.substring(eq + 1)
        }
        // ext 里的附加 query 同理覆盖
        if (extraQuery.isNotBlank()) {
            extraQuery.split('&').forEach { seg ->
                if (seg.isBlank()) return@forEach
                val eq = seg.indexOf('=')
                if (eq <= 0) basePairs.remove(seg) else basePairs[seg.substring(0, eq)] = seg.substring(eq + 1)
            }
        }
        if (basePairs.isEmpty()) return rawBase
        return rawBase + "?" + basePairs.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }

    /**
     * 列表动作用哪个 `ac`。
     *
     * type=0 是老 XML 接口（`at/xml/`），认 `ac=videolist`；type=1/4 是 JSON 接口，
     * 认 `ac=detail`。选错不是报错而是**静默返回无封面的精简载荷** ——
     * 实测 360zy 用 ac=videolist 拿到的就是 ac=list 那种没有 vod_pic 的 20 条，
     * 用 ac=detail 才拿到图。与参考项目 TVBoxOS SourceViewModel 的取值一致。
     */
    private val listAc: String get() = if (site.type == 0) "videolist" else "detail"

    private fun buildHeader(): JsonObject? {
        val map = buildMap {
            site.userAgent?.let { put("User-Agent", it) }
            putAll(extraHeaders)
        }
        if (map.isEmpty()) return null
        return map.toJsonObject()
    }

    /** ext 解析：支持 "a=b&c=d"（query）与 "{\"header\":{...}}"（请求头） */
    private fun parseExt(ext: String?): ParsedExt {
        if (ext.isNullOrBlank()) return ParsedExt()
        val s = ext.trim()
        // JSON 形式
        if (s.startsWith("{")) {
            return runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(s).let {
                    it as? kotlinx.serialization.json.JsonObject
                }
                val headers = obj?.get("header")
                    ?.let { h -> h as? kotlinx.serialization.json.JsonObject }
                    ?.mapNotNull { (k, v) ->
                        (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it }
                    }?.toMap().orEmpty()
                val query = obj?.get("params")
                    ?.let { p -> p as? kotlinx.serialization.json.JsonObject }
                    ?.mapNotNull { (k, v) ->
                        (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { "$k=$it" }
                    }?.joinToString("&").orEmpty()
                ParsedExt(headers, query)
            }.getOrElse { ParsedExt() }
        }
        // query 形式：过滤掉不像 query 的片段（如 https:// 开头）
        val query = s.split("&")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") && !it.startsWith("http") }
            .joinToString("&")
        return ParsedExt(emptyMap(), query)
    }

    /** 筛选 extend：JSON 形式的键值对，或裸字符串 */
    private fun parseFilterExtend(extend: String?): Map<String, String> {
        if (extend.isNullOrBlank()) return emptyMap()
        val s = extend.trim()
        if (!s.startsWith("{")) {
            // 裸值按 year 处理（CMS 通用筛选维度）
            return mapOf("year" to s)
        }
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(s)
                as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
            obj.filterValues { v ->
                v is kotlinx.serialization.json.JsonPrimitive &&
                    v.content.isNotBlank() && v.content != "全部" && v.content != "all"
            }.mapNotNull { (k, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * 统一异常边界：只做日志，**不吞异常、不在这里记账**。
     *
     * ## 为什么改成"抛出"而不是"记失败后返回 null"
     * 此前这里 catch 后返回 null，而 SourceRepository.call 把"返回 null"一律当作
     * 哨兵 TIMEOUT_MARKER（= 源正常但无数据）。后果是**真实故障被伪装成"接口正常
     * 但暂无数据"**——真机实测 4k.json 的「爱坤┃资源」正是如此：域名 TLS 握手
     * 失败，属网络故障，UI 却显示"接口正常但该分类暂无数据"，排查方向被完全带偏。
     *
     * 现在异常向上抛，由 SourceRepository 的 catch(Throwable) 统一 recordFailure，
     * 语义边界恢复为：
     *   - 抛异常 → 故障（计入失败/冷却，UI 显示真实错误）
     *   - 返回 null → 源确实响应了但内容为空（recordEmpty，不计故障）
     */
    private suspend inline fun <T> runGuarded(crossinline block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TbLog.tag(TAG).w( "%s 调用失败: %s", site.name, e.message)
        throw e
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private data class ParsedExt(
        val headers: Map<String, String> = emptyMap(),
        val query: String = "",
    )

    class CmsException(msg: String) : Exception(msg)

    companion object {
        private const val TAG = "CmsSpider"

        fun CmsParser.CmsMovie.toMovie(): Movie = Movie(
            id = id,
            name = name,
            pic = pic?.let { TextUtil.absoluteUrl(it, null) },
            remark = remark,
            year = year,
            area = area,
            typeName = typeName,
            content = content?.let { StripHtml.strip(it) },
            actor = actor,
            director = director,
            playFrom = playFrom,
            playUrl = PlayUrlCodec.sanitize(playUrl),
            extra = extra,
        )
    }
}

/** CMS 引擎门面 */
class CmsEngine(
    private val net: NetApi,
) : Engine {

    override val kind: EngineKind = EngineKind.CMS

    private val instances = java.util.concurrent.ConcurrentHashMap<String, CmsSpider>()

    /**
     * **api 后缀优先于 type**（方案 §4.2）。
     *
     * 生态里存在 `type` 写错但 `api` 是 `.js` 的配置（如 type=0 / type=1 配 drpy 运行时）。
     * CMS 引擎排在所有引擎最前面，若这里只按 type 判，`type=0 + api=x.js` 会被 CMS 抢走，
     * 表现为"站点显示可用、点开必然解析失败"。因此显式把脚本型站点让给 JS 引擎。
     */
    override fun supports(site: SiteConfig): Boolean =
        site.type in setOf(0, 1, 4) && site.api.isNotBlank() && !site.apiIsJs

    override suspend fun obtain(site: SiteConfig): Spider =
        instances.getOrPut(site.key) { CmsSpider(site, net) }

    override fun isCooling(siteKey: String): Boolean = SourceHealth.isCooling(siteKey)

    override fun invalidateAll() {
        instances.values.forEach { runCatching { it.destroy() } }
        instances.clear()
    }
}
