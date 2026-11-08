package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.charset.Charset

class TxtPlaylistParserTest {

    private fun parse(text: String): Pair<M3uHeader, List<M3uEntry>> =
        parseBytes(text.toByteArray(Charsets.UTF_8))

    private fun parseBytes(bytes: ByteArray): Pair<M3uHeader, List<M3uEntry>> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        val header = TxtPlaylistParser().parse(bytes.inputStream()) { out += it }
        header to out
    }

    @Test
    fun oneUrlPerLineGetsDerivedNames() {
        val (_, entries) = parse(
            """
            http://a.example/1.m3u8
            https://b.example/2.m3u8

            http://a.example/3.m3u8
            """.trimIndent(),
        )
        assertEquals(3, entries.size)
        assertEquals("a.example", entries[0].name)
        assertEquals("b.example", entries[1].name)
        assertEquals("a.example 2", entries[2].name)
        assertEquals("http://a.example/1.m3u8", entries[0].streamUrl)
    }

    @Test
    fun commaPipeAndSpaceSeparatedNames() {
        val (_, entries) = parse(
            """
            CCTV1,http://a.example/1.m3u8
            CCTV2|http://b.example/2.m3u8
            CCTV3 http://c.example/3.m3u8
            CCTV4，http://d.example/4.m3u8
            """.trimIndent(),
        )
        assertEquals(4, entries.size)
        assertEquals(listOf("CCTV1", "CCTV2", "CCTV3", "CCTV4"), entries.map { it.name })
        assertEquals("http://b.example/2.m3u8", entries[1].streamUrl)
    }

    @Test
    fun extInfLineNamesTheNextUrl() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv5" group-title="体育",CCTV5 体育
            http://a.example/5.m3u8
            #EXTINF:-1,Movie, The
            http://b.example/movie.m3u8
            """.trimIndent(),
        )
        // A leading #EXTM3U delegates the whole document to M3uParser, which handles both names
        // and the group-title attribute.
        assertEquals(2, entries.size)
        assertEquals("CCTV5 体育", entries[0].name)
        assertEquals("体育", entries[0].groupTitle)
        assertEquals("Movie, The", entries[1].name)
    }

    @Test
    fun extInfWithoutAnM3uHeaderStillNamesTheNextUrl() {
        val (_, entries) = parse(
            """
            #EXTINF:-1,CCTV5 体育
            http://a.example/5.m3u8
            #EXTINF:-1 tvg-name="CCTV1",CCTV1 综合
            http://b.example/1.m3u8
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals("CCTV5 体育", entries[0].name)
        assertEquals("CCTV1 综合", entries[1].name)
        assertEquals("http://a.example/5.m3u8", entries[0].streamUrl)
    }

    @Test
    fun genreMarkersBecomeCategories() {
        val (_, entries) = parse(
            """
            央视频道,#genre#
            CCTV1,http://a.example/1.m3u8
            CGTN,http://a.example/cgtn.m3u8

            卫视频道,#genre#
            湖南卫视,http://b.example/hunan.m3u8
            """.trimIndent(),
        )
        assertEquals(3, entries.size)
        assertEquals(listOf("央视频道", "央视频道", "卫视频道"), entries.map { it.groupTitle })
    }

    @Test
    fun blankLinesCommentsAndInvalidLinesAreIgnored() {
        val (_, entries) = parse(
            """
            # 这是一条注释

            这一行没有地址
            #EXTGRP:新闻
            ###
            央视一套,http://a.example/1.m3u8

            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("央视一套", entries[0].name)
    }

    @Test
    fun utf8BomIsStripped() {
        val text = "央视一套,http://a.example/1.m3u8\n"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        val (_, entries) = parseBytes(bytes)
        assertEquals(1, entries.size)
        assertEquals("央视一套", entries[0].name)
    }

    @Test
    fun gbkEncodedNamesAreReadable() {
        val text = "央视一套,http://a.example/1.m3u8\n湖南卫视,http://b.example/2.m3u8\n"
        val bytes = text.toByteArray(Charset.forName("GBK"))
        val (_, entries) = parseBytes(bytes)
        assertEquals(2, entries.size)
        assertEquals("央视一套", entries[0].name)
        assertEquals("湖南卫视", entries[1].name)
    }

    @Test
    fun utf16BomIsSupported() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "央视一套,http://a.example/1.m3u8".toByteArray(Charsets.UTF_16LE)
        val (_, entries) = parseBytes(bytes)
        assertEquals(1, entries.size)
        assertEquals("央视一套", entries[0].name)
    }

    @Test
    fun duplicateUrlsAreDropped() {
        val (_, entries) = parse(
            """
            CCTV1,http://a.example/1.m3u8
            CCTV1 备份,http://a.example/1.m3u8
            CCTV2,http://b.example/2.m3u8
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals(listOf("CCTV1", "CCTV2"), entries.map { it.name })
    }

    @Test
    fun emptyResultThrowsInsteadOfCreatingASource() {
        try {
            parse(
                """
                # 只有注释

                没有地址的一行
                """.trimIndent(),
            )
            fail("expected an IOException for an empty playlist")
        } catch (e: IOException) {
            assertEquals(TxtPlaylistParser.EMPTY, e.message)
        }
    }

    @Test
    fun m3uContentInsideTxtDelegatesToM3uParser() {
        val (header, entries) = parse(
            """
            #EXTM3U url-tvg="http://epg.example/xmltv.xml"
            #EXTINF:-1 tvg-logo="http://logo.example/a.png" group-title="新闻",CCTV 新闻
            http://a.example/news.m3u8
            """.trimIndent(),
        )
        assertEquals("http://epg.example/xmltv.xml", header.urlTvg)
        assertEquals(1, entries.size)
        assertEquals("CCTV 新闻", entries[0].name)
        assertEquals("http://logo.example/a.png", entries[0].logo)
    }

    @Test
    fun txtEntriesStayLiveEvenForMp4Links() {
        val (_, entries) = parse(
            """
            央视频道,#genre#
            支持作者,https://cdn.example/clip.mp4
            2024年春晚,https://cdn.example/gala.m3u8
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        entries.forEach {
            assertEquals("live", it.type)
            assertFalse("TXT lines must stay in the Live TV grid", it.isVod)
        }
    }

    @Test
    fun pipeHeadersAreSplitOffTheUrl() {
        val (_, entries) = parse("CCTV1,http://a.example/1.m3u8|User-Agent=Foo&Referer=Bar")
        assertEquals(1, entries.size)
        assertEquals("http://a.example/1.m3u8", entries[0].streamUrl)
        assertEquals("Foo", entries[0].headers["User-Agent"])
        assertEquals("Bar", entries[0].headers["Referer"])
    }

    @Test
    fun realWorldTxtSample() {
        val (_, entries) = parse(
            """
            央视频道,#genre#
            CGTN,https://0472.org/hls/cgtn.m3u8
            CGTN,http://english-livetx.cgtn.com/hls/yypdyyctzb_hd.m3u8
            CGTN法语,http://espanol-liveali.cgtn.com/hls/LSveOGBaBw41Ea7ukkVAUdKQ.m3u8

            卫视频道,#genre#
            东方卫视,http://bp-resource-dfl.bestv.cn/155/3/video.m3u8
            广东卫视,https://txmov2.a.kwimgs.com/upic/2023/01/26/09/x.mp4
            """.trimIndent(),
        )
        assertEquals(5, entries.size)
        assertTrue(entries.all { it.streamUrl.startsWith("http") })
        assertTrue(entries.all { it.name.isNotBlank() })
        assertNotNull(entries.last().groupTitle)
    }
}
