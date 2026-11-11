package tv.own.owntv.core.catalog

import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.MovieExtSnapshot
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.database.transaction
import tv.own.owntv.core.model.MediaType

/**
 * Writes a provider's catalogue into the ordinary `categories` / `movies` tables without the sync
 * machinery — used by the app's indexed-catalogue crawlers ([MediaType.MOVIE] only, for now).
 *
 * The one rule that cannot be broken: **never `movieDao.upsertAll`**. That is an
 * `@Insert(REPLACE)`, so every re-index deletes the row and inserts a new one with a new `id`, and
 * every favourite, resume position and history entry pointing at the old id becomes an orphan. This
 * class mirrors `SyncSupport.upsertStable` instead: diff by content hash, update in place, insert
 * only what is new.
 *
 * The caller passes [mergeExtJson] where the provider accumulates fields (TVBOX's `typeNames`); it
 * runs **before** hashing, so a merge that changes the value also changes the hash and the row is
 * updated. Running it after the comparison would leave the accumulation permanently undetected.
 */
class CatalogIndexWriter(
    private val db: OwnTVDatabase,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val favoriteDao: FavoriteDao,
    private val progressDao: ProgressDao,
    private val historyDao: HistoryDao,
    private val customCategoryDao: CustomCategoryDao,
    private val contentOrderDao: ContentOrderDao,
) {

    /** Insert/update category rows in place and return their local ids keyed by provider remote id. */
    suspend fun upsertCategories(
        sourceId: Long,
        type: MediaType,
        rows: List<CategoryEntity>,
    ): Map<String, Long> {
        val valid = rows
            .filter { !it.remoteId.isNullOrBlank() }
            .distinctBy { it.remoteId }
        if (valid.isEmpty()) return emptyMap()
        return db.transaction {
            val remoteIds = valid.mapNotNull { it.remoteId }
            val existing = remoteIds.chunked(CHUNK)
                .flatMap { categoryDao.findByRemoteIds(sourceId, type, it) }
                .mapNotNull { category -> category.remoteId?.let { it to category } }
                .toMap()
            val inserts = ArrayList<CategoryEntity>(valid.size)
            val updates = ArrayList<CategoryEntity>()
            valid.forEach { row ->
                val current = row.remoteId?.let(existing::get)
                when {
                    current == null -> inserts.add(row)
                    row.copy(id = current.id) != current -> updates.add(row.copy(id = current.id))
                }
            }
            if (updates.isNotEmpty()) categoryDao.updateAll(updates)
            if (inserts.isNotEmpty()) categoryDao.insertAll(inserts)
            categoryDao.findByRemoteIds(sourceId, type, remoteIds)
                .mapNotNull { category -> category.remoteId?.let { it to category.id } }
                .toMap()
        }
    }

    /**
     * Stable upsert of [rows] plus a freshness stamp, in one transaction.
     *
     * Unchanged rows are skipped by the hash comparison, so they would keep their old `lastSeenAt`
     * forever; [markSeen] over every candidate closes that hole. Returns how many rows were actually
     * written (inserted + updated), for logging and tests.
     */
    suspend fun upsertMovies(
        sourceId: Long,
        rows: List<MovieEntity>,
        mergeExtJson: (old: String?, new: String?) -> String? = { _, new -> new },
    ): Int {
        val candidates = rows
            .filter { it.remoteId != null }
            .distinctBy { it.remoteId }
        if (candidates.isEmpty()) return 0
        val now = System.currentTimeMillis()
        return db.transaction {
            val remoteIds = candidates.mapNotNull { it.remoteId }
            val oldRows = HashMap<String, MovieExtSnapshot>(remoteIds.size)
            remoteIds.chunked(CHUNK).forEach { chunk ->
                movieDao.extJsonForRemoteIds(sourceId, chunk).forEach { oldRows[it.remoteId] = it }
            }
            val stored = movieDao.contentHashesForSource(sourceId).associateBy { it.remoteId }
            val inserts = ArrayList<MovieEntity>(candidates.size)
            val updates = ArrayList<MovieEntity>()
            candidates.forEach { row ->
                val old = oldRows[row.remoteId]
                // "First non-empty category wins", in both directions: a search/detail sighting carries
                // no category, and a provider lists the same film under several. Letting either one
                // overwrite the first attribution would flip the hash back and forth on every sighting —
                // a full row rewrite plus an FTS trigger each time. The remote category is a kids/lock
                // attribution, not the filter key; browsing still filters by the provider's type id.
                val attributed = if (old?.categoryId != null) {
                    row.copy(categoryId = old.categoryId)
                } else {
                    row
                }
                val merged = attributed.copy(extJson = mergeExtJson(old?.extJson, attributed.extJson))
                val hash = merged.computeContentHash()
                val existing = stored[merged.remoteId]
                when {
                    existing == null -> inserts.add(merged.copy(contentHash = hash))
                    existing.contentHash != hash -> updates.add(merged.copy(id = existing.id, contentHash = hash))
                }
            }
            if (updates.isNotEmpty()) movieDao.updateAll(updates)
            if (inserts.isNotEmpty()) movieDao.insertAll(inserts)
            markSeenLocked(sourceId, remoteIds, now)
            inserts.size + updates.size
        }
    }

    /** One row's stable upsert; returns its local id so user data can be keyed to it. */
    suspend fun upsertMovie(
        sourceId: Long,
        row: MovieEntity,
        mergeExtJson: (old: String?, new: String?) -> String? = { _, new -> new },
    ): Long? {
        upsertMovies(sourceId, listOf(row), mergeExtJson)
        val remoteId = row.remoteId ?: return null
        return movieDao.findByRemote(sourceId, remoteId)?.id
    }

    /** Refresh [MovieEntity.lastSeenAt] without a full-row write or a hash comparison. */
    suspend fun markSeen(sourceId: Long, remoteIds: Collection<String>, at: Long = System.currentTimeMillis()) {
        db.transaction { markSeenLocked(sourceId, remoteIds, at) }
    }

    /**
     * Age out the index and then drop the orphans the sweep just created.
     *
     * Both guards are load-bearing: the DELETE itself skips every row a user row points at, and the
     * five `purgeOrphans` passes only run afterwards, when an orphan really is an orphan (an item
     * whose provider row is gone). Running only favourites and progress would leave custom-category
     * memberships and manual orders pointing at deleted films.
     */
    suspend fun prune(sourceId: Long, cutoff: Long, maxRows: Int): Int = db.transaction {
        var removed = movieDao.pruneIndexedBefore(sourceId, cutoff)
        val stored = movieDao.countForSourceOnce(sourceId)
        if (stored > maxRows) {
            val ids = movieDao.leastRecentlySeenCandidates(sourceId, stored - maxRows)
            if (ids.isNotEmpty()) removed += movieDao.deleteByIds(ids)
        }
        categoryDao.deleteEmptyMovieCategories(sourceId)
        favoriteDao.purgeOrphans()
        progressDao.purgeOrphans()
        historyDao.purgeOrphans()
        customCategoryDao.purgeOrphans()
        contentOrderDao.purgeOrphans()
        removed
    }

    /** A finished index pass: the settings page reads this as "last synced". */
    suspend fun markSynced(sourceId: Long) = sourceDao.markSynced(sourceId, System.currentTimeMillis())

    private suspend fun markSeenLocked(sourceId: Long, remoteIds: Collection<String>, at: Long) {
        remoteIds.toList().chunked(CHUNK).forEach { movieDao.markSeen(sourceId, it, at) }
    }

    private companion object {
        const val CHUNK = 500
    }
}