package tv.own.owntv.mobile

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.util.Perf
import tv.own.owntv.core.di.coreModule
import tv.own.owntv.core.di.dataModule
import tv.own.owntv.core.di.databaseModule
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.sync.work.KoinWorkerFactory
import tv.own.owntv.mobile.tvbox.di.tvboxModule
import tv.own.owntv.core.util.CrashRecorder
import tv.own.owntv.mobile.di.downloadsModule
import tv.own.owntv.mobile.di.guideModule
import tv.own.owntv.mobile.di.homeModule
import tv.own.owntv.mobile.di.libraryModule
import tv.own.owntv.mobile.di.liveModule
import tv.own.owntv.mobile.di.playerModule
import tv.own.owntv.mobile.di.searchModule
import tv.own.owntv.mobile.di.settingsModule
import tv.own.owntv.mobile.di.shellModule
import tv.own.owntv.mobile.i18n.AppLocales
import tv.own.owntv.mobile.ui.theme.subtitleFontResource

/**
 * The mobile shell's Application. Deliberately a near-copy of the TV app's `OwnTVApp` for the parts
 * that core depends on — the hooks below are core's only way to learn things a library cannot know
 * about the app compiled around it, and core reads them from its very first line.
 */
class OwnTVMobileApp : Application(), androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setWorkerFactory(KoinWorkerFactory())
            .build()

    /**
     * Wrap the Application base with the selected locale and apply it to the process locale defaults
     * BEFORE the rest of the process starts. The tag is read synchronously from the
     * SharedPreferences-backed store, because DataStore cannot be read here.
     */
    override fun attachBaseContext(base: Context) {
        val tag = AppLocales.normalize(LocaleStore.from(base).readBlocking())
        AppLocale.applyGlobally(tag)
        super.attachBaseContext(AppLocale.wrap(base, tag))
    }

    /** Re-apply the selection after the framework's process-level reset on a configuration change. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.applyGlobally(AppLocales.normalize(LocaleStore.from(this).readBlocking()))
    }

    override fun onCreate() {
        super.onCreate()
        // "Restart now" after an icon change runs a few milliseconds in a process of its own; nothing
        // below may start there (core's AppRestartActivity).
        if (tv.own.owntv.core.brand.AppIconSwitcher.isRestartProcess(this)) return
        // Zero-point for the OwnTVPerf startup timeline (adb logcat -s OwnTVPerf), matching the TV
        // app. Without it every Perf.stamp in core is a silent no-op — which mattered more than a
        // missing timeline: core's database-open stamp is the only thing that proves Room's onOpen
        // callback actually ran under a SQLiteDriver. That callback fails *silently* when it does
        // not, taking the PRAGMAs and the schema self-heal with it, so the phone
        // had no way to detect the one failure mode that ships green.
        Perf.begin()
        // Core has its own BuildConfig and it carries none of this: a library gets no version at all,
        // and the edge key and the maintainer switch are the app's build inputs. Hand them over
        // before the first reader — CrashRecorder, two lines down.
        CoreBuildInfo.versionName = BuildConfig.VERSION_NAME
        CoreBuildInfo.versionCode = BuildConfig.VERSION_CODE
        CoreBuildInfo.edgeKey = BuildConfig.TMDB_EDGE_KEY
        CoreBuildInfo.devTools = BuildConfig.DEV_TOOLS
        CoreBuildInfo.debug = BuildConfig.DEBUG
        CoreBuildInfo.diagnosticBuild = BuildConfig.DIAGNOSTIC_BUILD
        // No LEANBACK_LAUNCHER entry, so a Watch Next row published from here would be a row nothing
        // could open. Without this, core's sync worker tried to write to the TV content provider
        // after every catalog sync — using the two permissions this app's manifest removes.
        CoreBuildInfo.tvHome = false
        // This app's own releases, not the television's. Left at its default, an update check here
        // would offer OwnTV-v*.apk — a different applicationId, which the installer refuses after
        // the whole download.
        CoreBuildInfo.releaseRepo = "ahXN00/OwnTV_Mobile"
        // Fixed for this process before any player exists: the setting applies only after a restart.
        tv.own.owntv.core.player.FfmpegAudioSetting.load(this)
        // First thing after the context exists: a crash from here on leaves a trace on disk instead
        // of dying with the process, with the playback ring attached.
        CrashRecorder.diagnostics = { tv.own.owntv.player.LiveDiagnosticsLog.snapshot() }
        CrashRecorder.install(this)
        // One tuned image pipeline for the whole app, before the first AsyncImage asks: a quarter
        // of memory for posters/logos and 256 MB of disk, so a scrolled-past grid is still warm.
        // Per-request sizes stay with the call sites (PlaybackService's 256 px notification art,
        // the hero precache); decode sampling for views comes from their measured constraints.
        SingletonImageLoader.setSafe { ctx ->
            // Images go through the app's own OkHttp client — the television's loader, ported — so
            // posters/logos get the same User-Agent, proxy, DNS, TLS and connection pool the IPTV
            // requests use (some panels reject default UAs). Two settings deliberately diverge from
            // the panel-facing client: images re-enable connection retries (a poster CDN publishes
            // both IPv6 and IPv4, and a phone that resolves IPv6 first on a broken IPv6 route needs
            // the fallback OkHttp then tries) and restore HTTP/2 negotiation (a poster grid opens
            // dozens of requests to one host at once; over HTTP/1.1 they queue behind the per-host
            // connection limit, over h2 they multiplex). The shared client keeps its HTTP/1.1 pin:
            // IPTV panels answer an HTTP/2 handshake with RST_STREAM(PROTOCOL_ERROR).
            val imageHttpClient = GlobalContext.get().get<OkHttpClient>()
                .newBuilder()
                .retryOnConnectionFailure(true)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build()
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient })) }
                .memoryCache {
                    // Kept at Coil's default quarter, not the television's tenth: batch 2 measured
                    // this warm on phone grids, and a phone's smaller posters cost less per tile
                    // than a 4K TV's.
                    MemoryCache.Builder().maxSizePercent(ctx, 0.25).build()
                }
                .diskCache {
                    DiskCache.Builder().directory(ctx.cacheDir.resolve("image_cache").toOkioPath())
                        .maxSizeBytes(256L * 1024 * 1024).build()
                }
                // Opaque poster art doesn't need an alpha channel; RGB_565 halves bitmap memory
                // for every cached poster and logo.
                .allowRgb565(true)
                // Tiles flashing in one by one is what a grid without this does as bytes arrive.
                .crossfade(true)
                .build()
        }
        // Core learns a panel's session limit while syncing; the engine is what acts on it. Registered
        // before Koin so the very first source flow already reaches the player.
        tv.own.owntv.core.player.LiveSessionLimit.report =
            tv.own.owntv.player.LiveStreamQuirks::rememberSessionLimit
        // Which font file the engine hands to libass, so a subtitle is drawn in the face the user
        // chose. This used to be left unset, on the grounds that the app had no fonts of its own —
        // true when the line was written, and untrue since the app shipped all six families as
        // res/font files. The result was a Subtitle font setting that reached the app's own subtitle
        // layer and nothing else.
        tv.own.owntv.player.SubtitleFontAssets.resourceOf = { it.subtitleFontResource }
        // The eight icon colours are MainActivity + a suffix (see MainActivityColours.kt); core switches them.
        tv.own.owntv.core.brand.AppIconSwitcher.mainActivityClass = MainActivity::class.java.name
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@OwnTVMobileApp)
            modules(
                coreModule, databaseModule, dataModule, playerModule, shellModule,
                liveModule, libraryModule, guideModule, homeModule, searchModule,
                downloadsModule, settingsModule, tvboxModule,
            )
        }
        // The language whitelist enforced on the stored value, not only on the read. An install upgraded
        // from a build that offered 26 languages still carries a tag this APK has no strings for, and a
        // restore or a companion write could put one back later; collapsing it here means no reader has
        // to know the list. Idempotent — the write re-emits a tag that already normalises to itself.
        appScope.launch {
            val localeStore = GlobalContext.get().get<LocaleStore>()
            localeStore.currentTag.collect { raw ->
                val tag = AppLocales.normalize(raw)
                if (tag != raw) runCatching { localeStore.set(tag) }
            }
        }
        // A chosen icon colour ("Later", the first-run pick, a restore) reaches the launcher when the app
        // is next in the background, never while it is on screen.
        tv.own.owntv.core.brand.AppIconSwitcher.start(this, org.koin.core.context.GlobalContext.get().get())
        // Diagnostics switch and the one-shot settings migrations.
        tv.own.owntv.player.PlaybackStartup.start(
            context = this,
            scope = appScope,
            settings = org.koin.core.context.GlobalContext.get().get(),
        )
        // "Use this guide's logos": never started on the phone before, so the toggle did nothing here.
        // Reads no EPG data at all while no guide source has it on.
        tv.own.owntv.core.epg.EpgLogoStore.start(
            scope = appScope,
            settings = org.koin.core.context.GlobalContext.get().get(),
            epgDao = org.koin.core.context.GlobalContext.get().get(),
            customize = org.koin.core.context.GlobalContext.get().get(),
        )
        // Programme reminders: a notification while no screen is open (and for Notify only). Also what
        // creates core's ReminderManager at launch, which re-arms every reminder after an update.
        tv.own.owntv.mobile.reminder.ReminderNotifier.start(
            app = this,
            scope = appScope,
            reminders = org.koin.core.context.GlobalContext.get().get(),
            settings = org.koin.core.context.GlobalContext.get().get(),
            customize = org.koin.core.context.GlobalContext.get().get(),
            channels = org.koin.core.context.GlobalContext.get().get(),
        )
        // A new user starts on Stage with glass on; an existing install keeps its look. Then the slim
        // settings of this release are applied: fixed playback caps, and the appearance, sound,
        // playback-behaviour and watching/recording items whose rows are gone from Settings reset to
        // their factory values. All are idempotent and only write what differs, so a failed read
        // retries on the next cold start.
        appScope.launch {
            val settings = org.koin.core.context.GlobalContext.get().get<tv.own.owntv.core.settings.SettingsRepository>()
            runCatching { settings.applyPhoneStageDefaults(tv.own.owntv.mobile.ui.screens.settings.PHONE_GLASS_DEFAULT_PCT) }
            tv.own.owntv.mobile.playback.applySlimPlaybackCaps(settings)
            tv.own.owntv.mobile.playback.applySlimLiveDefaults(settings)
            tv.own.owntv.mobile.playback.applySlimAppearanceDefaults(settings)
            tv.own.owntv.mobile.playback.applySlimSoundDefaults(
                settings = settings,
                playbackPrefs = org.koin.core.context.GlobalContext.get().get(),
                context = this@OwnTVMobileApp,
            )
            tv.own.owntv.mobile.playback.applySlimPlaybackBehaviorDefaults(settings)
            tv.own.owntv.mobile.playback.applySlimWatchingDefaults(settings)
        }
    }

    /** Process-long scope for [tv.own.owntv.player.PlaybackStartup]; never cancelled. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** Memory pressure reaches every engine; which levels count is core's call (see PlaybackEngines). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<tv.own.owntv.player.PlaybackEngines>()?.onTrimMemory(level)
        }
    }
}
