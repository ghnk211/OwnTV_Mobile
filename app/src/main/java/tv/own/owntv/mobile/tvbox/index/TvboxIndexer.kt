package tv.own.owntv.mobile.tvbox.index

import tv.own.owntv.core.catalog.CatalogIndexWriter
import tv.own.owntv.core.database.dao.CatalogIndexCursorDao
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.entity.CatalogIndexCursorEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository

/**
 * The TVbox side of the catalogue index: map what was just seen and hand it to
 * [CatalogIndexWriter]. Only "seen" content is indexed (P0/P4 scope decision): no background
 * pre-indexing of whole categories.
 *
 * Every entry point is best-effort. A failure to index must never break browsing or playback — it
 * only costs the row its Core identity until the next sighting.
 */
class TvboxIndexer(
    private val configRepository: TvboxConfigRepository,
    private val writer: CatalogIndexWriter,
    private val cursorDao: CatalogIndexCursorDao,
    private val categoryDao: CategoryDao,
) {

    suspend fun indexClasses(sourceId: Long, site: SiteConfig, classes: List<ClassInfo>): Map<String, Long> {
        val rows = classes.mapIndexedNotNull { index, info ->
            TvboxIndexMapper.category(sourceId, site, info, index)
        }
        return runCatching { writer.upsertCategories(sourceId, MOVIE, rows) }.getOrDefault(emptyMap())
    }

    suspend fun indexHome(
        sourceId: Long,
        site: SiteConfig,
        movies: List<Movie>,
        categoryId: Long? = null,
    ): Int = index(sourceId, site, movies, categoryId, categoryTypeName = null)

    suspend fun indexCategoryPage(
        sourceId: Long,
        site: SiteConfig,
        typeId: String,
        typeName: String,
        movies: List<Movie>,
        page: Int,
    ): Int {
        // The category row was upserted by indexClasses() when the site was opened; resolving it here
        // is what gives every film a kids/lock attribution (`movies.categoryId`). Without it the
        // column stays null for the whole TVBOX source and the kids filter has nothing to filter.
        val categoryId = categoryIdOf(sourceId, site.key, typeId)
        val written = index(sourceId, site, movies, categoryId, categoryTypeName = typeName)
        runCatching {
            val categoryRemoteId = TvboxIndexMapper.categoryRemoteId(site.key, typeId)
            // REPLACE would reset the generation a filter change bumped; carry it across page writes.
            val generation = cursorDao.get(sourceId, categoryRemoteId)?.generation ?: 0
            cursorDao.save(
                CatalogIndexCursorEntity(
                    sourceId = sourceId,
                    categoryRemoteId = categoryRemoteId,
                    lastPage = page,
                    generation = generation,
                    syncedAt = System.currentTimeMillis(),
                ),
            )
        }
        return written
    }

    /**
     * The remote filter changed (plan §4.8 B2): the pages indexed under the old filter no longer
     * describe this category's pagination. Mark the cursor; the stale rows age out through the TTL
     * sweep, and the next unfiltered REFRESH starts the page count over.
     */
    suspend fun bumpGeneration(sourceId: Long, siteKey: String, typeId: String) {
        runCatching { cursorDao.bumpGeneration(sourceId, TvboxIndexMapper.categoryRemoteId(siteKey, typeId)) }
    }

    /**
     * Search results have no category attribution: `categoryId = null`, and the writer keeps a
     * category the row already had. A site that is no longer in the active configuration is skipped
     * rather than overwriting the old index with a null-identity row.
     */
    suspend fun indexSearchResults(sourceId: Long, siteKey: String, movies: List<Movie>): Int {
        val site = configRepository.sites.value.firstOrNull { it.key == siteKey } ?: return 0
        return index(sourceId, site, movies, categoryId = null, categoryTypeName = null)
    }

    /** Index one row and return its local id — the id user data (favourite, progress) is keyed by. */
    suspend fun ensureIndexed(
        sourceId: Long,
        site: SiteConfig,
        movie: Movie,
        categoryId: Long? = null,
        categoryTypeId: String? = null,
        categoryTypeName: String? = null,
    ): Long? = runCatching {
        val resolvedCategory = categoryId
            ?: categoryTypeId?.takeIf { it.isNotBlank() }?.let { categoryIdOf(sourceId, site.key, it) }
        val entity = TvboxIndexMapper.movie(
            movie = movie,
            sourceId = sourceId,
            categoryId = resolvedCategory,
            site = site,
            donor = donorOf(movie),
            now = System.currentTimeMillis(),
            categoryTypeName = categoryTypeName,
        ) ?: return null
        writer.upsertMovie(sourceId, entity, TvboxIndexMapper::mergeExtJson)
    }.getOrNull()

    suspend fun markSynced(sourceId: Long) {
        runCatching { writer.markSynced(sourceId) }
    }

    /**
     * Age the index out and drop the orphans that creates: the 30-day TTL, the per-source row cap and
     * the empty-category sweep (plan §5).
     *
     * **Nothing on a browse path may call this.** It is a table-wide DELETE with five user-data
     * sub-queries plus five `purgeOrphans` passes; [TvboxRuntime] runs it once per process, off the
     * first paint, which is the only cadence the TTL needs — a row is refreshed by `markSeen` every
     * time it is seen, so a daily sweep would only ever find rows that were already stale.
     */
    suspend fun maintain(
        sourceId: Long,
        ttlDays: Long = INDEX_TTL_DAYS,
        maxRows: Int = INDEX_MAX_ROWS,
    ): Int = runCatching {
        writer.prune(sourceId, System.currentTimeMillis() - ttlDays * DAY_MS, maxRows)
    }.getOrDefault(0)

    private suspend fun index(
        sourceId: Long,
        site: SiteConfig,
        movies: List<Movie>,
        categoryId: Long?,
        categoryTypeName: String?,
    ): Int {
        val now = System.currentTimeMillis()
        val rows = movies.mapNotNull { movie ->
            TvboxIndexMapper.movie(
                movie = movie,
                sourceId = sourceId,
                categoryId = categoryId,
                site = site,
                donor = donorOf(movie),
                now = now,
                categoryTypeName = categoryTypeName,
            )
        }
        if (rows.isEmpty()) return 0
        return runCatching {
            writer.upsertMovies(sourceId, rows, TvboxIndexMapper::mergeExtJson)
        }.getOrDefault(0)
    }

    /**
     * A borrowed row's detail/playback only works on the donor site. Null here means "not borrowed";
     * a non-null `donorSiteKey` with no matching site means the donor was removed from the
     * configuration, and [TvboxIndexMapper.movie] then refuses the row.
     */
    private fun donorOf(movie: Movie): SiteConfig? = movie.donorSiteKey?.let { key ->
        configRepository.sites.value.firstOrNull { it.key == key }
    }

    /**
     * The local `categories.id` a provider category was upserted as.
     *
     * A miss is normal and must stay harmless: a site opened for the first time may index a page
     * before `indexClasses` committed, and a category can be swept as empty after a TTL pass. The
     * row then keeps `categoryId = null` and the next category browse fills it in.
     */
    private suspend fun categoryIdOf(sourceId: Long, siteKey: String, typeId: String): Long? =
        runCatching {
            categoryDao
                .findByRemoteIds(sourceId, MOVIE, listOf(TvboxIndexMapper.categoryRemoteId(siteKey, typeId)))
                .firstOrNull()
                ?.id
        }.getOrNull()

    private companion object {
        val MOVIE = MediaType.MOVIE

        /** Plan §5: a row no provider has shown for 30 days may go. */
        const val INDEX_TTL_DAYS = 30L

        /** Plan §5: per-source cap, oldest `lastSeenAt` first. */
        const val INDEX_MAX_ROWS = 50_000

        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}