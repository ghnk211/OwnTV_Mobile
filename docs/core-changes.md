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

## 新增的 Mobile 专属能力

（暂无）

## 上游同步记录

| 日期 | 方式（merge/cherry-pick） | 上游提交 | 备注 |
|---|---|---|---|
| 2026-11-07 | merge | 2f7d4e3b | 首次同步；本地 1.0.70 改动与上游无冲突合并 |
