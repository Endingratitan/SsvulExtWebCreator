package io.github.endingratitan.Integra;

import java.util.*;

/**
 * Markdown 渲染器（v1 子集，规则见设计定稿）。
 *
 * 块级：标题(# 须空格/≤3 前导空格/尾随#)、段落、列表(两层/任务项)、引用块(每行须 >、递归、两层)、
 *       围栏代码块(```/~~~，未闭合报错)、表格(列数校验、对齐类)、$$块数学、*** 或 ___ 分隔线。
 * 行内：\ 转义、`code`、$math$、强调(***、**、__、*、_，_ 两侧不得同时为字母数字)、~~删除~~、
 *       [链接](url) / ![图](url)（白名单：http(s)://、pre-assets/、@data/、@page/、#、名字/）、
 *       [[key]] kbd、<https://> 自动链接。未闭合行内标记一律按普通文本。
 * 严格项：--- 分隔线、HTML 块、列表项内复杂块 → 报错；收集式报错（上限 20 条）一次性抛出。
 */
public class MarkdownRenderer {

    private static final int MAX_ERRORS = 20;
    private static final String PUNCT = "\\`*_{}[]()#+-.!|~><$";

    /** 代码渲染引擎（构建期上色通道），v1 默认纯转义；全局单例，适合单线程 CLI 使用 */
    private static CodeEngine codeEngine = new PassThroughCodeEngine();

    public static void setCodeEngine(CodeEngine engine) { codeEngine = Objects.requireNonNull(engine); }
    public static CodeEngine getCodeEngine() { return codeEngine; }

    /** HTML 转义（供引擎实现等复用） */
    public static String escapeHtml(String s) { return esc(s); }

    private final String source;
    private final List<String> errors = new ArrayList<>();
    private boolean overflow;

    private MarkdownRenderer(String source) { this.source = source; }

    public static String render(String md) { return render(md, "内联 md"); }

    public static String render(String md, String source) {
        return render(md, source, Collections.emptyMap());
    }

    /** v1：options 预留（div 级个性化 v2），当前忽略 */
    public static String render(String md, String source, Map<String, String> options) {
        MarkdownRenderer r = new MarkdownRenderer(source);
        String html = r.run(md == null ? "" : md);
        if (!r.errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("md 渲染错误（").append(source).append("）：\n");
            for (String e : r.errors) sb.append("  ").append(e).append('\n');
            if (r.overflow) sb.append("  ……（其余错误已省略，收集上限 ").append(MAX_ERRORS).append(" 条）\n");
            throw new RuntimeException(sb.toString());
        }
        return html;
    }

    // ==================== 入口与规范化 ====================

    private String run(String md) {
        if (md.startsWith("\uFEFF")) md = md.substring(1);              // BOM
        md = md.replace("\r\n", "\n").replace('\r', '\n');              // CRLF
        List<String> lines = new ArrayList<>(Arrays.asList(md.split("\n", -1)));
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"md-body\">\n");
        renderBlocks(out, lines, 0, 0, 0);
        out.append("</div>\n");
        return out.toString();
    }

    // ==================== 块级解析 ====================
    // lines：当前视图（已剥外层前缀）；行号 = base + i + 1

    private void renderBlocks(StringBuilder out, List<String> lines, int listDepth, int quoteDepth, int base) {
        int i = 0, end = lines.size();
        while (i < end) {
            String t = stripIndent(lines.get(i));
            if (t.isEmpty()) { i++; continue; }
            if (isFence(t))            { i = renderFenced(out, lines, i, end, base); continue; }
            if (t.startsWith(">"))     { i = renderQuote(out, lines, i, end, listDepth, quoteDepth, base); continue; }
            if (isListStart(t))        { i = renderList(out, lines, i, end, listDepth, quoteDepth, base); continue; }
            if (isTableAhead(lines, i, end)) { i = renderTable(out, lines, i, end, base); continue; }
            if (isMathBlock(t))        { out.append("<div class=\"md-math-block\">").append(esc(t)).append("</div>\n"); i++; continue; }
            int h = headingLevel(t);
            if (h > 0)                 { renderHeading(out, t, h, base + i + 1); i++; continue; }
            if (isHr(t))               { out.append("<hr>\n"); i++; continue; }
            if (isDashLine(t))         { error(base + i + 1, "v1 不支持 --- 分隔线", "请改用 ***", t); i++; continue; }
            if (isHtmlBlock(t))        { error(base + i + 1, "v1 不支持 HTML", "请改用 div.raw 或模板", t); i++; continue; }
            i = renderParagraph(out, lines, i, end, base);
        }
    }

    private int renderParagraph(StringBuilder out, List<String> lines, int start, int end, int base) {
        StringBuilder sb = new StringBuilder("<p>");
        int i = start;
        boolean first = true;
        boolean hardPrev = false;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) break;
            if (isBlockStartAt(lines, i, end)) break;
            String line = l.replaceAll("[ \t]+$", "");
            if (!first) sb.append(hardPrev ? "<br>\n" : "\n");
            sb.append(inline(line, base + i + 1, 0));
            hardPrev = l.matches(".*[ \t]{2,}$");
            first = false;
            i++;
        }
        sb.append("</p>\n");
        out.append(sb);
        return i;
    }

    private void renderHeading(StringBuilder out, String t, int level, int lineNo) {
        String content = t.substring(level).trim();
        content = content.replaceFirst("[ \t]*#+[ \t]*$", "");
        out.append("<h").append(level).append('>')
           .append(inline(content, lineNo, 0))
           .append("</h").append(level).append(">\n");
    }

    private int renderFenced(StringBuilder out, List<String> lines, int i, int end, int base) {
        String t = stripIndent(lines.get(i));
        char fc = t.charAt(0);
        int len = 0;
        while (len < t.length() && t.charAt(len) == fc) len++;
        String lang = "";
        String info = t.substring(len).trim();
        if (!info.isEmpty()) {
            String first = info.split("\\s+")[0];
            if (first.matches("^[A-Za-z0-9_+-]+$")) lang = first;
        }
        int j = i + 1;
        StringBuilder content = new StringBuilder();
        boolean closed = false;
        while (j < end) {
            String lt = stripIndent(lines.get(j));
            if (!lt.isEmpty() && lt.charAt(0) == fc) {
                int k = 0;
                while (k < lt.length() && lt.charAt(k) == fc) k++;
                if (k >= len && lt.substring(k).trim().isEmpty()) { closed = true; j++; break; }
            }
            if (content.length() > 0) content.append('\n');
            content.append(lines.get(j));
            j++;
        }
        if (!closed) error(base + i + 1, "未闭合的代码块", "请在末尾补上 " + fc, t);
        String rendered;
        try {
            rendered = codeEngine.renderCode(content.toString(), lang);
        } catch (RuntimeException e) {
            error(base + i + 1, "代码引擎渲染失败: " + e.getMessage(), "该块已按纯文本输出", lang);
            rendered = esc(content.toString());
        }
        out.append("<pre class=\"md-pre\"><code class=\"md-code");
        if (!lang.isEmpty()) out.append(" language-").append(lang);
        out.append("\">").append(rendered).append("</code></pre>\n");
        return j;
    }

    private int renderQuote(StringBuilder out, List<String> lines, int start, int end,
                            int listDepth, int quoteDepth, int base) {
        if (quoteDepth >= 2) {
            error(base + start + 1, "引用嵌套超过两层", "请压缩层级", lines.get(start).trim());
            return start + 1;
        }
        List<String> sub = new ArrayList<>();
        int i = start;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                int j = i;
                while (j < end && lines.get(j).trim().isEmpty()) j++;
                if (j < end && stripIndent(lines.get(j)).startsWith(">")) { sub.add(""); i = j; continue; }
                break;
            }
            String t = stripIndent(l);
            if (!t.startsWith(">")) {
                error(base + i + 1, "引用块内每行需以 > 开头", "请补 > 或以空行结束引用块", t);
                break;
            }
            t = t.substring(1);
            if (t.startsWith(" ")) t = t.substring(1);
            sub.add(t);
            i++;
        }
        out.append("<blockquote>\n");
        renderBlocks(out, sub, listDepth, quoteDepth + 1, base + start);
        out.append("</blockquote>\n");
        return i;
    }

    private int renderList(StringBuilder out, List<String> lines, int i, int end,
                           int listDepth, int quoteDepth, int base) {
        if (listDepth >= 2) {
            error(base + i + 1, "列表嵌套超过两层", "请压缩列表层级", stripIndent(lines.get(i)).trim());
            return renderParagraph(out, lines, i, end, base);
        }
        String t = stripIndent(lines.get(i));
        boolean ordered = Character.isDigit(t.charAt(0));
        int col = listContentCol(t);
        out.append(ordered ? "<ol>\n" : "<ul>\n");
        while (i < end) {
            while (i < end && lines.get(i).trim().isEmpty()) i++;
            if (i >= end) break;
            String cur = stripIndent(lines.get(i));
            if (!isListStart(cur)) break;
            if (Character.isDigit(cur.charAt(0)) != ordered || listContentCol(cur) != col) break;
            i = renderListItem(out, lines, i, end, col, listDepth, quoteDepth, base);
        }
        out.append(ordered ? "</ol>\n" : "</ul>\n");
        return i;
    }

    private int renderListItem(StringBuilder out, List<String> lines, int i, int end, int col,
                               int listDepth, int quoteDepth, int base) {
        String t = stripIndent(lines.get(i));
        String rawContent = t.substring(Math.min(col, t.length()));
        String content = rawContent.trim();
        boolean task = false, checked = false;
        String first = content;
        if (content.matches("^\\[[ xX]\\]([ \t].*)?$")) {
            task = true;
            checked = content.charAt(1) == 'x' || content.charAt(1) == 'X';
            first = content.length() >= 3 ? content.substring(3).trim() : "";
        }
        out.append("<li");
        if (task) out.append(" class=\"md-task\"");
        out.append(">");
        if (task) out.append("<input type=\"checkbox\" disabled").append(checked ? " checked" : "").append("> ");
        boolean hardPrev = rawContent.matches(".*[ \t]{2,}$");
        if (!first.isEmpty()) out.append(inline(first, base + i + 1, 0));
        i++;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                int j = i;
                while (j < end && lines.get(j).trim().isEmpty()) j++;
                if (j < end && leadingSpaces(lines.get(j)) >= col) { i = j; continue; }
                break;
            }
            if (leadingSpaces(l) < col) break;
            String inner = stripIndent(l.substring(Math.min(col, l.length())));
            if (isListStart(inner)) {
                out.append('\n');
                i = renderListAt(out, lines, i, end, col, listDepth + 1, quoteDepth, base);
                continue;
            }
            if (isFence(inner) || inner.startsWith(">") || headingLevel(inner) > 0 || isHr(inner)
                    || isDashLine(inner) || isMathBlock(inner) || isHtmlBlock(inner)
                    || isTableAhead(lines, i, end)) {
                error(base + i + 1, "列表项内仅支持文本与嵌套列表", "请将该块移出列表", inner);
                i++;
                continue;
            }
            String line = inner.replaceAll("[ \t]+$", "");
            out.append(hardPrev ? "<br>\n" : "\n").append(inline(line, base + i + 1, 0));
            hardPrev = inner.matches(".*[ \t]{2,}$");
            i++;
        }
        out.append("</li>\n");
        return i;
    }

    /** 收集嵌套列表行（剥 col 前缀）作为子视图渲染 */
    private int renderListAt(StringBuilder out, List<String> lines, int i, int end, int col,
                             int listDepth, int quoteDepth, int base) {
        List<String> sub = new ArrayList<>();
        int subBase = base + i;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) break;
            if (leadingSpaces(l) < col) break;
            sub.add(l.substring(Math.min(col, l.length())));
            i++;
        }
        StringBuilder sb = new StringBuilder();
        renderList(sb, sub, 0, sub.size(), listDepth, quoteDepth, subBase);
        out.append(sb);
        return i;
    }

    private int renderTable(StringBuilder out, List<String> lines, int i, int end, int base) {
        List<String> header = tableCells(lines.get(i));
        List<String> delim = tableCells(lines.get(i + 1));
        if (delim.size() != header.size()) {
            error(base + i + 2, "表格分隔行列数与表头不一致", null, lines.get(i + 1));
        }
        String[] align = new String[header.size()];
        for (int k = 0; k < header.size() && k < delim.size(); k++) {
            String d = delim.get(k).trim();
            boolean l = d.startsWith(":"), r = d.endsWith(":");
            align[k] = (l && r) ? "md-al-c" : l ? "md-al-l" : r ? "md-al-r" : "md-al-l";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"md-table-wrap\">\n<table class=\"md-table\">\n<thead>\n<tr>\n");
        for (int k = 0; k < header.size(); k++)
            sb.append("<th").append(alignCls(align[k])).append(">")
              .append(inline(header.get(k).trim(), base + i + 1, 0)).append("</th>\n");
        sb.append("</tr>\n</thead>\n<tbody>\n");
        int j = i + 2;
        while (j < end && !lines.get(j).trim().isEmpty() && lines.get(j).indexOf('|') >= 0) {
            List<String> cells = tableCells(lines.get(j));
            if (cells.size() != header.size()) {
                error(base + j + 1, "表格行列数与表头不一致（" + cells.size() + " vs " + header.size() + "）",
                        null, lines.get(j));
            }
            sb.append("<tr>\n");
            int m = Math.min(cells.size(), header.size());
            for (int k = 0; k < m; k++)
                sb.append("<td").append(alignCls(align[k])).append(">")
                  .append(inline(cells.get(k).trim(), base + j + 1, 0)).append("</td>\n");
            sb.append("</tr>\n");
            j++;
        }
        sb.append("</tbody>\n</table>\n</div>\n");
        out.append(sb);
        return j;
    }

    // ==================== 行内解析 ====================

    private String inline(String s, int lineNo, int depth) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && PUNCT.indexOf(s.charAt(i + 1)) >= 0) {
                out.append(esc(s.charAt(i + 1)));
                i += 2;
                continue;
            }
            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i + 1) {
                    out.append("<code class=\"md-code-inline\">").append(esc(s.substring(i + 1, j))).append("</code>");
                    i = j + 1;
                    continue;
                }
            }
            if (c == '$' && mathOpen(s, i)) {
                int j = mathClose(s, i);
                if (j > 0) {
                    out.append("<span class=\"md-math\">").append(esc(s.substring(i, j + 1))).append("</span>");
                    i = j + 1;
                    continue;
                }
            }
            if (depth < 2) {
                int j = -1;
                if (s.startsWith("***", i) && i + 3 < n && !isWS(s.charAt(i + 3))
                        && (j = findClose(s, i + 3, "***", false)) >= 0) {
                    out.append("<strong><em>").append(inline(s.substring(i + 3, j), lineNo, depth + 1)).append("</em></strong>");
                    i = j + 3;
                    continue;
                }
                if (s.startsWith("**", i) && i + 2 < n && !isWS(s.charAt(i + 2))
                        && (j = findClose(s, i + 2, "**", false)) >= 0) {
                    out.append("<strong>").append(inline(s.substring(i + 2, j), lineNo, depth + 1)).append("</strong>");
                    i = j + 2;
                    continue;
                }
                if (s.startsWith("__", i) && openUnder(s, i, 2)
                        && (j = findClose(s, i + 2, "__", true)) >= 0) {
                    out.append("<strong>").append(inline(s.substring(i + 2, j), lineNo, depth + 1)).append("</strong>");
                    i = j + 2;
                    continue;
                }
                if (c == '*' && i + 1 < n && !isWS(s.charAt(i + 1))
                        && (j = findClose(s, i + 1, "*", false)) >= 0) {
                    out.append("<em>").append(inline(s.substring(i + 1, j), lineNo, depth + 1)).append("</em>");
                    i = j + 1;
                    continue;
                }
                if (c == '_' && openUnder(s, i, 1)
                        && (j = findClose(s, i + 1, "_", true)) >= 0) {
                    out.append("<em>").append(inline(s.substring(i + 1, j), lineNo, depth + 1)).append("</em>");
                    i = j + 1;
                    continue;
                }
                if (s.startsWith("~~", i) && i + 2 < n && !isWS(s.charAt(i + 2))
                        && (j = findClose(s, i + 2, "~~", false)) >= 0) {
                    out.append("<del>").append(inline(s.substring(i + 2, j), lineNo, depth + 1)).append("</del>");
                    i = j + 2;
                    continue;
                }
            }
            if (s.startsWith("![", i)) {
                int close = s.indexOf("](", i + 2);
                if (close >= 0) {
                    int e = s.indexOf(')', close + 2);
                    if (e >= 0) {
                        String alt = s.substring(i + 2, close);
                        String url = s.substring(close + 2, e).trim();
                        if (!checkUrl(url, lineNo, "图片")) { /* 报错后按普通文本继续 */ }
                        else {
                            out.append("<img class=\"md-img\" src=\"").append(esc(url))
                               .append("\" alt=\"").append(esc(alt)).append("\" loading=\"lazy\">");
                            i = e + 1;
                            continue;
                        }
                    }
                }
            }
            if (c == '[') {
                int close = s.indexOf("](", i + 1);
                if (close >= 0) {
                    int e = s.indexOf(')', close + 2);
                    if (e >= 0) {
                        String url = s.substring(close + 2, e).trim();
                        if (checkUrl(url, lineNo, "链接")) {
                            out.append("<a href=\"").append(esc(url)).append("\">")
                               .append(inline(s.substring(i + 1, close), lineNo, depth)).append("</a>");
                            i = e + 1;
                            continue;
                        }
                    }
                }
            }
            if (s.startsWith("[[", i)) {
                int j = s.indexOf("]]", i + 2);
                if (j >= 0) {
                    out.append("<kbd class=\"md-kbd\">").append(esc(s.substring(i + 2, j))).append("</kbd>");
                    i = j + 2;
                    continue;
                }
            }
            if (c == '<') {
                int e = s.indexOf('>', i + 1);
                if (e > i + 1) {
                    String inner = s.substring(i + 1, e);
                    if (inner.startsWith("http://") || inner.startsWith("https://")) {
                        out.append("<a href=\"").append(esc(inner)).append("\">").append(esc(inner)).append("</a>");
                        i = e + 1;
                        continue;
                    }
                }
            }
            out.append(esc(c));
            i++;
        }
        return out.toString();
    }

    private boolean checkUrl(String url, int lineNo, String kind) {
        if (url.isEmpty() || url.contains(" ") || url.contains("\t")) {
            error(lineNo, kind + " URL 不能为空或含空白", null, url);
            return false;
        }
        if (!validUrl(url)) {
            error(lineNo, kind + " URL 不符合白名单: " + snippet(url),
                    "允许: http(s)://、pre-assets/、@data/、@page/、#锚点、已配置的 bucket 调用名（如 bk/...）", url);
            return false;
        }
        return true;
    }

    /** 名字/ 形态视为潜在 bucket 引用，最终由输出替换阶段校验调用名是否已配置 */
    private static boolean validUrl(String u) {
        return u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("pre-assets/") || u.startsWith("@data/")
                || u.startsWith("@page/") || u.startsWith("#")
                || u.matches("^[A-Za-z0-9_-]+/.*");
    }

    // ==================== 判定辅助 ====================

    private static boolean isFence(String t) { return t.matches("(`{3,}|~{3,}).*"); }
    private static boolean isMathBlock(String t) { return t.startsWith("$$") && t.endsWith("$$") && t.trim().length() >= 4; }
    private static boolean isHr(String t) { return t.matches("\\*{3,}[ \t]*$") || t.matches("_{3,}[ \t]*$"); }
    private static boolean isDashLine(String t) { return t.matches("-{3,}[ \t]*$"); }

    private static boolean isHtmlBlock(String t) {
        if (!t.startsWith("<")) return false;
        if (t.length() < 2) return false;
        char c = t.charAt(1);
        return Character.isLetter(c) || c == '/' || c == '!';
    }

    private static int headingLevel(String t) {
        if (t.isEmpty() || t.charAt(0) != '#') return 0;
        int k = 0;
        while (k < t.length() && t.charAt(k) == '#') k++;
        if (k > 6) return 0;
        if (k < t.length() && t.charAt(k) != ' ' && t.charAt(k) != '\t') return 0;   // 标准：须空格
        return k;
    }

    private static boolean isListStart(String t) {
        return t.matches("^[-*+]([ \t].*)?$") || t.matches("^\\d+[.)]([ \t].*)?$");
    }

    private static int listContentCol(String t) {
        int k = 0;
        if (Character.isDigit(t.charAt(0))) {
            while (k < t.length() && Character.isDigit(t.charAt(k))) k++;
            if (k < t.length() && (t.charAt(k) == '.' || t.charAt(k) == ')')) k++;
        } else {
            k = 1;
        }
        while (k < t.length() && (t.charAt(k) == ' ' || t.charAt(k) == '\t')) k++;
        return k;
    }

    private static boolean isTableAhead(List<String> lines, int i, int end) {
        if (i + 1 >= end) return false;
        return lines.get(i).trim().contains("|") && isDelimRow(lines.get(i + 1));
    }

    private static boolean isDelimRow(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.indexOf('|') < 0 || t.indexOf('-') < 0) return false;
        for (String cell : tableCells(t)) {
            if (!cell.trim().matches("^:?-+:?$")) return false;
        }
        return true;
    }

    /** 拆分表格行，剥离首尾管道产生的空单元格 */
    private static List<String> tableCells(String line) {
        List<String> cells = splitCells(line);
        if (line.startsWith("|") && !cells.isEmpty()) cells.remove(0);
        if (line.endsWith("|") && !cells.isEmpty()) cells.remove(cells.size() - 1);
        return cells;
    }

    private boolean isBlockStartAt(List<String> lines, int i, int end) {
        String t = stripIndent(lines.get(i));
        if (t.isEmpty()) return true;
        return isFence(t) || t.startsWith(">") || isListStart(t) || isTableAhead(lines, i, end)
                || isMathBlock(t) || headingLevel(t) > 0 || isHr(t) || isDashLine(t) || isHtmlBlock(t);
    }

    private static List<String> splitCells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int k = 0; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (esc) { cur.append(ch); esc = false; continue; }
            if (ch == '\\') { esc = true; continue; }
            if (ch == '|') { cells.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        cells.add(cur.toString());
        return cells;
    }

    private static String alignCls(String a) { return "md-al-l".equals(a) ? "" : " class=\"" + a + "\""; }

    // ---- 行内判定 ----

    /** $ 开标记：仅当"前后都是边界"（行首尾/空白/$）才视为孤立（货币等普通文本），否则为数学候选 */
    private static boolean mathOpen(String s, int i) {
        boolean prevB = i == 0 || isWS(s.charAt(i - 1)) || s.charAt(i - 1) == '$';
        boolean nextB = i + 1 >= s.length() || isWS(s.charAt(i + 1)) || s.charAt(i + 1) == '$';
        return !(prevB && nextB);
    }

    /** 闭 $：仅要求前邻紧邻非空白非 $（后邻随意，行尾亦可）；跳过被 \ 转义的 $ */
    private static int mathClose(String s, int open) {
        for (int j = open + 2; j < s.length(); j++) {
            if (s.charAt(j) != '$') continue;
            if (j > 0 && s.charAt(j - 1) == '\\') continue;   // \$ 是转义，不是闭定界符
            char p = s.charAt(j - 1);
            if (!isWS(p) && p != '$') return j;
        }
        return -1;
    }

    /** '_' 开标记：两侧不能同时是字母数字，且后一字符非空白 */
    private static boolean openUnder(String s, int i, int len) {
        boolean prevAl = i > 0 && isAlnum(s.charAt(i - 1));
        boolean nextAl = i + len < s.length() && isAlnum(s.charAt(i + len));
        boolean nextNotWs = i + len < s.length() && !isWS(s.charAt(i + len));
        return nextNotWs && (!prevAl || !nextAl);
    }

    /** under=true 用 '_' 闭规则（前一字符非空白且两侧不同时为字母数字），否则用 '*' 规则（前一字符非空白） */
    private static int findClose(String s, int from, String mark, boolean under) {
        int ml = mark.length();
        for (int j = from; j + ml <= s.length(); j++) {
            if (!s.startsWith(mark, j)) continue;
            boolean prevNotWs = j == 0 || !isWS(s.charAt(j - 1));
            if (!prevNotWs) continue;
            if (under) {
                boolean prevAl = j > 0 && isAlnum(s.charAt(j - 1));
                boolean nextAl = j + ml < s.length() && isAlnum(s.charAt(j + ml));
                if (prevAl && nextAl) continue;
            }
            return j;
        }
        return -1;
    }

    private static boolean isWS(char c) { return Character.isWhitespace(c); }
    private static boolean isAlnum(char c) { return Character.isLetterOrDigit(c); }

    private static int leadingSpaces(String line) {
        int k = 0;
        while (k < line.length() && line.charAt(k) == ' ') k++;
        return k;
    }

    private static String stripIndent(String line) { return line.replaceFirst("^ {0,3}", ""); }

    // ==================== 转义与报错 ====================

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) sb.append(esc(s.charAt(k)));
        return sb.toString();
    }

    private static String esc(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            default -> String.valueOf(c);
        };
    }

    private static String snippet(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private void error(int lineNo, String msg, String suggest, String original) {
        if (errors.size() >= MAX_ERRORS) { overflow = true; return; }
        StringBuilder sb = new StringBuilder("第 ").append(lineNo).append(" 行: ").append(msg);
        if (original != null && !original.isEmpty()) sb.append("，原文: \"").append(snippet(original)).append('"');
        if (suggest != null) sb.append("；建议: ").append(suggest);
        errors.add(sb.toString());
    }
}
