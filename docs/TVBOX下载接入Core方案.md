# TVBOX 下载接入 Core 方案

> 对象：`OwnTV_Mobile`（`app`）+ `OwnTV_Core`（`vendor/OwnTV_Core`）
> 前置：`docs/点播内容接入Core方案.md`（P0–P5 已实施）
> 状态：方案稿 v2（2026-11-14，审核修订；v1→v2 的逐项修订见 §15）
> 结论：**推翻该方案 §4.5.1 的"下载必须排除 TVBOX"**，改为"TVBOX 下载经 App 侧解析器接入 Core 下载队列"；本期只复用 Core 一套下载设施，不新建第二套 DownloadManager/Worker/通知/存储。
> v2 修正了原稿的编译级错误（`fun interface`、Room `defaultValue`）、P0 HLS 静默失败、SAF 下分片目录不可行、REPLACE 双写、离线续播 `extKey` 错位，以及"边看边下等待"对 TVBOX 不生效等问题。

---

## 1. 目标与三条红线

目标：点播（TVBOX）内容可离线下载、可断点续传、可从"下载内容"页离线播放，并与 Core 的档案、儿童模式、分类锁、进度/历史一致。

> 儿童模式口径：本方案复用现有"分类级 + 站点级"校验；**条目级 `isAdultItem` 过滤依赖前置方案 §4.5.2（P3，未落地）**。在此之前，下载入口与浏览侧存在同一残余缺口，发布说明中如实写明，不宣称"儿童完全覆盖"。

红线：

1. **不新建第二套下载设施**：队列、断点、通知、前台服务、存储目录、暂停/重试、Wi-Fi 限制继续由 Core `DownloadManager/DownloadEngine/DownloadWorker` 负责；TVBOX 连接数与 HLS 分片都按 1 条串行，不碰站点单流限制。
2. **`tvbox://` 永不出现在 HTTP 请求里**：`DownloadEntity.streamUrl` 只做身份句柄；真正的直链与 headers 在下载时由 App 侧解析器**每次现解析**，不落库用于传输。
3. **不新建 TVBOX 剧集表、不新增 `MediaType`**：一部点播片仍是一条 `movies` 行（`MediaType.MOVIE`）；"第几集/哪条线路"编码在下载行的 `extJson` 里。

---

## 2. 现状事实（代码取证）

| 事实 | 位置 | 影响 |
|---|---|---|
| Core 下载只认 http(s) 单文件：请求头只有 `User-Agent` + `Range`，没有 Referer/UA/Cookie，也没有 HLS 分支 | `core/download/DownloadEngine.kt:453-456`、`:223-262` | TVBOX 的 `tvbox://` 直接入队必失败；m3u8 下载会下到清单文本 |
| `DownloadEntity` 唯一索引是 `(profileId, mediaType, itemId)` | `core/database/entity/UserDataEntities.kt:294-324`、schema 54 | 一部点播剧有 40 集，只能存 1 条下载行 → 必须加区分列 |
| TVBOX 播放是两段式：`tvbox://` → `playerContent` → 命名解析/嗅探 → 直链 + headers | `app/.../tvbox/play/TvboxPlayResolver.kt:54-179` | 下载必须复用同一条解析链，不能只拿 `streamUrl` |
| TVBOX 一部片只有一条 `movies` 行；没有 `episodes/series` 行 | `app/.../tvbox/index/TvboxIndexMapper.kt:71-110` | 不能按 Core `EPISODE` 的 `itemId` 语义存下载 |
| 现有排除是临时兜底：`LibraryViewModel/DetailViewModel` 有 `tvbox://` 拦截，但 UI 入口仍显示；`ContentActions.download` 完全没有拦截 | `LibraryViewModel.kt:777-779`、`DetailViewModel.kt:400-401`、`ContentActions.kt:81-96` | 首页长按下载会入队并失败（已真机复现：`Expected URL scheme 'http' or 'https' but was 'tvbox'`） |
| TVBOX `SourceEntity.maxConnections = 0`（未知） | `app/.../TvboxSourceProvisioner.kt:54-77` | 下载会被 Core 当成"上限未知"，多连接拉满 5 条，易触发站点风控 |
| 索引 TTL 清理的保护名单只有 5 张用户态表，没有 `downloads` | `core/database/dao/MovieDao.kt:80-100` | 已下载但未收藏/未播过的点播片会在 30 天后被清掉索引，下载行失去片名/海报/分类归属 |
| 源项目已有完整 HLS 下载实现（分片并发、AES-128、fMP4、断点） | `E:\develop\androidPoject\video_on_demand\app\src\main\java\com\vod\player\download\{HlsDownloadPlanner,HlsSegmentDownloader}.kt`、`engine\m3u8\M3u8Purifier.kt` | TVBOX 主流是 m3u8，必须移植，否则功能基本不可用（实施前需确认该类在本机源项目中的实际版本/类名） |
| `WatchSession` 只由 `VodPlayback.sourceId` 登记，`playTvbox` 构造时未传 | `VodTuner.kt:147-156, 588-595`、`WatchSession.kt:34-62` | §5.3④/§6.7 的"边看边下等待/暂停"对 TVBOX 不生效，必须补 `sourceId` |
| `DownloadManager.enqueue` 是异步 `scope.launch`，`DownloadDao.upsert` 是 `@Insert(REPLACE)` | `DownloadManager.kt:104-124`、`DownloadDao.kt:16-17` | 重复入队会换主键；RUNNING 时重复点会两个 job 写同一文件（§5.2 必须查重） |
| `playerContent` 默认 `countHealth = true` | `SourceRepository.kt:217-229` | 后台下载解析失败会把源推进冷却，必须传 `false`（与 `NextEpisodePrefetcher` 同口径） |
| `MediaTarget`/`MediaRoot` 只有"单文件 + 相对目录树"，没有"在某个文档旁建目录"的 API | `MediaTarget.kt:31-200`、`MediaRoot.kt:49-86` | `<filePath>.parts/` 在 SAF 下不可行；HLS 分片只能放 App cache（§5.4） |
| `fun interface` 只允许一个抽象方法 | Kotlin 语言限制 | 文档原稿的 `DownloadSourceResolver` 有 `matches` + `suspend resolve` 两个抽象方法，写 `fun interface` 直接编译失败（§5.1） |

---

## 3. 方案总览

```
VOD 详情（选线路/选集）
  │  校验 kids/分类锁 → ensureIndexed 拿 movieId → resolver.resolve 验证可解析
  ▼
DownloadManager.enqueue(
  mediaType = MOVIE, itemId = movieId,
  streamUrl = "tvbox://<siteKey>/<vodId>",   // 只做身份
  identityKey = "siteKey|vodId|flag|episodeId",
  extJson = {siteKey,vodId,flag,episodeId,episodeName,index,lineName},
  httpHeaders = 首次解析得到的 headers, containerExt = … )
  ▼
Core downloads 表（唯一索引含 identityKey）
  ▼
DownloadWorker（WorkManager，同进程前台服务）
  └─ DownloadEngine.drainQueue
       ├─ DownloadSourceResolver（Core 定义 SPI，App 注册）
       │    └─ App: TvboxDownloadSourceResolver
       │         └─ playerContent → playUrl 前缀 → 命名解析 → 嗅探 → 直链 + headers
       ├─ 单文件传输：Range / 多连接 / 断点续传（现有）
       └─ HLS 传输：分片 + AES-128 + fMP4 + 分片级断点（新增）
  ▼
下载内容页 ──播放──► VodTuner.playTvboxDownload
                      ├─ 本地文件播放（不访问源站）
                      ├─ 进度写 Core（movieId + extKey=线路/集）
                      └─ 历史/收藏/继续观看 与在线播放一致
```

四个关键决策：

1. **身份与地址分离**：`streamUrl` 永远存 `tvbox://<siteKey>/<vodId>`；解析后的直链**不落库**（会过期、带 token）。
2. **解析方向反转**：Core 只定义 `DownloadSourceResolver` SPI；TVBOX 引擎在 App，App 在 `Application.onCreate` 注册实现。Core 不反向依赖 App。
3. **媒体类型保持 `MOVIE`，`itemId = movies.id`**：收藏、进度、历史、继续观看、播放器收藏按钮、分类锁全部沿用现有语义；多集靠新列 `identityKey` 区分。
4. **HLS 是 P1 必做项**：P0 先打通单文件直链与整条链路，P1 移植源项目 HLS 下载器；UI 对暂不支持的清单形态明确提示，而不是生成失败任务。

---

## 4. 数据模型（Core v55）

### 4.1 `DownloadEntity` 新增 5 列

```kotlin
@Entity(
    tableName = "downloads",
    foreignKeys = [/* 不变：profileId → profiles.id CASCADE */],
    indices = [
        Index("profileId"),
        Index("status"),
        // 替换原来的 (profileId, mediaType, itemId) 唯一索引
        Index(value = ["profileId", "mediaType", "itemId", "identityKey"], unique = true),
        // §4.4 的 TTL 保护 SQL 按 (mediaType, itemId) 反查，唯一索引以 profileId 开头用不上。
        Index(value = ["mediaType", "itemId"]),
    ],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val title: String,                 // TVBOX: "影片名 · 第N集"
    val posterUrl: String? = null,
    val streamUrl: String,             // TVBOX: "tvbox://<siteKey>/<vodId>"，只做身份
    val filePath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val reason: DownloadReason? = null,
    val httpCode: Int? = null,

    /**
     * 同一 itemId 下的下载区分键。
     * 普通源为 ""（保持原唯一语义）；TVBOX 为 `TvboxDownloadKey.identity(ref)`，
     * 不要用裸 `|` 拼接（flag 是 URL，可能自带 `|` 造成组合碰撞，见 §4.3）。
     * NOT NULL 是必须的：SQLite 唯一索引把 NULL 视为互不相等，可空列会让普通源重复入队。
     *
     * Room 的 defaultValue 是 **SQL 字面量**，空字符串必须写成 `"''"`（Core 现有先例：
     * ContentEntities.kt:208）。写成 `""` 会被当成未指定，与迁移 SQL 的 `DEFAULT ''` 不一致。
     */
    @ColumnInfo(defaultValue = "''") val identityKey: String = "",

    /** TVBOX 专用：{"kind":"tvbox","siteKey":…,"vodId":…,"flag":…,"episodeId":…,"episodeName":…,"index":N,"lineName":…} */
    val extJson: String? = null,

    /**
     * 首次解析得到的请求头（StreamHeaders.encode 格式），**仅作失败复盘/诊断**。
     * 传输只用 DownloadSourceResolver 每次返回的新鲜 headers；本列不参与请求。
     * 写库前过滤 Cookie/Authorization（或干脆不存），避免把 token 留在 DB；不进备份、不进日志。
     */
    val httpHeaders: String? = null,

    /** 入队时探测到的容器：mp4 / ts / flv；HLS 拼接产物为 ts 或 mp4。 */
    val containerExt: String? = null,

    /**
     * 传输形态（v55）。HLS 行需要分片下载、按分片统计进度，并在暂停/重试/删除时清理分片缓存
     * （位置见 §5.4：App 私有 cacheDir，**不是** `<filePath>.parts/`——SAF 文档旁不能建目录）；
     * 普通行恒为 NONE。存列而不是从 containerExt 反推：ts/mp4 既可能是直链也可能来自 HLS。
     *
     * 默认值同样必须是 SQL 字面量 `"'NONE'"`（Core 现有先例：TrendingEntities.kt:35）。
     */
    @ColumnInfo(defaultValue = "'NONE'") val manifest: DownloadManifest = DownloadManifest.NONE,
)
```

> `DownloadManifest` 定义在 `core/model/Enums.kt`（与 `DownloadStatus/DownloadReason` 并列），
> Room 按枚举名存 TEXT，无需 TypeConverter（与 `DownloadStatus/DownloadReason` 一致）。

> 为什么不把"集"建成 `episodes` 行：TVBOX 的线路/选集是远端详情页的瞬时结构，没有稳定 remoteId；一旦落成 Core 剧集，就要处理同步、TTL、备份、改集、换线全部语义，且与"一部片一条索引行"的既有决策冲突。`identityKey + extJson` 是更小的侵入面。

### 4.2 迁移 `MIGRATION_54_55`

```kotlin
val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(connection: SQLiteConnection) {
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "identityKey")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `identityKey` TEXT NOT NULL DEFAULT ''")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "extJson")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `extJson` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "httpHeaders")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `httpHeaders` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "containerExt")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `containerExt` TEXT")
        }
        if (!OwnTVDatabase.hasColumn(connection, "downloads", "manifest")) {
            connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `manifest` TEXT NOT NULL DEFAULT 'NONE'")
        }
        connection.execSQL("DROP INDEX IF EXISTS `index_downloads_profileId_mediaType_itemId`")
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_profileId_mediaType_itemId_identityKey` " +
                "ON `downloads` (`profileId`, `mediaType`, `itemId`, `identityKey`)",
        )
        // 这个非唯一索引不在 EXPECTED_NON_UNIQUE_INDEXES 里（downloads 不参与 BulkInsertHelper
        // 的目录重建，healSchema 不会替它建），必须由迁移自己建；漏了 Room 迁移后的 schema 校验
        // 会报 "Migration didn't properly handle: downloads"，整条升级链失败。
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_downloads_mediaType_itemId` " +
                "ON `downloads` (`mediaType`, `itemId`)",
        )
        OwnTVDatabase.healSchema(connection)
    }
}
```

同时：`OwnTVDatabase.version = 55`、`ALL_MIGRATIONS += MIGRATION_54_55`、提交 `schemas/55.json`。
`EXPECTED_NON_UNIQUE_INDEXES` **不需要**登记 downloads（该表不参与 `BulkInsertHelper` 的目录重建，且新索引是唯一索引）。

存量行 `identityKey = ''`，唯一性语义与旧索引一致，不需要数据清洗。

### 4.3 `extJson` / `identityKey` 编解码（App 纯函数）

```kotlin
object TvboxDownloadKey {
    data class Ref(val siteKey: String, val vodId: String, val flag: String,
                   val episodeId: String, val episodeName: String, val index: Int, val lineName: String)

    /**
     * 用 NUL 分隔，不要用裸 `|`：CMS 源的 `flag` 就是完整播放地址，可能自带 `|`，
     * `a|b`+`c` 与 `a`+`b|c` 会拼出同一个键。NUL 不会出现在 URL/网盘 id 中。
     * episodeId 与 flag 都为空时（个别站点），追加 `|index` 兜底，否则同影片各集会撞键。
     */
    fun identity(r: Ref): String =
        listOf(r.siteKey, r.vodId, r.flag, r.episodeId, if (r.flag.isBlank() && r.episodeId.isBlank()) r.index.toString() else "")
            .joinToString("\u0000")

    /**
     * 明细 JSON 的字段名与 `TvboxExtKey` 保持一致（flag/episodeId/index），
     * 离线播放写回 `playback_progress.extKey` 时可直接复用 `TvboxExtKey.encode`，不要出现第三套命名。
     */
    fun encode(r: Ref): String = JSONObject().apply {
        put("kind", "tvbox"); put("siteKey", r.siteKey); put("vodId", r.vodId)
        put("flag", r.flag); put("episodeId", r.episodeId)
        put("episodeName", r.episodeName); put("index", r.index); put("lineName", r.lineName)
    }.toString()
    fun decode(json: String?): Ref? { /* 空/非法/kind≠tvbox → null */ }
}
```

- `flag`/`episodeId` 可含任意字符（URL/网盘 id），所以 `identityKey` 只做索引、`extJson` 做明细，键里不拼可读分隔符。
- `identityKey` 不反解析（明细以 `extJson` 为准）；`Ref` 增删字段时必须同时改 `identity` 与 `extJson`，否则同一集会变成两行。

### 4.4 索引生命周期：把 downloads 加进保护名单

`MovieDao.pruneIndexedBefore` 与 `leastRecentlySeenCandidates` 各追加一条：

```sql
AND id NOT IN (SELECT itemId FROM downloads WHERE mediaType = 'MOVIE')
```

否则"下载完但没播过"的点播片 30 天后索引被清，下载行虽在，但片名/海报/分类归属/离线播放的锁校验全部退化成 `Unresolved`。

补充约束：

- **两条查询都要加**（`pruneIndexedBefore` 与 `leastRecentlySeenCandidates`），漏一条，行数上限淘汰仍会删掉已下载片。
- **必须带 `mediaType = 'MOVIE'`**：`downloads.itemId` 同时存 `movies.id` 与 `episodes.id`，数值会撞车；只按 `itemId` 子查询会错误保护一部同号的电影。
- `downloads` 表要补 `(mediaType, itemId)` 索引（§4.1）：新唯一索引以 `profileId` 开头，这个子查询用不上；少了它就是每次 TTL/上限清理对 `downloads` 全表扫。
- 保护名单只防 TTL 与行数淘汰，**不防源删除/重同步**：`MovieDao.clearSource(sourceId)` 与 re-sync 换 id 仍会让下载行失去片名。后一种是既有语义（`playDownload` 已按 `Unresolved` 处理），本期不扩大范围。

---

## 5. Core 侧改造

### 5.1 解析 SPI：`DownloadSourceResolver`

```kotlin
// core/download/DownloadSourceResolver.kt
data class DownloadSource(
    val url: String,
    val headers: Map<String, String>,
    /** 0 = 未知；TVBOX 固定 1，避免把站点当成可多连接 CDN。 */
    val maxConnections: Int = 0,
    val manifest: DownloadManifest = DownloadManifest.NONE,
    val containerExt: String? = null,
)

enum class DownloadManifest { NONE, HLS }   // 实际定义在 core/model/Enums.kt（§4.1）

/**
 * **不能用 `fun interface`**：Kotlin 的 SAM 转换只允许一个抽象方法，而这里既有 `matches`
 * 又有 `suspend resolve`，写成 `fun interface` 直接编译失败。
 */
interface DownloadSourceResolver {
    fun matches(d: DownloadEntity): Boolean
    /** 每次传输尝试都会调用一次，必须返回新鲜地址；失败抛异常（由引擎按尝试次数重试）。 */
    suspend fun resolve(d: DownloadEntity): DownloadSource
}

object DownloadSourceResolvers {
    @Volatile private var installed: DownloadSourceResolver? = null
    fun install(r: DownloadSourceResolver) { installed = r }
    /** 单测用：Core 的 `:core:test` 不能依赖 App，必须能把全局实现摘掉。 */
    internal fun clear() { installed = null }
    internal fun forDownload(d: DownloadEntity): DownloadSourceResolver? =
        installed?.takeIf { it.matches(d) }
}
```

安装时机：`OwnTVMobileApp.onCreate`（Koin 启动后）调用 `DownloadSourceResolvers.install(get<TvboxDownloadSourceResolver>())`。WorkManager 默认同进程，进程被拉起时 `Application.onCreate` 仍会执行，因此无头下载也能拿到实现。

### 5.2 `DownloadManager.enqueue` 增加可选身份参数

```kotlin
fun enqueue(
    profileId: Long, mediaType: MediaType, itemId: Long, title: String, posterUrl: String?,
    streamUrl: String, relativeDir: String, fileName: String,
    identityKey: String = "", extJson: String? = null,
    httpHeaders: String? = null, containerExt: String? = null,
    manifest: DownloadManifest = DownloadManifest.NONE,
)

/**
 * 同步版本：内部 `withContext(Dispatchers.IO)` 完成建文件 + upsert，返回行 id；批量下载用。
 * 现有 `enqueue` 保持原样（`scope.launch`、Unit），不破坏已有调用点。
 */
suspend fun enqueueNow(
    profileId: Long, mediaType: MediaType, itemId: Long, title: String, posterUrl: String?,
    streamUrl: String, relativeDir: String, fileName: String,
    identityKey: String = "", extJson: String? = null,
    httpHeaders: String? = null, containerExt: String? = null,
    manifest: DownloadManifest = DownloadManifest.NONE,
): Long?
```

- 继续保留 `settings.downloadsAllowed(profileId)` 门禁（UI 隐藏之外的第二道）。
- **REPLACE 不是"无害覆盖"**：`DownloadDao.upsert` 是 `@Insert(REPLACE)`，冲突时会 DELETE 旧行再 INSERT，主键 `id` 会变。若同一集正在 RUNNING，旧 job 仍持有旧 id、继续往同一个 `filePath` 写；新行又起一个 job → **两个传输写同一个 SAF 文档，文件必损坏**。因此入队前必须先查重：
  ```kotlin
  val existing = downloadDao.find(profileId, itemId, identityKey)
  when (existing?.status) {
      QUEUED, RUNNING, COMPLETED -> return   // 已在队列/已完成，不重复入队
      PAUSED -> manager.resume(existing)
      FAILED -> manager.retry(existing)
      null -> manager.enqueue(...)
  }
  ```
- 批量下载（整线路 N 集）不能 `forEach { enqueue(...) }`：`enqueue` 内部是 `scope.launch`，N 个协程会并发建文件/写库。应在 VM 的单一协程里逐条调用（或给 `DownloadManager` 增加 `suspend fun enqueueNow(...)`，内部 `withContext(Dispatchers.IO)` 串行完成 upsert）。

### 5.3 `DownloadEngine` 四处改动

**① 解析优先级**（替换 `resolveTarget` 的入口判断）：

```kotlin
private suspend fun resolveTarget(d: DownloadEntity): Resolved {
    DownloadSourceResolvers.forDownload(d)?.let { provider ->
        val src = try {
            provider.resolve(d)                         // 每次尝试现解析
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DownloadFailure(DownloadReason.SOURCE_UNAVAILABLE)
        }
        val movieSourceId = movieDao.getById(d.itemId)?.sourceId
        return Resolved(
            url = src.url, headers = src.headers,
            userAgent = src.headers.firstOrNull { it.key.equals("User-Agent", true) }?.value
                ?: HttpClient.DEFAULT_USER_AGENT,
            sourceId = movieSourceId,
            maxConnections = src.maxConnections.coerceAtLeast(1),
            manifest = src.manifest,
        )
    }
    // ↓ 原有 Stalker / M3U / Xtream 分支保持不变
}
```

> `Resolved` 是 `DownloadEngine.kt:458` 的 private 类，需加 `val headers: Map<String, String>` 与 `val manifest: DownloadManifest` 两个字段；原有 Stalker 分支构造时传 `headers = emptyMap()`、`manifest = DownloadManifest.NONE`。

**② 请求头**（单文件传输）：

```kotlin
private fun request(r: Resolved, from: Long, to: Long?): Request {
    val b = Request.Builder().url(r.url).header("User-Agent", r.userAgent)
    r.headers.forEach { (k, v) ->
        StreamHeaders.canonicalName(k)?.let { if (!it.equals("Range", true)) b.header(it, v) }
    }
    return b.header("Range", "bytes=$from-${to ?: ""}").build()
}
```

- **HLS 分片/密钥请求不要复用这个函数**：它强制写 `Range`，而 HLS 的 `EXT-X-BYTERANGE` 需要自己的区间，密钥请求则根本不能带 Range。HLS 侧另写 `segmentRequest(url, headers, userAgent, range: LongRange?)`，`Range` 只在 `byterange` 声明时添加。
- `StreamHeaders` 已拒绝 `Host/Content-Length/Connection/Transfer-Encoding/Range`，不会破坏传输层。
- 直链 30x 跳转沿用 OkHttp 默认行为；跨域时 `Authorization` 由 OkHttp 自行剥除。

**③ scheme 兜底**：没有解析器且 `streamUrl` 不是 `http/https` 时，直接抛 `DownloadFailure(DownloadReason.UNSUPPORTED_SOURCE)`，不再产生误导性的 `CONNECTION_LOST`（即本次真机回归看到的"连接已断开"）。`DownloadReasons.ofError` 同步加 `"Expected URL scheme"` 映射，覆盖旧路径。

**④ 连接数**：TVBOX 解析器返回 `maxConnections = 1`；同时 `TvboxSourceProvisioner` 建源时写 `maxConnections = 1`。但要兑现"边看边下不互抢"，还差一步：`waitsForWatching` 与 `DownloadWatchGuard` 都只看 `WatchSession`，而 `WatchSession` 只由 `VodPlayback.sourceId` 登记（`VodTuner.kt:147-156`）。当前 `playTvbox` 构造 `VodPlayback` 时**没有传 sourceId**（`VodTuner.kt:588-595`），所以 TVBOX 播放根本不占"源"，上面两处护栏对 TVBOX 永远不会触发。P0 必须把 Core 行的 `sourceId`（`movieDao.getById(coreId)?.sourceId`；`VodTuner` 已有 `movieDao`，不要为此引入 `provisioner`）作为 `VodPlayback.sourceId` 传进 `playTvbox`（本地离线播放不传，见 §6.5）。

**⑤ P0 的 HLS 必须明确拒绝**：P0 没有 HLS 分支，`manifest == HLS` 若落入 `streamWhole`，会把 m3u8 清单文本当视频下完并标 COMPLETED（用户点开是乱码）。P0 在 `attemptDownload` 的最前面加：

```kotlin
if (resolved.manifest == DownloadManifest.HLS) {
    throw DownloadFailure(DownloadReason.UNSUPPORTED_MANIFEST)
}
```

P1 再把这一行替换成 `HlsDownloader` 分支（§5.4）。UI 侧同时在入队前拦截（§6.3），两道都要有。

### 5.4 HLS 下载（P1）

新增 `core/download/hls/`：

| 新文件 | 职责 |
|---|---|
| `HlsUrls.kt` | `isM3u8(url)`：按路径后缀 `.m3u8/.m3u` 判断（**忽略 query**），`DownloadEngine` 与 App 共用，避免两处各写一套 |
| `HlsPlaylist.kt` | 解析 master/media playlist；选最高带宽 variant；相对 URI 按 base URL 解析；输出 `segments[{index, mediaSequence, url, byterange, key, duration}]`、`initMap`、`hasAes128`、`drm` |
| `HlsDownloader.kt` | 分片**串行**下载（见下）、单片 3 次指数退避、AES-128 解密（IV = 声明值或 mediaSequence 大端）、fMP4 init 段、按序拼接；`segmentRequest()` 独立于单文件的 `request()` |
| `HlsParts.kt` | 分片缓存目录（**App 私有 `context.cacheDir/hls/<identityHash>/`**，不是 `<filePath>.parts/`；`identityHash` 由 `HlsParts` 对 `identityKey` 取 SHA-256 前 16 位，不用行 `id`，避免 REPLACE 换 id 后留孤儿目录）：`{seq}.part` 原子 rename、`progress.json`（playlistHash/completed/bytes），续传只跳过"文件存在且大小正确"的分片 |

> **为什么不能放 `<filePath>.parts/`**：`DownloadEntity.filePath` 可能是 SAF 文档 URI（用户选文件夹），`MediaTarget`/`MediaRoot` 只提供"创建一个文件"和"创建相对目录树"，没有"在某个文档旁建目录"的能力（`MediaTarget.kt:31-200`、`MediaRoot.Tree.child`）。把分片放 App 私有 cache 是唯一不扩存储抽象的路线；代价是系统可能清 cache，所以恢复时必须逐片校验存在性 + `playlistHash`，不能只信 `progress.json`。

接入 `DownloadEngine.attemptDownload`（P1，替换 P0 的拒绝分支）：

```kotlin
if (resolved.manifest == DownloadManifest.HLS) {
    return HlsDownloader(context, client).download(d, target, resolved, onProgress)
}
```

约束：

- **分片串行**：解析器固定 `maxConnections = 1`，HLS 的分片请求也是打到同一站点，不能按"分片并发 2"跑（会把站点单流限制/风控触发，和 §1 红线冲突）。真要为分片开并发，只能对"分片域名与 API 域名不同、且确认是 CDN"的站点单独放开，P1 不做。
- 输出容器：TS 分片 → `.ts`；含 `#EXT-X-MAP` 的 fMP4 → `.mp4`（入队时探测一次清单决定 `containerExt`，避免 SAF 文档创建后无法改名）。探测失败（网络/解析）回退 `.mp4`，并在下载行 `reason` 留 `SOURCE_UNAVAILABLE` 以外的可重试提示。
- `totalBytes`：首次解析后写"所有分片声明大小的和"；清单没有 `EXT-X-BYTERANGE` 且只能靠 `Content-Length` 时，边下边累加，未知阶段写 0（UI 显示不确定进度，不能除零）。只写 `downloadedBytes` 不写 `totalBytes`，进度环会永远停在 0%。
- 不支持：`SAMPLE-AES`/DRM/`EXT-X-KEY:METHOD=SAMPLE-AES` → `UNSUPPORTED_MANIFEST`，UI 提示"该片源有版权保护，暂不支持离线下载"。
- `pause` 保留 cache；`retry` 清空 cache 并从 0 开始；`delete` 一并删除 `cacheDir/hls/<identityHash>/`。三者都在 `DownloadManager` 里做（它持有 `context`），Engine 不删。
- 进度：`manifest == HLS` 的行，`downloadedBytes` 取"已完成分片字节和"（由 `HlsParts` 计算），不取目标文件长度（拼接前为 0）。`DownloadEngine.bytesSoFar` 与 `DownloadManager.bytesOf` 都按 `d.manifest` 分支；普通行行为不变。
- 清单/密钥/分片请求都要带解析器给的 headers（Referer/UA/Cookie），密钥请求不带 `Range`。

> 可直接移植源项目 `HlsDownloadPlanner` / `HlsSegmentDownloader` 的算法与单元测试（实施前先确认 `E:\develop\androidPoject\video_on_demand` 的类名与当前版本一致）；`M3u8Purifier` 依赖 App 的广告规则，本期**不**移植净化（TVBOX 点播分片广告少见），保持"下载=原始清单"。

### 5.5 失败原因新增

`DownloadReason` 追加：

| 值 | 文案（en / zh-CN / zh-TW） | 场景 |
|---|---|---|
| `SOURCE_UNAVAILABLE` | Source unavailable / 源站解析失败，可稍后重试 / 來源解析失敗 | `playerContent` 失败、站点/配置已删、直链过期 |
| `UNSUPPORTED_SOURCE` | This source can't be downloaded / 该片源不支持下载 | 无解析器且不是 http(s) |
| `UNSUPPORTED_MANIFEST` | Protected stream can't be saved / 受保护片源不支持离线下载 | DRM / SAMPLE-AES |

`DownloadReasons.ofError` 增加 `"Expected URL scheme"` → `UNSUPPORTED_SOURCE` 的映射，保证旧路径也不再说"连接已断开"。

**必须同步改的三处**（漏一处就编译失败或 UI 空白）：

1. `model/Enums.kt` 的 `DownloadReason` 枚举加 3 个值。
2. `download/DownloadReasons.kt` 的 `displayTextOf` 是**穷尽 `when`（无 else）**，必须补 3 个分支，并新增 `R.string.content_downloads_reason_source_unavailable` / `_unsupported_source` / `_unsupported_manifest`。
3. `core/src/main/res/values{,-zh-rCN,-zh-rTW}/strings.xml` 三套文案；`verifyI18nLiterals` 会挡未分类字面量。

> `DownloadEntity.reason` 按枚举名存 TEXT。新增值对"旧版本读新库"是破坏性的（`valueOf` 失败），但下载行不参与备份/跨设备同步，降级安装只会影响本机已有行，可接受；不要在 `TvboxDownloadKey` 或其他跨设备格式里复用这个枚举名。

### 5.6 DAO 与查询

新增：

```kotlin
@Query("SELECT * FROM downloads WHERE profileId = :pid AND mediaType = 'MOVIE' AND itemId = :movieId")
fun observeForMovie(pid: Long, movieId: Long): Flow<List<DownloadEntity>>

// mediaType 不能省：movies.id 与 episodes.id 是两张表各自的自增，数值可能相同；
// 只按 itemId + identityKey 查会命中另一类型的普通下载行（identityKey 同为 ''）。
@Query("SELECT * FROM downloads WHERE profileId = :pid AND mediaType = 'MOVIE' AND itemId = :movieId AND identityKey = :key LIMIT 1")
suspend fun find(pid: Long, movieId: Long, key: String): DownloadEntity?
```

`DownloadDao.upsert(REPLACE)` 只用于"确定要新建"的路径；重复入队前先 `find`（§5.2），否则会换 id 并可能双写。

### 5.7 通知与锁

- 通知标题用 `progress.title`（入队时写"影片名 · 第N集"），无需改通知结构。
- `LockedNotificationTitles.hidesDownload` 对 `mediaType = MOVIE` 查 `movieDao` 分类，TVBOX 行天然适用（`itemId = movieId`）。

---

## 6. App 侧改造

### 6.1 `TvboxDownloadSourceResolver`（Core SPI 实现）

```kotlin
class TvboxDownloadSourceResolver(
    private val configRepository: TvboxConfigRepository,
    private val sourceRepository: SourceRepository,
    private val resolver: TvboxPlayResolver,
    private val runtime: TvboxRuntime,
    private val movieDao: MovieDao,
) : DownloadSourceResolver {

    // 只用 streamUrl 判定：extJson 损坏时也要交给 resolve 报 SOURCE_UNAVAILABLE，
    // 不能因为 extJson == null 就落到 scheme 兜底的 UNSUPPORTED_SOURCE（会被误读成"该源不支持下载"）。
    override fun matches(d: DownloadEntity) =
        d.streamUrl.startsWith(TvboxIndexMapper.STREAM_PREFIX)

    override suspend fun resolve(d: DownloadEntity): DownloadSource {
        val ref = TvboxDownloadKey.decode(d.extJson) ?: error("bad tvbox download identity")
        // 借源条目（donorSiteKey）的 origin 在 Core 行里，ref.siteKey 只是入队时的兜底。
        val movie = movieDao.getById(d.itemId)
        val siteKey = movie?.let { TvboxIndexMapper.originSiteKey(it.remoteId, it.extJson) } ?: ref.siteKey
        val site = configRepository.siteMap[siteKey] ?: error("site $siteKey is gone")

        // 只恢复配置 + 引擎；不要调 TvboxRuntime.restore()：
        // 它内部会 syncAll() + maintainIndex()，无头进程里 configs 尚未加载时 syncAll 会把
        // 所有 TVBOX 源的 syncMovies 关掉（Provisioner.syncAll 的 "known 为空" 分支）。
        runtime.ensureReady()

        // 后台下载失败不应把源推进冷却（与 NextEpisodePrefetcher 同一口径）。
        val play = resolver.resolveUrl(site, ref.flag, ref.episodeId, countHealth = false)
            ?: error("playerContent failed for ${site.key}")
        val hls = HlsUrls.isM3u8(play.url)
        return DownloadSource(
            url = play.url,
            headers = play.headers,
            maxConnections = 1,
            manifest = if (hls) DownloadManifest.HLS else DownloadManifest.NONE,
            containerExt = probeContainer(play.url, hls), // m3u8 → 拉一次清单判 ts/mp4；直链取后缀，失败回退 mp4
        )
    }
}
```

`probeContainer(url, hls)` 的规则：HLS 清单拉一次 master，含 `#EXT-X-MAP` → `mp4`，否则 `ts`（探测失败回退 `mp4`）；直链用 `StorageAccess.extOf(url)`（Core 已有，无后缀回退 `mp4`）。P0 已拒绝 HLS，实际只会走直链分支。

**必要重构**：把 `TvboxPlayResolver.resolve(...)` 里的"锁/儿童校验 + 解析编排"拆成三层——

- `resolve(...)`：现有签名，先做 `blockedByKids/sealed` 校验，再调 `resolveUrl(...)`（入队时用，保证越权内容不能下载）。
- `resolveUrl(site, flag, episodeId, countHealth)`：只做 `playerContent → playUrl 前缀 → 命名解析 → 嗅探 → 直链 + headers`（下载重试时用，不因锁状态变化中断已授权的传输）。
- 返回类型用**轻量** `ResolvedStream(url: String, headers: Map<String, String>)`，不要返回 `ResolvedPlay`：`ResolvedPlay` 需要 `lines/title/poster/categoryKey` 等详情页上下文，下载重试时没有这些。`resolve()` 内部再用 `ResolvedStream` 补成完整 `ResolvedPlay`。
- **WebView 解析必须在主线程**：`SniffEngine.sniffResult` 在 `newWebViewOrNull()` 返回后**直接**读 `webView.settings`（`SniffEngine.kt:285`），这一步要求 Main 调度器，而 `sniffResult` 本身没有整体 `withContext(Main)`。现有调用都来自 `viewModelScope`（Main），所以没暴露；下载 resolver 从 WorkManager 的 IO 协程调用会触发 `WebViewMethodCalledOnWrongThreadViolation`。`resolveUrl` 必须把"嗅探/聚合页"分支包进 `withContext(Dispatchers.Main)`，**不要整段切主线程**（网络请求仍在 IO）。
- **无头进程的 WebView / 代理**：WebView 可在 Application 进程创建（有主 Looper），但后台限制可能让嗅探超时；`JarProxyHub.ensureStarted()` 由 `JarEngine`/`JsSpider` 在解析时触发，下载前仍要检查 `isReady`，失败给 `SOURCE_UNAVAILABLE` 而不是崩溃。P0 真机必须单独验证"杀 App 后 WorkManager 拉起 → 嗅探站点下载"，不支持的站点明确报错。

其余复用点：

- `applyPlayUrlPrefix`、`JarProxyHub.isSelfProxyUrl/isReady`、`ParseRepository.pick/resolve`、`SniffEngine` 原样保留。
- `direct()` 生成的 `headers` 已包含站点 header、UA、Referer，并经过 `legalHeaders()`，直接给 Core 用。

### 6.2 注册与启动

```kotlin
// OwnTVMobileApp.onCreate()
startKoin { … }
DownloadSourceResolvers.install(get<TvboxDownloadSourceResolver>())
```

注意：

- 必须放在 `startKoin` 之后、任何下载可能启动之前。
- **`TvboxRuntime.restore()` 不是"每进程一次"的幂等函数**：它内部只有 `maintainIndex` 有 `@Volatile indexMaintained` 保护；`config.restoreActive()` 与 `provisioner.syncAll()` 每次都会执行（`TvboxRuntime.kt:65-82`）。而 `Provisioner.syncAll()` 在 `configs` 为空时会把所有已有 TVBOX 源的 `syncMovies` 置 false（`TvboxSourceProvisioner.kt:84-87`）。无头下载进程每次重启都调一次 `restore()` 会放大这个副作用。正确做法：
  - 新增 `TvboxRuntime.ensureReady()`：只做 `config.purgeInvalidJars() + config.restoreActive()`（不 `syncAll`、不 `maintainIndex`），供下载解析调用；
  - `Application.onCreate` 的既有 `appScope.launch { runtime.restore() }`（`OwnTVMobileApp.kt:171-177`）保持不动，它负责索引维护。
- 两个协程可能并发 `restoreActive()`；该方法有 `mutex`（`TvboxConfigRepository.kt:174-178`）但会重复 `applyConfig`。`ensureReady()` 应复用同一把锁，或以 `activeConfig.first { it != null }` 等待，避免并发应用配置。
- 下载进程不要通过 `android:process` 隔离，否则 App 的引擎与代理不可用。

### 6.3 点播详情：下载入口

`TvboxDetailPage` 现有只有返回/收藏（`TvboxPages.kt:697-845`）。新增：

1. **每集格子右上角状态徽标**（三态）：
   - `↓`：未下载 → 点击入队；
   - 进度环/百分比：QUEUED/RUNNING/PAUSED → 点击暂停/取消；
   - `✓`：COMPLETED → 点击删除（二次确认）。
   格子主体仍然点击播放，避免改变现有交互。
2. **线路级按钮**："下载本线路（N 集）"，复用 `BulkDownloadConfirm` 的确认弹窗。
3. 状态来源：`TvboxDetailViewModel` 注入 `DownloadManager`。`state` 是 `StateFlow`，`movieId` 变化必须参与 combine，否则索引补建成功后状态不刷新：
   ```kotlin
   private val pidFlow = settings.activeProfileId

   @OptIn(ExperimentalCoroutinesApi::class)
   val downloads: StateFlow<Map<String, DownloadEntity>> =
       combine(pidFlow, _state.map { it.movieId }) { pid, movieId -> pid to movieId }
           .flatMapLatest { (pid, movieId) ->
               if (pid < 0 || movieId == null) flowOf(emptyMap())
               else downloadManager.observe(pid).map { list ->
                   list.filter {
                       it.mediaType == MediaType.MOVIE && it.itemId == movieId &&
                           it.streamUrl.startsWith(TvboxIndexMapper.STREAM_PREFIX)
                   }.associateBy { it.identityKey }
               }
           }
           .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
   ```
4. 入队动作：
   ```kotlin
   suspend fun downloadEpisode(index: Int, all: Boolean = false) {
       val s = _state.value; val site = s.site ?: return
       val categoryKey = categoryKey ?: return
       if (s.blockedByKids || s.sealed) return
       val pid = settings.activeProfileId.first()
       // ensureIndexed 不是无参函数：需要 sourceId/site/detailMovie；detailMovie 在 open() 里已保存。
       val movieId = s.movieId ?: detailMovie?.let { movie ->
           provisioner.activeSourceId()?.let { sourceId ->
               indexer.ensureIndexed(
                   sourceId, site, movie,
                   categoryTypeId = categoryKey.typeId, categoryTypeName = categoryKey.typeName,
               )
           }
       } ?: return banner(NO_INDEX)

       val line = s.lines.getOrNull(s.currentLineIndex) ?: return
       val targets = if (all) line.episodes else listOfNotNull(line.episodes.getOrNull(index))
       if (targets.isEmpty()) return

       // 先解析一次：验证可下载 + 拿 headers/容器；失败直接提示，不入队。
       // 注意 Outcome 是 sealed：Blocked 没有 message，只有 Error 有。
       val first = targets.first()
       val sample = resolver.resolve(
           profileId = pid, site = site, vodId = s.vodId,
           flag = first.flag, episodeId = first.id, episodeIndex = first.index,
           categoryKey = categoryKey, detailLines = s.lines, title = s.name,
           subtitle = first.name, posterUrl = s.pic,
       )
       when (sample) {
           is TvboxPlayResolver.Outcome.Blocked -> return
           is TvboxPlayResolver.Outcome.Error -> return banner(sample.message)
           is TvboxPlayResolver.Outcome.Ready -> Unit
       }
       val play = (sample as TvboxPlayResolver.Outcome.Ready).play
       // P0：HLS 不支持，必须在这里拦。否则 Engine 会把清单文本下完并标 COMPLETED。
       if (HlsUrls.isM3u8(play.url)) return banner(HLS_SOON)
       val containerExt = TvboxDownloadPaths.probeContainer(play.url)

       // 逐条串行：enqueueNow 是新增的 suspend 方法（内部 withContext(IO) 完成建文件 + upsert），
       // 不能用现有 enqueue 的 scope.launch 在循环里并发 40 集。
       for (ep in targets) {
           val ref = TvboxDownloadKey.Ref(
               // 详情页的 site 就是实际能解析成功的站点（open() 从 siteMap 取的就是它）。
               // detailMovie 是 TVBOX 的 Movie，没有 remoteId/extJson 字段；借源 origin 要读 Core 行，
               // P0 直接用 site.key，P2 再从 movieDao.getById(movieId) 取 remoteId/extJson 校正。
               siteKey = site.key,
               vodId = s.vodId, flag = ep.flag, episodeId = ep.id,
               episodeName = ep.name, index = ep.index, lineName = line.name,
           )
           val key = TvboxDownloadKey.identity(ref)
           val existing = downloadManager.find(pid, movieId, key)
           if (existing != null) {
               when (existing.status) {
                   QUEUED, RUNNING, COMPLETED -> Unit      // 已在队列/已完成：不重复入队，避免 REPLACE 换 id 双写
                   PAUSED -> downloadManager.resume(existing)
                   FAILED -> downloadManager.retry(existing)
               }
               continue
           }
           downloadManager.enqueueNow(
               profileId = pid, mediaType = MediaType.MOVIE, itemId = movieId,
               title = "${s.name} · ${ep.name.ifBlank { "第${ep.index + 1}集" }}",
               posterUrl = s.pic, streamUrl = "tvbox://${site.key}/${s.vodId}",
               relativeDir = TvboxDownloadPaths.dir(s.name, line.index, line.name),
               fileName = TvboxDownloadPaths.fileName(ep, containerExt),
               identityKey = key, extJson = TvboxDownloadKey.encode(ref),
               // 该列只作诊断；过滤敏感头，避免把 Cookie/token 留在 DB（§12）。
               httpHeaders = StreamHeaders.encode(
                   play.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) },
               ),
               containerExt = containerExt,
           )
       }
   }
   ```
   - 入口渲染：`settings.activeDownloadsAllowed` 是 `Flow<Boolean>`，UI 用 `collectAsStateWithLifecycle()`；VM 不需要再判一次。详情页 `blockedByKids/sealed` 时不渲染入口；入队前 `resolver.resolve` 再校验一次。
   - `TvboxDownloadPaths.dir(name, lineIndex, lineName)`：`Movies/<sanitize(影片名)>/<lineIndex+1>-<sanitize(线路名)>/`。**线路 index 必须进目录名**——多个线路可能同名（都叫"线路1"），只用线路名会让同集文件互相覆盖（SAF `findFile` 命中已有文件后直接截断/追加）。
   - 文件名：`<sanitize(集名)>.<ext>`，空名回退 `ep-<index+1>`；`ext` 由 `probeContainer` 决定（直链取 `StorageAccess.extOf`，失败回退 `mp4`）。
   - 每集格子的 Lazy key 用 `episode.index`（列表下标），**不要用 `episode.id`**：CMS 源的 id 是播放地址，经常重复，Compose 会因重复 key 崩溃（clun.top 站点列表事故同型）。
   - `TvboxDetailPage` 新增参数（`downloads`/`onDownload`/`onPause`/`onDelete`）后，`VodScreen.kt` 的调用点（`TvboxDetailPage(...)`）必须一起接线，否则编译失败。

### 6.4 现有 Core 入口：收起 TVBOX 下载

本期**不**在 Core 影片页做"选线路/选集"弹窗，统一改为：

| 入口 | 现状 | 改为 |
|---|---|---|
| `MoviePage` 下载按钮 | 只判 `downloadsAllowed`，TVBOX 也显示（点击无反应） | `movie.streamUrl.startsWith("tvbox://")` → 不渲染 |
| `VodMenu` 选项菜单「下载」 | 同上 | TVBOX → 不加入 actions |
| `HomeScreen` 长按菜单「下载」 | 显示且**真的入队**（`ContentActions.download` 无拦截） | 传 `onDownload = null`（TVBOX）或 Action 内部拦截；必须消除失败任务 |
| `ContentActions.download` | 无 `tvbox://` 判断 | 增加兜底：`streamUrl.startsWith(STREAM_PREFIX)` → 不调用 `enqueue` |

理由：一集/一条线路的选择只有点播详情页有上下文；Core 影片页贸然下载默认集会误导用户。需要"从片库直接下载"时再开 P2 的选集弹窗（复用同一 `downloadEpisode` 逻辑）。

### 6.5 离线播放

`DownloadsViewModel.play` 目前统一走 `VodTuner.playDownload(mediaType, itemId, filePath, title, posterUrl)`（`DownloadsViewModel.kt:291-302`）。TVBOX 行改为：

```kotlin
if (download.isTvbox()) vodTuner.playTvboxDownload(download)
else vodTuner.playDownload(...)
```

`VodTuner.playTvboxDownload(download)`：

1. **复用 `playDownload` 已有的分类/锁逻辑**，不要重写一套：
   ```kotlin
   val pid = currentProfileId() ?: return false
   val category = downloadCategory(MediaType.MOVIE, download.itemId)
   if (!downloadAllowed(category, pid)) return false
   ```
   `downloadCategory`/`downloadAllowed` 是 `VodTuner` 的 private 方法（`VodTuner.kt:622-640`），`playTvboxDownload` 放在同一个类里可直接调用。行已丢（索引被清）→ `Unresolved`，与 `playDownload` 同口径。
2. 外部播放器：本地文件应保留 `playDownload` 的 `externalPlayerFor(MOVIE)` 分支（HLS 拼出的 ts/mp4 也能交给 VLC），不要照搬 `playTvbox` 的纯内嵌播放。
3. `saveProgress(); liveTuner.stop(); cast.release(this)`。
4. **续播位置必须校验 `extKey`**：`playback_progress` 是 `(profile, MOVIE, movieId)` 唯一一行，`extKey` 记的是"上次哪一集"。如果直接 `startPositionMs = progress.positionMs`，在第 2 集播完后点第 3 集下载，会从第 2 集的位置开始。正确做法：
   ```kotlin
   val ref = TvboxDownloadKey.decode(download.extJson) ?: return false
   val saved = progressDao.get(pid, MediaType.MOVIE, download.itemId)
   val sameEpisode = saved != null &&
       TvboxExtKey.decode(saved.extKey)?.let {
           it.flag == ref.flag && it.episodeId == ref.episodeId && it.index == ref.index
       } == true
   val startMs = if (sameEpisode) saved.positionMs else 0L
   ```
5. `player.play(url = download.filePath, title, subtitle = ext.episodeName, isLive = false, startPositionMs = startMs, contentKey = "tvbox:${ref.siteKey}:${ref.vodId}:${ref.index}")`。`contentKey` 让音量/缩放/音轨记忆与在线播放同一条（`playTvbox` 用的是不带 index 的 `tvbox:siteKey:vodId`，离线可按集区分，二者都落在 `playback_prefs`，不冲突）。
6. `beginPlay(PlayKind.TVBOX)`（让 `saveProgress` 走 TVBOX 分支），`playingProfileId = pid`，`tvboxProgressCtx = TvboxProgressCtx(ref.flag, ref.episodeId, ref.index)`。
7. `_playing.value = VodPlayback(MOVIE, movieId, title, subtitle = ext.episodeName, posterUrl, isExternal = false)`。**不要设 `isExternal = true`**：该标记在 `PlayerScreen.kt:268` 是"TVBOX 流没有字幕接口"的门禁；离线文件有字幕接口（下面第 9 步），设 true 会把字幕入口关掉。`tvboxSiteKey`/`tvboxCategoryKey` 若能从 `movieDao` 行解析则一并设置（relock 守卫需要），解析不到就留空。
8. `recordHistory(pid, MOVIE, movieId)`。
9. 字幕：`setDownloadSubtitleContext(pid, MOVIE, movieId, download.filePath)`（与 `playDownload` 一致）；电影行不在则该方法内部已 `return`。
10. **不检查 `dataSaver.allowsStreaming()`**：那是 `playTvbox` 对网络流的限制，本地文件不走流量。

**关键**：TVBOX 离线播放必须把 `extKey`（线路/集）继续写回 `playback_progress`，否则下次在线续播会退化成第一集。`saveProgress` 的 TVBOX 分支已经用 `TvboxProgressCtx` 写 `extKey`（`VodTuner.kt:722-744`），只需保证 `beginPlay(TVBOX)` 与 `tvboxProgressCtx` 都设置。

### 6.6 下载内容页

- `DownloadsViewModel.lookUp`：`d.streamUrl.startsWith(TvboxIndexMapper.STREAM_PREFIX)` 时返回
  ```kotlin
  DownloadDetails(title = d.title, artUrl = d.posterUrl, quality = null)
  ```
  避免 `movieDao.getById` 把"第 N 集"覆盖回片名。**不要用 `d.extJson != null` 判断**：该列是 TVBOX 专属实现细节，损坏/未来扩展时会误判；`streamUrl` 是身份句柄，判据稳定。`isTvbox()` 扩展放在 App 侧（`ui/screens/downloads/`），Core 不认识 TVBOX。
- 归属页签：保持 `mediaType = MOVIE` → 出现在"电影"页签；行标题已带集名。
- 文件位置行、导出、删除、暂停/继续全部复用现有逻辑；`delete` 时 Core 侧额外清理 HLS 分片缓存（`cacheDir/hls/<identityHash>/`，§5.4）。
- 分组：同一 `itemId` 的多集下载按影片聚合展示属于 P2；P0 逐行展示即可（行标题能区分）。

### 6.7 建源参数

`TvboxSourceProvisioner.syncAll()` 建/更新 `SourceEntity` 时补 `maxConnections = 1`。**insert 与 update（`existing.copy(...)`）两个分支都要显式写**：`copy` 只覆盖列出的字段，旧源的 0 不会被自动改成 1（`TvboxSourceProvisioner.kt:54-77`）。这样：

- 下载只开 1 条连接；
- 正在看同一个 TVBOX 源时，`DownloadWatchGuard`/`waitsForWatching` 会让下载等待或暂停，避免站点"同 IP 单流"限制把播放踢下线。

**第二个前提**：上面两条护栏都依赖 `WatchSession.isWatching(sourceId)`，而 `WatchSession` 只由 `VodPlayback.sourceId` 登记。`playTvbox` 目前构造 `VodPlayback` 时没传 sourceId（`VodTuner.kt:588-595`），所以 P0 必须同时补：`playTvbox(...)` 里把 Core 行的 `sourceId`（`movieDao.getById(coreId)?.sourceId`）作为 `VodPlayback.sourceId`。离线播放（§6.5）不传——本地文件不占连接。

> `SourceEntity.ignoreConnectionLimit` 只影响直播的 `ConnectionBudget`（`ConnectionBudget.kt:68`），不会绕过 `DownloadEngine.waitsForWatching`；用户开了"忽略连接限制"时 TVBOX 下载仍会等待观看，这是有意的保守行为，发布说明里写明。

---

## 7. 关键流程

### 7.1 单集入队

1. 用户点某集下载徽标（详情页已通过 kids/锁校验）。
2. `ensureIndexed`：没有 `movieId` 时先补索引（需要 `sourceId/site/detailMovie/categoryKey`）；仍失败 → 提示"暂不能下载"。
3. `TvboxPlayResolver.resolve(...)` 跑一次完整解析（含锁校验）：`Blocked` → 刷新锁态；`Error` → 展示 `resolveError`；都不入队。
4. `HlsUrls.isM3u8(play.url)` → P0 直接提示"该片源暂不支持离线下载"，**不入队**（否则 Engine 会把清单文本下成 COMPLETED）。
5. 成功 → `DownloadManager.find(pid, movieId, identityKey)`：
   - `QUEUED/RUNNING/COMPLETED` → 跳过；
   - `PAUSED` → `resume`；`FAILED` → `retry`；
   - `null` → `enqueueNow`（`tvbox://` 句柄 + `identityKey` + `extJson` + headers + 容器）。
6. 行状态 QUEUED → Worker kick。

### 7.2 单文件传输

1. Worker 取 QUEUED 行。
2. `resolveTarget` → `TvboxDownloadSourceResolver.resolve`（**每次尝试**现解析，拿新直链/新 headers；`playerContent(countHealth = false)`）。
3. `GET Range: bytes=N-`；206 + 足够大 → 多连接分块；否则单连接追加。
4. 完成 → COMPLETED；失败按现有 3 次退避重试；最终 FAILED + 具体 reason。
5. 若 `manifest == HLS` 且 P1 尚未接入 → `UNSUPPORTED_MANIFEST`（§5.3⑤），不得走 `streamWhole`。

### 7.3 HLS 传输

1. 解析器返回 `manifest=HLS` + `containerExt`。
2. 拉 master/media playlist（带 headers）→ 选最高码率 variant。
3. **串行**逐片下载到 `cacheDir/hls/<identityHash>/`（不是 `<filePath>.parts/`，SAF 建不了），AES-128 按片解密，fMP4 先下 init 段；每片 `{seq}.part` 原子 rename。
4. 全部完成后按 mediaSequence 顺序拼接为目标文件；删除分片缓存目录。
5. 进程被杀：`progress.json` 记录 `playlistHash/completed/bytes`；重启后先校验分片文件仍存在且大小正确，再跳过完整分片继续。系统清 cache 时按"缺片"重下，不能只信 `progress.json`。
6. `totalBytes` 在首次解析后写分片总大小（未知时写 0，UI 显示不确定进度）。

### 7.4 暂停 / 继续 / 重试 / 删除

| 动作 | Core 现有行为 | TVBOX 增量 |
|---|---|---|
| 暂停 | `suspendTransfer` + 保留 partial | HLS 保留 `cacheDir/hls/<id>/`；进度读分片统计 |
| 继续 | Range 续传 | HLS 按分片续传（校验文件存在 + playlistHash） |
| 重试 | 截断文件 + QUEUED | 同时清空分片缓存，重新解析 |
| 删除 | 删文件 + 删行 | 同时删分片缓存；注意 REPLACE 入队换 id 后旧缓存目录会成孤儿，按 `identityKey` 的 hash 命名缓存可避免 |

### 7.5 离线播放

见 §6.5：本地文件 → `PlayKind.TVBOX` → 进度/历史写 `movieId`，`extKey` 保线路/集；**续播位置只在 `extKey` 与当前集匹配时才使用**。

### 7.6 批量下载

- 详情页"下载本线路"：确认 N 集后**串行** `enqueueNow`（单个协程），Core 队列本身串行，不会并发打满站点；`enqueue` 的 `scope.launch` 不能在循环里并发调用（§5.2）。
- P2：同影片多集聚合卡片、离线连播（`playTvboxDownload` 构建本地队列）、按影片"全部删除"。

---

## 8. 边界、安全与兼容

| 主题 | 处理 |
|---|---|
| 直链过期 | 不落库；每次尝试现解析；失败 3 次后 FAILED，partial 保留可手动重试 |
| 请求头 | 只接受 `StreamHeaders.canonicalName` 通过的键；`Range/Host/Content-Length` 由传输层独占；HLS 分片/密钥请求单独构造 |
| Cookie / 登录态 | 解析器把站点 headers（含 Cookie，若 spider 返回）交给 Core；Core 不维护站点 CookieJar。仍 403 时按 `REFUSED` 失败 |
| 本机代理 | 允许 `127.0.0.1:<JarProxyHub>` 直链；解析前确保 `JarProxyHub.isReady`，否则报 `SOURCE_UNAVAILABLE` |
| 儿童 / 分类锁 | 入队时 `TvboxPlayResolver.resolve` 强制校验（分类级 + 站点级）；离线播放再查 Core 分类/锁；儿童档案对 `Unresolved`（索引已清）拒绝。**条目级过滤（`isAdultItem`）依赖前置方案 §4.5.2（P3，未落地）**，在此之前下载入口与浏览侧存在同一残余缺口，不能宣称"儿童完全覆盖" |
| 源冷却 | 下载解析传 `countHealth = false`；但 `SourceRepository.call` 在源冷却时仍直接返回 null（不计数），表现为 `SOURCE_UNAVAILABLE`，可稍后重试，不会把源进一步推入冷却 |
| 配置/站点删除 | 下载行与文件保留；重试会因源不可用失败；离线播放仍可用（只要文件在）。`Provisioner.syncAll` 会把非激活配置的 `syncMovies` 置 false，但不会删下载行 |
| 档案删除 | `downloads` 外键 CASCADE 会删行；文件与 HLS 分片不会自动删（现有行为）。P2 增加"删除档案前清理下载文件"或在启动时扫描孤儿目录；本期至少文档标注 |
| 索引 TTL | 已加 `downloads` 保护（§4.4）；索引清理不再删除已下载影片；但不防源删除/re-sync 换 id |
| 备份 | 下载不在备份内（与现状一致，`BackupManager` 只备份 `downloadsAllowed` 开关）；`httpHeaders` 可能含 token/Cookie，**不得**写入备份或日志 |
| 日志 | 解析 URL 只打印前 120 字符（沿用 `TbLog` 习惯）；不打印 headers 值；`httpHeaders` 列只作失败复盘，不参与传输 |
| SAF / 存储 | `filePath` 可能是 SAF 文档 URI：不可改名、不可在旁建目录；HLS 分片放 App 私有 cache（§5.4）。根不可写/权限撤回仍按 `FOLDER_UNAVAILABLE` |
| HLS cache 被系统清理 | `progress.json` 不能单独作为续传依据：逐片校验"文件存在 + 大小正确"；`playlistHash` 变化则清空重下 |
| DRM | `SAMPLE-AES`/`EXT-X-KEY:METHOD=SAMPLE-AES` → `UNSUPPORTED_MANIFEST`；`AES-128` 支持 |
| 旧数据 | v54 存量下载行 `identityKey=''`、`manifest='NONE'`，唯一性与旧索引一致；旧 `tvbox://` 失败行不存在（此前已被拦截/删除）。迁移依赖旧唯一索引保证无重复行；若库被外部改坏导致 `CREATE UNIQUE INDEX` 失败，迁移会中止，需在 `MIGRATION_54_55` 里记录并给出可诊断日志 |
| 其他源 | M3U/Xtream/Stalker/Local 分支完全不动；解析器只 `matches` TVBOX 行 |
| 多配置 | 只有激活配置的源参与读取；非激活配置的索引行不可见；已下载行仍可离线播放 |

---

## 9. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0 底座** | Core v55 + 5 列/索引/迁移；`DownloadSourceResolver` SPI（`interface`，非 `fun interface`）；`enqueue` 扩展 + `enqueueNow`；Engine 解析优先级/headers/scheme 兜底/maxConnections/**P0 HLS 拒绝**；`TvboxDownloadSourceResolver` + `resolveUrl`（返回 `ResolvedStream`）+ `playerContent(countHealth=false)`；详情页逐集下载（查重 + 串行）；离线播放 `playTvboxDownload`（extKey 校验）；Downloads 页 `lookUp`；Core 入口隐藏 + `ContentActions` 拦截；Provisioner `maxConnections=1`；`playTvbox` 登记 `sourceId`；`TvboxRuntime.ensureReady`；prune 保护 + `(mediaType,itemId)` 索引；`VodScreen.kt` 接线 | 单文件直链站点：选集 → 下载 → 暂停/继续 → 杀进程续传 → 完成 → 离线播放 → 进度回到原集；重复点同一集不产生第二个任务；m3u8 站点明确提示"暂不支持"且**不生成 COMPLETED 清单**；杀 App 后无头进程能完成一次"命名解析直链站点"下载，嗅探站点至少在失败时给出 `SOURCE_UNAVAILABLE` 而不是崩溃；首页长按不再产生失败任务 |
| **P1 HLS** | 移植 `HlsPlaylist/HlsDownloader/HlsParts`；Engine HLS 分支；`HlsUrls.isM3u8`；`containerExt` 探测；分片缓存放 `cacheDir/hls/<id>/` 且**串行**；`totalBytes` 写分片总大小；DRM 拒绝；暂停/重试/删除清理分片缓存 | m3u8 站点：单集/整线路下载完成，离线可播；AES-128 可解；DRM 给出明确提示而非失败；用户在 SAF 文件夹下下载也能完成（分片不进 SAF） |
| **P2 体验** | 多集聚合、离线连播、按影片批量删除、Core 影片页选集弹窗、下载页"点播"分组、删除档案/配置时清理孤儿文件 | 一部 40 集剧可整季下载/连播/整剧删除 |
| **P3 可选** | HLS 分片并发自适应（仅 CDN 域）、下载限速、下载队列优先级、索引清理时的下载统计 | — |

> 每阶段独立可发布；P0 不依赖 P1。P0 对 m3u8 的处理是**入队前拒绝 + UI 提示**，不是"生成任务等 P1 再救"——一旦生成行，Engine 会把它当单文件下完并标 COMPLETED，这是最坏的静默失败。

---

## 10. 改动清单

### Core（`vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core`）

| 文件 | 改动 |
|---|---|
| `database/entity/UserDataEntities.kt` | `DownloadEntity` 增 `identityKey/extJson/httpHeaders/containerExt/manifest`；唯一索引改 4 列；补 `(mediaType, itemId)` 索引；两个 `defaultValue` 写成 SQL 字面量 `"''"` / `"'NONE'"` |
| `database/OwnTVDatabase.kt` | `version = 55`；注册 `MIGRATION_54_55`；提交 `schemas/55.json` |
| `database/Migration54To55.kt` | 新增（§4.2）；末尾必须 `healSchema`（standing rule） |
| `model/Enums.kt` | 新增 `DownloadManifest`；`DownloadReason` 加 3 个值（**不要**放 `DownloadSourceResolver.kt`，与现有枚举位置保持一致） |
| `download/DownloadSourceResolver.kt` | 新增 SPI + 注册表（`interface`，含 `clear()` 测试钩子） |
| `download/DownloadManager.kt` | `enqueue` 可选参数 + 新增 `enqueueNow`（suspend）；`bytesOf` 按 `manifest` 分支；删除/重试时清理 `cacheDir/hls/<id>/` |
| `download/DownloadEngine.kt` | `Resolved` 增 `manifest`；解析优先级、headers、scheme 兜底、P0 HLS 拒绝、P1 HLS 分支、`bytesSoFar` 分支 |
| `download/hls/*` | 新增 `HlsUrls.kt`（`isM3u8`）、`HlsPlaylist.kt`、`HlsDownloader.kt`、`HlsParts.kt`（cache 分片） |
| `download/DownloadReasons.kt` | `ofError` 加 `Expected URL scheme` 映射；`displayTextOf` 补 3 个分支 |
| `database/dao/DownloadDao.kt` | `observeForMovie` / `find`（`find` 必须带 `mediaType = 'MOVIE'`） |
| `database/dao/MovieDao.kt` | `pruneIndexedBefore` 与 `leastRecentlySeenCandidates` 都追加 `downloads` 保护 |
| `res/values{,-zh-rCN,-zh-rTW}/strings.xml` | 3 个失败原因文案；`verifyI18nLiterals` 会校验 |

### App（`OwnTV_Mobile/app/src/main/java/tv/own/owntv/mobile`）

| 文件 | 改动 |
|---|---|
| `tvbox/download/TvboxDownloadKey.kt` | 新增：identity（NUL 分隔）/extJson 编解码；字段名复用 `TvboxExtKey`（flag/episodeId/index）；单测覆盖碰撞、空值、非法 JSON |
| `tvbox/download/TvboxDownloadSourceResolver.kt` | 新增：SPI 实现（§6.1）；用 Core 行 `originSiteKey`、`runtime.ensureReady()`、`playerContent(countHealth=false)` |
| `tvbox/download/TvboxDownloadPaths.kt` | 新增：`dir(name, lineIndex, lineName)` / `fileName(ep, ext)` / `probeContainer(url)` |
| `tvbox/play/TvboxPlayResolver.kt` | 抽出 `resolveUrl(..., countHealth): ResolvedStream?`；`resolve(...)` 保持锁校验；新增 `ResolvedStream` 类型 |
| `OwnTVMobileApp.kt` | `DownloadSourceResolvers.install(...)`（`startKoin` 之后） |
| `ui/screens/vod/TvboxViewModels.kt` | 详情 VM 注入 `DownloadManager`；`downloads`/`downloadEpisode`/暂停/删除；查重 + 串行 + P0 HLS 拒绝 |
| `ui/screens/vod/TvboxPages.kt` | 每集下载徽标、线路级下载、确认弹窗；Lazy key 用 `episode.index` |
| `ui/screens/vod/VodScreen.kt` | 给 `TvboxDetailPage` 接线新增的下载状态/回调参数（原清单漏了） |
| `ui/screens/library/VodTuner.kt` | `playTvboxDownload`（复用 `downloadCategory/downloadAllowed`、extKey 续播校验）；`playTvbox` 的 `VodPlayback` 补 `sourceId` |
| `ui/screens/downloads/DownloadsViewModel.kt` | `isTvbox()` 扩展；`lookUp`/`play` 分流 |
| `ui/screens/library/MoviePage.kt`、`VodActions.kt`、`ui/screens/home/HomeScreen.kt` | TVBOX 隐藏下载入口 |
| `ui/screens/ContentActions.kt` | **路径是 `ui/screens/ContentActions.kt`，不是 `ui/screens/library/`**；`download()` 增 `streamUrl.startsWith(STREAM_PREFIX)` 兜底拦截 |
| `tvbox/repository/TvboxSourceProvisioner.kt` | insert 与 update 两个分支都写 `maxConnections = 1` |
| `tvbox/di/TvboxRuntime.kt` | 新增 `ensureReady()`（只 `restoreActive` + 引擎预热，不 `syncAll`/`maintainIndex`） |
| `di/TvboxModule.kt` | 注册 resolver |
| `res/values*/strings_mobile.xml` | "下载本线路/暂不支持离线下载/暂不能下载" 等（三套） |

### 文档

- 更新 `docs/点播内容接入Core方案.md` §4.5.1：把"下载必须排除 TVBOX"改为"TVBOX 下载经 `DownloadSourceResolver` 接入"，并链接本文。
- `docs/core-changes.md` 记录 v55 与 SPI。

---

## 11. 测试计划

### Core 单测

| 用例 | 断言 |
|---|---|
| `MIGRATION_54_55` | 54 库升级后旧下载行完好、`identityKey=''`、`manifest='NONE'`、新唯一索引存在、重复普通行仍被拒绝 |
| `identityKey` 唯一性 | 同一影片两集可共存；**同一集在 RUNNING 时重复入队不产生第二行/第二个 job**（查重路径）；FAILED/PAUSED 走 retry/resume |
| Engine 解析优先级 | 安装 fake resolver 后不再请求 `tvbox://`；未安装时 scheme 非法 → `UNSUPPORTED_SOURCE`；`clear()` 后恢复旧行为 |
| P0 HLS 拒绝 | fake resolver 返回 `manifest=HLS` → `UNSUPPORTED_MANIFEST`，目标文件不产生、状态不是 COMPLETED |
| 请求头 | headers 注入成功；单文件 `Range` 不被覆盖；HLS 的 `segmentRequest` 只在 byterange 时带 Range；`Host` 等被 `StreamHeaders` 过滤 |
| HLS 解析 | master 选码率、`EXT-X-BYTERANGE`、`EXT-X-MAP`、`EXT-X-KEY:AES-128` IV、相对 URI；SAMPLE-AES → 拒绝 |
| HLS 续传 | 完成部分分片后中断，重启只下缺失分片；`progress.json` 存在但分片文件被删时重下；`playlistHash` 变化清空；拼接字节序正确 |
| `totalBytes` | HLS 首解析写分片总大小；未知时 0，进度不除零 |
| `prune` | 有下载行的 MOVIE 不被 TTL/上限删除；EPISODE 同号 itemId 不误保护电影；无下载行照常删 |
| `find` | 同 profile 下 `MOVIE itemId=N` 与 `EPISODE itemId=N` 不互相命中 |
| 失败文案 | 3 个新 `DownloadReason` 在 `displayTextOf` 都有字符串，`when` 穷尽通过编译 |

### App 单测

| 用例 | 断言 |
|---|---|
| `TvboxDownloadKey` | 编解码往返；非法/空 JSON → null；identity 稳定；flag 含 `\|` 不碰撞；flag/episodeId 双空时 index 兜底 |
| `TvboxDownloadPaths` | 名称净化、空集名回退、`dir` 含线路 index、m3u8 → ts/mp4、无后缀回退 mp4 |
| `resolveUrl` 重构 | 与旧 `resolve` 的直链/headers 结果一致；锁校验仍只在 `resolve`；`countHealth=false` 透传 |
| `playTvboxDownload` | `extKey` 与当前集一致才用 `positionMs`，不一致从 0；`isExternal=false`（字幕入口可用）；本地文件不检查 dataSaver |
| `ensureReady` | configs 为空时**不会**把既有 TVBOX 源 `syncMovies` 置 false（与 `restore()` 对比） |

### 真机回归（`tvbox源.txt`）

1. **CMS 直链**（精品 / souav）：详情 → 选集下载 → 进度 → 暂停/继续 → 杀进程 → 完成 → 离线播放 → 进度回到原集。
2. **JS 源**（XP天堂）：同上；重点看命名解析/嗅探得到的 headers 是否被下载请求带上；再杀 App 让 WorkManager 拉起跑一次，确认无头进程的嗅探/代理可用。
3. **PY 源**（肉视频 / 黄果短剧 / 51吃瓜）：同上；重点看 `playerContent` 耗时、URL 过期后的重试、无头进程（杀 App 后由 WorkManager 拉起）能否 `ensureReady` 并解析。
4. **P0 HLS 站点**：点击下载必须提示"暂不支持"且不生成 COMPLETED 行；**P1 HLS 站点**：整线路下载、离线播放、AES-128 可解、DRM 站点给出 `UNSUPPORTED_MANIFEST`。
5. **幂等**：同一集连续点两次下载，不出现第二个任务/进度跳回 0；"下载本线路"重复点，不重复建文件。
6. **边界**：未索引成功（下载入口禁用）、儿童档案（分类级 + 条目级残余缺口记录）、锁定分类、允许下载关闭、仅 Wi-Fi 开启、下载中删除配置、下载完成后清理索引、SAF 文件夹下载、TVBOX 播放时下载进入 `WAITING_FOR_WATCHING`。
7. **回归**：普通 M3U/Xtream 电影/剧集下载、Downloads 页三页签、导出、删除、空间条。

### 工程门禁

- `:core:test`、`:core:lint`、`:core:verifyI18nLiterals`
- `:app:testStandardDebugUnitTest`、`:app:verifyI18nLiterals`（新增文案需 `classify-safe`）、`:app:assembleStandardDebug`
- `core-check`：这是 CI job（`.github/workflows/android.yml`），本地等价为在 `vendor/OwnTV_Core` 下跑 `gradlew :core:test :core:lint`；不要把 `core-check` 当成本地 Gradle 任务名
- 真机安装验证（安装时需输入锁屏密码 `0000`）

---

## 12. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **HLS 占主流，P0 直接可用面窄** | 用户点了下载却提示不支持 | P0 在入队前拒绝并明确提示"该片源暂不支持离线下载"；P1 尽快补齐；绝不生成"下载成功的清单文本" |
| **直链带时效/token** | 下载中途 403/404 | 每次尝试重新解析；不落库；3 次退避；partial 保留可重试 |
| **HLS 清单在两次尝试间变化** | 分片错位/拼接损坏 | `progress.json` 存 playlistHash；hash 变了就清空分片缓存重下；分片文件被系统清理时按缺片重下 |
| **分片缓存放不了 SAF** | 用户选文件夹后 HLS 无法断点 | 分片放 App 私有 `cacheDir/hls/<id>/`，完成后再写入 SAF 目标；`cacheDir` 被清理按缺片重下（§5.4） |
| **站点 Cookie 在引擎私有 CookieJar 里** | Core 请求 403 | 解析器把 headers（含 Cookie，如 spider 返回）交给 Core；仍失败则 `REFUSED`，不无限重试 |
| **本机代理依赖进程内 JarProxyHub** | 无头下载时代理未就绪 | `ensureReady()` + `JarProxyHub.isReady` 检查；失败给 `SOURCE_UNAVAILABLE` |
| **多连接触发站点单流限制** | 下载把播放踢下线 / 429 | TVBOX `maxConnections=1`；`playTvbox` 必须登记 `VodPlayback.sourceId`，否则 `waitsForWatching`/`DownloadWatchGuard` 对 TVBOX 不生效（§5.3④） |
| **REPLACE 换 id 双写** | 重复点同一集导致两个 job 写同一文件，文件损坏 | 入队前 `find` 查重（§5.2）；RUNNING/QUEUED/COMPLETED 跳过；批量用 `enqueueNow` 串行 |
| **唯一索引迁移** | 老用户下载行冲突 | `identityKey=''` 默认值让旧行唯一性与旧索引完全一致；迁移只加列/换索引，不重建表。若库被外部改坏产生重复，`CREATE UNIQUE INDEX` 会让迁移中止——需记录可诊断日志 |
| **索引 TTL 清掉已下载影片** | 下载行失去片名/分类/锁归属 | §4.4 把 `downloads` 加入保护 SQL；补 `(mediaType,itemId)` 索引与单测 |
| **儿童条目级过滤未落地** | 儿童档案可能下载成人条目 | 本方案只复用现有分类级 + 站点级校验；条目级 `isAdultItem` 依赖前置方案 §4.5.2（P3）。发布说明写明"与浏览侧同口径"，不宣称完全覆盖 |
| **`httpHeaders` 存 Cookie** | DB 中留 token，扩大泄露面 | 若该列不参与传输（当前设计如此），建议**直接不存**；至少过滤 `Cookie/Authorization`，只留 `User-Agent/Referer`。无论存不存，都不进备份、不进日志 |
| **WebView 嗅探在无头进程/后台受限** | 杀 App 后 WorkManager 拉起，嗅探可能超时或线程违规崩溃 | `resolveUrl` 的嗅探分支切 `Dispatchers.Main`；`SniffEngine` 不要从 IO 直接调用；超时/失败按 `SOURCE_UNAVAILABLE` 收口；P0 真机单独验证无头进程 |
| **Core 上游合并冲突** | 下次吸收上游功能冲突 | 改动集中在 `download/database` 两个模块；SPI 单独文件；按阶段成组提交，按 `docs/Core源码集成方案.md` 同步 vendor |

---

## 13. 验收标准

- [ ] 点播详情页可对单集/整线路发起下载，入口受"允许下载"、儿童模式、分类锁约束；儿童条目级过滤的残余缺口在发布说明中记录（依赖前置方案 §4.5.2/P3）。
- [ ] 下载行带 `identityKey/extJson`，同影片多集可共存；**重复点同一集不产生新行/新 job**；FAILED/PAUSED 才走 retry/resume。
- [ ] 下载全程不出现 `tvbox://` 的 HTTP 请求；失败原因能区分"源站解析失败/不支持下载/受保护"。
- [ ] 单文件直链：暂停/继续/杀进程续传/重试/删除全部可用。
- [ ] P0：m3u8 入队被拒绝并提示，**不出现 COMPLETED 的清单文本**；P1：HLS 整集可下、可离线播放，AES-128 可解，DRM 明确拒绝。
- [ ] SAF 文件夹下 HLS 下载可完成并续传（分片在 app cache，不依赖 SAF 目录）。
- [ ] 下载内容页可播放、可导出、可删除；行标题显示"影片名 · 第N集"。
- [ ] 离线播放写入 Core 进度（含线路/集 `extKey`）与历史；下次在线续播回到同一集同一位置；**离线播放第 N 集不会继承第 M 集的 `positionMs`**。
- [ ] TVBOX 在线播放时，同一源的下载进入 `WAITING_FOR_WATCHING` 或暂停（`playTvbox` 已登记 `sourceId`）。
- [ ] 首页长按/片库菜单不再为 TVBOX 生成下载任务；Core 影片页不再显示无效下载按钮。
- [ ] 普通 M3U/Xtream/Stalker 下载零回归。
- [ ] 构建、单测、i18n 门禁全绿；`core-check` CI job 通过；真机回归通过。

---

## 14. 备选方案与不采纳原因

| 方案 | 做法 | 不采纳原因 |
|---|---|---|
| A. 入队时快照直链 | `streamUrl` 直接存解析后的 URL | 链接普遍带时效/token，进程被杀后重试必失败；多集无法区分；headers 无处存 |
| B. 把 TVBOX 剧集落成 Core `series/episodes` | 详情页为每部剧补行 | 与"一部片一条索引行"冲突；引入同步/TTL/备份/改集语义；远端剧集没有稳定 remoteId |
| C. App 内另起一套 TVBOX 下载器 | 移植源项目 `DownloadRepository/DownloadTask` | 队列/通知/前台服务/存储/暂停重试全部重复；用户态分裂；与"内容+用户态统一落 Core"的方向相反 |
| D. 只做"下载后用外部播放器打开" | 不接 Core 下载页 | 仍要解决解析、断点、重试；体验割裂，且无法复用现有 Downloads UI |
| E. HLS 分片放 `<filePath>.parts/` | 沿用源项目"文件旁建目录" | **SAF 下不可行**：`MediaTarget` 只有单文件/相对目录树，`content://` 文档旁无法建目录。改用 App 私有 `cacheDir/hls/<identityHash>/`（§5.4） |
| F. P0 先入队、P1 再补 HLS | 让 m3u8 行先跑单文件分支 | 会把清单文本下完并标 COMPLETED，是最坏的静默失败；P0 必须入队前拒绝 + Engine 兜底拒绝 |
| G. `httpHeaders` 列存 Cookie 做重试兜底 | 减少每次解析 | 解析器每次现解析已覆盖；DB 存 Cookie 扩大泄露面，列本身不参与传输，价值低（建议不存或只留非敏感头） |

**采用方案：Core 传输 + App 解析（SPI），数据模型只加 5 列 + `identityKey` 唯一索引 + `(mediaType, itemId)` 保护索引。**

---

## 15. 本版修订记录（v1 → v2）

| 类别 | 修订 |
|---|---|
| 编译级错误 | `DownloadSourceResolver` 由 `fun interface` 改 `interface`（双抽象方法）；`@ColumnInfo(defaultValue = "")` → `"''"`，`"NONE"` → `"'NONE'"` |
| P0 静默失败 | 补"P0 对 `manifest=HLS` 直接 `UNSUPPORTED_MANIFEST`"与"入队前 `isM3u8` 拒绝"两道 |
| 存储可行性 | HLS 分片由 `<filePath>.parts/` 改为 App 私有 `cacheDir/hls/<identityHash>/`（SAF 无目录 API）；补 `totalBytes` 与缓存被清后的校验 |
| 并发/幂等 | 补 `DownloadManager.enqueue` 的 REPLACE 换 id 双写风险、入队前 `find` 查重、批量 `enqueueNow` 串行 |
| 解析链路 | `resolveUrl` 返回 `ResolvedStream`（不是完整 `ResolvedPlay`）；`playerContent(countHealth=false)`；`originSiteKey` 解析借源站点；`TvboxRuntime.ensureReady()` 避免 `syncAll` 副作用 |
| 续播正确性 | `playTvboxDownload` 必须校验 `extKey` 与当前集一致，否则从 0；`isExternal=false` 保留字幕入口 |
| 边看边下 | 指出 `playTvbox` 未登记 `VodPlayback.sourceId`，`waitsForWatching`/`DownloadWatchGuard` 对 TVBOX 原本不生效；P0 补登记 |
| SQL/迁移 | `find` 补 `mediaType='MOVIE'`；补 `downloads(mediaType,itemId)` 索引；补 `displayTextOf` 三个字符串分支 |
| 文档准确性 | `ContentActions.kt` 路径修正为 `ui/screens/ContentActions.kt`；补 `VodScreen.kt` 接线；`core-check` 标注为 CI job |
| 无头解析 | 补 `SniffEngine` 的 Main 线程约束（`webView.settings` 在 IO 调用会 `WrongThread`）、`JarProxyHub.isReady` 检查、`TvboxRuntime.ensureReady()`；P0 增加"杀 App 后 WorkManager 拉起"验收 |
