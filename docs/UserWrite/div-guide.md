# div 编写指南（SsvulExtWebCreator）

> 本文档面向站点作者：如何正确编写自己的 div 组件，以及如何继承内置/自建 div。
> 运行时契约（SsvulDiv）与内置 div 清单见文末。

## 1. div 是什么

div = 一个目录，等于"可复用的页面组件"。三种形态：

```
sets/divs/<div名>/                ← 你的 div（categories=0 时直接一层；=1 时多一层分类目录）
├── template.html                 ← HTML 模板（可选；无模板则内容/子 div 直接输出）
├── xxx.js / xxx.css              ← 行为与样式（可多个，按文件名排序串联）
├── .global                       ← 标记：js/css 生成独立文件 assets/js/<div名>.js，页面用 deps 的 global:<div名> 引用
├── .adds                         ← 标记：js/css 聚合进全站共享的 web_global.js/css
├── .extends                      ← 标记文件：内容一行 = 父 div 名（继承）
└── .contract                     ← 契约文件（继承模式 + required 钩子，见 §6）
```

页面里使用：`"type": "div名"`。解析顺序：sets/divs 优先 → 官方预设 src/assets/divs 兜底。

## 2. 模板与占位符

| 占位符 | 含义 |
|---|---|
| `{{content}}` | 本 div 的 raw/markdown 渲染结果（无模板时自动追加在末尾） |
| `{{children}}` | 嵌套子 div 的渲染结果（无模板时自动追加） |
| `{{footnotes}}` | md 脚注区（footnote-display=end 时；无此占位符则自动并入 content） |
| `{{参数名}}` | params 里声明的任意参数（见 §3） |

模板根元素建议与 div 名呼应（外层包装 div 自动带 `ssvul-<div名>` 类与 `data-depth` 属性）。

## 3. params（参数）

- 页面 json 里 `"params": {"key": 值}` → 模板 `{{key}}` 替换 + 渲染为根元素 `data-key="值"` 属性。
- **键名禁止 content/children**（系统保留）。
- js 里读参数用 `root.getAttribute('data-key')`——模板注入与 data 属性双通道，天然防 XSS。
- `data-depth`：生成器自动注入（页面深度），js 用它拼相对路径：重复 depth 次 `'../'`。

## 4. js 编写约定（核心）

**原则：只注册，不执行。** 加载期零副作用，DOMContentLoaded 后由运行时统一 init：

```js
window.SsvulDiv.register('家族名', {
  init: function (doc, root) {
    // root = 本 div 实例的根元素（外层包装 div）
    // doc = document
  }
});
```

- **覆写（继承场景）**：子 div 用同名 register——Object.assign 合并、子键覆盖父键。
- **子调父**：`var parent = window.SsvulDiv.super('家族名', 'init'); if (parent) parent(doc, root);`
- **完全让位**：`register('家族名', null)` 清除钩子；或模板/组件声明自己的接管方式（如 list 的 `src` 指向你自己的函数）。
- **家族注册名 = 基类名**：navbar/topbar 都注册 `'bar'`——继承链上的覆写才能正确链接。
- **声明式覆写**（不推荐但可用）：`function f(){}`/`var x = …` 同名重声明——串联后后者胜（构建期还会自动清除父的同名函数实现 = "覆写即替换"）。
- **禁止**：`let/const/class` 同名重声明（串联后 SyntaxError，构建期直接报错）。

## 5. css 编写约定

- 串联即级联：子 div 的 css 排在父后面 → **同选择器后到即胜**：构建期 CssDeduper 会把**前一条同选择器规则整块删除**。这是省流量的**既定特性，不是等价变换**。
  - 因此子 div 若用与父相同的选择器，**必须把需要的声明全部重写**：父规则里没被覆盖的属性会一起消失。
    例：父 `.ssvul-card{color:red;margin:0}` + 子 `.ssvul-card{color:blue}` → 产物只剩 `.ssvul-card{color:blue}`，`margin` 丢失。
  - 想保留父的其余声明：换一个选择器（如 `.ssvul-child`），或把要用的声明写全。
  - at-rule 例外：`@media`/`@keyframes` 等**带块** at-rule 整块保守跳过（不参与去重）；`@import`/`@charset`/`@layer a,b;` 等**无块** at-rule 以 `;` 为界。
- 选择器建议以 `.ssvul-<div名>` 开头（外层包装类），避免全局污染。
- 明暗主题：`[data-theme="dark"] .ssvul-xxx { … }`；能用变量就用 `--xxx`（自定义变量在 `:root` 与暗色块各声明一份）。
- 颜色体系建议复用 `--md-code-*`（代码 token）与自定义 `--xxx-*`。

## 6. .extends 继承规则

- `.extends` 内容 = 父 div 名（可带 category/ 前缀，sets 与预设都可作父）。
- 模板：子有则覆盖父；js/css：**串联**（根→叶，父前子后）；`.global`/`.adds`：沿链**并集**。
- 标记冲突（父 adds + 子 global）：`.contract` 首非空行写 `override` 可覆盖父标记，否则**报错**。
- 护栏：继承环/链长>8/父类型不存在 → 报错。

## 7. .contract 契约文件

```
第 1 行（非空）：可空/注释占位
第 2 行（非空）：_extend（缺省）/ _override —— 继承模式
第 3 行起：每行一个 required 钩子名（如 init）
```

- required 钩子被子的 register 置 `null` 或缺失 → 构建警告（"父产物可能丢失"）。
- 子 js 动态注册（非字面形态）→ 通用警告"无法静态核验"（漏报覆盖，宁可提醒）。

## 8. 内置 div 清单（src/assets/divs，均可 .extends）

| div | params | 家族/说明 |
|---|---|---|
| bar / navbar / topbar | brand | 导航家族（家族名=bar）：bar 基类汉堡响应式 ← navbar ← topbar sticky+滚动阴影 |
| search | placeholder、limit | 站内搜索（构建期 search-index.json，按 data-depth 取路径；fetch 依赖 http(s)，file:// 直开不可用） |
| list | src、dir、pattern、fields | 列表：`src` 决定来源与渲染时机（不写 = 构建期静态、零 JS；`ssvul:inline`/`ssvul:shared` 用官方预设；`ssvul:json` = 纯 CSR：读你自己维护的 JSON（构建期不生成数据，只上传文件就更新）；裸函数名 = 你自己的外接函数，返回 `{items:[…]}`）。**过滤/排序只有构建期一份实现**：构建期来源按日期倒序（同日按标题码点序），客户端来源不做任何排序——要"最近 N 篇/按标签/随机"就写自己的对接函数 |
| breadcrumb | root-label、separator | 路径面包屑 |
| backtotop | threshold | 回到顶部 |
| pager | current、total、base | 分页 |
| theme-switcher | themes、labels | 主题切换（localStorage ssvul-theme；按钮组四态样式 + 选中态 .active） |
| palette-picker | palettes、labels（clear=恢复初始主题） | 整页主题切换按钮组（SsvulTheme.set；选中态 .active + aria-pressed；生成器自动全量链接 md-code-*.css） |

> list 的完整契约与配方见 [list-guide.md](list-guide.md)。

## 9. 完整示例：继承内置 bar 的自定义导航

```
sets/divs/my-nav/
├── .extends          → bar
├── nav.css           → 只写差异
└── nav.js            → 覆写家族 init（super 子调父）
```

```js
window.SsvulDiv.register('bar', {
  init: function (doc, root) {
    var parent = window.SsvulDiv.super('bar', 'init');
    if (parent) parent(doc, root);          // 保留父的汉堡菜单行为
    console.log('my-nav ready');            // 追加自己的
  }
});
```

页面：
```jsonc
{ "type": "my-nav", "params": { "brand": "我的站" },
  "markdown": "[首页](@page/INDEX) [关于](@page/about)" }   // {{content}} = 链接区
```

## 10. 构建期自动优化（无需你操心）

- 聚合去重：共享祖先的 js/css 只输出一份（origin 记账）；
- 覆写消冗余：同名函数声明父实现自动清除；var 语句紧邻恒等去重；CSS 同选择器**后到即胜（前驱整块删除，父的未覆盖声明会一并丢失，见 §5）**；
- 压缩：minify 档位 2（默认）全量压缩；-1 保留被删代码注释供调试对比（Environment.config `minify`）。
