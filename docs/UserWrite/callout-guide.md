# callout 编写指南（SsvulExtWebCreator）

> 面向站点作者：`> [!TYPE]` 提示块的语法、16 个内置类型，以及用 `CALLOUT.css` / `CALLOUT.<lang>.css` 扩展与覆写。
> 相关：`docs/UserWrite/div-guide.md`（div 组件）、`README.zh.md`（页面 json 与 md 语法总表）。

## 1. 语法

引用块的**首行**写 `[!类型]`（类型名大小写不敏感）：

```markdown
> [!note]
> 正文段落，可含列表、代码块、公式等任意块级内容。
```

带**自定义标题**（标题走行内解析，支持 `**粗**`、`` `代码` ``）：

```markdown
> [!tip] **发布**前检查
> 自定义标题会替换内置默认标签。
```

规则与边界：

| 情形 | 行为 |
|---|---|
| 标记在引用块**首行** | 识别为 callout |
| 标记出现在正文中间/非引用行 | 按普通文本渲染 |
| 类型名含空格、中文、符号 | 不识别（整行按字面引用渲染） |
| 只有标记、没有正文 | 构建警告「callout 无内容」，仍渲染标题 |
| 空行后紧跟下一个 `[!类型]` | **断成两个** callout（不会互相吞并） |
| 列表项内、引用嵌套内 | 同样生效 |
| 嵌套 callout（`> > [!note]`） | 生效；**内层自动去底色**（只留左边框与图标，避免色块套娃） |
| 嵌套深度上限 | 与引用同一档：**8 层**（第 9 层起 simple 警告一次仍渲染、strict 报错）。深度主要影响排版宽度——每层左内边距约 1.25em 累加，建议 ≤3 层 |
| 想写**字面** `[!note]` | 用行内代码 `` `[!note]` ``（注意：`\[` 在本项目是 LaTeX 块数学定界符，不是转义） |

产物结构（CSS/JS 定制钩子：基类 × 类型类 × `data-callout`）：

```html
<blockquote class="md-callout md-callout-note" data-callout="note">
  <p class="md-callout-title md-callout-title-default"><span class="md-callout-label">注意</span></p>
  <p>正文…</p>
</blockquote>
```

> 只有**默认标签**才带 `md-callout-title-default` 类与 `.md-callout-label` 包裹层；作者**自定义标题**直接是行内 md（两个都没有）。换语言时隐藏 `.md-callout-label`、再用 `.md-callout-title-default::after` 注入——这样自定义标题不会被追加第二段文字，图标（挂在 `::before`，`em` 尺寸）也不受影响。

## 2. 内置 16 类型（默认中文标签）

| 类型 | 类名 | 默认标签 | 图标 |
|---|---|---|---|
| NOTE | `md-callout-note` | 注意 | info |
| TIP | `md-callout-tip` | 提示 | bulb |
| IMPORTANT | `md-callout-important` | 重要 | star |
| WARNING | `md-callout-warning` | 警告 | warning |
| CAUTION | `md-callout-caution` | 小心 | caution |
| INFO | `md-callout-info` | 信息 | info |
| SUCCESS | `md-callout-success` | 成功 | check |
| QUESTION | `md-callout-question` | 疑问 | question |
| EXAMPLE | `md-callout-example` | 示例 | example |
| QUOTE | `md-callout-quote` | 引用 | quote |
| ABSTRACT | `md-callout-abstract` | 摘要 | list |
| TODO | `md-callout-todo` | 待办 | check |
| DANGER | `md-callout-danger` | 危险 | caution |
| FAILURE | `md-callout-failure` | 失败 | cross |
| BUG | `md-callout-bug` | 缺陷 | bug |
| DEBUG | `md-callout-debug` | 调试 | code |

**非内置类型不是错误**：`> [!release]` 会构建警告一次，并按扩展类型渲染——类名 `md-callout-release` 保留、标题自动回落为类型名首字母大写（`release-note` → `Release Note`）、配色落到中性兜底；随后用下面的逃生舱给它上样式即可。

## 3. 标题开关

页面 json 顶层或 div 条目里的 `md-options`（级联覆盖）：

| 键 | 值 | 说明 |
|---|---|---|
| `callout-title` | `default`（默认） | 注入内置默认标签；扩展类型注入「首字母大写」标题 |
| | `none` | 不出默认标题元素（**作者自定义标题仍生效**） |

```jsonc
{ "type": "article", "md-options": { "callout-title": "none" }, "markdown": "> [!important]\n> 没有标题，只剩色块与图标" }
```

## 4. 扩展与覆写：`sets/global/callout/`

| 文件 | 作用 |
|---|---|
| `CALLOUT.css` | 基础覆写（该站所有命中页面） |
| `CALLOUT.<lang>.css` | **语言变体**，`lang` 是占位符（`zh` / `zh-CN` / `en` / `ja` …） |

注入规则（与 `codeui` 同构）：

1. **存在即注入**，且**只注入本页出现 callout 的页面**（`hasCallout` 门控）——没有 callout 的页面零新增字节、零新增请求。
2. 排在预设 `md-callout.css` 之后 → 同名选择器**后到即胜**（不要用 `!important`）。
3. 页面 `lang` 命中变体时**只注入变体**（变体自包含）；命中顺序：完整 lang（`CALLOUT.zh-CN.css`）→ 主语言（`CALLOUT.zh.css`）→ 都未命中则用 `CALLOUT.css`。
4. 想让变体**叠加**基础覆写：在变体首行写 `@import url("CALLOUT.css");`（相对同一目录解析）。
5. 目录内只允许上述文件名（`CALLOUT.css` / `CALLOUT.<lang>.css`），多出报错。
6. 官方模板在 `src/assets/global/callout/`（**永不自动注入**），复制到 `sets/global/callout/` 后才生效。

## 5. 三个配方

```css
/* ① 新增扩展类型：视觉直接复用内置变量 —— 这就是 CSS 版"继承" */
.md-body blockquote.md-callout-release {
  --md-callout-bg: var(--md-callout-success-bg);
  --md-callout-border: var(--md-callout-success-border);
  --md-callout-fg: var(--md-callout-success-fg);
  --md-callout-icon: var(--md-callout-icon-check);
}

/* ② 覆写内置类型配色（只改要改的键；同选择器后到即胜） */
.md-body blockquote.md-callout-note { --md-callout-note-bg: #fff8e1; --md-callout-note-border: #d9a400; }

/* ③ 换标签语言：默认标签包在 .md-callout-label 里（真实文本，可复制/可读屏），
      隐藏这一层，再用 .md-callout-title-default::after 注入新文字；::before 是图标，保持不动。
      （必须限定 -default：否则作者自定义标题也会被追加一段文字） */
.md-body blockquote.md-callout-note .md-callout-label { display: none; }
.md-body blockquote.md-callout-note .md-callout-title-default::after { content: "Note"; }
```

> **两个坑（都是视觉验收实测抓到的）**：① 别用 `font-size: 0` 隐藏标题——图标是 `em` 尺寸的 `::before`，会跟着缩成 0（tip 块灯泡整块消失）；② 注入文字必须限定 `.md-callout-title-default`——否则作者自定义标题后面会多出一截变体文字。

> 为什么不把标签做成 CSS 变量（`content: var(...)`）？因为真实文本可复制、可被读屏器朗读、对 SEO 有效；生成内容不算文本。配方 ③ 的两行就是为此付的代价。

## 6. 变量契约（预设 `md-callout.css` 定义，均可覆写）

| 变量 | 含义 |
|---|---|
| `--md-callout-radius` / `--md-callout-pad` / `--md-callout-gap` | 圆角 / 内边距 / 标题与正文间距 |
| `--md-callout-<type>-bg` `-border` `-fg` | 16 个内置类型的配色（浅色基准） |
| `--md-callout-default-bg` `-border` `-fg` | 未知/扩展类型的兜底色 |
| `--md-callout-icon-info` … | 13 个图标（`info` `bulb` `star` `warning` `caution` `check` `question` `example` `quote` `list` `cross` `bug` `code`），`mask` 取 alpha，颜色跟随 `currentColor` |
| `--md-callout-bg` `-border` `-fg` `-icon` | **当前元素的取值**：基类消费它，类型规则把它指向自己的变量 |
| `.md-callout-label` / `.md-callout-title-default`（类，非变量） | 默认标签的包裹层与标题标记：换语言时隐藏前者、把 `::after` 限定到后者（作者自定义标题两者都没有） |

主题整合：`dark` / `sepia` / `green` 三套主题只在各自文件里覆盖 `--md-callout-bg` 与 `--md-callout-fg`（中性底），类型语义色由 `border` 保留——所以新增类型不必为每个主题再写一遍。

## 7. 校验与性能

- `sets/global/callout/` 仅白名单文件名，多出**报错**（同 `codeui`）。
- 非内置类型：警告一次（不阻断构建），类名保留供 CSS 实现——与 code-ui 的「未知名 item 保留在 `data-items`」同一套逃生舱哲学。
- 无 callout 的页面：不注入 `md-callout.css`，也不复制它（预设按需复制），**零新增流量**。
- 有 callout 的页面：多一个 `md-callout.css`（含 13 个 SVG data-URI，约 6KB，未压缩的预设文件）。
- 图标走 CSS `mask` + data-URI：**零请求、零 JS、换主题不换图**。
