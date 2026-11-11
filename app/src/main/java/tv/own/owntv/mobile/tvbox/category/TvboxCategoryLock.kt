package tv.own.owntv.mobile.tvbox.category

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.categorylock.PinAttemptLimiter
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.CustomizePinSettings
import tv.own.owntv.core.util.Pin

/**
 * 锁数据的读写缝：生产实现包 Core [CustomizationStore]（MOVIE section），单测用内存假件。
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
 * 站点+分类身份 → Core 定制 key 的解析缝。P1 起锁只写 Core 口径 key
 * `<sourceId>:<siteKey>/<typeId>`（与 [CustomizeKeys.category] 完全一致），单测用内存假件。
 */
interface TvboxLockKeys {
    /** 规范 key；身份无法落到已索引的分类时返回 null（没有可命中的锁）。 */
    suspend fun keyOf(sourceId: Long, siteKey: String, typeId: String?, typeName: String): String?
}

/**
 * 生产实现：typeId 在手直接拼 key；搜索来源的播放只带 typeName（回归 R14），按名字回查
 * 已索引的分类行取规范 key —— 锁只可能写在索引过的分类上，查不到即无锁可命中。
 */
class CoreLockKeys(private val categoryDao: CategoryDao) : TvboxLockKeys {
    override suspend fun keyOf(sourceId: Long, siteKey: String, typeId: String?, typeName: String): String? {
        typeId?.takeIf { it.isNotBlank() }?.let { return "$sourceId:$siteKey/$it" }
        if (typeName.isBlank()) return null
        return categoryDao.observe(listOf(sourceId), MediaType.MOVIE).first()
            .firstOrNull { it.name == typeName && it.remoteId?.startsWith("$siteKey/") == true }
            ?.let { CustomizeKeys.category(it) }
    }
}

/**
 * 分类锁（方案 §5.5，P1 改 Core 口径）：TVbox 分类已有 Core `categories` 行，锁 key 直接写
 * `<sourceId>:<siteKey>/<typeId>`，与 Library/Customize 共用同一份 `lockedCats`；
 * 旧 `tvbox:` key 废弃不迁移（方案 §6 决策 6）。
 *
 * - 数字 sourceId 前缀让 Core `CategoryLockView.withSealedIds` 解析得出源 id，
 *   `sealedCategoryIds` 与本锁天然同口径。
 * - 判定顺序严格照 Core `CategoryLockView`：无 PIN → false；key 解析不出/不含 → false；
 *   本会话已解锁 → false；否则 true。
 * - 解锁防绕过：先取 `since = unlocks.epoch()` → 校验 PIN → `unlocks.unlock(..., since)`。
 * - PIN 校验/限速复用 `Pin.verifyCustomize` + [PinAttemptLimiter]（无 PIN 时不校验直接放行 UI，
 *   与 Core 行为一致：没有 PIN 不算锁）。
 */
class TvboxCategoryLock(
    private val store: TvboxLockStore,
    private val unlocks: CategoryUnlockSession,
    private val customizePinSettings: CustomizePinSettings,
    private val pinLimiter: PinAttemptLimiter,
    private val keys: TvboxLockKeys,
) {

    /**
     * 该站点+分类是否被锁（详情/播放的强制校验）。
     * [sourceId] 为 null（源未置备）时没有可命中的锁。
     * [anySession] = 忽略解锁会话（重锁停播 guard 用，方案 §5.5.8）。
     */
    suspend fun seals(
        profileId: Long,
        sourceId: Long?,
        siteKey: String,
        typeId: String?,
        typeName: String,
        anySession: Boolean = false,
    ): Boolean {
        if (sourceId == null) return false
        if (customizePinSettings.customizePin(profileId).first() == null) return false
        val key = keys.keyOf(sourceId, siteKey, typeId, typeName) ?: return false
        if (key !in store.lockedCategories(profileId)) return false
        if (!anySession && unlocks.isUnlocked(profileId, MediaType.MOVIE, key)) return false
        return true
    }

    /** 上锁/撤锁（VOD 页 ⋯ 菜单）。前提是已有 Customize PIN，调用方负责提示。 */
    suspend fun toggleLock(profileId: Long, key: String, locked: Boolean) =
        store.setLocked(profileId, key, locked)

    /**
     * PIN 解锁（规范 key 形态，分类条直接持有 key）：先记 epoch 再验 PIN，
     * 期间发生 relockAll 则拒绝（返回 Wrong 语义）。
     */
    suspend fun unlockKey(profileId: Long, key: String, pin: String): PinAttempt {
        val stored = customizePinSettings.customizePin(profileId).first()
            ?: return PinAttempt.Accepted // 无 PIN：没有锁可解，直接放行
        val since = unlocks.epoch()
        return pinLimiter.attempt {
            Pin.verifyCustomize(pin, stored) && unlocks.unlock(profileId, MediaType.MOVIE, key, since)
        }
    }

    /** PIN 解锁（站点+分类身份形态，详情层用）：身份解析不出 key 时视同无锁。 */
    suspend fun unlock(
        profileId: Long,
        sourceId: Long?,
        siteKey: String,
        typeId: String?,
        typeName: String,
        pin: String,
    ): PinAttempt {
        val key = sourceId?.let { keys.keyOf(it, siteKey, typeId, typeName) }
            ?: return PinAttempt.Accepted
        return unlockKey(profileId, key, pin)
    }

    /** 该档案是否没有 Customize PIN（上锁菜单据此给"先去设置 PIN"引导）。 */
    suspend fun isNoPinProfile(profileId: Long): Boolean =
        customizePinSettings.customizePin(profileId).first() == null
}
