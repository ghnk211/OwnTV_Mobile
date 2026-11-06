package tv.own.owntv.mobile.ui.player

import androidx.compose.runtime.produceState
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.mobile.ui.components.CategoryPickerSheet
import android.content.pm.ActivityInfo
import android.os.Build
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.playback.PipController
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.subtitles.SubtitleController
import tv.own.owntv.mobile.ui.screens.ContentActions
import tv.own.owntv.mobile.ui.setup.copyPickedFile
import tv.own.owntv.mobile.ui.theme.LocalAccentOnVideo
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.screens.settings.SheetText
import tv.own.owntv.mobile.ui.screens.settings.StageConfirmSheet
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.Role
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackErrorLog
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.player.ZoomMode
import tv.own.owntv.player.describe
import tv.own.owntv.player.displayText
import tv.own.owntv.player.titleRes

private const val CONTROLS_TIMEOUT_MS = 3_000L
private const val HUD_TIMEOUT_MS = 900L

/** How long a confirmation line stays over the picture. */
private const val TOAST_MS = 1_800L
private const val HINT_MS = 6_000L
private const val SPEED_HOLD = 2.0

/** How many notches the brightness slide is divided into for its haptic tick. */
private const val NOTCHES = 20

/**
 * The picture, full screen, with everything on top of it.
 *
 * It is a route rather than its own activity, so the stream it shows is the one [LiveTuner] already
 * has running — going full screen and coming back out is a change of view, never a restart. Back and
 * the swipe down both leave it playing, which is what puts it into the mini player.
 *
 * The screen asks for landscape when it opens and then lets go of the orientation again, so a video
 * lands the right way up without a user who prefers portrait being trapped in landscape.
 */
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    tuner: LiveTuner = koinInject(),
    vodTuner: VodTuner = koinInject(),
    pip: PipController = koinInject(),
    settings: SettingsRepository = koinInject(),
    channelVersions: tv.own.owntv.core.live.ChannelVersions = koinInject(),
    actions: ContentActions = koinInject(),
    subtitles: SubtitleController = koinInject(),
    cast: CastController = koinInject(),
    downloadWatchGuard: tv.own.owntv.core.download.DownloadWatchGuard = koinInject(),
) {
    val player = tuner.player
    val downloadWatchPrompt by downloadWatchGuard.prompt.collectAsStateWithLifecycle()

    // Live now has two engines. `liveOnExo` says which one holds the picture, so the stage knows
    // whose surface to draw and the HUD's engine button knows which way it flips.
    val liveOnExo by tuner.liveOnExo.collectAsStateWithLifecycle()

    // The engine actually holding the stream. For VOD and for live-on-mpv this is a thin delegate
    // over `player`, so nothing changes there; on live-on-ExoPlayer it is the other engine.
    val activeEngine by tuner.activeEngine.collectAsStateWithLifecycle()
    val castEngine by cast.engine.collectAsStateWithLifecycle()
    val castDevice by cast.deviceName.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val inPip by pip.inPip.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val channel by tuner.channel.collectAsStateWithLifecycle()
    val autoFrameRateFilms by settings.autoFrameRateFilms.collectAsStateWithLifecycle(initialValue = false)
    val autoFrameRateLive by settings.autoFrameRateLive.collectAsStateWithLifecycle(initialValue = false)
    val previousChannel by tuner.previousChannel.collectAsStateWithLifecycle()
    val film by vodTuner.playing.collectAsStateWithLifecycle()
    val nowNext by tuner.nowNext.collectAsStateWithLifecycle()
    val siblings by tuner.siblings.collectAsStateWithLifecycle()
    // Held as States, not values: both tick about once a second while an archive plays, and only
    // the dock's clock, the Now/Next card and the live bar read them — values here would recompose
    // this whole screen on every tick.
    val offsetSecState = tuner.offsetSec.collectAsStateWithLifecycle()
    val watchingWallMsState = tuner.watchingWallMs.collectAsStateWithLifecycle()
    val timelineProgrammes by tuner.timelineProgrammes.collectAsStateWithLifecycle()
    // The same switch the Live TV list reads: off hides every number in the app, this one included.
    val showChannelNumbers by settings.directTune.collectAsStateWithLifecycle(true)
    // All four describe the stream that is playing, so they come from the engine that HAS it. Read
    // from `player` they described a stopped mpv while ExoPlayer held a live channel: `isPlaying`
    // was false for ever, which left the spinner turning over a moving picture and stopped the
    // controls from ever auto-hiding, because both are gated on it.
    val duration by activeEngine.duration.collectAsStateWithLifecycle()
    val error by activeEngine.error.collectAsStateWithLifecycle()
    val errorInfo by activeEngine.errorInfo.collectAsStateWithLifecycle()
    val isPlaying by activeEngine.isPlaying.collectAsStateWithLifecycle()
    // Waiting for the stream is NOT the same as not playing: a user who pressed pause is also "not
    // playing", and the spinner over a deliberately paused picture reads as a stall. The television
    // has always drawn this from `buffering`; so does this now.
    val buffering by activeEngine.buffering.collectAsStateWithLifecycle()
    val stalledSinceMs by activeEngine.stalledSinceMs.collectAsStateWithLifecycle()
    // Only the stream that never had a picture — a radio channel — reaches this screen without one.
    // The user's own sound-only choice cannot, see below.
    val audioOnlyMedia by activeEngine.audioOnlyMedia.collectAsStateWithLifecycle()
    // Held as the State, not its value: only the countdown below reads it, so the tick every second
    // does not recompose the whole screen.
    val positionState = activeEngine.position.collectAsStateWithLifecycle()
    val nav by player.nav.collectAsStateWithLifecycle()
    val nextUpTitle by player.nextUpTitle.collectAsStateWithLifecycle()
    // The sleep timer's "End of episode" stops there, so there is no next to count down to.
    val stopsAtItemEnd by player.stopsAtItemEnd.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Multiview. Off by default and opt-in, so the button is not even drawn until the user has
    // switched it on; the grid owns its own engines and stops this one while it is up.
    val multiviewEnabled by settings.multiviewEnabled.collectAsStateWithLifecycle(false)
    val multiviewTiles by settings.multiviewTiles.collectAsStateWithLifecycle(
        tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES,
    )
    val enginePool = koinInject<tv.own.owntv.player.LiveEnginePool>()
    val streamRegistry = koinInject<tv.own.owntv.core.live.OpenStreamRegistry>()
    var multiview by remember {
        mutableStateOf<tv.own.owntv.mobile.ui.screens.multiview.MobileMultiviewState?>(null)
    }
    // The relock reads the grid's tiles and empties those on a channel of a category it has just closed.
    val sealedHost = koinInject<tv.own.owntv.mobile.ui.categorylock.SealedPlaybackHost>()
    androidx.compose.runtime.DisposableEffect(multiview, sealedHost) {
        val grid = multiview
        sealedHost.tiles = grid?.let { g -> { g.tiles.map { it.channel?.categoryId } } }
        sealedHost.clear = grid?.let { g -> g::clear }
        onDispose {
            sealedHost.tiles = null
            sealedHost.clear = null
        }
    }
    var multiviewPickFor by remember { mutableStateOf<Int?>(null) }
    // Which category the Multiview picker is inside, and what it holds. Null = showing categories.
    var multiviewCategory by remember { mutableStateOf<LiveKey?>(null) }
    var multiviewChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var multiviewCategories by remember { mutableStateOf<List<Pair<LiveKey, String>>>(emptyList()) }
    // The same two steps for the player's own channel button. Null = showing categories.
    var playerCategory by remember { mutableStateOf<LiveKey?>(null) }
    var playerChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var playerCategories by remember { mutableStateOf<List<Pair<LiveKey, String>>>(emptyList()) }
    var playerCategoriesLoaded by remember { mutableStateOf(false) }
    // Loaded when the picker opens, not when the player does: it is a database read nobody watching
    // a channel has asked for.
    LaunchedEffect(multiviewPickFor) {
        if (multiviewPickFor != null && multiviewCategories.isEmpty()) {
            multiviewCategories = tuner.liveCategoriesForPicker()
        }
        if (multiviewPickFor == null) multiviewCategory = null
    }
    // Channels kept from the Live list (the second way into the grid). Playing any channel is what says
    // "now": the grid opens with them already in it, and the selection is spent.
    val multiviewSelection by tuner.multiviewSelection.collectAsStateWithLifecycle()
    LaunchedEffect(channel?.id, multiviewSelection.size, multiviewEnabled) {
        val current = channel
        if (multiviewEnabled && multiview == null && multiviewSelection.isNotEmpty() && current != null) {
            val kept = multiviewSelection
            tuner.clearMultiviewSelection()
            // BOTH engines, exactly as the HUD button does. This is the *other* way into the grid —
            // the one most used — and it stopped only mpv, so a channel playing on the
            // ExoPlayer engine carried on underneath: a second sound, and a connection spent on a
            // stream nobody could see.
            player.stop()
            tuner.exoEngine.stop()
            val grid = tv.own.owntv.mobile.ui.screens.multiview.MobileMultiviewState(
                pool = enginePool,
                registry = streamRegistry,
                tuner = tuner,
                scope = scope,
                maxTiles = multiviewTiles,
            )
            var tile = 0
            grid.fill(tile++, current)
            // Channels kept by name size the grid themselves, up to the ceiling: the user already
            // said how many they wanted.
            kept.filter { it.id != current.id }.forEach { keptChannel ->
                if (tile >= grid.tiles.size) grid.addTile()
                if (tile < grid.tiles.size) grid.fill(tile++, keptChannel)
            }
            multiview = grid
        }
    }

    // What Favourite would act on: the channel, the film, or — for an episode — the show it is from,
    // because an episode is never favourited on its own.
    val favoriteType = channel?.let { MediaType.LIVE }
        ?: film?.let { if (it.mediaType == MediaType.EPISODE) MediaType.SERIES else it.mediaType }
    val favoriteId = channel?.id ?: film?.let { it.seriesId ?: it.itemId }
    val favoriteIds by remember(favoriteType) {
        favoriteType?.let { actions.favoriteIds(it) } ?: flowOf(emptySet())
    }.collectAsStateWithLifecycle(emptySet())

    // A line over the picture: the engine swap, the report, a subtitle file that would not load.
    var toast by remember { mutableStateOf<String?>(null) }
    // A channel juddering on ExoPlayer: said once that the other engine may play it smoothly. It stays
    // longer than the other lines — this one has to be read.
    val smoothnessHint by activeEngine.smoothnessHint.collectAsStateWithLifecycle()
    val smoothnessHintText = stringResource(R.string.player_smoothness_hint)
    LaunchedEffect(smoothnessHint) { if (smoothnessHint) toast = smoothnessHintText }
    LaunchedEffect(toast) { if (toast != null) { delay(if (toast == smoothnessHintText) HINT_MS else TOAST_MS); toast = null } }
    val reportSaved = stringResource(R.string.player_report_saved)
    val subtitleFailed = stringResource(R.string.content_subtitle_load_failed)

    // A subtitle file already on the phone. Copied into the cache first: the player opens it by path,
    // and a document-provider Uri is not one.
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = copyPickedFile(context, uri, java.io.File(context.cacheDir, "subtitles"))
            if (file == null || runCatching { subtitles.applyLocal(file) }.isFailure) toast = subtitleFailed
        }
    }

    // **There is no full-screen sound-only mode.** Full screen is the one place in the app that
    // exists to show the picture, so arriving here turns it back on, however the session came to be
    // without it. Closing the floating window switches it off deliberately, and tapping the
    // quick-panel controls afterwards used to land here on a black rectangle with sound; expanding a
    // sound-only mini player did the same. Sound only lives in the docked bar, which is where its
    // own button sends it.
    LaunchedEffect(activeEngine) { activeEngine.exitAudioOnly() }
    // How far a value moves per centimetre of finger. 100 is the untouched behaviour.
    val gestureSensitivity by settings.gestureSensitivityPct.collectAsStateWithLifecycle(100)

    // A replay has an end and therefore a seek bar; the live edge and a rewind into the archive have
    // neither, and get the live bar instead.
    //
    // Asked of the tuner, never of the duration: a live stream reports whatever its rolling window
    // is, and providers that report a plausible twenty-odd hours had their channels classed as
    // recordings — a scrub bar offering 25 hours of live television, and no clock, Now/Next, live
    // badge, Go Live pill or programme timeline anywhere.
    val replaying by tuner.replaying.collectAsStateWithLifecycle()
    val isLive = channel != null && !replaying

    // "Go back to…", for a live channel whose provider keeps an archive — or its own saved copy,
    // which exists only once the tune has started saving. Recomputed per channel, since the depth of
    // the archive is the channel's, not the playlist's.
    val hasLocalCopy by tuner.hasLocalCopy.collectAsStateWithLifecycle()
    val catchup = remember(channel?.id, hasLocalCopy) {
        tuner.jumpOptions().takeIf { it.isNotEmpty() }?.let { offsets ->
            CatchupOptions(offsets, tuner.archiveWindowSec(), tuner::jumpBackTo, tuner::currentProgrammeStart)
        }
    }

    // Back on a channel whose saved copy was kept: continue from there, or stay live.
    val timeshiftResumeAt by tuner.timeshiftResumeAt.collectAsStateWithLifecycle()
    if (timeshiftResumeAt != null) {
        // Resume clears the offer itself, so the sheet's dismiss after it is a no-op.
        StageConfirmSheet(
            title = stringResource(R.string.player_timeshift_resume_title),
            text = stringResource(R.string.player_timeshift_resume_message),
            confirm = stringResource(R.string.common_resume),
            onConfirm = tuner::resumeTimeshift,
            onDismiss = tuner::dismissTimeshiftResume,
            cancel = stringResource(R.string.player_go_live),
        )
    }

    // A one-stream playlist a download was using: core has paused the download and asks what next.
    // Dismissing is watching — the picture is already on and the download stays paused.
    downloadWatchPrompt?.let { p ->
        var rememberAnswer by remember(p) { mutableStateOf(false) }
        MobileBottomSheet(
            onDismissRequest = { downloadWatchGuard.answer(watch = true, remember = rememberAnswer) },
        ) {
            StageSheetHeader(stringResource(R.string.content_download_watch_title))
            SheetText(stringResource(R.string.content_download_watch_body, p.playlistName))
            StageMenuItem(
                stringResource(R.string.content_download_watch_remember),
                onClick = { rememberAnswer = !rememberAnswer },
                checked = rememberAnswer,
                role = Role.Checkbox,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageButton(
                    onClick = {
                        downloadWatchGuard.answer(watch = false, remember = rememberAnswer)
                        // Stopped, not left playing in the mini player: the download needs the stream.
                        tuner.stop()
                        vodTuner.stop()
                        onExit()
                    },
                    text = stringResource(R.string.content_download_watch_keep),
                    modifier = Modifier.weight(1f),
                )
                StageButton(
                    onClick = { downloadWatchGuard.answer(watch = true, remember = rememberAnswer) },
                    text = stringResource(R.string.content_download_watch_watch),
                    style = StageButtonStyle.PRIMARY,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // The automatic advance, made visible. The engine starts the next episode eight seconds before the
    // end, so the card counts down to that, not to the duration.
    var autoNextDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(nextUpTitle, nav.hasNext) { autoNextDismissed = false }
    // Derived, so the screen recomposes only when the countdown's second changes, not on every tick.
    val secondsToAdvance by remember(isLive, duration, positionState) {
        derivedStateOf {
            val msToAdvance = if (!isLive && duration > 0L) (duration - 8_000L) - positionState.value else Long.MAX_VALUE
            if (msToAdvance in 0L..30_000L) ((msToAdvance + 999L) / 1000L).toInt().coerceIn(0, 30) else null
        }
    }
    val showNextCard = !isLive && error == null && nav.hasNext && nextUpTitle != null && !stopsAtItemEnd &&
        secondsToAdvance != null && !autoNextDismissed

    var controlsVisible by remember { mutableStateOf(true) }
    // Derived, so the screen recomposes only when the live edge is crossed, not on every tick.
    val atLiveEdge by remember { derivedStateOf { (offsetSecState.value ?: 0) <= 1 } }
    // Pure except for the channel (and the cast route) it reads: recompute on those, not on ticks.
    val archiveWindowSec = remember(isLive, channel?.id, castDevice) { tuner.archiveWindowSec() }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var showSources by remember { mutableStateOf(false) }
    // Closing the sheet forgets which category was open, so the next press starts at the categories.
    LaunchedEffect(sheet) { if (sheet != PlayerSheet.CHANNELS) playerCategory = null }
    val recordWatchingEnabled by settings.recordWhatImWatching.collectAsStateWithLifecycle(initialValue = false)
    val playerRecording by tuner.playerRecording.collectAsStateWithLifecycle()
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var hud by remember { mutableStateOf<GestureFeedback?>(null) }
    // Which *showing* this is, counted up on every one. Two forward double taps at the same seek step
    // produce an equal `GestureFeedback.Skip`, and Compose compares state by value: the second tap was
    // therefore not a change at all, so the hide timer below — keyed on the value — never restarted.
    // Worse, when a tap landed in the same frame as the timer's own `null`, the composition only ever
    // saw the value it started with, the key never moved, and the finished timer was never relaunched:
    // the badge then sat on the picture until some *different* gesture replaced it. Counting makes
    // every showing distinct, so every one of them arms its own timer.
    var hudShowing by remember { mutableIntStateOf(0) }
    fun showHud(feedback: GestureFeedback?) {
        hud = feedback
        hudShowing++
    }
    val haptics = LocalHapticFeedback.current
    // How far the screen-wide scrub gesture has moved so far, as a fraction of the whole, or null
    // when no such gesture is in progress. The seek bar draws it; nothing has been seeked yet.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    // Fractional carry: one flick of a thumb is many tiny deltas, and rounding each one to a whole
    // percent on its own would throw most of the movement away.
    val carry = remember { Carry() }

    // Full screen means full screen: no status bar, no navigation bar. The display is kept awake by
    // VideoStage, which knows whether there is a picture to stay awake for.
    //
    // This is also where the activity learns that the picture is on screen, which is the difference
    // between "home was pressed while watching" (Picture-in-Picture) and "home was pressed" (leave).
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        // Transient, not permanent. Left at the default, a swipe from the edge brought the clock and
        // the navigation bar back over the picture and *left them there* for the rest of the film —
        // the top bar in the middle of a scene. This way the swipe still shows them, and they leave
        // again on their own.
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        // Hiding the bars is not enough: the strip the camera sits in stays outside the window
        // unless the window is told to lay out into it, and the wallpaper shows through there.
        // The field itself only exists from Android 9, so it is read and restored inside the guard —
        // touching it on an older phone is a NoSuchFieldError, not a no-op.
        val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode
        } else {
            null
        }
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    // Before Android 11 only the short edges can be drawn into, which is the phone's
                    // top in portrait — the one that matters on a notched device.
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        // Hiding once is not enough either: the keyboard in the channel-number field pulls the bars
        // back up with it, and they do not always leave again when it goes. Anything that puts them
        // back while the picture is on screen puts them away again. The insets are returned exactly
        // as they arrived — this watches, it does not consume.
        val decor = window?.decorView
        if (decor != null) {
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, applied ->
                if (applied.isVisible(WindowInsetsCompat.Type.systemBars())) {
                    insets?.hide(WindowInsetsCompat.Type.systemBars())
                }
                applied
            }
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        pip.playerOnScreen.value = true
        onDispose {
            pip.playerOnScreen.value = false
            if (decor != null) ViewCompat.setOnApplyWindowInsetsListener(decor, null)
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    if (cutoutMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = cutoutMode
                    }
                }
            }
        }
    }
    // Once the rotation has actually happened, hand the orientation back: the request above was to
    // arrive in landscape, not to stay there.
    LaunchedEffect(landscape) {
        if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun setBrightness(value: Float) {
        brightness = value.coerceIn(0.01f, 1f)
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }

    /** A short tick when a gesture crosses a step: a volume notch, a skip firing, fast play engaging. */
    fun tick() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Every readout is a burst except fast play, which lasts exactly as long as the finger is down.
    LaunchedEffect(hudShowing) {
        if (hud != null && hud !is GestureFeedback.Speed) {
            delay(HUD_TIMEOUT_MS)
            showHud(null)
        }
    }
    // The controls go away on their own, but never while a picker is open over them and never in the
    // middle of a scrub — the bar is the only thing saying where letting go would land.
    val scrubbing = scrubFraction != null
    LaunchedEffect(controlsVisible, sheet, isPlaying, scrubbing) {
        if (controlsVisible && sheet == null && isPlaying && !scrubbing) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    // Back means "I am finished watching", and it ends the stream. Leaving the player *playing* is
    // what the mini-player button and the swipe down are for — both of them say so, and Back does not.
    // A stream that outlived a Back press was the app quietly holding one of the provider's allowed
    // connections open with nothing on screen to say so.
    val stopAndExit = {
        tuner.stop()
        vodTuner.stop()
        onExit()
    }
    BackHandler(onBack = stopAndExit)

    // Casting replaces the screen rather than decorating it. Everything below this point drives the
    // engine on this phone — the surface, the gestures, the track pickers — and while the television
    // has the stream there is no such engine to drive.
    val remote = castEngine
    if (remote != null) {
        CastStage(
            engine = remote,
            deviceName = castDevice,
            title = channel?.name ?: film?.title.orEmpty(),
            subtitle = if (channel != null) nowNext?.now?.title else film?.subtitle,
            artworkUrl = channel?.displayLogoUrl ?: film?.posterUrl,
            onBack = stopAndExit,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .playerGestures(
                onTap = { controlsVisible = !controlsVisible },
                onDoubleTapLeft = {
                    skip(tuner, isLive, forward = false)
                    tick()
                    showHud(GestureFeedback.Skip(forward = false, deltaMs = player.seekStepMs.value))
                },
                onDoubleTapRight = {
                    skip(tuner, isLive, forward = true)
                    tick()
                    showHud(GestureFeedback.Skip(forward = true, deltaMs = player.seekStepMs.value))
                },
                onScrub = {
                    carry.scrub += it
                    // The chrome is what shows the target, so a scrub that started on a bare picture
                    // brings the bar back rather than moving the film blind.
                    controlsVisible = true
                    scrubFraction = carry.scrub
                },
                onScrubEnd = {
                    scrubFraction = null
                    if (isLive) {
                        val window = tuner.archiveWindowSec()
                        if (window > 0) tuner.scrubLive((-carry.scrub * window).toInt())
                    } else if (duration > 0) {
                        activeEngine.seekBy((carry.scrub * duration).toLong())
                    }
                    carry.scrub = 0f
                },
                onBrightness = { delta ->
                    val before = (brightness * NOTCHES).toInt()
                    setBrightness(brightness + delta)
                    if ((brightness * NOTCHES).toInt() != before) tick()
                    showHud(GestureFeedback.Level(volume = false, percent = (brightness * 100).toInt()))
                },
                onVolume = { delta ->
                    val engine = activeEngine
                    if (engine is tv.own.owntv.mobile.cast.CastPlaybackEngine) {
                        // Casting: the swipe is the receiver's volume, as before.
                        carry.volume += delta * 150f
                        val whole = carry.volume.toInt()
                        if (whole != 0) {
                            carry.volume -= whole
                            engine.adjustVolumeByUser(whole)
                            tick()
                        }
                        showHud(GestureFeedback.Level(volume = true, percent = engine.volume.value))
                    } else {
                        // The phone's own media volume, like VLC: the same level the volume keys move.
                        // Past the phone's maximum the swipe goes on into the player's boost (to 150 %).
                        val swipe = SystemVolumeSwipe(context, engine)
                        carry.volume += delta * swipe.steps
                        val whole = carry.volume.toInt()
                        if (whole != 0) {
                            carry.volume -= whole
                            repeat(kotlin.math.abs(whole)) { swipe.step(up = whole > 0) }
                            tick()
                        }
                        showHud(GestureFeedback.Level(volume = true, percent = swipe.percent()))
                    }
                },
                onPinch = { zoomIn ->
                    val mode = if (zoomIn) ZoomMode.FILL else ZoomMode.FIT
                    activeEngine.setZoomModeByUser(mode)
                    showHud(GestureFeedback.Zoom(mode))
                },
                onSwipeDown = onExit,
                onSwipeUp = { if (isLive) sheet = PlayerSheet.CHANNELS },
                onSpeedHold = { held ->
                    if (isLive) return@playerGestures
                    if (held) {
                        carry.speedBefore = player.speed.value
                        player.setSpeed(SPEED_HOLD)
                        tick()
                        showHud(GestureFeedback.Speed(SPEED_HOLD))
                    } else {
                        player.setSpeed(carry.speedBefore)
                        showHud(null)
                    }
                },
                onTwoFingerTap = {
                    activeEngine.toggleMute()
                    // Silence has no level to show; coming back out of it, the level is the answer.
                    showHud(
                        if (activeEngine.volume.value == 0) {
                            GestureFeedback.Muted
                        } else {
                            GestureFeedback.Level(volume = true, percent = activeEngine.volume.value)
                        },
                    )
                },
                sensitivity = gestureSensitivity / 100f,
            ),
    ) {
        VideoStage(
            player = player,
            // Settings › Picture › Frame rate has one switch for Live TV and one for films and series.
            autoFrameRate = if (isLive) autoFrameRateLive else autoFrameRateFilms,
            // Where the picture actually is, so entering the little window is the picture shrinking
            // into the corner rather than the whole screen fading into it. Cleared on the way out,
            // or the system would be handed a rectangle from a screen that is no longer there.
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val box = coords.boundsInWindow()
                    pip.videoBounds = android.graphics.Rect(
                        box.left.toInt(),
                        box.top.toInt(),
                        box.right.toInt(),
                        box.bottom.toInt(),
                    )
                },
        )
        DisposableEffect(Unit) { onDispose { pip.videoBounds = null } }

        // A radio channel has no picture to show and never will, so the rectangle it would fill shows
        // what is playing instead. Not a screen of its own — the controls and the gestures are still
        // these. This is the *only* way full screen ends up without a picture.
        val noPicture = audioOnlyMedia && !inPip
        if (noPicture) {
            AudioOnlyBackdrop(
                title = channel?.name ?: film?.title.orEmpty(),
                playing = isPlaying,
                compact = controlsVisible,
                subtitle = if (channel != null) nowNext?.now?.title else film?.subtitle,
                artworkUrl = channel?.displayLogoUrl ?: film?.posterUrl,
                programmeEndMs = nowNext?.now?.stopMs,
            )
        }

        val failure = error
        if (failure != null) {
            ErrorPanel(failure = failure, info = errorInfo, onRetry = activeEngine::retry)
        } else if (buffering && !noPicture) {
            // On the same material as every other message over the picture, rather than a bare ring.
            PlayerToast(Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    // As on the TV, a live channel that had been playing and has stalled for
                    // 10 s says so under the spinner; gone as soon as the picture returns.
                    val stalledSince = stalledSinceMs
                    if (isLive && stalledSince != null) {
                        val showNotice by produceState(false, stalledSince) {
                            kotlinx.coroutines.delay(RECONNECT_NOTICE_AFTER_MS - (android.os.SystemClock.elapsedRealtime() - stalledSince))
                            value = true
                        }
                        if (showNotice) {
                            Text(
                                stringResource(R.string.player_reconnecting),
                                style = tv.own.owntv.mobile.ui.stage.stageText(14f, 600),
                                color = Color(0xFFE6ECE9),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                }
            }
        }

        PlayerControls(
            player = player,
            engine = activeEngine,
            // Whichever tuner has the surface. Only one of them ever does.
            title = channel?.name ?: film?.title.orEmpty(),
            // On a channel with a guide the Now/Next card carries the programme, so repeating it on
            // the title line would say the same thing twice on the narrowest row of the dock.
            subtitle = if (channel != null) null else film?.subtitle,
            logoUrl = channel?.displayLogoUrl ?: film?.posterUrl,
            // Nothing is drawn over the picture in the little window: it is a thumbnail, and the
            // system draws its own buttons on top of it.
            // Held sideways the channel list is a panel over the picture alone: the controls step aside.
            visible = controlsVisible && !inPip && !(sheet == PlayerSheet.CHANNELS && landscape),
            isLive = isLive,
            offsetSec = offsetSecState,
            archiveWindowSec = archiveWindowSec,
            epg = nowNext,
            watchingWallMs = watchingWallMsState,
            timelineProgrammes = timelineProgrammes,
            channelNumber = channel?.number?.takeIf { showChannelNumbers },
            liveOnExo = liveOnExo,
            // Offered only where a swap makes sense. Rewound into the archive, a swap would
            // re-open the channel at the live edge and throw the user out of the rewind; a protected
            // channel has only one engine that can obtain its key, so swapping would trade a playing
            // picture for a guaranteed failure. `isLive` already excludes a replay.
            onToggleLiveEngine = tuner::toggleLiveEngine.takeIf {
                isLive && (atLiveEdge || hasLocalCopy) && channel?.drmConfig == null
            },
            onBack = stopAndExit,
            onGoLive = tuner::goToLive,
            onScrubLive = tuner::scrubLive,
            onSkipLive = tuner::skipLive.takeIf { archiveWindowSec > 0 },
            liveGaps = tuner::timeshiftGaps,
            onOpenSheet = { sheet = it },
            // Shrink the picture without stopping it: the stream carries on in the mini player, docked
            // or floating as the user set it. This is the app's own small window and stays inside the
            // app — the system one that floats over *other* apps is what pressing Home gives.
            onMini = onExit,
            // A stream with no video track has nothing to go back to, so for that one the button
            // only ever reports the state it is already in. Every other stream is showing its
            // picture here, because full screen is never sound-only — so the button is only ever
            // one way round.
            audioOnly = audioOnlyMedia,
            // Sound only is a way of leaving the picture, so it leaves the full screen with it —
            // otherwise the user is staring at a black rectangle they asked to stop drawing. What it
            // leaves behind is the docked bar, there being no picture to float.
            onAudioOnly = {
                if (!audioOnlyMedia) {
                    tuner.setAudioOnly(true)
                    onExit()
                }
            },
            favorite = favoriteId != null && favoriteId in favoriteIds,
            onToggleFavorite = {
                val type = favoriteType ?: return@PlayerControls
                val id = favoriteId ?: return@PlayerControls
                scope.launch { actions.toggleFavorite(type, id) }
            },
            // Only a live channel whose provider keeps an archive has anything to go back into.
            onCatchup = if (catchup != null) ({ sheet = PlayerSheet.CATCHUP }) else null,
            onPreviousChannel = if (isLive && previousChannel != null) tuner::tunePrevious else null,
            // The previous/next channel of the same category, wrapping at both ends. Offered only
            // with a list worth stepping through — a lone channel has nowhere to go.
            onChannelPrevious = if (isLive && siblings.size >= 2) ({ tuner.step(-1) }) else null,
            onChannelNext = if (isLive && siblings.size >= 2) ({ tuner.step(1) }) else null,
            // Live channels only, and only once Multiview is switched on. The channel on screen
            // becomes tile 1 and the grid takes over from this player.
            onMultiview = if (multiviewEnabled && isLive && channel != null) {
                {
                    // The fullscreen stream goes first: tile 1 opens its own engine, and a playlist
                    // that allows two streams would otherwise be asked for three.
                    val current = channel
                    // BOTH engines. Live plays on the ExoPlayer engine whenever that is the
                    // chosen engine, and stopping only mpv left that stream running behind the grid —
                    // audible, and holding one of the provider's connections, while a tile played the
                    // same or another channel over the top of it. Two sounds at once.
                    player.stop()
                    tuner.exoEngine.stop()
                    val grid = tv.own.owntv.mobile.ui.screens.multiview.MobileMultiviewState(
                        pool = enginePool,
                        registry = streamRegistry,
                        tuner = tuner,
                        scope = scope,
                        maxTiles = multiviewTiles,
                    )
                    current?.let { grid.fill(0, it) }
                    multiview = grid
                }
            } else {
                null
            },
            onReport = {
                val meta = activeEngine.currentMeta.value
                scope.launch {
                    val snapshot = activeEngine.streamInfo().joinToString("\n") { row ->
                        "  ${res.getString(row.label.titleRes)}: ${row.value.displayText(res)}"
                    }
                    PlaybackErrorLog.report(
                        context = context,
                        engine = player.engineChip.value ?: "?",
                        live = isLive,
                        title = meta.title,
                        snapshot = snapshot,
                    )
                }
                // Acknowledged at once: the user pressed a button, and the gathering takes a moment.
                toast = reportSaved
            },
            onToast = { toast = it },
            gestureScrubMs = scrubFraction?.takeIf { duration > 0 }?.let { (it * duration).toLong() },
            // Report is offered only while Info is open, the television's rule on both apps.
            infoOpen = sheet == PlayerSheet.INFO,
            // Live channels only, and only once the setting is on. Null hides the button.
            onRecordThis = if (recordWatchingEnabled && isLive && channel != null) {
                { tuner.togglePlayerRecording() }
            } else {
                null
            },
            recordingThis = playerRecording != null,
            onSources = channel?.takeIf { isLive }?.let { { showSources = true } },
        )
        channel?.takeIf { showSources }?.let { playing ->
            SourcesSheet(
                load = { channelVersions.of(playing) },
                playingId = playing.id,
                onPick = { tuner.switchTo(it) },
                onDismiss = { showSources = false },
            )
        }

        // Independent of the controls, so it still appears after they have faded out on their own.
        if (showNextCard) {
            NextEpisodeCard(
                seconds = secondsToAdvance ?: 0,
                title = nextUpTitle.orEmpty(),
                onPlayNow = { autoNextDismissed = true; player.next() },
                onCancel = { autoNextDismissed = true; player.cancelAutoNext() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(end = 16.dp, bottom = 96.dp),
            )
        }

        toast?.let { message ->
            PlayerToast(
                Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 96.dp),
            ) {
                Text(message, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }

        GestureHud(hud.takeIf { !inPip })
    }

    // Multiview draws over the whole player, including the picture it grew out of, and owns its own
    // engines. The stream behind it was stopped when the grid opened.
    multiview?.let { grid ->
        tv.own.owntv.mobile.ui.screens.multiview.MultiviewScreen(
            state = grid,
            onPickChannel = { tile -> multiviewPickFor = tile },
            onFullscreen = { picked ->
                grid.releaseAll()
                multiview = null
                multiviewPickFor = null
                tuner.switchTo(picked)
            },
            onExit = {
                grid.releaseAll()
                multiview = null
                multiviewPickFor = null
                // Leaving the grid leaves *playback*, by design.
                //
                // It used to promote whichever tile had the sound to the single stream. In use that
                // is wrong twice over: a grid is put away by someone who has finished watching, and
                // being handed one of the tiles means a stream is still running and still costing a
                // connection when the user believes they closed everything.
                stopAndExit()
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Filling a tile: categories first, then that category's channels. The player's own picker
        // starts at channels because it already has one playing; an empty tile has no such context,
        // and a flat list of every channel is tens of thousands of rows on a real playlist.
        multiviewPickFor?.let { tile ->
            if (multiviewCategory == null && multiviewCategories.isNotEmpty()) {
                // The Live screen's own category sheet, not a second one: it already has the search
                // field a list of hundreds of categories needs on a phone.
                CategoryPickerSheet(
                    labels = multiviewCategories.map { it.second },
                    selectedIndex = -1,
                    onSelect = { index ->
                        multiviewCategories.getOrNull(index)?.first?.let { catId ->
                            multiviewCategory = catId
                            scope.launch { multiviewChannels = tuner.channelsInCategoryForPicker(catId) }
                        }
                    },
                    // Backing out of the categories leaves the picker entirely.
                    onDismiss = { multiviewPickFor = null },
                    // Two steps: choosing a category opens its channels, it does not close the sheet.
                    dismissOnSelect = false,
                )
            } else {
                PlayerSheetHost(
                    sheet = PlayerSheet.CHANNELS,
                    player = activeEngine,
                    channels = multiviewChannels,
                brightness = brightness,
                onBrightness = { setBrightness(it) },
                    onPickChannel = { picked ->
                        grid.fill(tile, picked)
                        multiviewPickFor = null
                        multiviewCategory = null
                    },
                    onOpenSheet = {},
                    canAddSubtitles = false,
                    onPickLocalSubtitle = {},
                    onTuneToNumber = null,
                    catchup = null,
                    // Back goes UP to the categories, not out of the picker.
                    onDismiss = { multiviewCategory = null },
                )
            }
        }
    }

    // The channel button goes through the categories first, like Multiview's picker: the phone's
    // sheet used to open on one category's channels, so a channel in any *other* category — or any
    // other playlist — could not be reached from the player at all. The category the user is
    // watching is pre-selected, so the common case is still one tap.
    if (sheet == PlayerSheet.CHANNELS && !inPip && multiview == null && playerCategory == null) {
        LaunchedEffect(Unit) {
            if (!playerCategoriesLoaded) {
                playerCategories = tuner.liveCategoriesForPicker()
                playerCategoriesLoaded = true
            }
        }
    }
    // Composed only once the list exists. A sheet is handed to the host as a lambda and drawn there,
    // and the host went on drawing the lambda it was first given: opened while the categories were
    // still being read, it showed its search field and an empty list for ever — until a rotation
    // rebuilt everything. Waiting for the data costs one database read and cannot go stale.
    if (sheet == PlayerSheet.CHANNELS && !inPip && multiview == null && playerCategory == null &&
        playerCategoriesLoaded && playerCategories.isNotEmpty()
    ) {
        CategoryPickerSheet(
            labels = playerCategories.map { it.second },
            selectedIndex = playerCategories.indexOfFirst { it.first == channel?.categoryId?.let { id -> LiveKey.Folder(id) } },
            onSelect = { index ->
                playerCategories.getOrNull(index)?.first?.let { catId ->
                    playerCategory = catId
                    scope.launch { playerChannels = tuner.channelsInCategoryForPicker(catId) }
                }
            },
            onDismiss = { sheet = null },
            dismissOnSelect = false,
        )
    }

    sheet.takeIf { !inPip && multiview == null && (it != PlayerSheet.CHANNELS || playerCategory != null) }?.let { open ->
        PlayerSheetHost(
            sheet = open,
            player = activeEngine,
            channels = if (open == PlayerSheet.CHANNELS) playerChannels else siblings,
            channelsTitle = playerCategories.firstOrNull { it.first == playerCategory }?.second,
            brightness = brightness,
            onBrightness = { setBrightness(it) },
            onPickChannel = { tuner.switchTo(it) },
            onOpenSheet = { sheet = it },
            // A live channel has nothing to match a subtitle against, and no file to hash.
            canAddSubtitles = film != null,
            onPickLocalSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
            // A phone's answer to the remote's number keys — offered on the same setting that shows
            // the numbers at all.
            onTuneToNumber = if (showChannelNumbers) tuner::tuneByNumber else null,
            catchup = catchup,
            programmeEndMs = if (channel != null) nowNext?.now?.stopMs else null,
            // Back out of a channel list returns to the categories, not out of the player's sheets.
            onDismiss = { if (open == PlayerSheet.CHANNELS) playerCategory = null else sheet = null },
        )
    }
}

/** Whatever a double tap means here: the user's own skip step, or the live rewind step of archive. */
private fun skip(tuner: LiveTuner, isLive: Boolean, forward: Boolean) {
    if (isLive) {
        tuner.skipLive(forward)
    } else {
        val step = tuner.player.seekStepMs.value
        tuner.player.seekBy(if (forward) step else -step)
    }
}

@Composable
private fun ErrorPanel(failure: PlaybackFailure, info: ErrorInfo?, onRetry: () -> Unit) {
    val res = LocalResources.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PlayerToast {
            Text(
                // Core owns the wording, so the phone and the television explain a failure alike.
                text = failure.describe { id, args -> res.getString(id, *args.toTypedArray()) },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            // Plain reason, then the media it choked on, then the engine's own line. The last two are
            // what turns "it did not play" into a report somebody can act on without a cable and adb.
            info?.reason?.let {
                Text(
                    text = stringResource(it.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            info?.spec?.let {
                Text(
                    text = it.displayText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            info?.raw?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.player_raw_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

/**
 * "Next episode in 8" — the automatic advance, with a way to take it now and a way to stop it.
 *
 * Bottom right, clear of the transport in the middle and of the tool bar along the bottom, so the two
 * buttons are reachable without the thumb crossing anything else that would react to it.
 */
@Composable
private fun NextEpisodeCard(
    seconds: Int,
    title: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerToast(modifier) {
        Text(
            text = stringResource(R.string.player_next_episode, seconds),
            style = MaterialTheme.typography.labelLarge,
            color = LocalAccentOnVideo.current,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            TextButton(onClick = onPlayNow) { Text(stringResource(R.string.player_play_now)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/** The leftovers of a gesture that has not yet added up to a whole step. */
/**
 * The volume swipe on the phone's media stream ([AudioManager.STREAM_MUSIC]): one step of the swipe
 * is one step of the system volume, and once that is at its maximum, [BOOST_STEPS] more steps raise
 * the player's own volume from 100 % to its 150 % ceiling. Coming down, the boost goes first.
 */
private class SystemVolumeSwipe(context: android.content.Context, private val engine: tv.own.owntv.player.PlaybackEngine) {
    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private val stream = android.media.AudioManager.STREAM_MUSIC
    private val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    val steps: Float get() = (max + BOOST_STEPS).toFloat()

    fun step(up: Boolean) {
        val level = audio.getStreamVolume(stream)
        val player = engine.volume.value
        if (up) {
            if (level < max) audio.setStreamVolume(stream, level + 1, 0)
            else if (player < MAX_PERCENT) engine.adjustVolumeByUser(minOf(BOOST_STEP_PERCENT, MAX_PERCENT - player))
        } else {
            if (player > FULL_PERCENT) engine.adjustVolumeByUser(-minOf(BOOST_STEP_PERCENT, player - FULL_PERCENT))
            else if (level > 0) audio.setStreamVolume(stream, level - 1, 0)
        }
    }

    /** What the readout shows: the boost while there is one, otherwise the phone's level. */
    fun percent(): Int {
        val player = engine.volume.value
        return if (player > FULL_PERCENT) player else audio.getStreamVolume(stream) * FULL_PERCENT / max
    }

    private companion object {
        const val FULL_PERCENT = 100
        const val MAX_PERCENT = 150
        const val BOOST_STEP_PERCENT = 10
        const val BOOST_STEPS = (MAX_PERCENT - FULL_PERCENT) / BOOST_STEP_PERCENT
    }
}

private class Carry {
    var scrub = 0f
    var volume = 0f
    var speedBefore = 1.0
}

/** How long a live stall lasts before the player says it is reconnecting (the TV's value). */
private const val RECONNECT_NOTICE_AFTER_MS = 10_000L
