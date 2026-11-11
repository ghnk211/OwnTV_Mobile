package tv.own.owntv.core.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PlaylistFormatTest {

    @Test
    fun explicitExtensionsDecideWithoutReadingTheStream() {
        assertEquals(PlaylistKind.M3U, PlaylistFormat.fromLocation("http://host/list.m3u"))
        assertEquals(PlaylistKind.M3U, PlaylistFormat.fromLocation("http://host/list.M3U8"))
        assertEquals(PlaylistKind.TXT, PlaylistFormat.fromLocation("https://live.zbds.top/tv/iptv4.txt"))
        assertEquals(PlaylistKind.TXT, PlaylistFormat.fromLocation("/sdcard/Download/iptv.txt"))
        assertEquals(PlaylistKind.TXT, PlaylistFormat.fromLocation("content://media/list.text"))
        assertNull(PlaylistFormat.fromLocation("http://host/get.php?type=m3u_plus"))
        assertNull(PlaylistFormat.fromLocation("http://host/list"))
    }

    @Test
    fun queryAndFragmentDoNotHideTheExtension() {
        assertEquals(PlaylistKind.TXT, PlaylistFormat.fromLocation("http://host/list.txt?token=abc#top"))
        assertEquals(PlaylistKind.M3U, PlaylistFormat.fromLocation("http://host/list.m3u8?token=abc"))
    }

    @Test
    fun extensionlessM3uIsRecognisedAndItsBytesAreReplayed() {
        val original = "#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://a.example/1.m3u8\n".toByteArray()
        val detected = PlaylistFormat.detect("http://host/get.php", original.inputStream())
        assertEquals(PlaylistKind.M3U, detected.kind)
        assertArrayEquals(original, detected.stream.readBytes())
    }

    @Test
    fun extensionlessTxtIsRecognisedAndItsBytesAreReplayed() {
        val original = "央视频道,#genre#\nCCTV1,http://a.example/1.m3u8\n".toByteArray(Charsets.UTF_8)
        val detected = PlaylistFormat.detect("http://host/list", original.inputStream())
        assertEquals(PlaylistKind.TXT, detected.kind)
        assertArrayEquals(original, detected.stream.readBytes())
    }

    @Test
    fun explicitM3uExtensionNeverPeeks() {
        // A stream that would fail on read proves the .m3u path is left completely untouched.
        val stream = object : java.io.ByteArrayInputStream(ByteArray(0)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw AssertionError("the original M3U path must not be re-read")
        }
        val detected = PlaylistFormat.detect("http://host/list.m3u8", stream)
        assertEquals(PlaylistKind.M3U, detected.kind)
        assertTrue(detected.stream === stream)
    }

    @Test
    fun binaryPayloadIsRejectedWithASpecificReason() {
        val png = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        ) + ByteArray(64)
        try {
            PlaylistFormat.detect("http://host/list", png.inputStream())
            fail("expected a non-text failure")
        } catch (e: IOException) {
            assertEquals(PlaylistFormat.NON_TEXT_CONTENT, e.message)
        }
    }

    @Test
    fun headLooksLikeAPlaylist() {
        assertTrue(PlaylistFormat.looksLikePlaylistText("#EXTM3U\n#EXTINF:-1,CCTV1"))
        assertTrue(PlaylistFormat.looksLikePlaylistText("央视频道,#genre#\nCCTV1,http://a/1.m3u8"))
        assertTrue(PlaylistFormat.looksLikePlaylistText("http://a.example/1.m3u8"))
        assertFalse(PlaylistFormat.looksLikePlaylistText("<!DOCTYPE html><html><body>404</body></html>"))
        assertFalse(PlaylistFormat.looksLikePlaylistText("just a sentence"))
    }
}
