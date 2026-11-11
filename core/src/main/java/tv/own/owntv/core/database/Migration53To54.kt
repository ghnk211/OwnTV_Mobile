package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v53 → v54: the indexed-catalogue columns.
 *
 * A TVbox configuration is provisioned as one [tv.own.owntv.core.model.SourceType.TVBOX] source and
 * its rows are written into the ordinary `sources/categories/movies` tables, so nothing here adds a
 * parallel content model. Everything is additive and nullable, and the two new indexes are also
 * registered in [OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES] — `BulkInsertHelper` drops every
 * non-unique index on `movies` during an eligible fresh import and rebuilds them from that list.
 * Last hop, so it carries [OwnTVDatabase.healSchema] (standing rule).
 */
val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "sources", "externalKey")) {
            connection.execSQL("ALTER TABLE `sources` ADD COLUMN `externalKey` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "categories", "extJson")) {
            connection.execSQL("ALTER TABLE `categories` ADD COLUMN `extJson` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "movies", "extGroup")) {
            connection.execSQL("ALTER TABLE `movies` ADD COLUMN `extGroup` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "movies", "extJson")) {
            connection.execSQL("ALTER TABLE `movies` ADD COLUMN `extJson` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "movies", "lastSeenAt")) {
            connection.execSQL("ALTER TABLE `movies` ADD COLUMN `lastSeenAt` INTEGER")
        }
        if (!OwnTVDatabase.hasColumn(connection, "playback_progress", "extKey")) {
            connection.execSQL("ALTER TABLE `playback_progress` ADD COLUMN `extKey` TEXT")
        }
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_index_cursor` (" +
                "`sourceId` INTEGER NOT NULL, " +
                "`categoryRemoteId` TEXT NOT NULL, " +
                "`lastPage` INTEGER NOT NULL, " +
                "`generation` INTEGER NOT NULL, " +
                "`syncedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `categoryRemoteId`))",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_movies_sourceId_extGroup_lastSeenAt` " +
                "ON `movies` (`sourceId`, `extGroup`, `lastSeenAt`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_movies_lastSeenAt` ON `movies` (`lastSeenAt`)",
        )
        OwnTVDatabase.healSchema(connection)
    }
}