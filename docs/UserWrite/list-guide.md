# list 编写指南（SsvulExtWebCreator）

> 面向站点作者：`list` 列表组件的对外契约、六种数据来源，以及"**只上传文件就更新**"的做法。
> 相关：`docs/UserWrite/div-guide.md`（div 组件规范）、`history.md`（对外契约变更台账，含 `shower → list` 迁移）。

## 1. 契约：只有 4 个已知参数

```jsonc
{ "type": "list",
  "params": {
    "src":     "build:page-index",        // 数据来源与渲染时机（不写 = 这个默认值）
    "dir":     "pages/blog",              // 目录前缀（构建期来源用；ssvul:json 用 file 而非 dir）
    "pattern": "*.md",                    // *.md | *.json | *（默认 *）
    "fields":  "date,title,excerpt"       // 显示哪些字段；渲染顺序固定：date → title → tags → excerpt
  } }
```

- **其余任意键原样透传**给对接函数（`file`、`empty` 这类是预设自己的约定参数，见 §7）。
- 产物：外层 `<div class="ssvul-list" data-src=… data-depth=…>` + 模板 `divs/list/template.html` 里的 `<ul class="list-items">{{items}}</ul>`。
- 条目类名（构建期渲染与客户端预设**必须一致**，这是唯一的不变量）：`.list-item` / `.list-date` / `.list-title` / `.list-tags` / `.list-tag` / `.list-excerpt`。

## 2. `src` 六种取值（从最静态到最动态）

| `src` | 谁渲染 | 构建期做什么 | 之后怎么更新 | `file://` |
|---|---|---|---|---|
| `build:page-index`（**默认**） | 构建期 | 直接产出条目 HTML，**不注入 list 的 js** | 重建 | ✓ |
| `ssvul:inline` | 客户端 | 把条目内联进页面（`.list-data`）+ 注入预设 js | 重建 | ✓ |
| `ssvul:shared` | 客户端 | 按目录发射分片 `assets/data/list/<dir>.json` + 注入预设 js | 重建，**或只上传那个分片** | ✗ |
| `ssvul:json` | 客户端 | **什么都不生成**，只注入预设 js | **只上传那个 JSON 文件** | ✗ |
| `ssvul:s3` | 客户端 | 只把桶信息注入 `data-bk-*`，**什么都不生成** | **往桶里加/删/改文件即刷新可见**（浏览器实时列目录） | ✗ |
| `<你的函数名>`（裸名） | 客户端 | 只写 `data-src` | 改你的 js（用 `head`/`deps` 引入） | ✗ |

> **"重建"= 增量重建（0.4.0）**：只重渲染受影响的那几页；一个源都没变时构建几乎立刻返回（不扫描/不渲染/不写盘）。
> 所以上表"之后怎么更新"里那些"重建"，成本通常就是**改一页 = 重渲染一页**。

`ssvul:*` 是官方预设，构建期**按名**解析 → 把 `src/assets/div-libs/list/<名>.js` 按需复制到 `output/assets/pre/div-libs/list/` → 自动注入 `<script>`。作者不需要知道文件路径。

## 3. 条目形状（构建期与客户端完全一致）

```json
{ "link": "pages/blog/post/", "type": "md", "title": "博文",
  "date": "2026-09-07", "excerpt": "纯文本摘要…", "tags": "cs,web" }
```

- `link` 是站点根相对路径（客户端按 `data-depth` 自动补 `../`；构建期由替换趟解析并校验目标存在）。
  **例外**：`ssvul:s3` 的 `link` 是**绝对 URL**（`href + 桶内路径`），不再叠加 `data-depth` 前缀。
- 标题与摘要里的行内标记（`**粗**`、链接、图片）在**索引阶段已净化成纯文本**。
- 引擎不再附带 `text` 全文（列表用不到）。

## 4. 配方

**① 零 JS 归档页**（SEO/无 JS 友好）
```jsonc
{ "type": "list", "params": { "dir": "pages/blog", "pattern": "*.md" } }
```
构建期固定规则：`dir`+`pattern` 命中的**全部**页面 → **日期倒序**（同日期按标题码点序，无日期最后）→ **不截断**；自动跳过 INDEX 与列表页自身。

**② "最近 N 篇 / 按标签 / 随机"** → 这些**不在契约里**，写自己的对接函数（§5）：
```jsonc
{ "type": "list", "params": { "src": "我的最近N篇", "dir": "pages/blog", "count": 5 } }
```
（`count` 是你的函数自己解释的键——list 只负责透传。）

**③ 多页共用一个索引**：用 `ssvul:shared`（构建期发射一次分片，多页共用一份字节）或 `ssvul:json`（你自己维护一份 JSON，构建期完全不参与）。

**④ 只上传文件就更新（不重建）**——这是纯 CSR 的用法：
1. 用 `src: ssvul:json` 指向一份**你自己维护**的 JSON（如 `assets/data/blog/index.json`；放在 `sets/data/blog/` 里构建会照常复制，或构建后直接上传到该路径）。
2. 之后新增文章 = 上传 md/html + 在 JSON 里加一条 → **刷新页面即生效**，不需要重新构建。
3. 注意事项：
   - **浏览器不能列目录**——所以"目录里有什么"必须由这份 JSON（或某个接口）提供，这是它的角色；
   - 依赖 `fetch`：`file://` 直开不可用，用本地 http 服务或部署后看；跨域要目标允许 CORS；
   - **缓存**：CDN/浏览器可能缓存旧 JSON，建议给 `assets/data/**` 配 no-cache，或在 URL 上带时间戳。

**⑤ 列目录：往桶里放文件即出现（`ssvul:s3`，纯 CSR）**

1. `Environment.config` 给桶声明**列目录基址**与**存储前缀**（`href` = 点开后访问的基址，`endpoint` = 列目录 API 的基址，两者可以不同）：
   ```
   bucket=[bk,https://cdn.example.com,endpoint=https://s3.us-east-1.amazonaws.com/my-bucket,prefix=site/blog]
   session-ttl=86400
   ```
2. 页面里指定子目录（`dir` 拼在桶前缀之后 → 实际列 `site/blog/`）：
   ```jsonc
   { "type": "list", "params": { "src": "ssvul:s3", "pattern": "*.html", "fields": "date,title" } }
   ```
   （再写 `"dir": "2026"` 就是列 `site/blog/2026/`。）
3. 桶要允许**匿名列目录 + 读对象**，并配 **CORS**（S3 与兼容协议同形——R2/MinIO 照抄；OSS/COS 用各自控制台的等价设置）：

   桶策略：
   ```json
   { "Version": "2012-10-17",
     "Statement": [
       { "Sid": "PublicRead", "Effect": "Allow", "Principal": "*",
         "Action": ["s3:GetObject"], "Resource": "arn:aws:s3:::my-bucket/*" },
       { "Sid": "PublicList", "Effect": "Allow", "Principal": "*",
         "Action": ["s3:ListBucket"], "Resource": "arn:aws:s3:::my-bucket",
         "Condition": { "StringLike": { "s3:prefix": ["site/blog/*"] } } }
     ] }
   ```
   CORS 规则：
   ```json
   [ { "AllowedOrigins": ["https://your-site.example.com"],
       "AllowedMethods": ["GET"], "AllowedHeaders": ["*"], "MaxAgeSeconds": 3000 } ]
   ```
4. 之后**新增文章 = 往桶里传一个文件**（如 `site/blog/xxx.html`）→ 刷新页面即出现，**不需要重新构建**。

- 条目由键推导：`link` = `href` + 去掉存储前缀的键；`type` = 扩展名；`title` = 文件名去扩展名、`-`/`_` → 空格；`date` = 对象的 `LastModified`（UTC 日期）；`excerpt`/`tags` 为空（要就自写对接函数）。
- **只列当前层、不递归**：子目录整层不列出（等价于真实 S3 的 `<CommonPrefixes>`）——需要更深目录就再加一个列表把 `dir` 指过去。
- **只认"浏览器能直接打开的文件"**：`html`/图片/`pdf` 点开就能看；`.md` 点开是纯文本（要站内渲染需要后续版本的客户端 md 渲染器）。
- **排序**：列目录本身没有有意义的顺序，预设会按**与构建期同一条规则**排（日期倒序 → 同日标题码点序 → 无日期最后）。
- **请求规模**：默认每页 1000 条、最多 3 页（`max`/`pages` 可调）；到顶还有更多会在控制台提示"还有更多条目未加载"。
- **改完文件想立刻看到**：把该列表临时写成 `"cache": "0"`（或在 DevTools 里清 `sessionStorage`）——默认有 `session-ttl`（24h 会话缓存，不跨标签/会话）。

## 5. 写自己的对接函数

```js
/* 你的 js（用页面 head 的 <script src> 或 deps 引入；函数名与 params.src 一致） */
window.我的最近N篇 = function (root, params) {
  // root   ：本列表的根元素（data-depth 可取，用于拼相对路径）
  // params ：全部 data-*（含 src/dir/pattern/fields 与你的自定义键）
  var prefix = window.SsvulList.prefixOf(root);
  return fetch('assets/data/blog/index.json')
    .then(function (r) { return r.json(); })
    .then(function (list) {
      list = list.slice(0, parseInt(params.count || '5', 10));       // 你的规则你说了算
      return { items: list.map(function (e) { return window.SsvulList.item(e, params.fields, prefix); }) };
    });
};
```

- **返回值统一形状**：`{ items: [ DOM 节点 或 HTML 字符串, … ] }`（Promise 也行）；数组为空就是空列表（要不要显示"暂无内容"由你决定）。
- 复用内置标记：`SsvulList.item(entry, fields, prefix)`（保证与构建期同一套类名）。
- **收尾时机**：条目装载完成后 list 会派发 **`ssvullist`** 事件（`root.addEventListener('ssvullist', fn)`）。
- **失败**：函数抛错/返回 rejected Promise → 薄壳在容器里放一条 `.list-error` 并 `console.warn`，页面不白屏。

## 6. 边界与坑

- 构建期来源**自动排除自己与 INDEX**；`ssvul:shared` 的分片是"整目录数据"，**不会排除自己**——不要把列表页放进它列出的目录里。
- 排序/截断只有构建期一份实现（客户端不排序）：所以构建期来源的顺序是固定的，客户端来源的顺序完全由你的函数决定。
- 自定义列表模板时，占位符写 `{{items}}`；**不要在模板注释里写这个字面量**（模板替换是全量 `replace`，注释里的也会被替换）。
- 组件零 JS 的判定：本页所有 `list` 实例都是 `build:*` 时，构建期不注入 `list` 的 js（只要它的 css）。
- `ssvul:s3` 的 `pattern` 作用于**键名**（含扩展名，如 `*.html`/`*.pdf`），与构建期来源的 `pattern`（作用于**页面类型**：`*`/`*.md`/`*.json`）**不是同一套语义**，别混用。
- `ssvul:s3` 必须联网：`file://` 直开不可用；`offline=1` 的站点会收到一条"运行时需要联网"的构建警告（**每页最多一条**）；桶权限/CORS 配错时列表位置显示「加载失败」，具体 http 状态在控制台。
- 列目录得到的 `link` 是**绝对 URL、永不本地化**（不参与 `offline` 镜像，也不走 `调用名/…` 替换）。因此 `offline=1` 的站点里，列目录页的产物**仍会带 `data-bk-href` / `data-bk-endpoint` 两个绝对 URL**——这是运行时列目录与点开所必需的，不算"未镜像的引用违规"（未镜像检查只针对 `调用名/…` 引用）。
- `params` 里以 **`bk-`** 开头的键是构建期注入的保留名（产物上是 `data-bk-*`），手写会直接报错。

## 7. 官方预设的约定参数

| 预设 | 约定参数 | 说明 |
|---|---|---|
| `build:page-index` | `dir` / `pattern` / `fields` / `empty` | 构建期直接渲染；`empty` = 0 条时显示的文案 |
| `ssvul:inline` | `fields` / `empty` | 读页面内 `.list-data` |
| `ssvul:shared` | `dir` / `fields` / `empty` | fetch `assets/data/list/<dir>.json`（dir 空 → `index.json`） |
| `ssvul:json` | **`file`（必填）** / `fields` / `empty` | fetch 你自己维护的 JSON 文件路径（相对站点根、`/` 开头或 `http(s)://`） |
| `ssvul:s3` | `bucket` / `dir` / `pattern` / `max` / `pages` / `cache` / `fields` / `empty` | 实时列桶目录（S3 兼容协议）。桶信息（`endpoint`/`href`/`prefix`/TTL）由构建期从 `Environment.config` 解析后注入 `data-bk-*`，作者不用重复写 |

- `bucket`：选哪个桶（站点只有一个时可省；多个必须指定，否则构建期报错）。
- `cache`：本次的会话缓存秒数，覆盖站点的 `session-ttl`；`0` = 本次不读也不写缓存。
- `max`（默认 1000，S3 上限）/ `pages`（默认 3）：请求规模安全阀。

GitHub API 预设、手工清单"逐篇取数"、客户端 md 渲染器**留待后续版本**（届时一并记入 `history.md`）。
