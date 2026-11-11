package tv.own.owntv.mobile.tvbox.search

import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryPolicy
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.repository.SourceSearchResult
import tv.own.owntv.mobile.tvbox.util.TextUtil

/**
 * One online (TVbox site) search hit, shaped for the global search page. [siteKey] is the *owner*
 * site — a borrowed row carries its donor's key, because its detail/playback only resolves there.
 */
data class OnlineSearchRow(
    val siteKey: String,
    val vodId: String,
    val title: String,
    val posterUrl: String?,
    val remark: String?,
    val year: String?,
    val typeName: String?,
) {
    /** The identity the Core index uses for the same title (`MovieEntity.remoteId`). */
    val remoteId: String get() = "$siteKey/$vodId"
}

/** A site's hits under one heading in the online section. */
data class OnlineSearchGroup(
    val siteKey: String,
    val siteName: String,
    val rows: List<OnlineSearchRow>,
)

/**
 * The online section's whole state. [hasSites] is false when the active configuration has no
 * searchable site, so the screen can hide the section entirely rather than show an empty one.
 */
data class OnlineSearchState(
    val searching: Boolean = false,
    val hasSites: Boolean = false,
    val groups: List<OnlineSearchGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty()
    val rowCount: Int get() = groups.sumOf { it.rows.size }
}

/**
 * Pure shaping of raw aggregate-search results into the online section: kids filter, poster
 * absolutisation, donor ownership, per-site grouping and cap. No I/O, so it is unit-testable and the
 * single place the online section's filtering rules live (it reuses [TvboxCategoryPolicy], never a
 * second word list — plan §4.5).
 */
object OnlineSearchAdapter {

    /** A site can return a whole page; the section shows the head of each, not an endless list. */
    const val SITE_ROWS_CAP = 20

    /**
     * Shape the results accumulated so far. Encounter order is preserved (first site done, first
     * heading), which is what makes the section stream. A result whose site is gone from the
     * configuration is skipped rather than rendered with a null identity.
     */
    fun shape(
        results: List<SourceSearchResult>,
        profile: ProfileEntity?,
        policy: TvboxCategoryPolicy,
        siteOf: (String) -> SiteConfig?,
        capPerSite: Int = SITE_ROWS_CAP,
    ): OnlineSearchState {
        val bySite = LinkedHashMap<String, MutableList<OnlineSearchRow>>()
        val names = HashMap<String, String>()
        for (r in results) {
            val site = siteOf(r.siteKey) ?: continue
            for (m in r.movies) {
                if (!policy.movieAllowed(profile, site, m)) continue
                val owner = m.donorSiteKey ?: r.siteKey
                val ownerSite = siteOf(owner)
                val row = OnlineSearchRow(
                    siteKey = owner,
                    vodId = m.id,
                    title = m.name,
                    posterUrl = TextUtil.absoluteUrl(m.pic, site.api).ifBlank { null },
                    remark = m.remark,
                    year = m.year,
                    typeName = m.typeName,
                )
                bySite.getOrPut(owner) { mutableListOf() }.add(row)
                names[owner] = ownerSite?.name ?: site.name
            }
        }
        val groups = bySite.map { (key, rows) ->
            OnlineSearchGroup(key, names[key] ?: key, rows.take(capPerSite))
        }
        return OnlineSearchState(groups = groups)
    }

    /**
     * Drop online rows the local index already shows, keyed by `siteKey/vodId` (plan P3: the online
     * section supplements the index, it does not repeat it). A group left empty is removed.
     */
    fun dedupe(state: OnlineSearchState, seenRemoteIds: Set<String>): OnlineSearchState {
        if (seenRemoteIds.isEmpty() || state.groups.isEmpty()) return state
        val groups = state.groups.mapNotNull { g ->
            val rows = g.rows.filterNot { it.remoteId in seenRemoteIds }
            if (rows.isEmpty()) null else g.copy(rows = rows)
        }
        return state.copy(groups = groups)
    }
}