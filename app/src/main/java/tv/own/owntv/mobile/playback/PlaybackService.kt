package tv.own.owntv.mobile.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.mobile.MainActivity
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.PlaybackSession

/**
 * The notification that keeps playing when the app is not on screen.
 *
 * A phone takes the app off screen constantly — the home button, another app, the lock screen — and
 * without a foreground service the system is entitled to kill the process mid-film. This is what makes
 * background audio legal, and what puts the controls on the lock screen: the notification is hung on
 * core's own [PlaybackSession] token, so the transport buttons, the metadata and the seek bar are the
 * ones the session already publishes rather than a second copy that can disagree with it.
 *
 * Started when a stream starts and stopped when playback ends, both from [LiveTuner] — the one object
 * that knows whether anything is playing at all.
 *
 * The platform's own `Notification.MediaStyle` is used rather than the AndroidX one: it takes the
 * `MediaSession.Token` this app already has, while the compat version needs a `MediaSessionCompat`
 * and a dependency on `androidx.media` to convert one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackService : Service() {

    private val cast: CastController by inject()
    private val tuner: LiveTuner by inject()
    private val session: PlaybackSession by inject()
    private val localeStore: LocaleStore by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Artwork is fetched over the network, so it is kept against the URL it came from. */
    private var artUrl: String? = null
    private var art: Bitmap? = null
    private var lastChannelName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Post once, synchronously, before anything can await a network image: a service that has not
        // called startForeground() within a few seconds of being started is killed outright.
        val local = tuner.currentEngine
        startForeground(build(Shown(local.currentMeta.value, local.isPlaying.value, local.audioOnly.value, local.isLiveContent)))
        // Whichever engine has the stream. While casting the notification must read the receiver, or
        // it would sit there saying paused at 00:00 for something playing perfectly well next door.
        //
        // And on this device it is the ACTIVE engine, not mpv: live plays on ExoPlayer by default,
        // so reading mpv left the notification permanently showing "paused", with no channel name,
        // for the whole of Live TV.
        cast.engine
            .flatMapLatest { remote ->
                if (remote == null) {
                    tuner.activeEngine.flatMapLatest { here ->
                        combine(here.currentMeta, here.isPlaying, here.audioOnly) { meta, playing, audioOnly ->
                            Shown(meta, playing, audioOnly, here.isLiveContent)
                        }
                    }
                } else {
                    combine(remote.currentMeta, remote.isPlaying, cast.deviceName) { meta, playing, device ->
                        Shown(meta, playing, audioOnly = false, live = remote.isLiveContent, castingTo = device)
                    }
                }
            }
            .distinctUntilChanged()
            .onEach { shown ->
                loadArt(shown.meta.logoUrl)
                notify(build(shown))
            }
            .launchIn(scope)
    }

    /** What the notification is currently saying, from whichever engine is playing. */
    private data class Shown(
        val meta: MediaMeta,
        val playing: Boolean,
        val audioOnly: Boolean,
        /** Live has no ±10 s to skip — the buttons would only nudge the stream off the live edge. */
        val live: Boolean,
        /** The receiver's name while casting, null while playing here. */
        val castingTo: String? = null,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The buttons drive the receiver when there is one: they are the same three transport
        // controls, and which box is decoding is not something the user should have to think about.
        val remote = cast.engine.value
        // The local fallback is the ACTIVE engine, not mpv — otherwise every one of these buttons
        // was a no-op throughout a live channel, on the notification and on the lock screen alike.
        val local = tuner.currentEngine
        when (intent?.action) {
            ACTION_TOGGLE -> remote?.togglePlayPause() ?: local.togglePlayPause()
            ACTION_BACK -> remote?.seekBy(-SEEK_MS) ?: local.seekBy(-SEEK_MS)
            ACTION_FORWARD -> remote?.seekBy(SEEK_MS) ?: local.seekBy(SEEK_MS)
            ACTION_AUDIO_ONLY -> local.enterAudioOnly()
            ACTION_STOP -> tuner.stop() // which stops this service in turn
        }
        // Not sticky: a service the system restarts with no stream behind it would post a notification
        // for nothing playing, with no way for the user to make it go away.
        return START_NOT_STICKY
    }

    /** Swiping the app out of Recents is not "keep playing in the background" — it is "close it". */
    override fun onTaskRemoved(rootIntent: Intent?) {
        tuner.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The app's own language, which is not necessarily the phone's — the same rule core's download
     *  notification follows. */
    private fun localized(): Context =
        AppLocale.wrap(this, tv.own.owntv.mobile.i18n.AppLocales.normalize(localeStore.currentTag.value))

    private fun build(shown: Shown): Notification {
        val (meta, playing, audioOnly, live, castingTo) = shown
        val ctx = localized()
        ensureChannel(ctx)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(meta.title ?: ctx.getString(R.string.app_name))
            // Where it is playing beats what is on next: the second line is the one place outside the
            // app that can say the sound is coming out of another room.
            .setContentText(
                castingTo?.let { ctx.getString(R.string.player_cast_playing_on, it) } ?: meta.subtitle.orEmpty(),
            )
            // Tapping the notification means "show me what I am listening to", so it opens the player
            // rather than wherever the app happened to be left — and after the PiP window was closed
            // the activity is gone entirely, so there is no "wherever" to go back to.
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    // The enabled icon colour's activity: with another colour chosen, MainActivity itself
                    // is disabled and an Intent to it would open nothing.
                    Intent().setComponent(tv.own.owntv.core.brand.AppIconSwitcher.launchComponent(this))
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (!live) {
            builder.addAction(
                action(
                    android.R.drawable.ic_media_rew,
                    ctx.getString(R.string.player_skip_back),
                    ACTION_BACK,
                ),
            )
        }
        builder.addAction(
            action(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                ctx.getString(R.string.settings_remote_action_play_pause),
                ACTION_TOGGLE,
            ),
        )
        if (!live) {
            builder.addAction(
                action(
                    android.R.drawable.ic_media_ff,
                    ctx.getString(R.string.player_skip_forward),
                    ACTION_FORWARD,
                ),
            )
        }
        // Nothing to offer once the picture is already off, and a chip that does nothing is worse
        // than one fewer chip. Casting is the same case: the picture is on the television, and there
        // is no local video output to drop.
        if (!audioOnly && castingTo == null) {
            builder.addAction(
                action(
                    R.drawable.ic_audio_only,
                    ctx.getString(R.string.player_tool_audio_only),
                    ACTION_AUDIO_ONLY,
                ),
            )
        }
        builder.addAction(
            action(
                android.R.drawable.ic_menu_close_clear_cancel,
                ctx.getString(R.string.content_close),
                ACTION_STOP,
            ),
        )
        art?.let { builder.setLargeIcon(it) }
        // The token is what the lock screen reads: title, position and the seek bar all come from the
        // session, so they cannot drift from what the player is actually doing.
        // The collapsed row holds three: skip back, play/pause, skip forward — on live, play/pause alone.
        val style = Notification.MediaStyle()
            .apply { if (live) setShowActionsInCompactView(0) else setShowActionsInCompactView(0, 1, 2) }
        session.token?.let { style.setMediaSession(it) }
        return builder.setStyle(style).build()
    }

    private fun action(icon: Int, label: String, intentAction: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            label,
            PendingIntent.getService(
                this,
                intentAction.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(intentAction),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun startForeground(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun notify(notification: Notification) {
        // Refusing POST_NOTIFICATIONS silences the notification but must never take playback with it.
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun loadArt(url: String?) {
        if (url == artUrl) return
        artUrl = url
        art = if (url.isNullOrBlank()) {
            null
        } else {
            val result = runCatching {
                SingletonImageLoader.get(this).execute(ImageRequest.Builder(this).data(url).size(ART_SIZE_PX).build())
            }.getOrNull()
            (result as? SuccessResult)?.image?.toBitmap()
        }
    }

    private fun ensureChannel(localized: Context) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val name = localized.getString(R.string.settings_playback_group)
        // Re-created when the app's language changes, so the channel is never left in the old one.
        if (name == lastChannelName) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        lastChannelName = name
    }

    companion object {
        private const val CHANNEL_ID = "owntv_playback"
        private const val NOTIFICATION_ID = 4301

        /** The large icon is shown at most ~128 dp; decoding a poster at full size only costs memory. */
        private const val ART_SIZE_PX = 256
        private const val ACTION_TOGGLE = "tv.own.owntv.mobile.TOGGLE"
        private const val ACTION_BACK = "tv.own.owntv.mobile.SKIP_BACK"
        private const val ACTION_FORWARD = "tv.own.owntv.mobile.SKIP_FORWARD"
        private const val ACTION_AUDIO_ONLY = "tv.own.owntv.mobile.AUDIO_ONLY"
        private const val ACTION_STOP = "tv.own.owntv.mobile.STOP"

        /** Fixed, for the same reason the Picture-in-Picture window's is: a notification chip is not
         *  where anyone configures a jump length. */
        private const val SEEK_MS = 10_000L

        /** Called when a stream starts. Safe to call again for a stream already playing. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
