package tv.own.owntv.mobile.tvbox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvboxDownloadKeyTest {

    private fun ref(
        siteKey: String = "site",
        vodId: String = "vod",
        flag: String = "https://x/e1",
        episodeId: String = "id1",
        index: Int = 0,
    ) = TvboxDownloadKey.Ref(siteKey, vodId, flag, episodeId, "第1集", index, "线路1")

    @Test
    fun encodeDecodeRoundTrips() {
        val r = ref()
        assertEquals(r, TvboxDownloadKey.decode(TvboxDownloadKey.encode(r)))
    }

    @Test
    fun decodeRejectsBlankMalformedAndOtherKind() {
        assertNull(TvboxDownloadKey.decode(null))
        assertNull(TvboxDownloadKey.decode(""))
        assertNull(TvboxDownloadKey.decode("{"))
        assertNull(TvboxDownloadKey.decode("""{"kind":"other","siteKey":"s","vodId":"v","index":0}"""))
        assertNull(TvboxDownloadKey.decode("""{"kind":"tvbox","siteKey":"","vodId":"v","index":0}"""))
        assertNull(TvboxDownloadKey.decode("""{"kind":"tvbox","siteKey":"s","vodId":"v","index":-1}"""))
    }

    @Test
    fun identityIsStableAndDoesNotCollideOnPipes() {
        val a = ref(flag = "a|b", episodeId = "c")
        val b = ref(flag = "a", episodeId = "b|c")
        assertTrue(TvboxDownloadKey.identity(a) != TvboxDownloadKey.identity(b))
        assertEquals(TvboxDownloadKey.identity(a), TvboxDownloadKey.identity(a.copy()))
    }

    @Test
    fun identityUsesIndexWhenBothIdsAreBlank() {
        val first = ref(flag = "", episodeId = "", index = 0)
        val second = ref(flag = "", episodeId = "", index = 1)
        assertTrue(TvboxDownloadKey.identity(first) != TvboxDownloadKey.identity(second))
    }

    @Test
    fun identitySeparatesTheSameEpisodeOnDifferentSites() {
        assertTrue(TvboxDownloadKey.identity(ref()) != TvboxDownloadKey.identity(ref(siteKey = "other")))
    }
}