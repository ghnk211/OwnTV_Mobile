package tv.own.owntv.mobile.tvbox.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Movie

class TvboxIndexMapperTest {

    private val site = SiteConfig(key = "cms", name = "CMS", api = "https://cms.example/api.php/provide/vod/")

    @Test
    fun `identity is the site key plus the vod id`() {
        val entity = TvboxIndexMapper.movie(movie(id = "42"), 1, null, site, null, 0)!!
        assertEquals("cms/42", entity.remoteId)
        assertEquals("tvbox://cms/42", entity.streamUrl)
        assertEquals("cms", entity.extGroup)
        assertEquals("42", TvboxIndexMapper.splitRemoteId(entity.remoteId)?.second)
    }

    @Test
    fun `a borrowed row is indexed under its donor site`() {
        val donor = SiteConfig(key = "donor", name = "Donor", api = "https://donor.example/")
        val entity = TvboxIndexMapper.movie(
            movie(id = "9", donorSiteKey = "donor", pic = "poster.jpg"),
            1,
            null,
            site,
            donor,
            0,
        )!!
        assertEquals("donor/9", entity.remoteId)
        assertEquals("donor", entity.extGroup)
        assertEquals("https://donor.example/poster.jpg", entity.posterUrl)
        assertTrue(entity.extJson!!.contains("\"donorSiteKey\":\"donor\""))
    }

    @Test
    fun `a borrowed row whose donor is gone is refused`() {
        assertNull(TvboxIndexMapper.movie(movie(id = "9", donorSiteKey = "gone"), 1, null, site, null, 0))
    }

    @Test
    fun `a row with no id or an unencodable site key is refused`() {
        assertNull(TvboxIndexMapper.movie(movie(id = ""), 1, null, site, null, 0))
        assertNull(TvboxIndexMapper.movie(movie(id = "1"), 1, null, site.copy(key = "a/b"), null, 0))
    }

    @Test
    fun `typeNames is a union that never loses the adult attribution`() {
        val first = """{"typeNames":["伦理片"],"remark":"first"}"""
        val second = """{"typeNames":["电影"],"adultSite":true}"""
        val merged = TvboxIndexMapper.mergeExtJson(first, second)!!
        assertTrue(merged.contains("伦理片"))
        assertTrue(merged.contains("电影"))
        assertTrue(merged.contains("\"adultSite\":true"))
        assertTrue(merged.contains("\"remark\":\"first\""))
    }

    @Test
    fun `merging with a missing side keeps the side that exists`() {
        val only = """{"typeNames":["电影"]}"""
        assertEquals(only, TvboxIndexMapper.mergeExtJson(null, only))
        assertEquals(only, TvboxIndexMapper.mergeExtJson(only, null))
        assertNull(TvboxIndexMapper.mergeExtJson(null, null))
    }

    @Test
    fun `a site ext adult marker is written into the row`() {
        val flagged = site.copy(ext = """{"nsfw":1}""")
        val entity = TvboxIndexMapper.movie(movie(id = "5"), 1, null, flagged, null, 0)!!
        assertTrue(entity.extJson!!.contains("\"adultSite\":true"))
    }

    @Test
    fun `a category identity keeps the site key`() {
        val category = TvboxIndexMapper.category(3, site, ClassInfo(typeId = "1", typeName = "电影"), 4)!!
        assertEquals("cms/1", category.remoteId)
        // The indexer resolves `movies.categoryId` by rebuilding this key, so the two spellings must
        // be one function — a film whose category cannot be resolved loses its kids/lock attribution.
        assertEquals(category.remoteId, TvboxIndexMapper.categoryRemoteId(site.key, "1"))
        assertEquals(4, category.sortOrder)
        assertTrue(category.extJson!!.contains("\"typeName\":\"电影\""))
    }

    @Test
    fun `an origin site is the donor when the row was borrowed`() {
        assertEquals(
            "donor",
            TvboxIndexMapper.originSiteKey("cms/9", """{"donorSiteKey":"donor"}"""),
        )
        assertEquals("cms", TvboxIndexMapper.originSiteKey("cms/9", null))
        assertNull(TvboxIndexMapper.originSiteKey("cms", null))
    }

    private fun movie(
        id: String,
        donorSiteKey: String? = null,
        pic: String? = null,
    ) = Movie(id = id, name = "Film", pic = pic, typeName = "电影", donorSiteKey = donorSiteKey)
}