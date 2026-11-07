package tv.own.owntv.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The slim sound page keeps only the surround mode and the subtitle appearance; every other stored
 * value must be converged to its factory default on cold start, or an old value would keep driving
 * the engines with no row left to change it.
 *
 * Source-scanned rather than executed — the page is a Composable needing a full Android graph, and
 * the thing worth checking is which rows and which setters exist, not runtime behaviour.
 */
class SlimSoundDefaultsTest {

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    private val rows: String by lazy {
        val text = read("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsPlayerRows.kt")
        Regex("""internal fun soundSections.*?\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value ?: error("soundSections not found")
    }

    private val convergence: String by lazy {
        val text = read("src/main/java/tv/own/owntv/mobile/playback/SlimPlayerDefaults.kt")
        Regex("""internal suspend fun applySlimSoundDefaults.*?\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value ?: error("applySlimSoundDefaults not found")
    }

    @Test
    fun `sound page has only surround and subtitle appearance`() {
        val keys = Regex(""""(vp_[a-z_]+)"""").findAll(rows).map { it.groupValues[1] }.toList()
        assertEquals(listOf("vp_surround", "vp_sub_style"), keys)
    }

    @Test
    fun `every removed sound setting converges to its default`() {
        val expected = listOf(
            "setDefaultVolume", "setAudioPassthrough", "setNightMode", "setVolumeLevelling",
            "setAudioDelayMs", "setOutputAudioDelay", "setPreferredAudioLang", "setPreferredSubLang",
            "FfmpegAudioSetting.save", "clearVolume", "clearAudioDelay",
        )
        assertEquals(
            "setters missing from applySlimSoundDefaults",
            emptyList<String>(),
            expected.filterNot { it in convergence },
        )
    }

    @Test
    fun `sound defaults are applied on cold start`() {
        assertTrue(
            "applySlimSoundDefaults is not mounted in OwnTVMobileApp",
            read("src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt").contains("applySlimSoundDefaults"),
        )
    }
}
