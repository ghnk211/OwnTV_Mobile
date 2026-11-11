package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.mobile.ui.screens.settings.DeleteSubtitlesViewModel
import tv.own.owntv.mobile.ui.screens.settings.EpgSourcesViewModel
import tv.own.owntv.mobile.ui.screens.settings.OpenSubtitlesViewModel
import tv.own.owntv.mobile.ui.screens.settings.SettingsViewModel
import tv.own.owntv.mobile.ui.screens.settings.customize.CustomizeItemsViewModel
import tv.own.owntv.mobile.ui.screens.settings.customize.CustomizeViewModel

/**
 * The view models behind the settings pages. Everything they take is core's already — the settings
 * themselves, the DAOs the Data and App pages read, and the shared HTTP client the proxy test
 * borrows so it inherits the app's own TLS rather than inventing a second stack.
 *
 * Customize has two of its own because it pages two levels of very different lists; the rest of
 * settings shares one.
 */
val settingsModule = module {
    viewModel {
        SettingsViewModel(
            context = androidContext(),
            settings = get(),
            sourceDao = get(),
            sourceRepository = get(),
            profileDao = get(),
            userDataWriter = get(),
            catalogSync = get(),
            categoryDao = get(),
            channelDao = get(),
            customize = get(),
            okHttpClient = get(),
            playbackPrefs = get(),
            metadataProvider = get(),
            metadataBudget = get(),
            xtreamClient = get(),
            stalkerClient = get(),
            stalkerAuth = get(),
            sourceTester = get(),
            importFinalizer = get(),
            trendingDao = get(),
            trendingActivity = get(),
            connectionLimits = get(),
            player = get(),
            livePreview = get(),
            enginePool = get(),
            sourceEditor = get(),
            profileManager = get(),
            tvboxIndexer = get(),
        )
    }
    viewModel {
        EpgSourcesViewModel(
            settings = get(),
            store = get(),
            epgSync = get(),
            epgRepository = get(),
            epgDao = get(),
            channelDao = get(),
            sourceRepository = get(),
            liveEpgReader = get(),
        )
    }
    viewModel {
        CustomizeViewModel(
            settings = get(),
            sourceDao = get(),
            categoryDao = get(),
            profileDao = get(),
            customCategoryDao = get(),
            contentOrderDao = get(),
            customize = get(),
        )
    }
    viewModel {
        CustomizeItemsViewModel(
            settings = get(),
            sourceDao = get(),
            channelDao = get(),
            movieDao = get(),
            seriesDao = get(),
            contentOrderDao = get(),
            customCategoryDao = get(),
            customize = get(),
            userDataWriter = get(),
        )
    }
    viewModel { OpenSubtitlesViewModel(settings = get(), accounts = get()) }
    viewModel { DeleteSubtitlesViewModel(controller = get()) }
}
