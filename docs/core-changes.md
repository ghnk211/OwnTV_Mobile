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

- **TVbox JS 源（drpy2）**：`tvbox/engine/js/**` 已落地，引擎来源 VOD `4c8d289`；**未改 Core**，也未改 `vendor/OwnTV_Core`（2026-11-12）。

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
