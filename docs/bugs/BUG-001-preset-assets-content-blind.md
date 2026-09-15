# BUG-001 传递性预设/第三方资产的内容改动不会被检出（E 增量跳过之后）

> 状态：**已缩小到"传递性文件"**（2026-09 方案 (c) 落地后重写）· 决定不修（文档化 + `--rebuild`）
> 影响面：只有**直接替换传递性 vendor 文件**的人（例：换掉 KaTeX 字体、替换 `lib/` 里第三方产物）。
> 相关代码：`SiteBuilder.refPreset` / `detectAssets` / `PageScope.refPreset`、`SiteWrite.writeAll`

## 方案 (c) 之后，预设侧分成两类

| 类别 | 例子 | 是否检出 | 成本 |
|---|---|---|---|
| **被页面按名引用/读过** | `page/BASE.html`、`divs/<类型>/*`、`md/css/md-code-<主题>.css`、`md/js/*`、`div-libs/list/*`、`runtime/ssvul-div.js` | ✅ **检出**，且**只重渲染引用它的页**；若只是被复制（css 内容变、href 不变）则**连页都不重渲染**，只刷新那份副本 | 每构建多一趟 stat：实测 example-sets **41 键 = 8ms（3 线程）**、300 页站 2 键 = 2ms |
| **传递性文件** | `lib/katex/fonts/*`（60 个，被 `katex.min.css` 内部 `url()` 引用）、`lib/` 内其它内部依赖 | ❌ **不检出** | 要检出须 stat 全部预设副本（example-sets 77 个）≈ 8–20ms/构建 |

实现（(c)，2026-09 落地）：
- `PageScope.refPreset(suffix)` → 把 `assets:<后缀>` 记进**该页依赖**（**引用即依赖**）；
- `SiteBuilder.refPreset` → 存在性检查本来就要一次 stat，**顺手**写一条预设源记录（净零额外 I/O）；
- `SiteBuilder.detectAssets()` → 在 `sets/` 遍历之后 stat「页依赖里出现过的 `assets:` 键 ∪ 由页 outs 反推的 `assets/pre/*` 源」
  （后者覆盖"只被引用、从未被读"的那批，也给改动前写下的旧记录兜底）。

实测证据（example-sets 副本，CLI）：
```
改 assets/md/css/md-code-dark.css → 页=渲染0/跳过11，写盘 1（只刷新那份副本）  ✓
改 assets/page/BASE.html          → 页=渲染11/跳过0（每页都读它）             ✓
两者之后「增量产物 vs --rebuild 全量产物」diff -rq = 0 行                      ✓
```
JUnit 门禁：`PerfStructureTest.incrementalMatchesFullRebuildByteForByte` 场景 ③（预设侧改动）。

## 残留：传递性文件

**现象**：直接替换 `src/assets/lib/katex/fonts/*` 这类**只被预设内部引用**的文件后，普通构建会把所有页判为未变 →
产物里那份副本仍是旧内容，且**没有任何警告**。

**为什么"正常用户不触发"**：用户侧的每一样定制都有通道，不需要碰 vendor 内部文件——

| 定制对象 | 用户侧通道 | 证据 |
|---|---|---|
| div 组件 | `sets/divs/<类型>/`（**优先级高于**预设）+ `.extends` 继承预设类型 | `div-guide.md:20/89`、`README.zh.md:191/256` |
| code-ui / callout 样式 | `sets/global/codeui/`、`sets/global/callout/`（预设里的是模板，**永不自动注入**，复制到 sets 才生效） | `README.zh.md:322/365`、`callout-guide.md:97` |
| 页面外壳槽位 | 页面键 `head`/`theme`/`md-css`/`md-js`/`deps`/`favicon`…（外壳标记本身**生成器内部使用**） | `README.zh.md:413` |
| 自定义主题 | `src/assets/md/css/md-code-<主题>.css`（**新增与后续编辑都被检出**） | `README.zh.md:187` |
| 数据 / favicon / 离线镜像 | `sets/data/**`、`sets/favicon/**`、`sets/outer/**` | — |

**为什么不修**：代价是**每次构建**都 stat 全部预设副本（8–20ms，永久），而收益只落在"手工替换 vendor 内部产物"这一种人身上；
`--rebuild` 已是现成兜底，**升级生成器**则由配置指纹里的版本号自动全量。

**规避**：替换 vendor/传递性文件后加 `--rebuild`；或顺手 bump 版本号。
