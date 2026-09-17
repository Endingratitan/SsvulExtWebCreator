# 变更台账（对外契约的破坏性变更）

> 这里只记录**会影响站点作者**的变更：改名的键/参数/div 类型、被移除的能力、不兼容的默认值或产物路径。
> 每条给出：**变更 → 旧写法为什么不生效 → 迁移写法**。日常修复、内部重构与踩坑记录见 `tech.md`（不入库）。

## 0.4.2（2026-09）：md 主题改革 + 两个既有 bug 修复

> 一句话：**md 文件只提供文本数据，呈现关联交给 div**；md 主题注入一律"注册类 + 作用域化"。
> 渲染逻辑（解析/净化/锚点/脚注/高亮/白名单）没动。

### 破坏性变更（站点作者需要改的地方）

1. **`md-options` → `md`**：页面级与 div 级合并成同一个对象，就近覆盖（div > 页面 > `Environment.config` > 内置默认）。
   - 旧写法 `"md-options": {"mode":"strict"}` → 新写法 `"md": {"mode":"strict"}`（4 个渲染选项名不变）。
   - 页面级 `"md-css": "pre-assets/md/css/md.css"` → 新写法 `"md": {"css": "…"}`。
2. **`md` 新增两个旋钮**：
   - `css`：`default`（预设主题 `md/css/md.css`）／`none`（**不要**预设排版主题，样式全交给站点自己的 css）／`pre-assets/…`、`global/…`（指定主题文件）。
   - `wrap`：`true`（默认，包 `<div class="md-body">`）／`false`（裸内容，外壳交给 div 模板）。
3. **主题注入改为"作用域化副本"**：产物里**不再出现** `assets/pre/md/css/md.css`；改为
   `assets/css/md-theme-<hash16>.css`，挂类在声明它的 div 包装元素上（`class="ssvul-<div名> md-theme-<hash16>"`）。
   - 想引用/覆写主题样式：改用 `.md-theme-*` 之外的自有选择器，或把主题文件放进 `sets/global/` 用 `md.css` 指定（见下）。
   - 类名由**归一化后的 spec 字符串**派生（路径 hash，非内容 hash）：改主题内容**不换类名/文件名** ⇒ 引用页 HTML 字节不变、增量只重写 1 个 css。
4. **`Environment.config` 新增两个站点级默认**：`md-css=default|none|<spec>`、`md-wrap=1|0`（页面/div 可就近覆盖）。
5. **`md-css` 的形态收窄**：`md.css` 只接受 `default` / `none` / `pre-assets/…` / `global/…`；
   文档曾提到的 `bk/`（bucket 调用名）与 `http(s)://` **未实现**，远程主题请先落到 `sets/global/` 再用 `global/…` 引用。

### 打包/发布修复（0.4.2 发布前实测抓到，影响所有用 jar 的人）

7. **`gradlew jar` 在 Gradle 9 下直接失败**：`processResources` 的 `doLast` 用了 Gradle 9 已移除的
   `destinationDirectory` 属性 ⇒ 版本文件改为独立生成任务（`genVersion`）+ `from(genVersion)` 进资源。
8. **源码树里有一份历史遗留的 `src/main/resources/ssvul-version.txt`**（生成物误入源码）⇒ 与生成的那份重名
   ⇒ `processResources` 报 duplicate 而失败。已删除（构建会自己生成；**提交时请带上这处删除**）。
9. **`java -jar … init` 曾完全不可用**：`templates/site/.gitignore` 命中 Ant/Gradle 的**默认排除**
   （`**/.gitignore`，`include '**'` 也绕不过），打不进 jar ⇒ init 抛"模板资源缺失"。
   处置：模板改名 `gitignore.txt` 进包，`SiteInit` 写盘时按别名改回 `.gitignore`，并留兜底内容。

10. **`sets/favicon/` 里的点文件不再被发布**：`init` 骨架自带 `favicon/.gitkeep`，此前会被原样复制进
    `output/favicon/`。现在与 `data/`、`pages/` 口径一致——**点文件是项目管理用，不进产物**。

### 顺带修掉的真 bug（也影响产物）

6. **同一份 css 文件里的重复选择器不再被删除**：`CssDeduper` 原设计是"同选择器后到胜、前驱整块删除"
   （为 div 继承链省流量），但它被套用到了**同一个文件内部** ⇒ 一个 css 文件里把同名选择器分两次写
   （基础样式 + 后续微调，极其常见；真站点实测 32 个重复选择器）会**静默丢掉前一条**，
   表现为字体/横幅/夜间模式大面积错乱。现在：**同文件内全保留，只有"后一个来源文件"才删前驱**
   （div 继承链的既定语义不变，见 `docs/UserWrite/div-guide.md` §5）。

## 0.4.0（2026-09）

> 本版主项是**并行渲染**，管线内部结构大改；按既定原则**不考虑向前兼容**（`SitePages`/`SiteTags` 的构造、
> `SourceWatcher.start` 的签名、`BuildStats` 的计数类型等都按最顺的形状改了）。对外产物只有下面第二条那处变化。

### 新增：**增量跳过**（E）——未变页不重渲染、无变更"秒回"

**它做什么**：构建前先检测"哪些源变了"，然后**只重渲染受影响的那几页**；若一个源都没变，则
**不扫描、不渲染、不写盘**，只逐产物 stat 一遍（"秒回"）。

- 判据（可证等价，不是启发式）：每页记下它**实际读过**的源键（`readFile` 埋点，缓存命中也算）与它**产出**的产物 rel；
  下次构建只要"这些源键一个都没变 + 本页产物都还在"，该页就跳过——跳过时**重放副作用**
  （继承写集、重放预设引用、注册 `@page/` 目标、恢复 search/list 分片标志）。
- 没有页归属的读取（`.extends`/`.contract`/`Environment.config`/`sets/global/**`/`sets/data/**`/README）记进一个
  **站点级依赖集**：它们一变就**全量**（无法精确归因，只此一处保守锤）。
- 配置指纹 = 生成器版本号 + `Environment.config` 原文：改配置或**升级生成器**都会全量（避免"升级后产物停留在旧逻辑"）。

**实测（本机 4 核 / WSL，example-sets 120 产物）**

| 场景 | 改前 | 改后 |
|---|---|---|
| 无变更（同 JVM 内的构建相） | ~1400 ms | **~520 ms**（其中检测 ~96 ms；不扫描/不渲染/不写盘） |
| 改一页 | 全量重渲染 11 页 | **渲染 1 页 / 跳过 10 页**，写盘 0–1 个文件 |
| 改共享 div 模板 | 全量 | **只渲染用到该 div 的 8 页**（另 3 页跳过） |

**新增开关**：`--verify 0|1`（默认 **1**）——`0` = 不校验产物（最快）；`1` = 逐产物 stat，
**产物被删或被手改 → 只重渲染它的归属页并修回**（＝自愈，与旧行为一致）。

**行为变化（都不破坏产物字节，已由"增量产物 vs `--rebuild` 全量产物逐字节相同"门禁钉住）**

1. **变更检测现在每次构建都跑**（增量跳过的输入）。`--detect` 从"要不要检测"变成"要不要多打一行"；
   构建输出多一行 `[增量] …` 说明本次是全量还是跳过了哪些。
2. `.ssvul/deps-<站点号>.json` 升到 **v2**（加了 `config`/`pages`/`shared`）。旧文件会被当作"无记录"→
   **下一次构建是全量**（之后恢复正常加速）。该文件仍是本地状态、**不进 `output/`**、手改无害（下次构建重写）。
3. **删除源文件现在能被检出**（此前无 git 仓库时 stat 检测器看不见删除，见 `docs/bugs/BUG-003`）。
4. `spawn` 出来的检测遍历改为**单趟 + 按条目并行**：300 个源文件 `--detect` **762 ms → 53 ms**。
5. **列表/搜索类页面现在会跟着一起更新**（修掉一个真 bug）：`build:page-index` 静态列表与 `ssvul:inline` 内联条目把**别的页**的
   标题/日期写进了自己的 HTML，而页与页之间没有文件级依赖——此前增量构建不会重渲染它们（列表会一直拿着旧条目）。
   现在"任何页面依赖变化 / 页集合变化"都会重算这些页。
   另外：**只改文件 mtime（`touch`、`git checkout`、拷贝文件）不再"秒回"**——索引条目的日期由 mtime 派生，秒回会让它停在旧日期
   而与全量构建不一致。两者都由门禁用例钉住（`IncrementalTest`）。

**已知限制（都不是缺陷，是设计边界或"传递性文件"；详见 `docs/bugs/`）**

- **预设侧分两类**：① **被页面按名引用/读过的**（BASE、预设 div 模板/js/css、主题 css、list/md-csr 库…）——
  E 现在能检出，且**只重渲染引用它的页**（引用即依赖；若只是被复制，则连页都不用重渲染，只刷新那份副本）。
  ② **传递性文件**（只被预设内部引用：KaTeX 的 60 个字体、vendor 内部依赖）——检不出来，直接替换它们请加 `--rebuild`。→ `BUG-001`
  - 所以 `README.zh.md` 的"自定义主题 = 在 `src/assets/md/css/` 放 css"**照旧可用**：新增与后续编辑都会被检出。
- 直接改 **`sets/outer/**`** 里的离线镜像文件后请加 `--rebuild`（按既定决定不做触发）。→ `BUG-002`
- `pages/` 之外的**全新**文件（新 `.extends`/新 div/新 global）会触发一次全量（之后恢复正常）。



**算法**
1. **串行预热**：解析全部 div 继承链（`effectiveDivs` 填满 → 渲染期只读，消掉惰性 memo 的竞争）；
2. **并行渲染**：每页一个任务 + 一个 `PageScope`（页内私有状态）+ 一对 `SitePages`/`SiteTags` 实例；
   线程数沿用 `.env` 的 `threads` / `--threads`：`auto` = `min(8, 核数−1)`，再按页数收敛
   （每 16 页才多开一个线程 → **11 页的小站自动走串行**，不建池）；
3. **收尾合并**：跨页累加器（写队列、输出路径集、预设引用集、分片目录表、警告/错误、计数）全部换成
   **线程安全容器**，且它们的**顺序不影响产物字节**（每条产物路径只有一个生产者；manifest 的键在落盘时排序）
   —— 因此不需要按页序重排，全局聚合与写盘相完全不动。

**为什么按页切、而不是按 html/js/css 分线程**：页内三件产物共用同一次 div 解析与 md 渲染，
拆开会让同一页被解析三遍；而实测聚合仅 ~60 ms、md ~70 ms，真正的成本是"每页固定开销 + 模板/标签/组装"（约 1.88 s）。

**时间复杂度**（P 页、N 线程、c = 每页固定成本、S = schema 字节数）

| 阶段 | 改前 | 改后 |
|---|---|---|
| 页面渲染 | O(P·c) | **O(P·c/N) + O(P)**（热身与合并各 O(P) 级） |
| schema 获取 | 每页 O(S) 读 + O(S) 哈希 | **O(1)**（volatile 读一次） |
| 产物写盘 | O(Outputs/N) | 不变（0.3.3 起已并行） |

**实测**（300 页合成站；本机 4 核 → auto = 3 线程）：页面相 **2182 ms → 634 ms（3.4×）**；
整构建 **6.6 s → 3.7 s（−44%）**。11 页的示例站不触发并行，耗时不变。

### 新增：可选压缩引擎 Closure（`minifier=closure`，**需自带 jar**）

- `sets/Environment.config` 里写 `minifier=closure` 即切换；**默认仍是 `simple`**
  （实测：Closure `SIMPLE_OPTIMIZATIONS` 相对内置 simple 再省 **11.9% 字节、但 gzip 只再省 4.2%**；耗时 60–90ms/唯一 bundle）。
- 生成器**不打包** Closure（保持零依赖）：把 `closure-compiler-v<日期>.jar` 放进 classpath（或 fat jar 同目录的 `lib/`）即可用；
  **没有它时自动回退 simple 并给一条警告**（不静默降级、也不会构建失败）。
- 只提供 `SIMPLE_OPTIMIZATIONS`（`WHITESPACE_ONLY` 实测**不如**内置 simple，故不作推荐；`ADVANCED` 需要 externs → 见 `tech.md` 待办）；
  `language_out` 固定 `ECMASCRIPT_NEXT`（不做 ES5 降级转译）；许可头保留；换 jar 版本会被记进依赖记录的 `config` 指纹。
- 非破坏性：不写 `minifier` 时行为与之前**逐字节相同**（双参照 diff 0 行）。

### 行为变化：`palette-picker` 的全量主题 css 不再"泄漏"到其它页面

- 旧实现里 `themePickerNeeded` 是**构建级共享字段、且每页不清零**：只要有一页（如 INDEX）用了 `palette-picker`，
  **其后渲染的每一页**都会多带 3 个 `<link>`（`md-code-dark/green/sepia.css`）。
- 现在它随 `PageScope` 按页私有 → 只有**真正含 picker 的页面**才全量链接主题 css（与文档原意一致，每页少 3 个请求）。
- 迁移写法：某页若需要"运行时可切全部主题"，给它加一个 palette-picker div，或用 `deps` 自行链接
  `pre-assets/md/css/md-code-<主题>.css`。
- 影响面：示例站 3 个文件各少 3 行（blog-list / callout-demo / strict-demo）；300 页合成站 0 差异。

### 行为变化：搜索索引的条目顺序改为**页面名字典序**

- 旧实现用 `HashSet` 装同目录下的 md/json 文件名 → 页面注册顺序 = **字符串哈希迭代序**（内容相同则稳定，但本质是隐式的）。
- 现在改为**按名排序**注册（同时扫描相也并行了，但顺序仍是确定性的）。
- 影响：`assets/data/search-index.json` 的**条目集合不变、顺序变**；`ssvul:list` 的 `build:page-index` 列表**不受影响**
  （它自己按日期倒序 + 同标题码点序排序）。这是可复现性改进，无需迁移。

### 新增：仓库外运行 jar 时**自动解包内置预设**（打包拼图补齐）

- 以前 `--assets` 默认是 `src/assets` —— 那是**仓库内**路径，所以 `java -jar ssvul-x.y.z.jar build` 在仓库外必然找不到预设（BASE.html/divs/md/lib 全缺）。
- 现在解析顺序是：`--assets` 显式给 → 仓库内 `src/assets`（开发期行为**一字不变**）→ **解包 jar 内 `assets/**` 到 `<项目根>/.ssvul/assets-<生成器版本>/`**
  （项目根 = `output/` 的父级，与 `.env` 同级）。首次几十 ms，之后带 `.complete` 标记复用；升级生成器自动用新目录；`--assets` 仍可覆盖。
- 实测（手工组装的 fat jar、一个**没有 `src/assets`** 的干净目录）：`java -jar … build` 直接产出 120 个文件，
  与"仓库内运行"的产物 **`diff -rq` = 0 行**；第二次运行幂等复用并走增量秒回。

### 其它


- `schema()` 热路径不再"每页读一遍 `page.schema.json` + 算 SHA-256"（实测 300 页白付 **1647 ms**）。
  **代价：改了 `page.schema.json` 需要重启构建/预览**（每条构建命令本来就是新 JVM）。
- 新增 `[页面细分]` 归因行（json / md / 聚合 / 其余）；并行时它是**线程累计**，可能大于墙钟，属正常。
- 并行下压缩/读盘可能各多算几次（多个线程同时发现同一聚合，各自压了一次）——结果相同，只是多花一点 CPU。

## 0.3.4（2026-09）

> 本版**没有破坏性变更**：既有站点源、产物路径与**产物字节**都不变（注入只改 HTTP 响应；双参照 diff 仍 0 行）。

### 新增：预览会**自动重建**，页面**自动刷新**

- `ssvul preview` 现在默认 `watch=auto`：**有浏览器连着才轮询**源目录，检出变更就重建，并通过 SSE 把结果推给页面（注入的 `client.js` 收到 `built` 后整页刷新）。没人开页面 = 不轮询、零开销。
- 检测判据与构建**同一套**（有 git 仓库用 `git status`；否则 stat 快筛 + **内容哈希终判**）→ 纯 `touch` 不误报。
- 频率：`poll`（默认 700ms，下限）+ 自适应退避（至少 4×检测耗时，空闲逐步退到 2s，有变更立刻回到 `poll`）+ 250ms 去抖；**构建期间不轮询**，连写只建一次。
- 失败语义不变：**构建失败 → 页面显示错误覆盖层、保持上一次成功的产物**，不刷新成半成品。

### 新增：`/__ssvul/` 保留路径（只存在于本机预览服务）

- `/__ssvul/events`（SSE 事件流）、`/__ssvul/client.js`（注入脚本），**其余 `/__ssvul/*` 一律 403**。
- 站点里若有同名目录请改名：它属于预览设施，不会被产物覆盖，但会被这个保留前缀挡住。
- 预览另外校验 `Host`（只放行 `127.0.0.1`/`localhost`），防 DNS rebinding。

### 新增：`.env` 四个键 + CLI 四个开关

| `.env` 键 | 默认 | CLI | 作用 |
|---|---|---|---|
| `watch` | auto | `--watch auto\|1\|0` | 自动重建：auto=有人在看才轮询 / 1=常开 / 0=关 |
| `poll` | 700 | `--poll <ms>` | 轮询间隔下限（50–60000） |
| `inject` | 1 | `--inject 0\|1` | 是否给 HTML 响应注入预览客户端脚本 |
| `sse-max` | 16 | `--sse-max <n>` | SSE 连接上限（1–256） |
| `open` | 1 | `--open 0\|1` | `preview` 启动后自动打开浏览器（按环境探测启动器；探测不到只打印 URL） |

优先级仍是 **CLI > `.env` > 内置默认**。**升级提示**：老 `.env` 缺这些键时，生成器会**在我们自己生成的文件末尾追加**默认值（已有行一个字节不改；你手写的 `.env` 不会被触碰）——不需要额外迁移动作。

### 新增：孤儿产物警告

源被删除/改名后，上次写过、这次不再生成的产物会触发一条构建警告（`有 N 个产物已不再生成…`）。**自动清理仍未实现**（后续版本），但预览不再"默默服务旧文件"。

### 补记：构建开关 `--threads`（0.3.3 已引入）

`--threads auto|0|N` 覆盖写盘并发度（`auto` = `min(8, 核数−1)`，留一个核）。

## 0.3.3（2026-09）

> 本版**没有破坏性变更**：既有站点、既有配置键、产物路径与**产物字节**都不变（示例站与 300 页参照产物逐文件 0 差异）。
> 下面是新增能力与两处**行为变化**（都不需要改站点源，但会影响你的工具链预期）。

### 新增：本机设置文件 `.env` 与构建状态 `.ssvul/deps.json`

- 首次构建在**项目根**（`output/` 的父级）生成 `.env`（7 键，见 README「本机设置」），此后**只读**：生成器绝不覆盖它，缺键用内置默认，未知键/非法值只警告。
- 同时生成 `.ssvul/deps-<站点号>.json`（构建状态：源与产物的 size/mtime、产物 sha256；站点号 = sets/output 绝对路径的哈希，**每个站点一份**）。**它不是产物**，不会进 `output/`，别手改（下次构建会覆盖）。
- 建议把 `.env` 与 `.ssvul/` 加进你自己仓库的 `.gitignore`（生成器仓库已加）。
- 需要的动作：无。不建 `.env` 就是全部走内置默认。

### 行为变化 1：标准布局下首建会在 `sets/` 建一个 git 仓库

- 触发条件：**默认 `sets/`**（没写 `--sets`）且本机有 git 且 `sets/.git` 不存在。
- 动作：`git init` + 写 `sets/.gitignore`（缺失时）+ `git add -A` 建立基线，**不提交**（不依赖你的 `user.name`/`user.email`）。
- 为什么：有仓库时变更检测用 `git status`（300 文件约 340ms，比 stat 遍历约 530ms 更快，且**纯 touch 不会误报**）。
- 不想要：加 `--git-init 0`；已有自己仓库的 `sets/` 完全不受影响（不 init、不 commit、不动 HEAD/分支/暂存区）。
- 迁移写法：无需迁移。若你的部署脚本假设"`sets/` 不是仓库"，请改脚本或关掉本行为。

### 行为变化 2：二次构建不再重写"内容没变"的产物

- 现在：内容与上次**逐字节相同**且磁盘 size/mtime 未被外部改动 → **跳过写盘**（产物内容保证一致，只是 mtime 不再每次构建都刷新）。
- 影响：靠 mtime 判断"哪些文件要上传"的部署脚本会认为文件没更新（这通常正是想要的效果；若你依赖 mtime 变化，请改用内容哈希或加 `--rebuild`）。
- 要强制全量重写：`--rebuild`（忽略 `.ssvul/deps.json`，每个产物都重写）。

### 新增：`preview` 子命令可用（本地静态服务）

- `ssvul preview`（或 `-p`）→ `http://127.0.0.1:23143/`；**只绑本机回环**，不对外暴露；`--port` 覆盖端口。
- 行为：启动时先构建一次（**构建失败也照常起服务**，页面会显示错误原因而不是装作 404）；只服务 `output/` 内的文件（目录自动回落 `index.html`；越界路径 403；`Cache-Control: no-store`）。
- 本版**不做**：文件监听自动重建、SSE 自动刷新、dev-panel（计划在 v4；接口 `SourceWatcher`/`ReloadNotifier` 已留好）。

### 新增：CLI 开关（都可裸写，即 `--rebuild` ≡ `--rebuild 1`）

`--rebuild` / `--detect` / `--git` / `--no-git` / `--git-init 0|1` / `--port n`。
其余键仍必须带值（缺值会报错并给退出码 2，这条从 0.3.0 起就没变）。

## 0.3.0-alpha2（2026-09）

### `shower` → `list`：列表组件重做对外契约

**1) div 类型改名**
- `{"type": "shower"}` → `{"type": "list"}`。旧名字不再被识别（构建报"div 类型不存在"）。
- 预设目录 `src/assets/divs/shower/` → `src/assets/divs/list/`；类名 `.shower-*` → `.list-*`（`.list-item/.list-date/.list-title/.list-tags/.list-tag/.list-excerpt`）。

**2) 新增 `src`：一个参数决定"数据从哪来 + 在哪渲染"**

| `src` 取值 | 行为 | 对应旧写法 |
|---|---|---|
| `build:page-index`（**不写 src 时的默认**） | 构建期直接产出静态条目（`dir`/`pattern` 命中的全部页面，日期倒序），**本页不注入 list 的 js** | 新增（旧版没有零 JS 列表） |
| `ssvul:inline` | 构建期把条目内联进页面（`.list-data`）+ 客户端预设渲染 | 旧的 `shared: false`（默认） |
| `ssvul:shared` | 构建期按目录发射分片 `assets/data/list/<dir>.json` + 客户端预设 fetch | 旧的 `shared: true` |
| `<你的函数名>`（裸名） | 构建期只写 `data-src`；你的外接函数 `function (root, params)` 返回 `{items:[…]}` | 新增（用于"只上传文件、不重建"） |

**3) 被移除的处理参数**：`order`、`seed`、`count`、`offset`、`tags`、`match`、`shared`、`manual` **一律不再生效**。
它们不会报错（`params` 是自由透传的），会被原样传给对接函数——所以**写了也没用**，请按下表迁移：

| 旧需求 | 旧写法 | 新写法 |
|---|---|---|
| 最新 N 篇 | `count: 5` + `order: "date"` | 写自己的对接函数（`src: 你的函数名`）里截断；或用 `build:page-index` 列全部 |
| 按标题排序 | `order: "name"` | 同上（构建期固定为日期倒序；要别的顺序就自己写函数） |
| 随机推荐 | `order: "random"` + `seed` | 同上 |
| 按标签筛选 | `tags`（旧版本来也没生效——页面 json 当时没有 `tags` 键） | 同上 |
| 手动分页 | `count` + `offset` | 同上（或把内容拆到多个目录，用 `dir` 各自列一份） |
| 内置渲染让位 | `manual: true` | 不需要：`src` 指向你的函数时，渲染本来就由你负责 |

**构建期固定约定（不可配置）**：取 `dir` + `pattern` 命中的**全部**页面（自动跳过 INDEX 与列表页自身）→ **日期倒序**（同日期按标题码点序，无日期最后）→ **不截断**。

**4) 条目形状**：`{link, type, title, date, excerpt, tags}`（内联与分片完全同形）。
- **不再携带 `text` 全文**（旧内联数据带全文，实测占 39% 字节而渲染用不到）。
- 标题与摘要里的行内标记（`**粗**`、链接、图片）在**索引阶段就净化成纯文本**，列表与搜索摘要共用。

**5) 渲染接管方式变更**：全局覆写 `window.SsvulShower.render / sort / after` **已移除**。
- 渲染 → 由你的对接函数返回 `{items: [DOM 节点或 HTML 字符串, …]}`（可复用 `window.SsvulList.item(entry, fields, prefix)` 得到内置标记）。
- 收尾（条目进 DOM 之后）→ 监听容器上的 **`ssvullist`** 事件。

**6) `fields` 语义**：作为上下文透传给对接函数；内置来源按它决定显示哪些字段，**渲染顺序固定** `date → title → tags → excerpt`（默认 `date,title,excerpt`）。

**7) 路径与保留名改名**
- 保留目录：`sets/data/shower/` → **`sets/data/list/`**（占名报错）。
- 分片输出：`assets/data/shower/<dir>.json` → **`assets/data/list/<dir>.json`**（`dir` 空 → `index.json`）。
- 内联数据脚本类名：`shower-data` → **`list-data`**；列表容器类名：`shower-list` → **`list-items`**。
- 模板占位符：列表条目 = **`{{items}}`**（`divs/list/template.html`）。

**8) 预设函数（构建期按名自动复制 + 注入，作者无需手写 `<script>`）**
- 资源位于 `src/assets/div-libs/list/*.js`，按需复制到 `output/assets/pre/div-libs/list/*.js`（沿用 `pre-assets/` 既有机制）。
- 目前两个：`ssvul:inline`、`ssvul:shared`。名单扩充（例如目录清单、对象存储列表）**留待后续版本**，届时也记在这里。

## 0.3.1（2026-09）

### Environment.config 的 `bucket` 改成方括号字段列表

**1) 新写法**：`bucket=[调用名,href,EndPoint,...]`——仍然**可多次输入**（即配置多个 bucket），一行一个：

```ini
bucket=[bk,https://bucket.example.com]
bucket=[img,https://img.example.com,https://s3.example.com]
```

- 第 1 段 **调用名**：非空、不含 `/` 与空格（它同时是内容里的引用前缀）；
- 第 2 段 **href**：必须带协议（`http(s)://`），末尾 `/` 会自动去掉；
- 第 3 段起是**预留字段**：第 3 段约定为 **EndPoint**（供将来"按目录列文件"的来源使用），更多段原样保留给后续版本；**字段内不能含逗号**。

**2) 旧写法不再接受**：`(调用名,URL)` 与裸 `调用名,URL` 一律**报错**并提示新格式（不再有兼容分支）。

**3) 页面 json 与内容完全不受影响**：以前写好的 json 原样可用——
内容里依旧写 `调用名/路径`（如 `bk/img/logo.png`）、`favicon: "bk/favicon.ico"`、`code-ui.bg`、md 里的图片链接等；
替换规则与 `offline=1` 的 outer 镜像行为**一个字都没改**。

**4) 迁移**：把 `bucket=(bk,https://x)` 改成 `bucket=[bk,https://x]`。
`init` 骨架（`src/main/resources/templates/site/Environment.config`）与示例站已同步。

### 同版本的非破坏性变化（备忘，不涉及迁移）

- **新增预设 `ssvul:json`**：`list` 的**纯 CSR** 数据来源——客户端 fetch 一份你自己维护的 JSON（构建期不生成任何数据），适合"只上传文件就更新"。约定参数：`file`（必填，JSON 路径）、`fields`、`empty`。
- **嵌套上限统一放宽到 8 层**：引用/callout 由 2 → 8；列表由「simple 软 4/硬 6、strict 2」→ 8（`MdBlocks.NEST_LIMIT`，两处共用）。simple 超限只提示一次并继续渲染，strict 超限报错。
- **嵌套 callout 去底色**（`.md-callout` 内层 `background: none`，保留左边框与图标）；**嵌套列表缩进收敛**（`ul ul` 等改为 `padding-left: 1.2em`）——都是深度放宽后的排版收敛。
- 版本号：`0.3.0-alpha2` → **`0.3.1`**（`build.gradle`；`init` 骨架写入的版本随之变化）。

## 0.3.2（2026-09）

### `bucket` 新增 `键=值` 属性｜新增站点键 `session-ttl`｜新增预设 `ssvul:s3`

**本批全部是向后兼容的新增**：不写新键、不用新预设的站点，产物不变（**无需迁移**）。

**1) `bucket` 第 3 段起改为 `键=值` 属性**

```ini
bucket=[bk,https://cdn.example.com,endpoint=https://s3.us-east-1.amazonaws.com/my-bucket,prefix=site/blog]
```

- 第 2 段 `href` 的语义明确为**公开访问基址**：继续负责 `调用名/…` 替换与 `offline=1` 的 outer 镜像，并新增"拼列表条目 link"一职；
- 可用属性：`endpoint=`（列目录 API 基址，**不含 query**；S3 的 bucket 写在 path 或 host 均可）、`prefix=`（**存储侧根前缀**，不带前导/末尾斜线、禁空段与 `..`）、`ref=`（预留，GitHub 来源用）；
- **兼容**：第 3 段**不带 `=`** 时仍按旧约定当 `EndPoint`（等价 `endpoint=值`）——`bucket=[bk,https://x]` 与 `bucket=[bk,https://x,https://s3…]` 行为不变；
- **未知属性报错**（`kind=` 之类明确不做：预设名即协议）；属性重复、值为空、endpoint 缺协议/带 query、prefix 含空段或 `..` 都报错。

**2) 新增站点键 `session-ttl`**：列目录型列表的**会话缓存时长（秒）**，默认 `86400`（24h），`0` = 默认不缓存。
缓存存放在浏览器 `sessionStorage`（**不跨会话、不跨标签**，随标签页存活 → 页面挂机不会反复请求）；单个列表可用 `params.cache` 覆盖（`0` = 本次既不读也不写）。**不写此键 = 用默认值**。

**3) 新增预设 `ssvul:s3`（纯 CSR 列目录，S3 兼容协议：R2/OSS/MinIO/COS）**

```jsonc
{ "type": "list", "params": { "src": "ssvul:s3", "dir": "", "pattern": "*.html", "fields": "date,title" } }
```

- 构建期**不生成任何数据**，只把桶信息解析成 `data-bk-*`（`bk-name`/`bk-endpoint`/`bk-href`/`bk-prefix`/`bk-ttl`）；浏览器实时请求 `GET {endpoint}?list-type=2&prefix=…&delimiter=/&max-keys=…`；
- **只列当前层、不递归**（等价于真实 S3 的 `<CommonPrefixes>` 不返回）；
- **`bk-` 是构建期注入的保留前缀**：手写 `params` 里以 `bk-` 开头的键会**报错**；
- 条目：`link` = **绝对 URL**（`href` + 键去掉存储前缀），**不再叠加 `data-depth` 前缀**；`type` = 扩展名；`title` = 文件名去扩展名、`-`/`_`→空格；`date` = `LastModified` 的 UTC 日期；`excerpt`/`tags` 为空；
- **排序**：按与构建期**同一条规则**（日期倒序 → 同日标题码点序 → 无日期最后）；
- **此处的 `pattern` 是"键名 glob"**（如 `*.html`/`*.pdf`），与构建期来源那个作用于**页面类型**（`*`/`*.md`/`*.json`）的 `pattern` **不是同一套语义**；
- 参数：`bucket`（选桶；只有一个桶可省，多个必须指定）/ `dir` / `pattern` / `max`（默认 1000=S3 上限）/ `pages`（默认 3，请求规模安全阀）/ `cache` / `fields` / `empty`；
- 前置条件：桶允许**匿名 ListBucket + 读对象**，并配好 **CORS**；`file://` 不可用；`offline=1` 的站点会得到一条"运行时需要联网"的**构建警告**（每页最多一条，属预期可见化而非错误）。注意：该页产物里会出现 `data-bk-href`/`data-bk-endpoint` 两个**绝对 URL**（运行时列目录与点开所必需）——`offline=1` 的"未命中镜像"检查只针对 `调用名/…` 引用，不受影响。

**4) 预设资产路径调整（产物路径变化）**：div 专属库从 `src/assets/<库名>/` 移入 **`src/assets/div-libs/<div 名>/`** —— `list` 的官方对接函数与 `md-csr` 的客户端渲染库都在此列，产物路径随之变为 **`assets/pre/div-libs/list/*.js`**、**`assets/pre/div-libs/md-csr/*.js`**。
按名引用（`src: "ssvul:json"` 等）与自动注入**完全不受影响**；只有直接手写 `pre-assets/list/…` 或按产物路径配过 `_headers`/缓存规则的站点需要补上 `div-libs/`。

**5) 调用名保留字（新增校验）**：`bucket` 调用名不得为 **`assets`** 或 **`pre-assets`** —— 与产物目录同名会让替换趟把 `assets/…` 产物路径误当桶引用替换（实测一页 14 处）。这两个名字此前的行为本就是坏的（只是不报错），现在构建期直接**报错**。

**5) 新增 div `md-csr`（客户端渲染 markdown）**：`src/assets/divs/md-csr/`（薄壳）+ `src/assets/div-libs/md-csr/*.js`（10 个文件的渲染库，与 `Integra/MarkdownIntergra/` 一一对应）。取数三形态（桶内键 / `file` / `?p=` 查询参数）；渲染选项与构建期 md-options 同名（`raw-html` 默认 **off**＝转义原生 HTML、`link-policy` 默认 **relaxed**＝放行相对路径但拦危险协议——这是与构建期**有意**的两处差异，已由金标差分测试台钉住）；`math=on` 才注入 KaTeX（重资产按需）。渲染完成派发 `ssvulmd` 事件。**纯新增，不影响既有站点**。

**6) `ssvul:s3` 新增 `link` 模板参数**：如 `"link": "./?p={key}"`（`{key}`=桶内键、`{path}`=去掉存储前缀的相对路径）——让桶列表条目指向你自己的 reader 页，而不是直链到桶里的原始 md。不写 = 与之前完全一致（`href` + 键）。

**7) 版本号**：`0.3.1` → **`0.3.2`**（`build.gradle` 已同步；本批提交为 `522ae62 v0.3.2 -md CSR and S3 support`）。
`example-sets/` 增加 `pages/blog-s3.json`（列目录演示，含 `session-ttl=600` 与 `params.cache` 覆盖示例）；该页在示例站 `offline=1` 下会刻意产生一条构建警告。
