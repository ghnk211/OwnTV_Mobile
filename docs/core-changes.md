# Core 改造记录

## 上游与 fork

- 上游仓库：https://github.com/ahXN00/OwnTV_Core
- 个人 fork：https://github.com/ghnk211/OwnTV_Core
- 本地改造分支：mobile（跟踪 origin/mobile）
- 上次完整同步：2f7d4e3b / 2026-11-07
- 已吸收的上游提交：无（首次同步为整分支 merge）

## 删除的功能

（尚未删减）

## 修改的行为

| 文件 | 行为差异 | 原因 | 提交 |
|---|---|---|---|
| tools/i18n/safe_literals.txt | 将 `programme_details` 归类为 preference-key | 与 long-press 菜单里新增的字面量保持一致，避免 i18n ratchet 报 UNCLASSIFIED | 2ea6df51 |
| core/…/database/dao/ChannelDao.kt | 新增只读查询 `snapshotHistory(profileId, sourceIds, limit)` | 播放页左侧悬浮列要「最近观看」的一条界内快照；`pagingHistory` 是分页源，取不了首屏 | 待提交 |
| tools/i18n/safe_literals.txt、hardcoded_baseline.txt | 新增的 4 条 watch_history 查询片段归入 room-schema，baseline 253 → 249 | 同上查询带来的字面量，属 Room 表名/列名，不是 UI 文案 | 待提交 |
| core/…/categorylock/SealedCategoryAction.kt | 新增 `PLAY`（点击已加锁分类：验 PIN 后直接起播），`run(...)` 相应加一个 `play` 回调 | 直播电视主页面变成分类页，点分类即播放，加锁分组需要「验完 PIN 就播」而不是再点一次 | 待提交 |

## 新增的 Mobile 专属能力

（暂无）

## 与 Mobile 的同步约定

Mobile 仓库通过 `vendor/OwnTV_Core`（git subtree）参与 composite build，**不**读这里的检出目录。
改动流程：在本仓库改 → 提交到 `mobile` 分支 → 在 Mobile 仓库执行
`git subtree pull --prefix vendor/OwnTV_Core <core-remote> mobile --squash`。
在同步完成前，两处需保持同一份内容（本仓库与 `vendor/OwnTV_Core` 逐字节一致）。

## 上游同步记录

| 日期 | 方式（merge/cherry-pick） | 上游提交 | 备注 |
|---|---|---|---|
| 2026-11-07 | merge | 2f7d4e3b | 首次同步；本地 1.0.70 改动与上游无冲突合并 |

## TVbox 点播迁移（2026-11-08）

`docs/TVbox点播迁移方案.md` 的实施**未修改 `vendor/OwnTV_Core` 任何文件**：本仓库的
`vendor/OwnTV_Core` 快照保持 `Core mobile @0619549b`，Core Room schema 仍为 v53。
分类锁/儿童模式按方案 §5.5 只复用 Core 的 `lockedCats`（MOVIE section，`tvbox:` 前缀 key）、
Customize PIN、`CategoryUnlockSession`、`PinAttemptLimiter` 与 `AdultCategoryClassifier`，
全部经既有 Koin 单例（`DataModule`）注入，无 Core 源码/资源改动，无 subtree 同步需求。

## 点播内容接入 Core — P0（2026-11-11）

`docs/点播内容接入Core方案.md` 的 P0 阶段先直接改了 `vendor/OwnTV_Core`，**已同步回
`OwnTV_Core` 的 `mobile` 分支**：Core 提交 `c6f90356`，已 `push origin mobile`，随后用
`git subtree pull --prefix=vendor/OwnTV_Core https://github.com/ghnk211/OwnTV_Core.git mobile --squash`
更新快照。两处现在逐字节一致，下次照 `docs/Core源码集成方案.md` 的常规流程走即可。

Core 改动清单（P0，提交 `c6f90356`）：

| 文件 | 改动 |
|---|---|
| `model/Enums.kt` | `SourceType.TVBOX` |
| `database/entity/ProfileEntities.kt` | `SourceEntity.externalKey` |
| `database/entity/ContentEntities.kt` | `CategoryEntity.extJson`；`MovieEntity.extGroup/extJson/lastSeenAt` + 两条索引；`computeContentHash()` 非空折叠新字段（`lastSeenAt` 不进哈希） |
| `database/entity/UserDataEntities.kt` | `PlaybackProgressEntity.extKey`；`CatalogIndexCursorEntity` |
| `database/Migration53To54.kt` | 新迁移（只加列/表/索引） |
| `database/OwnTVDatabase.kt` | v54、注册新实体/迁移/两条索引；`catalogIndexCursorDao()` |
| `database/dao/SourceDao.kt` | 按 `type + externalKey` 查源、按类型列源、`updateSyncMovies` |
| `database/dao/MovieDao.kt` | 索引快照（`categoryId/extJson`）、`markSeen`、TTL/LRU 清理（反查五张用户态表） |
| `database/dao/CatalogIndexCursorDao.kt` | 新增 |
| `catalog/CatalogIndexWriter.kt` | 新增：稳定 upsert（禁 REPLACE）+ 同事务 markSeen + prune + 分类 upsert |
| `repository/ActiveSources.kt` | 两处 `defaultSourceId` 收窄豁免 `SourceType.TVBOX` |
| `backup/BackupManager.kt` | `externalKey` 进入导出/恢复 |
| `companion/*`、`live/ProbeChannelSource.kt`、`repository/EpgRepository.kt`、`repository/SourceTester.kt`、`sync/SyncManager.kt` | TVBOX 分支（拒绝编辑/探针/EPG/测试；不参与常规同步） |
| `di/DataModule.kt`、`di/DatabaseModule.kt` | 注册 `CatalogIndexWriter`、`CatalogIndexCursorDao` |
| `schemas/.../54.json` | 构建导出 |

未实施（P1–P5）：分类条改读 Core、历史/进度落 Core、搜索/Home/Library 的儿童条目级过滤、
RemoteMediator、删除 `TvboxStore`。因此 P0 阶段 TVBOX 索引行会出现在搜索/Home 中，儿童档案的
条目级过滤仍需按方案 §4.5.2 补齐。

## 点播内容接入 Core — P0 收尾（2026-11-11，同日）

复核 P0 时补掉三处实现缺口。前两项已在 Core 提交 `c6f90356` 里，第三项在 App 侧：

1. **`movies.categoryId` 从未被写入**（Core 无改动，App 侧）：`TvboxIndexer.indexClasses()`
   返回的 `remoteId → 分类 id` 映射被丢弃，`indexCategoryPage` 硬编码 `categoryId = null`，
   整源条目都没有儿童/锁归属；连带 `prune` 的 `deleteEmptyMovieCategories` 会把全部分类当
   "空分类"删掉。现在 `indexCategoryPage` 与 `ensureIndexed` 按 `siteKey/typeId` 回查分类行
   （key 拼法收敛到 `TvboxIndexMapper.categoryRemoteId()`）。
2. **"首次非空分类不覆盖"只写了半边**：旧实现仅在旧值为 null 时保留，旧值非空仍会被新分类
   覆盖。归属补齐后，同一部片在两个分类间浏览会来回改 `categoryId`、改哈希、重写整行并触发
   FTS。现在旧值非空即保留。
3. **索引生命周期没有触发点**：`CatalogIndexWriter.prune` 无调用方，30 天 TTL / 5 万行上限
   从未执行。`TvboxRuntime.restore()` 在源供给完成后跑一次 `TvboxIndexer.maintain()`，每进程
   一次，覆盖所有配置（切走的配置也要老化），不落在浏览路径上。

仍未实施：P1–P5（同上）；`PlaybackProgressEntity.extKey` 仍无人写入（P2 用）；设置页手动
"清理索引"入口与文案（方案 §7.2）。

## 点播内容接入 Core — P1–P5（2026-11-11）

`docs/点播内容接入Core方案.md` 的 P1–P5 已全部实施。P0 的索引骨架之上，把分类、用户态、
搜索、Library 续页全部接到 Core，并删除私有存储。**6 项已确认决策（方案 §12）全部落实**：
一配置一源、仅激活配置可见、按档案隔离、只索引见过的内容、儿童档案条目级 `extJson` 检查
（统一 Core 词表）、不做存量数据迁移。

实施要点：

- **P1 分类复用**：VOD 分类条改读 Core `categories`，隐藏/改名/排序/锁全部复用
  `CustomizationStore`；锁 key 收敛为 `<sourceId>:<siteKey>/<typeId>`（MOVIE section）；
  儿童判定委托 `AdultCategoryClassifier.isAdult`。
- **P2 历史/进度/继续观看**：`playTvbox` 起播写 Core 历史、5s 定时 + 暂停/退出写 Core 进度
  （`PlaybackProgressEntity.extKey` 经 `TvboxExtKey.encode(flag, episodeId, index)` 带线路/集数身份）；
  详情页续播读 Core 进度（按 `extKey` 匹配）；`TvboxPlaybackRouter` 路由到 Core 播放界面；
  备份导出/恢复携带 `extKey`。`TVBOX_ITEM_ID = -2L` 仅保留为**索引失败时的播放哨兵**：可播放、
  不写进度、收藏入口隐藏（`itemId = -2` 绝不进 Core 用户态表）。
- **P3 搜索复用**：在线分区 `TvboxOnlineSearch` 聚合（每源先到先显示）；已索引条目经 Core
  `SearchReader` 命中；儿童条目级 `isAdultItem(extJson)` 过滤补到四处读路径
  （`SearchReader.search`、`SearchReader.curated`、`HomeFeed`、`LibraryViewModel`）。
- **P4 Library RemoteMediator**：`TvboxRemoteMediator` + Factory，Room 单一真相，游标
  `"<siteKey>/<typeId>"`，页 1 进程级缓存（`CACHE_TTL_MS = Long.MAX_VALUE`），
  `CatalogIndexCursorDao.bumpGeneration` 失效旧页。
- **P5 清理与下线**：删除 `TvboxStore` 与私有 JSON（**不迁移**，决策 6）、`TvboxSearchPage`、
  `SourceRepository` 的 `searchProgress` 共享流、`VodTuner`/`TvboxDetailViewModel`/
  `TvboxPlaybackRouter` 的 `siteKey/vodId` 冗余字段；设置 › 播放列表新增"清理点播索引"
  （仅当存在 TVBOX 源时显示，`maintain(ttlDays = 0)` 清未受保护索引行，浏览时重建；
  收藏/历史/进度不受影响）。

Core 改动清单（P1–P5，Core 提交 `e0556f0f`，在 `c6f90356` 之上，10 文件 +126/−17）：

| 文件 | 改动 |
|---|---|
| `content/AdultCategoryClassifier.kt` | 中文成人词表 verbatim 上移为共享常量；新增 `isAdultItem(extJson)`（`typeNames` 并集 + `adultSite` 标志） |
| `content/SearchReader.kt` | `search` 与 `curated`（CONTINUE/UNWATCHED）对儿童档案按 `isAdultItem` 过滤 TVBOX 条目 |
| `home/HomeFeed.kt` | `Hidden.isKids` 分支 + 收藏电影/剧集行按 `isAdultItem` 过滤 |
| `database/dao/ProgressDao.kt` | `updateIfNewer` 增 `extKey` 参数；快照 SELECT 带 `extKey` |
| `database/dao/UserDataExportRow.kt` | 进度导出行新增 `extKey` 字段 |
| `backup/UserDataResolver.kt` | 进度导出/恢复 `putOpt("ext")` / `optStringOrNull`（旧备份无 `ext` 仍兼容） |
| `database/dao/CatalogIndexCursorDao.kt` | 新增 `bumpGeneration`（P4 失效旧页） |
| `backup/BackupV17ScopingTest.kt`、`content/AdultCategoryClassifierTest.kt` | 备份 `extKey` 往返、`isAdultItem` 词表/标志用例 |
| `tools/i18n/safe_literals.txt` | 新增字面量归类（Core 仍 360 baseline + 5830 safe） |

App 侧（`vendor` 之外）：新增 `tvbox/play/TvboxExtKey.kt`、`tvbox/index/TvboxRemoteMediator.kt`、
`tvbox/search/TvboxOnlineSearch.kt` 与 `OnlineSearchAdapter.kt`（各带 JVM 用例）；改 `VodTuner`、
`TvboxDetailViewModel`、`TvboxPlaybackRouter`、`TvboxPlayResolver`、`SourceRepository`、`TvboxIndexer`、
`TvboxModule`、`LibraryModule`、`SettingsModule`、`SettingsViewModel`、`SettingsPlaylistsPage`、
`SettingsScreen`（`LEAF_ROWS` 收录清理行）、`LibraryViewModel`、`SearchViewModel`/`SearchScreen`、
`MobileNavHost`、`PlayerScreen`、`VodScreen`、分类锁/策略/密封守卫、`ConfigParser`、
`strings_mobile.xml`（三语清理文案，删 5 条旧搜索文案）；删 `TvboxStore.kt`、`TvboxSearchPage`。
`app/build.gradle.kts` + `libs.versions.toml` 仅加 **test-only** `org.json`（真 `JSONObject`，
供 `extKey` 编解码 JVM 用例；android.jar 的是桩），与 vendor 同版本，不动 Core 构建脚本。
守护测试 `SettingsSearchCoverageTest` 全绿（367 app 用例），Core 用例全绿，app/Core i18n 闸均通过
（app 0 baseline + 1976 safe；Core 360 baseline + 5830 safe）。

同步状态：vendor 改动尚未提交回 Core `mobile`，亦未 `git subtree pull`。按
`docs/Core源码集成方案.md` §4.6 流程：Core 提交 → `push origin mobile` → Mobile 提交 →
`git subtree pull --prefix=vendor/OwnTV_Core … mobile --squash` → 校验逐字节一致 → 回填本表提交号。
