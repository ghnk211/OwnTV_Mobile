package tv.own.owntv.mobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search finds a setting by its own name. The group pages' rows cannot drift from it — the
 * search reads the same `allGroupSections` the pages draw. The rows inside the full pages can: search
 * knows them only through the hand-kept `LEAF_ROWS` list, and a row added to a page but not to the list
 * is unfindable by name.
 *
 * Source-scanned rather than executed — these are Compose pages needing a full Android graph, and the
 * thing worth checking is the text itself.
 */
class SettingsSearchCoverageTest {

    private val dir = File("src/main/java/tv/own/owntv/mobile/ui/screens/settings")

    private fun read(name: String): String {
        val file = File(dir, name)
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    private val screen: String by lazy { read("SettingsScreen.kt") }

    /** Titles search can match, by string resource name: the group rows, the pages themselves and `LEAF_ROWS`. */
    private val indexed: Set<String> by lazy {
        Regex("""R\.string\.([a-z_0-9]+)""")
            .findAll(read("SettingsGroupRows.kt") + read("SettingsPlayerRows.kt") + read("SettingsLeaf.kt") + screen)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `group rows come from the pages' own row list`() {
        // If search ever gets its own copy of the group rows, they start going missing the way the old
        // hand-written list did.
        assertTrue("search no longer reads allGroupSections", screen.contains("allGroupSections(vm)"))
        assertTrue("search no longer walks every group's rows", Regex("""all\.filterKeys""").containsMatchIn(screen))
    }

    @Test
    fun `leaf rows name a title, not a description`() {
        // A description string would read as a sentence in the results instead of a setting's name.
        assertEquals(
            emptyList<String>(),
            Regex("""SettingsLeaf\.[A-Z_]+ to R\.string\.([a-z_0-9]*_description[a-z_]*)""")
                .findAll(screen).map { it.groupValues[1] }.toList(),
        )
    }

    /**
     * Every row on every full settings page. Sheet and confirmation titles, actions, section heads and
     * status lines are not settings and are listed here instead; Backup and Local sync are More pages.
     */
    @Test
    fun `every setting on every full page can be found by search`() {
        val notSettings = setOf(
            "setup_from_phone", "settings_delete_all", "settings_delete_subtitle",
            "settings_epg_sources_delete_title", "settings_sources_refresh_days_title", "settings_glass_section",
            "settings_mode", "settings_language_help_translate", "settings_metadata_active_source",
            "settings_metadata_clear_advanced_title", "player_subtitles_connected_as", "player_subtitles_delete_action",
            "player_subtitles_downloads", "player_subtitles_resets", "player_subtitles_sign_in", "player_subtitles_sign_out",
            "player_subtitles_stay_signed_in", "settings_sources_delete_title", "settings_sources_probe_title",
            "setup_auto_refresh_title", "setup_default_playlist", "settings_subtitle_color",
            "settings_subtitle_reset_all", "settings_sources_clean_names_title",
        )
        val skipped = setOf("SettingsBackupPage.kt", "SettingsLocalSyncPage.kt")
        val pages = dir.listFiles { f ->
            (f.name.startsWith("Settings") && f.name.contains("Page") || f.name.endsWith("Page.kt")) && f.name !in skipped
        }.orEmpty()
        assertTrue("no settings pages found", pages.size > 10)
        val missing = pages.flatMap { f ->
            Regex("""(?<![a-zA-Z])title = stringResource\(R\.string\.([a-z_0-9]+)""").findAll(f.readText())
                .map { it.groupValues[1] }
                .filterNot { it.endsWith("_description") || it in notSettings || it in indexed }
                .map { "${f.name}: $it" }.toList()
        }.distinct().sorted()
        assertEquals("settings missing from the settings search index (LEAF_ROWS)", emptyList<String>(), missing)
    }
}
