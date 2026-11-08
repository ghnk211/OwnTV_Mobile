package tv.own.owntv.mobile.ui.nav

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.mobile.R

/**
 * Where the phone can navigate to.
 *
 * [sections] is what ties a destination to core's visibility rule: a destination shows only when at
 * least one of its sections is in `NavVisibility.visibleSections()`, so a channels-only playlist loses
 * Live TV in both apps at once. VOD has no section yet — its page is still a placeholder — so it is
 * always shown.
 *
 * MORE has no section: it is always reachable — from the avatar on a phone, from the rail on a tablet —
 * because Settings, Profiles and Downloads live behind it and a user must never be able to hide their way
 * out of the app's own settings.
 */
enum class MobileDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val sections: Set<MainSection>,
    /** The bottom bar's word, a quarter of the screen wide; the page title keeps [labelRes]. */
    @param:StringRes val barLabelRes: Int = labelRes,
) {
    HOME("home", R.string.common_nav_home, MobileIcons.Home, setOf(MainSection.HOME)),
    LIVE("live", R.string.common_nav_live_tv, MobileIcons.LiveTv, setOf(MainSection.LIVE_TV)),
    GUIDE("guide", R.string.common_nav_guide, MobileIcons.Guide, setOf(MainSection.EPG), R.string.common_nav_guide_short),
    MOVIES("movies", R.string.common_nav_movies, MobileIcons.Movie, setOf(MainSection.MOVIES)),
    SERIES("series", R.string.common_nav_series, MobileIcons.Series, setOf(MainSection.SERIES)),
    /** On demand: movies and series in one place. Its page is a placeholder for now. */
    VOD("vod", R.string.mobile_nav_vod, MobileIcons.Movie, emptySet()),
    MORE("more", R.string.common_nav_more, MobileIcons.MoreHoriz, emptySet()),
    DOWNLOADS(
        "downloads",
        R.string.common_nav_downloads,
        MobileIcons.Download,
        setOf(MainSection.DOWNLOADS),
    ),
    SETTINGS("settings", R.string.common_nav_settings, MobileIcons.Settings, emptySet());

    companion object {
        /**
         * The bottom bar, in order: the three places the phone offers. More is not on it: the avatar at
         * the top right opens it, as the TV rail ends in the profile.
         */
        val bottomBar: List<MobileDestination> = listOf(HOME, LIVE, VOD)

        /**
         * The rail (`railH`), in order: the places, Downloads, then More as the last
         * item. Settings is not on it — it is what More is for, on every width.
         */
        val rail: List<MobileDestination> =
            listOf(HOME, LIVE, VOD, DOWNLOADS, MORE)

        /**
         * The place selected for [route] when its own is not shown: a film or a show opened under Movies
         * or Series keeps VOD lit on the bar, so turning the phone keeps a place.
         */
        fun List<MobileDestination>.standingFor(route: String?): MobileDestination? {
            val place = route?.substringBefore('/') ?: return null
            return when (place) {
                MOVIES.route, SERIES.route -> firstOrNull { it == VOD }
                else -> null
            }
        }

        /**
         * Filters a bar or rail to what the active playlist actually offers. A destination with no
         * sections is always kept; the rest survive if core says any of their sections is visible.
         */
        fun List<MobileDestination>.visible(sections: Set<MainSection>): List<MobileDestination> =
            filter { it.sections.isEmpty() || it.sections.any { section -> section in sections } }
    }
}
