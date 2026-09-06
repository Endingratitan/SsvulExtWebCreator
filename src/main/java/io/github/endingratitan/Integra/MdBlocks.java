/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * 项目来源: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra;

import java.util.*;

/**
 * 块级解析器（包内私有）：段落/标题/围栏/引用/列表（子视图递归，围栏与表格逃逸）/表格/数学块/分隔线。
 * owner 为 MarkdownRenderer（共享模式/错误/行内），fn 为脚注子系统（定义行掩码与就地占位）。
 */
class MdBlocks {

    private final MarkdownRenderer owner;
    private final MdFootnotes fn;

    MdBlocks(MarkdownRenderer owner, MdFootnotes fn) {
        this.owner = owner;
        this.fn = fn;
    }

    // lines：当前视图（已剥外层前缀）；行号 = base + i + 1

    void renderBlocks(StringBuilder out, List<String> lines, int listDepth, int quoteDepth, int base) {
        int i = 0, end = lines.size();
        while (i < end) {
            if (lines == owner.srcLines && fn.defLineIdx.containsKey(i)) {
                if (fn.inlineFn) fn.emitInlineDefMarker(out, fn.defLineIdx.get(i));   // inline 模式：定义处占位
                i++;
                continue;
            }
            String t = MarkdownRenderer.stripIndent(lines.get(i));
            if (t.isEmpty()) { i++; continue; }
            if (isFence(t))            { i = renderFenced(out, lines, i, end, base); continue; }
            if (t.startsWith(">"))     { i = renderQuote(out, lines, i, end, listDepth, quoteDepth, base); continue; }
            if (isListStart(t))        { i = renderList(out, lines, i, end, listDepth, quoteDepth, base); continue; }
            if (isTableAhead(lines, i, end)) { i = renderTable(out, lines, i, end, base); continue; }
            if (isMathBlock(t))        { out.append("<div class=\"md-math-block\">").append(MarkdownRenderer.esc(t)).append("</div>\n"); i++; continue; }
            int h = headingLevel(t);
            if (h > 0)                 { renderHeading(out, t, h, base + i + 1); i++; continue; }
            if (isHr(t))               { out.append("<hr>\n"); i++; continue; }
            if (isDashLine(t)) {
                if (owner.isStrict()) { owner.error(base + i + 1, "不支持 --- 分隔线", "请改用 ***", t); i++; continue; }
                out.append("<hr>\n"); i++; continue;
            }
            if (isHtmlBlock(t)) {
                if (owner.isStrict()) { owner.error(base + i + 1, "strict 模式不支持 HTML", "请改用 div.raw 或模板", t); i++; continue; }
                out.append(lines.get(i)).append('\n');   // simple：原生 HTML 直出
                i++; continue;
            }
            i = renderParagraph(out, lines, i, end, base);
        }
    }

    /** 兜底：把当前行按普通段落渲染并消费一行（保证索引前进，避免死循环） */
    private int renderFallbackLine(StringBuilder out, List<String> lines, int i, int end, int base) {
        if (i >= end) return i;
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        out.append("<p>").append(owner.inline(t, base + i + 1, 0)).append("</p>\n");
        return i + 1;
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
            sb.append(owner.inline(line, base + i + 1, 0));
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
        out.append("<h").append(level).append(" id=\"s").append(++owner.headingSeq).append("\">")
           .append(owner.inline(content, lineNo, 0))
           .append("</h").append(level).append(">\n");
        if (owner.tocOn && level >= 2) owner.recordHeading(level, content, owner.headingSeq);
    }

    private int renderFenced(StringBuilder out, List<String> lines, int i, int end, int base) {
        String t = MarkdownRenderer.stripIndent(lines.get(i));
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
            String lt = MarkdownRenderer.stripIndent(lines.get(j));
            if (!lt.isEmpty() && lt.charAt(0) == fc) {
                int k = 0;
                while (k < lt.length() && lt.charAt(k) == fc) k++;
                if (k >= len && lt.substring(k).trim().isEmpty()) { closed = true; j++; break; }
            }
            if (content.length() > 0) content.append('\n');
            content.append(lines.get(j));
            j++;
        }
        if (!closed && owner.isStrict()) owner.error(base + i + 1, "未闭合的代码块", "请在末尾补上 " + fc, t);
        String rendered;
        try {
            rendered = MarkdownRenderer.codeEngine.renderCode(content.toString(), lang);
        } catch (RuntimeException e) {
            owner.error(base + i + 1, "代码引擎渲染失败: " + e.getMessage(), "该块已按纯文本输出", lang);
            rendered = MarkdownRenderer.esc(content.toString());
        }
        out.append("<pre class=\"md-pre\"><code class=\"md-code");
        if (!lang.isEmpty()) out.append(" language-").append(lang);
        out.append("\">").append(rendered).append("</code></pre>\n");
        return j;
    }

    private int renderQuote(StringBuilder out, List<String> lines, int start, int end,
                            int listDepth, int quoteDepth, int base) {
        if (quoteDepth >= 2) {
            owner.error(base + start + 1, "引用嵌套超过两层", "请压缩层级", lines.get(start).trim());
            return start + 1;
        }
        List<String> sub = new ArrayList<>();
        int i = start;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                int j = i;
                while (j < end && lines.get(j).trim().isEmpty()) j++;
                if (j < end && MarkdownRenderer.stripIndent(lines.get(j)).startsWith(">")) { sub.add(""); i = j; continue; }
                break;
            }
            String t = MarkdownRenderer.stripIndent(l);
            if (!t.startsWith(">")) {
                owner.error(base + i + 1, "引用块内每行需以 > 开头", "请补 > 或以空行结束引用块", t);
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
        int levels = listDepth + 1;
        if (owner.isStrict()) {
            if (levels > 2) {
                owner.error(base + i + 1, "列表嵌套超过两层", "请压缩列表层级", MarkdownRenderer.stripIndent(lines.get(i)).trim());
                return renderFallbackLine(out, lines, i, end, base);
            }
        } else {
            if (levels > 6) return renderFallbackLine(out, lines, i, end, base);   // 超 6 层不再渲染嵌套
            if (levels > 4) owner.warn(base + i + 1, "列表嵌套已到第 " + levels + " 层（软上限 4），请考虑压缩层级");
        }
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        boolean ordered = Character.isDigit(t.charAt(0));
        int col = listContentCol(t);
        out.append(ordered ? "<ol>\n" : "<ul>\n");
        while (i < end) {
            while (i < end && lines.get(i).trim().isEmpty()) i++;
            if (i >= end) break;
            String cur = MarkdownRenderer.stripIndent(lines.get(i));
            if (!isListStart(cur)) break;
            if (Character.isDigit(cur.charAt(0)) != ordered || listContentCol(cur) != col) break;
            i = renderListItem(out, lines, i, end, col, listDepth, quoteDepth, base);
        }
        out.append(ordered ? "</ol>\n" : "</ul>\n");
        return i;
    }

    private int renderListItem(StringBuilder out, List<String> lines, int i, int end, int col,
                               int listDepth, int quoteDepth, int base) {
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        String rawContent = t.substring(Math.min(col, t.length()));
        String content = rawContent.trim();
        boolean task = false, checked = false;
        if (content.matches("^\\[[ xX]\\]([ \t].*)?$")) {
            task = true;
            checked = content.charAt(1) == 'x' || content.charAt(1) == 'X';
        }
        String firstRaw = task ? rawContent.replaceFirst("^\\[[ xX]\\][ \t]*", "") : rawContent;
        out.append("<li");
        if (task) out.append(" class=\"md-task\"");
        out.append(">");
        if (task) out.append("<input type=\"checkbox\" disabled").append(checked ? " checked" : "").append("> ");
        int firstIdx = i;
        i++;
        // 续行收集为子视图（段落/嵌套列表/引用/标题等；围栏与表格触发"逃逸"交给主循环）
        List<String> sub = new ArrayList<>();
        sub.add(firstRaw);
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                int j = i;
                while (j < end && lines.get(j).trim().isEmpty()) j++;
                if (j < end && MarkdownRenderer.leadingSpaces(lines.get(j)) >= col) {
                    for (int k = i; k < j; k++) sub.add("");   // 逐行加入保行号
                    i = j;
                    continue;
                }
                break;
            }
            if (MarkdownRenderer.leadingSpaces(l) < col) break;
            String stripped = l.substring(Math.min(col, l.length()));
            String inner = MarkdownRenderer.stripIndent(stripped);
            if (isFence(inner) || isTableAhead(lines, i, end)) break;   // 逃逸：交给主循环
            sub.add(stripped);   // 保留相对缩进，嵌套层级判定不被抹平
            i++;
        }
        if (!sub.isEmpty()) {
            out.append('\n');
            renderBlocks(out, sub, listDepth + 1, quoteDepth, base + firstIdx);
        }
        out.append("</li>\n");
        return i;
    }

    private int renderTable(StringBuilder out, List<String> lines, int i, int end, int base) {
        List<String> header = tableCells(lines.get(i));
        List<String> delim = tableCells(lines.get(i + 1));
        if (delim.size() != header.size() && owner.isStrict()) {
            owner.error(base + i + 2, "表格分隔行列数与表头不一致", null, lines.get(i + 1));
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
              .append(owner.inline(header.get(k).trim(), base + i + 1, 0)).append("</th>\n");
        sb.append("</tr>\n</thead>\n<tbody>\n");
        int j = i + 2;
        while (j < end && !lines.get(j).trim().isEmpty() && lines.get(j).indexOf('|') >= 0) {
            List<String> cells = tableCells(lines.get(j));
            if (cells.size() != header.size() && owner.isStrict()) {
                owner.error(base + j + 1, "表格行列数与表头不一致（" + cells.size() + " vs " + header.size() + "）",
                        null, lines.get(j));
            }
            sb.append("<tr>\n");
            int m = Math.min(cells.size(), header.size());
            for (int k = 0; k < m; k++)
                sb.append("<td").append(alignCls(align[k])).append(">")
                  .append(owner.inline(cells.get(k).trim(), base + j + 1, 0)).append("</td>\n");
            sb.append("</tr>\n");
            j++;
        }
        sb.append("</tbody>\n</table>\n</div>\n");
        out.append(sb);
        return j;
    }

    // ---- 判定辅助 ----

    private boolean isBlockStartAt(List<String> lines, int i, int end) {
        if (lines == owner.srcLines && fn.defLineIdx.containsKey(i)) return true;
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        if (t.isEmpty()) return true;
        return isFence(t) || t.startsWith(">") || isListStart(t) || isTableAhead(lines, i, end)
                || isMathBlock(t) || headingLevel(t) > 0 || isHr(t) || isDashLine(t) || isHtmlBlock(t);
    }

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
}
