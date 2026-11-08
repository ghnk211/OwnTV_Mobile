package tv.own.owntv.mobile.tvbox.category

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.categorylock.PinAttemptLimiter
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.CustomizePinSettings
import tv.own.owntv.mobile.tvbox.engine.ClassInfo

/**
 * 分类锁判定层的 JVM 单测（回归 D2/D3 的防线）：seals 的四步判定顺序、双轨 key 匹配
 * （typeId 写锁 / typeName 别名命中）、解锁会话的 epoch 防绕过协议。
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

    private val store = FakeLockStore()
    private val unlocks = CategoryUnlockSession()
    private lateinit var lock: TvboxCategoryLock

    private val pid = 1L
    private val site = "local_cms"

    @Before
    fun setUp() {
        var t = 0L
        lock = TvboxCategoryLock(
            store = store,
            unlocks = unlocks,
            customizePinSettings = FakePinSettings("1234"),
            pinLimiter = PinAttemptLimiter(clock = { t += 100; t }),
        )
    }

    @Test
    fun `无 PIN 时不算锁 即使 key 在锁集合里`() = runTest {
        val noPin = TvboxCategoryLock(store, unlocks, FakePinSettings(null), PinAttemptLimiter({ 0L }))
        store.locked.add("tvbox:$site:1")
        assertFalse(noPin.seals(pid, site, "1", "电影"))
    }

    @Test
    fun `key 不在锁集合时不算锁`() = runTest {
        assertFalse(lock.seals(pid, site, "1", "电影"))
    }

    @Test
    fun `锁定且会话未解锁时为锁`() = runTest {
        store.locked.add("tvbox:$site:1")
        assertTrue(lock.seals(pid, site, "1", "电影"))
    }

    @Test
    fun `锁定且本会话已解锁时放行`() = runTest {
        store.locked.add("tvbox:$site:1")
        assertTrue(unlocks.unlock(pid, MediaType.MOVIE, "tvbox:$site:1"))
        assertFalse(lock.seals(pid, site, "1", "电影"))
    }

    @Test
    fun `双轨 - 搜索来源只有 typeName 也能命中双写的锁`() = runTest {
        // 真实链路：toggleLock 双写主键与别名；搜索来源的播放只有 typeName
        lock.toggleLock(pid, site, "1", "电影", locked = true)
        assertTrue(lock.seals(pid, site, null, "电影"))
        // 历史单键锁（旧版本写入）在带 typeId 的调用上仍命中
        assertTrue(lock.seals(pid, site, "1", "电影"))
    }

    @Test
    fun `双轨 - 通过 typeName 解锁后 typeId 形态的播放也放行`() = runTest {
        store.locked.add("tvbox:$site:1")
        val attempt = lock.unlock(pid, site, null, "电影", "1234")
        assertTrue(attempt is PinAttempt.Accepted)
        assertFalse(lock.seals(pid, site, "1", "电影"))
        assertFalse(lock.seals(pid, site, null, "电影"))
    }

    @Test
    fun `双轨 - 通过 typeId 解锁后 typeName 形态的播放也放行`() = runTest {
        store.locked.add("tvbox:$site:1")
        val attempt = lock.unlock(pid, site, "1", "电影", "1234")
        assertTrue(attempt is PinAttempt.Accepted)
        assertFalse(lock.seals(pid, site, null, "电影"))
    }

    @Test
    fun `双轨 - keysOf 在 typeId 缺失时两形态合一`() {
        assertEquals(listOf("tvbox:$site:电影"), TvboxCategoryLock.keysOf(site, null, "电影"))
        assertEquals(listOf("tvbox:$site:电影"), TvboxCategoryLock.keysOf(site, "", "电影"))
    }

    @Test
    fun `双轨 - keysOf 在 typeId 存在时返回主键与别名`() {
        assertEquals(
            listOf("tvbox:$site:1", "tvbox:$site:电影"),
            TvboxCategoryLock.keysOf(site, "1", "电影"),
        )
    }

    @Test
    fun `anySession 忽略解锁会话 - 重锁停播 guard 口径`() = runTest {
        store.locked.add("tvbox:$site:1")
        assertTrue(unlocks.unlock(pid, MediaType.MOVIE, "tvbox:$site:1"))
        assertTrue(lock.seals(pid, site, "1", "电影", anySession = true))
    }

    @Test
    fun `错误 PIN 返回 Wrong 且不进会话`() = runTest {
        store.locked.add("tvbox:$site:1")
        val attempt = lock.unlock(pid, site, "1", "电影", "9999")
        assertTrue(attempt is PinAttempt.Wrong)
        assertTrue(lock.seals(pid, site, "1", "电影"))
    }

    @Test
    fun `relockAll 后解锁协议拒绝旧 epoch`() {
        val key = "tvbox:$site:1"
        val since = unlocks.epoch()
        unlocks.relockAll()
        // 与 TvboxCategoryLock.unlock 相同的协议：PIN 过了、epoch 变了也不放行
        assertFalse(unlocks.unlock(pid, MediaType.MOVIE, key, since))
    }

    @Test
    fun `toggleLock 双写主键与别名`() = runTest {
        lock.toggleLock(pid, site, "1", "电影", locked = true)
        assertEquals(setOf("tvbox:$site:1", "tvbox:$site:电影"), store.locked)
        assertTrue(lock.seals(pid, site, "1", "电影"))
        lock.toggleLock(pid, site, "1", "电影", locked = false)
        assertEquals(emptySet<String>(), store.locked)
    }

    @Test
    fun `sealedKeys 返回命中锁的完整 key`() = runTest {
        store.locked.add("tvbox:$site:1")
        val keys = lock.sealedKeys(pid, site, listOf(ClassInfo("1", "电影"), ClassInfo("2", "剧集")))
        assertEquals(setOf("tvbox:$site:1"), keys)
    }

    @Test
    fun `无 PIN 时 sealedKeys 为空集`() = runTest {
        val noPin = TvboxCategoryLock(store, unlocks, FakePinSettings(null), PinAttemptLimiter({ 0L }))
        store.locked.add("tvbox:$site:1")
        assertTrue(noPin.sealedKeys(pid, site, listOf(ClassInfo("1", "电影"))).isEmpty())
    }
}
