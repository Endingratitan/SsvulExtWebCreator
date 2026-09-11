# 变更台账（对外契约的破坏性变更）

> 这里只记录**会影响站点作者**的变更：改名的键/参数/div 类型、被移除的能力、不兼容的默认值或产物路径。
> 每条给出：**变更 → 旧写法为什么不生效 → 迁移写法**。日常修复、内部重构与踩坑记录见 `tech.md`（不入库）。

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
- 资源位于 `src/assets/list/*.js`，按需复制到 `output/assets/pre/list/*.js`（沿用 `pre-assets/` 既有机制）。
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
