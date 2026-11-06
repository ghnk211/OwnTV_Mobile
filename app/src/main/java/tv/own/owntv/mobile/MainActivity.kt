package tv.own.owntv.mobile

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.playback.PipController
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.components.MobileSheetHost
import tv.own.owntv.mobile.ui.shell.MobileShell
import tv.own.owntv.mobile.ui.theme.GlassBackdropRoot
import tv.own.owntv.mobile.ui.theme.MobileTheme

/**
 * The single activity the whole app runs in.
 *
 * A `FragmentActivity` rather than a bare `ComponentActivity` for exactly one reason: the Cast
 * button's device chooser is a dialog fragment, and it looks for a `FragmentManager` on whatever
 * activity hosts it. Nothing in this app draws a fragment; this is the base class the platform's own
 * cast picker requires in order to open at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class MainActivity : FragmentActivity() {

    private val tuner: LiveTuner by inject()
    private val pip: PipController by inject()
    private val cast: CastController by inject()
    private val localeStore: LocaleStore by inject()
    private val settings: SettingsRepository by inject()
    private val autoRefresh: tv.own.owntv.core.sync.AutoRefresh by inject()
    private val vodTuner: tv.own.owntv.mobile.ui.screens.library.VodTuner by inject()
    private val relock: tv.own.owntv.core.categorylock.CategoryRelock by inject()
    private val sealedHost: tv.own.owntv.mobile.ui.categorylock.SealedPlaybackHost by inject()

    /**
     * The engine actually holding the stream — mpv for a film, a download or a recording, ExoPlayer
     * for a live channel, which is now the default there.
     *
     * This used to be `tuner.player`, which is mpv and mpv alone. mpv is *stopped* while a live
     * channel plays, so everything below reported "nothing is playing" for the whole of Live TV: the
     * screen was allowed to sleep mid-match, the little floating window never opened on Home, and
     * the window's own transport buttons drove an idle engine. Read at the moment of use, because a
     * lifecycle callback has no time to wait on a flow — and `currentEngine` is the answer now,
     * where the flow's value can still be a frame behind the engine that was just started.
     */
    private val engine get() = tuner.currentEngine

    /** Both read on a lifecycle callback, where there is no time to suspend on a preference. */
    private var pipEnabled = true
    private var backgroundPlayback = true
    private var audioOnScreenOff = true
    private var pausedForBackground = false

    /** Set when *this* class dropped the picture on the way off screen, so returning restores it —
     *  and a sound-only mode the user chose themselves is left exactly as they left it. */
    private var droppedVideoForBackground = false

    /** Set the moment the floating window opens, and cleared by whichever comes first: coming back to
     *  full screen, or the window being closed — see [onPictureInPictureModeChanged]. */
    private var wasInPipWindow = false

    /** The result is deliberately ignored: refusing only costs the user the lockscreen controls, and
     *  playback must not depend on it. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** The PiP window's own buttons. Registered only while the window is up. */
    private val pipActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(EXTRA_PIP_ACTION)) {
                PIP_TOGGLE -> engine.togglePlayPause()
                PIP_BACK -> engine.seekBy(-PIP_SEEK_MS)
                PIP_FORWARD -> engine.seekBy(PIP_SEEK_MS)
            }
        }
    }

    /**
     * The chosen app language, on the Activity's own resources — the screen reads its strings from here, not
     * from the Application's. Without it a language picked in the app showed again only until the next cold
     * start, then the phone's own language came back while Settings still named the chosen one (the TV wraps
     * its Activity the same way).
     *
     * An override of the locales alone, not the TV's wrapped context: that context is a frozen copy of the
     * whole configuration, and this Activity handles rotation itself, so after turning the phone back
     * upright the shell still read the landscape width and kept the side rail.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        val tag = tv.own.owntv.mobile.i18n.AppLocales.normalize(LocaleStore.from(newBase).readBlocking())
        val locales = AppLocale.wrap(newBase, tag).resources.configuration.locales
        applyOverrideConfiguration(android.content.res.Configuration().apply { setLocales(locales) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch screen (the icon colour's flip card) must be installed before super.onCreate;
        // it then swaps the activity to Theme.OwnTVMobile via postSplashScreenTheme.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askForNotifications()
        readOpenPlayerRequest(intent)
        // The buttons say Play or Pause depending on what is happening, so they are rebuilt whenever
        // that changes — a PiP window whose button lies is worse than one with no buttons.
        lifecycleScope.launch {
            // Whichever engine has the stream, and re-subscribed when that changes: a handover from
            // ExoPlayer to mpv mid-channel would otherwise leave the window watching a flow that has
            // stopped moving.
            tuner.activeEngine
                .flatMapLatest { it.isPlaying }
                .collectLatest { if (pip.inPip.value) applyPipParams() }
        }
        lifecycleScope.launch { settings.pipEnabled.collect { pipEnabled = it } }
        lifecycleScope.launch { settings.backgroundPlayback.collect { backgroundPlayback = it } }
        lifecycleScope.launch { settings.audioOnScreenOff.collect { audioOnScreenOff = it } }
        keepScreenOnWhileThereIsAPicture()
        // Category locks: leaving the app or changing profile closes what the PIN opened, and stops
        // what plays from a category that is now sealed. The tuners outlive this activity, so does this.
        relock.guard = tv.own.owntv.mobile.ui.categorylock.sealedPlaybackGuard(tuner, vodTuner, sealedHost)
        lifecycleScope.launch { settings.activeProfileId.collect(relock::profileChanged) }
        // "Refresh on startup" and the interval modes of the Auto refresh settings, once a profile is
        // active (straight away, or as soon as first-run setup has made one).
        lifecycleScope.launch {
            settings.activeProfileId.first { it >= 0 }
            autoRefresh.check(includeStartup = true)
        }
        setContent {
            tv.own.owntv.mobile.ui.LocalizedContent(localeStore) {
            MobileTheme {
                // The wallpaper and its blurred copy sit outside the shell, so the frost every glass
                // panel samples is one image for the whole app rather than one per panel.
                GlassBackdropRoot {
                    // Inside the backdrop, because a sheet's whole reason for living in this window
                    // is that it can frost the same wallpaper everything else frosts.
                    MobileSheetHost {
                        // Width, not device type: a phone in landscape and a tablet in split-screen
                        // are the same problem, and the configuration re-reads itself on every
                        // rotation and resize.
                        MobileShell(windowWidthDp = LocalConfiguration.current.screenWidthDp)
                    }
                }
            }
            }
        }
    }

    /**
     * Leaving the app while the picture is full screen takes the picture along, in the system's own
     * floating window.
     *
     * That window goes over *other* apps, so it belongs to leaving the app and to nothing else. The
     * button in the player's tools is the app's own mini player, which stays inside it. With the
     * setting off, or with no picture to carry, Home leaves the sound and the notification instead.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipEnabled) return
        if (!pip.playerOnScreen.value) return
        // Nothing to float while casting: the picture is on the television, and a little window here
        // would show a black rectangle with the receiver's transport buttons under it.
        if (cast.engine.value != null) return
        val playing = engine
        if (!playing.isPlaying.value || playing.audioOnly.value || playing.audioOnlyMedia.value) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            wasInPipWindow = true
            // The floating window goes over other apps: the app has left, so the categories the PIN
            // opened close, and a channel or film from one stops.
            relock.leftApp()
            ContextCompat.registerReceiver(
                this,
                pipActions,
                IntentFilter(ACTION_PIP),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            runCatching { unregisterReceiver(pipActions) }
            // Deliberately no decision here. Leaving the window means one of two opposite things —
            // tapped, to come back to full screen, or closed — and this callback cannot tell them
            // apart. Nor can it be relied on to arrive first: on some builds it lands after onStop,
            // and a flag set here would then be read after the moment it was meant to answer. So the
            // two lifecycle callbacks decide, in whichever order they come: onResume means tapped,
            // onStop means closed.
            if (isFinishing) {
                wasInPipWindow = false
                closedThePipWindow()
            }
        }
    }

    /**
     * The X on the PiP window: the picture ends, the session does not.
     *
     * Closing the window says "off my screen", not "forget where I was". So the video stops, the sound
     * stops with it, and what stays behind is the notification and the quick-panel controls — press
     * play there and it comes back as sound only, tap the notification and the full player opens again
     * at the same place. Live channels included: the user closed a window, and stopping their
     * subscription's stream is a bigger thing than the button they pressed.
     */
    private fun closedThePipWindow() {
        if (!tuner.hasStream) return
        val playing = engine
        // Sound-only first, then stop. The window is gone, so there is no surface and no reason to keep
        // a video decoder alive — and it settles what the play button in the quick panel will do next.
        //
        // Flagged as *this* class's doing, exactly as the screen-off path flags it, so coming back
        // undoes it. Without that, tapping the quick-panel controls opened the app with sound and a
        // black screen — and there is no full-screen sound-only mode in this app.
        if (!playing.audioOnly.value) {
            droppedVideoForBackground = true
            playing.enterAudioOnly()
        }
        if (playing.isPlaying.value) playing.togglePlayPause()
    }

    /** The playback notification was tapped. The player is a navigation destination, so the shell
     *  does the moving — see [PipController.openPlayerRequested]. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenPlayerRequest(intent)
    }

    private fun readOpenPlayerRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            pip.openPlayerRequested.value = true
        }
    }

    /**
     * Off screen, drop the video decoder and keep the sound. A phone spends most of its life with the
     * screen off, and decoding frames nobody can see is the single most expensive thing this app can
     * do to a battery.
     *
     * Not on a rotation (the activity is only being rebuilt) and not in Picture-in-Picture, where the
     * window is still on screen.
     *
     * With background playback turned off the sound stops too — the stream is only paused, so coming
     * back resumes it where it was rather than reconnecting from the start.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // The relock first: it reads what is playing before anything below pauses or drops it.
        relock.leftApp()
        // Stopped after the floating window was up, without ever resuming in between: the window was
        // closed. That is its own answer, and not the background-playback question below.
        if (wasInPipWindow) {
            wasInPipWindow = false
            closedThePipWindow()
            return
        }
        if (pip.inPip.value) return
        // A preview is a glance, not watching: leaving the app ends it rather than playing on unseen.
        if (tuner.previewing.value) return tuner.stop()
        if (!tuner.hasStream) return
        val playing = engine
        if (!backgroundPlayback) {
            // Remembered, so a stream the user paused themselves is not resumed for them on return.
            if (playing.isPlaying.value) {
                pausedForBackground = true
                playing.togglePlayPause()
            }
            return
        }
        // Only when the user wants the picture dropped. With that switch off the stream keeps
        // decoding video nobody is looking at, which is their choice to make and costs battery.
        if (!audioOnScreenOff) return
        // Not if they are already in sound-only mode on purpose — coming back must not hand them a
        // picture they switched off themselves.
        if (playing.audioOnly.value) return
        droppedVideoForBackground = true
        playing.enterAudioOnly()
    }

    /** Back on screen at full size, so the window was tapped rather than closed. */
    override fun onResume() {
        super.onResume()
        wasInPipWindow = false
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground: interval refreshes only; core throttles a quick in-and-out.
        lifecycleScope.launch { autoRefresh.check(includeStartup = false) }
        // Not before the relock check is done: what it found sealed must not come back first.
        relock.afterSealCheck {
            if (droppedVideoForBackground) {
                droppedVideoForBackground = false
                engine.exitAudioOnly()
            }
            if (pausedForBackground) {
                pausedForBackground = false
                if (tuner.hasStream && !engine.isPlaying.value) engine.togglePlayPause()
            }
        }
    }

    /**
     * Nobody touches the screen during a film, and the phone's own timeout does not know that.
     *
     * Held on the window, by the activity, once — not by the composable that draws the picture. There
     * are three of those (Live TV's preview, the full screen player, the mini player) and they hand
     * the stream to one another: the arriving one would set the flag and the leaving one would then
     * clear it, which is exactly why the screen still went dark mid-film.
     */
    private fun keepScreenOnWhileThereIsAPicture() = lifecycleScope.launch {
        // Asked of whichever engine holds the stream, and re-asked when that changes. Read from mpv
        // alone, this stayed false for the whole of a live channel — so the screen dimmed and locked
        // in the middle of a match, exactly as it would while reading a page.
        // Buffering counts as playing: the screen timeout runs from the last touch, so letting go for a
        // one-second rebuffer deep into a film turned the screen off at once.
        tuner.activeEngine.flatMapLatest { playing ->
            combine(
                playing.isPlaying, playing.buffering, playing.error, playing.audioOnly, playing.audioOnlyMedia,
            ) { on, loading, failed, off, radio ->
                (on || loading) && failed == null && !off && !radio
            }
        }.distinctUntilChanged().collect { keep ->
            if (keep) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun applyPipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val ctx = AppLocale.wrap(this, localeStore.currentTag.value)
        val playing = engine.isPlaying.value
        val builder = PictureInPictureParams.Builder()
            // The picture's own shape, not a fixed 16:9. A 2.35:1 film in a 16:9 window is a small
            // picture with black bands above and below it, inside a window that is already tiny.
            // Clamped to what the platform accepts — it throws outside roughly 1:2.39 to 2.39:1 —
            // and expressed in thousandths, because Rational takes integers.
            .setAspectRatio(pipAspect())
            .setActions(
                listOf(
                    pipAction(
                        android.R.drawable.ic_media_rew,
                        ctx.getString(R.string.player_skip_back),
                        PIP_BACK,
                    ),
                    pipAction(
                        if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        ctx.getString(R.string.settings_remote_action_play_pause),
                        PIP_TOGGLE,
                    ),
                    pipAction(
                        android.R.drawable.ic_media_ff,
                        ctx.getString(R.string.player_skip_forward),
                        PIP_FORWARD,
                    ),
                ),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(false)
        // Where the picture is right now, so the window grows out of it instead of the whole screen
        // cross-fading into a rectangle in the corner. Absent — nothing is drawing a picture — the
        // system falls back to that cross-fade, which is what always happened before.
        pip.videoBounds?.takeIf { !it.isEmpty }?.let { builder.setSourceRectHint(it) }
        return builder.build()
    }

    /**
     * The little window's shape: the picture's own, clamped to what the platform will accept.
     *
     * `setAspectRatio` throws outside roughly 1:2.39 … 2.39:1, and a provider is entirely capable of
     * reporting something absurd, so the value is bounded before it is handed over. Thousandths
     * because [Rational] takes two integers, and a thousandth is finer than any panel can show.
     */
    private fun pipAspect(): Rational {
        val aspect = (tuner.videoAspect ?: DEFAULT_ASPECT)
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_PIP_ASPECT, MAX_PIP_ASPECT)
            ?: DEFAULT_ASPECT
        return Rational((aspect * 1000).toInt(), 1000)
    }

    private fun pipAction(icon: Int, label: String, action: String): RemoteAction = RemoteAction(
        Icon.createWithResource(this, icon),
        label,
        label,
        PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** Set on the playback notification's own intent: "open the player, not wherever the app was". */
        const val EXTRA_OPEN_PLAYER = "open_player"

        private const val ACTION_PIP = "tv.own.owntv.mobile.PIP"
        private const val EXTRA_PIP_ACTION = "pip_action"
        private const val PIP_TOGGLE = "toggle"
        private const val PIP_BACK = "back"
        private const val PIP_FORWARD = "forward"

        /** Fixed, not the user's seek step: three buttons is all a PiP window has room for, and a
         *  window is not where anyone sets up a 90-second jump. */
        private const val PIP_SEEK_MS = 10_000L

        /** What a stream with no shape of its own yet gets, and what everything used to get. */
        private const val DEFAULT_ASPECT = 16f / 9f

        /**
         * The shapes a Picture-in-Picture window may take — deliberately INSIDE the platform's own
         * limits rather than on them.
         *
         * It rejects anything outside roughly 1:2.39 … 2.39:1. Clamping to exactly 1/2.39 and then
         * rounding down to thousandths produces 0.418, which is a hair *under* the limit and is
         * refused — and because entering the window is wrapped in `runCatching`, the refusal would
         * not crash: it would silently never open the window at all, which is worse than the fixed
         * 16:9 this replaced. A little margin costs nothing; no real stream sits on that edge.
         */
        private const val MIN_PIP_ASPECT = 0.43f
        private const val MAX_PIP_ASPECT = 2.35f
    }
}
