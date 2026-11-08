package tv.own.owntv.mobile.tvbox.engine

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.config.model.ParseConfig
import tv.own.owntv.mobile.tvbox.net.NetApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 前缀解析器消费链路（P2：解析 / 超级解析；P3：修正类型语义 + WebView JS 解析）。
 *
 * ## 为什么需要它
 * TVBoxOS 的 `playerContent` 返回的 `parse` 字段不只是 0/1：
 * - `0` 直连（已在 `PlayViewModel.resolveAndPlay` 处理）
 * - `1` 嗅探（已走 `SniffEngine`）
 * - `≥2` 或 `flag` 命中 `parses` 数组某项 → 需要用**该命名解析器**二次解析真实地址
 *
 * 这一支在 P0/P1 被注释为「一期不做」，P2 补上，P3 修正了类型语义。
 *
 * ## parses 数组的 type 语义（对齐 TVBoxOS `ParseConfig` / `PlayFragment`）
 * - `type=0` WebView 嗅探型：WebView 打开"解析器地址?url=待解析地址"自动嗅探
 * - `type=1` 普通 json 解析：GET 解析器地址（带 `{url}` 占位符），返回 JSON 取真实地址
 * - `type=2` json 扩展：同样 HTTP+JSON，服务端会引用其他解析器（客户端统一按 JSON 取 url）
 * - `type=3` json 聚合：同样 HTTP+JSON，服务端并发多路后回传最终 url
 * 后三类客户端行为一致（请求地址 → 取返回 JSON 的 url），差异在服务端，**不再误标 type=2 为"JS 不支持"**。
 *
 * ## "JS 解析"到底跑在哪
 * 真正需要跑 JS 的不是 type 字段，而是**解析结果 JSON 里的 `parse:1` 标记**
 * （对齐 TVBoxOS `rs.optInt("parse", 0) == 1 → loadWebView`）：
 * 该 url 必须丢进 WebView 让 JS 执行、再抓最终媒体直链。本工程复用已有的
 * `SniffEngine`（WebView + shouldInterceptRequest 抓直链），以 [Outcome.NeedJs] 表达，
 * 由 `PlayViewModel` 转调 `SniffEngine.sniff()`。**无需 QuickJS / NDK**——
 * 参考工程里 QuickJS（`wang.harlon.quickjs` AAR）只用于**站点 spider（jar/js 源）**，与解析链无关。
 *
 * ## 为什么不自己养一套解析器
 * 解析器地址来自**用户配置**（`parses[].url`），本工程只负责"调它 + 取结果"。
 * 真正的解析逻辑在配置侧，保持通用播放器的中立定位。
 */
class ParseRepository(
    private val net: NetApi,
) {

    /**
     * 从 `PlayResult` 选出要用的解析器（没有则返回 null → 走直连/嗅探）。
     *
     * 命中优先级：
     * 1. `flag`（线路名）与 `parses[].name` 精确匹配（源常见写法）
     * 2. `parse` 当作 1-based 下标取 `parses[parse-1]`
     */
    fun pick(
        playResult: PlayResult,
        parses: List<ParseConfig>,
    ): ParseConfig? {
        if (parses.isEmpty()) return null
        val p = playResult.parse
        if (p <= 1) return null // 0 直连 / 1 嗅探，不走前缀解析
        val flag = playResult.flag?.takeIf { it.isNotBlank() }
        val chosen = parses.firstOrNull { it.name.isNotBlank() && it.name == flag }
            ?: runCatching { p }.getOrNull()?.let { parses.getOrNull(it - 1) }
        // B1：熔断中的解析器不进候选（10 分钟内不再陪跑已知坏的，如 502 的巧技系列）
        return chosen?.takeIf { ParseHealth.isAvailable(ParseHealth.keyOf(it.url)) }
    }

    /**
     * 网页型播放地址（parse=0/1 但 URL 是 HTML 页面）的解析器候选列表 —— 对齐参考项目。
     *
     * drpy2 官源（如爱奇艺[js]）对 VIP 内容返回 `{parse:0, jx:1, url: "http://www.iqiyi.com/v_xxx.html"}`：
     * parse=0 语义是"直连"，但地址是**网页**不是流，直连/嗅探都拿不到视频。
     * 参考项目（TVBoxOS/影视仓）对此的处理是拿配置里的解析器解析该网页地址
     * （实测截图：爱奇艺[js] 选集播放 → 解析=超级解析 → 23.9Mbps 真实流）。
     *
     * 排序（参考项目行为：解析器按配置顺序 + flag 匹配优先，逐个试）：
     * 1. `ext.flag` 关键词与 sourceUrl 匹配的（声明支持该站点，如 "iqiyi"/"爱奇艺"）
     * 2. 其余 type=1/2 的 HTTP+JSON 型
     * 3. type=0 的 WebView 嗅探型
     * 4. type=3 聚合型（csp_Demo 需 jar spider 的 playerContent，本地未实现，垫底）
     *
     * 上层逐个尝试，全部失败才降级嗅探。**不要返回空表就认为没戏** ——
     * 单个解析器失效很常见（实测 0821.json 的"巧技"已 502），遍历才是正解。
     */
    fun candidatesForWebPage(
        sourceUrl: String,
        parses: List<ParseConfig>,
    ): List<ParseConfig> {
        if (parses.isEmpty()) return emptyList()
        val lower = sourceUrl.lowercase()
        fun flagsOf(pc: ParseConfig) = pc.ext?.get("flag")?.takeIf { it is JsonArray }
            ?.let { it as JsonArray }
            ?.mapNotNull { e -> runCatching { e.jsonPrimitive.content }.getOrNull() }
            .orEmpty()
        val flagMatched = parses.filter { pc ->
            flagsOf(pc).any { it.isNotBlank() && lower.contains(it.lowercase()) }
        }
        val rest = parses - flagMatched.toSet()
        val jsonTypes = rest.filter { it.type == 1 || it.type == 2 }
        val sniffTypes = rest.filter { it.type == 0 }
        val aggregateTypes = rest.filter { it.type == 3 }
        // B1：熔断中的解析器不进候选（type=0 不记账故不受影响；JSON 型全灭时
        // 上层自然跳过 JSON 并行段，即优化方案 B2 的"空转跳过"）。
        // B5：健康权重排序 —— flag 匹配且最近成功 > flag 匹配 > 最近成功 > 其余；
        // 稳定排序，同档保持上面的分组原序。
        val base = (flagMatched + jsonTypes + sniffTypes + aggregateTypes)
            .filter { ParseHealth.isAvailable(ParseHealth.keyOf(it.url)) }
        val flagUrls = flagMatched.map { it.url }.toSet()
        return base.sortedByDescending { pc ->
            val flag = pc.url in flagUrls
            val ok = ParseHealth.lastSuccessAt(ParseHealth.keyOf(pc.url)) > 0
            when {
                flag && ok -> 4
                flag -> 3
                ok -> 2
                else -> 1
            }
        }
    }

    /** 解析结果去向 */
    sealed interface Outcome {
        /** 拿到直链，可直接起播 */
        data class Direct(val url: String, val header: Map<String, String>? = null) : Outcome

        /**
         * 解析结果要求再用 WebView 跑 JS 二次解析（对齐 TVBoxOS 解析 JSON 的 `parse:1`）。
         * 上层把 `url` 交给 `SniffEngine` 加载，等其 JS 发起视频请求、由
         * `shouldInterceptRequest` 拦下直链。`ua` 可选，部分解析器要求特定 UA。
         */
        data class NeedJs(val url: String, val ua: String? = null) : Outcome

        /** 解析失败/不支持 */
        data class Failure(val message: String) : Outcome
    }

    /**
     * 用解析器二次解析真实地址。
     *
     * @param pc 选中的解析器配置
     * @param sourceUrl 待解析的中间地址（来自 `PlayResult.url`）
     * @param referer 源站 referer，部分解析器需要带
     */
    suspend fun resolve(
        pc: ParseConfig,
        sourceUrl: String,
        referer: String? = null,
    ): Outcome {
        val target = buildRequestUrl(pc.url, sourceUrl)
        if (target.isBlank()) return Outcome.Failure("解析器地址为空")

        // type 1/2/3 是 HTTP+JSON 系（普通/扩展/聚合），差异在服务端，客户端统一处理。
        // type 0 是 WebView 嗅探型（TVBoxOS 语义：WebView 打开解析器页面自动嗅探）——
        // 把"解析器地址+待解析地址"交给 SniffEngine 加载即可，走 NeedJs 分支。
        return when (pc.type) {
            1, 2, 3 -> {
                val out = resolveJson(target, referer)
                // B1：解析器可用性记账（只有 HTTP+JSON 型有明确成败；type=0 走嗅探不记）
                when (out) {
                    is Outcome.Direct, is Outcome.NeedJs ->
                        ParseHealth.recordSuccess(ParseHealth.keyOf(pc.url))
                    is Outcome.Failure ->
                        ParseHealth.recordFailure(ParseHealth.keyOf(pc.url))
                }
                out
            }
            0 -> Outcome.NeedJs(target)
            else -> Outcome.Failure("未知解析器类型 type=${pc.type}")
        }
    }

    /**
     * 构造解析器请求地址：
     * 1. 有 `{url}` 占位符 → 替换
     * 2. 模板已含 `url=` 查询键（配置常见写法 `...php?url=`）→ **直接拼编码地址**，
     *    不能再补 `&url=`（否则出 `?url=&url=xxx` 双键，解析器服务端取到空值直接挂）
     * 3. 都没有 → 按有无 `?` 拼 `?url=` / `&url=`
     */
    internal fun buildRequestUrl(template: String, sourceUrl: String): String {
        val t = template.trim()
        if (t.isEmpty()) return ""
        val enc = java.net.URLEncoder.encode(sourceUrl, "UTF-8")
        if (t.contains("{url}", ignoreCase = true)) {
            return t.replace("{url}", sourceUrl, ignoreCase = true)
        }
        if (t.contains("url=", ignoreCase = true)) {
            return t + enc
        }
        return if (t.contains('?')) "$t&url=$enc" else "$t?url=$enc"
    }

    /**
     * **并发**解析网页型地址的 JSON 型候选 —— 对齐参考项目 `JsonParallel.parse`。
     *
     * 参考项目不串行试解析器：线程池并发打所有 type=1 解析器，
     * `CompletionService` 取**第一个成功结果**，其余 `cancelAll()`。
     * 串行最坏 N×timeout（实测巧技 502 + 巧技二 502 白等 20s），
     * 并发最坏 1×timeout，且快的解析器胜出 —— 这就是"更好的实现方式"。
     *
     * @param pcs 候选解析器（调用方已按优先级过滤，这里只取 type=1/2）
     * @return 第一个 Direct/NeedJs；全失败返回 Failure（上层再走 type=0 聚合/嗅探）
     */
    suspend fun resolveParallel(
        pcs: List<ParseConfig>,
        sourceUrl: String,
        referer: String? = null,
    ): Outcome = coroutineScope {
        val jsonCandidates = pcs.filter { it.type == 1 || it.type == 2 }
        if (jsonCandidates.isEmpty()) {
            return@coroutineScope Outcome.Failure("无 JSON 型解析器")
        }
        TbLog.i("并发解析 %d 个 JSON 型解析器：%s", jsonCandidates.size,
            jsonCandidates.joinToString("/") { it.name })
        val tasks = jsonCandidates.map { pc ->
            async(Dispatchers.IO) {
                runCatching { resolve(pc, sourceUrl, referer) }.getOrNull()
            }
        }
        // 轮询等第一个"能出结果"的：按完成顺序检查，不等慢的拖死快的
        while (true) {
            val hit = tasks.filter { it.isCompleted }
                .mapNotNull { runCatching { it.getCompleted() }.getOrNull() }
                .firstOrNull { it is Outcome.Direct || it is Outcome.NeedJs }
            if (hit != null) {
                tasks.forEach { it.cancel() }
                return@coroutineScope hit
            }
            if (tasks.all { it.isCompleted }) break
            delay(60)
        }
        Outcome.Failure("${jsonCandidates.size} 个 JSON 解析器均失败")
    }

    /**
     * type=1/2/3 统一走 HTTP + JSON 解析（对齐 TVBoxOS 的 普通/扩展/聚合 三类）。
     *
     * 三类差异主要在服务端行为（type=2 扩展会引用其他解析器、type=3 聚合会并发多路），
     * 客户端都是"请求这个地址 → 取返回 JSON 的 url"，故统一处理。
     *
     * 关键：`parse` 字段。若返回 JSON 里 `parse == 1`，说明这个 url 还要再丢进 WebView
     * 跑一遍 JS 才能拿到最终直链（TVBoxOS 的 `loadWebView`），此时返回 [Outcome.NeedJs]
     * 交给上层用 SniffEngine 处理；否则直接 [Outcome.Direct]。
     */
    private suspend fun resolveJson(
        target: String,
        referer: String?,
    ): Outcome {
        val headers = buildMap<String, String> {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }.ifEmpty { null }
        // 10s 而非默认 20s：并行架构下 JSON 与聚合页同时跑，慢解析器不该
        // 成为瓶颈；不可达的解析器（连不上）10s connect 也够区分"慢"与"挂"。
        val resp = net.getText(target, headers = headers, timeoutMs = 10_000)
        if (!resp.isSuccess) {
            return Outcome.Failure("解析器请求失败：${resp.error ?: "HTTP ${resp.code}"}")
        }
        return parseJsonOutcome(resp.body, headers)
    }

    /**
     * 解析器返回文本 → 解析结局（纯函数，供单测直接覆盖，无需网络）。
     *
     * 返回 JSON 时取 `url` 字段；若 JSON 含 `parse == 1` 则 [Outcome.NeedJs]
     * （交 WebView 跑 JS），否则 [Outcome.Direct]。非 JSON 文本兜底扫媒体地址。
     */
    internal fun parseJsonOutcome(body: String, headers: Map<String, String>? = null): Outcome {
        val parsed = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull()
        if (parsed is JsonObject) {
            val url = parsed.extractUrl() ?: return Outcome.Failure("解析器未返回可用的播放地址")
            val needJs = parsed["parse"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 1
            val ua = parsed["ua"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            return if (needJs) {
                TbLog.i("前缀解析需 WebView 跑 JS：%s", url.take(120))
                Outcome.NeedJs(url, ua)
            } else {
                TbLog.i("前缀解析成功：%s", url.take(120))
                Outcome.Direct(url, headers)
            }
        }
        // 非 JSON（裸文本）：兜底按媒体地址扫描
        val fallback = extractMediaUrl(body)
        return if (fallback.isNullOrBlank()) {
            Outcome.Failure("解析器未返回可用的播放地址")
        } else {
            Outcome.Direct(fallback, headers)
        }
    }

    /** 从解析到的 JSON 对象里取第一个媒体直链（媒体后缀优先，其次首个 http 串） */
    private fun JsonObject.extractUrl(): String? {
        val collected = collectUrls(this)
        return collected.firstOrNull { isMediaUrl(it) } ?: collected.firstOrNull()
    }

    /**
     * 从解析器返回的 JSON 文本里取第一个媒体直链（纯函数，供单测直接覆盖）。
     *
     * 策略：递归遍历 JSON 树收集所有 http(s) 字符串，优先挑带媒体后缀
     * （.m3u8/.mp4/.flv/.ts…）的；没有则取第一个。兜底再用正则扫一遍原始文本，
     * 防止返回的是裸字符串数组之类 parse 不出来的形态。
     */
    internal fun extractMediaUrl(jsonText: String): String? {
        val collected = runCatching {
            val root = json.parseToJsonElement(jsonText.trim())
            collectUrls(root)
        }.getOrNull().orEmpty()

        val media = collected.firstOrNull { isMediaUrl(it) }
        if (media != null) return media

        return collected.firstOrNull()
            ?: MEDIA_URL.find(jsonText)?.groupValues?.get(1)
    }

    private fun collectUrls(node: JsonElement, out: MutableList<String> = ArrayList()): List<String> {
        when (node) {
            is JsonObject -> node.forEach { (_, v) -> collectUrls(v, out) }
            is JsonArray -> node.forEach { collectUrls(it, out) }
            is JsonPrimitive -> node.contentOrNull
                ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.let { out += it }
        }
        return out
    }

    private fun isMediaUrl(u: String): Boolean =
        MEDIA_EXT.containsMatchIn(u)

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val MEDIA_EXT = Regex(""".*\.(m3u8|mp4|flv|ts|m3u|webm|mkv|mov|avi)(\?.*)?$""", RegexOption.IGNORE_CASE)

        /** 兜底：直接扫裸 URL（解析器返回纯字符串数组时 JSON 解析拿不到字段） */
        val MEDIA_URL = Regex("""(https?://[^\s"',\]}]+?\.(?:m3u8|mp4|flv|ts|m3u|webm|mkv|mov|avi))""", RegexOption.IGNORE_CASE)
    }
}
