package tv.own.owntv.mobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.entity.ProfileEntity

/**
 * The one-profile name policy, row for row.
 *
 * [BackupProfiles.read] is not here on purpose: it goes through `org.json` and the container's own
 * decryption, and a JVM unit test with `isReturnDefaultValues` would have every JSON call answer with
 * a default rather than with data — a test that passed on nothing. What is worth getting wrong is the
 * table below, and the table is pure.
 *
 * The stakes are a household's data: too strict and a restore of their own backup is refused, too
 * lax and the app ends up holding a profile row nothing can open, with the favorites inside it.
 */
class BackupProfilesPolicyTest {

    private val device = ProfileEntity(id = 7L, name = "Ada", avatarColor = 0)
    private val sources = setOf(BackupManager.Section.SOURCES)
    private val noSources = setOf(BackupManager.Section.FAVORITES)

    private fun summary(count: Int, name: String?) = BackupProfiles.Summary(count, name)

    private fun decide(
        summary: BackupProfiles.Summary?,
        deviceProfile: ProfileEntity? = device,
        otherNames: List<String> = emptyList(),
        sections: Set<BackupManager.Section> = sources,
    ) = BackupProfiles.decide(summary, deviceProfile, otherNames, sections)

    /** Row: unreadable, or not a profile backup at all — core reports the real error, not us. */
    @Test
    fun `an unreadable summary is allowed through`() {
        assertEquals(BackupProfiles.Decision(), decide(null))
    }

    /** Row: more than one profile. */
    @Test
    fun `a container of several profiles is refused`() {
        assertEquals(BackupProfiles.Block.MULTIPLE, decide(summary(2, "Ada")).block)
        // Whatever else is true about it — the name would have matched, and the sections would not
        // have touched profiles. There is nowhere on this app to put the second one.
        assertEquals(BackupProfiles.Block.MULTIPLE, decide(summary(3, "Ada"), sections = noSources).block)
        assertEquals(BackupProfiles.Block.MULTIPLE, decide(summary(2, "Ada"), deviceProfile = null).block)
    }

    /** Row: no profiles — a settings-only or pre-v11 file. */
    @Test
    fun `a container of no profiles is allowed`() {
        assertEquals(BackupProfiles.Decision(), decide(summary(0, null)))
    }

    /** Row: one profile, unnamed. */
    @Test
    fun `a nameless profile is refused whatever it would have matched`() {
        assertEquals(BackupProfiles.Block.UNNAMED, decide(summary(1, null)).block)
        assertEquals(BackupProfiles.Block.UNNAMED, decide(summary(1, "   ")).block)
        assertEquals(BackupProfiles.Block.UNNAMED, decide(summary(1, ""), sections = noSources).block)
    }

    /** Row: one profile, and this device has none to match it against. */
    @Test
    fun `a device with no profile adopts the incoming one`() {
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "Ada"), deviceProfile = null))
    }

    /** Row: one profile, same name. */
    @Test
    fun `a matching name restores with no rename and no refusal`() {
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "Ada")))
        // Matched as core matches it: trimmed, and not caring about case.
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "ada")))
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "  Ada  ")))
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "  ADA "), otherNames = listOf("Grace")))
    }

    /** Row: one profile whose name is already taken by a dormant row on this device. */
    @Test
    fun `a name belonging to a dormant profile is refused`() {
        assertEquals(BackupProfiles.Block.HIDDEN, decide(summary(1, "Grace"), otherNames = listOf("Grace")).block)
        // Same tolerance for the dormant side: those rows were written by the old profile editor.
        assertEquals(BackupProfiles.Block.HIDDEN, decide(summary(1, " grace "), otherNames = listOf("GRACE")).block)
        // And it only bites when the profiles really would be applied.
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "Grace"), otherNames = listOf("Grace"), sections = noSources))
    }

    /**
     * Row: one profile, a different name. This device's profile is renamed to match — the alternative
     * is core creating a second row that nothing on this app can reach.
     */
    @Test
    fun `a different name renames this device's profile when profiles are restored`() {
        val decision = decide(summary(1, "  Grace  "))
        assertNull(decision.block)
        assertEquals("Grace", decision.renameTo)
    }

    /** Row: a different name, Sources unticked — no profile arrives, so nothing is renamed for it. */
    @Test
    fun `a different name without sources changes nothing`() {
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "Grace"), sections = noSources))
        assertEquals(BackupProfiles.Decision(), decide(summary(1, "Grace"), sections = emptySet()))
    }

    /**
     * Sweeping every input shape: a decision is a refusal or a rename, never both, so a sheet can always
     * say in one sentence what restoring will do.
     */
    @Test
    fun `no input both refuses and renames`() {
        val counts = listOf(0, 1, 2)
        val names = listOf(null, "", "  ", "Ada", "Grace")
        val devices = listOf(null, device)
        val dormants = listOf(emptyList(), listOf("Grace"), listOf("ada"))
        val sectionSets = listOf(sources, noSources, emptySet(), BackupManager.Section.entries.toSet())
        for (count in counts) {
            for (name in names) {
                for (profile in devices) {
                    for (dormant in dormants) {
                        for (sectionSet in sectionSets) {
                            val decision = decide(summary(count, name), profile, dormant, sectionSet)
                            assertEquals(
                                "count=$count name=$name device=${profile?.name} dormant=$dormant sections=$sectionSet",
                                true,
                                decision.block == null || decision.renameTo == null,
                            )
                        }
                    }
                }
            }
        }
    }
}
