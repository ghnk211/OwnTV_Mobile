package tv.own.owntv.mobile.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.CatalogSyncWorker

/**
 * Re-syncing a playlist that lives on this phone.
 *
 * Core schedules every re-sync as a WorkManager job behind `NetworkType.CONNECTED`, and current
 * WorkManager turns that into INTERNET + TRUSTED + VALIDATED. Right for a provider, wrong for a file:
 * an `.m3u` in shared storage or a `content://` document needs no network at all, so on a phone with
 * no validated internet — a LAN-only setup, a captive portal, the emulator — the job is never started,
 * the playlist row keeps reading "Preparing catalog" and nothing ever happens.
 *
 * This submits core's own worker under the same unique name without the constraint, so the state and
 * progress the rest of the app already reads (`CatalogSyncScheduler.observeSync`) keep working
 * unchanged. The input keys are core's public constants: if core renames them this stops compiling
 * rather than silently syncing nothing.
 */
object LocalPlaylistSync {

    /** The same test core's importer uses before it skips its own "are we online" check. */
    fun isLocal(source: SourceEntity): Boolean = source.url.isLocalPlaylistPath()

    fun enqueue(context: Context, sourceId: Long, reason: String, forcePrune: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setInputData(
                workDataOf(
                    CatalogSyncWorker.KEY_SOURCE_ID to sourceId,
                    CatalogSyncWorker.KEY_REASON to reason,
                    CatalogSyncWorker.KEY_BASE_ITEM_COUNT to 0,
                    CatalogSyncWorker.KEY_LIVE to true,
                    CatalogSyncWorker.KEY_MOVIES to true,
                    CatalogSyncWorker.KEY_SERIES to true,
                    CatalogSyncWorker.KEY_COMPLETES_INITIAL_SYNC to false,
                    CatalogSyncWorker.KEY_FORCE_PRUNE to forcePrune,
                ),
            )
            .addTag(CatalogSyncScheduler.TAG_CATALOG_SYNC)
            .addTag("source-$sourceId")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CatalogSyncScheduler.workName(sourceId), ExistingWorkPolicy.REPLACE, request)
    }
}

/** A playlist address that is a file on this device: no scheme, `file://` or a picked document. */
private fun String.isLocalPlaylistPath(): Boolean =
    startsWith("/") || startsWith("file://") || startsWith("content://")
