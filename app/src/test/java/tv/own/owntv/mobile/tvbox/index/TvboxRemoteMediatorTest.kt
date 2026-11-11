package tv.own.owntv.mobile.tvbox.index

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.CatalogIndexCursorDao
import tv.own.owntv.core.database.entity.CatalogIndexCursorEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import java.io.IOException

/**
 * The Library mediator's page state machine (plan §4.8 B2): REFRESH restarts at page 1, APPEND
 * continues from the cursor, a failed fetch is an error rather than a silent end, and the cursor
 * key is `siteKey/typeId`. Pure JVM — the remote fetch and the index write are scripted.
 */
@OptIn(ExperimentalPagingApi::class)
class TvboxRemoteMediatorTest {

    private class FakeCursorDao : CatalogIndexCursorDao {
        val rows = mutableMapOf<Pair<Long, String>, CatalogIndexCursorEntity>()
        var resets = 0

        override suspend fun save(cursor: CatalogIndexCursorEntity) {
            rows[cursor.sourceId to cursor.categoryRemoteId] = cursor
        }

        override suspend fun get(sourceId: Long, categoryRemoteId: String): CatalogIndexCursorEntity? =
            rows[sourceId to categoryRemoteId]

        override suspend fun lastPage(sourceId: Long, categoryRemoteId: String): Int? =
            rows[sourceId to categoryRemoteId]?.lastPage

        override suspend fun reset(sourceId: Long, categoryRemoteId: String) {
            resets++
            rows.remove(sourceId to categoryRemoteId)
        }

        override suspend fun clearSource(sourceId: Long) {
            rows.keys.removeAll { it.first == sourceId }
        }

        override suspend fun bumpGeneration(sourceId: Long, categoryRemoteId: String) {
            rows[sourceId to categoryRemoteId]?.let {
                rows[sourceId to categoryRemoteId] = it.copy(generation = it.generation + 1)
            }
        }
    }

    private class Harness(pages: Map<Int, SpiderResult?>) {
        val cursor = FakeCursorDao()
        val fetched = mutableListOf<Int>()
        val indexed = mutableListOf<Pair<Int, List<Movie>>>()
        val mediator = TvboxRemoteMediator(
            site = SiteConfig(key = "s1", name = "站一", type = 0, api = "http://x/api"),
            typeId = "1",
            sourceId = 7L,
            cursorDao = cursor,
            fetchPage = { page -> fetched += page; pages[page] },
            indexPage = { page, movies -> indexed += page to movies },
        )

        suspend fun load(type: LoadType): RemoteMediator.MediatorResult =
            mediator.load(type, PagingState<Int, MovieEntity>(emptyList(), null, PagingConfig(pageSize = 60), 0))
    }

    private val movie = Movie(id = "a", name = "片A")

    private fun result(movies: List<Movie>, pagecount: Int) = SpiderResult(list = movies, pagecount = pagecount)

    private fun success(result: RemoteMediator.MediatorResult): Boolean {
        assertTrue(result is RemoteMediator.MediatorResult.Success)
        return (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached
    }

    @Test
    fun `refresh resets the cursor and fetches page one`() = runTest {
        val h = Harness(mapOf(1 to result(listOf(movie), pagecount = 3)))
        h.cursor.save(CatalogIndexCursorEntity(sourceId = 7L, categoryRemoteId = "s1/1", lastPage = 5))
        val end = success(h.load(LoadType.REFRESH))
        assertEquals(false, end)
        assertEquals(1, h.cursor.resets)
        assertEquals(listOf(1), h.fetched)
        assertEquals(listOf(1 to listOf(movie)), h.indexed)
        assertTrue(h.cursor.rows.isEmpty())
    }

    @Test
    fun `append continues after the last indexed page`() = runTest {
        val h = Harness(mapOf(3 to result(listOf(movie), pagecount = 3)))
        h.cursor.save(CatalogIndexCursorEntity(sourceId = 7L, categoryRemoteId = "s1/1", lastPage = 2))
        val end = success(h.load(LoadType.APPEND))
        assertEquals(true, end)
        assertEquals(listOf(3), h.fetched)
        assertEquals(0, h.cursor.resets)
    }

    @Test
    fun `append without a cursor starts at page one`() = runTest {
        val h = Harness(mapOf(1 to result(listOf(movie), pagecount = 2)))
        val end = success(h.load(LoadType.APPEND))
        assertEquals(false, end)
        assertEquals(listOf(1), h.fetched)
    }

    @Test
    fun `prepend never fetches`() = runTest {
        val h = Harness(mapOf(1 to result(listOf(movie), pagecount = 3)))
        val end = success(h.load(LoadType.PREPEND))
        assertEquals(true, end)
        assertTrue(h.fetched.isEmpty())
        assertTrue(h.indexed.isEmpty())
    }

    @Test
    fun `a failed fetch is an error, not the end`() = runTest {
        val h = Harness(emptyMap())
        val result = h.load(LoadType.REFRESH)
        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is IOException)
        assertTrue(h.indexed.isEmpty())
    }

    @Test
    fun `an empty page ends pagination`() = runTest {
        val h = Harness(mapOf(1 to result(emptyList(), pagecount = 5)))
        assertEquals(true, success(h.load(LoadType.REFRESH)))
    }

    @Test
    fun `the last page ends pagination`() = runTest {
        val h = Harness(mapOf(2 to result(listOf(movie), pagecount = 2)))
        h.cursor.save(CatalogIndexCursorEntity(sourceId = 7L, categoryRemoteId = "s1/1", lastPage = 1))
        assertEquals(true, success(h.load(LoadType.APPEND)))
    }

    @Test
    fun `the cursor key is siteKey slash typeId`() = runTest {
        val h = Harness(mapOf(1 to result(listOf(movie), pagecount = 9), 5 to result(listOf(movie), pagecount = 9)))
        // A cursor stored under a bare typeId must NOT be found: the key carries the site.
        h.cursor.save(CatalogIndexCursorEntity(sourceId = 7L, categoryRemoteId = "1", lastPage = 4))
        success(h.load(LoadType.APPEND))
        assertEquals(listOf(1), h.fetched)
        h.cursor.save(CatalogIndexCursorEntity(sourceId = 7L, categoryRemoteId = "s1/1", lastPage = 4))
        success(h.load(LoadType.APPEND))
        assertEquals(listOf(1, 5), h.fetched)
    }
}