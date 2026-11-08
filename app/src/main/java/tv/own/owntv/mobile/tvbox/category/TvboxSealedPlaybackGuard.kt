package tv.own.owntv.mobile.tvbox.category

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.tvbox.util.TbLog

/**
 * TVbox 播放中的重锁停播（方案 §5.5.8）。
 *
 * 与 Core 的 `CategoryRelock` 并行、互不替代：Core guard 只停 Live/Core VOD，
 * 本 guard 只停 TVbox 播放。宿主在 `MobileShell` 里挂两个钩子：
 * - `Lifecycle.Event.ON_STOP` → [leftApp]（Core 侧此刻会 `relockAll`，所以这里
 *   的 `seals(..., anySession = false)` 会把"解锁会话已失效"的锁重新判为锁上）；
 * - `settings.activeProfileId` 变化 → [profileChanged]。
 *
 * 读锁异常 → fail-closed 停播（与 Core `sealsNow` 一致）。
 */
class TvboxSealedPlaybackGuard(
    private val vodTuner: VodTuner,
    private val lock: TvboxCategoryLock,
    private val settings: SettingsRepository,
    private val unlocks: CategoryUnlockSession,
    private val profileDao: ProfileDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** App 进入后台（ON_STOP）。 */
    fun leftApp() = stopIfSealed("leftApp")

    /** 活动档案切换。 */
    fun profileChanged() = stopIfSealed("profileChanged")

    private fun stopIfSealed(reason: String) {
        val ctx = vodTuner.tvboxPlayingContext()
        TbLog.tag(TAG).i("stopIfSealed（%s）：ctx=%s", reason, ctx != null)
        if (ctx == null) return
        val (siteKey, category) = ctx
        scope.launch {
            val pid = runCatching { settings.activeProfileId.first() }.getOrDefault(-1L)
            if (pid < 0) return@launch
            val sealed = runCatching {
                lock.seals(pid, siteKey, category.typeId, category.typeName, anySession = false)
            }.getOrElse {
                // 读锁异常 fail-closed：宁可停播，不可漏播
                TbLog.tag(TAG).w(it, "TVbox 重锁检查失败，按锁定处理（%s）", reason)
                true
            }
            if (sealed) {
                TbLog.tag(TAG).i("TVbox 播放命中重锁（%s），停止播放：%s", reason, siteKey)
                vodTuner.stop()
            }
        }
    }

    private companion object {
        const val TAG = "TvboxGuard"
    }
}
