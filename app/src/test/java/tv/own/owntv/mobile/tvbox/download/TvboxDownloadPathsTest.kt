package tv.own.owntv.mobile.tvbox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.mobile.tvbox.engine.Episode

class TvboxDownloadPathsTest {

    private fun episode(name: String, index: Int = 0, url: String = "https://x/a.mp4") =
        Episode(index = index, name = name, url = url, flag = url)

    @Test
    fun dirKeepsTheLineIndexAndSanitizesNames() {
        assertEquals("Movies/Film A/2-Line 1", TvboxDownloadPaths.dir("Film/A", 1, "Line 1"))
    }

    @Test
    fun emptyEpisodeNameFallsBackToIndex() {
        assertEquals("ep-1.mp4", TvboxDownloadPaths.fileName(episode(name = " ", index = 0), "mp4"))
    }

    @Test
    fun containerProbeRejectsPlaylistsAndDefaultsToMp4() {
        assertNull(TvboxDownloadPaths.probeContainer("https://x/index.m3u8?token=1"))
        assertEquals("mp4", TvboxDownloadPaths.probeContainer("https://x/play/123"))
        assertEquals("ts", TvboxDownloadPaths.probeContainer("https://x/a.ts"))
    }
}