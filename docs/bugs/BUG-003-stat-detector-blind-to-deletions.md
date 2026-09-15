# BUG-003 无 git 仓库时，stat 检测器看不见"删除源文件"

> 状态：**✅ 已修**（2026-09，E 第 2 步落地）· 原始复现与机制保留在下面，供回看
> 修法：`SiteDetect.detectByStat` 在单趟遍历后，用"**上次的源记录键集 − 本次遍历到的文件**"做差集补删除（零额外 I/O）；
> `SiteDetect` 的 `visited` 顺便供 `SiteIncremental.recordInertSources` 使用。
> 验证：`IncrementalTest.deletedPageKeepsOthersSkippedAndWarnsOrphan`（删页 → 其余页照旧跳过 + 孤儿警告）。

## 现象

`sets/` 没有 git 仓库时删掉一个页面源文件（例如 `sets/pages/engine-demo.json`）：

- `ssvul build --detect` 打印 **"无变更"**（或只报那些"从未被记录"的惰性文件），**不含这个删除**；
- watch/预览因此**不重建**，`output/pages/engine-demo/` 的旧产物**继续被服务**，看起来像"删了没生效"；
- 手动跑一次完整构建时 `reportOrphans` 才会警告"有 N 个产物已不再生成"（因为要等到写盘相）。

## 触发条件

| 条件 | 结果 |
|---|---|
| `sets/` 有 git 仓库（标准布局 init 时会 `git init` + `git add -A`） | ✅ 正常：`git status --porcelain -uall` 报工作区删除（Y 列非空） |
| `sets/` 无仓库 / `--no-git` / `sets` 目录名非标准（CLI 不建库） | ❌ **漏报删除** |

**新增文件不受影响**（没有源记录 → 会被报成变更）；漏的只有**删除与改名**。

## 证据（实测）

```
# build/etest 副本（无 git）：删掉一页后检测
$ rm build/etest/site/pages/engine-demo.json
$ java ... Main build --sets build/etest/site --output build/etest/out --assets build/etest/assets --detect
[变更检测] stat → 7 个文件          ← 这 7 个是"从未被记录"的惰性文件（data/*、.adds、被覆盖的模板、favicon…）
```

根因：`detectByStat()` 用 `collectFiles(setsDir, all)` 收集文件后逐个与源记录比对——
**删除的文件不在 `all` 里，永远不会被检查**。而 `manifest.sources` 里明明有它的记录（记录是"上次的源状态"）。

## 规避（E 落地前）

- 删/改页后用 `--rebuild`；
- 或让 `sets/` 有 git 仓库（标准布局的首建会自动建；也可 `git -C sets init && git -C sets add -A`）。

## 计划修法（E 第 2 步，**零额外 I/O**）

manifest v2 里有**页表**（页身份 → {own, group, outs}）。构建开始时把"上次的页表键集合"与"本次遍历到的
页面文件集合"做差集：多出来的 = 新增页，少掉的 = 删除页 → 两者都直接进变更集。
不需要任何额外 stat（遍历本来就要做）。
