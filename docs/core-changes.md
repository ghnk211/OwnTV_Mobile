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
