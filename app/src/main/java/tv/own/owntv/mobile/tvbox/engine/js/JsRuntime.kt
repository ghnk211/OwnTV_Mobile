package tv.own.owntv.mobile.tvbox.engine.js

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.ModuleLoader
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.util.TbLog
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个 QuickJS context 绑定一条专属线程。
 *
 * ## 为什么必须"一个 context 一条线程"
 * QuickJSContext 非线程安全：同一 context 在两个线程上 evaluate 会直接崩在 native。
 * 这里用单线程 executor，所有 JS 调用都在 [dispatcher] 上排队执行。
 *
 * ## 死循环怎么办
 * wrapper 2.1.0 未暴露 `JS_SetInterruptHandler`，本仓采用三重缓解 + 隔离：
 * `setMemoryLimit` + `setMaxStackSize` + `withTimeoutOrNull` 判死重建；
 * 被卡住的线程不再接受新任务，不扩散到其它源。
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsRuntime.kt`。
 */
class JsRuntime(
    private val net: NetApi,
    private val siteKey: String,
    private val siteName: String,
    private val store: JsLocalStore,
    private val cache: JsModuleCache,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "drpy-js-$siteKey").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)
    private val dead = AtomicBoolean(false)

    @Volatile
    private var context: QuickJSContext? = null

    @Volatile
    private var bridge: JsBridge? = null

    private val moduleSources = ConcurrentHashMap<String, String>()

    suspend fun init(runtimeSource: String, runtimeUrl: String, siteSource: String?, siteUrl: String?) =
        call {
            val ctx = QuickJSContext.create()
            context = ctx
            ctx.setMemoryLimit(MEMORY_LIMIT_BYTES)
            ctx.setMaxStackSize(MAX_STACK_BYTES)

            val installed = JsBridge(ctx, net, siteKey, siteName, timeoutMs, store)
            installed.installAll()
            bridge = installed

            moduleSources[runtimeUrl] = runtimeSource
            val isModule = RUNTIME_MODULE_PATTERN.containsMatchIn(runtimeSource)
            if (isModule) {
                ctx.setModuleLoader(createModuleLoader())
                val bootstrap = "import __drpy from ${Gson().toJson(runtimeUrl)};\n" +
                    "globalThis.__drpyModule = __drpy;"
                ctx.evaluateModule(bootstrap, BOOTSTRAP_MODULE_URL)
            } else {
                val cached = cache.readBytecode(runtimeUrl)
                val bytes = cached ?: runCatching { ctx.compile(runtimeSource, runtimeUrl) }
                    .onSuccess { cache.writeBytecode(runtimeUrl, it) }
                    .getOrNull()
                if (bytes != null) {
                    ctx.execute(bytes)
                } else {
                    ctx.evaluate(runtimeSource, runtimeUrl)
                }
            }

            if (siteSource != null) {
                val ext = Gson().toJson(siteSource)
                ctx.evaluate(
                    "if (globalThis.__drpyModule && typeof globalThis.__drpyModule.init === 'function') " +
                        "globalThis.__drpyModule.init($ext);",
                    siteUrl ?: "vod-init.js",
                )
            }

            ctx.evaluate(INVOKE_BRIDGE, "vod-bridge.js")
        }

    private fun createModuleLoader(): ModuleLoader = object : ModuleLoader() {
        override fun isBytecodeMode(): Boolean = true

        override fun getModuleBytecode(moduleName: String): ByteArray? {
            cache.readBytecode(moduleName)?.let { return it }
            val source = loadModuleSource(moduleName) ?: return null
            val ctx = context ?: return null
            return runCatching { ctx.compileModule(source, moduleName) }
                .onSuccess { cache.writeBytecode(moduleName, it) }
                .getOrNull()
        }

        override fun getModuleStringCode(moduleName: String): String =
            loadModuleSource(moduleName) ?: EMPTY_MODULE_CODE

        override fun moduleNormalizeName(baseName: String, moduleName: String): String =
            normalizeModuleUrl(baseName, moduleName)
    }

    private fun loadModuleSource(moduleName: String): String? {
        moduleSources[moduleName]?.let { return it }
        cache.readSource(moduleName)?.let {
            moduleSources[moduleName] = it
            return it
        }
        val fetched = runCatching {
            runBlocking {
                withTimeout(timeoutMs) {
                    val response = net.getText(moduleName, siteKey, timeoutMs = timeoutMs)
                    check(response.isSuccess) { "模块下载失败 ${response.code}: ${response.error}" }
                    val body = response.body
                    check(!isInvalidModuleContent(body)) { "模块地址返回非 JS 内容：$moduleName" }
                    body
                }
            }
        }.getOrElse {
            TbLog.tag("JS").w("[$siteKey] 模块 $moduleName 加载失败：${it.message}")
            null
        }
        if (fetched != null) {
            cache.writeSource(moduleName, fetched)
            moduleSources[moduleName] = fetched
            return fetched
        }
        TbLog.tag("JS").w("[$siteKey] 模块 $moduleName 缺失，返回空 stub")
        return EMPTY_MODULE_CODE
    }

    suspend fun callFunction(name: String, vararg args: Any?): Any? = call {
        val ctx = context ?: error("JS runtime 未初始化")
        val invoke = ctx.getProperty(ctx.globalObject, "__vodInvoke") as? JSFunction
            ?: error("JS 调用桥未初始化")
        val encodedArgs = Gson().toJson(args)
        val encodedResult = invoke.call(name, encodedArgs) as? String ?: return@call null
        decode(encodedResult)
    }

    /** `/proxy` 路由回调：走 JS 侧的 `proxy(params)` */
    suspend fun callProxy(params: Map<String, String>): Any? = call {
        val ctx = context ?: error("JS runtime 未初始化")
        val invoke = ctx.getProperty(ctx.globalObject, "__vodInvoke") as? JSFunction
            ?: error("JS 调用桥未初始化")
        val encodedArgs = Gson().toJson(arrayOf<Any?>(params))
        val encodedResult = invoke.call("proxy", encodedArgs) as? String ?: return@call null
        decode(encodedResult)
    }

    private fun decode(encodedResult: String): Any? {
        val element = runCatching { JsonParser.parseString(encodedResult) }.getOrNull() ?: return null
        return when {
            element.isJsonNull -> null
            element.isJsonObject || element.isJsonArray -> element
            element.isJsonPrimitive -> primitive(element)
            else -> null
        }
    }

    /** 已不可用（超时/异常）的 runtime：调用方应销毁重建 */
    val isDead: Boolean get() = dead.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bridge?.clearTimers()
        moduleSources.clear()
        runCatching {
            executor.execute {
                runCatching { context?.destroy() }
                context = null
            }
        }
        dispatcher.close()
    }

    private suspend fun <T> call(block: () -> T): T = withContext(dispatcher) {
        check(!closed.get()) { "JS runtime 已释放" }
        check(!dead.get()) { "JS runtime 已因超时/异常停用" }
        val boxed = withTimeoutOrNull(timeoutMs) {
            val value = block()
            runCatching { bridge?.drainTimers() }
            Box(value)
        }
        if (boxed == null) {
            dead.set(true)
            throw JsTimeoutException("[$siteName] JS 调用超时（${timeoutMs}ms）")
        }
        boxed.value
    }

    /** 装箱值：区分「block 返回 null」与「withTimeoutOrNull 超时返回 null」 */
    private class Box<out T>(val value: T)

    private fun isInvalidModuleContent(body: String): Boolean {
        val head = body.trimStart()
        return head.isEmpty() || head.startsWith("<") || head.startsWith("<!DOCTYPE") ||
            head.startsWith("404")
    }

    private fun primitive(element: JsonElement): Any? {
        val value = element.asJsonPrimitive
        return when {
            value.isBoolean -> value.asBoolean
            value.isNumber -> value.asNumber
            value.isString -> value.asString
            else -> null
        }
    }

    companion object {
        private const val BOOTSTRAP_MODULE_URL = "https://vod.invalid/__bootstrap__.mjs"
        private val RUNTIME_MODULE_PATTERN = Regex("(?m)^\\s*import\\s*(?:[\\w{*]|['\"])")
        const val DEFAULT_TIMEOUT_MS = 15_000L

        private const val MEMORY_LIMIT_BYTES = 64 * 1024 * 1024
        private const val MAX_STACK_BYTES = 512 * 1024

        internal const val INVOKE_BRIDGE = """
            globalThis.__vodInvoke = function(name, argsJson) {
              var args = JSON.parse(argsJson);
              var api = globalThis.__drpyModule;
              var fn = (api && api[name]) || globalThis[name];
              if (typeof fn !== 'function') return 'null';
              var result = fn.apply(api || globalThis, args);
              if (result === undefined || result === null) return 'null';
              return JSON.stringify(result);
            };
        """

        /**
         * 缺失模块的空 stub。没有它，一个源引用了未打包的模块就是**整个 context 崩**，
         * 而不是"这个源不可用"。刻意保持空实现，不补全成宿主能力。
         */
        internal val EMPTY_MODULE_CODE = """
            var _empty = {};
            export function inflate() { return null; }
            export function deflate() { return null; }
            export function gzip() { return null; }
            export function ungzip() { return null; }
            export function encrypt() { return ''; }
            export function decrypt() { return ''; }
            export function parse() { return null; }
            export function stringify() { return ''; }
            export function JSEncrypt() { return _empty; }
            export function NodeRSA() { return _empty; }
            export function JSON5() { return _empty; }
            export function pako() { return _empty; }
            export default {
              inflate: inflate, deflate: deflate, gzip: gzip, ungzip: ungzip,
              encrypt: encrypt, decrypt: decrypt, parse: parse, stringify: stringify,
              JSEncrypt: _empty, NodeRSA: _empty, JSON5: _empty, pako: _empty
            };
        """.trimIndent()

        internal fun normalizeModuleUrl(baseUrl: String, moduleName: String): String =
            runCatching { URI(baseUrl).resolve(URI(moduleName)).normalize().toString() }
                .getOrElse { moduleName }
    }
}

/**
 * JS 调用**自身**超时。刻意不是 `CancellationException` 的子类：
 * 这是「超时失败」的普通异常语义，交给 `SourceRepository.call` 归一记账后返回 null；
 * 协程取消/外层超时则继续以 CancellationException 穿透。
 */
class JsTimeoutException(message: String) : RuntimeException(message)