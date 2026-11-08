package tv.own.owntv.mobile.tvbox.di

import tv.own.owntv.mobile.tvbox.engine.cms.CmsEngine
import tv.own.owntv.mobile.tvbox.engine.jar.JarEngine
import tv.own.owntv.mobile.tvbox.engine.sniff.SniffEngine
import tv.own.owntv.mobile.tvbox.net.OkHttpProvider
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.tvbox.util.SourceHealth

/**
 * Wires the config repository's lifecycle hooks into the engines and the network/sniff layers —
 * the same glue VodPlayer's ServiceLocator did by hand.
 *
 * Resolved eagerly by [TvboxModule]; the assignments in `init` must happen before any UI can
 * import a config, otherwise `applyConfig` would silently no-op its side effects.
 */
class TvboxRuntime(
    private val config: TvboxConfigRepository,
    private val sourceRepository: SourceRepository,
    private val cmsEngine: CmsEngine,
    private val jarEngine: JarEngine,
    private val sniffEngine: SniffEngine,
) {

    init {
        config.onConfigApplied = { cfg ->
            sniffEngine.setGlobalRules(cfg.rules)
        }
        config.onSpiderWarmUp = { jarEngine.warmUp() }
        config.onSwitchHook = { _ ->
            OkHttpProvider.onConfigSwitched()
            cmsEngine.invalidateAll()
            jarEngine.invalidateAll()
            sourceRepository.invalidateAll()
            SourceHealth.clear()
        }
    }

    /** Cold start: restore the active config (cache first, then re-fetch by its source URL). */
    suspend fun restore() {
        config.purgeInvalidJars()
        config.restoreActive()
    }
}
