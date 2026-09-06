# SsvulExtWebCreator
## 项目目标
- 依靠json配置文件，组装html，js，css等文件，自动生成网络文件  
- md格式转html，并个性化可配置文件
- 其他

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

#### divs/
divs里面可有子目录作为categories，所有子目录下再有单独的div类型目录；
或者直接每个子目录作为div（categories=0）。  
div下可有JS、CSS文件与template.html模板，不能有图片、json等数据文件，数据文件可从data/引入；图片必须从bucket导入。
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
| md-js                      | string[] | md 增强 js（顺序保序；如 md-highlight.js、md-math.js）；有 md 内容时注入                           |
| deps                       | string[] | 依赖：http(s):// 外源、pre-assets/ 预设、global:<type> 显式引用 .global div                        |
| page                       | object   | 内容区：div 组合树，键为 div-ID                                                                    |  


#### page（div 组合树）

- 键名 `div-ID`（正整数 ID）；按 ID 数值升序组装，允许跳号，同页重复 ID 报错；div-ID 渲染为根元素 id，子 div 按 div-2-1 拼接
- 每个 div 对象：
  - `type`（必填）：对应 sets/divs/ 下目录（categories=1 时写作 category/divname），未命中回落 src/assets/divs/
  - `params`：注入模板 {{key}} 并渲染为根元素 data-key 属性；键名禁止 content/children
  - `markdown`：内联 md；或以 @pages/...、@data/... 引用 md 文件 → 转 html 注入 {{content}}
  - `raw`：任意 HTML 片段注入 {{content}}（与 markdown 二选一）
  - `attrs`：注入根元素属性（class 并入默认类 ssvul-<type>；id 禁止）
  - `divs`：嵌套子组合（同 div-ID 规则）

#### md 语法（v1 子集）
标题（# 后须空格，自动分配锚点 id s1/s2…；`#锚点` 链接放行不校验）、段落、**粗**/*斜*/_斜_（_ 两侧不得同时为字母数字）、`行内代码`、~~删除~~、
[链接](url)、![图片](url)、两层列表（含任务列表 - [ ]）、引用块（每行须 >，递归，两层）、围栏代码块（```lang）、
表格（:--- 对齐）、$...$ 行内 / $$...$$ 块数学、[[key]] 按键、*** 分隔线、<https://> 自动链接、\ 转义（含 \$ 强制输出 $）。
链接/图片 URL 白名单：http(s)://、pre-assets/、@data/、@page/（站内互链）、#锚点、bucket 调用名。

脚注：引用 `[^n]`（尊重用户编号、可重复引用）与 `[^.]`（自动补最小空位，显式编号全部占位）；
定义行 `[^n]: 内容`（文档顶层、单行、仅行内 md）与 `[.^]: 内容`（懒惰定义，按顺序配给未配对引用）；
重复定义/引用未定义报错，定义多出仅警告；定义内再写 `[^x]` 按字面（不支持嵌套，与 GitHub 一致）；
`footnote-display: end`（默认：脚注区放 md-body 末尾；div 模板可用 `{{footnotes}}` 占位符接管位置）| `inline`（定义处就地显示）。

md 渲染选项（页面 json 顶层 `md-options`，全部有默认值可不写）：
- `mode`: `simple`（默认，GitHub 兼容：--- 分隔线、原生 HTML、不限列表嵌套、未闭合围栏不报错）| `strict`（严格报错，列表嵌套限两层）
- `footnote-display`: `end`（默认，脚注统一放）| `inline`（就地显示）
- `toc`: `true` 构建期生成目录（md-body 顶部，h2~h6 入目录、h1 排除；默认 false）
未识别的块级语法、未闭合代码块、--- 分隔线、HTML 行 → 报错（带行号与修复建议）。

#### 特殊文件
- `sets/pages/INDEX.json`：特判输出到 output/index.html（name 键必须为 INDEX）
- 裸 md 文件（pages 下无同名 json）：自动成为最简页面（BASE.html + md 渲染 + 预设 md.css）

#### 示例
```jsonc
{
  "name": "about",
  "theme": "light",
  "deps": ["pre-assets/lib/katex/katex.min.css", "pre-assets/lib/katex/katex.min.js"],
  "md-js": ["pre-assets/md/js/md-math.js", "pre-assets/md/js/md-highlight.js"],
  "page": {
    "div-1": { "type": "navbar", "params": { "brand": "Ssvul" } },
    "div-2": { "type": "article", "markdown": "@pages/about.md", "divs": { "div-1": { "type": "comment" } } }
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

