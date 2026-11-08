package tv.own.owntv.mobile.tvbox.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
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
import tv.own.owntv.mobile.tvbox.net.AddressFetcher
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.net.OkHttpProvider
import tv.own.owntv.mobile.tvbox.play.TvboxPlayResolver
import tv.own.owntv.mobile.tvbox.repository.SourceRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxConfigRepository
import tv.own.owntv.mobile.tvbox.repository.TvboxSourceStats
import tv.own.owntv.mobile.tvbox.repository.TvboxStore
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
    single { TvboxStore(androidContext()) }

    // Network: fresh chains (main / download / jar) with the TVbox interceptors. The Core global
    // client is left untouched — it forces HTTP/1.1 and its own trust set, which the jar chain
    // must not inherit.
    single<OkHttpClient> { OkHttpProvider.get() }
    single {
        NetApi(
            clientProvider = { OkHttpProvider.get() },
            downloadProvider = { OkHttpProvider.download() },
        )
    }
    single { AddressFetcher(androidContext(), get(), get<TvboxPrefs>()) }

    single { TvboxConfigRepository(androidContext(), get(), get(), get()) }
    single { TvboxSourceStats() }

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
    single<SourceRepository> {
        SourceRepository(
            configRepository = get(),
            engines = listOf<Engine>(get<CmsEngine>(), get<JarEngine>()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sourceStatRepository = get(),
        )
    }

    single { ParseRepository(get()) }
    single { SniffEngine(androidContext()) }

    single { TvboxCategoryPolicy(get<ProfileDao>(), get<TvboxPrefs>()) }
    single {
        TvboxCategoryLock(
            customize = get<CustomizationStore>(),
            unlocks = get<CategoryUnlockSession>(),
            customizePinSettings = get<SettingsRepository>() as CustomizePinSettings,
            pinLimiter = get<PinAttemptLimiter>(),
        )
    }
    single { TvboxPlayResolver(get(), get(), get(), get(), get<ProfileDao>(), get(), get()) }
    single {
        TvboxSealedPlaybackGuard(
            vodTuner = get<VodTuner>(),
            lock = get(),
            settings = get(),
            unlocks = get(),
            profileDao = get<ProfileDao>(),
        )
    }

    // Catvod stub host: context + jar network stack, set before any jar can load.
    single { CatvodHostInit(androidContext()) }

    // Config lifecycle hooks into engines/sniff/network; resolved eagerly on app start.
    single { TvboxRuntime(get(), get(), get(), get(), get()) }

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
