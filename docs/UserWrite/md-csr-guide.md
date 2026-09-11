# md-csr 指南（客户端渲染 markdown）

> 面向站点作者：让**运行时**（浏览器）渲染 markdown —— 站点构建一次之后，往桶里/服务器上**加、删、改 md 文件**，
> 刷新页面即渲染，**不需要重新构建**。
> 相关：`docs/UserWrite/list-guide.md`（列表 + 桶列目录）、`history.md`（对外契约台账）。

---

## 1. 它是什么、不是什么

| | 构建期渲染（`markdown` div / 裸 md 页） | **md-csr（本指南）** |
|---|---|---|
| 何时渲染 | 构建时 | 打开页面时（浏览器） |
| 内容从哪来 | `sets/` 里的文件 | **桶 / 任意 URL / `?p=` 查询参数** |
| 更新方式 | 重新构建 | **上传文件 + 刷新** |
| 搜索引擎可见 | ✓ | ✗（CSR 内容爬虫看不到） |
| `file://` 直开 | ✓ | ✗（需要 fetch） |
| 语法覆盖 | 全部 | **全部**（与构建期逐字节同构，见 §5） |

**结论**：静态内容、要 SEO 的内容用构建期；**"作者随时上传、不想重建"**的内容用 md-csr。两者可以同页共存。

## 2. 最小用法

```jsonc
// sets/pages/reader.json —— 一个通用"阅读页"：?p=<桶内路径>
{ "name": "reader",
  "page": { "div-1": { "type": "md-csr", "params": { "bucket": "bk" } } } }
```

配合 `list` 的 `link` 模板，让桶列表的条目点进来由本站渲染（而不是直链原始 md）：

```jsonc
{ "type": "list",
  "params": { "src": "ssvul:s3", "dir": "blog", "pattern": "*.md",
              "link": "./?p={key}", "fields": "date,title", "empty": "桶里还没有 md" } }
```

之后：把 `xxx.md` 传到桶里 `site/blog/xxx.md` → 刷新列表页 → 条目出现 → 点它 → `reader/?p=site/blog/xxx.md` 渲染。
**全程没有重新构建。**

## 3. 数据源（三选一，都不写则读 `?p=`）

| 写法 | 含义 |
|---|---|
| `bucket` + `key` | 桶内路径（`key="site/blog/note.md"`）；`href` 由构建期从 `Environment.config` 注入 |
| `href` + `key` | 直接给公开读基址（不写 `bucket` 也可以，适合非桶来源） |
| `file` | 站点相对路径 / `/` 开头 / `http(s)://`（与 `ssvul:json` 同风格） |
| （默认）`?p=<路径>` | **有桶配置时按桶内键解释**（配合 `link` 模板），否则按站点相对路径；参数名可用 `query=` 改 |

`key`/`?p=` 的形态校验：禁 `..`、禁协议头；逐段 `encodeURIComponent` 后拼接。

## 4. 渲染选项（params 透传，与构建期 md-options 同名）

| 键 | 默认 | 说明 |
|---|---|---|
| `raw-html` | **`off`** | `off`=原生 HTML 一律**转义**（安全）；`on`=与构建期 simple 模式一致（**直出**，仅在你信任 md 来源时用） |
| `link-policy` | **`relaxed`** | `relaxed`=放行相对路径（`../`、`./`、`/`）；`strict`=只认构建期白名单。**两种都拦 `javascript:/data:/vbscript:/file:`** |
| `toc` | `false` | `true`=生成目录（h2~h6，锚点 `s1/s2…`） |
| `footnote-display` | `end` | `inline`=定义处就地渲染脚注块（文末不再生成脚注区） |
| `callout-title` | `default` | `none`=不注入 callout 默认中文标签（仍渲染 callout 容器） |
| `codeui.items` / `codeui.bg` / `codeui.rounded` / `codeui.label-pos` | 空 | 与构建期同名的 code-ui 外壳配置（声明了 `codeui.*` 时会一并注入站点 CODEUI.css/js 与复制按钮脚本） |
| `math` | 空 | **`on`** = 自动注入 KaTeX（`katex.min.css` + 字体 + `katex.min.js` + `md-math.js` 预设，约 1MB）；不写 = 不注入。**公式页请显式打开**：md-csr 的内容构建期不可知，不能替你做这个判断 |

> 数学：md-csr 只输出 `.md-math` / `.md-math-block` 锚点，排版交给 KaTeX。
> 写 `"math": "on"` 即自动引入（代码块同理由 hljs 自动引入）；也可以自己用 `deps` 手写这三条——
> 两处都写会在构建期**跳过自动项并警告**（`md-js 与 md-csr 自动资源重复`），不会重复加载。

## 5. 与构建期的关系：**同一套实现，可证等价**

- 客户端渲染器是 `Integra/MarkdownIntergra/` 的 **JS 镜像**（`src/assets/div-libs/md-csr/*.js`，文件与 Java 一一对应）。
- **金标差分测试台**把等价性钉死：`src/test/md-corpus/` 的语料由 Java 渲染成金标（`*.expected.html`），
  JS 侧必须**逐字节相等**：
  ```bash
  java -cp "build/classes:$(ls build/deps/*.jar | paste -sd:)" MdDump gen     # 改语法后重生成金标（人工复核）
  node src/test/js/md-parity.test.js all                                       # 源码级 parity
  node src/test/js/md-parity.test.js all '' build/<站点>/assets/pre/div-libs/md-csr   # 产物级（压缩后）
  ```
- **两处有意的差异**（都进测试台）：
  1. 原生 HTML 默认转义（`raw-html=on` 时与构建期一致）；
  2. 链接放行相对路径（构建期用站点白名单校验目标存在，客户端没有站点树）。
- **代码高亮**：客户端一律按 **hljs 语义**输出（只转义 + `class="md-code language-x"`），上色交给浏览器 hljs。
  若站点在构建期用 `simple` 引擎（构建期就上色），两种渲染的**代码配色可能不同**——这是已知且可接受的差异。
- **公式**：构建期与客户端都输出同样的锚点，由 `md-math.js` + KaTeX 现场排版。

## 6. 边界、安全与性能

- **必须联网**：`file://` 直开不可用；桶/接口要允许跨域（见 list-guide 的桶策略与 CORS 原文）。
- **XSS**：默认 `raw-html=off` —— 桶里的 md 属于"别人上传的内容"，直出等于存储型 XSS。**只有你完全掌控内容时才开 `on`**。
- **SEO/无 JS**：CSR 内容爬虫看不到。重要内容请同时提供构建期版本（例如归档页用 `build:page-index`）。
- **失败不阻断**：取数/渲染失败时槽位里放一条 `.md-csr-error`（页面不白屏），详细原因在 `console.warn`。
- **事件**：渲染完成派发 **`ssvulmd`**（`detail = {url, errors, warnings, hasCode, hasCallout}`），可在此挂目录/分享按钮等。
- **缓存（重要）**：渲染器走**预设路径** `assets/pre/div-libs/md-csr/*.js`（全站共享、路径稳定）。
  `_headers` 配方（Cloudflare Pages 等）：
  ```
  /assets/pre/div-libs/md-csr/*
    Cache-Control: public, max-age=86400
  ```
  保守值（1 天）——**在没有内容哈希前不要用 `immutable, max-age=31536000`**：渲染器升级后老访客会一年拿不到新版。
  md **正文**不要长缓存（内容会变），让服务器按常规短 TTL 处理即可。
- **体积**：库只在含 `md-csr` 的页面注入（约 40KB 未压缩、10 个文件；hljs 三件套另计）。

## 7. 调试

| 现象 | 排查 |
|---|---|
| 槽位显示"内容加载失败" | 看 `console.warn`：`SsvulMdCsr: …`（http 状态、路径不存在、缺 `href`、无数据源…） |
| 列表条目点开是纯文本 | `list` 的 `link` 模板没写（默认直链到桶里的原始文件） |
| 中文/空格文件名 404 | `key` 里不要自己编码，交给 `{key}`/`key`（内部逐段编码） |
| 公式不排版 | div 里没写 `"math": "on"`（或自己引入 KaTeX 时把 `md-math.js` 排在了 `katex.min.js` 之前） |
| 改完 md 看不到变化 | 浏览器/CDN 缓存 md 正文；`list` 的列表本身有 `session-ttl` 缓存（可 `cache: "0"` 临时关掉） |
