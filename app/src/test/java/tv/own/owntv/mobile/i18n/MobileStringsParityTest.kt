package tv.own.owntv.mobile.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's own strings exist in all three shipped languages, with the same keys in each.
 *
 * These are the only strings this module owns — core holds the rest — and they are the only place the
 * whitelist is not enforced by the build: a key missing from `values-zh-rTW` does not fail anything,
 * it silently answers that one line in English inside a Chinese screen. Same for a file added for a
 * language the APK no longer packages.
 *
 * Source-read rather than executed: a JVM unit test has no resources to look up, and the thing worth
 * checking is the file set and the key sets, not the rendering.
 */
class MobileStringsParityTest {

    private val stringEntry = Regex("""<string name="([a-zA-Z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun read(qualifier: String): String {
        val file = File("src/main/res/$qualifier/strings_mobile.xml")
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    /** The directories the build packages, from the same list `localeFilters` is built from. */
    private val qualifiers = listOf("values", "values-zh-rCN", "values-zh-rTW")

    private val entriesByQualifier: Map<String, List<Pair<String, String>>> by lazy {
        qualifiers.associateWith { qualifier -> stringEntry.findAll(read(qualifier)).map { it.groupValues[1] to it.groupValues[2] }.toList() }
    }

    @Test
    fun `the packaged qualifiers are the whitelist`() {
        // An extra folder here means a language the APK no longer ships; a missing one means a shipped
        // language with no app text at all. `en` is the default `values/` folder, not a `values-en` one.
        assertEquals(listOf("en", "zh-rCN", "zh-rTW"), AppLocales.shippedQualifiers)
        assertEquals(qualifiers, listOf("values", "values-zh-rCN", "values-zh-rTW"))
        assertEquals(
            "every shipped qualifier has a folder",
            AppLocales.shippedQualifiers.size,
            AppLocales.shippedQualifiers.count { File("src/main/res/values${if (it == "en") "" else "-$it"}").isDirectory },
        )
    }

    @Test
    fun `every language carries exactly the same keys`() {
        val expected = entriesByQualifier.getValue("values").map { it.first }
        entriesByQualifier.forEach { (qualifier, entries) ->
            assertEquals("$qualifier key set", expected, entries.map { it.first })
        }
    }

    @Test
    fun `no key is repeated and no value is blank`() {
        entriesByQualifier.forEach { (qualifier, entries) ->
            val names = entries.map { it.first }
            assertEquals("$qualifier duplicates", names.distinct(), names)
            entries.forEach { (name, value) ->
                assertTrue("$qualifier/$name is blank", value.isNotBlank())
            }
        }
    }

    @Test
    fun `the default file is English`() {
        // `values/` is what an unpicked language falls back to, so it is the one file that must read well
        // to everyone.
        val english = entriesByQualifier.getValue("values").toMap()
        assertEquals("App lock", english["mobile_app_lock"])
        assertEquals("Unlock", english["mobile_unlock"])
    }

    @Test
    fun `placeholders match across languages`() {
        val counts = entriesByQualifier.mapValues { (_, entries) ->
            entries.toMap().filterKeys { it.contains("rename_notice") }.mapValues { (_, value) ->
                Regex("%[0-9]+[$]s").findAll(value).count()
            }
        }
        counts.forEach { (qualifier, perKey) ->
            perKey.forEach { (key, n) -> assertEquals("$qualifier/$key", 1, n) }
        }
    }
}
