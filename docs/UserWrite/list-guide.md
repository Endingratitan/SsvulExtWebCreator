# list 编写指南（SsvulExtWebCreator）

> 面向站点作者：`list` 列表组件的对外契约、五种数据来源，以及"**只上传文件就更新**"的做法。
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

## 2. `src` 五种取值（从最静态到最动态）

| `src` | 谁渲染 | 构建期做什么 | 之后怎么更新 | `file://` |
|---|---|---|---|---|
| `build:page-index`（**默认**） | 构建期 | 直接产出条目 HTML，**不注入 list 的 js** | 重建 | ✓ |
| `ssvul:inline` | 客户端 | 把条目内联进页面（`.list-data`）+ 注入预设 js | 重建 | ✓ |
| `ssvul:shared` | 客户端 | 按目录发射分片 `assets/data/list/<dir>.json` + 注入预设 js | 重建，**或只上传那个分片** | ✗ |
| `ssvul:json` | 客户端 | **什么都不生成**，只注入预设 js | **只上传那个 JSON 文件** | ✗ |
| `<你的函数名>`（裸名） | 客户端 | 只写 `data-src` | 改你的 js（用 `head`/`deps` 引入） | ✗ |

`ssvul:*` 是官方预设，构建期**按名**解析 → 把 `src/assets/list/<名>.js` 按需复制到 `output/assets/pre/list/` → 自动注入 `<script>`。作者不需要知道文件路径。

## 3. 条目形状（构建期与客户端完全一致）

```json
{ "link": "pages/blog/post/", "type": "md", "title": "博文",
  "date": "2026-09-07", "excerpt": "纯文本摘要…", "tags": "cs,web" }
```

- `link` 是站点根相对路径（客户端按 `data-depth` 自动补 `../`；构建期由替换趟解析并校验目标存在）。
- 标题与摘要里的行内标记（`**粗**`、链接、图片）在**索引阶段已净化成纯文本**。
- 引擎不再附带 `text` 全文（列表用不到）。

## 4. 四个配方

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

## 7. 官方预设的约定参数（截至 0.3.1）

| 预设 | 约定参数 | 说明 |
|---|---|---|
| `build:page-index` | `dir` / `pattern` / `fields` / `empty` | 构建期直接渲染；`empty` = 0 条时显示的文案 |
| `ssvul:inline` | `fields` / `empty` | 读页面内 `.list-data` |
| `ssvul:shared` | `dir` / `fields` / `empty` | fetch `assets/data/list/<dir>.json`（dir 空 → `index.json`） |
| `ssvul:json` | **`file`（必填）** / `fields` / `empty` | fetch 你自己维护的 JSON 文件路径（相对站点根、`/` 开头或 `http(s)://`） |

预设名单的扩充（手工"清单"文件逐篇取数、对象存储 list API、GitHub API、客户端 md 渲染器）**留待后续版本**，届时会同时记入 `history.md`。
