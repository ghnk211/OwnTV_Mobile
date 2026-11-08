package tv.own.owntv.mobile.tvbox.repository

import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for VodPlayer's Room-backed SourceStatRepository.
 *
 * The ported SourceRepository calls five methods on it; the TVbox first version keeps the call
 * sites intact but holds nothing persistent: no source is ever excluded by stats (the user's
 * category locks and the kids filter are the only visibility gates), and success/failure records
 * live only for the current process. Auto-marking (VodPlayer's stale-source detection) is a no-op.
 */
class TvboxSourceStats {

    data class Stat(val success: Int = 0, val failure: Int = 0, val lastError: String? = null)

    private val stats = ConcurrentHashMap<String, Stat>()

    private fun keyOf(site: SiteConfig) = "${site.key}:${site.api}"

    /** Nothing is ever excluded: locks and the kids filter own visibility. */
    fun isExcluded(site: SiteConfig): Boolean = false

    fun statOf(site: SiteConfig): Stat? = stats[keyOf(site)]

    fun recordSuccess(site: SiteConfig, costMs: Long) {
        stats[keyOf(site)] = (stats[keyOf(site)] ?: Stat()).copy(success = (stats[keyOf(site)]?.success ?: 0) + 1)
    }

    fun recordFailure(site: SiteConfig, error: String) {
        val prev = stats[keyOf(site)] ?: Stat()
        stats[keyOf(site)] = prev.copy(failure = prev.failure + 1, lastError = error)
    }

    fun recordEmpty(site: SiteConfig, note: String) {
        // An empty result is not a failure; keep the note for diagnostics only.
    }

    suspend fun evaluateAutoMarks(sites: List<SiteConfig>) {
        // No auto-marking in the TVbox first version.
    }

    fun clear() = stats.clear()
}
