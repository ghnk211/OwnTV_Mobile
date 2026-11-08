package tv.own.owntv.mobile.tvbox.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * 播放解析器可用性记忆（优化方案 B1，对齐 `SourceHealth` 的熔断模式）。
 *
 * 单个解析器失效是常态（0821.json 的「巧技/巧技二」实测 502），没有记忆的话
 * 每次官源播放都要陪跑一轮注定失败的 JSON 并行解析（最多白付 10s）。
 *
 * - 记账点：`ParseRepository.resolve` 的 HTTP+JSON 路径（type=1/2/3）——
 *   `Direct/NeedJs` 清零，`Failure` 记 1 次；type=0（WebView 嗅探型）不记账，永远放行。
 * - 熔断：连续 2 次失败 → 10 分钟不进任何候选（`candidatesForWebPage` 与 `pick` 过滤）。
 * - 半开：熔断到期自动放行试探 —— 每次播放的首个请求即试探，成功恢复、失败重新熔断。
 *
 * 进程内存即可（Session 级）：解析器状态随进程生灭，无需落盘。
 */
object ParseHealth {

    private const val FAIL_THRESHOLD = 2
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    private class State {
        @Volatile var consecutiveFail = 0
        @Volatile var cooldownUntil = 0L
        @Volatile var lastSuccessAt = 0L
    }

    private val states = ConcurrentHashMap<String, State>()

    /** 解析器键：解析器 URL（同名不同址按不同解析器对待） */
    fun keyOf(url: String): String = url

    fun recordSuccess(key: String) {
        val s = states.getOrPut(key) { State() }
        s.consecutiveFail = 0
        s.cooldownUntil = 0L
        s.lastSuccessAt = System.currentTimeMillis()
    }

    /** 最近成功时间（0 = 无成功记录）；B5 候选排序的健康权重来源 */
    fun lastSuccessAt(key: String): Long = states[key]?.lastSuccessAt ?: 0L

    fun recordFailure(key: String) {
        val s = states.getOrPut(key) { State() }
        synchronized(s) {
            s.consecutiveFail++
            if (s.consecutiveFail >= FAIL_THRESHOLD) {
                s.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
            }
        }
    }

    /** 熔断中返回 false；到期即半开放行（首个请求试探，成功恢复 / 失败续熔） */
    fun isAvailable(key: String): Boolean {
        val s = states[key] ?: return true
        return System.currentTimeMillis() >= s.cooldownUntil
    }

    /**
     * 连续失败次数（未到熔断线也返回真实值）。
     *
     * 用途：**聚合腿缩容**（第 3 项）—— 失败过 1 次的站点先把 iframe 从 8 个收到 2~3 个
     * （实测 8 个通用 jx 解析器在 0821 配置下 4/4 全灭，缩容能显著降低 JS 争抢与 pageFinished 时间），
     * 再失败才整腿熔断。语义与「冷却」分开，别混用。
     */
    fun failCount(key: String): Int = states[key]?.consecutiveFail ?: 0
}
