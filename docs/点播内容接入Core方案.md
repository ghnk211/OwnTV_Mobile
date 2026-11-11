# 点播内容接入 Core（搜索 / 分类 / 收藏 / 隐藏）技术方案

> 对象：`OwnTV_Mobile`（`app`）+ `OwnTV_Core`（`vendor/OwnTV_Core`，mobile 分支）
> 前置约束：Core 允许增删字段与新增表，但**必须走显式 Migration**（Core 无破坏性回退，见 `di/DatabaseModule.kt:12-36`）
> 状态：方案稿 v3；**P0 已实施（2026-11-11）**，P1–P5 未实施（见 §9 与 `docs/core-changes.md`）。v2 已按当前代码逐项核对，修正了内容哈希、播放路由、备份兼容、锁 key 双轨等结论；v3 落实 §12 的 6 项已确认决策：一配置一源、仅激活配置可见、按档案隔离、索引范围按 P0、儿童档案补条目级 `extJson.typeNames` 检查、不做存量数据迁移

---

## 1. 目标

让 TVbox 点播链路不再维护"另一套"内容与用户态，而是复用 Core 的一整套能力：

| 能力 | 现状（TVbox） | 目标 |
|---|---|---|
| 分类 | `ClassInfo` 只在内存（`TvboxHomeUiState.categories`）；锁写在 Core，隐藏/改名/排序未接 | `categories` 表 + `CustomizationStore`：隐藏、改名、排序、锁定、儿童过滤一套 |
| 收藏 | `files/tvbox/favorites.json`，无 profile、不进备份 | `favorites` 表：收藏页、继续观看、备份/跨设备同步自动可用 |
| 历史/进度 | `files/tvbox/progress.json` / `history.json`，无 profile | `watch_history` / `playback_progress` 表 |
| 隐藏 | 只有分类锁；无条目级隐藏 | `CustomizationStore.hiddenCategories / hiddenItems` |
| 搜索 | 远端实时聚合（`SearchReader` 搜不到） | Core `SearchReader` 命中索引；远端在线结果作为补充 |

核心思路一句话：**TVbox 只保留"取内容"和"播内容"，"内容目录 + 用户态 + 查询过滤"全部落到 Core 的现有模型里。**

---

## 2. 现状事实（决定方案边界）

### 2.1 Core 已具备、可直接复用的部分

| 能力 | 关键事实 | 位置 |
|---|---|---|
| 稳定远端 ID | `MovieEntity.remoteId: String?`、`CategoryEntity.remoteId: String?`；唯一索引 `movies(sourceId, remoteId)`、`categories(sourceId, mediaType, remoteId)` | `ContentEntities.kt:148-209 / 17-39` |
| 稳定 upsert | `SyncSupport.upsertStable`：哈希比对，未变行跳过、变更行**保留本地 id** | `sync/SyncSupport.kt:100-137` |
| 分类树 | `CategoryEntity(sourceId, mediaType, name, remoteId, sortOrder)`，单层 | `ContentEntities.kt:17-39` |
| 收藏 | `FavoriteEntity(profileId, mediaType, itemId)` 唯一 `(profileId,mediaType,itemId)` | `UserDataEntities.kt:17-33` |
| 历史/进度 | `WatchHistoryEntity`、`PlaybackProgressEntity` 同构 | `UserDataEntities.kt:35-72` |
| 隐藏/改名/排序/锁 | `CustomizationStore`（DataStore `owntv_customizations`，key `cust_<pid>_<TYPE>`，JSON `hiddenCats/hiddenItems/catNames/itemNames/catPos/lockedCats`） | `customize/CustomizationStore.kt:22,118-152,181-211` |
| 稳定 item key | `CustomizeKeys.movie(m) = "${sourceId}:${remoteId ?: name}"` | `customize/CustomizationStore.kt:29-42` |
| 搜索 | `SearchReader`：FTS + 隐藏条目/隐藏分类/kids/重命名过滤 | `content/SearchReader.kt:97-149` |
| 列表/分页 | `VodQueries` 的 All/Favorites/History/Custom/Folder 分支 + `PagingSource` | `content/VodQueries.kt:41-75` |
| 孤儿清理 | `FavoriteDao` / `HistoryDao` / `ProgressDao` / `CustomCategoryDao` / `ContentOrderDao` 各有 `purgeOrphans`（删除"目录里不存在"的行） | `FavoriteDao.kt:84-90`、`ProgressDao.kt:171-177` 等 |
| 删除墓碑 | `UserDataWriter.removeFavorite/removeHistory/clearProgress` 记录 tombstone，防同步回魂 | `backup/UserDataWriter.kt:35-50` |

> **关键结论：Core 的 `remoteId` 本来就是 `String`，TVbox 的 `siteKey + vodId` 可以无损放进去，不需要把字符串哈希成 Long。**

### 2.2 TVbox 侧现状（要拆掉的部分）

| 状态 | 存储 | 档案隔离 | 现状问题 |
|---|---|---|---|
| 收藏 | `files/tvbox/favorites.json`（`siteKey+vodId`） | 否 | 只有详情页一个按钮，无收藏列表；不进备份/收藏页 |
| 进度 | `files/tvbox/progress.json`（`siteKey\|vodId\|flag\|episodeId`，500 条上限） | 否 | 详情页可续播，但继续观看卡片看不到 |
| 历史 | `files/tvbox/history.json`（200 条上限） | 否 | **只写不读**（`TvboxStore.history()` 无调用方），不进历史页 |
| 分类 | 内存 `StateFlow` | — | 无法隐藏/改名/排序 |
| 分类锁 | **已写 Core**（`lockedCats`），key 为 `tvbox:<siteKey>:<typeId>`，并额外写一条 `tvbox:<siteKey>:<typeName>` 别名（搜索来源只有 typeName） | 是 | key 口径与 Core 的 `sourceId:remoteId` 不一致；新方案直接改用新口径，旧 key 废弃不迁移（决策 6，§6） |
| 播放入口 | `TVBOX_ITEM_ID = -2L` 哨兵；`PlayKind` 是 `VodTuner` 的私有枚举；`playingProfileId = -1L` 阻断 Core 写入 | — | `PlayerScreen` 用一个哨兵判断同时隐藏收藏与字幕；进度不落 Core |

### 2.3 三个硬约束（方案必须满足）

1. **不能用 `MovieDao.upsertAll`**：它是 `@Insert(onConflict = REPLACE)`（`MovieDao.kt:20-21`），REPLACE = 删旧行 + 插新行 → **`id` 变化** → `favorites/progress/history` 里指向旧 id 的行全部变孤儿。索引写入只能用 `upsertStable` / `insertFresh`。
2. **索引清理不能删用户态行**：`purgeOrphans` 只保护"目录里存在"的行；索引 TTL 清理先删目录行，随后孤儿清理会把收藏/进度一起删掉。清理 SQL 必须反查并跳过**全部五张用户态表**：`favorites` / `playback_progress` / `watch_history` / `custom_category_members` / `content_order`。
3. **TVBOX 源必须绕过"当前播放列表"过滤**：`activeProfileSources()` 会被 `settings.defaultSourceId` 收窄（`repository/ActiveSources.kt:52-57`），**一次性版本 `activeSourceIds()` 也有同样的收窄（`ActiveSources.kt:97-100`，HomeFeed/HomeViewModel 在用）**。如果用户当前选的是某个 IPTV 源，TVBOX 的索引内容会从 `SearchReader`/Library/Home 里消失。两处都必须豁免。

---

## 3. 路线对比

| 路线 | 做法 | 成本 | 结论 |
|---|---|---|---|
| A. 只共享 `CustomizationStore` | 分类隐藏/改名/锁复用 Core；收藏/进度仍 JSON | 低 | 只解决"隐藏"，不解决收藏/搜索 |
| **B. 索引落库 + 用户态入 Core（推荐）** | TVbox 浏览/搜索/详情见到的内容写 Core `sources/categories/movies`；收藏/历史/进度/隐藏全写 Core | 中高 | 满足目标，可分阶段，风险可控 |
| C. 全量 RemoteMediator 迁移 | Library 直接用 Paging3 + RemoteMediator 浏览 TVbox 分类，Room 是唯一数据源 | 高 | 终态；作为 B 的 P4 阶段演进 |

**推荐 B，并以 C 为终态**。理由：

- B 保留 TVbox 引擎（远端分页/详情/播放）不动，只在"写"这一侧接 Core；
- 收藏/隐藏/搜索一旦落 Core，旧 UI 与 Core 原生页面（收藏、继续观看、备份）立即受益；
- 索引是"见过的内容"，天然带 TTL，不会把几十万条远端内容全量镜像到手机。

---

## 4. 目标架构

### 4.1 数据流

```
                     ┌──────────────────────────────────────┐
TVbox 引擎            │  Core Room                            │
(远端分页/详情/播放)  │  sources / categories / movies        │
        │            │  favorites / watch_history /          │
        │  读取      │  playback_progress                    │
        ▼            │  + CustomizationStore(DataStore)      │
  TvboxIndexer ──────►  stable upsert（保留 id）              │
        │            │                                       │
        │            └──────────────┬────────────────────────┘
        │                           │
        └──► TvboxPlayResolver      ├──► SearchReader（全局搜索、Library 搜索框）
                                    ├──► VodQueries / PagingSource（Library、收藏、历史）
                                    └──► CustomizationStore（隐藏/改名/排序/锁）
```

写入与读取彻底分离：TVbox 负责 **fetch**，Core 负责 **store + query + filter**。

### 4.2 身份设计

**一个 TVbox 配置 = 一个 Core 源**（`SourceEntity.type = TVBOX`），站点身份编码进 `remoteId`：

| 实体 | 字段 | 取值 |
|---|---|---|
| `SourceEntity` | `name` | `"点播 · <配置名>"` |
| | `type` | `SourceType.TVBOX`（新增枚举值） |
| | `url` | `"tvbox://config/<configId>"` |
| | `externalKey` | `<configId>`（新增字段） |
| | `syncLive / syncMovies / syncSeries` | `false / true / false`（非激活配置的 `syncMovies` 为 `false`） |
| `CategoryEntity` | `remoteId` | `"<siteKey>/<typeId>"` |
| | `name` | `"<站点名> · <分类名>"` |
| | `sortOrder` | 分类在站点内的顺序（0 起） |
| | `extJson` | `{"siteKey":"...","typeName":"..."}`（新增字段） |
| `MovieEntity` | `remoteId` | `"<siteKey>/<vodId>"`（**必须非空**） |
| | `streamUrl` | `"tvbox://<siteKey>/<vodId>"`（稳定句柄，不存真实 URL） |
| | `extGroup` | `<siteKey>`（可索引，用于按站点清理） |
| | `extJson` | `{"typeNames":[..],"remark":..,"donorSiteKey":..,"adultSite":true,"lineCount":..,"episodeCount":..}`（`typeNames` 为累积并集，见 §4.5.2；`adultSite` 仅站点 ext 带 adult/nsfw 标记时写入） |
| | `lastSeenAt` | 最近一次被远端看到的时间（TTL 依据） |

`url` 只用于展示/排查，身份以 `externalKey` 为准：Provisioner 按 `type + externalKey` 查重，不能靠 `url` 字符串比较。

为什么"一个配置一个源"，不是"一个站点一个源"：

- `sources` 表会被播放列表选择器、`defaultSourceId`、Library 的 playlist 标记、备份导出直接感知；几十个站点会污染 UI；
- 切配置只切换哪个源参与读取（翻转 `syncMovies`），不必追踪哪些站点被删除；
- 站点仍可通过 `extGroup` 分组、清理、统计。

若产品要求"每个站点可单独显示/隐藏/上锁"，再升级为"一站一源"（代价：源列表与备份会膨胀）。

**多配置策略（决策 2，已确认）**：按"一配置一源"，导入过的每个配置都会各自拥有一个 TVBOX 源。**只有当前激活配置**对应的源参与内容读取（`syncMovies = true`），其余配置的源置 `syncMovies = false`。这样切换配置只改两个布尔列，**不改写任何 `remoteId`**，旧配置的索引与用户态原样保留，切回即可用；代价是搜索/Library 一次只看到一个配置的内容。禁止的做法是复用一个源、切换时改写 `externalKey`——那会让该源下所有 `remoteId` 失效，收藏/进度集体变孤儿。

**档案绑定（决策 3 的推论）**：TVBOX 源必须绑定**全部档案**，不是只绑当前档案。Core 的口径本来就是"内容目录共享、用户态按档案隔离"——`ProfileManager.create` 会把所有已有源链接给新档案（`profile/ProfileManager.kt:50`）。若只绑当前档案，儿童档案将完全看不到 TVBOX 内容，决策 5 的条目级过滤也就无从谈起。Provisioner 建源时遍历全部档案 `sourceDao.link(...)`；收藏/进度/历史/隐藏由各自的 `profileId` 列天然隔离。

**remoteId 编码约定**：`"<siteKey>/<vodId>"`。`siteKey` 来自配置，Provisioner 必须校验它**不包含 `/`**（非法站点跳过并记日志）；`vodId` 可含任意字符，解析时只按**第一个** `/` 切分。`Movie.donorSiteKey != null`（首页兜底借源）时，`siteKey` 一律取 `donorSiteKey`——否则详情/播放在借主站点必然 404。

### 4.3 实体改造清单（允许增删字段）

建议**少量真实列 + 一个 JSON 扩展列**，避免 TVbox 字段变化反复改 schema：

```kotlin
// SourceType
enum class SourceType { M3U, XTREAM, LOCAL_BACKUP, STALKER, TVBOX }

// SourceEntity（新增 1 列）
val externalKey: String? = null          // TVBOX: 配置 id；其它类型：null

// CategoryEntity（新增 1 列）
val extJson: String? = null              // {"siteKey":"...","typeName":"..."}

// MovieEntity（新增 3 列 + 2 索引）
val extGroup: String? = null             // siteKey（借源时是 donorSiteKey），供按站点清理/分组
val extJson: String? = null              // {"typeNames":[...],"remark":...,"donorSiteKey":...,"adultSite":true,"lineCount":...,"episodeCount":...}
val lastSeenAt: Long? = null             // 最近一次远端命中；TTL 依据
// @Index(value = ["sourceId", "extGroup", "lastSeenAt"])
// @Index(value = ["lastSeenAt"])

// PlaybackProgressEntity（新增 1 列）
val extKey: String? = null               // TVBOX: {"flag":"...","episodeId":"...","index":N}

// 新表：索引游标（记录已拉到第几页 / 第几代目录）
@Entity(
    tableName = "catalog_index_cursor",
    primaryKeys = ["sourceId", "categoryRemoteId"],
)
data class CatalogIndexCursorEntity(
    val sourceId: Long,
    val categoryRemoteId: String,        // "<siteKey>/<typeId>"，与 CategoryEntity.remoteId 同口径
    val lastPage: Int = 0,
    val generation: Int = 0,
    val syncedAt: Long = 0,
)
```

**`extGroup` / `extJson` 必须进入 `MovieEntity.computeContentHash()`（沿用现有"非空才折叠"的写法）**：`upsertStable` 只按哈希 + `sortOrder` 判断是否写入，若 `extJson` 在哈希之外，"更新到第 N 集""线路数变化"这类更新会被静默跳过，索引永远停在第一次见到的样子。写法与 `httpHeaders/drmConfig/manifestType` 相同——只有携带值的行才改变哈希，存量 M3U/Xtream 行不会被全量重写。

不保留 `indexedAt`：`upsertStable` 的更新路径会把整行写成新值，这个字段会在每次内容变化时被刷新，不再是"首次入库"，留着只会误导。

**不建议新增 `MediaType.TVBOX`**：TVbox 条目一律落成 `MediaType.MOVIE`（剧集也先建一行 `movies`，集数/线路放 `extJson`）。新增 `MediaType` 会让收藏/历史/进度/备份/播放器的所有 `when` 都要改，收益为零。

**不建议给 `FavoriteEntity / WatchHistoryEntity` 加字段**：`itemId` 已经足够；标题/海报由 `movies` JOIN 提供。

### 4.4 写入路径（索引器）

Core 新增通用写入器（与 provider 无关）：

```kotlin
// core/catalog/CatalogIndexWriter.kt
class CatalogIndexWriter(
    private val database: OwnTVDatabase,
    private val syncSupport: SyncSupport,
) {
    suspend fun upsertCategories(sourceId: Long, type: MediaType, rows: List<CategoryEntity>): Map<String, Long>
    suspend fun upsertMovies(
        sourceId: Long,
        rows: List<MovieEntity>,
        // TVBOX 传 typeNames 并集合并（§4.5.2）；其它调用方默认覆盖
        mergeExtJson: (old: String?, new: String?) -> String? = { _, new -> new },
    )
    suspend fun markSeen(sourceId: Long, remoteIds: Collection<String>, at: Long = System.currentTimeMillis())
    suspend fun prune(sourceId: Long, cutoff: Long, maxRows: Int): Int
}
```

`upsertMovies` 的实现约束：

- **必须在一个事务里同时完成 `upsertStable` + `markSeen`**。`upsertStable` 会跳过内容未变的行，这些行不会带上新的 `lastSeenAt`；漏掉 `markSeen`，正在被反复浏览的内容也会在 30 天后被 TTL 清掉。
- **`mergeExtJson` 必须在哈希比对之前执行**：先按 `remoteId` 读出现有行的 `extJson`（新增一个 DAO 查询，或复用现有行快照），合并后再算哈希。`typeNames` 并集增长会改变哈希、触发整行更新（id 不变），这是预期行为；若先比哈希再合并，累积的分类名会被"内容未变"跳过。
- **`remoteId == null` 的行直接丢弃**：`(sourceId, remoteId)` 唯一索引对 NULL 不去重，写进去只会产生重复幽灵行。
- 一轮索引成功后调用 `sourceDao.markSynced(sourceId, now)`，否则 `sources.lastSyncAt` 永远为 null，设置页会一直显示"未同步"。

App 侧新增 `TvboxIndexMapper` + `TvboxIndexer`：

```kotlin
// app/tvbox/index/TvboxIndexer.kt
class TvboxIndexer(
    private val configRepository: TvboxConfigRepository,
    private val writer: CatalogIndexWriter,
) {
    suspend fun indexClasses(sourceId: Long, site: SiteConfig, classes: List<ClassInfo>): Map<String, Long>
    suspend fun indexHome(sourceId: Long, site: SiteConfig, movies: List<Movie>, categoryId: Long?)
    suspend fun indexCategoryPage(sourceId: Long, site: SiteConfig, typeId: String, page: Int, movies: List<Movie>)
    suspend fun indexSearchResults(sourceId: Long, siteKey: String, movies: List<Movie>)
    suspend fun ensureIndexed(sourceId: Long, site: SiteConfig, movie: Movie): Long
}
```

`upsertMovies` 内部**必须**走 `SyncSupport` 的稳定路径：

```kotlin
// 正例：哈希比对，内容未变跳过，内容变了保留本地 id
syncSupport.upsertStable(
    rows = movies,
    hashDeferred = syncSupport.asyncHashLoad(scope, "tvbox", sourceId) {
        movieDao.contentHashesForSource(sourceId)
    },
    adapter = syncSupport.movieAdapter,
)

// 反例（禁止）：REPLACE 会换 id，收藏/进度立刻变孤儿
// movieDao.upsertAll(movies)
```

映射示例：

```kotlin
fun Movie.toEntity(sourceId: Long, categoryId: Long?, site: SiteConfig, donor: SiteConfig?, now: Long, categoryTypeName: String? = null): MovieEntity {
    // 首页兜底借来的条目，其 id/详情只在借主站点有效（见 Movie.donorSiteKey）；
    // 海报的相对路径也要按借主站点的 api 补全，不能用当前站点的。
    // donorSiteKey 存在但 donor 为 null（借主站点已从配置里删掉）时，调用方应跳过该条：
    // 退回当前站点会让身份错位，播放必然 404。
    val origin = if (donorSiteKey != null) donor ?: site else site
    val siteKey = origin.key
    val key = "$siteKey/$id"
    return MovieEntity(
        sourceId = sourceId,
        categoryId = categoryId,
        name = name,
        posterUrl = TextUtil.absoluteUrl(pic, origin.api),
        year = year?.toIntOrNull(),
        plot = content,
        streamUrl = "tvbox://$key",          // 稳定句柄，不是真实播放地址
        remoteId = key,                       // 与 Core 的稳定身份口径一致
        extGroup = siteKey,
        extJson = buildJsonObject {
            // 本次见到的分类名（分类浏览来源用 ClassInfo.typeName 兜底）；
            // 与旧值的并集合并由 CatalogIndexWriter.mergeExtJson 完成（§4.5.2）
            val seen = typeName?.takeIf { it.isNotBlank() } ?: categoryTypeName?.takeIf { it.isNotBlank() }
            put("typeNames", JsonArray(listOfNotNull(seen).map { JsonPrimitive(it) }))
            put("remark", remark.orEmpty())
            donorSiteKey?.let { put("donorSiteKey", it) }
            // 站点 ext 的 adult/nsfw 标记：复用 TvboxCategoryPolicy 的同一判定，不另写正则
            if (siteMarkedAdult(origin)) put("adultSite", true)
        }.toString(),
        lastSeenAt = now,
    )
}
```

**分类归属策略**：一部片只存一个 `categoryId`，但远端可能把它列在多个分类里。写索引时统一用"首次见到的非空分类，之后不覆盖"：搜索/详情来源（`categoryId = null`）不得把已有分类覆写成 null，否则哈希在 null 与真实分类之间反复变化，行被反复重写、FTS 触发器反复触发。分类浏览的筛选仍以远端 `typeId` 为准，Core 侧只把 `categoryId` 当作 kids/锁判定的归属。

**`sortOrder`**：P0-B1 可全部保持 0，避免无意义的 move 写入；P4 若要在 Library 按远端顺序展示，再写 `(page - 1) * pageSize + index`。

触发点：

| 时机 | 索引动作 |
|---|---|
| `TvboxHomeViewModel.openSite` | `indexClasses` + `indexHome` |
| `loadCategory(page)` | `indexCategoryPage`（含 `markSeen`）+ 更新 `catalog_index_cursor` |
| `aggregateSearch` 任一站返回 | `indexSearchResults`（`categoryId = null`，`extGroup = donorSiteKey ?: siteKey`）；找不到对应 `SiteConfig` 时跳过，不覆盖旧索引 |
| `detailContent` 返回 | `ensureIndexed`，并补 `plot/year`、累积 `typeNames`；借源条目用 `donorSiteKey` |
| 起播前 | `ensureIndexed`（拿 `movieId` 写用户态）；索引失败保留 `TVBOX_ITEM_ID` 降级路径 |

### 4.5 读取路径

| 场景 | 读取方式 | Core 改动 |
|---|---|---|
| 全局搜索/Library 搜索框 | `SearchReader.search(...)`（FTS 命中索引行） | kids 条目级过滤（§4.5.2）；源需在 `ActiveProfileSources` 内，两处 `active*` 必须豁免 `defaultSourceId` |
| 收藏页 | `VodQueries` Favorites（`INNER JOIN favorites`） | 无 |
| 继续观看 | `PlaybackProgressEntity(MOVIE, movieId)` | 无 |
| 历史页 | `WatchHistoryEntity` | 无 |
| 分类条（VOD 页） | `categoryDao.observe(tvboxSourceIds, MOVIE)`、`CategoryRailEditor`、`CustomizationStore.observeVisible` | 无 |
| 分类内容（P4） | `LibraryViewModel` Pager + `RemoteMediator` | 需给 `Pager` 增加 mediator 参数 |

**必须改的读取侧**：`activeProfileSources` 与 `activeSourceIds` 两处都要让 TVBOX 源不受 `defaultSourceId` 收窄（后者被 `HomeFeed` / `HomeViewModel` 直接调用，漏改会让 Home 看不到索引内容）：

```kotlin
// repository/ActiveSources.kt —— 两个函数都要加同一个条件
val filtered = if (defaultId > 0 && srcs.any { it.id == defaultId }) {
    // TVBOX 是常驻内容层，不参与"当前播放列表"筛选
    srcs.filter { it.id == defaultId || it.type == SourceType.TVBOX }
} else {
    srcs
}
```

TVBOX 源是否进入 `movieSourceIds` 仍取决于 `syncMovies`——这正是 §4.2 用 `syncMovies` 控制"只有激活配置可见"的开关。

#### 4.5.1 从 Core 界面播放 TVBOX 条目（原方案缺失的关键一环）

索引落库后，Home 的"继续观看"/Hero/推荐行、Library 的收藏/历史/搜索/详情、Favorites 页都会拿到 TVBOX 的 `MovieEntity`，它们的播放入口统一是 `VodTuner.playMovie(movieId, positionMs)`（`HomeViewModel.kt:246`、`MoreCountsViewModel.kt:212`、`DetailViewModel.kt:338` 等）。`playMovie` 目前直接 `resolve(source, movie.streamUrl)` 后交给播放器，而 TVBOX 的 `streamUrl` 只是 `tvbox://` 句柄，**直接播必然失败**。

必须加一个统一路由（只加在 `VodTuner.playMovie` 一处，覆盖所有 Core 入口）。分流要**先于 `externalPlayerMovies` 判断**——TVBOX 条目交给外置播放器只会打开一个 `tvbox://` 死链：

1. 读 `sourceDao.getById(movie.sourceId)`，`type != TVBOX` → 走现有逻辑；
2. `TVBOX` → 按 `externalKey` 找到配置，从 `remoteId` 解析 `siteKey/vodId`（借源用 `extJson.donorSiteKey`）；
3. 拉一次 `detailContent` 拿线路/选集；失败则提示"源已失效"，索引行与收藏保留；
4. 读 `progressDao.get(pid, MOVIE, movieId)?.extKey` 恢复线路 + 集数 + 位置；
5. `TvboxPlayResolver.resolve(...)`（kids/分类锁仍在这里强制校验）→ `playTvbox(...)`。

反方向同样需要一条：TVbox 详情页起播时，索引成功则把真实 `movieId` 带进 `playTvbox`，让收藏/进度有 Core id；只有索引失败才退回 `TVBOX_ITEM_ID`。

**下载必须排除 TVBOX**：`DownloadManager` 不认 `tvbox://`，列表/详情/播放页的"下载"入口要按源类型隐藏，否则会生成永远失败的下载任务。

#### 4.5.2 儿童档案的条目级过滤（决策 5，已确认）

**问题**：现有三处读取路径的 kids 过滤都是**分类级**，且 `categoryId == null` 一律放行——搜索/详情来源的 TVBOX 行恰好没有分类归属，会全部漏过：

| 读取路径 | 现状代码 | 漏洞 |
|---|---|---|
| `SearchReader.search` 电影行 | `it.categoryId == null \|\| it.categoryId !in hiddenMovieCats`（`content/SearchReader.kt:131-134`） | null 分类行放行 |
| `HomeFeed` 收藏/继续观看/Hero 等电影行 | 同构 null 放行（`home/HomeFeed.kt:246`，`hiddenState` 305-324） | 同上 |
| `LibraryViewModel.customized` | `categoryOf(item) == null \|\| categoryOf(item) !in hidden`（`ui/screens/library/LibraryViewModel.kt:453-459`） | 同上 |

**数据**：`MovieEntity.extJson.typeNames` 记录该条目**见过的所有分类名**，由 `CatalogIndexWriter.mergeExtJson` 做**并集合并、只增不减**（§4.4）。只增不减是必须的：条目可能先以"伦理片"被搜索索引、后以"电影"被浏览命中，若覆盖式写入，成人判定会被后来的良性分类名洗掉。站点 ext 带 adult/nsfw 标记时另写 `adultSite: true`（站点级标记没有分类名可查，只能落标记）。

**判定**：Core `AdultCategoryClassifier` 新增条目级判定，各读取路径共用同一谓词：

```kotlin
// content/AdultCategoryClassifier.kt
fun isAdultItem(extJson: String?): Boolean {
    if (extJson.isNullOrBlank()) return false
    val o = runCatching { JSONObject(extJson) }.getOrNull() ?: return false
    if (o.optBoolean("adultSite")) return true
    val names = o.optJSONArray("typeNames") ?: return false
    return (0 until names.length()).any { isAdult(names.optString(it)) }
}
```

**词表统一（本决策的行为变化点）**：TVbox 侧的中文补充词表（"伦理片/福利/午夜/写真/禁片/里番/无码/情色/三级/激情/偷拍/探花/18禁/限制级"，`TvboxCategoryPolicy.kt:28-31`）目前只在 app 侧生效，Core `isAdult` 中文只覆盖"成人/成年人"。若 Core 条目级检查只用 Core 词表，"伦理片"这类最主要的成人类目仍会漏过，决策 5 等于没做。因此**把中文补充词表并入 Core `isAdult`**，`TvboxCategoryPolicy` 删除私有词表、改为委托 Core。代价：IPTV 分类级的 kids 过滤同步收紧（同名 IPTV 分类也会被隐藏）——这是口径统一的预期结果，需在发布说明中写明；子串误伤风险（如"激情"）见 §10。

**应用点**（仅 `isKids` 档案启用，非儿童档案零开销）：

- `SearchReader.search` 电影行过滤追加 `!(isKids && isAdultItem(it.extJson))`；`curated` 的 CONTINUE/UNWATCHED 电影行一并覆盖（现状连分类级 kids 过滤都没有，属既有缺口，同一谓词顺带补上）；
- `HomeFeed` 电影行（收藏/继续观看/Hero/trending）加同一谓词；
- `LibraryViewModel.customized` 增加条目级判定参数，仅儿童档案传入。

**留在 app 侧的部分**：`TvboxPlayResolver` 的播放前拦截保持不变，仍执行完整 `TvboxCategoryPolicy`（站点 ext 标记、站点名判定、`kidsStrict`）。`kidsStrict` 的"分类名缺失即隐藏"不进 Core——Core 读取层不 fail-closed（与现有分类级口径一致），`typeNames` 为空且 `categoryId = null` 的行在 Core 搜索放行，由播放前拦截兜底。这是已确认的残余口径，写入验收说明。

### 4.6 用户态写入

**收藏**

```kotlin
// 新增：直接写 Core 表（与 Core app 层现有做法一致）
suspend fun addFavorite(profileId: Long, movieId: Long) =
    favoriteDao.add(FavoriteEntity(profileId = profileId, mediaType = MediaType.MOVIE, itemId = movieId))

// 取消：必须走 UserDataWriter，写删除墓碑，跨设备同步才不会"回魂"
userDataWriter.removeFavorite(profileId, MediaType.MOVIE, movieId)

// 状态
favoriteDao.isFavorite(profileId, MediaType.MOVIE, movieId)
```

`favoriteDao.add` 是 `OnConflictStrategy.IGNORE`，重复收藏不会抛错。UI 侧优先复用 `ContentActions.toggleFavorite`（删除已走 `userDataWriter`，带 tombstone），不要另写一套。

**进度**

```kotlin
progressDao.save(
    PlaybackProgressEntity(
        profileId = pid,
        mediaType = MediaType.MOVIE,
        itemId = movieId,
        positionMs = position,
        durationMs = duration,
        // JSON 而不是 \u0000 拼接：备份/恢复会把它原样带进 JSON，NUL 在部分序列化路径上不可靠；
        // JSON 也便于后续加字段（线路名、清晰度…）
        extKey = """{"flag":"$flag","episodeId":"$episodeId","index":$index}""",
    ),
)
```

- Core 的继续观看/进度条只读 `positionMs/durationMs`，不受 `extKey` 影响；
- TVbox 详情页续播时解析 `extKey` 恢复"线路 + 集数"；
- 换线路/换集只更新同一条 `(profile, MOVIE, movieId)`（Core 唯一索引），符合"一部片一条进度"的语义；
- **`extKey` 必须随备份一起走**：`UserDataResolver.exportAll` / `exportForSource` / `toJson` / `resolveAndInsert` 的 `prog` 记录要加这个字段，否则恢复后只剩位置、线路/集数丢失。

**历史**

```kotlin
historyDao.record(profileId, MediaType.MOVIE, movieId)   // 现有 API
```

**隐藏/改名/排序/锁**

```kotlin
val movieKey = CustomizeKeys.movie(entity)      // "<sourceId>:<siteKey>/<vodId>"
val catKey = CustomizeKeys.category(category)   // "<sourceId>:<siteKey>/<typeId>"

customize.setItemHidden(profileId, MediaType.MOVIE, movieKey, entity.name, true)
customize.setCategoryHidden(profileId, MediaType.MOVIE, catKey, true)
customize.setCategoryLocked(profileId, MediaType.MOVIE, catKey, true)
customize.renameCategory(profileId, MediaType.MOVIE, catKey, "我的电影")
```

### 4.7 播放链路改造

| 现状 | 目标 |
|---|---|
| `TVBOX_ITEM_ID = -2L` 作为"非 Core 条目"哨兵 | 索引成功时 `VodPlayback.itemId` 填真实 `movieId`；索引失败才用 `-2L` 降级播放 |
| `PlayKind.TVBOX` + `playingProfileId = -1L` 阻断 Core 写入 | `playKind == TVBOX` 只用于**引擎/字幕**分支；`playingProfileId` 填真实档案，`saveProgress` 的 TVBOX 分支改写 Core 进度（带 `extKey`），不再写 `TvboxStore` |
| `PlayerScreen` 用 `itemId == -2L` 同时隐藏收藏与字幕 | 拆成两个判断：收藏按 `itemId > 0` 正常显示（走 `ContentActions`）；字幕按 `playKind == TVBOX` 隐藏（TVbox 无字幕接口） |
| TVbox 详情页只看 `TvboxStore` 续播 | 索引命中后读 `progressDao` + `extKey`；`TvboxStore` 直接删除，不做存量迁移（决策 6，§6） |

`VodPlayback` 建议直接加 `isExternal: Boolean = false`（或把 `PlayKind` 提升为公开枚举后放进 `VodPlayback`）。**不建议只加 `catalogId` 而保留 `itemId = -2`**：`PlayerScreen` 仍要为一个字段拆两种语义，且 `seriesId ?: itemId` 的字幕键逻辑会被负数污染。

`playTvbox` 里的 `contentKey = "tvbox:$siteKey:$vodId"` 是播放器偏好（缩放/音量/音轨）的稳定键，改造时保持原样，不要换成目录 id。

### 4.8 分类浏览（B1 → B2）

- **B1（P1 完成）**：VOD 页分类条改读 Core：
  - `TvboxHomeViewModel` 的 `categories` 从 `state.categories` 改为 `categoryDao.observe(listOf(tvboxSourceId), MediaType.MOVIE)`；
  - 隐藏/改名/排序/锁由 `CustomizationStore.observeVisible` 叠加（复用 `LibraryViewModel` 的 `hiddenCategoryIds` 与 `categoryRail` 逻辑）；
  - 内容列表仍由 `loadCategory` 远端拉取，边拉边写索引。
- **B2（P4 终态）**：Library 页把 TVBOX 源当普通播放列表：
  - `Pager` 增加 `remoteMediator`，实现 `TvboxRemoteMediator`：`REFRESH/APPEND` → `categoryContent(site, typeId, page)` → `upsertStable` → `MediatorResult.Success(endOfPaginationReached)`；
  - `LibraryViewModel` 的搜索框、排序、网格/列表、隐藏、自定义分类、收藏全部零改动复用；
  - 远端筛选 `extend` 变化 → `generation++` + 清空该分类索引（旧行 TTL 清理）。

`RemoteMediator` 的关键约束：

```kotlin
// Paging3：Room 为唯一数据源；远端只负责把页写进来
class TvboxRemoteMediator(
    private val site: SiteConfig,
    private val typeId: String,
    private val sourceId: Long,
    private val indexer: TvboxIndexer,
    private val cursorDao: CatalogIndexCursorDao,
) : RemoteMediator<Int, MovieEntity>() {
    private val categoryKey = "${site.key}/$typeId"   // 与 CategoryEntity.remoteId / cursor 主键同口径

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MovieEntity>): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> {
                cursorDao.reset(sourceId, categoryKey)   // 刷新从第 1 页重来
                1
            }
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> (cursorDao.lastPage(sourceId, categoryKey) ?: 0) + 1
        }
        val result = sourceRepository.categoryContent(site, typeId, page.toString())
            ?: return MediatorResult.Error(IOException("categoryContent failed"))  // 失败不能当成"没有下一页"
        indexer.indexCategoryPage(sourceId, site, typeId, page, result.list)
        cursorDao.record(sourceId, categoryKey, page, System.currentTimeMillis())
        return MediatorResult.Success(
            endOfPaginationReached = result.list.isEmpty() || page >= result.pagecount,
        )
    }
}
```

游标主键是 `categoryRemoteId`，必须传 `"<siteKey>/<typeId>"`；传裸 `typeId` 会让不同站点的同名分类共用一个游标。

---

## 5. 索引生命周期

| 策略 | 规则 |
|---|---|
| 首次写入 | `lastSeenAt = now` |
| 重复命中 | `markSeen` 只更新 `lastSeenAt`（不触发哈希/整行更新）；**每次 `upsertMovies` 必须在同一事务里调用它**，否则"内容未变"的行不会刷新 `lastSeenAt`，正在浏览的内容也会在 30 天后被清掉 |
| 内容变化 | `upsertStable` 比对 `computeContentHash()`（含 `extJson/extGroup`）；变了更新、不变跳过。`typeNames` 并集合并必须先于哈希比对（§4.4），累积出新分类名即视为内容变化 |
| TTL | 默认 30 天未命中 → 候选删除 |
| 上限 | 单源默认 50,000 行；超出按 `lastSeenAt` LRU 删 |
| 保护 | **永不删除**：在 `favorites` / `playback_progress` / `watch_history` / `custom_category_members` / `content_order` 里的 MOVIE 行 |
| 分类清理 | 删除无条目的 TVBOX 分类（`CategoryDao.deleteEmptyMovieCategories`） |
| 失效 | 配置切换/站点删除 → 该 `sourceId` 的索引进入 TTL，**不删 `sources` 行**。删源会 CASCADE 删掉 `movies`，用户态瞬间变孤儿并被 `purgeOrphans` 清掉 |

```sql
-- 清理（伪 SQL）：用户态保护是硬条件
DELETE FROM movies
WHERE sourceId = :tvboxSourceId
  AND IFNULL(lastSeenAt, 0) < :cutoff
  AND id NOT IN (SELECT itemId FROM favorites               WHERE mediaType = 'MOVIE')
  AND id NOT IN (SELECT itemId FROM playback_progress       WHERE mediaType = 'MOVIE')
  AND id NOT IN (SELECT itemId FROM watch_history           WHERE mediaType = 'MOVIE')
  AND id NOT IN (SELECT itemId FROM custom_category_members WHERE mediaType = 'MOVIE')
  AND id NOT IN (SELECT itemId FROM content_order           WHERE mediaType = 'MOVIE');
```

清理必须在一个事务里完成，并在清理后调用 `FavoriteDao` / `ProgressDao` / `HistoryDao` / `CustomCategoryDao` / `ContentOrderDao` 的 `purgeOrphans`（此时孤儿才是真孤儿）。只跑收藏和进度的两个 `purgeOrphans`，会留下指向已删电影的"自定义分类成员"和"手动排序"脏行。

---

## 6. 存量数据：不迁移（决策 6，已确认）

**不做任何存量数据迁移。** 升级后 TVbox 的收藏/进度/历史从空开始，产品已确认接受。具体口径：

| 存量 | 处理 |
|---|---|
| `favorites.json` / `progress.json` / `history.json` | 不读、不迁。新版本用户态直接读写 Core 表；旧 JSON 文件留在磁盘无害，P5 随 `TvboxStore` 一并删除 |
| 旧分类锁 key（`tvbox:<siteKey>:<typeId>` 及 `typeName` 别名） | 不迁移。新口径 `<sourceId>:<siteKey>/<typeId>` 与旧 key 不匹配，旧锁自然失效（不生效、不报错）；P5 可选清理 `cust_*_MOVIE.lockedCats` 里的 `tvbox:` 前缀死 key |
| 迁移器 / 迁移标记 | 不存在。无 `TvboxUserDataMigrator`、无 `TvboxPrefs.coreIndexMigrated.*`、无"保留一个版本再删"的安排 |

注意区分：**Room schema 迁移（§8 的 `MIGRATION_53_54`）仍然必须做**——决策 6 免除的是 TVbox 用户态数据的搬运，不是 Core 数据库的结构演进；已有安装的 DB 停在 v53，加列加表必须走显式 Migration。

---

## 7. 改动清单

### 7.1 Core（`OwnTV_Core` mobile 分支 → vendor 快照）

| 文件 | 改动 |
|---|---|
| `model/Enums.kt` | `SourceType` 新增 `TVBOX` |
| `database/entity/ProfileEntities.kt` | `SourceEntity` 加 `externalKey: String?` |
| `database/entity/ContentEntities.kt` | `CategoryEntity` 加 `extJson`；`MovieEntity` 加 `extGroup/extJson/lastSeenAt` + 2 个索引；`computeContentHash()` 非空才折叠 `extGroup/extJson` |
| `database/entity/UserDataEntities.kt` | `PlaybackProgressEntity` 加 `extKey: String?`；新增 `CatalogIndexCursorEntity` |
| `database/OwnTVDatabase.kt` | `version = 54`、注册新实体、`ALL_MIGRATIONS` 加 `MIGRATION_53_54`；**把 2 个新索引登记进 `EXPECTED_NON_UNIQUE_INDEXES`**（`BulkInsertHelper` 会从 `sqlite_master` 删掉该表全部非唯一索引，只按这张表重建） |
| `database/Migration53To54.kt` | 新增（只加列/加表/加索引，不重建表） |
| `database/dao/SourceDao.kt` | 新增按 `type + externalKey` 查源（Provisioner 幂等用），避免每次启动重复建源 |
| `catalog/CatalogIndexWriter.kt` | 新增：稳定 upsert + markSeen（同事务）/ prune；`upsertMovies` 带 `mergeExtJson` 钩子（哈希前合并，§4.4） |
| `content/AdultCategoryClassifier.kt` | 中文补充词表并入 `isAdult`（口径统一，IPTV kids 过滤同步收紧，§4.5.2）；新增 `isAdultItem(extJson)` 条目级判定 |
| `content/SearchReader.kt` | `search` / `curated` 电影行追加 kids 条目级过滤（§4.5.2） |
| `home/HomeFeed.kt` | 电影行（收藏/继续观看/Hero/trending）追加同一 kids 条目级过滤 |
| `sync/SyncManager.kt`、`sync/SyncStatus.kt`、`sync/ImportFinalizer.kt` | `when` 增加 `SourceType.TVBOX -> Unit`（不参与常规同步/导入收尾） |
| `repository/ActiveSources.kt` | `activeProfileSources` **和** `activeSourceIds` 都豁免 `defaultSourceId` 收窄（§4.5） |
| `backup/BackupManager.kt` | `sourceTo` / `sourceFrom` 带上 `externalKey`；**索引不入备份**（与其它源一致：只备份源定义 + 用户态，内容靠 re-sync），恢复后 TVBOX 索引从空开始、按浏览重建。旧版本已能按"未知 type 跳过"处理（`sourceFrom` 返回 null + 计数） |
| `backup/UserDataResolver.kt` | `prog` 记录导出/恢复带 `extKey`（`exportAll` / `exportForSource` / `toJson` / `resolveAndInsert`） |
| `setup/SourceImporter.kt`、`repository/SourceEditor.kt`、`repository/SourceTester.kt` | 拒绝/特殊处理 TVBOX 源（不能当 IPTV 源编辑/测试） |
| `companion/*` | 远端编辑页拒绝 TVBOX 源 |

> `SourceType` 全仓约 300 处引用（含 vendor 快照与测试，主代码约 120 处），其中多数是 `==`/`!=` 判断；新增枚举值后**编译器只在穷举 `when` 处报错**，按报错逐个处理，重点是 `SyncManager / SyncStatus / ImportFinalizer / SourceImporter / SourceEditor / SourceTester / BackupManager / CompanionSourceEdit`。`SettingsPlaylistsPage` / `SettingsViewModel`（`SourceType.` 共 18 处）也要确认 TVBOX 源不被当成 IPTV 播放列表展示或编辑。

### 7.2 App（`OwnTV_Mobile`）

| 文件 | 改动 |
|---|---|
| `tvbox/index/TvboxIndexer.kt` | 新增：映射 + 索引编排 |
| `tvbox/index/TvboxIndexMapper.kt` | 新增：`ClassInfo/Movie → Entity` 纯函数（`typeNames` 单次快照 + `adultSite` 标记，§4.4） |
| `tvbox/index/TvboxRemoteMediator.kt` | 新增（P4） |
| `tvbox/repository/TvboxSourceProvisioner.kt` | 新增：配置 → `SourceEntity(TVBOX)` 的创建/更新；**必须 `sourceDao.link(ProfileSourceCrossRef)` 绑定全部档案**（`activeProfileSources` 走 `observeForProfile`，只建源不绑定等于没建；只绑当前档案则儿童档案看不到内容，§4.2）；只有激活配置 `syncMovies = true`。**删除配置时不删 `sources` 行**（会 CASCADE 删 `movies` 并连带清掉用户态），只置 `syncMovies = false`，索引走 TTL |
| `tvbox/repository/TvboxStore.kt` | **删除**（三个 JSON 文件的读写一并移除）；不迁移存量（决策 6，§6） |
| `tvbox/category/TvboxCategoryPolicy.kt` | 删除私有中文词表，委托 Core `AdultCategoryClassifier.isAdult`（词表已上移，§4.5.2）；站点 ext 标记判定提为可复用纯函数，供 Mapper 写 `adultSite` |
| `ui/screens/vod/TvboxViewModels.kt` | 分类改读 Core；收藏/进度改 Core；详情 `ensureIndexed`；续播读 `progressDao.extKey` |
| `ui/screens/vod/TvboxPages.kt` | 分类条渲染用 Core 实体；增加"隐藏/改名/锁"菜单；TVBOX 条目隐藏"下载"入口 |
| `ui/screens/vod/VodScreen.kt` | VOD 页没有路由参数，详情身份是内部 `pendingDetail`（`siteKey/vodId/typeId/typeName`）——补一个 `movieId` 即可，不需要改导航 |
| `ui/screens/library/VodTuner.kt` | `playMovie` 增加 TVBOX 路由（§4.5.1）；`playTvbox` 写 Core 进度/历史；`VodPlayback` 加 `isExternal`；TVBOX 进度写 Core（带 `extKey`） |
| `tvbox/play/TvboxPlayResolver.kt` | 增加"从 Core 条目反查"入口（按 `sourceId/externalKey` 找配置、拉详情、校验），供 `playMovie` 路由使用 |
| `ui/player/PlayerScreen.kt` | 收藏按 `itemId > 0` 开放；字幕按 `playKind == TVBOX` 关闭（拆成两个判断） |
| `ui/screens/settings/SettingsPlaylistsPage.kt`、`SettingsViewModel.kt` | TVBOX 源不按 IPTV 播放列表展示/编辑；删除配置走 `TvboxConfigRepository.deleteConfig` |
| `ui/screens/library/LibraryViewModel.kt` | `customized` 增加 kids 条目级过滤（§4.5.2）；Pager 增加 `remoteMediator`（P4） |
| `di/TvboxModule.kt` | 注入 `CatalogIndexWriter`、`TvboxIndexer` |
| `res/values*/strings_mobile.xml` | 新增"点播索引/清理索引"等文案 |

### 7.3 文档

- `docs/TVbox点播迁移方案.md`：把"不写 Core 表"改为"索引写 Core 表、播放仍走 TVbox"，并标注 §5.4（TVbox 独立 JSON 存储）的旧结论作废。
- `docs/Core源码集成方案.md`：追加 Core 侧改动的推送与 vendor 同步流程。
- `extras/FEATURE_MAP.md`：搜索/收藏/历史条目补 TVBOX 源。

---

## 8. DB 迁移与兼容

```kotlin
val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE sources ADD COLUMN externalKey TEXT")
        connection.execSQL("ALTER TABLE categories ADD COLUMN extJson TEXT")
        connection.execSQL("ALTER TABLE movies ADD COLUMN extGroup TEXT")
        connection.execSQL("ALTER TABLE movies ADD COLUMN extJson TEXT")
        connection.execSQL("ALTER TABLE movies ADD COLUMN lastSeenAt INTEGER")
        connection.execSQL("ALTER TABLE playback_progress ADD COLUMN extKey TEXT")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS catalog_index_cursor (" +
                "sourceId INTEGER NOT NULL, categoryRemoteId TEXT NOT NULL, " +
                "lastPage INTEGER NOT NULL DEFAULT 0, generation INTEGER NOT NULL DEFAULT 0, " +
                "syncedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(sourceId, categoryRemoteId))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_extGroup_lastSeenAt` ON `movies` (`sourceId`, `extGroup`, `lastSeenAt`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_lastSeenAt` ON `movies` (`lastSeenAt`)")
        OwnTVDatabase.healSchema(connection)   // 最后一个 hop 的固定动作
    }
}
```

注意：

- Core 有 `healSchema` 与 `EXPECTED_NON_UNIQUE_INDEXES`（`OwnTVDatabase.kt:1509-1539`）。`BulkInsertHelper` 在批量导入时会把 `movies` 上的**全部非唯一索引**从 `sqlite_master` 删掉，再按这张表重建；新索引不登记就会被永久删掉，下一次版本升级的 Room 全 schema 校验会崩溃（即 4.0.x → 4.1.0 的复现路径）。两个新索引必须同步进 `EXPECTED_NON_UNIQUE_INDEXES`。
- `exportSchema = true`，迁移后要提交 `schemas/.../54.json`。
- 备份兼容：旧版本 `sourceFrom` 对未知 `type` 返回 null、跳过并计数（`BackupManager.kt:1599-1603`），**不会整体失败**——原方案"恢复新备份会失败"的结论作废。真正要补的是：`externalKey` 要进 `sourceTo` / `sourceFrom`，`prog` 记录要带 `extKey`；否则恢复后源找不到配置、进度丢失线路/集数。

---

## 9. 分阶段实施

### P0：索引底座 + 收藏闭环

- Core：`SourceType.TVBOX`、字段、`MIGRATION_53_54`、`CatalogIndexWriter`、`ActiveSources` 两处豁免、`SourceDao` 查询。
- App：`TvboxSourceProvisioner`（配置 → 源，`sourceDao.link(ProfileSourceCrossRef)` 绑定**全部档案**，只有激活配置 `syncMovies = true`）、`TvboxIndexer`（分类/首页/详情）、`ensureIndexed`。
- 播放：`VodTuner.playMovie` 的 TVBOX 路由（§4.5.1）必须一起做，否则收藏页/继续观看点进去播不了。
- 收藏：`TvboxDetailViewModel.toggleFavorite` 改 Core；收藏页可见。
- 验收：点播里收藏一部片 → Library 收藏页出现、**能直接播放**；重启后仍在；取消收藏后消失。

### P1：分类复用

- VOD 分类条改读 `CategoryDao`；隐藏/改名/排序/锁全部走 `CustomizationStore`。
- 锁直接写新口径 key `<sourceId>:<siteKey>/<typeId>`；旧 `tvbox:` key 废弃不迁移（§6）。
- 验收：VOD 分类隐藏后不再出现；改名在 VOD 与 Library 同步；锁定后点分类弹 PIN；儿童档案隐藏成人分类。

### P2：历史/进度/继续观看

- `VodTuner` 写 Core 进度/历史；`VodPlayback.isExternal`；详情续播解析 `extKey`；`UserDataResolver` 备份带 `extKey`。
- 验收：TVbox 片播一半 → Home 继续观看出现 → 点进去续播到原集原位置。

### P3：搜索复用

- `SearchReader` 命中索引（源已豁免 `defaultSourceId`）。
- 搜索页保留"在线结果"分区（上一轮方案），并与索引结果按 `siteKey/vodId` 去重。
- 儿童档案：条目级 `extJson.typeNames` 检查落地（§4.5.2）——Core 词表统一、`SearchReader`/`curated`/`HomeFeed`/Library 列表四处接入；`typeNames` 并集随索引累积。
- 验收：搜一个浏览过/收藏过的点播片 → 与本地影片同页出现，分类/海报/年份正确；隐藏后搜不到；**儿童档案下，搜索来源（无分类归属）的成人分类条目搜不到、Home 收藏/继续观看不出现**。

### P4：Library 全量复用（RemoteMediator）

- `Pager` 加 mediator；TVBOX 播放列表在 Library 中可浏览、可搜索、可排序。
- 验收：Library → 选择"点播"播放列表 → 分类分页滚动、搜索框过滤、排序、隐藏、收藏全部可用。

### P5：清理与下线

- 删除已无调用方的 `TvboxStore` 及旧 JSON 文件（含只写不读的 `history.json`；不迁移，决策 6）、`TvboxSearchPage`、`TVBOX_ITEM_ID` 的旧路径（保留兜底）。
- 可选清理：`lockedCats` 里 `tvbox:` 前缀的失效旧 key（含 typeName 别名，§6）。

---

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **REPLACE upsert 换 id** | 收藏/进度全部变孤儿 | 红线：只用 `upsertStable/insertFresh`；单测断言"二次索引后 id 不变" |
| **索引清理删用户态** | 收藏/进度/自定义分类/手动排序静默消失 | 清理 SQL 反查五张用户态表；清理后再跑五个 `purgeOrphans`（§5） |
| `defaultSourceId` 收窄 | 搜索/Library/Home 看不到点播索引 | `activeProfileSources` **和** `activeSourceIds` 都豁免 |
| **Core 界面直接播 `tvbox://`** | 收藏/继续观看/搜索点进去播放失败 | `VodTuner.playMovie` 统一路由到 `TvboxPlayResolver` + `playTvbox`（§4.5.1） |
| **`extJson` 不进哈希** | 集数/线路/备注永远停在首次索引的样子 | `computeContentHash()` 非空折叠 `extGroup/extJson`；补单测 |
| 多分类归属抖动 | 同一片在搜索/分类间来回覆写 `categoryId`，行与 FTS 反复重写 | "首次非空分类，之后不覆盖"；搜索/详情来源不得写 null |
| 索引膨胀 | DB 体积/启动变慢 | 50k 上限、30 天 TTL、`extGroup` 分站清理、批量事务（200 行/批） |
| FTS 触发器写放大 | 大批量索引卡顿 | 分页小批写入；避免一次性拉全分类；必要时延后 FTS（评估后再定） |
| 远端内容下架 | 收藏点进去 404 | 详情失败给"源已失效"提示；索引行保留（保护用户态），播放时 `TvboxPlayResolver` 给换源引导 |
| 档案语义变化 | TVbox 现状全局共享 → 新方案按档案隔离 | **已确认（决策 3）**：用户态按 `profileId` 隔离；不迁移存量（决策 6），升级后从空开始，发布说明写明 |
| 儿童模式漏判 | 成人条目进入 Core 搜索/Home/Library | **已确认补条目级检查（决策 5，§4.5.2）**：`extJson.typeNames` 并集 + `adultSite` 标记，四处读取路径共用 `isAdultItem`；播放前 `TvboxPlayResolver` 拦截兜底（含 `kidsStrict`） |
| `typeNames` 只增不减 | 远端分类改名/条目被误标后，成人判定无法自动解除 | 并集语义是防"洗白"的刻意设计；误标行随 30 天 TTL 自然过期，设置页"清理索引"可立即重建 |
| 词表统一误伤 | 中文补充词表并入 Core 后，IPTV 同名分类对儿童档案同步隐藏（如"激情"子串） | 口径统一的预期行为变化（§4.5.2），发布说明写明；词表与 TVbox 现状完全一致，不新增词条；回归测试覆盖 IPTV 分类 |
| 播放哨兵改造 | 索引失败时无法播放 | 保留 `TVBOX_ITEM_ID` 降级路径；索引失败只影响收藏/进度，不阻断播放 |
| 多配置同时可见 | 搜索/收藏跨配置串内容 | **已确认（决策 2）**：只有激活配置 `syncMovies = true`；切配置只翻转布尔列，不改 `remoteId` |
| Core 上游合并冲突 | 下次吸收上游功能时冲突 | 改动集中在 catalog/content/backup 等少数模块、按功能成组提交；`docs/Core源码集成方案.md` 流程 |
| 备份兼容 | 恢复后源/进度不完整 | `externalKey` 进备份；`prog` 带 `extKey`；旧版本按未知 type 跳过（已有） |

---

## 11. 测试计划

### Core 单测

| 用例 | 断言 |
|---|---|
| `MIGRATION_53_54` | 53 库升级后旧数据完好、新列默认值正确、两个新索引存在且已登记进 `EXPECTED_NON_UNIQUE_INDEXES` |
| `CatalogIndexWriter.upsertMovies` | 同内容二次写入 → 行数不变、`id` 不变、`markSeen` 后 `lastSeenAt` 更新 |
| 内容变化 | `name/poster/extJson` 任一变化 → 同一 `id` 更新、`contentHash` 变化 |
| `typeNames` 并集 | 先以"伦理片"索引、再以"电影"命中 → `typeNames` 含两者、行 id 不变；并集增长触发哈希更新（§4.4 合并先于哈希） |
| `isAdultItem` | `typeNames` 任一命中（含中文补充词表）→ true；`adultSite=true` → true；空数组/缺字段/非法 JSON → false |
| 词表统一回归 | IPTV 分类级 kids 过滤：补充词表分类被隐藏；正常分类不受影响（行为变化点，§4.5.2） |
| kids 读取路径 | 儿童档案下 `categoryId=null` 且 `typeNames` 命中成人的行：`search`/`curated`/`HomeFeed` 均不出现；非儿童档案不受影响 |
| 哈希兼容 | 未携带 `extGroup/extJson` 的存量 M3U/Xtream 行哈希不变（不触发全量重写） |
| `prune` | 收藏/进度/历史/自定义分类/手动排序行都不被删除；过期未保护行被删除 |
| `ActiveProfileSources` / `activeSourceIds` | `defaultSourceId` 选中 IPTV 源时，TVBOX 源仍在集合内 |
| 备份 | `prog` 记录导出/恢复后 `extKey` 往返一致；`externalKey` 往返一致 |

### App 单测（纯函数）

| 用例 | 断言 |
|---|---|
| `TvboxIndexMapper` | `remoteId = siteKey/vodId`；借源条目用 `donorSiteKey` 且海报按借主站点 api 补全；`streamUrl = tvbox://...`；`siteKey`/`vodId` 为空的条目被拒绝；`typeNames` 取 `typeName` 或分类名兜底；站点 ext 标记 → `adultSite=true` |
| `TvboxIndexMapper` 分类 | `remoteId = siteKey/typeId`；name 带站点前缀；sortOrder 单调 |
| `extKey` 编解码 | JSON 形式的 `flag/episodeId/index` 往返一致；空值安全 |
| `TvboxCategoryPolicy` 委托 | 词表上移 Core 后判定矩阵与现状完全一致（站点 ext/站点名/`kidsStrict` 仍在 app 侧） |
| `VodTuner.playMovie` 路由 | 源为 TVBOX 时走 `TvboxPlayResolver` + `playTvbox`；其它源走原路径 |

### 集成/回归

- 模拟器：收藏 → 杀进程 → 重进仍在；继续观看 → 续播位置正确；隐藏分类 → 全局搜索搜不到；儿童档案 → 搜索来源的成人条目在搜索/Home/Library 均不可见，播放前拦截仍生效。
- `tools/regression/` 增加 `tvbox_index_test.py`：走收藏/续播/隐藏三条路径。
- 构建：`:app:testStandardDebugUnitTest`、`:app:lintStandardDebug`、`:app:assembleStandardDebug`、`core-check`。

---

## 12. 已确认决策（2026-11-11）

| # | 决策 | 结论 | 落点 |
|---|---|---|---|
| 1 | 源粒度 | **一个 TVbox 配置 = 一个 Core 源**；站点身份编码进 `remoteId`，不做"一站一源" | §4.2 |
| 2 | 多配置可见性 | **只让激活配置的源参与读取**（`syncMovies` 开关）；切配置只翻转布尔列，不改 `remoteId` | §4.2、§4.5 |
| 3 | 档案隔离 | **按档案隔离**：收藏/进度/历史/隐藏按 `profileId` 分；内容目录共享，源绑定全部档案 | §4.2、§4.6 |
| 4 | 索引范围 | **按 P0 处理**：只索引浏览/搜索/详情见过的内容，不做后台预索引 | §4.4、§5 |
| 5 | 儿童档案 | **补条目级 `extJson.typeNames` 检查**：并集累积 + `adultSite` 标记，Core 词表统一，四处读取路径接入 | §4.5.2 |
| 6 | 存量数据 | **不迁移**：旧 JSON 用户态与旧锁 key 直接废弃；Room schema 迁移（§8）不受影响 | §6 |

