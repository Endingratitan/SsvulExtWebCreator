/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Project: https://github.com/Endingratitan/SsvulExtWebCreator
 * Copyright (c) 2026 Endingratitan
 */
package io.github.endingratitan.Integra.MarkdownIntergra;

import java.util.*;

/**
 * 块级解析**派发器**（包内私有）：逐行判定块类型，交给对应处理器。
 *
 * 本类只做三件事：派发（renderBlocks）、段落合并（renderParagraph）、兜底一行（renderFallbackLine）。
 * 具体块类型拆到各自文件，便于单独阅读与修改：
 * <ul>
 *   <li>{@link MdCodeBlocks} 围栏代码（code-ui 外壳）与块数学</li>
 *   <li>{@link MdQuotes} 引用块与 callout（登记在 {@link Callouts}）</li>
 *   <li>{@link MdLists} 有序/无序/任务列表（子视图递归）</li>
 *   <li>{@link MdTables} 表格</li>
 *   <li>{@link MdBlockScan} "这一行是什么"的判定辅助</li>
 * </ul>
 * owner 为 MarkdownRenderer（共享模式/错误/行内），fn 为脚注子系统（定义行掩码与就地占位）。
 */
class MdBlocks {

    /** **嵌套深度上限（引用/callout 与列表共用同一档）**：到达该层数后
     *  simple 警告一次并继续渲染（深度计数不再影响产物）、strict 报错（列表降级为普通段落，不丢内容）。
     *  0.3.1：引用/callout 由 2 放宽到 8，列表由「simple 软 4/硬 6、strict 2」统一为 8。
     *  实测该上限只影响"警告/报错阈值与深度计数"，不影响能否渲染——所以真正约束可读性的是 CSS 缩进累积。 */
    static final int NEST_LIMIT = 8;

    private final MarkdownRenderer owner;
    private final MdFootnotes fn;
    private final MdBlockScan scan;
    private final MdCodeBlocks code;
    private final MdQuotes quotes;
    private final MdLists lists;
    private final MdTables tables;

    MdBlocks(MarkdownRenderer owner, MdFootnotes fn) {
        this.owner = owner;
        this.fn = fn;
        this.scan = new MdBlockScan(owner, fn);
        this.code = new MdCodeBlocks(owner);
        this.tables = new MdTables(owner);
        this.quotes = new MdQuotes(owner, this);   // 引用/列表的子视图递归回到本类的 renderBlocks
        this.lists = new MdLists(owner, this);
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
            if (MdBlockScan.isFence(t))          { i = code.renderFenced(out, lines, i, end, base); continue; }
            if (t.startsWith(">"))               { i = quotes.renderQuote(out, lines, i, end, listDepth, quoteDepth, base); continue; }
            if (MdBlockScan.isListStart(t))      { i = lists.renderList(out, lines, i, end, listDepth, quoteDepth, base); continue; }
            if (MdTables.isTableAhead(lines, i, end)) { i = tables.renderTable(out, lines, i, end, base); continue; }
            if (MdBlockScan.isMathOpen(t) || MdBlockScan.isMathOpenBracket(t)) {
                i = code.renderMathBlock(out, lines, i, end, base); continue;
            }
            int h = MdBlockScan.headingLevel(t);
            if (h > 0)               { renderHeading(out, t, h, base + i + 1); i++; continue; }
            String hr = MdBlockScan.hrType(t);
            if (hr != null)          { out.append("<hr class=\"").append(hr).append("\">\n"); i++; continue; }
            if (MdBlockScan.isHtmlBlock(t)) {
                if (owner.isStrict()) { owner.error(base + i + 1, "strict 模式不支持 HTML", "请改用 div.raw 或模板", t); i++; continue; }
                out.append(lines.get(i)).append('\n');   // simple：原生 HTML 直出
                i++; continue;
            }
            i = renderParagraph(out, lines, i, end, base);
        }
    }

    /** 标题：锚点 id 自动编号（h2 起入目录），空标题仅警告（GitHub 容忍） */
    private void renderHeading(StringBuilder out, String t, int level, int lineNo) {
        String content = t.substring(level).trim();
        content = content.replaceFirst("[ \t]*#+[ \t]*$", "");
        if (content.isEmpty()) owner.warn(lineNo, "标题为空（# 后无内容）");   // 两模式仅警告
        out.append("<h").append(level).append(" id=\"").append(owner.anchorId(++owner.headingSeq)).append("\">")
           .append(owner.inline(content, lineNo, 0))
           .append("</h").append(level).append(">\n");
        if (owner.tocOn && level >= 2) owner.recordHeading(level, content, owner.headingSeq);
    }

    /** 兜底：把当前行按普通段落渲染并消费一行（保证索引前进，避免死循环）；列表超限降级也复用它 */
    int renderFallbackLine(StringBuilder out, List<String> lines, int i, int end, int base) {
        if (i >= end) return i;
        String t = MarkdownRenderer.stripIndent(lines.get(i));
        out.append("<p>").append(owner.inline(t, base + i + 1, 0)).append("</p>\n");
        return i + 1;
    }

    /** 段落：连续非空行合并（行尾两个以上空格 = 硬换行），遇到任何块起点即结束 */
    private int renderParagraph(StringBuilder out, List<String> lines, int start, int end, int base) {
        StringBuilder sb = new StringBuilder("<p>");
        int i = start;
        boolean first = true;
        boolean hardPrev = false;
        while (i < end) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) break;
            if (scan.isBlockStartAt(lines, i, end)) break;
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
}
