package tv.own.owntv.mobile.tvbox.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.categorylock.PinAttemptLimiter
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.di.dataModule
import tv.own.owntv.core.settings.CustomizePinSettings
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.tvbox.CatvodHost
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryLock
import tv.own.owntv.mobile.tvbox.category.TvboxCategoryPolicy
import tv.own.owntv.mobile.tvbox.category.TvboxSealedPlaybackGuard
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.data.prefs.TvboxPrefs
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.ParseRepository
import tv.own.owntv.mobile.tvbox.engine.sniff.SniffEngine
import tv.own.owntv.mobile.tvbox.engine.cms.CmsEngine
import tv.own.owntv.mobile.tvbox.engine.jar.JarEngine
import tv.own.owntv.mobile.tvbox.engine.js.JsEngine
import tv.own.owntv.mobile.tvbox.engine.js.JsLocalStore
import tv.own.owntv.mobile.tvbox.engine.js.JsModuleCache
import tv.own.owntv.mobile.tvbox.engine.js.JsWarmUp
import tv.own.owntv.mobile.tvbox.util.FileUtil
import tv.own.owntv.mobile.tvbox.net.AddressFetcher
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.net.OkHttpProvider
import tv.own.owntv.mobile.tvbox.play.TvboxPlayResolver
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.ui.screens.vod.TvboxConfigViewModel
import tv.own.owntv.mobile.ui.screens.vod.TvboxDetailViewModel
import tv.own.owntv.mobile.ui.screens.vod.TvboxHomeViewModel

/**
 * Koin wiring for the TVbox VOD chain (plan §6 row 4).
 *
 * Deliberately `single` for everything stateful: the config repository, the engine pool and the
 * tuners outlive any screen, exactly like the Core-side singletons they sit beside.
 */
val tvboxModule = module {

    single { TvboxPrefs(androidContext()) }
    single {
        tv.own.owntv.mobile.tvbox.repository.TvboxSourceProvisioner(
            context = androidContext(),
            sourceDao = get(),
            profileDao = get(),
            configRepository = get(),
        )
    }
    single {
        tv.own.owntv.mobile.tvbox.index.TvboxIndexer(
            configRepository = get(),
            writer = get(),
            cursorDao = get(),
            categoryDao = get(),
        )
    }
    single {
        tv.own.owntv.mobile.tvbox.index.TvboxUserData(
            favoriteDao = get(),
            progressDao = get(),
            movieDao = get(),
            userDataWriter = get(),
        )
    }
    single {
        tv.own.owntv.mobile.tvbox.search.TvboxOnlineSearch(
            sourceRepository = get(),
            configRepository = get(),
            policy = get(),
            profileDao = get(),
            provisioner = get(),
            indexer = get(),
        )
    }
    single {
        tv.own.owntv.mobile.tvbox.index.TvboxRemoteMediatorFactory(
            categoryDao = get(),
            sourceDao = get(),
            configRepository = get(),
            sourceRepository = get(),
            indexer = get(),
            cursorDao = get(),
        )
    }

    // Network: fresh chains (main / download / jar) with the TVbox interceptors.
    //
    // Deliberately NOT registered as `single<OkHttpClient>`: Koin resolves duplicates
    // last-one-wins, and tvboxModule loads after coreModule — a same-key definition here would
    // silently replace the CORE client for the whole app (image loading, sync, backup all read
    // `get<OkHttpClient>()`), which the plan forbids ("派生不改 Core 单例"). The TVbox chains are
    // reached through OkHttpProvider / NetApi's providers instead.
    single {
        NetApi(
            clientProvider = { OkHttpProvider.get() },
            downloadProvider = { OkHttpProvider.download() },
        )
    }
    single { AddressFetcher(androidContext(), get(), get<TvboxPrefs>()) }

    single { TvboxConfigRepository(androidContext(), get(), get(), get()) }

    // Engines: CMS first (its api-suffix check yields .js sites to nothing else in this port —
    // the JS engine is P5), then jar.
    single<CmsEngine> { CmsEngine(get()) }
    single<JarEngine> {
        val config = get<TvboxConfigRepository>()
        JarEngine(
            context = androidContext(),
            spiderJarDirProvider = { config.ensureSpiderDir() },
            spiderMd5Provider = { config.spiderMd5s() },
            siteJarProvider = { site: SiteConfig -> config.ensureSiteJar(site) },
        )
    }
    // JS spider runtime: one local KV store, one module/bytecode cache under files/tvbox/js.
    single { JsLocalStore(androidContext()) }
    single { JsModuleCache(FileUtil.jsDir(androidContext())) }
    single<JsEngine> {
        val config = get<TvboxConfigRepository>()
        JsEngine(
            net = get(),
            store = get(),
            cache = get(),
            addressFetcher = get(),
            activeSourceUrl = { config.activeSourceUrl() },
        )
    }
    single {
        JsWarmUp(
            engine = get<JsEngine>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
    single<SourceRepository> {
        val config = get<TvboxConfigRepository>()
        // Python collection sources: PyEngineHolder is compiled per source set — the real engine
        // in the full build, a null-returning stub in the slim build. `main` never names PyEngine,
        // so the slim build has no Chaquopy classes to resolve.
        val pyEngine = PyEngineHolder.create(androidContext()) { site: SiteConfig ->
            config.ensurePyFile(site)
        }
        SourceRepository(
            configRepository = config,
            engines = listOfNotNull<Engine>(
                get<CmsEngine>(),
                get<JarEngine>(),
                get<JsEngine>(),
                pyEngine,
            ),
            jsWarmUp = get(),
        )
    }

    single { ParseRepository(get()) }
    single { SniffEngine(androidContext()) }

    single { TvboxCategoryPolicy(kidsStrict = { get<TvboxPrefs>().kidsStrict }) }
    single {
        TvboxCategoryLock(
            store = tv.own.owntv.mobile.tvbox.category.CoreLockStore(get()),
            unlocks = get<CategoryUnlockSession>(),
            customizePinSettings = get<SettingsRepository>() as CustomizePinSettings,
            pinLimiter = get<PinAttemptLimiter>(),
            keys = tv.own.owntv.mobile.tvbox.category.CoreLockKeys(get()),
        )
    }
    single { TvboxPlayResolver(get(), get(), get(), get(), get<ProfileDao>(), get(), get(), get()) }
    single {
        // Core's DownloadSourceResolver SPI: the App registers the TVBOX one at process start.
        tv.own.owntv.mobile.tvbox.download.TvboxDownloadSourceResolver(
            configRepository = get(),
            resolver = get(),
            runtime = get(),
            movieDao = get(),
        )
    }
    single {
        tv.own.owntv.mobile.tvbox.play.TvboxPlaybackRouter(
            sourceDao = get(),
            configRepository = get(),
            sourceRepository = get(),
            resolver = get(),
            progressDao = get(),
            categoryDao = get(),
        )
    }
    single {
        TvboxSealedPlaybackGuard(
            vodTuner = get<VodTuner>(),
            lock = get(),
            settings = get(),
            unlocks = get(),
            profileDao = get<ProfileDao>(),
            provisioner = get(),
        )
    }

    // Catvod stub host: context + jar network stack, set before any jar can load.
    single { CatvodHostInit(androidContext()) }

    // Config lifecycle hooks into engines/sniff/network; resolved eagerly on app start.
    single { TvboxRuntime(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    viewModelOf(::TvboxConfigViewModel)
    viewModelOf(::TvboxHomeViewModel)
    viewModelOf(::TvboxDetailViewModel)
}

/** Eager CatvodHost.init — resolved once from [OwnTVMobileApp] before anything loads a jar. */
class CatvodHostInit(private val context: Context) {
    init {
        CatvodHost.init(context)
    }
}
