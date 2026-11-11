package tv.own.owntv.mobile.tvbox.category

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.categorylock.PinAttemptLimiter
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.CustomizePinSettings

/**
 * 分类锁判定层的 JVM 单测（P1 Core 口径）：seals 的判定顺序、规范 key
 * `<sourceId>:<siteKey>/<typeId>`、typeName 身份回查（回归 R14）、解锁会话的 epoch 防绕过协议。
 */
class TvboxCategoryLockTest {

    /** 内存锁库：模拟 Core `cust_<pid>_MOVIE.lockedCats`。 */
    private class FakeLockStore : TvboxLockStore {
        val locked = mutableSetOf<String>()
        override suspend fun lockedCategories(profileId: Long): Set<String> = locked.toSet()
        override suspend fun setLocked(profileId: Long, key: String, locked: Boolean) {
            if (locked) this.locked.add(key) else this.locked.remove(key)
        }
    }

    /** PIN 固定为 "1234"（明文形态走 Pin.verifyCustomize 的 sameText 分支）。 */
    private class FakePinSettings(private val pin: String?) : CustomizePinSettings {
        override fun customizePin(profileId: Long): Flow<String?> = flowOf(pin)
        override suspend fun setCustomizePin(profileId: Long, pin: String?) = error("unused")
        override fun settingsPinEnabled(profileId: Long): Flow<Boolean> = flowOf(false)
        override suspend fun setSettingsPinEnabled(profileId: Long, enabled: Boolean) = error("unused")
        override suspend fun exportCustomizePins(): org.json.JSONObject = error("unused")
        override suspend fun importCustomizePins(o: org.json.JSONObject, existingProfileIds: Set<Long>) = error("unused")
    }

    /** 身份解析假件：typeName → typeId 映射模拟已索引分类行的按名回查。 */
    private class FakeLockKeys : TvboxLockKeys {
        val names = mutableMapOf("电影" to "1", "剧集" to "2")
        override suspend fun keyOf(sourceId: Long, siteKey: String, typeId: String?, typeName: String): String? {
            val tid = typeId?.takeIf { it.isNotBlank() } ?: names[typeName] ?: return null
            return "$sourceId:$siteKey/$tid"
        }
    }

    private val store = FakeLockStore()
    private val unlocks = CategoryUnlockSession()
    private val keys = FakeLockKeys()
    private lateinit var lock: TvboxCategoryLock

    private val pid = 1L
    private val sourceId = 7L
    private val site = "local_cms"
    private val key = "7:local_cms/1"

    @Before
    fun setUp() {
        var t = 0L
        lock = TvboxCategoryLock(
            store = store,
            unlocks = unlocks,
            customizePinSettings = FakePinSettings("1234"),
            pinLimiter = PinAttemptLimiter(clock = { t += 100; t }),
            keys = keys,
        )
    }

    @Test
    fun `无 PIN 时不算锁 即使 key 在锁集合里`() = runTest {
        val noPin = TvboxCategoryLock(store, unlocks, FakePinSettings(null), PinAttemptLimiter({ 0L }), keys)
        store.locked.add(key)
        assertFalse(noPin.seals(pid, sourceId, site, "1", "电影"))
    }

    @Test
    fun `sourceId 为 null 时不算锁`() = runTest {
        store.locked.add(key)
        assertFalse(lock.seals(pid, null, site, "1", "电影"))
    }

    @Test
    fun `key 不在锁集合时不算锁`() = runTest {
        assertFalse(lock.seals(pid, sourceId, site, "1", "电影"))
    }

    @Test
    fun `锁定且会话未解锁时为锁`() = runTest {
        store.locked.add(key)
        assertTrue(lock.seals(pid, sourceId, site, "1", "电影"))
    }

    @Test
    fun `锁定且本会话已解锁时放行`() = runTest {
        store.locked.add(key)
        assertTrue(unlocks.unlock(pid, MediaType.MOVIE, key))
        assertFalse(lock.seals(pid, sourceId, site, "1", "电影"))
    }

    @Test
    fun `搜索来源只有 typeName 也命中同一把规范 key 的锁`() = runTest {
        lock.toggleLock(pid, key, locked = true)
        assertTrue(lock.seals(pid, sourceId, site, null, "电影"))
        assertTrue(lock.seals(pid, sourceId, site, "1", "电影"))
    }

    @Test
    fun `通过 typeName 解锁后 typeId 形态的播放也放行`() = runTest {
        store.locked.add(key)
        val attempt = lock.unlock(pid, sourceId, site, null, "电影", "1234")
        assertTrue(attempt is PinAttempt.Accepted)
        assertFalse(lock.seals(pid, sourceId, site, "1", "电影"))
        assertFalse(lock.seals(pid, sourceId, site, null, "电影"))
    }

    @Test
    fun `身份解析不出 key 时不算锁`() = runTest {
        store.locked.add(key)
        assertFalse(lock.seals(pid, sourceId, site, null, "未索引的分类"))
    }

    @Test
    fun `身份解析不出 key 时解锁直接放行`() = runTest {
        val attempt = lock.unlock(pid, sourceId, site, null, "未索引的分类", "0000")
        assertTrue(attempt is PinAttempt.Accepted)
    }

    @Test
    fun `anySession 忽略解锁会话 - 重锁停播 guard 口径`() = runTest {
        store.locked.add(key)
        assertTrue(unlocks.unlock(pid, MediaType.MOVIE, key))
        assertTrue(lock.seals(pid, sourceId, site, "1", "电影", anySession = true))
    }

    @Test
    fun `错误 PIN 返回 Wrong 且不进会话`() = runTest {
        store.locked.add(key)
        val attempt = lock.unlockKey(pid, key, "9999")
        assertTrue(attempt is PinAttempt.Wrong)
        assertTrue(lock.seals(pid, sourceId, site, "1", "电影"))
    }

    @Test
    fun `relockAll 后解锁协议拒绝旧 epoch`() {
        val since = unlocks.epoch()
        unlocks.relockAll()
        // 与 TvboxCategoryLock.unlockKey 相同的协议：PIN 过了、epoch 变了也不放行
        assertFalse(unlocks.unlock(pid, MediaType.MOVIE, key, since))
    }

    @Test
    fun `toggleLock 只写单一规范 key`() = runTest {
        lock.toggleLock(pid, key, locked = true)
        assertEquals(setOf(key), store.locked)
        assertTrue(lock.seals(pid, sourceId, site, "1", "电影"))
        lock.toggleLock(pid, key, locked = false)
        assertEquals(emptySet<String>(), store.locked)
    }

    @Test
    fun `unlockKey 无 PIN 时直接放行`() = runTest {
        val noPin = TvboxCategoryLock(store, unlocks, FakePinSettings(null), PinAttemptLimiter({ 0L }), keys)
        assertTrue(noPin.unlockKey(pid, key, "0000") is PinAttempt.Accepted)
    }

    @Test
    fun `unlockKey 正确 PIN 进会话`() = runTest {
        store.locked.add(key)
        assertTrue(lock.unlockKey(pid, key, "1234") is PinAttempt.Accepted)
        assertFalse(lock.seals(pid, sourceId, site, "1", "电影"))
    }

    // ---- CoreLockKeys：生产身份解析（typeId 直拼 / typeName 按名回查 + 站点前缀限定） ----

    /** 只实现 observe 的 CategoryDao 假件，其余方法不应被触达。 */
    private class FakeCategoryDao(private val rows: List<CategoryEntity>) : CategoryDao {
        override suspend fun upsertAll(categories: List<CategoryEntity>): List<Long> = error("unused")
        override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> = error("unused")
        override suspend fun updateAll(categories: List<CategoryEntity>) = error("unused")
        override fun observe(sourceIds: List<Long>, type: MediaType): Flow<List<CategoryEntity>> =
            flowOf(rows.filter { it.sourceId in sourceIds && it.mediaType == type })
        override suspend fun getById(id: Long): CategoryEntity? = error("unused")
        override fun count(sourceIds: List<Long>, type: MediaType): Flow<Int> = error("unused")
        override suspend fun clear(sourceId: Long, type: MediaType) = error("unused")
        override suspend fun findByRemoteIds(sourceId: Long, type: MediaType, remoteIds: List<String>): List<CategoryEntity> = error("unused")
        override suspend fun remoteIdsForSource(sourceId: Long, type: MediaType): List<String> = error("unused")
        override suspend fun deleteByRemoteIds(sourceId: Long, type: MediaType, remoteIds: List<String>) = error("unused")
        override suspend fun deleteEmptyMovieCategories(sourceId: Long): Int = error("unused")
    }

    private fun row(id: Long, name: String, remoteId: String) =
        CategoryEntity(id = id, sourceId = sourceId, mediaType = MediaType.MOVIE, name = name, remoteId = remoteId)

    @Test
    fun `CoreLockKeys - typeId 在手直接拼规范 key`() = runTest {
        val resolver = CoreLockKeys(FakeCategoryDao(emptyList()))
        assertEquals(key, resolver.keyOf(sourceId, site, "1", "电影"))
    }

    @Test
    fun `CoreLockKeys - typeName 按名回查且站点前缀限定`() = runTest {
        // 同名分类来自别的站点（前缀不同）不能命中；本站点的行才解析得出。
        val resolver = CoreLockKeys(
            FakeCategoryDao(listOf(row(12, "电影", "other_site/9"), row(11, "电影", "local_cms/1"))),
        )
        assertEquals(key, resolver.keyOf(sourceId, site, null, "电影"))
    }

    @Test
    fun `CoreLockKeys - 查无分类行或名字为空返回 null`() = runTest {
        val resolver = CoreLockKeys(FakeCategoryDao(listOf(row(11, "电影", "local_cms/1"))))
        assertNull(resolver.keyOf(sourceId, site, null, "剧集"))
        assertNull(resolver.keyOf(sourceId, site, null, ""))
        assertNull(resolver.keyOf(sourceId, "other_site", null, "电影"))
    }
}
