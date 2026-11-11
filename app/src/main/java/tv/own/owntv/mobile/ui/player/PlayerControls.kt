package tv.own.owntv.mobile.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.player.ControlCluster
import tv.own.owntv.core.player.PlayerControl
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastRouteButton
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.theme.LocalAccentOnVideo
import tv.own.owntv.mobile.ui.theme.LocalMobileMotion
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.player.LiveProgramme
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.SleepTimer
import java.text.NumberFormat

/** The pickers the tool bar opens. Each one is a sheet; each one also has a gesture. */
enum class PlayerSheet {
    VOLUME, BRIGHTNESS, SUBTITLES, SUBTITLE_SEARCH, AUDIO, ASPECT, QUALITY, SPEED, INFO, CHANNELS, CATCHUP, SLEEP_TIMER,
}

/**
 * What is drawn over the picture, while the picture is being watched: the top dock and the bottom
 * bar.
 *
 * The transport row that used to sit in the middle of the screen — play / pause between skip-back
 * and skip-forward — is gone: on this screen the two panels are the interface, the left one for what
 * else there is to watch and the right one, on a long press, for the settings. The gestures still
 * work — volume and brightness on the outer thirds, a horizontal drag to scrub, a double tap to skip.
 *
 * The bottom bar is the way to everything the gestures cannot do: the seek bar (or the live
 * timeline), and the tool bar with its volume, subtitles, speed, aspect and info buttons. Its code
 * still sits verbatim in `docs/backup/PlayerControls.kt.bak` alongside the transport row.
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
    // Derived, so the ticking archive state above moves only its readers: the edge is crossed
    // rarely, and the clock's null-ness only on entering/leaving the archive.
    val behindLive by remember { derivedStateOf { (offsetSec.value ?: 0) > 1 } }
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

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(BottomScrim)
                    .systemBarsPadding()
                    .padding(horizontal = MobileDimens.GapSmall, vertical = MobileDimens.GapTiny),
            ) {
                PlayerDock {
                    if (isLive) {
                        LiveBar(
                            offsetSec = offsetSec,
                            archiveWindowSec = archiveWindowSec,
                            programmes = timelineProgrammes,
                            onScrubLive = onScrubLive,
                            gaps = liveGaps,
                        )
                    } else {
                        SeekBar(player, gestureScrubMs)
                    }
                    ToolBar(
                        player = player,
                        engine = engine,
                        isLive = isLive,
                        goLive = if (isLive && behindLive) onGoLive else null,
                        onOpenSheet = onOpenSheet,
                        onMini = onMini,
                        audioOnly = audioOnly,
                        onAudioOnly = onAudioOnly,
                        favorite = favorite,
                        onToggleFavorite = onToggleFavorite,
                        onCatchup = onCatchup,
                        onPreviousChannel = onPreviousChannel,
                        onMultiview = onMultiview,
                        onReport = onReport,
                        onToast = onToast,
                        infoOpen = infoOpen,
                        onRecordThis = onRecordThis,
                        recordingThis = recordingThis,
                        liveOnExo = liveOnExo,
                        onToggleLiveEngine = onToggleLiveEngine,
                        onSources = onSources,
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

/**
 * Position, duration and the scrubber, for anything with an end: a film, an episode, a replay.
 *
 * The bar is dragged either directly or by the screen-wide horizontal gesture, and both show the same
 * bubble in the same place — [gestureDeltaMs] is the gesture's running total, still unapplied.
 */
@Composable
private fun SeekBar(player: OwnTVPlayer, gestureDeltaMs: Long?) {
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()
    val buffered by player.bufferedMs.collectAsStateWithLifecycle()
    if (duration <= 0) return
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val playedFraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    val gestureFraction = gestureDeltaMs?.let {
        ((position + it).toFloat() / duration).coerceIn(0f, 1f)
    }
    val fraction = dragValue.takeIf { dragging } ?: gestureFraction ?: playedFraction
    val bufferedFraction = (buffered.toFloat() / duration).coerceIn(fraction, 1f)
    val targetMs = (fraction * duration).toLong()
    // Never the theme accent: over a picture the light theme's accent is a dark tone on a dark scene.
    val accent = LocalAccentOnVideo.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeCap(formatTimestamp(targetMs), Alignment.Start)
        MobileSeekBar(
            fraction = fraction,
            bufferedFraction = bufferedFraction,
            dragging = dragging || gestureDeltaMs != null,
            accent = accent,
            // While the value is moving, say where it will land and by how much.
            bubbleTargetMs = targetMs.takeIf { dragging || gestureDeltaMs != null },
            bubbleDeltaMs = gestureDeltaMs ?: (targetMs - position).takeIf { dragging },
            onSeekTo = { player.seekBy((it * duration).toLong() - player.position.value) },
            onDrag = { dragging = true; dragValue = it },
            onDragEnd = {
                dragging = false
                player.seekBy((dragValue * duration).toLong() - player.position.value)
            },
            modifier = Modifier.weight(1f).padding(horizontal = MobileDimens.GapSmall),
        )
        TimeCap(stringResource(R.string.player_time_remaining, formatTimestamp(duration - targetMs)), Alignment.End)
    }
}

/**
 * The live edge, and how far back from it the picture is.
 *
 * The timeline is programme-aware: it spans the last two hours up to now, marks where each programme
 * began, and names the one under the finger. A channel whose provider keeps no archive has nothing
 * to drag into, so it gets the badge alone rather than a bar that refuses to move.
 */
@Composable
private fun LiveBar(
    offsetSec: State<Int?>,
    archiveWindowSec: Int,
    programmes: List<LiveProgramme>,
    onScrubLive: (Int) -> Unit,
    gaps: () -> List<LongRange>,
) {
    val liveEdgeMs by rememberClockTick()
    // Read once here: this bar already ticks on its own clock, so the archive tick costs it
    // nothing extra, and nobody above has to hear about it.
    val offset = offsetSec.value
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (archiveWindowSec > 0) {
            MobileLiveTimeline(
                offsetSec = offset ?: 0,
                programmes = programmes,
                // Now, and it keeps being now. Read once per composition this stood still, so a
                // player left open drew its programme ticks against the moment the controls last
                // appeared rather than against the live edge.
                liveEdgeMs = liveEdgeMs,
                accent = LocalAccentOnVideo.current,
                onScrub = onScrubLive,
                modifier = Modifier.weight(1f).padding(end = MobileDimens.GapSmall),
                gaps = gaps,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        LiveStateBadge(offset)
    }
}

/**
 * The tools: one button per picker, scrollable because a phone in portrait is narrow.
 *
 * Each is a 48 dp square showing **its glyph and nothing else**. No control draws its name: the bar
 * sits over the picture on a phone-width screen and there is very little room, so a button that grew
 * sideways into a word pushed its neighbours off the end of the row. Every name is still published
 * to accessibility services — see `CtrlButton`.
 *
 * The order is core's `PlayerControl`, shared with the television (Feature H).
 */
@Composable
private fun ToolBar(
    player: OwnTVPlayer,
    /** Read state from here; `player` is used only for the VOD engine toggle, which is mpv's own. */
    engine: PlaybackEngine,
    isLive: Boolean,
    /** The way back to the live edge, or null when the picture is already there. */
    goLive: (() -> Unit)?,
    onOpenSheet: (PlayerSheet) -> Unit,
    /** Shrink into the app's own mini player, still playing. */
    onMini: () -> Unit,
    audioOnly: Boolean,
    onAudioOnly: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onCatchup: (() -> Unit)?,
    onPreviousChannel: (() -> Unit)? = null,
    // Live only, and only once Multiview is switched on in Settings. Null hides the button.
    onMultiview: (() -> Unit)? = null,
    onReport: () -> Unit,
    onToast: (String) -> Unit,
    /**
     * Whether the Info sheet is open. Report is offered only while it is — a report is about
     * what Info is showing, and there is nothing to report without it.
     */
    infoOpen: Boolean = false,
    // Live only, and only once "Record what I'm watching" is switched on in Settings. Null
    // hides the button, exactly as on the television.
    onRecordThis: (() -> Unit)? = null,
    recordingThis: Boolean = false,
    liveOnExo: Boolean = false,
    onToggleLiveEngine: (() -> Unit)? = null,
    onSources: (() -> Unit)? = null,
    sleepTimer: SleepTimer = koinInject(),
) {
    val speed by engine.speed.collectAsStateWithLifecycle()
    // Only whether one runs: the countdown ticks every second, and the bar must not redraw with it.
    val sleepLeft = sleepTimer.remainingMs.collectAsStateWithLifecycle()
    val sleepRunning by remember { derivedStateOf { sleepLeft.value != null } }
    val engineName by engine.engineChip.collectAsStateWithLifecycle()
    val qualities by engine.videoQualities.collectAsStateWithLifecycle()
    val qualityPick by engine.videoQualityPick.collectAsStateWithLifecycle()
    // The engine chip in the title line is small and easy to miss, so the swap says which engine it
    // landed on — otherwise the only feedback for the button is a picture that blinks.
    val switchedToExo = stringResource(R.string.player_switch_exo)
    val switchedToMpv = stringResource(R.string.player_switch_mpv)
    // The engine chip's own name for ExoPlayer, from the same resource the chip itself reads — so
    // the comparison follows the name rather than repeating the literal.
    val exoName = stringResource(R.string.player_engine_exo)
    // The ORDER is core's `PlayerControl`, the same list the television's HUD renders from, so
    // the two bars read alike. Each control keeps its own composable and its own condition; only the
    // sequence is shared. The `when` is exhaustive, so a control added to the shared list cannot be
    // silently missing here.
    //
    // Drawn through one lambda because the bar has two LAYOUTS and only one set of buttons. In
    // portrait a phone has nowhere near the width for both clusters, so they are concatenated into a
    // single scrolling row. In landscape there is room, and the bar takes the television's shape:
    // the media cluster hugs the left edge, the tools cluster the right, and the gap between them
    // separates "what is playing" from "how it is shown". That gap is the thing the two bars were
    // supposed to share and once did not - the phone ran them together at every width.
    val render: @Composable (PlayerControl) -> Unit = { control ->
        when (control) {
            // First, so the way back to now is the first thing the thumb reaches on a bar that
            // scrolls.
            PlayerControl.GO_LIVE -> GoLivePill(enabled = goLive != null, onClick = { goLive?.invoke() })
            PlayerControl.VOLUME -> CtrlButton(MobileIcons.VolumeUp, stringResource(R.string.player_tool_volume), {
                onOpenSheet(PlayerSheet.VOLUME)
            })
            // Phone-only, and rightly so: a television's brightness belongs to the television.
            PlayerControl.BRIGHTNESS -> CtrlButton(MobileIcons.BrightnessMedium, stringResource(R.string.player_tool_brightness), {
                onOpenSheet(PlayerSheet.BRIGHTNESS)
            })
            // Live has no speed to change - the stream arrives at the rate it arrives.
            PlayerControl.SPEED -> if (!isLive) {
                SpeedButton(
                    rate = formatSpeed(speed),
                    label = stringResource(R.string.player_tool_speed),
                    active = speed != 1.0,
                    onClick = { onOpenSheet(PlayerSheet.SPEED) },
                )
            }
            PlayerControl.SUBTITLES -> CtrlButton(
                icon = MobileIcons.ClosedCaption,
                label = stringResource(R.string.player_tool_subtitles),
                onClick = { onOpenSheet(PlayerSheet.SUBTITLES) },
            )
            // Always, like the subtitles button beside it and like the television's — never gated on
            // the track count. This sheet is also where A/V sync lives, and a single-soundtrack film
            // is precisely the one whose voices need dragging back into line with the mouths; hidden
            // on one track, the fix was unreachable exactly when it was wanted. An empty list says so
            // itself, the way the subtitle sheet already does.
            PlayerControl.AUDIO -> CtrlButton(
                icon = MobileIcons.Audiotrack,
                label = stringResource(R.string.player_tool_audio),
                onClick = { onOpenSheet(PlayerSheet.AUDIO) },
            )
            // Adding what is on to Favourites without leaving it. From here there is no list row
            // to hold down, so the player has to carry the toggle itself.
            PlayerControl.FAVOURITE -> CtrlButton(
                icon = if (favorite) MobileIcons.Favorite else MobileIcons.FavoriteBorder,
                label = stringResource(
                    if (favorite) R.string.content_favorited else R.string.content_favorite,
                ),
                onClick = onToggleFavorite,
                active = favorite,
            )
            // The television's catch-up glyph, not History.
            PlayerControl.CATCH_UP -> if (onCatchup != null) {
                CtrlButton(MobileIcons.Catchup, stringResource(R.string.content_catchup_jump), onCatchup)
            }
            // The television's glyph for it, History; present only once there is a channel to go back to.
            PlayerControl.PREVIOUS_CHANNEL -> if (onPreviousChannel != null) {
                CtrlButton(MobileIcons.History, stringResource(R.string.player_previous_channel), onPreviousChannel)
            }
            // The same channel in another quality, category or playlist; the list is read when it opens.
            PlayerControl.SOURCES -> if (onSources != null) {
                CtrlButton(MobileIcons.PlaylistPlay, stringResource(R.string.player_tool_sources), onSources)
            }
            // Live has two engines now, so the button is real there too. It is NOT the VOD
            // toggle: on live it is the television's "compatibility mode", pinned per channel, so a
            // channel only mpv can play opens on mpv next time without being asked again. Null means
            // there is nothing sensible to swap - a replay, a rewind, or a protected channel whose
            // key only one engine can obtain.
            PlayerControl.ENGINE -> if (isLive) {
                if (onToggleLiveEngine != null) {
                    EngineToggle(
                        engine = stringResource(
                            if (liveOnExo) R.string.player_engine_exo else R.string.player_engine_mpv,
                        ),
                        label = stringResource(R.string.player_tool_engine),
                        // Teal while pinned to mpv, exactly as on the television: being on the
                        // compatibility engine is the state worth colouring, because it is the one
                        // the user chose.
                        active = !liveOnExo,
                        icon = MobileIcons.SwapHoriz,
                        onClick = {
                            onToast(if (liveOnExo) switchedToMpv else switchedToExo)
                            onToggleLiveEngine()
                        },
                    )
                }
            } else {
                EngineToggle(
                    engine = engineName.orEmpty(),
                    label = stringResource(R.string.player_tool_engine),
                    // ExoPlayer is not this app's default for a film, so being on it is a state
                    // worth colouring - it is what the user switched to.
                    active = engineName == exoName,
                    icon = MobileIcons.SwapHoriz,
                    onClick = {
                        onToast(if (engineName == exoName) switchedToMpv else switchedToExo)
                        player.toggleVodEngine()
                    },
                )
            }
            PlayerControl.ASPECT -> CtrlButton(
                icon = MobileIcons.AspectRatio,
                label = stringResource(R.string.player_tool_aspect),
                onClick = { onOpenSheet(PlayerSheet.ASPECT) },
            )
            // Only when this stream offers several; tinted while a pick overrides Auto.
            PlayerControl.QUALITY -> if (qualities.isNotEmpty()) {
                CtrlButton(
                    icon = MobileIcons.VideoLibrary,
                    label = stringResource(R.string.player_tool_quality),
                    onClick = { onOpenSheet(PlayerSheet.QUALITY) },
                    active = qualityPick != null,
                )
            }
            // Phone-only today; the television reaches the same list with Left.
            PlayerControl.CHANNEL_LIST -> if (isLive) {
                CtrlButton(MobileIcons.FormatListBulleted, stringResource(R.string.content_channel_overlay_title), {
                    onOpenSheet(PlayerSheet.CHANNELS)
                })
            }
            // Shrink into the app's own small player and keep browsing. Not the system's floating
            // window: that one goes over *other* apps and is what pressing Home gives, so it is
            // not a button.
            PlayerControl.MINI_PLAYER ->
                CtrlButton(MobileIcons.PictureInPictureAlt, stringResource(R.string.settings_mini_player), onMini)
            // Dropping the picture is the phone's biggest battery and data saving, so it is a
            // button on the bar rather than something only the notification offers.
            // The television's headphones glyph, not a music note.
            PlayerControl.AUDIO_ONLY -> CtrlButton(
                icon = MobileIcons.Headphones,
                label = stringResource(R.string.player_tool_audio_only),
                onClick = onAudioOnly,
                active = audioOnly,
            )
            PlayerControl.MULTIVIEW -> if (onMultiview != null) {
                CtrlButton(MobileIcons.GridView, stringResource(R.string.multiview_button), onMultiview)
            }
            // Record the channel already playing. Null until the setting is on, exactly as
            // on the television.
            PlayerControl.RECORD -> if (onRecordThis != null) {
                CtrlButton(
                    icon = MobileIcons.LiveTv,
                    label = stringResource(
                        if (recordingThis) R.string.recording_stop else R.string.recording_record,
                    ),
                    onClick = onRecordThis,
                    active = recordingThis,
                )
            }
            // The full-screen way to the sleep timer; the sound-only screen and the
            // floating window already had one. Coloured while a countdown is running.
            PlayerControl.SLEEP_TIMER -> CtrlButton(
                icon = MobileIcons.Bedtime,
                label = stringResource(R.string.player_sleep_timer),
                onClick = { onOpenSheet(PlayerSheet.SLEEP_TIMER) },
                active = sleepRunning,
            )
            PlayerControl.INFO -> CtrlButton(MobileIcons.Info, stringResource(R.string.player_tool_info), {
                onOpenSheet(PlayerSheet.INFO)
            })
            // The television's rule, on both apps. A report is about what Info is
            // showing, there is nothing to report without it, and the great majority of users
            // never file one - so keeping it out of the bar keeps the bar short.
            // The television's share glyph, not a bug.
            PlayerControl.REPORT -> if (infoOpen) {
                CtrlButton(MobileIcons.Share, stringResource(R.string.player_tool_report), onReport)
            }
        }
    }

    val media = PlayerControl.clusterFor(tv = false, cluster = ControlCluster.MEDIA)
    val tools = PlayerControl.clusterFor(tv = false, cluster = ControlCluster.TOOLS)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (landscape) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
            ) {
                media.forEach { render(it) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
            ) {
                tools.forEach { render(it) }
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
        ) {
            (media + tools).forEach { render(it) }
        }
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
