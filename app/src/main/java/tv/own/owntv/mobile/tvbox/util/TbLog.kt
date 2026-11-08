package tv.own.owntv.mobile.tvbox.util

import android.util.Log
import tv.own.owntv.mobile.BuildConfig

/**
 * Log wrapper for the TVbox parsing chain, standing in for VodPlayer's Timber.
 *
 * Same call shape as Timber (`tag().level(format, args)`, throwable-first overloads) so the ported
 * files keep their original lines; output goes straight to `android.util.Log` under a `Tvbox.` tag
 * prefix. `treeCount` mirrors `Timber.treeCount` for the two call sites that guard expensive
 * formatting behind it: logs are only formatted in debug builds.
 */
object TbLog {

    private const val PREFIX = "Tvbox."

    /** Mirrors Timber.treeCount: non-zero when log calls should bother formatting. */
    val treeCount: Int get() = if (BuildConfig.DEBUG) 1 else 0

    fun tag(tag: String): Api = Api(PREFIX + tag)

    class Api internal constructor(private val tag: String) {
        fun d(message: String, vararg args: Any?) = log(Log.DEBUG, tag, null, message, args)
        fun i(message: String, vararg args: Any?) = log(Log.INFO, tag, null, message, args)
        fun w(message: String, vararg args: Any?) = log(Log.WARN, tag, null, message, args)
        fun e(message: String, vararg args: Any?) = log(Log.ERROR, tag, null, message, args)
        fun w(t: Throwable?, message: String, vararg args: Any?) = log(Log.WARN, tag, t, message, args)
        fun e(t: Throwable?, message: String, vararg args: Any?) = log(Log.ERROR, tag, t, message, args)
        fun i(t: Throwable?, message: String, vararg args: Any?) = log(Log.INFO, tag, t, message, args)
    }

    fun d(message: String, vararg args: Any?) = DEFAULT.d(message, *args)
    fun i(message: String, vararg args: Any?) = DEFAULT.i(message, *args)
    fun w(message: String, vararg args: Any?) = DEFAULT.w(message, *args)
    fun e(message: String, vararg args: Any?) = DEFAULT.e(message, *args)
    fun w(t: Throwable?, message: String, vararg args: Any?) = DEFAULT.w(t, message, *args)
    fun e(t: Throwable?, message: String, vararg args: Any?) = DEFAULT.e(t, message, *args)

    private val DEFAULT = Api(PREFIX + "core")

    private fun log(priority: Int, tag: String, t: Throwable?, message: String, args: Array<out Any?>) {
        val text = if (args.isEmpty()) message
        else runCatching { String.format(message, *args) }.getOrDefault(message)
        if (t != null) {
            Log.println(priority, tag, "$text\n${Log.getStackTraceString(t)}")
        } else {
            Log.println(priority, tag, text)
        }
    }
}
