package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tv.own.owntv.core.database.entity.CatalogIndexCursorEntity

/**
 * Paged-index position per source category (v54). The cursor's [CatalogIndexCursorEntity.categoryRemoteId]
 * must be `"<siteKey>/<typeId>"` — a bare type id would let two sites with the same category name share
 * one page position.
 */
@Dao
interface CatalogIndexCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cursor: CatalogIndexCursorEntity)

    @Query("SELECT * FROM catalog_index_cursor WHERE sourceId = :sourceId AND categoryRemoteId = :categoryRemoteId")
    suspend fun get(sourceId: Long, categoryRemoteId: String): CatalogIndexCursorEntity?

    @Query("SELECT lastPage FROM catalog_index_cursor WHERE sourceId = :sourceId AND categoryRemoteId = :categoryRemoteId")
    suspend fun lastPage(sourceId: Long, categoryRemoteId: String): Int?

    @Query("DELETE FROM catalog_index_cursor WHERE sourceId = :sourceId AND categoryRemoteId = :categoryRemoteId")
    suspend fun reset(sourceId: Long, categoryRemoteId: String)

    /** A remote-filter change invalidates the old pagination; the sweep ages the stale rows out. */
    @Query("UPDATE catalog_index_cursor SET generation = generation + 1 WHERE sourceId = :sourceId AND categoryRemoteId = :categoryRemoteId")
    suspend fun bumpGeneration(sourceId: Long, categoryRemoteId: String)

    @Query("DELETE FROM catalog_index_cursor WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)
}