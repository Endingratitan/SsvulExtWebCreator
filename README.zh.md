# SsvulExtWebCreator
## 项目目标

项目将牺牲产出物的可读性，但是编译材料是方便维护阅读的。

- 依靠json配置文件，组装html，js，css等文件，自动生成网络文件  
- md格式转html，并个性化可配置文件
- 其他

## 使用

- 构建：`java -jar ssvul-<版本>.jar build`（默认读取 sets/，输出 output/；或 `-b`）。**预设资产不用你操心**：仓库内运行时直接用 `src/assets`；**从 jar 运行时自动解包内置预设到 `<项目根>/.ssvul/assets-<版本>/`**（首次几十 ms，之后复用；升级生成器会自动换新目录；`--assets <目录>` 可覆盖）
- 本地预览：`java -jar ssvul-<版本>.jar preview`（默认 `http://127.0.0.1:23143/`，**只绑本机回环**；端口取 `.env` 的 `preview-port`，可用 `--port` 覆盖；或 `-p`）
- 初始化站点：`java -jar ssvul-<版本>.jar init [dir]`（默认生成 site/ 骨架，含正确 .gitignore）
- 子命令：`build|-b`（构建）、`init|-i`（骨架）、`preview|-p`（本地预览）、`version|-v`（版本）
- 构建开关（都可裸写，排错用）：`--rebuild`（**强制全量渲染**，忽略增量记录）、`--detect`（构建后额外打印变更清单；**检测本身每次构建都跑**，见下"增量跳过"）、`--verify 0|1`（产物校验：`1`=默认，逐个比对记录，产物被删/被改时**只重渲染它的归属页**把它修回；`0`=不校验，最快）、`--git` / `--no-git`（强制变更检测器）、`--git-init 0|1`（是否允许在 `sets/` 建 git 基线）、`--threads auto|0|N`（I/O 并发度）
- 开发运行：`.\gradlew run`（Gradle；版本号注入由 gradle 构建完成）

## 构建复杂度与性能

### 一次构建做什么（复杂度）

| 阶段 | 复杂度 | 说明 |
|---|---|---|
| 扫描 | O(源文件数) | div 索引 + `.extends` 链解析（memo）+ 页面注册 + 页面索引；目录遍历**排序后**处理（可复现性前提） |
| 页面渲染 | O(**变更**页数 × 每页工作量) | **增量渲染**（0.4.0）：依赖未变的页**整体跳过**；只有变了的页才从零解析 md、逐页替换 div 模板 |
| 全局聚合 | O(唯聚合数 × 内容长度) | origin 集去重 → 文件级内容去重 → JsDeduper/CssDeduper → 压缩 |
| 写盘 | O(写集大小) | 内容未变的产物**跳过写盘**（写集仍完整记录，供孤儿清理/增量用）；被跳过页的产物直接**继承写集**，因此孤儿检测与下次增量不会失真 |
| 变更检测 | O(源文件数)（**单趟**、按条目并行） | **每次构建都跑**（这是增量的输入）：有 git 仓库用 `git status`，否则 stat 快筛 + **内容哈希终判**；300 个源文件实测 ~50ms |

### 0.3.3 优化清单（机制）

| 项 | 优化前 | 优化后 | 代号 |
|---|---|---|---|
| 页面 schema 编译 | 每页 1 次（N 页 = N 次） | **每进程 1 次**（按 schema 内容哈希缓存） | P1 |
| 内容读取 | 每个引用点各读一次盘 | 同一文件**首次读即入内存**（单文件 ≤512KiB；总量默认 8MiB，构建末释放） | P3 |
| JS/CSS 压缩 | 每个聚合压一次 | 同内容**只压一次**（键 = 档位\|引擎\|内容哈希） | P4 |
| 二次构建写盘 | 全部重写 | **零写盘**（内容哈希 + 磁盘 size/mtime 全中 → O(1) 跳过；不符才读文件逐字节比较） | P5 |
| 二次构建渲染（0.4.0） | 全部重渲染 | **零渲染**：依赖未变 + 本页产物完好 → 整页跳过；一个源都没变时连扫描/写盘都不做（见下"增量跳过"） | E |
| md 热路径正则 | 每行现编译/`replaceAll` | **正则预编译** + 字符扫描 | P6 |
| 引擎链 | 静态字段 + 每页 set（非线程安全） | 沿参数传递（为 watch/并行铺路） | R10 |
| `page.schema.json` | 依赖当前工作目录 | classpath 优先、CWD 兜底（jar 里也带一份） | R11 |

**实测（本机 WSL + `/mnt/d`，4 核 → `threads=auto` 即 3 线程）**：example-sets（11 页 / 120 产物）冷 **2.2–2.4s** / 热（无变更）**1.2–1.3s**；300 页合成站 冷 **2.7–2.9s** / 热 **1.35–1.5s**。热构建里 JVM 启动与首次解析约占 0.4–0.7s，**构建相本身约 0.5s**（其中检测 0.05–0.12s、产物校验 0.03–0.07s）。

### 0.4.0 增量跳过（E）

构建前先检测"哪些源变了"，然后**只重渲染受影响的那几页**；若一个源都没变，则**不扫描、不渲染、不写盘**，只把产物逐个 stat 一遍（称"秒回"）。

- **判据可证等价，不是启发式**：每页记下它**实际读过/引用过**的源键与它**产出**的产物 rel；下次只要"这些源一个都没变 + 本页产物都还在"，该页就跳过。跳过时会**补齐副作用**：继承写集、重放预设引用、注册 `@page/` 目标、恢复 search/list 分片标志。
- **没有页归属的读取**（`.extends`/`.contract`/`Environment.config`/`sets/global/**`/未被任何页面引用的 `sets/data/**`）归入**站点级依赖集**：它们一变就整站重渲染（无法精确归因，只此一处保守）。
- **配置指纹** = 生成器版本号 + `Environment.config`：改配置或**升级生成器**都会整站重渲染（避免"升级后产物停在旧逻辑"）。
- **产物自愈**：产物被删或被手改 → 只重渲染它的**归属页**并修回（与旧版行为一致）；`--verify 0` 可关掉这步换速度。
- **预设侧**：被页面**按名引用/读过**的预设（`page/BASE.html`、预设 div 模板、主题 css、list/md-csr 库…）改了也能检出，且只重渲染引用它的页；只有**传递性 vendor 文件**（如 KaTeX 的 60 个字体）需要 `--rebuild`（见 `docs/bugs/BUG-001`）。
- **已知边界**：`pages/` 之外的**全新**文件（新 `.extends`/新 div/新 global）会触发一次整站重渲染；直接改 `sets/outer/**` 里的离线镜像后建议 `--rebuild`（见 `docs/bugs/BUG-002`）。

### 本机设置：`.env`

项目根（= `output/` 的父级）放一个 `.env`：**首次构建自动生成**，此后**只读**（生成器绝不覆盖，删掉即恢复默认）。它只回答"**这台机器怎么跑生成器**"；**网站契约仍写在 `sets/Environment.config`**，两者不混。

| 键 | 默认 | 作用 |
|---|---|---|
| `env-version` | 1 | 本文件格式版本 |
| `git` | 探测结果 | git 能力位（0/1）；`0` = 不使用 git 检测器 |
| `detector` | auto | `auto`（有仓库用 git，否则 stat）/ `git` / `stat` |
| `git-gc` | **2** | `sets/.git` 整理：`0`=关、`1`=`git gc --quiet`、`2`=只保留本次+上次两代快照（省空间；会立刻清掉你自己产生的悬空对象） |
| `cache-limit` | 8 | 单次构建内容缓存上限（MiB）；`0` = 关 |
| `preview-port` | 23143 | 预览端口（>20000 的质数；`0` = 随机） |
| `threads` | auto | 写盘相 I/O 并发度：`auto`（`min(8, 核数−1)`——**留一个核**给别的服务，再按产物数量收敛；小站自动串行）/ `0` = 强制串行 / `N` = 指定（1–256） |
| `watch` | auto | 预览是否**自动重建**：`auto`（**有浏览器连着才轮询**，没人看零开销）/ `1` = 常开 / `0` = 关（只手动重建） |
| `poll` | 700 | 轮询间隔（毫秒，下限）。实际**自适应退避**：至少 4×上次检测耗时，连续无变更逐步退到 2s，一有变更立刻回到 `poll` |
| `inject` | 1 | 是否给 HTML 响应注入预览客户端脚本（**只改响应，磁盘产物零变化**）；`0` 可看"真实产物" |
| `sse-max` | 16 | 同时在线的 SSE 连接上限（独立线程池；超限 `503 + Retry-After`，静态请求永不被长连接饿死） |
| `open` | 1 | `preview` 启动后**自动打开浏览器**。启动器按环境探测：**纯 Windows** = `cmd.exe /c start`（备选 `rundll32`、`powershell`）；**WSL** = `wslview` → `cmd.exe` 互操作 → `powershell` → `explorer.exe`；macOS = `open`；Linux 桌面 = `xdg-open`。**探测不到就只打印 URL**，不报错 |

优先级：**命令行 > `.env` > 内置默认**；未知键与非法值**只警告**，不阻断构建。
升级后若是**生成器自己创建的** `.env` 缺了新键，会在**文件末尾追加**默认值（已有行一个字节不改；键齐了根本不写；用户手写的 `.env` 一碰不碰）。

### 并行度为什么这样定

写盘相（判定 + 比较 + 落盘）是纯 I/O、彼此独立，实测（`/mnt/d` 9p，302 个文件）并发几乎线性：

| 操作 | 1 线程 | 2 线程 | 4 线程 | 8 线程 |
|---|---|---|---|---|
| stat | 462 ms | 93 ms | 44 ms | 32 ms |
| read | 505 ms | 117 ms | 58 ms | 40 ms |
| write | 448 ms | 136 ms | 81 ms | 69 ms |

所以默认 `auto` = **`min(8, 核数 − 1)`**（**留一个核**给宿主/其他服务，8 是实测收益拐点：8 之后只剩 ~1.4×），再按工作量收敛（每 16 个产物才多开一个线程）——12 页的小站因此**自动走串行**，不为几个文件建线程池。要调就写 `.env` 的 `threads`，或命令行 `--threads 0|N`（`0` 用于得到确定性的串行现场，方便排错与 A/B）。

> 注：2 核机器 auto → 1（串行），要用满请显式 `threads=2`。

**效果（300 页合成站，写盘相）**：冷构建 1317 → **308 ms**，热构建 357 → **75 ms**；产物逐字节不变（有专门的"并行 = 串行字节一致"测试与双参照 diff 把关）。

### 本地预览：自动重建 + 页面自动刷新

```bash
ssvul preview                          # 默认 http://127.0.0.1:23143/，并自动打开浏览器
ssvul preview --watch 1 --poll 300     # 常开监听、300ms 轮询
ssvul preview --open 0                 # 不自动开浏览器（CI/无人环境）
ssvul preview --inject 0               # 不注入客户端脚本（看"真实产物"）
```

链路：**源变了 → 轮询检出 → 重建 → SSE 推送 → 页面自动刷新**。

- **启动后自动打开浏览器**（`open`，默认开）：纯 Windows 用 `cmd.exe /c start`；WSL 走 `wslview` 或 `cmd.exe` 互操作；macOS `open`；Linux 桌面 `xdg-open`。探测不到只打印 URL（不算错误），`--open 0` 可关。
- 监听是**轮询**（本机 9p 上 inotify 完全无事件）：判据与构建**同一套**（有 git 仓库用 `git status`，否则 stat 快筛 + **内容哈希终判**）→ 纯 `touch` 不误报、"同长度改写"也不漏判。检出的**重建是增量重建**（只重渲染受影响页；一个源都没变时"秒回"），所以常开监听的开销极小。
- **`watch=auto`（默认）没人开页面就完全不轮询**：9p 上一次检测约 1ms/文件，省下来的是实打实的资源。
- 去抖 250ms（编辑器保存是"临时文件 + rename"、格式化会连改多文件）+ **构建期间不轮询** + 连写只建一次。
- **`/__ssvul/` 是预览保留路径**：`/__ssvul/events`（SSE）、`/__ssvul/client.js`（注入的脚本），其余一律 403。
- **注入只发生在 HTTP 响应里**：磁盘上的 `output/` 一个字节都不变（双参照 diff 依旧 0 行）；遇到非 UTF-8 的 HTML 会主动放弃注入。
- 只绑 `127.0.0.1` 且校验 `Host`（防 DNS rebinding）；刷新时请求持读锁、重建持写锁 → 不会读到半个产物。
- **构建失败**：页面显示红色覆盖层与错误原因，**保持上一次成功的产物**，不白屏、不刷新成半成品。
- 源被删除/改名后旧产物的**自动清理**还没做（后续版本），但预览会给出警告——否则那些旧文件会一直被服务，看起来像"改了没生效"。增量构建下只有**被删/改名页**的旧产物会进这条警告，其余页照旧跳过。

### 生成的本机文件（都别入库）

| 文件 | 谁写 | 作用 |
|---|---|---|
| `.env` | 首次构建生成一次 | 本机设置（上表） |
| `.ssvul/deps-<站点号>.json` | 每次构建（原子写；**内容未变就不写**） | 依赖/状态记录（**v2**）：`sources`（源 size+mtime[+sha]）、`outputs`（产物 size+mtime+sha256）、`config`（配置指纹）、`pages`（每页的依赖键/产物/页级标志）、`shared`（无页归属依赖）。**它是增量跳过的依据**（二次构建零渲染零写盘），也是产物校验与将来孤儿清理的前提。**每个站点一份**（站点号 = sets 与 output 绝对路径的哈希），同一父目录下构建多个站点不会互相顶掉。**不在 `output/` 里**，不会被打包部署 |
| `sets/.git/`（可选） | 首次构建（标准布局 + git 可用时） | 站点源自己的仓库：**每次构建**的变更检测走 `git status`（300 文件 ~340ms；`stat` 检测器经 0.4.0 单趟并行后 ~50ms，且纯 `touch` 不误报）。构建**从未读过**的文件会被记入基线，因此不会每轮都被报成"已变更" |

**`sets/` 里的 git 仓库是你站点自己的**（生成器仓库的 `.gitignore` 已忽略 `sets/`）：首次构建只 `git init` + `git add -A` 建基线，**不会替你提交**；不需要就用 `--git-init 0` 关掉。

## 方案
### output 目录
output目录是产生的结果，内容为完整的0构建依赖网页项目文件
有
CNAME
index.html
assets/
pages/
favicon/ （可选）
README.md （可选）

assets下有js/,css/,data/和pre/目录（pre/为官方预设复制区，被引用时按需复制）

### sets 目录
sets目录下应该有以下目录/文件
Environment.config
divs/
pages/
data/
outer/
global/ （可选，全局域根：codeui/ 等，未来扩展 fonts/ 等）
favicon/ （可选）

#### Environment.config
用键值对存储环境变量，你只需要填你需要设置的键，不设置等同于留空值，等同于默认。
键允许重复输入
bucket键的值将以数组存储，允许多次输入；其他键以最后一次输入为准
有以下可用键

| 名            | 作用                                                             |
|---------------|------------------------------------------------------------------|
| cname         | 唯一必填项，缺失时程序报错；填0则不创建CNAME文件，认为你使用端口 |
| server        | 是否部署在服务器上，是为1，不是为0，默认0                        |
| bucket        | 外部数据源描述（**允许多次输入**=多个 bucket）。值格式 `[调用名,href,键=值...]`：第 1 段调用名；第 2 段 href=**公开访问基址**（页面内容里以 `调用名/...` 引用，如 bk/img/logo.png，生成时替换为 href；offline=1 命中 outer/ 镜像则本地替换）；第 3 段起为 `键=值` 属性——`endpoint=`（列目录 API 基址，`ssvul:s3` 用）、`prefix=`（存储侧根前缀）、`ref=`（预留）。**兼容**：第 3 段不带 `=` 时仍按旧约定当 endpoint。**旧写法 `(调用名,URL)` 不再接受**（见 [history.md](history.md)） |
| categories    | divs中是否需要一中间层目录作为categories，需要1，不要0，默认为0  |
| readme        | 会不会在output中添加readme等md文件，需要为1，不要为0，默认为0    |
| local-favicon | favicon目录是在项目中还是云端，0为项目，1为bucket，默认0         |
| offline       | 离线模式：1=是（bucket 引用命中 outer/ 镜像时本地替换进 assets/outer/，未命中报错）；0=否（默认，输出线上 URL；outer 目录存在时对未命中的引用发警告） |
| engine-words  | 词表扩展：`(语言,data/词表路径)` 可多条；为 simple 引擎追加关键字（行格式 `词` 或 `词:tokenid`，tokenid 见 token-map.js 的 tk 集合，缺省 kw）        |
| minify        | 优化档：**2=全开（默认**：去重+压缩）、1=去重不压缩、0=全关、-1=去重且被覆写代码以注释保留原位（调试对比）；档 ≥1 生成物用 `.min.js` 后缀        |
| minifier      | 压缩引擎：`simple`（默认，自编稳定实现——局部名改写+注释/空白压缩，含 eval/解构等自动降级护栏）／**`closure`（可选，需自带 jar）**：把 `closure-compiler-v<日期>.jar` 放进 classpath（或 fat jar 同目录的 `lib/`）即生效，**没放就自动回退 simple 并给一条警告**（不静默降级、不阻断构建）。只提供 `SIMPLE_OPTIMIZATIONS`；`language_out` 固定 `ECMASCRIPT_NEXT`（不做 ES5 降级转译）；许可头保留。**实测相对 simple 再省 11.9% 字节 / 4.2% gzip**，耗时约 60–90ms/唯一 bundle（`WHITESPACE_ONLY` 实测不如内置 simple；`ADVANCED` 需要 externs，列入计划）          |
| md-css        | md 主题默认值（站点级）：`default`=预设主题 `md/css/md.css`；`none`=不要预设 md 排版主题（站点自带设计系统时用）；或 `pre-assets/…`、`global/…` 指定主题文件。页面/div 的 `md.css` 可就近覆盖 |
| md-wrap       | md 结果是否用 `<div class="md-body">` 包住（站点级默认，`1`/缺省=包，`0`=不包）。页面/div 的 `md.wrap` 可就近覆盖 |
| session-ttl   | 会话缓存时长（秒），默认 86400（24h），0=默认不缓存。作用于列目录型列表（`ssvul:s3`）：列目录结果存浏览器 `sessionStorage`（**不跨会话/标签**，随标签页存活 → 挂机页面不反复请求），过期才重新请求；单个列表可用 `params.cache` 覆盖 |

#### divs/
divs里面可有子目录作为categories，所有子目录下再有单独的div类型目录；
或者直接每个子目录作为div（categories=0）。  
div下可有JS、CSS文件与template.html模板，不能有图片、json等数据文件，数据文件可从data/引入；图片必须从bucket导入。

**div 继承（.extends）**：div 目录放 `.extends` 文件（首非空行 = 父类型名，sets 或预设均可）——
- 模板：子有则覆盖父，无则继承最近祖先的；
- js/css：沿链**串联**（根→叶：父在前子在后——CSS 级联"子覆盖父"、JS 子后执行可覆写）；
- `.global`/`.adds` 标记：沿链**并集**；冲突时子可在 `.contract` 首非空行写 `override` 覆盖父标记（否则报错）；
- 护栏：继承环/链长>8/父类型不存在 → 报错。
- **JS 覆写消冗余**（构建期，词法判定）：同名**函数声明后到即胜**（父实现直接清除=覆写即替换）；`var x=…;` 紧邻恒等去重；顶层 `let/const/class` 跨文件重名 → 报错（串联后 SyntaxError）。
- **CSS 同选择器后到即胜**（构建期，CssDeduper）：规范化选择器相同 → **前驱规则整块删除**（省流量的既定特性，非等价变换）。因此子 div 用同名选择器时**必须重写全部所需声明**——父规则中未被覆盖的属性会一起消失（父 `.ssvul-x{color:red;margin:0}` + 子 `.ssvul-x{color:blue}` → 只剩 `color:blue`）；要保留父的声明就换选择器或写全。`@media`/`@keyframes` 等带块 at-rule 整块保守跳过；`@import`/`@charset` 等无块 at-rule 以 `;` 为界。
- **钩子契约**：div js 建议"只注册不执行"——`window.SsvulDiv.register(name,{init:fn})`（同名合并覆写）、`SsvulDiv.super(name,'init')`（子调父）、`initAll` 统一执行；`.contract` 其余非空行 = required 钩子，子置 null/缺失 → 构建警告（动态注册则通用警告）。

**内置 div（src/assets/divs，可 .extends 继承）**：
| div | params | 说明 |
|---|---|---|
| bar / navbar / topbar | brand | 导航家族：基类 bar（汉堡响应式）← navbar ← topbar（sticky+滚动阴影）；家族注册名=bar |
| search | placeholder、limit | 站内静态搜索（构建期生成 assets/data/search-index.json，路径按 div 的 data-depth 解析） |
| list | src、dir、pattern、fields（其余键原样透传给对接函数） | 列表组件：**数据来源与渲染时机全由 `src` 决定**——不写 = `build:page-index`（构建期直接出静态条目、**零 JS**）；`ssvul:inline`（构建期内联 + 客户端预设渲染，零请求/file:// 可用）；`ssvul:shared`（按目录分片 `assets/data/list/<dir>.json`，多页共用一份字节，fetch 依赖 http(s)）；`ssvul:json`（**纯 CSR**：客户端读你自己维护的 JSON 文件，构建期不生成任何数据——**只上传文件就更新**）；`ssvul:s3`（**纯 CSR 列目录**：构建期只把桶信息注入 `data-bk-*`，浏览器实时列桶目录（S3 兼容协议：R2/OSS/MinIO/COS），**构建一次后往桶里加/删/改文件即刷新可见**；需桶允许匿名 ListBucket 且配好 CORS，`file://` 不可用，`offline=1` 时构建期发一条警告；`link` 模板如 `./?p={key}` 可让条目指向你自己的 reader 页）；`<你的函数名>`（构建期只写 `data-src`，由你的外接函数返回 `{items:[…]}`） |
| md-csr | bucket、key、href、file、query、math（+ raw-html / link-policy / toc / footnote-display / callout-title / codeui.*） | **客户端渲染 markdown**：打开页面时 fetch 一个 md（桶内键 / 任意 URL / `?p=` 查询参数）并用与构建期**逐字节同构**的渲染器渲染 → 站点构建一次后，**上传 md + 刷新即渲染**。需要 fetch（`file://` 不可用）；默认 `raw-html=off`（转义原生 HTML，防存储型 XSS）；`math=on` 才注入 KaTeX（重资产按需）；完整说明见 [docs/UserWrite/md-csr-guide.md](docs/UserWrite/md-csr-guide.md) |
| breadcrumb | root-label、separator | 按 URL 路径生成面包屑（纯客户端） |
| backtotop | threshold | 回到顶部按钮 |
| pager | current、total、base | 分页导航 |
| palette-picker | palettes、labels | 整页主题切换按钮组（SsvulTheme.set；选中态 .active + aria-pressed；clear=恢复页面初始主题；生成器自动全量链接 md-code-*.css） |
| theme-switcher | themes、labels | 主题切换（持久化 ssvul-theme；选中态 .active + aria-pressed） |
每个 div 的 params/钩子契约见其 template.html 头注释；div 根元素带 `data-depth` 属性供 js 计算相对路径。

**内置主题**：`theme` 键自动引用 `md-code-<theme>.css`（存在即注入）：`dark`（深色）、`sepia`（护眼纸色）、`green`（终端绿）；自定义主题 = 在 src/assets/md/css/ 放同名 css 文件。

> **div 编写完整指南**：[docs/UserWrite/div-guide.md](docs/UserWrite/div-guide.md)（模板占位符/params 双通道/只注册不执行/继承规则/.contract 格式/内置 div 清单/示例）。
div用于创建具体的模块及行为，如导航栏
最后将由[Integra](./src/main/java/io/github/endingratitan/Integra)完成页面组装；sets/divs 未命中的类型会回落查找 src/assets/divs/（官方预设组件）
##### 额外文件（三态互斥，同一 div 只能有一种）
- 无标记：被引用时 div 内的 JS/CSS 合并为 `<name>.js` / `<name>.css`（name 为页面名），放在 `pages/<structures...>/<name>/` 下与页面 html 同级；INDEX 页特例放在 `assets/index/` 下（index.js / index.css）
- `.global` 文件（无内容）：div 的 JS/CSS 输出为 assets/js|css/<type>.js|css 独立全局文件，页面用 deps 的 `global:<type>` 显式引用
- `.adds` 文件（无内容，不需要再加 .global）：div 的 JS/CSS 拼入 assets/js/web_global.js 与 assets/css/web_global.css，此两文件被所有 html 自动引用
- `template.html`（可选）：页面组装模板，占位符 {{key}}（←params）、{{content}}（←markdown/raw）、{{children}}（←嵌套子 div）；未声明占位符报错；没有模板时使用默认包裹层（id/class/attrs/data-*）
#### pages/
pages目录下结构将被完整保留到output/pages目录中，具体内容将被替换
pages中可放入json文件，作为网页内容的配置文件；可放入md文件，作为内容
也可以建一个文件夹，直接放入HTML，js，css文件，内容将直接整个文件夹对应进入output/中；
```
（structures... 表示中间可有若干层任意目录结构，源与目标一一对应）
sets/pages
└── structures.../
    ├── page.json            ← 网页配置文件，生成 page-name/ 目录及 html
    ├── page.md              ← 页面内容（配合 json 使用）
    └── raw_page/            ← 原始文件夹：html/js/css 原样复制
        └── index.html ...

output/pages
└── structures.../
    ├── page-name/           ← 由 json 生成（name 键决定命名）
    │   └── index.html ...
    └── raw_page/            ← 原样复制
        └── index.html ...
```

#### data/
可有readme/目录，若readme键设为1，将目录下所有.md平铺复制到output根（用于GitHub展示，文件名重复报错）
其他目录结构自定，将会完整复制到output/assets/data下
建议存储json、md、图片等；禁止js/css（生成时扫描报错）
用于被其他引用（md 中经 @data/... 引用）
生成器保留名：`list/` 目录（list 的 `ssvul:shared` 分片输出，sets/data 下占名报错）；`search-index.json` 同样由生成器输出，勿同名占位
#### outer/
作为bucket的本地复制，
下面有若干以调用名命名的子目录（如 outer/bk/），其内容与对应云端结构一模一样
offline=1 时：bucket 引用命中此处 → 复制进 output/assets/outer/<调用名>/... 并替换引用为本地路径（离线全图预览）；
offline=0 时：输出线上 URL，但若 outer 目录存在而引用未命中 → 构建警告（对照功能）
#### favicon/
若有，将被复制到output/favicon

### 网页页面 json 规则
使用 jackson 读取，并按 `page.schema.json` 校验：未知键、类型不符、重复键均报错；md 渲染错误为收集式（一次最多报 20 条，带行号与修复建议）。

#### 顶层键

| 键                         | 类型     | 作用                                                                                               |
|----------------------------|----------|----------------------------------------------------------------------------------------------------|
| name                       | string   | 页面名（kebab-case），缺省用 json 文件名；生成 pages/<structures...>/<name>/index.html             |
| title / lang / description | string   | <title>、<html lang>（默认 zh-CN）、<meta name="description">                                      |
| favicon                    | string   | 图标地址：http(s)://、pre-assets/、@favicon/文件名（sets/favicon/ 下）、bucket 调用名路径          |
| head                       | string[] | 追加进 <head> 的原生 HTML 片段                                                                     |
| theme                      | string   | 初始主题（如 light/dark）：生成 data-theme + 防闪烁内联脚本，并自动引用 md-theme.js 预设           |
| md                         | object   | md 渲染与主题（页面级默认，div 级同构且就近覆盖）：`css`=主题（`default`=预设主题／`none`=不要主题／`pre-assets/…`／`global/…`）、`wrap`=是否用 `<div class="md-body">` 包住渲染结果（默认 true）、其余为渲染选项。主题注入时**统一作用域化**为 `md-theme-<hash>`（一份主题一个类一个文件，见下"md 主题"节） |
| md-js                      | string[] | md 增强 js（顺序保序；如 md-math.js）；有 md 内容时注入；hljs 高亮三件套已由 engine 自动注入，无需手写 |
| deps                       | string[] | 依赖：http(s):// 外源、pre-assets/ 预设、global:<type> 显式引用 .global div                        |
| engine                     | string/string[] | 代码高亮引擎（有序链，默认 hljs）；simple=构建期词法零客户端脚本；详见"代码高亮引擎"小节          |
| code-ui                    | object   | 代码块外壳定制（items 数组 + bg/rounded/label-pos，全默认关）；详见"代码块外壳定制"小节            |
| page                       | object   | 内容区：div 组合树，键为 div-ID                                                                    |


#### page（div 组合树）

- 键名 `div-ID`（正整数 ID）；按 ID 数值升序组装，允许跳号，同页重复 ID 报错；div-ID 渲染为根元素 id，子 div 按 div-2-1 拼接
- 每个 div 对象：
  - `type`（必填）：对应 sets/divs/ 下目录（categories=1 时写作 category/divname），未命中回落 src/assets/divs/
  - `params`：注入模板 {{key}} 并渲染为根元素 data-key 属性；键名禁止 content/children
  - `markdown`：内联 md；或以 @pages/...、@data/... 引用 md 文件 → 转 html 注入 {{content}}
  - `md`：本 div 的 md 渲染与主题（可选）：就近覆盖页面级同名键，只作用于本 div 的 markdown；`css`/`wrap` 见顶层键表；锚点/脚注 id 自动加本 div-ID 前缀
  - `raw`：任意 HTML 片段注入 {{content}}（与 markdown 二选一）
  - `attrs`：注入根元素属性（class 并入默认类 ssvul-<type>；id 禁止）
  - `divs`：嵌套子组合（同 div-ID 规则）

#### md 语法（v1 子集）
标题（# 后须空格，自动分配锚点 id：页面 div 内为 `div-ID 前缀` 形态如 `div-1-s1`（多 md div 不重复），裸 md 页面为 `s1/s2…`；`#锚点` 链接放行不校验，需写全带前缀的 id）、段落、**粗**/*斜*/_斜_（_ 两侧不得同时为字母数字）、`行内代码`、~~删除~~、
[链接](url)、![图片](url)、列表（含任务列表 - [ ]；simple 不限层、strict 限 8 层）、引用块（每行须 >；simple 超 8 层警告一次、strict 超 8 层报错）、围栏代码块（```lang）、
表格（:--- 对齐）、$...$ 与 \(...\) 行内数学、$$...$$ 与 \[...\] 块数学（块级支持多行）、[[key]] 按键、
分割线（***/___ 实线、--- 虚线、+++ 双线，各自独立 class 可分别定制 CSS）、<https://> 自动链接、\ 转义（含 \$ 强制输出 $）、
引用块**首行** `> [!TYPE]` = 提示块 callout（16 个内置类型 + 扩展类型；标题可自定义，详见「提示块 callout」小节）。
链接/图片 URL 白名单：http(s)://、pre-assets/、@data/、@page/（站内互链）、#锚点、bucket 调用名。

脚注：引用 `[^n]`（尊重用户编号、可重复引用）与 `[^.]`（自动补最小空位，显式编号全部占位）；
定义行 `[^n]: 内容`（文档顶层、单行、仅行内 md）与 `[.^]: 内容`（懒惰定义，按顺序配给未配对引用）；
重复定义/引用未定义报错，定义多出仅警告；定义内再写 `[^x]` 按字面（不支持嵌套，与 GitHub 一致）；
`footnote-display: end`（默认：脚注区放 md-body 末尾；div 模板可用 `{{footnotes}}` 占位符接管位置）| `inline`（定义处就地显示）。

md 渲染选项（页面 json 顶层 `md` 对象里，全部有默认值可不写）：
- `mode`: `simple`（默认，GitHub 兼容：--- 分隔线、原生 HTML、不限列表嵌套、未闭合围栏不报错）| `strict`（严格报错，列表嵌套限 8 层）
- `footnote-display`: `end`（默认，脚注统一放）| `inline`（就地显示）
- `toc`: `true` 构建期生成目录（md-body 顶部，h2~h6 入目录、h1 排除；默认 false）
- `callout-title`: `default`（默认，注入 callout 默认标签）| `none`（不出默认标题元素，作者自定义标题仍生效）
div 条目内可写同一个 `md` 对象（可选，就近覆盖）：只覆盖写出的键，其余继承页面级（嵌套 div 继承父 div 有效值）；
只作用于该 div 自己的 markdown，其标题锚点与脚注 id 自动加该 div-ID 前缀（如 div-1-s1、div-1-fn-1）。

simple/strict 行为矩阵（⑥ 定稿；两模式支持面相同，仅容忍度不同）：

| 语法点 | simple（默认） | strict |
|---|---|---|
| 未闭合代码围栏 | 渲染到文末 | 报错"未闭合的代码块" |
| 列表嵌套 | 不限层；超 8 层警告一次（仍继续渲染） | 超 8 层报错（降级为普通段落，不丢内容） |
| 表格列数不齐 | 按表头列数补齐/截断 | 报错（分隔行/数据行） |
| 分割线 `---` | `<hr class="md-hr-dash">`（虚线，可定制 CSS） | 同左 |
| 分割线 `+++` | `<hr class="md-hr-plus">`（双线，可定制 CSS） | 同左 |
| 分割线 `***`/`___` | `<hr class="md-hr-star">`（实线，可定制 CSS） | 同左 |
| 原生 HTML 行 | 透传 | 报错"请改用 div.raw" |
| 引用嵌套 | 超 8 层警告一次并压平渲染（仍嵌套渲染，只是不再计深度） | 超 8 层报错 |
| 引用内缺 `>` 行 | 警告，按段落继续 | 报错 |
| 块数学 `$$` 未闭合 | 报错 | 报错 |
| 行内数学未闭合 `$` | 孤 `$` 按行尾收口渲染 + 警告；`$数字`（货币）保持字面 | 报错；`$数字` 字面 |
| 空标题 | 警告 | 警告 |
| 脚注定义未被引用 | 警告 | 报错 |
| URL 白名单违反 / 空 URL | 报错 | 报错（安全不分模式） |
| 脚注引用未定义 | 报错 | 报错 |

#### 提示块 callout

引用块**首行**写 `[!类型]` 即成提示块（类型名大小写不敏感，可带自定义标题）：

```markdown
> [!note]
> 正文（可含列表/代码块/公式等任意块级内容）

> [!tip] **自定义**标题
> 写了标题就替换默认标签，标题走行内 md。
```

- **内置 16 类型**（默认中文标签）：`note` 注意 / `tip` 提示 / `important` 重要 / `warning` 警告 / `caution` 小心 / `info` 信息 / `success` 成功 / `question` 疑问 / `example` 示例 / `quote` 引用 / `abstract` 摘要 / `todo` 待办 / `danger` 危险 / `failure` 失败 / `bug` 缺陷 / `debug` 调试。
- **非内置类型不算错误**：警告一次并按扩展类型渲染（类名 `md-callout-<type>` 保留、标题回落为类型名首字母大写 `release` → Release、配色落到中性兜底），随后用站点级 `CALLOUT.css` 上样式即可。
- 产物契约：`<blockquote class="md-callout md-callout-note" data-callout="note">` + `<p class="md-callout-title md-callout-title-default"><span class="md-callout-label">注意</span></p>`（自定义标题不带 `-default` 类与 `label` 层）；图标由 CSS `mask` + data-URI 实现（**零 JS、零请求**）。
- **按需注入**：`md-callout.css` 只在**本页出现 callout** 时注入（hasCallout 门控，与 code-ui 的 hasCode 同构）；无 callout 的页面零新增字节。
- `md.callout-title`: `default`（默认）| `none`（不出默认标题；自定义标题仍生效），div 级可就近覆盖。
- **扩展与覆写（站点级逃生舱）**：`sets/global/callout/CALLOUT.css`（基础）与 `CALLOUT.<lang>.css`（语言变体，`lang` 为占位符，如 `zh` / `zh-CN` / `en`）。**存在即注入** + hasCallout 门控；页面 `lang` 命中变体时**只注入变体**（变体自包含；想叠加就在变体首行 `@import url("CALLOUT.css");`），未命中回落 `CALLOUT.css`；同选择器后到即胜。目录内仅允许这两个文件名，多出报错。官方模板在 `src/assets/global/callout/`（永不自动注入）。

> 完整指南：[docs/UserWrite/callout-guide.md](docs/UserWrite/callout-guide.md)（语法/16 类型表/扩展三配方/变量契约/校验与性能）。

#### 代码高亮引擎（engine 换装）

页面顶层 `engine`（string 或有序链 string[]，默认 `hljs`）决定"谁给围栏代码块上色"。换引擎不换产物契约：
代码块始终是 `<code class="md-code language-x">` 外壳 + canonical 类 `tk-*`，由 CSS 变量层（--md-code-*）统一上色，
主题换肤（theme 键）对所有引擎同样生效。

- `hljs`（默认）：客户端上色。构建期仅转义；浏览器加载 hljs.min.js → md-highlight.js 上色 → token-map.js 映射 tk-*。
  资源自动注入（仅当页面含**带语言**的代码块时，性能优先）；md-js 手写同款脚本会去重并警告。
- `simple`：构建期词法引擎（技术验证器）。内置 java/javascript/python/c/cpp/bash/html 词表，
  构建产物直接带 tk-* 类——零客户端脚本、无首屏闪烁、无 JS 环境也有颜色，追求性能与离线时的首选。
  局限：仅词法（正则内部、模板插值内部等粗处理），不认识的代码块不接。
- 数组 = 有序链（如 `["simple","hljs"]`）：按序问询各引擎 `accepts(lang)`，命中的上色；
  全员拒接 → 纯文本兜底 + 构建警告（**构建永不因高亮失败**）；链成员的客户端资源合并去重后注入。
- 未知名引擎：忽略 + 构建警告（⑥ 定稿：不升级为报错）。
- 词表扩展：Environment.config 的 `engine-words=(语言,data/词表路径)`（可多条）为 simple 追加/覆盖关键字，
  文件行格式：`#注释`、`词`（默认 tokenid=kw）、`词:tokenid`。
- 裸 md 页面（无同名 json）不注入任何引擎资源（性能优先）；第三方引擎接入为 v3 计划（tree-sitter 等）。

#### 代码块外壳定制（code-ui）

页面顶层 `code-ui`（全部默认关/缺省）：

- `items`（string[]）：声明块内要哪些元素，**数组顺序 = DOM 顺序**。内置：`lang-label`（语言角标）、`mac-dots`（红绿灯）、`copy-btn`（复制按钮，自动引用内置 md-copy.js——clipboard API + execCommand 回退，成功派发 `ssvulcopy` 事件）。未知名 item：警告跳过，但完整列表保留在块容器 `data-items` 属性里，站点 CODEUI.js 可按它自行实现（逃生舱）。
- `bg`（string）：块背景图，inline style 注入（pre-assets/、bk/、http(s):// 形态，替换趟联动、离线可用）。
- `rounded`（boolean）：圆角（半径变量 `--codeui-radius`，随主题变化）。
- `label-pos`（enum tr/tl/br/bl）：角标位置；复制按钮固定右上，tr 冲突时角标自动让位（块加 `has-copy` 类）。

生成的契约结构（CSS/JS 深度定制三层钩子：块级开关类 × 元素类 × `data-lang` 语言态）：
```html
<div class="md-code-block codeui-lang codeui-dots codeui-copy codeui-bg codeui-rounded has-copy"
     data-lang="bash" data-items="lang-label,mac-dots,copy-btn" style="background-image:url(…);">
  <span class="md-code-lang pos-tr">bash</span>
  <span class="md-code-dots" aria-hidden="true"><i></i><i></i><i></i></span>
  <button type="button" class="md-code-copy">复制</button>
  <pre class="md-pre"><code class="md-code language-bash">…
```

**站点级逃生舱**：`sets/global/codeui/CODEUI.css` 与 `CODEUI.js`（域内仅允许这两个文件，多出报错）——
**存在即注入**：仅注入**含代码块的页面**（hasCode 门控，性能优先）；css 在主题 css 之后、js 在所有引擎/内置脚本之后执行。
官方模板在 `src/assets/global/codeui/`（含明暗主题与自定义 item 示例），复制到 sets 后即生效，永不自动注入。
未在 json 写任何 code-ui 的页面同样会注入（站点级定制全局生效）。

#### 接口契约（主题/配色/事件）

| 契约 | 值 |
|---|---|
| localStorage 键 | `ssvul-theme`（主题名）、`ssvul-palette`（访客调色板：JSON 变量表如 `{"--md-code-kw":"#ff7b72"}`，优先于主题 css 文件）、`ssvul-palette-ver`（v0.3.0 迁移标记：旧版 picker 遗留的调色板在首次升级时自动清除一次） |
| 事件 | `themechange`(detail.theme)、`palettechange`(detail.palette)、`ssvulhighlight`(引擎上色完成)、`ssvulcopy`(detail.ok/lang) |
| 全局对象 | `window.SsvulTheme { get, set, toggle, setPalette, clearPalette }`（md-theme.js 预设提供） |
| CSS 变量 | `--md-code-*`（26 类 token 配色）+ `--codeui-*`（code-ui 半径等）；主题 css 文件与访客调色板都走这套变量 |

访客自定配色：站点作者用 CODEUI.js 或自写 div 调 `SsvulTheme.setPalette({…})` 即可；防闪烁内联脚本首帧前自动恢复两个键。

#### 特殊文件
- `sets/pages/INDEX.json`：特判输出到 output/index.html（name 键必须为 INDEX）
- 裸 md 文件（pages 下无同名 json）：自动成为最简页面（BASE.html + md 渲染 + 预设 md.css）
- raw 文件夹（pages 下含 index.html 的目录）：整个目录**任意文件直拷**（原始 HTML 资产区，内部 @page/ 等引用照常替换，但不再套模板、不校验内容）

#### 校验清单（**配置/资产层**，⑥b 定稿）

> **别和"产物校验"混**：这里是构建**输入**的校验（键/路径/形态，错了就报错不写盘）。产物层的校验是 `--verify`：
> 默认档 `1` 逐个比对 `.ssvul/deps-<站点号>.json` 里记录的产物 size/mtime，缺失或被手改 → **只重渲染它的归属页**把它修回；`--verify 0` 关掉换速度。

| 层 | 校验点 |
|---|---|
| Environment.config | 未知键报错；语法缺 '=' 报错；cname 必填且禁协议；bucket 格式/重复/协议/**属性（endpoint/prefix/ref）**校验；session-ttl 须为非负整数；engine-words 语言名、路径（须 data/）、tokenid 白名单 |
| page json（schema 层） | 未知键报错；类型不符报错；div-ID 格式与重复键；params 键名禁 content/children；deps 形态白名单（http(s)://、pre-assets/、global:…）；engine 类型 |
| page json（运行时） | deps 仅 .js/.css 且重复条目去重+警告；md-js 形态与重复去重；@page/@data/md 引用目标存在性；模板未声明占位符；engine 未知名警告回退；md 引用路径形态 |
| divs 目录 | 类型 kebab-case；目录内仅 js/css/template.html/.global/.adds，未知文件报错；.global 与 .adds 互斥；global: 引用必须指向 .global div |
| global 目录 | codeui 域仅允许 CODEUI.css/CODEUI.js（多出报错）；callout 域仅允许 CALLOUT.css / CALLOUT.<lang>.css（多出报错）；未知域目录仅警告（未来扩展预留）；文件复制进 assets/global/<域>/ |
| data 目录 | 禁 js/css；readme 仅 .md 且文件名去重 |
| favicon | 形态白名单；local-favicon=1 时禁 @favicon/ |
| 输出替换 | pre-assets/@data 引用存在性；offline=1 时 bucket 引用必须命中 outer/ 镜像（未命中报错），offline=0 时未命中警告 |

#### 示例
```jsonc
{
  "name": "about",
  "theme": "light",
  "deps": ["pre-assets/lib/katex/katex.min.css", "pre-assets/lib/katex/katex.min.js"],
  "md-js": ["pre-assets/md/js/md-math.js", "pre-assets/md/js/md-highlight.js"],
  "page": {
    "div-1": { "type": "navbar", "params": { "brand": "Ssvul" } },
    "div-2": { "type": "article", "markdown": "@pages/about.md", "md": { "footnote-display": "inline" }, "divs": { "div-1": { "type": "comment" } } }
  }
}
```

### src/assets/
官方预设目录（作为 sets/ 部分目录的预设），按功能分类：
- `page/`：页面外壳 BASE.html（生成器内部使用）
- `md/`：md 相关（css 主题与调色板、js 增强：md-highlight / token-map / md-math / md-theme；**callout 视觉 `md/css/md-callout.css` 按需注入**）
- `divs/`：预设组件（如 theme-switcher，sets/divs 未命中时回落此处）
- `div-libs/`：**div 专属库**（`list/` 官方对接函数 inline/shared/json/s3；`md-csr/` 客户端 md 渲染库）——由构建期按需注入，产物落在 `assets/pre/div-libs/<div 名>/`：**全站共享一份字节、路径稳定**，可配长期缓存（见 md-csr 指南的 `_headers` 配方）
- `global/`：站点级逃生舱官方模板（`codeui/`、`callout/`；复制到 sets 后才生效，永不自动注入）
- `lib/`：第三方 vendor（hljs、katex，附许可证文件）
- `runtime/`：构建期注入的运行时库（`ssvul-div.js` 的 register/super/initAll）
预设以 `pre-assets/<路径>` 引用，对应 src/assets/<路径>；生成时按需复制到 output/assets/pre/<路径>（未引用不复制，引用缺失报错），
输出文本中的字串 `pre-assets/` 替换为按深度修正的 `assets/pre/`
可以提供已有的CSR等功能

### 示例项目
`example-sets/` 是最小示例站点（含 div 三态、theme、md 数学/高亮、callout 提示块与语言变体、bucket、站内互链、raw 文件夹、list 的四种来源——含 `ssvul:s3` 列目录演示页 `pages/blog-s3.json`，该页在 offline=1 下会刻意产生一条"运行时需要联网"的构建警告）：
将其内容复制为 `sets/` 后运行程序，即可在 output/ 得到完整站点。

### 许可证
本项目以 **MPL-2.0** 发布（全文见 `LICENSE`，归属与贡献者清单见 `NOTICE`）。
使用与再分发要求：
- 必须保留各源文件头部的版权与项目来源声明，以及根目录的 `LICENSE` 与 `NOTICE`
- 对 MPL 覆盖文件的修改，须同样以 MPL-2.0 分发（文件级 copyleft）；工具生成的站点产物不受此约束，但站点内随附的预设内容（如 BASE 模板头部）需保留其来源声明
- `src/assets/lib/` 下的第三方 vendor（hljs BSD-3、katex MIT/OFL）保持各自原许可

