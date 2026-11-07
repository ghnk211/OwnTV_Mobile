package tv.own.owntv.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The watching & recording page keeps only the resume choice and the five recording rows; every
 * other stored value must be converged to its factory default on cold start, or an old value would
 * keep driving playback with no row left to change it.
 *
 * Source-scanned rather than executed — the page is a Composable needing a full Android graph, and
 * the thing worth checking is which rows and which setters exist, not runtime behaviour.
 */
class SlimWatchingDefaultsTest {

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    private fun functionText(path: String, name: String): String {
        val text = read(path)
        return Regex("""(?:private |internal )?fun $name\(.*?\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value ?: error("$name not found in $path")
    }

    private val rows: String by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsPlayerRows.kt", "watchingSections")
    }

    private val convergence: String by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/playback/SlimPlayerDefaults.kt", "applySlimWatchingDefaults")
    }

    @Test
    fun `watching page has only resume and the five recording rows`() {
        val keys = Regex(""""([a-z][a-z_0-9]+)"""").findAll(rows).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("vp_resume", "rec_reserve", "rec_mobile_data", "rec_watching", "rec_pre_roll", "rec_post_roll"),
            keys,
        )
    }

    @Test
    fun `every removed watching setting converges to its default`() {
        val expected = listOf(
            "setAutoPlayNext", "setMultiviewEnabled", "setMultiviewTiles", "setMiniPlayerStyle",
            "setPipEnabled", "setPipSize", "setPipSnap", "setBackgroundPlayback",
            "setAudioOnScreenOff", "setAudioOnMobileData", "setAudioPerChannel",
        )
        assertEquals(
            "setters missing from applySlimWatchingDefaults",
            emptyList<String>(),
            expected.filterNot { it in convergence },
        )
    }

    @Test
    fun `watching defaults are applied on cold start`() {
        assertTrue(
            "applySlimWatchingDefaults is not mounted in OwnTVMobileApp",
            read("src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt").contains("applySlimWatchingDefaults"),
        )
    }
}
