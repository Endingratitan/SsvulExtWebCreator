# SsvulExtWebCreator
## 项目目标

项目将牺牲产出物的可读性，但是编译材料是方便维护阅读的。

- 依靠json配置文件，组装html，js，css等文件，自动生成网络文件  
- md格式转html，并个性化可配置文件
- 其他

## 使用

- 构建：`java -jar ssvul-<版本>.jar build`（默认读取 sets/，输出 output/；或 `-b`）
- 初始化站点：`java -jar ssvul-<版本>.jar init [dir]`（默认生成 site/ 骨架，含正确 .gitignore）
- 子命令：`build|-b`（构建）、`init|-i`（骨架）、`preview|-p`（本地预览，v3 后续提供）、`version|-v`（版本）
- 开发运行：`.\gradlew run`（Gradle；版本号注入由 gradle 构建完成）

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
| bucket        | 外部 bucket 存储，值格式 (调用名,完整URL)，如 (bk,https://bucket.example.com)；允许多次输入；内容中以 `调用名/...` 引用（如 bk/img/logo.png），生成时替换为完整 URL |
| categories    | divs中是否需要一中间层目录作为categories，需要1，不要0，默认为0  |
| readme        | 会不会在output中添加readme等md文件，需要为1，不要为0，默认为0    |
| local-favicon | favicon目录是在项目中还是云端，0为项目，1为bucket，默认0         |
| offline       | 离线模式：1=是（bucket 引用命中 outer/ 镜像时本地替换进 assets/outer/，未命中报错）；0=否（默认，输出线上 URL；outer 目录存在时对未命中的引用发警告） |
| engine-words  | 词表扩展：`(语言,data/词表路径)` 可多条；为 simple 引擎追加关键字（行格式 `词` 或 `词:tokenid`，tokenid 见 token-map.js 的 tk 集合，缺省 kw）        |
| minify        | 优化档：**2=全开（默认**：去重+压缩）、1=去重不压缩、0=全关、-1=去重且被覆写代码以注释保留原位（调试对比）；档 ≥1 生成物用 `.min.js` 后缀        |
| minifier      | 压缩引擎：simple（默认，自编稳定实现——局部名改写+注释/空白压缩，含 eval/解构等自动降级护栏）；v3 预留 closure（Google Closure Compiler）接入          |

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
- **钩子契约**：div js 建议"只注册不执行"——`window.SsvulDiv.register(name,{init:fn})`（同名合并覆写）、`SsvulDiv.super(name,'init')`（子调父）、`initAll` 统一执行；`.contract` 其余非空行 = required 钩子，子置 null/缺失 → 构建警告（动态注册则通用警告）。

**内置 div（src/assets/divs，可 .extends 继承）**：
| div | params | 说明 |
|---|---|---|
| bar / navbar / topbar | brand | 导航家族：基类 bar（汉堡响应式）← navbar ← topbar（sticky+滚动阴影）；家族注册名=bar |
| search | placeholder、limit | 站内静态搜索（构建期生成 assets/data/search-index.json，路径按 div 的 data-depth 解析） |
| shower | dir、pattern(*.md/*.json)、count、order(date/name/random)、seed、fields、manual、shared | 数据驱动列表：默认内联 data-json（零请求、file:// 可用）；`shared:true` 改按目录分片共享索引 `assets/data/shower/<dir>.json`（dir 空 → index.json；只发射声明的目录，同目录多 shower 共用一份字节，客户端过滤 pattern/order/count；fetch 依赖 http(s)）；window.SsvulShower 三钩子可覆写 |
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
生成器保留名：`shower/` 目录（shared shower 共享索引输出，sets/data 下占名报错）；`search-index.json` 同样由生成器输出，勿同名占位
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
| md-css                     | string   | md 主题 css（pre-assets/、bk/、http(s)://）；缺省用预设 pre-assets/md/css/md.css；无 md 内容时忽略 |
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
  - `md-options`：本 div 的 md 渲染选项（可选）：级联覆盖页面级/父 div 同名键，只作用于本 div 的 markdown；锚点/脚注 id 自动加本 div-ID 前缀
  - `raw`：任意 HTML 片段注入 {{content}}（与 markdown 二选一）
  - `attrs`：注入根元素属性（class 并入默认类 ssvul-<type>；id 禁止）
  - `divs`：嵌套子组合（同 div-ID 规则）

#### md 语法（v1 子集）
标题（# 后须空格，自动分配锚点 id：页面 div 内为 `div-ID 前缀` 形态如 `div-1-s1`（多 md div 不重复），裸 md 页面为 `s1/s2…`；`#锚点` 链接放行不校验，需写全带前缀的 id）、段落、**粗**/*斜*/_斜_（_ 两侧不得同时为字母数字）、`行内代码`、~~删除~~、
[链接](url)、![图片](url)、列表（含任务列表 - [ ]；simple 不限层、strict 两层）、引用块（每行须 >；simple 超两层压平警告、strict 两层）、围栏代码块（```lang）、
表格（:--- 对齐）、$...$ 与 \(...\) 行内数学、$$...$$ 与 \[...\] 块数学（块级支持多行）、[[key]] 按键、
分割线（***/___ 实线、--- 虚线、+++ 双线，各自独立 class 可分别定制 CSS）、<https://> 自动链接、\ 转义（含 \$ 强制输出 $）。
链接/图片 URL 白名单：http(s)://、pre-assets/、@data/、@page/（站内互链）、#锚点、bucket 调用名。

脚注：引用 `[^n]`（尊重用户编号、可重复引用）与 `[^.]`（自动补最小空位，显式编号全部占位）；
定义行 `[^n]: 内容`（文档顶层、单行、仅行内 md）与 `[.^]: 内容`（懒惰定义，按顺序配给未配对引用）；
重复定义/引用未定义报错，定义多出仅警告；定义内再写 `[^x]` 按字面（不支持嵌套，与 GitHub 一致）；
`footnote-display: end`（默认：脚注区放 md-body 末尾；div 模板可用 `{{footnotes}}` 占位符接管位置）| `inline`（定义处就地显示）。

md 渲染选项（页面 json 顶层 `md-options`，全部有默认值可不写）：
- `mode`: `simple`（默认，GitHub 兼容：--- 分隔线、原生 HTML、不限列表嵌套、未闭合围栏不报错）| `strict`（严格报错，列表嵌套限两层）
- `footnote-display`: `end`（默认，脚注统一放）| `inline`（就地显示）
- `toc`: `true` 构建期生成目录（md-body 顶部，h2~h6 入目录、h1 排除；默认 false）
div 条目内可写同名 `md-options`（可选，级联覆盖）：只覆盖写出的键，其余继承页面级（嵌套 div 继承父 div 有效值）；
只作用于该 div 自己的 markdown，其标题锚点与脚注 id 自动加该 div-ID 前缀（如 div-1-s1、div-1-fn-1）。

simple/strict 行为矩阵（⑥ 定稿；两模式支持面相同，仅容忍度不同）：

| 语法点 | simple（默认） | strict |
|---|---|---|
| 未闭合代码围栏 | 渲染到文末 | 报错"未闭合的代码块" |
| 列表嵌套 | 不限层；>4 层警告、>6 层停止展开 | ≤2 层，超出报错 |
| 表格列数不齐 | 按表头列数补齐/截断 | 报错（分隔行/数据行） |
| 分割线 `---` | `<hr class="md-hr-dash">`（虚线，可定制 CSS） | 同左 |
| 分割线 `+++` | `<hr class="md-hr-plus">`（双线，可定制 CSS） | 同左 |
| 分割线 `***`/`___` | `<hr class="md-hr-star">`（实线，可定制 CSS） | 同左 |
| 原生 HTML 行 | 透传 | 报错"请改用 div.raw" |
| 引用嵌套 | 超 2 层警告一次并压平渲染 | 超 2 层报错 |
| 引用内缺 `>` 行 | 警告，按段落继续 | 报错 |
| 块数学 `$$` 未闭合 | 报错 | 报错 |
| 行内数学未闭合 `$` | 孤 `$` 按行尾收口渲染 + 警告；`$数字`（货币）保持字面 | 报错；`$数字` 字面 |
| 空标题 | 警告 | 警告 |
| 脚注定义未被引用 | 警告 | 报错 |
| URL 白名单违反 / 空 URL | 报错 | 报错（安全不分模式） |
| 脚注引用未定义 | 报错 | 报错 |

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

#### 校验清单（配置/资产层，⑥b 定稿）

| 层 | 校验点 |
|---|---|
| Environment.config | 未知键报错；语法缺 '=' 报错；cname 必填且禁协议；bucket 格式/重复/协议校验；engine-words 语言名、路径（须 data/）、tokenid 白名单 |
| page json（schema 层） | 未知键报错；类型不符报错；div-ID 格式与重复键；params 键名禁 content/children；deps 形态白名单（http(s)://、pre-assets/、global:…）；engine 类型 |
| page json（运行时） | deps 仅 .js/.css 且重复条目去重+警告；md-js 形态与重复去重；@page/@data/md 引用目标存在性；模板未声明占位符；engine 未知名警告回退；md 引用路径形态 |
| divs 目录 | 类型 kebab-case；目录内仅 js/css/template.html/.global/.adds，未知文件报错；.global 与 .adds 互斥；global: 引用必须指向 .global div |
| global 目录 | codeui 域仅允许 CODEUI.css/CODEUI.js（多出报错）；未知域目录仅警告（未来扩展预留）；文件复制进 assets/global/codeui/ |
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
    "div-2": { "type": "article", "markdown": "@pages/about.md", "md-options": { "footnote-display": "inline" }, "divs": { "div-1": { "type": "comment" } } }
  }
}
```

### src/assets/
官方预设目录（作为 sets/ 部分目录的预设），按功能分类：
- `page/`：页面外壳 BASE.html（生成器内部使用）
- `md/`：md 相关（css 主题与调色板、js 增强：md-highlight / token-map / md-math / md-theme）
- `divs/`：预设组件（如 theme-switcher，sets/divs 未命中时回落此处）
- `lib/`：第三方 vendor（hljs、katex，附许可证文件）
预设以 `pre-assets/<路径>` 引用，对应 src/assets/<路径>；生成时按需复制到 output/assets/pre/<路径>（未引用不复制，引用缺失报错），
输出文本中的字串 `pre-assets/` 替换为按深度修正的 `assets/pre/`
可以提供已有的CSR等功能

### 示例项目
`example-sets/` 是最小示例站点（含 div 三态、theme、md 数学/高亮、bucket、站内互链、raw 文件夹）：
将其内容复制为 `sets/` 后运行程序，即可在 output/ 得到完整站点。

### 许可证
本项目以 **MPL-2.0** 发布（全文见 `LICENSE`，归属与贡献者清单见 `NOTICE`）。
使用与再分发要求：
- 必须保留各源文件头部的版权与项目来源声明，以及根目录的 `LICENSE` 与 `NOTICE`
- 对 MPL 覆盖文件的修改，须同样以 MPL-2.0 分发（文件级 copyleft）；工具生成的站点产物不受此约束，但站点内随附的预设内容（如 BASE 模板头部）需保留其来源声明
- `src/assets/lib/` 下的第三方 vendor（hljs BSD-3、katex MIT/OFL）保持各自原许可

