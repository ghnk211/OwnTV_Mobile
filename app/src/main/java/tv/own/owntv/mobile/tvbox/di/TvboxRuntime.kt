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
    private val provisioner: tv.own.owntv.mobile.tvbox.repository.TvboxSourceProvisioner,
    private val indexer: tv.own.owntv.mobile.tvbox.index.TvboxIndexer,
) {

    /**
     * The index maintenance runs once per process, not once per restore. `restore()` is called from
     * the VOD screen's view model, so a user walking in and out of that screen would otherwise pay
     * for a table-wide sweep every time.
     */
    @Volatile
    private var indexMaintained = false

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
        // One configuration is one Core TVBOX source; only the active one participates in reads.
        // Hooked here rather than in a ViewModel so every path (import, switch, delete, rename)
        // provisions in the background before a screen can look for the source id.
        config.onConfigsChanged = { provisioner.syncAll() }
    }

    /** Cold start: restore the active config (cache first, then re-fetch by its source URL). */
    suspend fun restore() {
        config.purgeInvalidJars()
        config.restoreActive()
        maintainIndex(provisioner.syncAll())
    }

    /**
     * Plan §5's index lifecycle, which used to have no trigger at all: the TTL sweep and the row cap
     * were written, tested by hand, and never called — so the index only ever grew.
     *
     * Runs after the sources exist (a prune needs their ids), over every configuration rather than
     * just the active one: a config the user switched away from keeps its rows and must age out too.
     */
    private suspend fun maintainIndex(sourceIds: List<Long>) {
        if (indexMaintained) return
        indexMaintained = true
        sourceIds.forEach { id -> runCatching { indexer.maintain(id) } }
    }
}
