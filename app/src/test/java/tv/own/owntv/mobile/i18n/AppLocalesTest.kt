package tv.own.owntv.mobile.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-language whitelist, checked where it is decided rather than where it is drawn.
 *
 * Packaging, the pickers and the persisted tag all read this object, and each has its own way of
 * failing quietly: a qualifier list that no longer matches the tags ships an APK with no strings for a
 * language the picker still offers, and a `normalize` that lets a tag through leaves that language
 * selected in Settings while the resources fall back to English.
 */
class AppLocalesTest {

    @Test
    fun `the whitelist is the three shipped languages`() {
        assertEquals(listOf("en-US", "zh-CN", "zh-TW"), AppLocales.shippedTags)
        assertEquals(listOf("en", "zh-rCN", "zh-rTW"), AppLocales.shippedQualifiers)
    }

    @Test
    fun `picker rows are the whitelist, in order, and core knows every one`() {
        assertEquals(AppLocales.shippedTags, AppLocales.rows.map { it.languageTag })
        assertEquals(AppLocales.shippedQualifiers, AppLocales.rows.map { it.resourceQualifier })
        assertEquals(3, AppLocales.rows.size)
        assertTrue(AppLocales.rows.all { it.packaged })
    }

    @Test
    fun `shipped tags and the follow-system tag survive unchanged`() {
        assertEquals("", AppLocales.normalize(""))
        assertEquals("", AppLocales.normalize("     "))
        AppLocales.shippedTags.forEach { assertEquals(it, AppLocales.normalize(it)) }
        // Case and stray spaces come from hand-edited prefs and from restores, not from the picker.
        assertEquals("zh-CN", AppLocales.normalize(" zh-cn "))
    }

    @Test
    fun `every english variant collapses to en-US`() {
        // D6: en-GB is not packaged, so it must not stay selected over no resources.
        assertEquals("en-US", AppLocales.normalize("en-GB"))
        assertEquals("en-US", AppLocales.normalize("en"))
        assertEquals("en-US", AppLocales.normalize("en-AU"))
        assertEquals("en-US", AppLocales.normalize("EN-gb"))
    }

    @Test
    fun `unsupported languages fall back to the phone rather than a dead tag`() {
        listOf("de", "fr", "ar", "bn", "pt-BR", "zh-HK", "zh-Hant-TW", "ja").forEach {
            assertEquals("$it", "", AppLocales.normalize(it))
            assertTrue("$it", AppLocales.isShipped(AppLocales.normalize(it)))
        }
    }

    @Test
    fun `nothing outside the whitelist is offered as shipped`() {
        assertFalse(AppLocales.isShipped("de"))
        assertFalse(AppLocales.isShipped("en-GB"))
        assertFalse(AppLocales.isShipped("zh-HK"))
        assertTrue(AppLocales.isShipped(""))
        AppLocales.shippedTags.forEach { assertTrue(it, AppLocales.isShipped(it)) }
    }

    @Test
    fun `normalize is idempotent, so the app-start guard settles`() {
        // The guard writes back only when normalize changed the tag; a second pass must be a no-op or it
        // would write on every emission forever.
        listOf("en-GB", "de", "", "zh-TW", "ar-XB").forEach { raw ->
            val once = AppLocales.normalize(raw)
            assertEquals(raw, once, AppLocales.normalize(once))
        }
    }
}
