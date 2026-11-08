package tv.own.owntv.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SupportedLocales regression tests.
 *
 * The generated catalogue is pure Kotlin data — no Android framework dependencies — so it is fully
 * testable on the JVM. These tests pin the catalogue's structural invariants so a stale or
 * hand-edited generation is caught here, not at picker-runtime.
 */
class SupportedLocalesTest {

    @Test
    fun `catalogue ships English and the two Chinese resources`() {
        assertEquals(
            listOf("en-US", "zh-CN", "zh-TW"),
            SupportedLocales.all.map { it.id },
        )
        assertEquals(3, SupportedLocales.all.count { it.tier == 1 })
        assertTrue(SupportedLocales.all.none { it.tier == 2 })
    }

    @Test
    fun `every catalogue locale is packaged and picker-visible`() {
        SupportedLocales.all.forEach {
            assertTrue("${it.id} should be packaged", it.packaged)
            assertTrue("${it.id} should be picker-visible", it.pickerVisible)
        }
    }

    @Test
    fun `translation readiness threshold has an exact 74 75 boundary`() {
        assertEquals(75, SupportedLocales.TRANSLATION_READINESS_THRESHOLD_PERCENT)
        assertFalse(SupportedLocales.isTranslationReady(74))
        assertTrue(SupportedLocales.isTranslationReady(75))
    }

    @Test
    fun `ids are unique`() {
        val ids = SupportedLocales.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `language tags are unique`() {
        val tags = SupportedLocales.all.map { it.languageTag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `resource qualifiers are unique`() {
        val quals = SupportedLocales.all.map { it.resourceQualifier }
        assertEquals(quals.size, quals.toSet().size)
    }

    @Test
    fun `canonicalTag accepts only catalogue tags and canonicalizes case`() {
        assertEquals("en-US", SupportedLocales.canonicalTag(" EN-us "))
        assertEquals("zh-CN", SupportedLocales.canonicalTag("ZH-cn"))
        assertEquals("zh-TW", SupportedLocales.canonicalTag("zh-tw"))
        assertEquals("", SupportedLocales.canonicalTag("  "))
        assertNull(SupportedLocales.canonicalTag("und"))
        assertNull(SupportedLocales.canonicalTag("zh-HK"))
        assertNull(SupportedLocales.canonicalTag("not-a-locale"))
    }

    @Test
    fun `scriptForTag returns the catalogue script for known tags`() {
        assertEquals("Latn", SupportedLocales.scriptForTag("en-US"))
        assertEquals("Hans", SupportedLocales.scriptForTag("zh-CN"))
        assertEquals("Hant", SupportedLocales.scriptForTag("zh-TW"))
    }

    @Test
    fun `scriptForTag returns null for unknown tags`() {
        // Tags not in the catalogue return null — the caller (LocalizedContent) then falls back to
        // ICU likely-subtags. A non-null return for an unknown tag would be a bug.
        assertNull(SupportedLocales.scriptForTag("zh-HK"))
        assertNull(SupportedLocales.scriptForTag("unknown"))
        assertNull(SupportedLocales.scriptForTag(""))
    }

    @Test
    fun `rtl is false for every shipped locale`() {
        SupportedLocales.all.forEach { assertFalse("${it.id} must not be RTL", it.rtl) }
        assertFalse(SupportedLocales.isRtl("ar"))
        assertFalse(SupportedLocales.isRtl("zh-CN"))
        assertFalse(SupportedLocales.isRtl("unknown"))
    }

    @Test
    fun `pickerRows contains every shipped locale`() {
        val rows = SupportedLocales.pickerRows
        assertEquals(SupportedLocales.all.map { it.id }, rows.map { it.id })
        rows.forEach {
            assertTrue("${it.id} must be packaged", it.packaged)
            assertTrue("${it.id} must be pickerVisible", it.pickerVisible)
        }
    }

    @Test
    fun `system default tag is the empty string`() {
        assertEquals("", SupportedLocales.SYSTEM_DEFAULT_TAG)
    }

    @Test
    fun `source language coverage is 100`() {
        val en = SupportedLocales.all.first { it.id == "en-US" }
        assertEquals(100, en.coverage)
    }

    @Test
    fun `100 percent visible community locale hides coverage badge`() {
        val complete = SupportedLocales.all.first { it.id == "zh-CN" }.copy(coverage = 100)
        assertNull(SupportedLocales.coverageBadgePercent(complete))
    }

    @Test
    fun `partial visible community locale shows badge while source and hidden locales do not`() {
        val visible = SupportedLocales.all.first { it.id == "zh-CN" }.copy(coverage = 99)
        val source = SupportedLocales.all.first { it.id == "en-US" }.copy(coverage = 99)
        val hidden = visible.copy(pickerVisible = false)
        val regionalOverride = visible.copy(tier = 0, pickerVisible = true)
        assertEquals(99, SupportedLocales.coverageBadgePercent(visible))
        assertNull(SupportedLocales.coverageBadgePercent(source))
        assertNull(SupportedLocales.coverageBadgePercent(hidden))
        assertNull(SupportedLocales.coverageBadgePercent(regionalOverride))
    }

    @Test
    fun `locale coverage stays within percentage bounds`() {
        SupportedLocales.all.forEach { locale ->
            assertTrue("${locale.id} coverage is out of range", locale.coverage in 0..100)
        }
    }

    @Test
    fun `every entry has non-blank required fields`() {
        SupportedLocales.all.forEach { e ->
            assertTrue("id is blank", e.id.isNotBlank())
            assertTrue("languageTag is blank", e.languageTag.isNotBlank())
            assertTrue("resourceQualifier is blank", e.resourceQualifier.isNotBlank())
            assertTrue("weblateCode is blank", e.weblateCode.isNotBlank())
            assertTrue("englishName is blank", e.englishName.isNotBlank())
            assertTrue("endonym is blank", e.endonym.isNotBlank())
            assertTrue("script is blank", e.script.isNotBlank())
        }
    }
}
