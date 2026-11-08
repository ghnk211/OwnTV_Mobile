package tv.own.owntv.mobile.tvbox.engine.sniff

import tv.own.owntv.mobile.tvbox.util.TbLog

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import tv.own.owntv.mobile.tvbox.config.model.AdRuleSet
import tv.own.owntv.mobile.tvbox.config.model.SniffRule
import tv.own.owntv.mobile.tvbox.util.UaUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * WebView 嗅探引擎（架构方案 5.6）。
 *
 * ## 解决的问题
 * CMS / Jar 源里大量地址不是直链，而是**二级播放页**：
 * ```
 *量子资源/share/xxx       → 返回 2405 字节 HTML，页面里再嵌 url = "http://vip.okzybo.com/..."
 * Jar 源 余红旧事 线路1/线路2 → 同样是二级页
 * ```
 * ExoPlayer 直接吃这些页面会报 `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`。
 * 嗅探就是用 WebView 加载页面，等它自己发起视频请求，
 * 在 `shouldInterceptRequest` 里拦下来拿到直链。
 *
 * ## 流程
 * ```
 * url → WebView.loadUrl
 *      → shouldInterceptRequest 逐请求喂给 SniffMatcher
 *      → 命中 → 返回空响应（阻断，不让 WebView 真下载）+ complete(直链)
 *      → 超时 12s / onPageFinished+宽限 / onReceivedError → complete(null)
 * ```
 *
 * ## 四个必须注意的实现细节
 * 1. **命中后要 return 空响应**：否则 WebView 会真去下载几十 MB 视频，
 *    既浪费流量又可能把直链请求搞挂（很多直链是一次性的）。
 * 2. **WebView 必须 destroy**：不 destroy 会持续泄漏（方案第 4 条明确要求）。
 *    这里每次嗅探用完立即销毁，不做跨次复用 —— 复用带来的状态污染风险
 *    高于它省下的那点创建开销（WebView 创建约 100ms，而嗅探是用户可感知等待）。
 * 3. **必须在主线程创建/销毁 WebView**，网络拦截回调也在主线程。
 * 4. **Referer 必须随首次 loadUrl 一起传**（`loadUrl(url, headers)`）。
 *    二次 `loadUrl` 覆盖首次会丢掉已发出的请求，白白多一轮加载。
 *
 * ## 为什么不能"复用 2 个实例池化"
 * 架构文档写的是池化（最多 2 个）。实际实现改为每次新建 + 即销毁：
 * 嗅探失败后 WebView 内部会残留 cookie/缓存/JS 状态，复用会把上一个源的
 * 登录态带给下一个源，产生极难排查的串源问题。取舍上先保正确性。
 *
 * ## 热路径优化（第 1 批，见 docs/WebView嗅探效率优化方案.md）
 * 这四条都是**改动小、收益直接**的，改动前请先看理由，别按"看起来更稳妥"改回去：
 *
 * 1. **验证异步化**：命中播放列表时先拦下（返回空响应）再异步验证，硬预算 [VERIFY_BUDGET_MS]。
 *    改前是 `runBlocking` 同步跑在 `shouldInterceptRequest` 线程上，真机实测一次拖 3.7s，
 *    期间该 WebView 所有请求排队（详见 [SniffClient.verifyInBackground]）。
 *    **现在进一步改成「命中即播」**：先采用、验证在后台跑；判死则 `rejectStream` +
 *    [onBadStream] 让上层立刻换线（P1-4）。真流上省 0.7~1.1s。
 * 2. **不再 `clearCache(true)`**：它清全局缓存、主线程删磁盘、让页面资源全重下，
 *    却对串源零防护（串源靠 Cookie/localStorage，存在于 WebView 数据目录）。
 *    这笔开销是纯亏（详见 [applyBaseSettings]）。
 * 3. **结果一出就卸页面**：`stopLoading` + `about:blank`，不等外层超时，
 *    避免"结果已出、页面还在拉流抢带宽"。
 * 4. **规则预编译 + 日志采样**：拦截回调每请求一次，不能重复拆规则字符串，
 *    未命中日志也不能每条都拼（见 [SniffMatcher.CompiledRules]、[SniffClient.logMiss]）。
 *
 * 每次嗅探结束会打一条 `SniffTiming` 汇总（见 [SniffTiming]），改前后对比以它为准。
 */
class SniffEngine(
    private val context: Context,
    /**
     * 命中验证器：播放列表型地址（m3u8/mpd）命中后、真正采用前，
     * 用 WebView 的 Cookie/UA/Referer 实际请求一次，确认流里**有视频**。
     *
     * 为什么需要：真机实测某解析流（m3u8cache 的 m3u8.php）是**音频-only**
     * （ExoPlayer 只建 AAC 解码器、视频解码器从未创建），起播后"有声无画"。
     * 还有签名失效返回 403 JSON 被当容器解析（3003）的形态。
     * 验证不过就不采用，继续等下一个 iframe 的命中 —— 聚合页一次放多个
     * 解析器，本来就该"谁的真能用谁"。
     *
     * 只验证播放列表（m3u8/mpd）；mp4/ts 直链是内容本身，不验。
     */
    private val verifier: (suspend (String, Map<String, String>?) -> Boolean)? = null,
) {

    init {
        // 构造时立即注册：懒注册会错过启动阶段的 onResume，
        // 首次嗅探时钩子才生效 → 拿不到 Activity → 脱离视图树秒败（实测）。
        ensureLifecycleHooks()
    }

    /** siteKey → 配置里单站点覆盖的自定义规则 */
    private val rulesCache = ConcurrentHashMap<String, List<SniffRule>>()

    /**
     * 引擎级作用域：只用于「调用方协程已经取消，但主线程清理仍必须完成」的场景
     * （见 [destroyQuietly] 的兜底路径）。
     *
     * 不能复用调用方协程 —— 竞速拆腿时它已经是取消状态，任何挂起点都会直接抛。
     * SupervisorJob：单次清理失败不影响后续。
     */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 坏流指纹表（P1-4 失败反馈）：`host + path` → 过期时刻（`elapsedRealtime`）。
     *
     * ## 为什么要指纹而不是整条 url
     * 直链带签名（`?sign=...&expire=...`），每次解析都变，整条 url 存下来永远命中不了。
     * 真正稳定的标识是「域名 + 路径」—— 同一个片源的同一集，签名换了路径不变。
     *
     * ## 谁往里写
     * - 命中即播后，后台验证判定"确定坏流"（音频-only / 图片轮播 / 403 JSON）；
     * - 播放器起播早期报错（`PlayViewModel.onPlayFailure` 且该 url 来自嗅探）。
     * ## 谁读
     * [SniffClient] 命中时先查表：在表内就**不采用**、继续等别的命中（不回放给用户）。
     */
    private val rejectedStreams = ConcurrentHashMap<String, Long>()

    /**
     * 「命中即播后发现是坏流」的回调（P1-4）。由 `PlayViewModel` 注册：
     * 若这条流正在播且还没出画面，立刻换线/换源。
     *
     * 没有这个回调时，命中即播会把"音频-only"这类坏流直接放给用户 —— 这正是当年
     * 引入同步验证层的原因，现在把"发现坏流"的职责从关键路径挪到失败路径。
     */
    @Volatile
    var onBadStream: ((String) -> Unit)? = null

    /** 记录一条坏流指纹（TTL 内同一 host+path 不再被采用） */
    fun rejectStream(url: String) {
        val fp = streamFingerprint(url)
        if (fp.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (rejectedStreams.size >= MAX_REJECTED) {
            rejectedStreams.entries.removeAll { it.value <= now }
            if (rejectedStreams.size >= MAX_REJECTED) rejectedStreams.clear()
        }
        rejectedStreams[fp] = now + REJECT_TTL_MS
        TbLog.tag(TAG).i("坏流已记录（%d 分钟内跳过）：%s", REJECT_TTL_MS / 60_000, fp.take(80))
    }

    /** 该地址是否在坏流表内（未过期） */
    internal fun isStreamRejected(url: String): Boolean {
        val fp = streamFingerprint(url)
        if (fp.isBlank()) return false
        val expireAt = rejectedStreams[fp] ?: return false
        if (SystemClock.elapsedRealtime() > expireAt) {
            rejectedStreams.remove(fp)
            return false
        }
        return true
    }

    /** 坏流指纹：域名 + 路径（丢掉签名 query） */
    private fun streamFingerprint(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.host.orEmpty()}${u.path.orEmpty()}"
    }.getOrDefault(url.substringBefore('?'))

    /** 当前 resumed Activity（生命周期回调维护，WeakReference 不泄漏） */
    @Volatile
    private var resumedActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * 配置顶层的 `rules`（TvBoxConfig.rules），对所有站点生效。
     * 站点级规则优先于全局规则。
     */
    @Volatile
    private var globalRules: List<SniffRule> = emptyList()

    /** 导入/切换配置时由 ConfigRepository 调用 */
    fun setGlobalRules(rules: List<SniffRule>) {
        globalRules = rules
        TbLog.tag(TAG).i( "全局嗅探规则：%d 条", rules.size)
    }

    /**
     * 配置级广告规则（顶层 `ads` + `rules[].ads`）。
     *
     * 只用于 WebView **子资源**拦截；主文档放行，避免广告域名恰好是页面地址时
     * 返回空响应导致整页白屏、嗅探直接失败。
     */
    @Volatile
    private var adRules: AdRuleSet = AdRuleSet.EMPTY

    fun setAdRules(rules: AdRuleSet) {
        adRules = rules
        TbLog.tag(TAG).i("WebView 广告规则：%d 条", rules.size)
    }

    private val adBlockedTotal = AtomicInteger(0)

    /** 诊断页：本次进程累计拦截的广告子请求数 */
    fun adBlockedCount(): Int = adBlockedTotal.get()

    /** 站点级规则；空列表表示清除该站点覆盖，回落到全局规则 */
    fun setRules(siteKey: String, rules: List<SniffRule>) {
        if (rules.isEmpty()) rulesCache.remove(siteKey) else rulesCache[siteKey] = rules
    }

    /** 站点实际生效的规则 = 全局 + 站点级（站点级在后，优先级更高） */
    private fun rulesFor(siteKey: String): List<SniffRule> {
        val site = rulesCache[siteKey].orEmpty()
        return if (globalRules.isEmpty()) site else globalRules + site
    }

    /**
     * 嗅探出直链。
     *
     * @param pageUrl 二级播放页地址
     * @param siteKey 站点 key，用于取自定义规则
     * @param ua 站点 UA（要与播放器请求头一致，否则站点可能返回不同页面）
     * @param referer 站内 referer，部分站点强校验防盗链
     * @param timeoutMs 总超时，默认 12s（方案 5.6 第 3 条）
     * @param htmlData 直接用该 HTML 起页（loadDataWithBaseURL），跳过 [pageUrl] 加载。
     *   用于 type=0 解析器聚合页（多 iframe 并发放一页里，哪个先出流拦哪个，
     *   对齐参考项目 SuperParse.loadHtml）——比逐个 WebView 串行快一个数量级。
     * @param excludeUrls 命中排除列表（聚合页的 iframe src，见 [SniffClient]）
     */
    suspend fun sniffResult(
        pageUrl: String,
        siteKey: String,
        ua: String? = null,
        referer: String? = null,
        timeoutMs: Long = TIMEOUT_MS,
        htmlData: String? = null,
        excludeUrls: List<String> = emptyList(),
        /** TVBoxOS 复刻模式（实验分支）：命中规则换 checkVideoFormat、无 verifier、命中即采用 */
        parityMode: Boolean = false,
    ): SniffResult {
        if (htmlData == null && pageUrl.isBlank()) return SniffResult.Failure("地址为空")
        if (htmlData == null && !looksWebPage(pageUrl)) return SniffResult.Failure("不是网页地址，无需嗅探")

        val timing = SniffTiming(
            htmlData?.let { "HTML ${it.length}B" } ?: pageUrl.take(48),
        )

        // 入口日志：真机排障第一眼要看的就是"到底要打开哪个页面"。
        // 华为 WebView 的日志会把 URL 脱敏成 http// 之类，App 侧不打就永远对不上。
        TbLog.tag(TAG).i( "开始嗅探：%s（ua=%s, referer=%s）",
            htmlData?.let { "聚合HTML ${it.length}B" } ?: pageUrl.take(160),
            ua?.take(40) ?: "默认", referer ?: "无")

        // 规则预编译一次（P0-6）：拦截回调每个请求都要用，不能让它在
        // WebView 的拦截线程上反复拆 hosts/关键字字符串。
        val compiled = SniffMatcher.compile(rulesFor(siteKey))
        val result = CompletableDeferred<AdoptedUrl?>()
        // HLS 分片兜底：.ts/.m4s 分片是视频的一小段，直接当直链只能播 3~6 秒（真机实测），
        // 命中时降级记为兜底候选、继续等主播放列表；全程等不到才用它。
        val segmentFallback = AtomicReference<String?>(null)
        // P1-2：解析器**自己域名**上的弱命中（`api.php?type=m3u8&…` 这类接口地址）降级候选。
        // 它多半不是媒体流，直接采用会让播放器报容器错误、整条换线换源链重来；
        // 但也不能完全丢 —— 少数解析器就在自己域名上代理真流，所以留作最后一档兜底。
        val parserFallback = AtomicReference<String?>(null)
        var pageFailed = false

        val webView = newWebViewOrNull(timing) ?: return SniffResult.Failure("当前设备不支持网页嗅探")

        var outcome = "失败"
        var client: SniffClient? = null
        return try {
            // UA 只算一次：既用于 WebView 设置，也用于验证请求头（见 SniffClient.collectHeaders）。
            // 后者不能在拦截线程上回读 `webView.settings` —— 那是 WebView 方法，
            // 只能在主线程调用（真机/模拟器实测 StrictMode
            // `WebViewMethodCalledOnWrongThreadViolation`）。
            val effectiveUa = ua?.takeIf { it.isNotBlank() } ?: UaUtil.DEFAULT_UA
            webView.settings.userAgentString = effectiveUa
            client = webView.attachSniffClient(
                result, compiled, { pageFailed = true }, excludeUrls,
                segmentFallback = segmentFallback, parserFallback = parserFallback,
                parityMode = parityMode,
                webUrl = pageUrl, timing = timing, ua = effectiveUa,
                // 聚合页宽限收窄到 1.2s（第 2 项）：实测那 4s 是纯白等；单页保持 4s
                settleMs = if (htmlData != null) SETTLE_DELAY_MS_AGGREGATED else SETTLE_DELAY_MS,
            )
            // Referer 一次性随首次 loadUrl 传入（聚合 HTML 场景用目标页 origin 兜底）
            val refererValue = referer?.takeIf { it.isNotBlank() } ?: originOf(pageUrl)

            // P0-3：结果一出（命中 / 失败 / 页面报错）就立刻把页面卸掉，不再等
            // withTimeout 结束才 destroy —— 否则聚合页里其它 iframe 的 JS 会继续跑、
            // 继续抢带宽，直接拖慢紧随其后的起播。
            // 读 Cookie 不受影响：CookieManager 是进程级、按域存的，与当前导航无关。
            // **只 stopLoading，不再 loadUrl("about:blank")**（主线程排队优化，第 4 批）：
            // 那条导航要在主线程做一次完整导航 + 布局，实测它与"恢复到 Main 协程继续起播"
            // 争同一个队列，是命中后 744ms 的主要来源。真正卸载交给 destroyQuietly（已投递 + 延迟）。
            result.invokeOnCompletion {
                webView.post { runCatching { webView.stopLoading() } }
            }

            if (htmlData != null) {
                // P0-5：baseURL 不能给 null —— 否则 iframe 子资源的 Referer 为空，
                // 部分解析器据此拒流或降级（表现为"要等更久才出流"甚至不出）。
                webView.loadDataWithBaseURL(refererValue, htmlData, "text/html", "utf-8", null)
            } else {
                webView.loadUrl(pageUrl, mapOf("Referer" to refererValue))
            }
            timing.mark("load")

            val hit = withTimeoutOrNull(timeoutMs) { result.await() }
            // 计时点：hit(采用) → awaited 的差值 = 恢复到本协程所排的主线程队列（收尾优化要盯的就是它）
            timing.mark("awaited")
            if (hit != null && hit.url.isNotBlank()) {
                // 命中来源已在SniffClient 里打过日志，这里只补一条成功
                outcome = if (hit.verified) "命中" else "命中(未验证)"
                val headers = collectPlaybackHeaders(webView, hit.url, ua, refererValue)
                timing.mark("headers")
                SniffResult.Success(
                    url = hit.url,
                    reason = SniffMatcher.matchCompiled(hit.url, compiled)?.reason ?: "已定位",
                    headers = headers,
                    verified = hit.verified,
                )
            } else {
                // 兜底档位（由强到弱）：解析器域名弱命中 > HLS 分片。
                // 解析器域名那档可能是真代理流，分片只能播 3~6 秒，故优先它。
                val parserHit = parserFallback.get()
                val segment = segmentFallback.get()
                if (!parserHit.isNullOrBlank()) {
                    TbLog.tag(TAG).w("只等到解析器域名的弱命中，按兜底采用：%s", parserHit.take(120))
                    outcome = "解析器域名兜底"
                    SniffResult.Success(
                        url = parserHit,
                        reason = "解析器域名兜底",
                        headers = collectPlaybackHeaders(webView, parserHit, ua, refererValue),
                        verified = false,
                    )
                } else if (!segment.isNullOrBlank()) {
                    // 兜底：只等到分片、没等到主播放列表 —— 采用分片并标注，
                    // 只能播很短一段，但好过完全起播不了（失败会走换线链重试）
                    TbLog.tag(TAG).w("主媒体未命中，HLS 分片兜底（只能播一小段）：%s", segment.take(120))
                    outcome = "分片兜底"
                    SniffResult.Success(
                        url = segment,
                        reason = "HLS分片兜底",
                        headers = collectPlaybackHeaders(webView, segment, ua, refererValue),
                    )
                } else {
                    val msg = when {
                        pageFailed -> "页面加载失败，可尝试切换线路"
                        else -> "未在页面中找到视频地址，可尝试切换线路"
                    }
                    // S2：失败原因分类，脱离"人肉看日志"
                    val why = client?.failureReason() ?: "unknown"
                    outcome = "失败:$why"
                    // 注意必须 `.get()`：直接传 AtomicInteger 会让 %d 抛
                    // IllegalFormatConversionException，整条嗅探被 catch 成"异常"
                    TbLog.tag(TAG).i(
                        "SniffFail reason=%s sub=%d hit=%d excluded=%d rejected=%d mainErr=%d iframeErr=%d | %s",
                        why,
                        client?.subCount?.get() ?: 0,
                        client?.hitCount?.get() ?: 0,
                        client?.excludedCount?.get() ?: 0,
                        client?.rejectedCount?.get() ?: 0,
                        client?.mainFrameError?.get() ?: 0,
                        client?.iframeError?.get() ?: 0,
                        msg,
                    )
                    SniffResult.Failure(msg)
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 竞速拆腿（第 1 项）会 cancel 正在跑的嗅探：必须原样抛出。
            // 若被下面的 Throwable 分支吞掉，被取消的腿会带着一个伪造的
            // Failure 回到编排层，日志与状态都不可信。
            outcome = "取消"
            throw c
        } catch (t: Throwable) {
            // WebView 相关异常（Renderer 崩溃、非法参数）不能连带崩 App
            TbLog.tag(TAG).w( "嗅探异常：$t")
            outcome = "异常"
            SniffResult.Failure("嗅探异常，可尝试切换线路")
        } finally {
            // 先关验证作用域：本次嗅探已经结束，后台验证结果再回来也没有意义，
            // 留着只会多打一次无用网络请求。
            client?.close()
            // 无论成败都必须销毁，否则泄漏（见类注释"实现细节 2"）
            destroyQuietly(webView)
            timing.mark("destroy")
            timing.dump(outcome)
        }
    }

    /**
     * 收集播放直链所需的请求头。
     *
     * ## 时序要求
     * 必须在 WebView **还活着**时读 Cookie —— `destroy()` 之后该 WebView 会话
     * 的 Cookie 可能取不到了，所以这个方法要在 `finally { destroyQuietly }` 之前调用。
     *
     * 参考项目对应代码：
     * ```java
     * String cookie = CookieManager.getInstance().getCookie(url);
     * if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", " " + cookie);
     * ```
     */
    private fun collectPlaybackHeaders(
        webView: WebView,
        mediaUrl: String,
        ua: String?,
        referer: String,
    ): Map<String, String> = runCatching {
        val headers = LinkedHashMap<String, String>()
        val cookie = CookieManager.getInstance().getCookie(mediaUrl)
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
        if (referer.isNotBlank()) headers["Referer"] = referer
        headers
    }.getOrElse {
        TbLog.tag(TAG).w( "读取 Cookie 失败：${it.message}")
        emptyMap()
    }

    /** 便捷方法：只要直链，失败返回 null */
    suspend fun sniff(
        pageUrl: String,
        siteKey: String,
        ua: String? = null,
        referer: String? = null,
        timeoutMs: Long = TIMEOUT_MS,
    ): String? =
        (sniffResult(pageUrl, siteKey, ua, referer, timeoutMs) as? SniffResult.Success)?.url

    /** 该地址是否需要走嗅探：不是直链的网页地址才需要 */
    fun needsSniff(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        if (SniffMatcher.looksDirect(u)) return false
        return looksWebPage(u)
    }

    /** 是不是 http/https 网页地址（排除 data: blob: file: content: 等） */
    private fun looksWebPage(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    /** 取页面所在站点根地址作为 Referer（https://host/） */
    private fun originOf(pageUrl: String): String = runCatching {
        val u = java.net.URI(pageUrl)
        val host = u.host.orEmpty()
        val scheme = u.scheme ?: "https"
        if (host.isEmpty()) pageUrl else "$scheme://$host/"
    }.getOrDefault(pageUrl)

    /**
     * WebView 必须在主线程创建，且**必须挂进 Activity 的视图树**。
     *
     * ## 为什么必须挂视图树（真机踩过的坑）
     * 最初这里写的是 `WebView(applicationContext)` —— 脱离视图树的 WebView。
     * 在华为 OXF-AN00 上表现为：**页面能`loadUrl`，但 JS 不执行、
     * `shouldInterceptRequest` 一次都不触发**，12 秒后必然"未在页面中找到视频地址"。
     * 日志里连一条"未命中"都没有，正是因为回调压根没被调用。
     *
     * 参考项目 `PlayFragment.configWebViewSys` 早就用了正确做法：
     * ```java
     * ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(1, 1);
     * requireActivity().addContentView(webView, params);
     * ```
     * 1×1 像素 —— 不可见但 participate 布局，渲染管线才会真正跑起来。
     *
     * @param activityProvider 每次取当前 Activity（可能已变化），拿不到时降级为不挂载
     */
    private suspend fun newWebViewOrNull(timing: SniffTiming? = null): WebView? =
        withContext(Dispatchers.Main) {
            runCatching {
                val wv = WebView(context)
                // 首次调用要同步加载 WebView provider（低端盒 0.5~1.5s），
                // 单独一个标记位，便于判断"慢在 provider 还是慢在页面"。
                timing?.mark("new")
                attachToWindow(wv)
                applyBaseSettings(wv)
                timing?.mark("settings")
                wv
            }.onFailure { TbLog.tag(TAG).e( "WebView 创建失败：${it.message}") }
                .getOrNull()
        }

    /**
     * 把 WebView 挂到 Activity 内容视图上（1×1 像素）。
     *
     * 拿不到 Activity 时**不抛异常**，只是不挂 —— 降级成脱离视图树，
     * 顶多嗅探失败，不会连带崩播放。
     */
    private fun attachToWindow(wv: WebView) {
        runCatching {
            val activity = currentActivity()
            if (activity == null) {
                TbLog.tag(TAG).w( "拿不到 Activity，WebView 将脱离视图树（嗅探大概率失败）")
                return
            }
            activity.runOnUiThread {
                runCatching {
                    activity.addContentView(
                        wv,
                        android.view.ViewGroup.LayoutParams(1, 1),
                    )
                    TbLog.tag(TAG).i( "WebView 已挂载到视图树（1x1）")
                }.onFailure { TbLog.tag(TAG).w( "WebView 挂载失败：${it.message}") }
            }
        }.onFailure { TbLog.tag(TAG).w( "WebView 挂载异常：${it.message}") }
    }

    /**
     * 取当前（resumed）Activity。
     *
     * ## 为什么放弃 ActivityThread.mActivities 反射
     * 早前实现反射读 `ActivityThread.mActivities`，但该结构在 API 26+ 已变为
     * `ArrayMap<IBinder, ActivityClientRecord>`（值不再是 `WeakReference<Activity>`），
     * 转型静默失败 → **永远拿不到 Activity** → WebView 脱离视图树 →
     * 页面 JS 不执行、shouldInterceptRequest 收不到请求 → 嗅探与 type=0 解析全废
     * （实测 API 36 模拟器：所有嗅探 1.5 秒内 pageFailed）。
     *
     * 现改为 `registerActivityLifecycleCallbacks` 维护 resumed Activity：
     * 公开 API、无版本差异、无反射。进程级注册一次，靠 WeakReference 不泄漏。
     */
    private fun currentActivity(): android.app.Activity? {
        val ref = resumedActivityRef
        val activity = ref?.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) return activity
        // 兜底：生命周期钩子因任何原因漏记（注册前已 resumed 等）时，
        // 回退反射 ActivityThread.mActivities —— API 26+ 值是 ActivityClientRecord，
        // 反射取其 .activity 字段（旧版是 WeakReference<Activity>，两种都兼容）。
        return runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null) ?: return null
            @Suppress("UNCHECKED_CAST")
            val activities = threadClass.getDeclaredField("mActivities").apply { isAccessible = true }
                .get(thread) as? Map<*, *> ?: return null
            activities.values.forEach { v ->
                val act = when (v) {
                    is java.lang.ref.WeakReference<*> -> v.get() as? android.app.Activity
                    else -> runCatching {
                        v?.javaClass?.getDeclaredField("activity")?.apply { isAccessible = true }
                            ?.get(v) as? android.app.Activity
                    }.getOrNull()
                }
                if (act != null && !act.isFinishing && !act.isDestroyed) return act
            }
            null
        }.getOrNull()
    }

    @Volatile
    private var lifecycleHooked = false

    private fun ensureLifecycleHooks() {
        if (lifecycleHooked) return
        synchronized(this) {
            if (lifecycleHooked) return
            runCatching {
                val app = context.applicationContext as? android.app.Application
                app?.registerActivityLifecycleCallbacks(object :
                    android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(a: android.app.Activity) {
                        resumedActivityRef = java.lang.ref.WeakReference(a)
                    }
                    override fun onActivityPaused(a: android.app.Activity) {
                        // 只在该 Activity 就是当前记录的那个时才清，
                        // 避免"新页面已 resumed、旧页面 paused"把新引用擦掉
                        if (resumedActivityRef?.get() === a) resumedActivityRef = null
                    }
                    override fun onActivityDestroyed(a: android.app.Activity) {
                        if (resumedActivityRef?.get() === a) resumedActivityRef = null
                    }
                    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                    override fun onActivityStarted(a: android.app.Activity) {}
                    override fun onActivityStopped(a: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                })
                lifecycleHooked = true
                TbLog.tag(TAG).i("Activity 生命周期钩子已注册")
            }.onFailure {
                TbLog.tag(TAG).w("注册 Activity 生命周期钩子失败：${it.message}")
            }
        }
    }

    /** 基础设置：只需调一次的进程级配置 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun applyBaseSettings(wv: WebView) {
        wv.settings.apply {
            // 嗅探必须开 JS —— 绝大多数播放页的 m3u8 是 JS 动态拼出来的
            javaScriptEnabled = true
            domStorageEnabled = true
            // 嗅探不需要渲染，关图片省内存省流量
            blockNetworkImage = true
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // 部分源用 form/localStorage 存播放状态，必须留着
            @Suppress("DEPRECATION")
            setSaveFormData(false)
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // M-5：开启 Google 安全浏览（Safe Browsing）恶意站点拦截。
                // 嗅探目标虽是视频直链、预期无误伤；若个别源被误拦再按域名豁免。
                safeBrowsingEnabled = true
            }
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        // 嗅探 WebView 静音：`mediaPlaybackRequiresUserGesture=false` 让 h5 播放器自动起播
        // （嗅探需要它发流请求），但出声就是"退出页面声音还在"。
        // setMediaVolume 不在公开 SDK 里，反射调用（Chromium WebView 有此方法）；
        // 拿不到就降级为现状 —— 声音问题的主根因靠销毁可靠性兜住（见 destroyQuietly）。
        runCatching {
            WebView::class.java.getMethod("setMediaVolume", Float::class.javaPrimitiveType)
                .invoke(wv, 0f)
        }
        // P0-2：**不要再调 clearCache(true)**。
        //
        // 它清的是**全局**磁盘+内存缓存（不是本实例），还在主线程做磁盘删除；
        // 清完之后该页面的 JS/CSS/字体/解析器 SDK 全部重新下载，纯亏。
        // 更关键的是它对"串源"**零防护**：串源来自 Cookie / localStorage / IndexedDB，
        // 这些存在 WebView 的**数据目录**里，clearCache 一个都清不到 ——
        // 原注释把它当防串源手段是误判，这里用真实成本换了零收益。
        //
        // 跨源隔离仍然靠：每次新实例 + 命中即 about:blank/destroy + CookieManager 按域隔离。
        // 真需要"不吃旧缓存"时用 cacheMode = LOAD_NO_CACHE（只影响本实例、不动磁盘）。
    }

    /**
     * 销毁 WebView。必须在主线程。
     *
     * 挂进过视图树的话，**必须先 removeView** —— 只destroy 的话
     * Activity 的内容视图里会留一个空 View 引用，累积多次嗅探会漏内存。
     */
    /**
     * 销毁 WebView —— **不阻塞调用方**。
     *
     * ## 为什么改成投递而不是 await（实测）
     * 命中即播后这里是结果返回前的最后一站，实测 `hit=2073 → destroy=3057`：
     * 光等 `about:blank` + `destroy()` 就多吃 **984ms**，直接把"省下来的验证时间"又还回去了。
     * 清理本来就不该在关键路径上 —— 投递到引擎级 Main 作用域即可，
     * 即便宿主协程已被 cancel（竞速拆腿）也照样会执行。
     */
    private fun destroyQuietly(wv: WebView) {
        engineScope.launch {
            // 稍等再销毁：`about:blank` + dispose 都要占主线程，而"恢复到 Main 继续起播"
            // 正排在同一个队列里 —— 先让它排上，能省几百毫秒（见 SniffTiming 的 hit→awaited）。
            // 100ms 足够越过这次续体调度，又不会让页面多播多久。
            delay(DESTROY_POST_DELAY_MS)
            runCatching { destroyNow(wv) }
                .onFailure { TbLog.tag(TAG).d("WebView 销毁异常：${it.message}") }
        }
    }

    /**
     * 真正执行销毁（必须在主线程）。每一步单独兜住 ——
     * 兜底路径可能对同一个 WebView 重复调用，`destroy()` 之后再 `loadUrl` 会抛，
     * 不能让它中断后续清理。
     */
    private fun destroyNow(wv: WebView) {
        runCatching { wv.stopLoading() }
        // 断开对 SniffClient 的引用，尽早 GC
        runCatching { wv.webViewClient = WebViewClient() }
        runCatching { wv.loadUrl("about:blank") }
        runCatching { (wv.parent as? android.view.ViewGroup)?.removeView(wv) }
        runCatching { wv.removeAllViews() }
        runCatching { wv.destroy() }
    }

    /** 挂载拦截客户端（返回实例，供 [sniffResult] 结束后 [SniffClient.close] 收掉验证作用域） */
    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.attachSniffClient(
        result: CompletableDeferred<AdoptedUrl?>,
        compiled: SniffMatcher.CompiledRules,
        onPageFailed: () -> Unit,
        excludeUrls: List<String> = emptyList(),
        referer: String? = null,
        segmentFallback: AtomicReference<String?> = AtomicReference(null),
        /** 解析器域名弱命中的降级候选（P1-2，见 [SniffClient.parserHosts]） */
        parserFallback: AtomicReference<String?> = AtomicReference(null),
        parityMode: Boolean = false,
        webUrl: String = "",
        timing: SniffTiming? = null,
        /** 本次嗅探实际使用的 UA（验证请求要带同一个，见 [SniffClient.collectHeaders]） */
        ua: String? = null,
        /** onPageFinished 后的宽限窗口：聚合页 1.2s、单页 4s（见 [SETTLE_DELAY_MS_AGGREGATED]） */
        settleMs: Long = SETTLE_DELAY_MS,
    ): SniffClient {
        val client = SniffClient(
            result, compiled, onPageFailed, excludeUrls, referer, verifier, segmentFallback,
            parserFallback = parserFallback,
            parityMode = parityMode, webUrl = webUrl, timing = timing, ua = ua, settleMs = settleMs,
        )
        webViewClient = client
        return client
    }

    /**
     * 拦截客户端：只在请求发出阶段匹配，不下载内容。
     *
     * 用 `inner` 是为了直接读写外层的**坏流指纹表**与 `onBadStream` 回调（P1-4）。
     * 实例生命周期与本次嗅探一致（挂到 WebView 上、随 sniffResult 的 finally 释放），
     * 不会造成 SniffEngine 被长期持有。
     */
    private inner class SniffClient(
        private val result: CompletableDeferred<AdoptedUrl?>,
        /** 预编译规则（见 [SniffMatcher.compile]）—— 每条请求复用，不再重复拆字符串 */
        private val compiled: SniffMatcher.CompiledRules,
        private val onPageFailed: () -> Unit,
        /**
         * 命中排除列表：这些地址**本身不是视频**，是聚合页里 iframe 的 src
         * （解析器页面 URL）。不排除的话，iframe 导航请求会被当成"命中"——
         * 起播一个 HTML 页面，播放器报 NETWORK 错误后整条换线换源链全废
         * （真机实测：聚合页命中 `jx.m3u8.tv/jiexi/?url=...` 即此类）。
         */
        private val excludeUrls: List<String> = emptyList(),
        /** 嗅探目标页 referer，验证请求要原样带上 */
        private val referer: String? = null,
        /** 播放列表命中验证器（见 [SniffEngine] 构造参数） */
        private val verifier: (suspend (String, Map<String, String>?) -> Boolean)? = null,
        /** HLS 分片兜底候选（.ts/.m4s 命中降级存这里，见 [sniffResult]） */
        private val segmentFallback: AtomicReference<String?> = AtomicReference(null),
        /** 解析器域名弱命中的降级候选（P1-2） */
        private val parserFallback: AtomicReference<String?> = AtomicReference(null),
        /** TVBoxOS 复刻模式：命中走 [TvBoxParity.checkVideoFormat]，无验证、命中即采用 */
        private val parityMode: Boolean = false,
        /** 原始播放页地址（parity 模式 checkVideoFormat 的 webUrl 参数） */
        private val webUrl: String = "",
        private val timing: SniffTiming? = null,
        /** 本次嗅探的 UA（与 `webView.settings.userAgentString` 一致，见 [collectHeaders]） */
        private val ua: String? = null,
        /** onPageFinished 后的宽限窗口（聚合 1.2s / 单页 4s） */
        private val settleMs: Long = SETTLE_DELAY_MS,
    ) : WebViewClient() {

        @Volatile
        private var done = false

        /**
         * 验证作用域：**拦截线程绝不阻塞**（P0-1）。
         *
         * 改前是 `runBlocking { withTimeoutOrNull(2_500) { verifier(...) } }` ——
         * 直接跑在 `shouldInterceptRequest` 的调用线程上，验证期间该 WebView
         * 所有后续请求排队（注释自述真机实测一次拖 3.7s，是"点集到出画面"
         * 慢于参考项目的大头之一）。现在验证挪到独立 IO 协程，
         * 拦截线程当次就返回。
         */
        private val verifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 请求计数，用于未命中日志采样（见 [logMiss]） */
        private val reqCount = AtomicInteger(0)

        /** 子资源请求数（S2 失败分类：0 = JS 没跑/被反爬） */
        internal val subCount = AtomicInteger(0)

        /** 规则命中次数（S2：>0 但没采用 = 被降级/被挡） */
        internal val hitCount = AtomicInteger(0)

        /** 被 `excludeUrls` 挡掉的 iframe 导航数 */
        internal val excludedCount = AtomicInteger(0)

        /** 被坏流表挡掉的命中数（P1-4） */
        internal val rejectedCount = AtomicInteger(0)

        /** 被配置广告规则挡掉的子资源请求数（诊断用） */
        internal val adBlockedCount = AtomicInteger(0)

        /** 主文档加载错误数（S2：`main-doc-failed`） */
        internal val mainFrameError = AtomicInteger(0)

        /**
         * 解析器 iframe 自身的加载错误数（S2：`iframes-failed`）。
         * 只统计 URL 命中 [excludeUrls] 的失败 —— 广告图/埋点的 404 也走子资源错误回调，
         * 不筛掉会把"iframes-failed"误判成常态。
         */
        internal val iframeError = AtomicInteger(0)

        /**
         * 解析器自己的域名集合（从 [excludeUrls] 的 iframe src 推出）。
         * 用于 P1-2：识别"落在解析器域名上的弱命中"。
         */
        private val parserHosts: Set<String> = excludeUrls.mapNotNull { hostOf(it) }.toSet()

        private fun hostOf(url: String): String? =
            runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

        /**
         * S2 失败原因分类（对齐优化方案 §4 的六类）。
         *
         * 有了它，"嗅探失败"不再是一句话，而是能统计的指标：
         * - `main-doc-failed`  主文档就挂了（DNS/TLS/404）—— 换线就能好，与规则无关
         * - `no-sub-request`   主文档成功但一条子请求都没发 —— JS 没跑/被反爬（不是规则问题）
         * - `iframes-failed`   聚合页里 iframe 全部报错 —— 解析器整体不可用，该熔断的是解析器
         * - `no-hit`           有子请求但规则一条都没命中 —— 规则/后缀表问题
         * - `hit-in-bad-stream-list`  命中了但都在坏流表里
         * - `only-parser-domain`      只有解析器域名的弱命中（已降级兜底）
         * - `only-segment`    只有 .ts/.m4s 分片
         */
        fun failureReason(): String = when {
            mainFrameError.get() > 0 -> "main-doc-failed"
            subCount.get() == 0 -> "no-sub-request"
            hitCount.get() == 0 -> {
                // iframe 全灭（每个被 exclude 的 iframe 都报错）→ 不是规则问题，是解析器不可用
                if (excludedCount.get() > 0 && iframeError.get() >= excludedCount.get()) {
                    "iframes-failed"
                } else {
                    "no-hit"
                }
            }
            rejectedCount.get() > 0 -> "hit-in-bad-stream-list"
            parserFallback.get() != null -> "only-parser-domain"
            segmentFallback.get() != null -> "only-segment"
            else -> "no-adopt"
        }

        /**
         * 本次嗅探结束：**延迟**收掉验证作用域。
         *
         * 不能立即 cancel —— 命中即播后验证还在后台跑（0.7~1.1s），而
         * `sniffResult` 的 finally 在 complete 后马上就调这里，直接 cancel 等于
         * "验证永远跑不完" = 坏流反馈形同虚设（有声无画就再也发现不了）。
         * 给一个预算 + 余量再收，跑完的结果仍然有效。
         */
        fun close() {
            verifyScope.launch {
                delay(VERIFY_BUDGET_MS + 500)
                verifyScope.cancel()
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (done) return null
            val url = request.url?.toString().orEmpty()
            if (url.isBlank()) return null

            // 配置广告规则：只拦子资源。主文档若命中广告域名直接返回空响应，
            // 页面会白屏、嗅探必然失败，反而把"有广告"变成"播不了"。
            if (!request.isForMainFrame && adRules.isAd(url)) {
                adBlockedCount.incrementAndGet()
                adBlockedTotal.incrementAndGet()
                logMiss(url, "广告规则命中")
                return emptyResponse()
            }

            // "页面到底有没有发请求"是嗅探失败归因的第一分叉（S2-2）：
            // 有 firstReq/firstSub 但无命中 = 规则问题；连 firstSub 都没有 = JS 没跑/被反爬。
            timing?.mark("firstReq")
            if (!request.isForMainFrame) {
                timing?.mark("firstSub")
                subCount.incrementAndGet()
            }

            // 排除 iframe 自身地址（聚合页场景）：它们是解析器页面导航，不是视频流
            if (excludeUrls.any { url.startsWith(it) }) {
                excludedCount.incrementAndGet()
                logMiss(url, "聚合页自身地址")
                return null
            }

            // 请求发出阶段还没有响应头，拿不到 Content-Type，
            // 故先按 URL 判 —— 绝大多数场景（.m3u8/.mp4 后缀）足够可靠。
            val hit = if (parityMode) {
                // TVBoxOS 复刻：PlayFragment.checkVideoFormat 语义（url=http/.html 排除 + snifferMatch）
                if (TvBoxParity.checkVideoFormat(webUrl, url)) {
                    SniffMatcher.Hit(url, "TVBox复刻 checkVideoFormat")
                } else {
                    null
                }
            } else {
                SniffMatcher.matchCompiled(url, compiled)
            }
            if (hit != null) {
                hitCount.incrementAndGet()
                TbLog.tag(TAG).i( "嗅探命中（%s）：%s", hit.reason, hit.url.take(120))
            } else {
                logMiss(url, null)
            }

            if (hit == null) return null

            // P1-2：解析器**自己域名**上的弱命中 = 它的接口地址（`api.php?type=m3u8&…`），
            // 不是媒体。直接采用会起播 JSON/HTML → 播放器报容器错误 → 整条换线换源链重来（慢 10s+）。
            // 降级为最后一档兜底，继续等真正媒体域的命中；带真后缀的强命中不受影响。
            // parity 模式保持 TVBoxOS 原语义，不插这条。
            if (!parityMode && !hit.strong && parserHosts.isNotEmpty()) {
                val h = hostOf(hit.url)
                if (h != null && parserHosts.contains(h)) {
                    parserFallback.compareAndSet(null, hit.url)
                    TbLog.tag(TAG).i(
                        "弱命中落在解析器域名上，降级为兜底候选：%s（%s）", hit.url.take(110), hit.reason,
                    )
                    return null
                }
            }

            // HLS 分片不是正片：直接采用只能播 3~6 秒（真机实测"只解析出来几秒钟的视频"）。
            // 降级为兜底候选后继续等主播放列表（m3u8/mp4）命中；不阻断该请求
            // （分片很小，页面本来就在拉流）。
            if (!parityMode && isLikelyHlsSegment(hit.url)) {
                segmentFallback.compareAndSet(null, hit.url)
                TbLog.tag(TAG).i("HLS 分片命中，降级为兜底候选，继续等主媒体：%s", hit.url.take(120))
                return null
            }

            // 播放列表型命中（m3u8/mpd）：**命中即播**（P1-4）。
            // 旧实现要等验证通过才 complete，真流上实测多付 0.7~1.1s；
            // 现在立刻采用并把验证移到后台，判死时通过 [onBadStream] 让上层马上换线。
            // 真机实测两类坏流仍会被识破：① 音频-only（有声无画）；
            // ② 签名失效返回 403 JSON 被当容器解析（ExoPlaybackException 3003）。
            if (!parityMode && verifier != null && looksLikePlaylist(hit.url)) {
                if (isStreamRejected(hit.url)) {
                    // 已知坏流指纹：不采用（不回放给用户），继续等别的命中
                    rejectedCount.incrementAndGet()
                    TbLog.tag(TAG).i("命中在坏流表内，跳过继续等：%s", hit.url.take(120))
                    return null
                }
                // 只有真正"抢到采用权"的那次才发起验证：
                // 同一页面对同一 URL 可能同时发多个请求（`<video src>` + `fetch`），
                // 两个拦截线程都过了 `done` 检查时都会走到这里 —— 第二个会被 adopt 内的
                // done 挡掉，但**验证会被发起两次** → 重复上报坏流、重复换线（实测踩到）。
                if (adopt(hit.url, verified = false)) verifyInBackground(hit.url)
                return emptyResponse()
            }

            adopt(hit.url, verified = true)
            // 返回空响应：阻断 WebView 真去下载视频，避免浪费流量与一次性直链失效
            return emptyResponse()
        }

        /**
         * 命中后的**后台**验证（P1-4）。结果不再影响本次采用，只影响"要不要立刻换线"。
         *
         * 三条约束：
         * 1. **硬上限 [VERIFY_BUDGET_MS]**：await 是可取消挂起点，到点返回 null。
         * 2. **超时按通过**：不因为验证慢就把好流报成坏流（403/网络失败同理）。
         * 3. **判死才上报**：只有 verifier 明确说"不是视频流"才 `rejectStream` + `onBadStream`。
         */
        private fun verifyInBackground(mediaUrl: String) {
            val headers = collectHeaders(mediaUrl)
            verifyScope.launch {
                val verdict = withTimeoutOrNull(VERIFY_BUDGET_MS) {
                    async {
                        runCatching { verifier?.invoke(mediaUrl, headers) }.getOrDefault(true)
                    }.await()
                }
                when (verdict) {
                    false -> {
                        // 先记账再上报：下一次嗅探直接跳过这个指纹
                        rejectStream(mediaUrl)
                        TbLog.tag(TAG).w("命中即播后验证判定坏流，上报换线：%s", mediaUrl.take(120))
                        onBadStream?.invoke(mediaUrl)
                    }
                    null -> TbLog.tag(TAG).i(
                        "验证超时（%d ms）按通过处理（已先起播）：%s", VERIFY_BUDGET_MS, mediaUrl.take(120),
                    )
                    else -> TbLog.tag(TAG).i("命中通过视频验证（后台）：%s", mediaUrl.take(120))
                }
            }
        }

        /**
         * 采用命中（命中即播）：只允许第一个生效。
         *
         * @param verified false = 播放列表型命中，验证还在后台跑（见 [verifyInBackground]）
         * @return true = 本次抢到了采用权（调用方据此决定是否发起后台验证）
         */
        private fun adopt(url: String, verified: Boolean): Boolean {
            if (done) return false
            done = true
            timing?.mark("hit")
            return result.complete(AdoptedUrl(url, verified))
        }

        /**
         * 未命中/跳过日志（P0-6）。
         *
         * 一个播放页动辄上百条请求，每条都拼 `url.take(160)` 纯属浪费 ——
         * 而且这类日志在正常播放时没人看。策略：前 [MISS_LOG_LIMIT] 条全打
         * （排障需要看"页面到底发了什么"），之后每 [MISS_LOG_EVERY] 条采样一条。
         * 无 Timber 树时直接返回，连字符串都不拼。
         */
        private fun logMiss(url: String, why: String?) {
            if (TbLog.treeCount == 0) return
            val n = reqCount.incrementAndGet()
            if (n > MISS_LOG_LIMIT && n % MISS_LOG_EVERY != 0) return
            if (why != null) {
                TbLog.tag(TAG).d( "跳过%s（第 %d 条）：%s", why, n, url.take(120))
            } else {
                TbLog.tag(TAG).d( "嗅探未命中（第 %d 条）：%s", n, url.take(160))
            }
        }

        /**
         * HLS/fMP4 分片判定：URL 去掉 query/hash 后以 .ts/.m4s 结尾。
         * 这类命中是视频的**一小段**，当直链只能播 3~6 秒；.m3u8/.mp4 主资源才是正片。
         */
        private fun isLikelyHlsSegment(u: String): Boolean {
            val path = u.substringBefore('?').substringBefore('#')
            return path.endsWith(".ts", true) || path.endsWith(".m4s", true)
        }

        /**
         * 验证请求头：Cookie + UA + Referer，原样复现页面上下文。
         *
         * ## 为什么不再传 WebView 进来（真机/模拟器实测修）
         * 改前这里读 `view.url` 和 `view.settings` —— 而本方法运行在
         * `shouldInterceptRequest` 的**拦截线程**上，`WebView.getUrl()/getSettings()`
         * 是 WebView 方法，只允许在主线程调用。实测每次命中都刷两条
         * `StrictMode: WebViewMethodCalledOnWrongThreadViolation`（debug 下还有
         * `java.lang.Throwable: A WebView method was called on thread 'ThreadPoolForeg'`），
         * 属于"没崩但随时可能崩"的隐患。
         *
         * 现在：UA 由 [SniffEngine] 传入（就是设进 `settings.userAgentString` 的那个值），
         * Cookie 直接按**媒体地址**取 —— 这比按页面地址取更正确：
         * 验证要请求的是媒体 URL，浏览器也只会带上媒体域的 Cookie。
         */
        private fun collectHeaders(mediaUrl: String): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            runCatching {
                val cookie = CookieManager.getInstance().getCookie(mediaUrl)
                if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
            }
            ua?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
            referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
            return headers
        }
        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: android.graphics.Bitmap?,
        ) {
            timing?.mark("pageStarted")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            // S2 计数：主文档失败（换线能解决） vs 解析器 iframe 失败（该熔断解析器）
            if (request != null) {
                if (request.isForMainFrame) {
                    mainFrameError.incrementAndGet()
                } else {
                    val reqUrl = request.url?.toString().orEmpty()
                    if (excludeUrls.any { reqUrl.startsWith(it) }) iframeError.incrementAndGet()
                }
            }
            // 只关心主文档失败；子资源（图片/埋点）失败一律忽略
            if (request?.isForMainFrame == true && !done) {
                done = true
                onPageFailed()
                result.complete(null)
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame == true && !done) {
                done = true
                onPageFailed()
                result.complete(null)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // 页面加载完 ≠ 嗅探失败：m3u8 常由页面 JS 异步发起（甚至在 load 之后）。
            // 这里不立即判死，给一个短宽限窗口；真正的兜底是外层超时。
            // 计时点：pageFinished → destroy 的差值里含 [SETTLE_DELAY_MS]，
            // 聚合页（多 iframe）实测这 4s 是"白等"的大头（见 docs 第 8 节）。
            timing?.mark("pageFinished")
            if (done) return
            view?.postDelayed({
                if (!done) {
                    done = true
                    result.complete(null)
                }
            }, settleMs)
        }

        /** 阻断响应体：1×1 透明像素，避免 WebView 拿到非法数据报错 */
        private fun emptyResponse(): WebResourceResponse {
            val headers = emptyMap<String, String>()
            return WebResourceResponse(
                "image/png", "png", 200, "OK", headers, ByteArrayInputStream(BLOCK_PNG),
            )
        }
    }

    companion object {
        private const val TAG = "SniffEngine"

        /** 总超时（方案 5.6 第 3 条：12s） */
        private const val TIMEOUT_MS = 12_000L

        /** 页面 onPageFinished 后给异步 JS 发起视频请求的宽限窗口（单页嗅探） */
        private const val SETTLE_DELAY_MS = 4_000L

        /**
         * 聚合页（多 iframe）的宽限窗口：**1.2s**（第 2 项）。
         *
         * 实测依据（轮次 D）：聚合腿 `pageFinished=6288 → destroy=10334`，
         * 差值 4046ms 几乎全是我们自己的 4s 宽限在空等 —— 8 个 iframe 早在
         * `firstSub=1283` 之后就没再发过媒体请求，等到 6.3s 全部 load 完已经足够。
         *
         * 单页为什么**不能**一起降到 1.2s：实测单页的 m3u8 是在
         * `pageFinished` 之后 **0.87s** 才由页面 JS 发出的（`pageFinished=1006 → hit=1881`），
         * 而且各家播放器初始化时间不一，4s 是留给它们的真实需求。
         */
        private const val SETTLE_DELAY_MS_AGGREGATED = 1_200L

        /**
         * 命中后异步验证的**硬预算**（P0-1）。
         *
         * 改前同步验证的预算是 2_500ms（且阻塞拦截线程），真机实测一次拖 3.7s。
         * 现在：超时按通过处理（宁可播坏流走换线链，也不阻塞好流），
         * 预算收到 1.2s —— 绝大多数 m3u8 首包在 300ms 内到，1.2s 已经足够判死
         * "图片轮播/音频-only"这类确定坏流。
         */
        private const val VERIFY_BUDGET_MS = 1_200L

        /** 坏流指纹的保留时长（P1-4）：5 分钟内同一 host+path 不再被采用 */
        private const val REJECT_TTL_MS = 5 * 60 * 1000L

        /**
         * 起播后再延迟多少毫秒才真正销毁 WebView（主线程排队优化）。
         * 见 [destroyQuietly] 的注释：销毁要占主线程，而续体也排在同一条队列上。
         */
        private const val DESTROY_POST_DELAY_MS = 100L

        /** 坏流指纹表上限（超过就先清过期，再不行整体清空） */
        private const val MAX_REJECTED = 64

        /** 未命中日志：前 N 条全打（排障要看页面发了什么） */
        private const val MISS_LOG_LIMIT = 60

        /** 未命中日志：之后每 N 条采样一条（避免上百条请求把 logcat 刷爆） */
        private const val MISS_LOG_EVERY = 20

        /** 1×1 透明 PNG，用于阻断命中请求的下载 */
        private val BLOCK_PNG: ByteArray = hexToBytes(
            "89504E470D0A1A0A0000000D4948445200000001000000010806000000" +
                "1F15C4890000000A49444154789C63000100000500010D0A2DB4" +
                "0000000049454E44AE426082",
        )

        /** WebView 组件是否可用（某些精简 ROM 没有 WebView） */
        fun isAvailable(): Boolean = runCatching {
            Class.forName("android.webkit.WebView")

            true
        }.getOrDefault(false)

        /** 是不是播放列表型地址（m3u8/mpd）—— 只有它需要"验证含视频" */
        internal fun looksLikePlaylist(url: String): Boolean {
            val u = url.lowercase()
            return u.contains("m3u8") || u.contains(".mpd")
        }

        /** 十六进制字符串 → 字节数组（避开 byteArrayOf 里满屏的 toByte()） */
        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filterNot { it.isWhitespace() }
            require(clean.length % 2 == 0) { "hex 长度必须为偶数：${clean.length}" }
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }

        /**
         * 多 iframe 聚合 HTML —— 对齐参考项目 `SuperParse.loadHtml`。
         *
         * type=0（WebView 嗅探型）解析器逐个串行试，最坏 N×12s；
         * 参考项目的做法是把**该 flag 下所有 type=0 解析器**拼进一页多 iframe，
         * 一次加载谁先出流拦谁。我们照做：
         * 一页并发、命中即停，超时从"逐个累加"变成"一次 12s"。
         *
         * @param parserPageUrls 解析器页面地址列表（已含 `?url=` 后缀或占位符展开）
         * @param targetUrl 待解析的播放页地址（iframe src 直接拼接它）
         */
        internal fun buildAggregatedHtml(parserPageUrls: List<String>, targetUrl: String): String {
            val iframes = parserPageUrls.joinToString("\n") { src ->
                "<iframe sandbox='allow-scripts allow-same-origin allow-forms' " +
                    "frameborder='0' allowfullscreen='true' webkitallowfullscreen='true' " +
                    "mozallowfullscreen='true' src=\"$src\"></iframe>"
            }
            return """
                <!doctype html>
                <html>
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width">
                <title>解析</title>
                </head>
                <body style="margin:0;background:#000">
                $iframes
                </body>
                </html>
            """.trimIndent()
        }
    }
}

/**
 * 被采用的命中。带上 [verified] 是为了让上层知道"这条是命中即播、验证还在后台跑"（P1-4），
 * 从而在坏流上报时能给出正确文案。
 */
private data class AdoptedUrl(val url: String, val verified: Boolean)

/**
 * 嗅探结果（供调用方区分成功/失败原因，给出准确的 UI 文案）。
 */
sealed interface SniffResult {
    data class Success(
        val url: String,
        val reason: String,
        /**
         * 播放直链所需的请求头（Cookie / UA / Referer）。
         *
         * ## 为什么要带上
         * 很多播放页在加载时种下 Cookie（访客 token、云解析签名），直链缺这个头会返回
         * 403 或空 m3u8。症状极具迷惑性：**解析成功 + 嗅探成功，然后 ExoPlayer 报
         * HTTP 错误** —— 看起来像源坏了，实际只是少一个请求头。
         *
         * 参考项目 `PlayFragment.checkIsVideo` 命中后正是这么做的：
         * `CookieManager.getInstance().getCookie(url)` → `headers.put("Cookie", ...)` → `playUrl(url, headers)`。
         */
        val headers: Map<String, String> = emptyMap(),
        /**
         * 是否已通过视频验证（P1-4「命中即播」）。
         *
         * `false` = 命中就被采用了，验证还在后台跑；若后台判定"确定坏流"，
         * 会通过 [SniffEngine.onBadStream] 回调上层立刻换线。
         */
        val verified: Boolean = true,
    ) : SniffResult
    data class Failure(val message: String) : SniffResult
}

/**
 * 单次嗅探的分点计时（P2-S1，第 1 批随 P0 一起落，否则改完无法证明变快）。
 *
 * 一行日志看全链路：哪一段是"provider 初始化"、哪一段是"页面自己慢"、
 * 命中到返回之间还有没有额外等待。字段说明：
 *
 * ```
 * new        WebView(context) 返回（首次含 provider 初始化，低端盒 0.5~1.5s）
 * settings   attachToWindow + applyBaseSettings 完成
 * load       loadUrl / loadDataWithBaseURL 调用返回
 * pageStarted   主文档开始加载（WebView 回调）
 * firstReq      页面的第一条被拦截请求
 * firstSub      第一条**子资源**请求（**关键分叉**：没有它 = JS 没跑/被反爬）
 * pageFinished  主文档 onPageFinished（之后还要等 SETTLE_DELAY_MS 宽限窗口）
 * hit           命中并采用（含异步验证等待，硬预算 1.2s）
 * awaited       `withTimeoutOrNull` 返回 —— 与 hit 的差值 = 恢复到本协程所排的主线程队列
 * headers       直链请求头收集完成（CookieManager 的同步 IPC 成本）
 * destroy       提交 WebView 销毁（不阻塞结果返回，见 destroyQuietly）
 * ```
 *
 * 只在有 Timber 树时输出；日志体控制在 ~200 字符内（华为 logcat 会吞超长消息）。
 */
internal class SniffTiming(private val label: String) {

    private val t0 = SystemClock.elapsedRealtime()

    /** 分点名 → 相对起点的毫秒数（mark 幂等，先到先记） */
    private val marks = ConcurrentHashMap<String, Long>()

    fun mark(name: String) {
        marks.putIfAbsent(name, SystemClock.elapsedRealtime() - t0)
    }

    fun dump(outcome: String) {
        if (TbLog.treeCount == 0) return
        val line = marks.entries
            .sortedBy { it.value }
            .joinToString(" ") { "${it.key}=${it.value}" }
        TbLog.tag("SniffEngine").i("SniffTiming %s %s | %s", outcome, line, label.take(48))
    }
}