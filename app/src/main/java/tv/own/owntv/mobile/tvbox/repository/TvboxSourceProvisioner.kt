package tv.own.owntv.mobile.tvbox.repository

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.R

/**
 * Keeps the Core `sources` table in step with the TVbox configuration list: one configuration is one
 * [SourceType.TVBOX] source, keyed by `type + externalKey` so re-importing or renaming it updates the
 * same row instead of creating a second source whose content a user's favourites do not point at.
 *
 * Two rules matter more than the rest:
 *
 * 1. **Every profile is linked.** Content is shared, user data is per profile. A source linked to
 *    only the active profile hides all VOD content from a kids profile — and then the item-level
 *    kids filter has nothing to filter.
 * 2. **A deleted configuration keeps its source row.** Deleting the row would CASCADE-delete
 *    `movies`, turning every favourite and resume position into an orphan for `purgeOrphans` to
 *    delete. Instead the source stops participating in movie reads (`syncMovies = false`) and its
 *    index ages out through the TTL sweep.
 */
class TvboxSourceProvisioner(
    private val context: Context,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val configRepository: TvboxConfigRepository,
) {

    /** Restore and a first browse can provision at the same time; the lookup-then-insert is a race. */
    private val mutex = Mutex()

    /**
     * Provision every known configuration; only the active one participates in movie reads.
     *
     * Returns every TVBOX source id, so the caller can run the index maintenance (TTL / row cap)
     * over all of them — a configuration that is no longer active still owns rows that must age out.
     */
    suspend fun syncAll(): List<Long> = mutex.withLock {
        val configs = configRepository.configs.value
        val activeConfigId = configRepository.activeId()
        val profiles = profileDao.getAllOnce()
        val provisioned = ArrayList<Long>(configs.size)

        configs.forEach { meta ->
            val externalKey = meta.id.toString()
            val existing = sourceDao.findByTypeAndExternalKey(SourceType.TVBOX, externalKey)
            val name = context.getString(R.string.tvbox_source_name, meta.name)
            val sourceId = if (existing == null) {
                sourceDao.insert(
                    SourceEntity(
                        name = name,
                        type = SourceType.TVBOX,
                        url = "tvbox://config/$externalKey",
                        externalKey = externalKey,
                        syncLive = false,
                        syncMovies = meta.id == activeConfigId,
                        syncSeries = false,
                    ),
                )
            } else {
                sourceDao.update(
                    existing.copy(
                        name = name,
                        url = "tvbox://config/$externalKey",
                        syncLive = false,
                        syncMovies = meta.id == activeConfigId,
                        syncSeries = false,
                    ),
                )
                existing.id
            }
            profiles.forEach { profile -> sourceDao.link(ProfileSourceCrossRef(profile.id, sourceId)) }
            provisioned += sourceId
        }

        // Configurations removed from the list keep their source and index; they simply stop being
        // read, and the TTL sweep is what eventually drops their rows.
        val known = configs.mapTo(HashSet()) { it.id.toString() }
        sourceDao.getAllOfType(SourceType.TVBOX)
            .filter { it.externalKey == null || it.externalKey !in known }
            .forEach { if (it.syncMovies) sourceDao.updateSyncMovies(it.id, false) }

        provisioned
    }

    /** The Core source id of the active configuration, provisioning on demand when it is missing. */
    suspend fun activeSourceId(): Long? {
        val activeConfigId = configRepository.activeId()
        if (activeConfigId < 0) return null
        val externalKey = activeConfigId.toString()
        sourceDao.findByTypeAndExternalKey(SourceType.TVBOX, externalKey)?.let { return it.id }
        syncAll()
        return sourceDao.findByTypeAndExternalKey(SourceType.TVBOX, externalKey)?.id
    }
}