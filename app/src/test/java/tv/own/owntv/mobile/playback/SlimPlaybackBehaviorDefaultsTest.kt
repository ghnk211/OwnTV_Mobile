package tv.own.owntv.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The slimmed settings pages keep only the rows listed here; every removed playback-behaviour
 * value must be converged to its factory default on cold start, or an old value would keep driving
 * the engines with no row left to change it.
 *
 * Source-scanned rather than executed — the pages are Composables needing a full Android graph, and
 * the thing worth checking is which rows and which setters exist, not runtime behaviour.
 */
class SlimPlaybackBehaviorDefaultsTest {

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

    private fun keys(section: String): List<String> =
        Regex(""""(vp_[a-z_0-9]+)"""").findAll(section).map { it.groupValues[1] }.toList()

    private val playerRows by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsPlayerRows.kt", "playerSections")
    }

    private val pictureRows by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsPlayerRows.kt", "pictureSections")
    }

    private val liveRows by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsPlayerRows.kt", "liveSections")
    }

    private val watchingRows by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsPlayerRows.kt", "watchingSections")
    }

    private val appRows by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsGroupRows.kt", "appSections")
    }

    private val convergence by lazy {
        functionText("src/main/java/tv/own/owntv/mobile/playback/SlimPlayerDefaults.kt", "applySlimPlaybackBehaviorDefaults")
    }

    @Test
    fun `player page has no diagnostics section`() {
        assertEquals(listOf("vp_external", "vp_vod_reconnects"), keys(playerRows))
    }

    @Test
    fun `picture page drops software decode, tunneled playback and frame rate`() {
        assertEquals(listOf("vp_hw", "vp_hdr", "vp_zoom", "vp_reset_zoom"), keys(pictureRows))
    }

    @Test
    fun `live page keeps only the timeshift rows`() {
        assertEquals(listOf("vp_timeshift", "vp_timeshift_window", "vp_timeshift_resume"), keys(liveRows))
    }

    @Test
    fun `watching page drops the step lengths and gesture sensitivity`() {
        val keys = keys(watchingRows)
        assertTrue("vp_seek_step still on the page: $keys", "vp_seek_step" !in keys)
        assertTrue("vp_rewind_step still on the page: $keys", "vp_rewind_step" !in keys)
        assertTrue("gesture_sensitivity still on the page: $keys", "gesture_sensitivity" !in keys)
        assertTrue("vp_resume missing from the page: $keys", "vp_resume" in keys)
        assertTrue("vp_autoplay missing from the page: $keys", "vp_autoplay" in keys)
    }

    @Test
    fun `app page has no diagnostics section`() {
        val keys = keys(appRows)
        assertTrue("vp_measured_stats still on the page: $keys", "vp_measured_stats" !in keys)
        assertTrue("vp_logging still on the page: $keys", "vp_logging" !in keys)
    }

    @Test
    fun `quick list no longer offers the removed switches`() {
        val text = read("src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsQuick.kt")
        listOf("vp_sw_above_1080p", "vp_measured_stats", "vp_logging", "detailed_diagnostics").forEach {
            assertTrue("\"$it\" still pinnable", "\"$it\"" !in text)
        }
    }

    @Test
    fun `every removed playback setting converges to its default`() {
        val expected = listOf(
            "setMeasuredStreamStats", "setDetailedDiagnostics", "setVodNetworkTimeoutSecs",
            "setTunneledPlayback", "setSoftwareDecodeAbove1080p", "setAutoFrameRateFilms",
            "setAutoFrameRateLive", "setLiveLatencyMode", "setLiveLatencyCustomSecs",
            "setLiveTuneTimeoutSecs", "setSeekStepSec", "setLiveRewindStepSec",
            "setGestureSensitivityPct",
        )
        assertEquals(
            "setters missing from applySlimPlaybackBehaviorDefaults",
            emptyList<String>(),
            expected.filterNot { it in convergence },
        )
    }

    @Test
    fun `playback behaviour defaults are applied on cold start`() {
        assertTrue(
            "applySlimPlaybackBehaviorDefaults is not mounted in OwnTVMobileApp",
            read("src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt").contains("applySlimPlaybackBehaviorDefaults"),
        )
    }
}