package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.mobile.ui.screens.library.DetailViewModel
import tv.own.owntv.mobile.ui.screens.library.LibraryViewModel
import tv.own.owntv.mobile.ui.screens.library.VodTuner

/**
 * Movies and Series, and the tuner they share.
 *
 * A `single` for the same reason the live tuner is one: the film is what is playing, and it outlives
 * the list it was started from.
 */
val libraryModule = module {
    single {
        VodTuner(
            context = androidContext(),
            movieDao = get(),
            seriesDao = get(),
            categoryDao = get(),
            profileDao = get(),
            sourceDao = get(),
            historyDao = get(),
            progressDao = get(),
            settings = get(),
            streamUrlResolver = get(),
            externalPlayerLauncher = get(),
            subtitleController = get(),
            metadata = get(),
            session = get(),
            liveTuner = get(),
            dataSaver = get(),
            cast = get(),
            player = get(),
            watchSession = get(),
            customize = get(),
            tvboxStore = get(),
            tvboxPlayback = get(),
        )
    }
    viewModelOf(::LibraryViewModel)
    viewModelOf(::DetailViewModel)
}
