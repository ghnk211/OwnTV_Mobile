package tv.own.owntv.mobile.tvbox.play

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryLock
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryPolicy
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ParseRepository
import tv.own.owntv.mobile.tvbox.engine.PlayLine
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.PlayUrlCodec
import tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub
import tv.own.owntv.mobile.tvbox.engine.sniff.SniffEngine
import tv.own.owntv.mobile.tvbox.engine.sniff.SniffResult
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.TbLog
import tv.own.owntv.mobile.tvbox.util.UaUtil
import tv.own.owntv.mobile.tvbox.util.legalHeaders
import tv.own.owntv.mobile.tvbox.util.toHeaderMap

/**
 * TVbox 播放地址解析（方案 §7 第 16 步）：`playerContent → ResolvedPlay`。
 *
 * 只做解析编排，**不含任何播放器**（红线：不引 PlayerManager/Media3）。保留
 * VodPlayer `PlayViewModel.resolveAndPlay` 的核心分支顺序：
 * 冷却 → playerContent → playUrl 前缀 → 本机代理 → 命名解析器 → 嗅探 → 直连。
 *
 * 锁/儿童双重校验只在 [resolve] 里做（方案 §5.5.6 第三层）；下载重试走 [resolveUrl]，
 * 不因播放期间锁状态变化中断已授权的传输。
 */
class TvboxPlayResolver(
    private val sourceRepository: SourceRepository,
    private val configRepository: TvboxConfigRepository,
    private val parseRepository: ParseRepository,
    private val sniffEngine: SniffEngine,
    private val profileDao: ProfileDao,
    private val categoryPolicy: TvboxCategoryPolicy,
    private val categoryLock: TvboxCategoryLock,
    private val provisioner: tv.own.owntv.mobile.tvbox.repository.TvboxSourceProvisioner,
) {

    sealed interface Outcome {
        data class Ready(val play: ResolvedPlay) : Outcome

        /** 被儿童模式/分类锁拒绝；文案由 UI 出。 */
        data class Blocked(val byKids: Boolean) : Outcome

        data class Error(val message: String) : Outcome
    }

    private sealed interface StreamOutcome {
        data class Ready(val stream: ResolvedStream) : StreamOutcome
        data class Error(val message: String) : StreamOutcome
    }

    suspend fun resolve(
        profileId: Long,
        site: SiteConfig,
        vodId: String,
        flag: String,
        episodeId: String,
        episodeIndex: Int,
        categoryKey: TvboxCategoryKey,
        detailLines: List<PlayLine>,
        title: String,
        subtitle: String?,
        posterUrl: String?,
    ): Outcome {
        // ---- 锁/儿童强制校验（详情层已查过，这里按方案再查一次） ----
        val profile = profileDao.getById(profileId)
        val blockedKids = categoryPolicy.blockedByKids(profile, site, categoryKey.typeName)
        val sealed = categoryLock.seals(profileId, provisioner.activeSourceId(), categoryKey.siteKey, categoryKey.typeId, categoryKey.typeName)
        if (blockedKids) return Outcome.Blocked(byKids = true)
        if (sealed) return Outcome.Blocked(byKids = false)

        return when (val result = resolveStream(site, flag, episodeId, countHealth = true)) {
            is StreamOutcome.Error -> Outcome.Error(result.message)
            is StreamOutcome.Ready -> resolvedPlay(
                url = result.stream.url,
                headers = result.stream.headers,
                site = site,
                vodId = vodId,
                episodeId = episodeId,
                episodeIndex = episodeIndex,
                categoryKey = categoryKey,
                detailLines = detailLines,
                title = title,
                subtitle = subtitle,
                posterUrl = posterUrl,
            )
        }
    }

    /**
     * Resolve without the lock/kids checks, for a download retry or a headless Worker.
     *
     * [countHealth] must be false for background work: a download that hits an expired direct URL
     * is not the user asking the site for something, and must not push the source into cooldown.
     *
     * Returns null on any failure; the engine maps that to `SOURCE_UNAVAILABLE`. This is intentional
     * — a download row stores an identity, not a URL, so there is no stale address to fall back to.
     */
    suspend fun resolveUrl(
        site: SiteConfig,
        flag: String,
        episodeId: String,
        countHealth: Boolean = true,
    ): ResolvedStream? = when (val result = resolveStream(site, flag, episodeId, countHealth)) {
        is StreamOutcome.Ready -> result.stream
        is StreamOutcome.Error -> null
    }

    private suspend fun resolveStream(
        site: SiteConfig,
        flag: String,
        episodeId: String,
        countHealth: Boolean,
    ): StreamOutcome {
        if (SourceHealth.isCooling(site.key)) {
            return StreamOutcome.Error("「${site.name}」已冷却，请切换线路或稍后重试")
        }

        val result = runCatching {
            sourceRepository.playerContent(site, flag, episodeId, null, countHealth)
        }.getOrElse {
            TbLog.tag(TAG).w(it, "playerContent 失败：%s", site.key)
            null
        }
        if (result == null) {
            return StreamOutcome.Error("解析失败，可尝试切换线路或换源")
        }

        val prefixed = applyPlayUrlPrefix(site, result)

        // 本机回环代理地址：jar 侧约定，本身就是可播直链
        val localProxy = JarProxyHub.isSelfProxyUrl(prefixed.url)
        if (localProxy && !JarProxyHub.isReady) {
            return StreamOutcome.Error("该源需要本机代理服务，服务未就绪，请重试或切换线路")
        }

        // 命名解析器（配置 parses 数组）：type=1/2 的 JSON 解析
        if (!localProxy) {
            val parseCfg = parseRepository.pick(prefixed, configRepository.activeConfig.value?.parses.orEmpty())
            if (parseCfg != null) {
                val parsed = runCatching {
                    parseRepository.resolve(parseCfg, prefixed.url)
                }.getOrNull()
                val directOutcome = parsed as? ParseRepository.Outcome.Direct
                val finalUrl = directOutcome?.url?.takeIf { it.isNotBlank() }
                if (finalUrl != null) {
                    TbLog.tag(TAG).i("命名解析器命中：%s -> %s", parseCfg.name, finalUrl.take(120))
                    return StreamOutcome.Ready(
                        ResolvedStream(
                            finalUrl,
                            buildHeaders(finalUrl, prefixed.header, site, directOutcome.header),
                        ),
                    )
                }
                // 解析器失败 → 落到嗅探兜底
                TbLog.tag(TAG).w("解析器 %s 未出直链，转嗅探", parseCfg.name)
            }
        }

        // 嗅探兜底：parse=1 或地址本身是网页
        val needSniff = !localProxy && (prefixed.needSniff || sniffEngine.needsSniff(prefixed.url))
        if (needSniff) {
            val ua = sniffUa(prefixed)
            // SniffEngine creates/reads a WebView; its settings access requires the main thread.
            // A download resolver calls this from WorkManager's IO coroutine, so switch here
            // rather than wrapping the whole resolve (network calls must stay off the main thread).
            val snipped = withContext(Dispatchers.Main) {
                runCatching {
                    sniffEngine.sniffResult(prefixed.url, site.key, ua = ua, referer = originOf(prefixed.url))
                }.getOrNull()
            }
            val success = snipped as? SniffResult.Success
                ?: return StreamOutcome.Error("嗅探解析失败，请切换线路或换源")
            TbLog.tag(TAG).i("嗅探成功：%s", success.url.take(120))
            return StreamOutcome.Ready(
                ResolvedStream(success.url, buildHeaders(success.url, prefixed.header, site, success.headers)),
            )
        }

        // 直连
        val finalUrl = prefixed.url
        if (finalUrl.isBlank()) return StreamOutcome.Error("解析结果为空地址")
        return StreamOutcome.Ready(ResolvedStream(finalUrl, buildHeaders(finalUrl, prefixed.header, site)))
    }

    /** 直连出口：补齐 UA/Referer 后组装 [ResolvedPlay]。 */
    private fun resolvedPlay(
        url: String,
        headers: Map<String, String>,
        site: SiteConfig,
        vodId: String,
        episodeId: String,
        episodeIndex: Int,
        categoryKey: TvboxCategoryKey,
        detailLines: List<PlayLine>,
        title: String,
        subtitle: String?,
        posterUrl: String?,
    ): Outcome {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val lineIndex = detailLines.indexOfFirst { l -> l.episodes.any { it.id == episodeId } }
        return Outcome.Ready(
            ResolvedPlay(
                url = url,
                headers = headers,
                userAgent = ua,
                lines = detailLines,
                lineIndex = lineIndex.coerceAtLeast(0),
                episodeIndex = episodeIndex,
                title = title,
                subtitle = subtitle,
                posterUrl = posterUrl,
                categoryKey = categoryKey,
                siteKey = site.key,
                vodId = vodId,
            ),
        )
    }

    private fun buildHeaders(
        url: String,
        header: kotlinx.serialization.json.JsonObject?,
        site: SiteConfig,
        extraHeaders: Map<String, String>? = null,
    ): Map<String, String> = buildMap {
        header?.toHeaderMap()?.forEach { (k, v) -> put(k, v) }
        extraHeaders?.forEach { (k, v) -> put(k, v) }
        if (none { it.key.equals("User-Agent", ignoreCase = true) }) {
            put("User-Agent", UaUtil.uaFor(site.key))
        }
        if (none { it.key.equals("Referer", ignoreCase = true) }) {
            put("Referer", originOf(url))
        }
    }.legalHeaders()

    /** 站点级播放解析前缀（生态 playUrl）：非直链地址拼上它再解析/嗅探。 */
    private fun applyPlayUrlPrefix(site: SiteConfig, result: PlayResult): PlayResult {
        val prefix = site.playUrl?.takeIf { it.isNotBlank() } ?: return result
        if (JarProxyHub.isSelfProxyUrl(result.url)) return result
        return if (result.isDirect || tv.own.owntv.mobile.tvbox.util.TextUtil.looksLikeStream(result.url) || result.url.startsWith(prefix)) {
            result
        } else {
            TbLog.tag(TAG).i("应用站点级 playUrl 前缀：%s", prefix.take(80))
            result.copy(url = prefix + result.url)
        }
    }

    /** 嗅探 UA：源给的 UA 优先，否则移动 UA（桌面 UA 会拿到 PC 版反爬更强的页面）。 */
    private fun sniffUa(result: PlayResult): String =
        result.header?.toHeaderMap()?.entries
            ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotBlank() }
            ?: UaUtil.DEFAULT_UA

    private fun originOf(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme ?: "https"}://${u.host}"
    }.getOrDefault("")

    /** 详情页线路/选集解析（供 UI 与 resolve 共用）。 */
    fun parseLines(playUrl: String?, playFrom: String?): List<PlayLine> =
        PlayUrlCodec.parse(playUrl, playFrom)

    private companion object {
        const val TAG = "PlayResolver"
    }
}