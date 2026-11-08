package tv.own.owntv.mobile.ui.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.profile.PROFILE_AVATAR_COUNT
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * A profile's picture, drawn rather than loaded — ten object tiles (lightning, sword, tree, car,
 * football, rocket, crown, heart, music, gamepad) on their own colour.
 *
 * Drawn because it has to be sharp at 40 dp in a settings row and at 96 dp on the gate, on every
 * screen density, and because the television numbers these tiles the same way: a profile made on
 * the TV and synced here shows the same picture rather than a coloured blank.
 *
 * `avatarId` -1 is "no picture" and falls back to a plain silhouette.
 */
@Composable
fun ProfileAvatar(avatarId: Int, modifier: Modifier = Modifier, imagePath: String = "") {
    if (avatarId < 0 && imagePath.isBlank()) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(LocalStagePalette.current.cardSolid),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MobileIcons.Person,
                contentDescription = null,
                tint = LocalStagePalette.current.muted,
                modifier = Modifier.fillMaxSize(SILHOUETTE_FRACTION),
            )
        }
        return
    }
    val i = ((avatarId % PROFILE_AVATAR_COUNT) + PROFILE_AVATAR_COUNT) % PROFILE_AVATAR_COUNT
    Box(modifier = modifier) {
        // The drawn tile stays underneath the picture rather than being replaced by it: it costs a
        // few Canvas paths, it fills the moment while the file is read, and it is what shows if the
        // picture has gone — no file-existence check on every composition, and never a blank hole.
        if (avatarId >= 0) {
            Canvas(modifier = Modifier.matchParentSize()) { drawAvatar(i) }
        }
        if (imagePath.isNotBlank()) {
            AsyncImage(
                // A content:// address is a photo just picked for a profile not created yet.
                model = if (imagePath.startsWith("content:")) imagePath else java.io.File(imagePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        }
    }
}

/** The silhouette reads as a person rather than as a filled circle at this share of the tile. */
private const val SILHOUETTE_FRACTION = 0.62f

/**
 * The face a profile has when none has been drawn or photographed: its name's initial, uppercased,
 * on the warm gradient the app has always used for this. Shared by the shell's avatar and the
 * wizard's new-profile page so the picture under the name field is the one the profile will have.
 */
@Composable
fun ProfileInitial(name: String?, diameter: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            drawRect(Brush.linearGradient(listOf(INITIAL_TOP, INITIAL_BOTTOM), Offset.Zero, Offset(size.width, size.height)))
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name?.firstOrNull()?.uppercase().orEmpty(),
            style = stageText(if (diameter == 32.dp) 14f else diameter.value * 0.41f, 800, lineHeight = 1f),
            color = Color(0xFF2A0D12),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val INITIAL_TOP = Color(0xFFFFB35C)
private val INITIAL_BOTTOM = Color(0xFFFF5F7A)

private val BG = listOf(
    Color(0xFFF59E0B), // 0 lightning — amber
    Color(0xFF64748B), // 1 sword — slate
    Color(0xFF22C55E), // 2 tree — green
    Color(0xFFEF4444), // 3 car — red
    Color(0xFF059669), // 4 football — emerald
    Color(0xFF6366F1), // 5 rocket — indigo
    Color(0xFFD97706), // 6 crown — gold
    Color(0xFFEC4899), // 7 heart — pink
    Color(0xFF8B5CF6), // 8 music — violet
    Color(0xFF06B6D4), // 9 gamepad — cyan
)

private fun DrawScope.drawAvatar(i: Int) {
    val s = size.minDimension
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    val w = Color.White
    val ink = Color(0xFF10181C)
    val bg = BG[i]

    // A lit gradient rather than one flat colour, with a soft sheen across the top and a hairline
    // rim — the same treatment the television gives them, so one profile looks like itself on both.
    drawRoundRect(
        brush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(bg.lighten(TILE_TOP_LIGHTEN), bg, bg.darken(TILE_BOTTOM_DARKEN)),
            start = Offset(0f, 0f),
            end = Offset(s, s),
        ),
        size = Size(s, s),
        cornerRadius = CornerRadius(s * 0.30f),
    )
    drawRoundRect(
        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
            endY = s * 0.55f,
        ),
        size = Size(s, s),
        cornerRadius = CornerRadius(s * 0.30f),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.14f),
        size = Size(s, s),
        cornerRadius = CornerRadius(s * 0.30f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.012f),
    )

    when (i) {
        0 -> { // Lightning bolt
            drawPath(poly(listOf(p(0.57f, 0.13f), p(0.31f, 0.55f), p(0.47f, 0.55f), p(0.42f, 0.87f), p(0.71f, 0.41f), p(0.53f, 0.41f), p(0.61f, 0.13f))), w, style = Fill)
        }
        1 -> { // Sword
            drawPath(poly(listOf(p(0.5f, 0.12f), p(0.57f, 0.22f), p(0.57f, 0.60f), p(0.43f, 0.60f), p(0.43f, 0.22f))), w, style = Fill)
            drawRoundRect(w, topLeft = p(0.33f, 0.59f), size = Size(0.34f * s, 0.06f * s), cornerRadius = CornerRadius(0.02f * s))
            drawRoundRect(w, topLeft = p(0.455f, 0.65f), size = Size(0.09f * s, 0.18f * s), cornerRadius = CornerRadius(0.02f * s))
            drawCircle(w, 0.045f * s, p(0.5f, 0.86f))
        }
        2 -> { // Tree
            drawRoundRect(w, topLeft = p(0.45f, 0.54f), size = Size(0.10f * s, 0.31f * s), cornerRadius = CornerRadius(0.02f * s))
            drawCircle(w, 0.20f * s, p(0.5f, 0.38f))
            drawCircle(w, 0.145f * s, p(0.33f, 0.47f))
            drawCircle(w, 0.145f * s, p(0.67f, 0.47f))
        }
        3 -> { // Car
            drawRoundRect(w, topLeft = p(0.32f, 0.35f), size = Size(0.36f * s, 0.18f * s), cornerRadius = CornerRadius(0.05f * s))
            drawRoundRect(w, topLeft = p(0.15f, 0.49f), size = Size(0.70f * s, 0.16f * s), cornerRadius = CornerRadius(0.07f * s))
            drawCircle(ink, 0.075f * s, p(0.34f, 0.66f)); drawCircle(w, 0.034f * s, p(0.34f, 0.66f))
            drawCircle(ink, 0.075f * s, p(0.66f, 0.66f)); drawCircle(w, 0.034f * s, p(0.66f, 0.66f))
        }
        4 -> { // Football (soccer ball)
            val c = p(0.5f, 0.5f)
            drawCircle(w, 0.30f * s, c, style = Fill)
            drawPath(pentagon(c, 0.10f * s), ink, style = Fill)
            for (k in 0 until 5) {
                val a = -Math.PI / 2 + k * 2 * Math.PI / 5
                val v = Offset(c.x + (0.10f * s * Math.cos(a)).toFloat(), c.y + (0.10f * s * Math.sin(a)).toFloat())
                val outer = Offset(c.x + (0.27f * s * Math.cos(a)).toFloat(), c.y + (0.27f * s * Math.sin(a)).toFloat())
                drawLine(ink, v, outer, strokeWidth = 0.035f * s, cap = StrokeCap.Round)
            }
        }
        5 -> { // Rocket
            drawRoundRect(w, topLeft = p(0.40f, 0.32f), size = Size(0.20f * s, 0.34f * s), cornerRadius = CornerRadius(0.10f * s))
            drawPath(poly(listOf(p(0.40f, 0.34f), p(0.50f, 0.14f), p(0.60f, 0.34f))), w, style = Fill)
            drawCircle(bg, 0.06f * s, p(0.50f, 0.41f))
            drawPath(poly(listOf(p(0.40f, 0.54f), p(0.29f, 0.68f), p(0.40f, 0.63f))), w, style = Fill)
            drawPath(poly(listOf(p(0.60f, 0.54f), p(0.71f, 0.68f), p(0.60f, 0.63f))), w, style = Fill)
            drawPath(poly(listOf(p(0.44f, 0.66f), p(0.50f, 0.84f), p(0.56f, 0.66f))), Color(0xFFFBBF24), style = Fill)
        }
        6 -> { // Crown
            drawPath(poly(listOf(p(0.26f, 0.64f), p(0.32f, 0.40f), p(0.42f, 0.56f), p(0.50f, 0.34f), p(0.58f, 0.56f), p(0.68f, 0.40f), p(0.74f, 0.64f))), w, style = Fill)
            drawRoundRect(w, topLeft = p(0.26f, 0.62f), size = Size(0.48f * s, 0.10f * s), cornerRadius = CornerRadius(0.02f * s))
        }
        7 -> { // Heart
            drawCircle(w, 0.155f * s, p(0.37f, 0.41f))
            drawCircle(w, 0.155f * s, p(0.63f, 0.41f))
            drawPath(poly(listOf(p(0.225f, 0.45f), p(0.775f, 0.45f), p(0.50f, 0.82f))), w, style = Fill)
        }
        8 -> { // Music note
            drawCircle(w, 0.085f * s, p(0.36f, 0.66f))
            drawCircle(w, 0.085f * s, p(0.64f, 0.60f))
            drawRoundRect(w, topLeft = p(0.435f, 0.30f), size = Size(0.035f * s, 0.36f * s), cornerRadius = CornerRadius(0.01f * s))
            drawRoundRect(w, topLeft = p(0.715f, 0.26f), size = Size(0.035f * s, 0.34f * s), cornerRadius = CornerRadius(0.01f * s))
            drawPath(poly(listOf(p(0.435f, 0.27f), p(0.75f, 0.23f), p(0.75f, 0.33f), p(0.435f, 0.37f))), w, style = Fill)
        }
        9 -> { // Gamepad
            drawRoundRect(w, topLeft = p(0.16f, 0.41f), size = Size(0.68f * s, 0.22f * s), cornerRadius = CornerRadius(0.11f * s))
            drawRoundRect(bg, topLeft = p(0.26f, 0.50f), size = Size(0.13f * s, 0.04f * s), cornerRadius = CornerRadius(0.01f * s))
            drawRoundRect(bg, topLeft = p(0.305f, 0.455f), size = Size(0.04f * s, 0.13f * s), cornerRadius = CornerRadius(0.01f * s))
            drawCircle(bg, 0.03f * s, p(0.64f, 0.49f))
            drawCircle(bg, 0.03f * s, p(0.71f, 0.55f))
        }
    }
}

private fun poly(pts: List<Offset>): Path = Path().apply {
    pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
    close()
}

private fun pentagon(center: Offset, r: Float): Path = Path().apply {
    for (i in 0 until 5) {
        val a = -Math.PI / 2 + i * 2 * Math.PI / 5
        val x = center.x + (r * Math.cos(a)).toFloat()
        val y = center.y + (r * Math.sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** How much lighter the top-left corner of a tile is than its base colour, and darker the bottom. */
private const val TILE_TOP_LIGHTEN = 0.22f
private const val TILE_BOTTOM_DARKEN = 0.14f

private fun Color.lighten(amount: Float) = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

private fun Color.darken(amount: Float) = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
