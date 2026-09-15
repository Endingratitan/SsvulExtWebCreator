# 变更台账（对外契约的破坏性变更）

> 这里只记录**会影响站点作者**的变更：改名的键/参数/div 类型、被移除的能力、不兼容的默认值或产物路径。
> 每条给出：**变更 → 旧写法为什么不生效 → 迁移写法**。日常修复、内部重构与踩坑记录见 `tech.md`（不入库）。

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
