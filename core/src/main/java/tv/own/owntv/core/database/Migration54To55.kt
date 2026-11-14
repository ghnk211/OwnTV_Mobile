package tv.own.owntv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v54 → v55: the TVBOX download identity.
 *
 * A TVBOX film is one `movies` row, so its episodes cannot be told apart by
 * `(profileId, mediaType, itemId)`. `identityKey` carries the line/episode identity (empty for
 * every ordinary source, keeping their old uniqueness), and `extJson` carries the details needed to
 * re-resolve the stream at download time.
 *
 * `httpHeaders`/`containerExt`/`manifest` are transport metadata. The unique index is replaced
 * rather than the table rebuilt: every existing row gets `identityKey = ''`, so the four-column
 * index is exactly as unique as the old three-column one.
 *
 * The non-unique `(mediaType, itemId)` index is **not** in `EXPECTED_NON_UNIQUE_INDEXES`
 * (`downloads` does not participate in BulkInsertHelper's catalogue rebuild), so this migration has
 * to create it itself; otherwise Room rejects the migrated schema.
 *
 * Last hop, so it carries [OwnTVDatabase.healSchema] (standing rule).
 */
val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "identityKey")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `identityKey` TEXT NOT NULL DEFAULT ''")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "extJson")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `extJson` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "httpHeaders")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `httpHeaders` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "containerExt")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `containerExt` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "manifest")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `manifest` TEXT NOT NULL DEFAULT 'NONE'")
        }
        connection.execSQL("DROP INDEX IF EXISTS `index_downloads_profileId_mediaType_itemId`")
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_profileId_mediaType_itemId_identityKey` " +
                "ON `downloads` (`profileId`, `mediaType`, `itemId`, `identityKey`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_downloads_mediaType_itemId` " +
                "ON `downloads` (`mediaType`, `itemId`)",
        )
        OwnTVDatabase.healSchema(connection)
    }
}