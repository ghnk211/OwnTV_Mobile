package tv.own.owntv.mobile.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastRouteButton
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.theme.LocalMobileMotion
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.player.LiveProgramme
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackEngine
import java.text.NumberFormat

/** The pickers the tool bar opens. Each one is a sheet; each one also has a gesture. */
enum class PlayerSheet {
    VOLUME, BRIGHTNESS, SUBTITLES, SUBTITLE_SEARCH, AUDIO, ASPECT, QUALITY, SPEED, INFO, CHANNELS, CATCHUP, SLEEP_TIMER,
}

/**
 * What is drawn over the picture, while the picture is being watched.
 *
 * Only the top dock is left. The transport row that used to sit in the middle of the screen — play /
 * pause between skip-back and skip-forward — and the whole bottom half (the seek bar, the live bar
 * and the tool bar with its volume, subtitles, speed, aspect and info buttons) are gone: on this
 * screen the two panels are the interface, the left one for what else there is to watch and the
 * right one, on a long press, for the settings. The gestures still work — volume and brightness on
 * the outer thirds, a horizontal drag to scrub, a double tap to skip.
 *
 * The removed composables are kept verbatim in `docs/backup/PlayerControls.kt.bak`. The signature
 * below was deliberately left as it was, so restoring one of them needs no change in [PlayerScreen]:
 * every callback they used is still passed in.
 */
@Composable
fun PlayerControls(
    player: OwnTVPlayer,
    /**
     * The engine actually holding the stream, which on live is now either one.
     *
     * Everything the bar READS comes from here; `player` stays only for the two things that are mpv's
     * alone. Without this the HUD read a stopped mpv while ExoPlayer played: the title line said MPV
     * beside a button saying EXO, and the play/pause glyph showed "paused" over a moving picture.
     */
    engine: PlaybackEngine,
    title: String,
    subtitle: String?,
    logoUrl: String?,
    visible: Boolean,
    isLive: Boolean,
    // Held as States, not values: both tick about once a second while an archive plays, and only
    // the clock, the Now/Next card and the live bar read them — values here would recompose this
    // whole column on every tick.
    offsetSec: State<Int?>,
    archiveWindowSec: Int,
    /** Now and Next for the channel playing, or null when its guide has nothing. */
    epg: EpgNowNext?,
    /** The wall-clock instant on screen while an archive plays; null at the live edge. */
    watchingWallMs: State<Long?>,
    /** The channel's guide window, as the live timeline's boundary ticks. */
    timelineProgrammes: List<LiveProgramme>,
    /** The provider's own number for the channel, or null when the user has numbers turned off. */
    channelNumber: Int?,
    /**
     * Whether live is playing on ExoPlayer right now. Ignored off live.
     *
     * The engine button used to be hidden on live, and the comment beside it said why: the phone had
     * only mpv, so there was nothing to swap to. Now there is.
     */
    liveOnExo: Boolean = false,
    /**
     * Flip the live channel between ExoPlayer and mpv, remembering the choice for that channel —
     * the television's "compatibility mode". Null hides the button, which is what a replay, a rewind
     * and a protected channel all want.
     */
    onToggleLiveEngine: (() -> Unit)? = null,
    onBack: () -> Unit,
    onGoLive: () -> Unit,
    onScrubLive: (deltaSec: Int) -> Unit,
    /** The saved copy's holes, for the live bar. */
    liveGaps: () -> List<LongRange> = { emptyList() },
    /** One Live-rewind-step back (false) or forward (true); null on a channel that cannot be rewound. */
    onSkipLive: ((forward: Boolean) -> Unit)? = null,
    onOpenSheet: (PlayerSheet) -> Unit,
    /** Shrink into the app's own mini player, still playing. */
    onMini: () -> Unit,
    audioOnly: Boolean,
    onAudioOnly: () -> Unit,
    /** Whether what is playing is one of this profile's favourites. */
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    /** Opens "Go back to…", or null when this channel's provider keeps no archive. */
    onCatchup: (() -> Unit)?,
    /** Back to the channel watched before this one; null hides the button. */
    onPreviousChannel: (() -> Unit)? = null,
    /**
     * Step to the previous/next channel of the same category, wrapping at both ends; null
     * hides the button. Distinct from [onPreviousChannel], which returns to the channel
     * watched before this one rather than moving within the list.
     */
    onChannelPrevious: (() -> Unit)? = null,
    onChannelNext: (() -> Unit)? = null,
    // Live only, and only once Multiview is switched on in Settings. Null hides the button.
    onMultiview: (() -> Unit)? = null,
    /** Files a diagnostic report about the stream on screen. */
    onReport: () -> Unit,
    /** Flashes a line over the picture — what the engine toggle uses to name what it switched to. */
    onToast: (String) -> Unit,
    /** The screen-wide scrub gesture's running total, in media milliseconds, while it is happening. */
    gestureScrubMs: Long?,
    /** Whether the Info sheet is open — Report is offered only while it is. */
    infoOpen: Boolean = false,
    // Live only, and only once "Record what I'm watching" is switched on. Null hides it.
    onRecordThis: (() -> Unit)? = null,
    recordingThis: Boolean = false,
    /** Live only: the channel's other versions (Sources); null hides the button. */
    onSources: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val showClock by remember { derivedStateOf { isLive || watchingWallMs.value != null } }
    // The app's own effects spring, not Material's default: with animations off it snaps, so the
    // chrome is simply there or not there.
    val fade = LocalMobileMotion.current.fast<Float>()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fade),
        exit = fadeOut(fade),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(TopScrim)
                    .systemBarsPadding()
                    .padding(horizontal = MobileDimens.GapSmall, vertical = MobileDimens.GapTiny),
            ) {
                PlayerDock {
                    TopRow(
                        player = engine,
                        title = title,
                        subtitle = subtitle,
                        logoUrl = logoUrl,
                        channelNumber = channelNumber,
                        showClock = showClock,
                        watchingWallMs = watchingWallMs,
                        onBack = onBack,
                    )
                    // Under the identity rather than beside it: the guide line is the longest text on
                    // the dock, and a phone has no width to spare on the row the title is already in.
                    MobileNowNextCard(
                        epg = epg,
                        atMs = watchingWallMs,
                        modifier = Modifier.padding(top = MobileDimens.GapTiny),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopRow(
    player: PlaybackEngine,
    title: String,
    subtitle: String?,
    logoUrl: String?,
    channelNumber: Int?,
    showClock: Boolean,
    watchingWallMs: State<Long?>,
    onBack: () -> Unit,
) {
    val engine by player.engineChip.collectAsStateWithLifecycle()
    val resolution by player.videoRes.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(MobileIcons.ArrowBack, stringResource(R.string.common_back), tint = Color.White)
        }
        ChannelLogo(logoUrl = logoUrl, title = title, number = channelNumber)
        Column(Modifier.weight(1f).padding(start = MobileDimens.GapSmall)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(subtitle, engine, resolution)
                .joinToString(stringResource(R.string.player_metadata_separator))
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnVideo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showClock) {
            MobilePlayerClock(
                watchingMs = watchingWallMs,
                modifier = Modifier.padding(horizontal = MobileDimens.GapTiny),
            )
        }
        // Always light: this one sits over the picture, and the picture is never a light surface.
        // It shows itself only when a receiver is actually within reach — the SDK's own behaviour,
        // and the reason the bar does not carry a permanently dead button.
        CastRouteButton(light = true)
    }
}

/** "Normal" at 1x, "1.5x" otherwise — the same wording the television uses. */
@Composable
internal fun formatSpeed(speed: Double): String {
    if (speed == 1.0) return stringResource(R.string.player_speed_normal_short)
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(speed, locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 2
        }.format(speed)
    }
    return stringResource(R.string.player_speed, number)
}

/** 0:42 / 23:45 / 1:23:45 — the app's one duration format, the same one the television uses. */
@Composable
fun formatTimestamp(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        stringResource(R.string.common_timestamp_hours, h, m, s)
    } else {
        stringResource(R.string.common_timestamp_minutes, m, s)
    }
}
