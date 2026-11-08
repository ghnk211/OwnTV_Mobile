package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelNumberProjection
import tv.own.owntv.core.sync.PlaylistNumbering.Change

/**
 * Numbering playlists that carry no `tvg-chno`. Rows are given the way the DAO returns them: every
 * stored channel of one source, in playlist order. [table] applies the writes, standing in for the
 * database, so each case checks what the user would see after the sync.
 */
class PlaylistNumberingTest {

    private fun row(id: Long, key: String, sortOrder: Int, number: Int? = null, name: String = key) =
        ChannelNumberProjection(id = id, remoteId = key, sortOrder = sortOrder, number = number, name = name)

    private fun table(rows: List<ChannelNumberProjection>, changes: List<Change>): Map<String, Int?> {
        val byId = changes.associate { it.id to it.number }
        return rows.associate { it.remoteId!! to if (it.id in byId) byId[it.id] else it.number }
    }

    @Test
    fun `a playlist with no numbers is numbered in file order`() {
        val rows = listOf(row(7, "News", 0), row(3, "Sport", 1), row(9, "Movies", 4))
        val changes = PlaylistNumbering.changes(rows, providerNumbered = false, unnumberedKeys = setOf("News", "Sport", "Movies"))
        assertEquals(mapOf("News" to 1, "Sport" to 2, "Movies" to 3), table(rows, changes))
    }

    @Test
    fun `resyncing an unchanged playlist writes nothing`() {
        val rows = listOf(row(1, "News", 0, 1), row(2, "Sport", 1, 2), row(3, "Movies", 2, 3))
        assertTrue(PlaylistNumbering.changes(rows, providerNumbered = false, unnumberedKeys = rows.map { it.remoteId!! }.toSet()).isEmpty())
    }

    @Test
    fun `a channel added mid-file moves only the ones after it`() {
        // "Weather" was inserted at position 1; the rows after it were shifted by the upsert's position move.
        val rows = listOf(row(1, "News", 0, 1), row(4, "Weather", 1), row(2, "Sport", 2, 2), row(3, "Movies", 3, 3))
        val changes = PlaylistNumbering.changes(rows, providerNumbered = false, unnumberedKeys = rows.map { it.remoteId!! }.toSet())
        assertEquals(listOf(4L, 2L, 3L), changes.map { it.id })
        assertEquals(mapOf("News" to 1, "Weather" to 2, "Sport" to 3, "Movies" to 4), table(rows, changes))
    }

    @Test
    fun `a playlist with its own numbers is left alone`() {
        val rows = listOf(row(1, "News", 0, 101), row(2, "Sport", 1, 205))
        assertTrue(PlaylistNumbering.changes(rows, providerNumbered = true, unnumberedKeys = emptySet()).isEmpty())
    }

    @Test
    fun `provider numbers are never mixed with derived ones`() {
        // A playlist numbered 1-3 by file order now carries tvg-chno on two channels. "Movies" has
        // none; its unchanged row was skipped by the upsert and still holds the derived 3.
        val rows = listOf(row(1, "News", 0, 101), row(2, "Sport", 1, 205), row(3, "Movies", 2, 3))
        val changes = PlaylistNumbering.changes(rows, providerNumbered = true, unnumberedKeys = setOf("Movies"))
        assertEquals(mapOf("News" to 101, "Sport" to 205, "Movies" to null), table(rows, changes))
    }

    @Test
    fun `a mixed playlist that is already clean writes nothing`() {
        val rows = listOf(row(1, "News", 0, 101), row(2, "Movies", 1))
        assertTrue(PlaylistNumbering.changes(rows, providerNumbered = true, unnumberedKeys = setOf("Movies")).isEmpty())
    }

    @Test
    fun `the lines of one channel share one number`() {
        // A playlist that lists 北京卫视 six times keeps six rows — one URL each — and every list
        // draws one. Numbering the rows left that number counting the five lines behind it: the
        // next channel's number skipped to 7 and the row's own said 1 of a channel with 6 lines.
        val rows = listOf(
            row(1, "北京卫视\u0001卫视频道", 0, name = "北京卫视"),
            row(2, "北京卫视\u0001卫视频道\u0001#2", 1, name = "北京卫视"),
            row(3, "北京卫视\u0001卫视频道\u0001#3", 2, name = "北京卫视"),
            row(4, "湖南卫视\u0001卫视频道", 3, name = "湖南卫视"),
        )
        val changes = PlaylistNumbering.changes(rows, providerNumbered = false, unnumberedKeys = rows.map { it.remoteId!! }.toSet())
        assertEquals(
            mapOf(rows[0].remoteId to 1, rows[1].remoteId to 1, rows[2].remoteId to 1, rows[3].remoteId to 2),
            table(rows, changes),
        )
    }

    @Test
    fun `numbers follow the playlist position, not the row id`() {
        // Ids reflect when a row was first stored; the number must come from where it sits in the file.
        val rows = listOf(row(50, "Late addition first", 0), row(1, "Original", 1))
        val changes = PlaylistNumbering.changes(rows, providerNumbered = false, unnumberedKeys = setOf("Late addition first", "Original"))
        assertEquals(mapOf("Late addition first" to 1, "Original" to 2), table(rows, changes))
    }
}
