package tv.own.owntv.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.i18n.SupportedLocales

/**
 * The app language picked in Settings, applied to every screen as soon as it is picked — the TV app's
 * `LocalizedContent`, the same mechanics. `stringResource` reads `LocalResources`, so all four locale
 * locals are provided; a change of script (Latin → Bengali, say) recreates the Activity once, because
 * font shaping follows the Activity's own context, which `attachBaseContext` then builds in the new
 * language.
 */
@Composable
fun LocalizedContent(localeStore: LocaleStore, content: @Composable () -> Unit) {
    val hostContext = LocalContext.current
    // Both tags are collapsed onto the app's whitelist before they are compared or wrapped: a stored
    // tag the APK has no strings for would otherwise reach `scriptOf` and the recreate check as itself,
    // and a wrapper built from it answers with the default resources while the picker still names it.
    val rawTag by localeStore.currentTag.collectAsStateWithLifecycle()
    val tag = tv.own.owntv.mobile.i18n.AppLocales.normalize(rawTag)
    val startTag = remember { tv.own.owntv.mobile.i18n.AppLocales.normalize(localeStore.readBlocking()) }
    LaunchedEffect(tag) {
        if (tag != startTag && scriptOf(tag) != scriptOf(startTag)) findActivity(hostContext)?.recreate()
    }
    // Keyed on the host configuration too: the Activity handles rotation itself, and a wrapped copy
    // taken before it would keep the old width.
    val hostConfig = LocalConfiguration.current
    val wrapped = remember(hostContext, tag, hostConfig) { AppLocale.wrapForCompose(hostContext, tag) }
    val resources = wrapped.resources
    val direction = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalConfiguration provides resources.configuration,
        LocalContext provides wrapped,
        LocalResources provides resources,
        LocalLayoutDirection provides direction,
        content = content,
    )
}

private fun findActivity(context: Context?): Activity? {
    var c = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The tag's script; "" is the phone's own language. Likely subtags fill in a bare "de" or "bn". */
private fun scriptOf(tag: String): String {
    val t = tag.trim().ifEmpty {
        android.content.res.Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
    }
    return SupportedLocales.scriptForTag(t) ?: runCatching {
        android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLanguageTag(t)).script
    }.getOrDefault("")
}
