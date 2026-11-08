package tv.own.owntv.mobile.tvbox

import android.content.Context
import okhttp3.OkHttpClient
import tv.own.owntv.mobile.tvbox.net.OkHttpProvider

/**
 * Host bridge for the `com.github.catvod` stubs.
 *
 * VodPlayer's stubs reached the application context and the jar network stack through its
 * hand-written `ServiceLocator`; this app wires DI through Koin, so the stubs go through this
 * object instead. It holds the context (set once from [tv.own.owntv.mobile.tvbox.di.TvboxModule])
 * and hands out the jar-stub OkHttp client — private-net blocked, trust-all TLS, per the same
 * security boundary the stubs had in VodPlayer.
 */
object CatvodHost {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun appContext(): Context =
        appContext ?: throw IllegalStateException("CatvodHost.init not called")

    fun netClient(): OkHttpClient = OkHttpProvider.jarStubClient()
}
