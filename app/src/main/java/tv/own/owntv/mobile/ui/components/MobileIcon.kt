package tv.own.owntv.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import tv.own.owntv.core.ui.stage.StageGlyph

/**
 * The brand glyph set, drawn on the same normalised 24×24 grid as the television's `OwnTVIcon` so
 * that the two apps look like one product.
 *
 * These are `ImageVector`s rather than the television's `Canvas` composable, deliberately. Every call
 * site here already passes an `ImageVector` to Material's `Icon`, which is what applies the tint, the
 * content description and the RTL mirroring — a second drawing composable would have meant touching
 * ~150 call sites and re-implementing all three. `autoMirror` in particular is free this way, and the
 * app ships right-to-left languages.
 *
 * Where a name has a Stage glyph (core's [StageGlyph], the same paths the television draws) it is that
 * glyph; the rest are drawn here on the same grid at the same 1.8 stroke until their screens move to Stage.
 */
object MobileIcons {

    // ---- Navigation and structure -------------------------------------------------------------

    val ArrowBack: ImageVector by lazy { stage(StageGlyph.CHEVRON_LEFT, mirror = true) }
    val KeyboardArrowRight: ImageVector by lazy { stage(StageGlyph.CHEVRON, mirror = true) }
    val KeyboardArrowUp: ImageVector by lazy { stage(StageGlyph.CHEVRON_UP) }
    val KeyboardArrowDown: ImageVector by lazy { stage(StageGlyph.CHEVRON_DOWN) }
    val KeyboardDoubleArrowUp: ImageVector by lazy {
        glyph() { line(5f, 12f, 12f, 5f, 19f, 12f); line(5f, 20f, 12f, 13f, 19f, 20f) }
    }
    val KeyboardDoubleArrowDown: ImageVector by lazy {
        glyph() { line(5f, 4f, 12f, 11f, 19f, 4f); line(5f, 12f, 12f, 19f, 19f, 12f) }
    }
    val Close: ImageVector by lazy { stage(StageGlyph.CLOSE) }
    val MoreHoriz: ImageVector by lazy { stage(StageGlyph.MORE) }
    val Home: ImageVector by lazy { stage(StageGlyph.HOME) }
    val OpenInNew: ImageVector by lazy { stage(StageGlyph.EXTERNAL, mirror = true) }
    val OpenInFull: ImageVector by lazy { stage(StageGlyph.EXPAND) }

    // ---- Content ------------------------------------------------------------------------------

    val LiveTv: ImageVector by lazy { stage(StageGlyph.LIVE_TV) }
    val Tv: ImageVector by lazy {
        glyph() { box(3f, 4f, 21f, 17f, 2.5f); line(8f, 21f, 16f, 21f); line(12f, 17f, 12f, 21f) }
    }
    val Movie: ImageVector by lazy { stage(StageGlyph.MOVIES) }
    val VideoLibrary: ImageVector by lazy {
        glyph() {
            box(7f, 3f, 21f, 15f, 2f)
            solid(11.5f, 6f, 16f, 9f, 11.5f, 12f)
            line(3f, 7f, 3f, 21f, 17f, 21f)
        }
    }
    val Image: ImageVector by lazy {
        glyph() {
            box(3f, 5f, 21f, 19f, 2f)
            dot(8f, 10f, 1.2f)
            line(4.5f, 18f, 9.5f, 12f, 13f, 15f, 16f, 11f, 19.5f, 18f)
        }
    }
    val Folder: ImageVector by lazy { stage(StageGlyph.FOLDER) }
    /** Playlists (the top cluster's playlist pill, the playlist sheet's All playlists). */
    val Layers: ImageVector by lazy { stage(StageGlyph.LAYERS) }
    /** The TV Guide place (`guide`). */
    val Guide: ImageVector by lazy { stage(StageGlyph.EPG) }
    /** The order tool (`sort`). */
    val Sort: ImageVector by lazy { stage(StageGlyph.SORT) }
    val Bell: ImageVector by lazy { stage(StageGlyph.BELL) }
    val Now: ImageVector by lazy { stage(StageGlyph.NOW) }
    /** Record (`rec`), Multiview (`mv`) and Move (`move`) in the channel menu. */
    val Record: ImageVector by lazy { stage(StageGlyph.REC) }
    val Warning: ImageVector by lazy { stage(StageGlyph.WARNING) }
    val Multiview: ImageVector by lazy { stage(StageGlyph.MULTIVIEW) }
    val Move: ImageVector by lazy { stage(StageGlyph.MOVE) }
    /** Home's trending eyebrow (`trend`). */
    val Trend: ImageVector by lazy { stage(StageGlyph.TREND) }
    /** The Series place (`series`). */
    val Series: ImageVector by lazy { stage(StageGlyph.SERIES) }
    /** Four tiles (`more`): Show counts. */
    val Tiles: ImageVector by lazy { stage(StageGlyph.TILES) }

    // ---- Actions ------------------------------------------------------------------------------

    val Add: ImageVector by lazy { stage(StageGlyph.PLUS) }
    /** Four tiles — Multiview, and the same idea as the television's LIST_GRID mark. */
    val GridView: ImageVector by lazy { stage(StageGlyph.GRID) }
    val Check: ImageVector by lazy { stage(StageGlyph.CHECK) }
    val CheckCircle: ImageVector by lazy {
        glyph() { ring(12f, 12f, 9f); line(7.5f, 12.3f, 10.7f, 15.5f, 16.5f, 8.8f) }
    }
    val Delete: ImageVector by lazy { stage(StageGlyph.TRASH) }
    val Edit: ImageVector by lazy { stage(StageGlyph.PENCIL) }
    val Save: ImageVector by lazy {
        glyph() {
            box(3.5f, 3.5f, 20.5f, 20.5f, 2f)
            bar(8f, 3.5f, 15f, 9f)
            box(7f, 13f, 17f, 20.5f, 1f)
        }
    }
    val Search: ImageVector by lazy { stage(StageGlyph.SEARCH) }
    val Refresh: ImageVector by lazy { stage(StageGlyph.REFRESH) }
    val Sync: ImageVector by lazy {
        glyph() {
            arc(12f, 12f, 8.5f, -160f, 130f)
            line(2f, 8.5f, 3.5f, 3.6f, 8.4f, 5.1f)
            arc(12f, 12f, 8.5f, 20f, 130f)
            line(22f, 15.5f, 20.5f, 20.4f, 15.6f, 18.9f)
        }
    }
    val Restore: ImageVector by lazy {
        glyph() {
            arc(12f, 12f, 8.5f, 180f, -314f)
            line(3.2f, 3.6f, 3.2f, 8.6f, 8.2f, 8.6f)
            line(12f, 7.5f, 12f, 12f, 15.5f, 14f)
        }
    }
    val History: ImageVector by lazy { stage(StageGlyph.HISTORY) }
    /**
     * Catch-up: a television with a replay loop inside it, and a single antenna.
     *
     * Drawn to match the television app's `OwnTVIcon.CATCHUP`. It used to be [History] here and
     * a screen-with-a-loop there — the same function wearing two faces, which is one of the four
     * mismatches Feature H exists to remove. One antenna rather than a V: it says "television" for
     * the cost of a single line and stays clear of the loop's arrowhead.
     */
    val Catchup: ImageVector by lazy { stage(StageGlyph.REWIND) }

    /**
     * Over-ear headphones — a headband arc and two earcups.
     *
     * Drawn to match the television's `OwnTVIcon.HEADPHONES`. It was a [MusicNote] here, which
     * says "audio" but not "sound only, no picture", and the television has never used one.
     */
    val Headphones: ImageVector by lazy { stage(StageGlyph.HEADPHONES) }

    /**
     * Share: three nodes and the two lines between them.
     *
     * Drawn to match the television's `OwnTVIcon.SHARE`. Reporting a stream was a [BugReport]
     * here and this there; the television's is the reference, and "share this readout" is nearer to
     * what the button does than "file a bug" is.
     */
    val Share: ImageVector by lazy {
        glyph() {
            ring(6f, 12f, 2.4f)
            ring(18f, 6f, 2.4f)
            ring(18f, 18f, 2.4f)
            line(8f, 11f, 16f, 7f)
            line(8f, 13f, 16f, 17f)
        }
    }

    val Schedule: ImageVector by lazy { stage(StageGlyph.CLOCK) }
    val CalendarMonth: ImageVector by lazy { stage(StageGlyph.CALENDAR) }

    // ---- Lists --------------------------------------------------------------------------------

    val PlaylistPlay: ImageVector by lazy {
        glyph() {
            line(4f, 7f, 16f, 7f); line(4f, 12f, 16f, 12f); line(4f, 17f, 11f, 17f)
            solid(15f, 14f, 21f, 17f, 15f, 20f)
        }
    }
    val FormatListBulleted: ImageVector by lazy { stage(StageGlyph.LIST) }
    val SwapVert: ImageVector by lazy {
        glyph() {
            line(8f, 20f, 8f, 4f); line(4.8f, 7.2f, 8f, 4f, 11.2f, 7.2f)
            line(16f, 4f, 16f, 20f); line(12.8f, 16.8f, 16f, 20f, 19.2f, 16.8f)
        }
    }
    val SwapHoriz: ImageVector by lazy {
        glyph() {
            line(4f, 9f, 18f, 9f); line(15f, 6.5f, 18f, 9f, 15f, 11.5f)
            line(20f, 15f, 6f, 15f); line(9f, 12.5f, 6f, 15f, 9f, 17.5f)
        }
    }

    // ---- Marks --------------------------------------------------------------------------------

    val Star: ImageVector by lazy { stage(StageGlyph.STAR, filled = true) }
    val StarBorder: ImageVector by lazy { stage(StageGlyph.STAR) }
    val Favorite: ImageVector by lazy { stage(StageGlyph.FAVORITE, filled = true) }
    val FavoriteBorder: ImageVector by lazy { stage(StageGlyph.FAVORITE) }
    val Info: ImageVector by lazy { stage(StageGlyph.INFO) }
    val BugReport: ImageVector by lazy {
        glyph() {
            box(7.5f, 8f, 16.5f, 20f, 4.5f)
            line(7.5f, 12f, 3.5f, 12f); line(16.5f, 12f, 20.5f, 12f)
            line(7.5f, 17f, 4f, 19f); line(16.5f, 17f, 20f, 19f)
            line(9.5f, 6.5f, 8f, 4f); line(14.5f, 6.5f, 16f, 4f)
        }
    }
    val Build: ImageVector by lazy {
        glyph() {
            outline {
                moveTo(14.4f, 3.2f); lineTo(17.6f, 6.4f); lineTo(20.8f, 3.2f)
                arcTo(6.5f, 6.5f, 0f, true, true, 12.4f, 12.6f)
                lineTo(4.6f, 20.4f); lineTo(2.6f, 18.4f); lineTo(10.4f, 10.6f)
                arcTo(6.5f, 6.5f, 0f, true, true, 14.4f, 3.2f); close()
            }
        }
    }
    val Person: ImageVector by lazy { stage(StageGlyph.PERSON) }
    val Phone: ImageVector by lazy { stage(StageGlyph.PHONE) }
    // No Stage glyph: the profile chooser's 🔒 PIN tag — a body and its shackle.
    val Lock: ImageVector by lazy {
        glyph() {
            box(5f, 11f, 19f, 20.5f, 2f)
            line(8f, 11f, 8f, 8f)
            line(16f, 11f, 16f, 8f)
            arc(12f, 8f, 4f, 180f, 180f)
        }
    }

    // ---- Player -------------------------------------------------------------------------------

    val PlayArrow: ImageVector by lazy { stage(StageGlyph.PLAY, filled = true) }
    /** The outlined play of a sheet's menu items (`mi('play')`). */
    val PlayOutline: ImageVector by lazy { stage(StageGlyph.PLAY) }
    val PlayCircle: ImageVector by lazy { stage(StageGlyph.PLAY_CIRCLE) }
    val Pause: ImageVector by lazy { stage(StageGlyph.PAUSE) }
    val FastForward: ImageVector by lazy { stage(StageGlyph.FORWARD) }
    val FastRewind: ImageVector by lazy { stage(StageGlyph.REWIND) }
    val SkipNext: ImageVector by lazy { stage(StageGlyph.SKIP_NEXT) }
    val SkipPrevious: ImageVector by lazy { stage(StageGlyph.SKIP_PREVIOUS) }
    val Download: ImageVector by lazy { stage(StageGlyph.DOWNLOADS) }
    val Subtitles: ImageVector by lazy { stage(StageGlyph.SUBTITLE) }
    val ClosedCaption: ImageVector by lazy {
        glyph() {
            box(3f, 5f, 21f, 19f, 2.5f)
            arc(9.2f, 12f, 2.7f, -35f, -290f)
            arc(15.6f, 12f, 2.7f, -35f, -290f)
        }
    }
    val VolumeUp: ImageVector by lazy { stage(StageGlyph.VOLUME_HIGH) }
    val VolumeOff: ImageVector by lazy {
        glyph() { speaker(); line(14f, 9f, 20f, 15f); line(20f, 9f, 14f, 15f) }
    }
    val MusicNote: ImageVector by lazy { glyph() { note() } }
    val Audiotrack: ImageVector by lazy { glyph() { note() } }
    val AspectRatio: ImageVector by lazy {
        glyph() {
            box(3f, 5f, 21f, 19f, 2.5f)
            line(7f, 11f, 7f, 9f, 9f, 9f)
            line(17f, 13f, 17f, 15f, 15f, 15f)
        }
    }
    val PictureInPictureAlt: ImageVector by lazy {
        glyph() { box(3f, 5f, 21f, 19f, 2.5f); bar(12.5f, 12f, 19f, 17f) }
    }
    // No Cast glyph: the cast button is the platform's own MediaRouteButton, which the Cast SDK
    // drives — see CastRouteButton.kt. Drawing our own would leave it not knowing when a receiver
    // is in range, which is the only interesting thing about that button.

    // ---- Settings -----------------------------------------------------------------------------

    val Settings: ImageVector by lazy { stage(StageGlyph.SETTINGS) }
    val Tune: ImageVector by lazy {
        glyph() {
            line(4f, 8f, 20f, 8f); line(4f, 16f, 20f, 16f)
            dot(9f, 8f, 2.6f); dot(15f, 16f, 2.6f)
        }
    }
    val Palette: ImageVector by lazy { stage(StageGlyph.PALETTE) }
    val Title: ImageVector by lazy {
        glyph() { line(4f, 6f, 20f, 6f); line(12f, 6f, 12f, 19f) }
    }
    val Storage: ImageVector by lazy {
        glyph() {
            box(3f, 5f, 21f, 9.5f, 1.5f); box(3f, 14.5f, 21f, 19f, 1.5f)
            dot(6.5f, 7.25f, 1.1f); dot(6.5f, 16.75f, 1.1f)
        }
    }
    val SdStorage: ImageVector by lazy {
        glyph() {
            outline {
                moveTo(7f, 21f); lineTo(7f, 8f); lineTo(12f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); close()
            }
            line(10f, 6.5f, 10f, 9.5f); line(13f, 6.5f, 13f, 9.5f); line(16f, 6.5f, 16f, 9.5f)
        }
    }
    val Wifi: ImageVector by lazy {
        glyph() {
            arc(12f, 19f, 10f, -150f, 120f)
            arc(12f, 19f, 6.3f, -145f, 110f)
            dot(12f, 18.5f, 1.7f)
        }
    }
    val Visibility: ImageVector by lazy { stage(StageGlyph.EYE) }
    val VisibilityOff: ImageVector by lazy { stage(StageGlyph.EYE_OFF) }

    // ---- Atmosphere ---------------------------------------------------------------------------

    val WbSunny: ImageVector by lazy { stage(StageGlyph.SUN) }
    val BrightnessMedium: ImageVector by lazy {
        glyph() {
            ring(12f, 12f, 4.6f); rays()
            fill { moveTo(12f, 7.4f); arcTo(4.6f, 4.6f, 0f, false, true, 12f, 16.6f); close() }
        }
    }
    val Bedtime: ImageVector by lazy { stage(StageGlyph.MOON) }
    /** A globe: the language picker. Meridians drawn as polylines — the grid has no ellipse. */
    val Translate: ImageVector by lazy { stage(StageGlyph.LANGUAGE) }
    val AutoAwesome: ImageVector by lazy { stage(StageGlyph.SPARKLE) }
}

// -------------------------------------------------------------------------------------------------
// The grid. Everything above is written in 24×24 units; everything below turns that into a vector.
// -------------------------------------------------------------------------------------------------

/** The Stage stroke (`.ph .ico`, 1.8 on the 24 grid), for its glyphs and the hand-drawn rest alike. */
private const val W = 1.8f
private val Ink = SolidColor(Color.Black)

private class Grid(private val builder: ImageVector.Builder) {

    /** A polyline through the given `x, y` pairs. */
    fun line(vararg v: Float) = outline {
        moveTo(v[0], v[1])
        for (i in 2 until v.size step 2) lineTo(v[i], v[i + 1])
    }

    /** A closed, filled polygon through the given `x, y` pairs. */
    fun solid(vararg v: Float) = fill {
        moveTo(v[0], v[1])
        for (i in 2 until v.size step 2) lineTo(v[i], v[i + 1])
        close()
    }

    fun bar(l: Float, t: Float, r: Float, b: Float) = solid(l, t, r, t, r, b, l, b)

    fun ring(cx: Float, cy: Float, r: Float) = outline { circle(cx, cy, r) }

    fun dot(cx: Float, cy: Float, r: Float) = fill { circle(cx, cy, r) }

    fun box(l: Float, t: Float, r: Float, b: Float, rad: Float) = outline {
        moveTo(l + rad, t); lineTo(r - rad, t); arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad); arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b); arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad); arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }

    /** An open arc, in degrees clockwise from three o'clock — the same convention as `drawArc`. */
    fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) = outline {
        fun rad(d: Float) = (d * Math.PI / 180.0).toFloat()
        moveTo(cx + r * cos(rad(startDeg)), cy + r * sin(rad(startDeg)))
        val end = startDeg + sweepDeg
        // A single arc segment cannot cross 360°, and anything past a half turn needs the long-way
        // flag; splitting at the halfway point keeps both facts true without a special case.
        val mid = startDeg + sweepDeg / 2f
        arcTo(r, r, 0f, abs(sweepDeg / 2f) > 180f, sweepDeg > 0f, cx + r * cos(rad(mid)), cy + r * sin(rad(mid)))
        arcTo(r, r, 0f, abs(sweepDeg / 2f) > 180f, sweepDeg > 0f, cx + r * cos(rad(end)), cy + r * sin(rad(end)))
    }

    /** The speaker body the two volume glyphs share. */
    fun speaker() = solid(3f, 9f, 7f, 9f, 11f, 5f, 11f, 19f, 7f, 15f, 3f, 15f)

    /** A quaver — the audio track, told apart from the speaker at a glance. */
    fun note() {
        dot(8.5f, 17.5f, 3f)
        line(11.5f, 17.5f, 11.5f, 5f)
        line(11.5f, 5f, 16.5f, 7f)
        line(11.5f, 8.5f, 16.5f, 10.5f)
    }

    /** Eight rays around a 4.6 disc. */
    fun rays() {
        for (i in 0 until 8) {
            val a = (Math.PI / 4 * i).toFloat()
            line(12f + 6.4f * cos(a), 12f + 6.4f * sin(a), 12f + 8.6f * cos(a), 12f + 8.6f * sin(a))
        }
    }

    fun outline(build: PathBuilder.() -> Unit) = builder.path(
        stroke = Ink,
        strokeLineWidth = W,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    )

    fun fill(build: PathBuilder.() -> Unit) = builder.path(fill = Ink, pathBuilder = build)
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

/** A Stage glyph from core: its path stroked like the rest, or [filled] (play, star, heart). */
private fun stage(glyph: StageGlyph, filled: Boolean = false, mirror: Boolean = false): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = mirror,
    ).addPath(
        pathData = addPathNodes(glyph.path),
        fill = if (filled) Ink else null,
        stroke = if (filled) null else Ink,
        strokeLineWidth = W,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/**
 * One glyph. The vector carries no name — a per-icon string here would be eighty-one literals for the
 * i18n gate to police, and nothing reads it.
 */
private fun glyph(mirror: Boolean = false, body: Grid.() -> Unit): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = mirror,
    ).apply { Grid(this).body() }.build()
