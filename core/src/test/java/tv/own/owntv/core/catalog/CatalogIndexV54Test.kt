package tv.own.owntv.core.catalog

import java.util.Objects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.Converters
import tv.own.owntv.core.database.MIGRATION_53_54
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.model.SourceType

/**
 * The v54 indexed-catalogue contract, kept as pure assertions so a later schema edit cannot quietly
 * break the two rules the index's correctness rests on: the new columns must be part of the content
 * hash, and the new indexes must be registered for `BulkInsertHelper`'s restore pass.
 */
class CatalogIndexV54Test {

    @Test
    fun `extGroup and extJson change the hash`() {
        val plain = movie()
        assertNotEquals(plain.computeContentHash(), plain.copy(extGroup = "site").computeContentHash())
        assertNotEquals(plain.computeContentHash(), plain.copy(extJson = "{}").computeContentHash())
    }

    @Test
    fun `lastSeenAt is bookkeeping and never changes the hash`() {
        val plain = movie()
        assertEquals(plain.computeContentHash(), plain.copy(lastSeenAt = 1_700_000_000_000L).computeContentHash())
    }

    @Test
    fun `a row without the new fields hashes exactly as it did before v54`() {
        // The literal formula is the pre-v54 one. Folding the new columns in unconditionally would
        // change every stored M3U/Xtream hash and turn the next resync into a full rewrite.
        val row = movie()
        val expected = Objects.hash(
            row.sourceId, row.categoryId, row.name, row.posterUrl, row.backdropUrl,
            row.year, row.rating, row.durationSecs, row.plot, row.streamUrl,
            row.containerExt, row.remoteId, row.addedAt,
        )
        assertEquals(expected, row.computeContentHash())
    }

    @Test
    fun `the sweep indexes are registered for the bulk-import restore pass`() {
        // Room's @Entity/@Index annotations are BINARY-retention and cannot be read back at runtime,
        // so the canonical list is the contract: BulkInsertHelper restores exactly these statements.
        val registered = OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES["movies"].orEmpty()
        assertTrue(registered.any { it.contains("`extGroup`") && it.contains("`lastSeenAt`") })
        assertTrue(registered.any { it.endsWith("(`lastSeenAt`)") })
    }

    @Test
    fun `the schema hop is 53 to 54 and in the chain`() {
        assertEquals(53, MIGRATION_53_54.startVersion)
        assertEquals(54, MIGRATION_53_54.endVersion)
        assertTrue(OwnTVDatabase.ALL_MIGRATIONS.any { it === MIGRATION_53_54 })
    }

    @Test
    fun `a TVBOX source type round-trips through its stored name`() {
        val converters = Converters()
        assertEquals("TVBOX", converters.sourceTypeToString(SourceType.TVBOX))
        assertEquals(SourceType.TVBOX, converters.stringToSourceType("TVBOX"))
    }

    private fun movie() = MovieEntity(
        sourceId = 7,
        name = "A film",
        streamUrl = "https://example.invalid/a.mp4",
        remoteId = "7/a",
    )
}