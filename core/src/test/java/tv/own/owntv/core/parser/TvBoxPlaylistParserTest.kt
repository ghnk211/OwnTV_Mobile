package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TVBox `lives` shapes OwnTV has to turn into playable live channels. */
class TvBoxPlaylistParserTest {

    private fun parse(
        text: String,
        onExternal: (suspend (TvBoxExternalSource) -> Unit)? = null,
    ): Pair<List<M3uEntry>, M3uHeader> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        val header = TvBoxPlaylistParser().parse(text.byteInputStream(), { out += it }, onExternal)
        out to header
    }

    @Test
    fun groupsChannelsAndFallbackLines() {
        val (entries, header) = parse(
            """{"epg":"http://epg.example/x.xml","lives":[
                {"group":"央视","channels":[
                    {"name":"CCTV-1","tvg_name":"cctv1","urls":["http://a/1.m3u8#http://b/1.m3u8"]},
                    {"name":"CCTV-2","urls":["http://a/2.m3u8"]}
                ]},
                {"group":"卫视","channels":[]}
            ]}""",
        )
        assertEquals(2, entries.size)
        assertEquals("CCTV-1", entries[0].name)
        assertEquals("http://a/1.m3u8", entries[0].streamUrl)
        assertEquals("央视", entries[0].groupTitle)
        assertEquals("cctv1", entries[0].tvgId)
        assertEquals("live", entries[0].type)
        assertFalse(entries[0].isVod)
        assertEquals("央视", entries[1].groupTitle)
        assertEquals("http://a/2.m3u8", entries[1].streamUrl)
        assertEquals("http://epg.example/x.xml", header.urlTvg)
    }

    @Test
    fun perChannelHeadersAndAlternateKeys() {
        val (entries, _) = parse(
            """{"lives":[{"title":"地方台","chs":[
                {"title":"北京卫视","logo":"http://logo/b.png","ua":"MyPlayer/1.0",
                 "referer":"http://ref.example/","urls":["http://a/b.m3u8"]}
            ]}]}""",
        )
        assertEquals(1, entries.size)
        assertEquals("北京卫视", entries[0].name)
        assertEquals("地方台", entries[0].groupTitle)
        assertEquals("http://logo/b.png", entries[0].logo)
        assertEquals("MyPlayer/1.0", entries[0].headers["User-Agent"])
        assertEquals("http://ref.example/", entries[0].headers["Referer"])
    }

    @Test
    fun groupMapShapeIsWalked() {
        val (entries, _) = parse(
            """{"lives":{"央视":[{"name":"C1","urls":["http://a/1.m3u8"]}]}}""",
        )
        assertEquals(1, entries.size)
        assertEquals("央视", entries[0].groupTitle)
    }

    @Test
    fun externalListReferencesAreReportedNotTurnedIntoChannels() {
        val externals = mutableListOf<TvBoxExternalSource>()
        val (entries, _) = parse(
            """{"lives":[{"name":"外部列表","type":0,"playerType":2,
                "url":"http://host/list.txt","epg":"http://epg.example/x.xml"}]}""",
            onExternal = { externals += it },
        )
        assertTrue(entries.isEmpty())
        assertEquals(1, externals.size)
        assertEquals("http://host/list.txt", externals[0].url)
    }

    @Test
    fun publishedTvBoxConfigsReportTheirExternalLists() {
        val externals = mutableListOf<TvBoxExternalSource>()
        val (entries, _) = parse(
            """{"lives":[
                {"name":"Kimentanm","type":0,"url":"https://down.nigx.cn/iptv.m3u","playerType":2},
                {"name":"综合直播","type":0,"playerType":2,
                 "url":"http://193.123.86.190:14888/TV/iptv.php","ua":"bingcha/1.1"},
                {"name":"极直播电视","url":"http://php.example/jzb.php?key=x"},
                {"name":"python采集","type":3,"api":"http://host/x.py","ext":{}}
            ]}""",
            onExternal = { externals += it },
        )
        assertTrue(entries.isEmpty())
        assertEquals(3, externals.size)
        assertEquals("bingcha/1.1", externals[1].userAgent)
        assertEquals("http://php.example/jzb.php?key=x", externals[2].url)
    }

    @Test
    fun bomAndLineCommentsAreTolerated() {
        val (entries, _) = parse(
            "\uFEFF// hand-maintained config\n" +
                """{"lives":[{"group":"央视","channels":[{"name":"C1","urls":["http://a/1.m3u8"]}]}]}""",
        )
        assertEquals(1, entries.size)
        assertEquals("C1", entries[0].name)
    }

    @Test
    fun locationDetectionOnlyAcceptsJson() {
        assertTrue(TvBoxPlaylistParser.isTvBoxLocation("https://host/config.json?token=1"))
        assertTrue(TvBoxPlaylistParser.isTvBoxLocation("/data/user/0/tv/config.JSON"))
        assertFalse(TvBoxPlaylistParser.isTvBoxLocation("https://host/playlist.m3u"))
        assertFalse(TvBoxPlaylistParser.isTvBoxLocation("https://host/config.txt"))
    }
}
