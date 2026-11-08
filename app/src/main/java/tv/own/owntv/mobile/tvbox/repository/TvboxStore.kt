package tv.own.owntv.mobile.tvbox.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.mobile.tvbox.util.FileUtil
import java.io.File

/**
 * TVbox 独立的进度 / 历史 / 收藏存储（方案 §5.4）。
 *
 * **不写 Core 表**：`VodPlayback.itemId` 是 Long，Core 的
 * `playback_progress` / `watch_history` / `favorites` 都以 `(profileId, mediaType, itemId)`
 * 为键且 itemId 必须是目录行 id；TVbox 条目只有 `siteKey + vodId` 字符串身份。
 * 落 app 私有 JSON（`files/tvbox/progress.json` 等），首版 schema：
 * `siteKey / vodId / episodeFlag / episodeId / episodeIndex / positionMs / durationMs / updatedAt`。
 */
class TvboxStore(private val context: Context) {

    data class ProgressEntry(
        val siteKey: String,
        val vodId: String,
        val episodeFlag: String,
        val episodeId: String,
        val episodeIndex: Int,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
    )

    data class HistoryEntry(
        val siteKey: String,
        val vodId: String,
        val name: String,
        val pic: String?,
        val episodeIndex: Int,
        val updatedAt: Long,
    )

    data class FavoriteEntry(
        val siteKey: String,
        val vodId: String,
        val name: String,
        val pic: String?,
        val addedAt: Long,
    )

    private val mutex = Mutex()

    private fun progressFile() = File(FileUtil.tvboxDir(context), "progress.json")
    private fun historyFile() = File(FileUtil.tvboxDir(context), "history.json")
    private fun favoritesFile() = File(FileUtil.tvboxDir(context), "favorites.json")

    fun keyOf(siteKey: String, vodId: String, episodeFlag: String, episodeId: String) =
        "$siteKey|$vodId|$episodeFlag|$episodeId"

    // ---------------- 进度 ----------------

    suspend fun saveProgress(entry: ProgressEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val map = readProgress().toMutableMap()
            map[keyOf(entry.siteKey, entry.vodId, entry.episodeFlag, entry.episodeId)] = entry
            writeProgress(map)
        }
    }

    suspend fun loadProgress(siteKey: String, vodId: String, episodeFlag: String, episodeId: String): ProgressEntry? =
        withContext(Dispatchers.IO) { readProgress()[keyOf(siteKey, vodId, episodeFlag, episodeId)] }

    suspend fun latestProgressForVod(siteKey: String, vodId: String): ProgressEntry? =
        withContext(Dispatchers.IO) {
            readProgress().values
                .filter { it.siteKey == siteKey && it.vodId == vodId }
                .maxByOrNull { it.updatedAt }
        }

    private fun readProgress(): Map<String, ProgressEntry> = runCatching {
        val text = FileUtil.readText(progressFile()) ?: return emptyMap()
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            ProgressEntry(
                siteKey = o.getString("siteKey"),
                vodId = o.getString("vodId"),
                episodeFlag = o.optString("episodeFlag"),
                episodeId = o.optString("episodeId"),
                episodeIndex = o.optInt("episodeIndex"),
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                updatedAt = o.optLong("updatedAt"),
            )
        }.associateBy { keyOf(it.siteKey, it.vodId, it.episodeFlag, it.episodeId) }
    }.getOrDefault(emptyMap())

    private fun writeProgress(map: Map<String, ProgressEntry>) {
        val arr = JSONArray()
        // 上限保护：只留最近 500 条，防长年累积撑爆文件
        map.values.sortedByDescending { it.updatedAt }.take(500).forEach { e ->
            arr.put(
                JSONObject()
                    .put("siteKey", e.siteKey)
                    .put("vodId", e.vodId)
                    .put("episodeFlag", e.episodeFlag)
                    .put("episodeId", e.episodeId)
                    .put("episodeIndex", e.episodeIndex)
                    .put("positionMs", e.positionMs)
                    .put("durationMs", e.durationMs)
                    .put("updatedAt", e.updatedAt),
            )
        }
        FileUtil.writeText(progressFile(), arr.toString())
    }

    // ---------------- 历史 ----------------

    suspend fun recordHistory(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readHistory().toMutableList()
            list.removeAll { it.siteKey == entry.siteKey && it.vodId == entry.vodId }
            list.add(0, entry)
            FileUtil.writeText(historyFile(), historyToJson(list.take(200)))
        }
    }

    suspend fun history(): List<HistoryEntry> = withContext(Dispatchers.IO) { readHistory() }

    private fun readHistory(): List<HistoryEntry> = runCatching {
        val text = FileUtil.readText(historyFile()) ?: return emptyList()
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                siteKey = o.getString("siteKey"),
                vodId = o.getString("vodId"),
                name = o.optString("name"),
                pic = o.optString("pic").takeIf { it.isNotEmpty() },
                episodeIndex = o.optInt("episodeIndex"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun historyToJson(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("siteKey", e.siteKey)
                    .put("vodId", e.vodId)
                    .put("name", e.name)
                    .put("pic", e.pic ?: "")
                    .put("episodeIndex", e.episodeIndex)
                    .put("updatedAt", e.updatedAt),
            )
        }
        return arr.toString()
    }

    // ---------------- 收藏 ----------------

    suspend fun toggleFavorite(entry: FavoriteEntry): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readFavorites().toMutableList()
            val existing = list.firstOrNull { it.siteKey == entry.siteKey && it.vodId == entry.vodId }
            if (existing != null) {
                list.remove(existing)
                FileUtil.writeText(favoritesFile(), favoritesToJson(list))
                false
            } else {
                list.add(0, entry)
                FileUtil.writeText(favoritesFile(), favoritesToJson(list))
                true
            }
        }
    }

    suspend fun isFavorite(siteKey: String, vodId: String): Boolean =
        withContext(Dispatchers.IO) { readFavorites().any { it.siteKey == siteKey && it.vodId == vodId } }

    suspend fun favorites(): List<FavoriteEntry> = withContext(Dispatchers.IO) { readFavorites() }

    private fun readFavorites(): List<FavoriteEntry> = runCatching {
        val text = FileUtil.readText(favoritesFile()) ?: return emptyList()
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FavoriteEntry(
                siteKey = o.getString("siteKey"),
                vodId = o.getString("vodId"),
                name = o.optString("name"),
                pic = o.optString("pic").takeIf { it.isNotEmpty() },
                addedAt = o.optLong("addedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun favoritesToJson(list: List<FavoriteEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("siteKey", e.siteKey)
                    .put("vodId", e.vodId)
                    .put("name", e.name)
                    .put("pic", e.pic ?: "")
                    .put("addedAt", e.addedAt),
            )
        }
        return arr.toString()
    }
}
