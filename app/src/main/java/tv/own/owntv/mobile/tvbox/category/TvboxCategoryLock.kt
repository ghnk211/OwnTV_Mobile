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
 * 锁数据的读写缝：生产实现包 Core [CustomizationStore]（MOVIE section），单测用内存假件。
 * TVbox 内容没有 Core `categories` 行，读写都走 `tvbox:` 前缀 key（方案 §5.5.2/§5.5.3）。
 */
interface TvboxLockStore {
    suspend fun lockedCategories(profileId: Long): Set<String>
    suspend fun setLocked(profileId: Long, key: String, locked: Boolean)
}

/** 生产实现：Core 锁数据（`cust_<pid>_MOVIE.lockedCats`）。 */
class CoreLockStore(private val customize: CustomizationStore) : TvboxLockStore {
    override suspend fun lockedCategories(profileId: Long): Set<String> =
        customize.observe(profileId, MediaType.MOVIE).first().lockedCategories

    override suspend fun setLocked(profileId: Long, key: String, locked: Boolean) =
        customize.setCategoryLocked(profileId, MediaType.MOVIE, key, locked)
}

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
 * - **key 双轨匹配**（回归 R14 教训）：锁以 typeId 形态写入，而搜索来源的播放只带得出
 *   typeName —— 判定与解锁会话对两种形态都生效；写锁仍只写 typeId 优先的那一个。
 * - 解锁防绕过：先取 `since = unlocks.epoch()` → 校验 PIN → `unlocks.unlock(..., since)`。
 * - PIN 校验/限速复用 `Pin.verifyCustomize` + [PinAttemptLimiter]（无 PIN 时不校验直接放行 UI，
 *   与 Core 行为一致：没有 PIN 不算锁）。
 */
class TvboxCategoryLock(
    private val store: TvboxLockStore,
    private val unlocks: CategoryUnlockSession,
    private val customizePinSettings: CustomizePinSettings,
    private val pinLimiter: PinAttemptLimiter,
) {

    /** 统一 key 规范：typeId 优先、typeName 兜底。 */
    fun keyOf(siteKey: String, typeId: String?, typeName: String): String = keyOfStatic(siteKey, typeId, typeName)

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
        if (customizePinSettings.customizePin(profileId).first() == null) return false
        val keys = keysOf(siteKey, typeId, typeName)
        val locked = store.lockedCategories(profileId)
        if (keys.none { it in locked }) return false
        // 任一形态被本会话解锁 → 放行（seals=true 是"该停"，别处语义）
        if (!anySession && keys.any { unlocks.isUnlocked(profileId, MediaType.MOVIE, it) }) return false
        return true
    }

    /** 锁定 key 集合：UI 据此给分类行加锁图标（不隐藏行）。 */
    suspend fun sealedKeys(profileId: Long, siteKey: String, categories: List<ClassInfo>): Set<String> {
        if (customizePinSettings.customizePin(profileId).first() == null) return emptySet()
        val locked = store.lockedCategories(profileId)
        if (locked.isEmpty()) return emptySet()
        return categories.mapNotNull { c ->
            val k = keyOf(siteKey, c.typeId, c.typeName)
            if (k in locked) k else null
        }.toSet()
    }

    /**
     * 上锁/解锁（VOD 页 ⋯ 菜单）。前提是已有 Customize PIN，调用方负责提示。
     * 双轨写：主键（typeId 形态）与别名（typeName 形态）一起落 [TvboxLockStore]，
     * 搜索来源的播放（只有 typeName）才能命中同一把锁；解除时两形态一起撤。
     */
    suspend fun toggleLock(profileId: Long, siteKey: String, typeId: String?, typeName: String, locked: Boolean) {
        keysOf(siteKey, typeId, typeName).forEach { store.setLocked(profileId, it, locked) }
    }

    /**
     * PIN 解锁：先记 epoch 再验 PIN，期间发生 relockAll 则拒绝（返回 Wrong 语义）。
     * 解锁会话对双轨两个形态都放行（见 [keysOf]）。
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
        val keys = keysOf(siteKey, typeId, typeName)
        val since = unlocks.epoch()
        return pinLimiter.attempt {
            val verified = Pin.verifyCustomize(pin, stored)
            verified && keys.all { unlocks.unlock(profileId, MediaType.MOVIE, it, since) }
        }
    }

    /** 该档案是否没有 Customize PIN（上锁菜单据此给"先去设置 PIN"引导）。 */
    suspend fun isNoPinProfile(profileId: Long): Boolean =
        customizePinSettings.customizePin(profileId).first() == null

    companion object {
        /** UI 与锁判定共用的 key 构造（无状态）。 */
        fun keyOfStatic(siteKey: String, typeId: String?, typeName: String): String =
            "tvbox:$siteKey:${typeId?.takeIf { it.isNotBlank() } ?: typeName}"

        /**
         * 同一站点的两种 key 形态：主键（typeId 优先，写锁用它）与别名（typeName 兜底）。
         * typeId 缺失（搜索来源播放）时两形态合一。
         */
        internal fun keysOf(siteKey: String, typeId: String?, typeName: String): List<String> {
            val primary = keyOfStatic(siteKey, typeId, typeName)
            val alias = typeId?.takeIf { it.isNotBlank() }?.let { keyOfStatic(siteKey, null, typeName) }
            return if (alias != null && alias != primary) listOf(primary, alias) else listOf(primary)
        }
    }
}
