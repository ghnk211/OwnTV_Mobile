package tv.own.owntv.mobile.tvbox.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 全局作用域：配置预热、jar 预下载、进度落盘等脱离 UI 生命周期的任务 */
object AppScope {
    private val job = SupervisorJob()
    val io: CoroutineScope = CoroutineScope(job + Dispatchers.IO)
    val main: CoroutineScope = CoroutineScope(job + Dispatchers.Main.immediate)
    fun cancel() { runCatching { io.cancel() }; runCatching { main.cancel() } }
}

/**
 * 站点健康状态内存表（M5 的 source_stat 落库前的轻量实现）。
 * 驱动"连续失败冷却"与 UI 的"源异常"标记。
 */
object SourceHealth {
    private const val FAIL_THRESHOLD = 3
    private const val COOLDOWN_MS = 5 * 60 * 1000L

    data class State(
        var success: Int = 0,
        var failure: Int = 0,
        var consecutiveFail: Int = 0,
        var cooldownUntil: Long = 0L,
        var lastError: String? = null,
        var avgCostMs: Long = 0L,
        /** 最近一次"接口正常但无数据"的时间戳（借源兜底跳过近期空源用，见 [isRecentlyEmpty]） */
        var lastEmptyAt: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun state(siteKey: String): State = states.getOrPut(siteKey) { State() }

    fun isCooling(siteKey: String): Boolean = System.currentTimeMillis() < state(siteKey).cooldownUntil

    fun cooldownRemainMs(siteKey: String): Long =
        (state(siteKey).cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 记录一次成功（costMs 可为 0） */
    fun recordSuccess(siteKey: String, costMs: Long) {
        val s = state(siteKey)
        synchronized(s) {
            s.success++
            s.consecutiveFail = 0
            s.cooldownUntil = 0L
            // 保留上一次的 lastError 作为"最近一次异常"：成功不代表之前没失败过，
            // 用户报障时"最近一次错误是什么"往往比"最后一次成功"更有定位价值。
            //（此前这里清空，导致切分类/翻页成功一次就把诊断信息抹掉，
            //  UI 又退回笼统的"加载失败"，正是排查 vip.json 时遇到的假象。）
            s.lastError = s.lastError?.takeIf { it.contains("源返回空") }
        }
        if (costMs > 0) {
            synchronized(s) {
                val n = s.success + s.failure
                s.avgCostMs = if (n <= 1) costMs else (s.avgCostMs * (n - 1) + costMs) / n
            }
        }
    }

    /** 记录一次失败，连续失败达阈值即进入冷却 */
    fun recordFailure(siteKey: String, error: String?) {
        val s = state(siteKey)
        synchronized(s) {
            s.failure++
            s.consecutiveFail++
            s.lastError = error?.take(200)
            if (s.consecutiveFail >= FAIL_THRESHOLD) {
                s.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
            }
        }
    }

    /**
     * 记录一次"接口正常但无数据"。
     *
     * ## 为什么不复用 recordFailure
     * 源返回空**不是故障**：很多 jar 源某些分类本来就是空的、或上游站点临时没推片。
     * 走 recordFailure 会连续失败 3 次直接把源判为冷却，反而让本来正常的源被下线。
     *
     * 但完全不记也不对 —— UI 会只能显示"加载失败，可切换站点重试"，
     * 用户分不清"源挂了"和"源本身没数据"，排查成本极高。
     * 故单独一档：写lastError 供诊断，但不动 failure / consecutiveFail / cooldownUntil。
     */
    fun recordEmpty(siteKey: String, note: String?) {
        val s = state(siteKey)
        synchronized(s) {
            s.lastError = note?.take(200)
            s.lastEmptyAt = System.currentTimeMillis()
        }
    }

    /**
     * 最近是否"接口正常但无数据"（借源兜底跳过近期空源用）。
     *
     * 首页兜底借源时若挑一个同样没首页的源，只是再白等一个超时（P1-5）。
     * 窗口取 5 分钟，与冷却时长一致；recordSuccess 会自然让它过期。
     */
    fun isRecentlyEmpty(siteKey: String): Boolean {
        val s = states[siteKey] ?: return false
        val last = synchronized(s) { s.lastEmptyAt }
        return last > 0 && System.currentTimeMillis() - last < COOLDOWN_MS
    }

    fun clear() = states.clear()

    /**
     * 解除某站点的冷却（用户主动重试时调用）。
     *
     * ## 为什么需要
     * 冷却是 5 分钟的**硬锁定**，期间连"重试"按钮都点不动 ——
     * 用户明明看到"该源异常，可重试"，点了却毫无反应，只能干等。
     * 真机实测正是这种体感：「只有前两个页签能用，其余全部提示冷却 4 分钟」，
     * 用户既不知道等多久能好，也不知道点重试有没有用。
     *
     * 用户显式点重试是"我愿意等它"的明确信号，此时再拦着只是徒增挫败感。
     * 故重试即解除：让这次请求真实发生，成功则 [recordSuccess] 自然清零，
     * 失败则重新累计（阈值照旧，仍能拦住真正持续故障的源）。
     */
    fun clearCooldown(siteKey: String) {
        val s = states[siteKey] ?: return
        synchronized(s) {
            s.cooldownUntil = 0L
            s.consecutiveFail = 0
        }
    }

    fun snapshot(): Map<String, State> = states.toMap()

    /** 取某站点最近一次失败原因（诊断与UI 提示用） */
    fun lastErrorOf(siteKey: String): String? = states[siteKey]?.lastError
}

/** 简单的布尔原子开关，用于"某任务只执行一次"场景 */
class OnceFlag {
    private val done = AtomicBoolean(false)
    fun run(block: () -> Unit) {
        if (done.compareAndSet(false, true)) block()
    }
    val isDone: Boolean get() = done.get()
}

/** 切到 IO 线程执行（引擎反射调用等阻塞实现用） */
suspend inline fun <T> onIo(crossinline block: suspend () -> T): T =
    withContext(Dispatchers.IO) { block() }
