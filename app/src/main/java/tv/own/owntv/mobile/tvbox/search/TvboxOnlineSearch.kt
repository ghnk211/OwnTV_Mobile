package tv.own.owntv.mobile.tvbox.search

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryPolicy
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.index.TvboxIndexer
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.SourceSearchResult
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxSourceProvisioner

/**
 * The online half of the global search page: run the TVbox aggregate search for a keyword and stream
 * shaped snapshots as each site answers (plan P3 — the "online results" section).
 *
 * Cancellation is the whole point of the shape here: [SourceRepository.aggregateSearch] runs in the
 * caller's scope, so when the search page's `flatMapLatest` swaps keywords this flow is cancelled and
 * every in-flight site request stops with it — no late emissions from the previous term.
 *
 * What was seen is indexed (best-effort) under the active configuration's Core source, so the next
 * search finds it locally; the online section then dedupes it away (see [OnlineSearchAdapter.dedupe]).
 */
class TvboxOnlineSearch(
    private val sourceRepository: SourceRepository,
    private val configRepository: TvboxConfigRepository,
    private val policy: TvboxCategoryPolicy,
    private val profileDao: ProfileDao,
    private val provisioner: TvboxSourceProvisioner,
    private val indexer: TvboxIndexer,
) {

    fun search(keyword: String, profileId: Long): Flow<OnlineSearchState> = flow {
        val profile = profileDao.getById(profileId)
        val sites = policy.visibleSites(profile, configRepository.sites.value)
            .filter { it.canSearch && it.isSupported }
        if (sites.isEmpty()) {
            emit(OnlineSearchState(hasSites = false))
            return@flow
        }
        val siteOf: (String) -> SiteConfig? = { configRepository.siteMap[it] }
        val visibleKeys = sites.map { it.key }.toSet()
        emit(OnlineSearchState(searching = true, hasSites = true))

        // aggregateSearch calls onProgress from its per-site children; bridge those into this single
        // flow through a channel so shaping and emission stay on the flow's own coroutine.
        val events = Channel<SourceSearchResult>(Channel.UNLIMITED)
        val accumulated = mutableListOf<SourceSearchResult>()
        coroutineScope {
            val job = launch {
                val all = sourceRepository.aggregateSearch(
                    keyword,
                    onlySiteKeys = visibleKeys,
                    onProgress = { events.trySend(it) },
                )
                indexSeen(all, profile, siteOf)
                events.close()
            }
            for (r in events) {
                accumulated += r
                emit(OnlineSearchAdapter.shape(accumulated, profile, policy, siteOf).copy(searching = true, hasSites = true))
            }
            job.join()
        }
        emit(OnlineSearchAdapter.shape(accumulated, profile, policy, siteOf).copy(searching = false, hasSites = true))
    }

    /** Index only the hits the profile may see, the same rule the VOD search indexed by. */
    private suspend fun indexSeen(
        results: List<SourceSearchResult>,
        profile: ProfileEntity?,
        siteOf: (String) -> SiteConfig?,
    ) {
        val sourceId = provisioner.activeSourceId() ?: return
        for (r in results) {
            if (!r.isSuccess) continue
            val site = siteOf(r.siteKey) ?: continue
            val allowed = r.movies.filter { policy.movieAllowed(profile, site, it) }
            if (allowed.isNotEmpty()) indexer.indexSearchResults(sourceId, r.siteKey, allowed)
        }
    }
}