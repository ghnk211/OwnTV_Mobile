package tv.own.owntv.mobile.tvbox.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryPolicy
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.repository.SourceSearchResult

/**
 * The online section's shaping rules (plan P3): kids filter, poster absolutisation, donor ownership,
 * per-site cap, and dedupe against the local index. Pure JVM, no engine or network.
 */
class OnlineSearchAdapterTest {

    private val policy = TvboxCategoryPolicy(kidsStrict = { false })
    private val normal = ProfileEntity(id = 1, name = "Tester", avatarColor = 0, isKids = false)
    private val kids = ProfileEntity(id = 2, name = "Kid", avatarColor = 0, isKids = true)

    private val s1 = SiteConfig(key = "s1", name = "站一", type = 0, api = "http://x/api")
    private val s2 = SiteConfig(key = "s2", name = "站二", type = 0, api = "http://y/api")
    private val sites = mapOf("s1" to s1, "s2" to s2)
    private val siteOf: (String) -> SiteConfig? = { sites[it] }

    private fun movie(
        id: String,
        name: String = id,
        pic: String? = null,
        typeName: String? = "电影",
        donor: String? = null,
    ) = Movie(id = id, name = name, pic = pic, typeName = typeName, donorSiteKey = donor)

    private fun result(siteKey: String, vararg movies: Movie) =
        SourceSearchResult(siteKey = siteKey, siteName = sites[siteKey]?.name ?: siteKey, movies = movies.toList())

    @Test
    fun `normal profile keeps every row`() {
        val state = OnlineSearchAdapter.shape(listOf(result("s1", movie("a"), movie("b"))), normal, policy, siteOf)
        assertEquals(1, state.groups.size)
        assertEquals(2, state.groups[0].rows.size)
    }

    @Test
    fun `kids profile drops adult typeName rows`() {
        val state = OnlineSearchAdapter.shape(
            listOf(result("s1", movie("a", typeName = "伦理片"), movie("b", typeName = "电影"))),
            kids, policy, siteOf,
        )
        assertEquals(listOf("b"), state.groups[0].rows.map { it.vodId })
    }

    @Test
    fun `relative poster is absolutised and blank becomes null`() {
        val state = OnlineSearchAdapter.shape(
            listOf(result("s1", movie("a", pic = "/img/a.jpg"), movie("b", pic = "  "))),
            normal, policy, siteOf,
        )
        assertEquals("http://x/img/a.jpg", state.groups[0].rows[0].posterUrl)
        assertNull(state.groups[0].rows[1].posterUrl)
    }

    @Test
    fun `borrowed row is owned by its donor site`() {
        val state = OnlineSearchAdapter.shape(
            listOf(result("s1", movie("a", donor = "s2"))),
            normal, policy, siteOf,
        )
        assertEquals(1, state.groups.size)
        assertEquals("s2", state.groups[0].siteKey)
        assertEquals("站二", state.groups[0].siteName)
        assertEquals("s2/a", state.groups[0].rows[0].remoteId)
    }

    @Test
    fun `per-site cap keeps the head`() {
        val many = (1..30).map { movie("v$it") }.toTypedArray()
        val state = OnlineSearchAdapter.shape(listOf(result("s1", *many)), normal, policy, siteOf)
        assertEquals(OnlineSearchAdapter.SITE_ROWS_CAP, state.groups[0].rows.size)
    }

    @Test
    fun `group order follows first site done`() {
        val state = OnlineSearchAdapter.shape(
            listOf(result("s2", movie("a")), result("s1", movie("b"))),
            normal, policy, siteOf,
        )
        assertEquals(listOf("s2", "s1"), state.groups.map { it.siteKey })
    }

    @Test
    fun `a site gone from the config is skipped`() {
        val state = OnlineSearchAdapter.shape(listOf(result("ghost", movie("a"))), normal, policy, siteOf)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `dedupe removes rows the index already shows and drops empty groups`() {
        val state = OnlineSearchAdapter.shape(
            listOf(result("s1", movie("a"), movie("b")), result("s2", movie("c"))),
            normal, policy, siteOf,
        )
        val deduped = OnlineSearchAdapter.dedupe(state, setOf("s1/a", "s2/c"))
        assertEquals(1, deduped.groups.size)
        assertEquals("s1", deduped.groups[0].siteKey)
        assertEquals(listOf("b"), deduped.groups[0].rows.map { it.vodId })
    }

    @Test
    fun `dedupe with an empty index is a no-op`() {
        val state = OnlineSearchAdapter.shape(listOf(result("s1", movie("a"))), normal, policy, siteOf)
        assertEquals(state, OnlineSearchAdapter.dedupe(state, emptySet()))
    }
}