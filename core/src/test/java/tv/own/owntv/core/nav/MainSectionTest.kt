package tv.own.owntv.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSectionTest {

    @Test
    fun `dynamic mode always shows home, with or without a playlist`() {
        for (live in listOf(false, true)) for (movies in listOf(false, true)) for (series in listOf(false, true)) {
            assertTrue(MainSection.HOME in MainSection.dynamicVisible(live, movies, series))
        }
        assertEquals(setOf(MainSection.HOME), MainSection.dynamicVisible(false, false, false))
    }

    @Test
    fun `static mode hides only sections the user hid, and ignores unknown names`() {
        // A hidden set saved by an older build may still name a section this build no longer has.
        assertTrue(MainSection.HOME in MainSection.staticVisible(setOf("SPORTS", "MOVIES")))
        assertFalse(MainSection.MOVIES in MainSection.staticVisible(setOf("MOVIES")))
    }
}
