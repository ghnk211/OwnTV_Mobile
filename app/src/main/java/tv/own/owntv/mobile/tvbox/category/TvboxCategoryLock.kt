package tv.own.owntv.mobile.tvbox.category

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.categorylock.PinAttemptLimiter
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.CustomizePinSettings
import tv.own.owntv.core.util.Pin
import tv.own.owntv.mobile.tvbox.engine.ClassInfo

/**
 * 分类锁（方案 §5.5）：TVbox 站点/分类没有 Core `categories` 行，接的是
 * **Core 的锁数据 + PIN + 解锁会话**，不是 `sealsCategory` 本身。
 *
 * - 锁 key 写 Core `lockedCats`（MOVIE section），格式 `tvbox:<siteKey>:<typeId|typeName>`，
 *   与 Core 的 `<sourceId>:<remoteId ?: name>` 同构；`tvbox` 前缀不是数字，
 *   `CategoryLockView.withSealedIds` 的 `substringBefore(':').toLongOrNull()` 会得到 null，
 *   不会混进 Core 的 `sealedCategoryIds`。
 * - 判定顺序严格照 Core `CategoryLockView`：无 PIN → false；不含 key → false；
 *   未解锁会话 → false；否则 true。
 * - 解锁防绕过：先取 `since = unlocks.epoch()` → 校验 PIN → `unlocks.unlock(..., since)`。
 * - PIN 校验/限速复用 `Pin.verifyCustomize` + [PinAttemptLimiter]（无 PIN 时不校验直接放行 UI，
 *   与 Core 行为一致：没有 PIN 不算锁）。
 */
class TvboxCategoryLock(
    private val customize: CustomizationStore,
    private val unlocks: CategoryUnlockSession,
    private val customizePinSettings: CustomizePinSettings,
    private val pinLimiter: PinAttemptLimiter,
) {

    /** 统一 key 规范：typeId 优先、typeName 兜底。 */
    fun keyOf(siteKey: String, typeId: String?, typeName: String): String = keyOfStatic(siteKey, typeId, typeName)

    companion object {
        /** UI 与锁判定共用的 key 构造（无状态）。 */
        fun keyOfStatic(siteKey: String, typeId: String?, typeName: String): String =
            "tvbox:$siteKey:${typeId?.takeIf { it.isNotBlank() } ?: typeName}"
    }

    /**
     * 该站点+分类是否被锁（详情/播放的强制校验）。
     * [anySession] = 忽略解锁会话（重锁停播 guard 用，方案 §5.5.8）。
     */
    suspend fun seals(
        profileId: Long,
        siteKey: String,
        typeId: String?,
        typeName: String,
        anySession: Boolean = false,
    ): Boolean {
        val key = keyOf(siteKey, typeId, typeName)
        if (customizePinSettings.customizePin(profileId).first() == null) return false
        val locked = customize.observe(profileId, MediaType.MOVIE).first().lockedCategories
        if (key !in locked) return false
        if (!anySession && unlocks.isUnlocked(profileId, MediaType.MOVIE, key)) return false
        return true
    }

    /** 锁定 key 集合：UI 据此给分类行加锁图标（不隐藏行）。 */
    suspend fun sealedKeys(profileId: Long, siteKey: String, categories: List<ClassInfo>): Set<String> {
        if (customizePinSettings.customizePin(profileId).first() == null) return emptySet()
        val locked = customize.observe(profileId, MediaType.MOVIE).first().lockedCategories
        if (locked.isEmpty()) return emptySet()
        return categories.mapNotNull { c ->
            val k = keyOf(siteKey, c.typeId, c.typeName)
            if (k in locked) k else null
        }.toSet()
    }

    /** 该档案是否没有 Customize PIN（上锁菜单据此给"先去设置 PIN"引导）。 */
    suspend fun isNoPinProfile(profileId: Long): Boolean =
        customizePinSettings.customizePin(profileId).first() == null

    /** 上锁/解锁（VOD 页 ⋯ 菜单）。前提是已有 Customize PIN，调用方负责提示。 */
    suspend fun toggleLock(profileId: Long, siteKey: String, typeId: String?, typeName: String, locked: Boolean) {
        customize.setCategoryLocked(profileId, MediaType.MOVIE, keyOf(siteKey, typeId, typeName), locked)
    }

    /**
     * PIN 解锁：先记 epoch 再验 PIN，期间发生 relockAll 则拒绝（返回 Wrong 语义）。
     */
    suspend fun unlock(
        profileId: Long,
        siteKey: String,
        typeId: String?,
        typeName: String,
        pin: String,
    ): PinAttempt {
        val stored = customizePinSettings.customizePin(profileId).first()
            ?: return PinAttempt.Accepted // 无 PIN：没有锁可解，直接放行
        val key = keyOf(siteKey, typeId, typeName)
        val since = unlocks.epoch()
        return pinLimiter.attempt {
            Pin.verifyCustomize(pin, stored) && unlocks.unlock(profileId, MediaType.MOVIE, key, since)
        }
    }
}
