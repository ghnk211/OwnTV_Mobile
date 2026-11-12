package tv.own.owntv.mobile.tvbox.engine.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.util.TbLog

/**
 * JS 源预热管线（JS 版 `launchSpiderPrefetch`）。
 *
 * 配置生效后 JS 源零预热时，首个 JS 源首屏要在业务超时预算内完成全部冷启动
 * （脚本下载 + QuickJS 编译）。这里把 init 移出首屏：
 * - **顺序**：`priorityKeys`（默认/上次使用）优先，其余按配置原顺序（稳定排序）；
 * - **动作**：LRU 名额内 `obtain + init`；名额外只预下载脚本；
 * - **节流**：并发 2，每源间隔 100ms，不占 `SourceRepository.globalSemaphore`；
 * - **插队**：[warmSoon] 供聚合搜索对冷源即点即热。
 *
 * 本仓 `SourceHealth` 没有 rank API，故不接收成功率排名。
 * 移植并改造自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsWarmUp.kt`。
 */
class JsWarmUp(
    private val engine: JsEngine,
    private val scope: CoroutineScope,
) {
    private val gate = Semaphore(2)

    fun warmAll(
        sites: List<SiteConfig>,
        priorityKeys: List<String> = emptyList(),
    ) {
        val jsSites = sites.filter { JsEngine.supportsSite(it) }
        if (jsSites.isEmpty()) return
        val priorityIndex = priorityKeys.withIndex().associate { (i, k) -> k to i }
        val ordered = jsSites.sortedBy { priorityIndex[it.key] ?: Int.MAX_VALUE }
        TbLog.tag(TAG).i(
            "JS 预热启动：%d 个源（优先=%s）",
            ordered.size, priorityKeys.joinToString("/").ifBlank { "无" },
        )
        scope.launch {
            ordered.forEach { site ->
                launch { warmOne(site) }
                delay(INTERVAL_MS)
            }
        }
    }

    /**
     * 插队预热单个源：**强制 obtain+init**（允许临时挤占 LRU 名额）。
     * 用户马上就要搜它，只下载脚本不够 —— context 不就绪的话下一轮搜索仍会被跳过。
     */
    fun warmSoon(site: SiteConfig) {
        if (!JsEngine.supportsSite(site)) return
        scope.launch { warmOne(site, forceInit = true) }
    }

    private suspend fun warmOne(site: SiteConfig, forceInit: Boolean = false) {
        gate.withPermit {
            if (engine.isInitialized(site)) return@withPermit
            if (forceInit || engine.liveCount() < JsEngine.MAX_LIVE_CONTEXTS) {
                val spider = engine.obtain(site)
                if (!spider.initialized) {
                    runCatching { spider.init(site.ext) }
                        .onFailure { TbLog.tag(TAG).w(it, "预热 init 失败（静默）：%s", site.name) }
                }
            } else {
                engine.prefetchScripts(site)
            }
        }
    }

    private companion object {
        const val TAG = "JsWarmUp"
        const val INTERVAL_MS = 100L
    }
}