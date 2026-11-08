package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.ui.stage.PlaylistMark
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.home.formatYear
import tv.own.owntv.mobile.ui.screens.home.timeLeftText
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StagePlaylistMark
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * `.chrow`: 66 dp — the number (28 wide, 13 / 600 dim), the logo (52 × 38, no plate unless its ink
 * would vanish), the provider's name without its marks (14.5 / 700), "programme · time left" (12 muted)
 * and a 3 dp progress line at 70%; then the provider tags, ↺ for catch-up, ♥ when a favourite and the
 * playlist mark. [current] is the playing channel (`.fx` + `.selbar`).
 *
 * The player's own channel sheet and Favourites draw this row; the player's left overlay has a
 * narrower one of its own ([tv.own.owntv.mobile.ui.player.OverlayChannelRow]) — a third of the screen
 * has no room for a 52 dp logo beside a name.
 */
@Composable
internal fun LiveChannelRow(
    channel: ChannelEntity,
    showNumber: Boolean,
    now: XtEpgEntry?,
    nowMs: Long,
    mark: PlaylistMark?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    current: Boolean = false,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val name = remember(channel.name) { ProviderTags.parse(channel.name) }
    StageRow(
        onClick = onClick,
        onLongClick = onLongClick,
        current = current,
        selected = current,
        height = 66.dp,
    ) {
        if (showNumber) {
            Text(
                channel.number?.takeIf { it > 0 }?.let(::formatYear).orEmpty(),
                style = stageText(13f, 600).copy(textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
                color = palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 28 dp by design; a playlist with five-digit numbers widens it rather than cutting them.
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
        Box(Modifier.size(52.dp, 38.dp).clip(RoundedCornerShape(StageRadii.Plate)), contentAlignment = Alignment.Center) {
            ChannelLogoImage(
                url = channel.displayLogoUrl,
                modifier = Modifier.fillMaxSize(),
                plateOn = palette.page,
                fallback = { StageIcon(MobileIcons.LiveTv, 19.dp, palette.dim) },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name.name, style = stageText(14.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                val left = timeLeftText(((now.stopMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000)
                Text(
                    now.title + stringResource(R.string.content_epg_bits_separator) + left,
                    style = stageText(12f, 400),
                    color = if (current) palette.body else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                val span = (now.stopMs - now.startMs).coerceAtLeast(1)
                StageProgress(
                    (nowMs - now.startMs).toFloat() / span,
                    Modifier.padding(top = 6.dp).fillMaxWidth(0.7f),
                    thin = true,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            name.tags.forEach { StageTag(it) }
            if (channel.catchup) StageIcon(MobileIcons.Catchup, 14.dp, palette.dim)
            if (isFavorite) StageIcon(MobileIcons.Favorite, 14.dp, accent)
            mark?.let { StagePlaylistMark(it) }
        }
    }
}
