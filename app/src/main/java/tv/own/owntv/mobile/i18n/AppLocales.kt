package tv.own.owntv.mobile.i18n

import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.mobile.BuildConfig

/**
 * The phone's language whitelist: English, Simplified Chinese, Traditional Chinese.
 *
 * core still knows 45 locales and still ships their strings; this is the app's decision about what a
 * phone user may be given. Three layers have to agree, and a fourth exists only in debug builds:
 * [shippedQualifiers] is what `localeFilters` packages into the APK, [rows] is what the pickers list,
 * [normalize] is what a persisted tag is collapsed to before it is read back. Changing one without the
 * others is how a picker offers a language whose strings were never packaged.
 */
object AppLocales {
    /** Runtime tags this app persists, in picker order. */
    val shippedTags = listOf("en-US", "zh-CN", "zh-TW")

    /** Android resource qualifiers, one per [shippedTags] entry, in the same order. */
    val shippedQualifiers = listOf("en", "zh-rCN", "zh-rTW")

    /**
     * The picker rows, taken from core's catalogue rather than rebuilt here, so `endonym`, `script`
     * and the rest stay core's answer and cannot drift from the strings it ships.
     */
    val rows: List<SupportedLocale> = shippedTags.mapNotNull { tag -> SupportedLocales.all.firstOrNull { it.languageTag == tag } }

    /** Pseudolocales stay testable; they are packaged only in debug, so only debug may persist them. */
    private val debugPseudoTags = if (BuildConfig.DEBUG) setOf("en-XA", "ar-XB") else emptySet()

    /**
     * Collapse any persisted tag onto the whitelist.
     *
     * `""` keeps meaning "follow the phone". Every English variant becomes `en-US` — `en-GB` is not
     * packaged, so it would otherwise select a locale with no resources behind it. Anything else falls
     * back to `""`, which is the honest answer: the APK has no strings for it, so the phone's own
     * language (or English) is what the user gets.
     */
    fun normalize(tag: String): String {
        val t = tag.trim()
        if (t.isEmpty()) return ""
        // Case is matched loosely and answered canonically: a tag can arrive from a restore or an
        // editing mistake, and the picker only ever writes the forms below.
        shippedTags.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        debugPseudoTags.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        if (t.equals("en", ignoreCase = true) || t.startsWith("en-", ignoreCase = true)) return "en-US"
        return ""
    }

    /** True for a tag this app may persist, including the empty "follow the system" tag. */
    fun isShipped(tag: String): Boolean {
        val t = tag.trim()
        return t.isEmpty() || t in shippedTags || debugPseudoTags.any { it.equals(t, ignoreCase = true) }
    }
}
